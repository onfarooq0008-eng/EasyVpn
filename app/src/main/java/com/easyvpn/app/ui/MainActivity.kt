package com.easyvpn.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.easyvpn.app.R
import com.easyvpn.app.ads.AdManager
import com.easyvpn.app.admin.AdminLoginActivity
import com.easyvpn.app.data.AppSettings
import com.easyvpn.app.data.Server
import com.easyvpn.app.data.ServerSource
import com.easyvpn.app.databinding.ActivityMainBinding
import com.easyvpn.app.util.NotificationHelper
import com.easyvpn.app.util.PingUtil
import com.easyvpn.app.util.SecureKeyStore
import com.easyvpn.app.vpn.TunnelState
import com.easyvpn.app.vpn.VpnTunnelManager
import com.easyvpn.app.vpn.VpnTunnelManagerHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.easyvpn.app.util.applyEdgeToEdgeInsets

/**
 * Home screen: servers grouped by country, tap a country with 2+ servers to
 * expand it inline (no separate screen). Works in local Admin Panel mode or,
 * when a Backend API URL is set (built-in default or an override), talks to
 * your control API automatically -- see ServerSource / BackendApiClient.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverSource: ServerSource
    private lateinit var appSettings: AppSettings
    private lateinit var keyStore: SecureKeyStore
    private lateinit var tunnelManager: VpnTunnelManager
    private lateinit var adapter: HomeListAdapter

    private var allServers: List<Server> = emptyList()
    private var searchQuery: String = ""
    private var expandedCountryCodes: MutableSet<String> = mutableSetOf()
    private var pendingChain: List<Server>? = null
    private var connectedServer: Server? = null
    private var statsJob: Job? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingChain?.let { beginConnection(it) }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* fine either way -- notification is a nice-to-have, not required to use the VPN */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root)
        setSupportActionBar(binding.toolbar)

        serverSource = ServerSource(this)
        appSettings = AppSettings(this)
        keyStore = SecureKeyStore(this)
        tunnelManager = VpnTunnelManagerHolder.get(this)

        binding.recyclerServers.layoutManager = LinearLayoutManager(this)
        adapter = HomeListAdapter(
            onHeaderClick = { group -> onCountryTapped(group) },
            onServerClick = { server -> onServerTapped(server) }
        )
        binding.recyclerServers.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadAndPing() }
        binding.buttonFastest.setOnClickListener { onActionButtonTapped() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                renderRows()
            }
        })

        binding.textVersion.setOnLongClickListener {
            // Admin Panel is intentionally unreachable in release builds -- gated on
            // BuildConfig.DEBUG so the Play Store APK ships with zero UI path to it.
            // (AdminLoginActivity itself still enforces the password too, in case
            // anyone launches it directly via adb from an installed debug build --
            // defense in depth, not just security-by-obscurity.)
            if (com.easyvpn.app.BuildConfig.DEBUG) {
                startActivity(Intent(this, AdminLoginActivity::class.java))
            }
            true
        }

        requestNotificationPermissionIfNeeded()
        AdManager.loadBanner(binding.adContainer, this)

        loadAndPing(onDone = {
            // Once the server list is loaded, restore the display name for an
            // already-running VPN. The tunnel state itself is authoritative; the
            // Activity's connectedServer field is only presentation state.
            restoreConnectedServerFromSettings()
            updateStatusCard()
            updateActionButton()

            if (appSettings.autoConnectEnabled && tunnelManager.state == TunnelState.DOWN) {
                appSettings.lastConnectedServerId?.let { id ->
                    allServers.find { it.id == id }?.let { onServerTapped(it) }
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()

        // The backend tunnel state is authoritative for EasyVPN. Android's generic
        // VPN transport flag is useful as a secondary signal, but it cannot tell us
        // which VPN belongs to this app. Most importantly, never let a missing
        // Activity-level connectedServer turn an actually-running tunnel into a
        // disconnected UI state.
        val wasConnected = tunnelManager.state == TunnelState.UP
        tunnelManager.syncStateFromBackend()
        if (tunnelManager.state == TunnelState.UP) {
            restoreConnectedServerFromSettings()
            if (!wasConnected) startConnectionStats()
        } else if (wasConnected) {
            connectedServer = null
            stopConnectionStats()
        }
        updateStatusCard()
        updateActionButton()

        lifecycleScope.launch {
            val fresh = serverSource.getServers()
            fresh.forEach { s -> allServers.find { it.id == s.id }?.let { s.pingMs = it.pingMs } }
            allServers = fresh
            renderRows()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadAndPing(onDone: (() -> Unit)? = null) {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            val servers = serverSource.getServers()
            allServers = servers
            renderRows()
            val targets = servers.map { Triple(it.id, it.endpointHost, 22) }
            val results = PingUtil.pingAll(targets)
            servers.forEach { it.pingMs = results[it.id] ?: -2 }
            allServers = servers
            renderRows()
            binding.swipeRefresh.isRefreshing = false
            onDone?.invoke()
        }
    }

    private fun renderRows() {
        val query = searchQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            allServers
        } else {
            allServers.filter {
                it.name.lowercase().contains(query) ||
                    it.countryName.lowercase().contains(query) ||
                    it.city.lowercase().contains(query)
            }
        }
        val groups = CountryGroup.groupByCountry(filtered)
        val rows = mutableListOf<HomeRow>()
        groups.forEach { group ->
            val expanded = expandedCountryCodes.contains(group.countryCode)
            rows.add(HomeRow.Header(group, expanded))
            if (expanded) {
                group.servers.forEach { rows.add(HomeRow.ServerRow(it)) }
            }
        }
        adapter.submit(rows, connectedServer?.id)
        binding.emptyState.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onCountryTapped(group: CountryGroup) {
        if (group.servers.size == 1) {
            onServerTapped(group.servers.first())
            return
        }
        if (expandedCountryCodes.contains(group.countryCode)) {
            expandedCountryCodes.remove(group.countryCode)
        } else {
            expandedCountryCodes.add(group.countryCode)
        }
        renderRows()
    }

    private fun onActionButtonTapped() {
        if (tunnelManager.state == TunnelState.UP) {
            // Do not require connectedServer here. That field belongs to the Activity
            // and is lost when the Activity is recreated, while the WireGuard tunnel
            // can still be running. The notification can disconnect successfully in
            // exactly this situation, so the home button must do the same.
            lifecycleScope.launch {
                val result = tunnelManager.disconnect()
                result.onSuccess {
                    onDisconnected()
                }.onFailure {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Could not disconnect VPN: ${it.message ?: "unknown error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // Re-read the manager state so the button never lies about the
                    // actual tunnel state after a failed disconnect attempt.
                    tunnelManager.syncStateFromBackend()
                    updateStatusCard()
                    updateActionButton()
                }
            }
        } else {
            connectToFastest()
        }
    }

    private fun connectToFastest() {
        val reachable = allServers.filter { it.enabled && it.pingMs >= 0 }.sortedBy { it.pingMs }
        if (reachable.isEmpty()) {
            android.widget.Toast.makeText(this, "No reachable servers yet -- pull to refresh and try again", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        onServerTapped(reachable.first())
    }

    /** Up to 2 other reachable servers (by ping) to try automatically if the
     *  tapped one turns out not to actually pass traffic -- see doConnect(). */
    private fun buildFailoverChain(primary: Server): List<Server> {
        val fallbacks = allServers
            .filter { it.enabled && it.id != primary.id && it.pingMs >= 0 }
            .sortedBy { it.pingMs }
            .take(2)
        return listOf(primary) + fallbacks
    }

    private fun onServerTapped(server: Server) {
        if (tunnelManager.state == TunnelState.UP) {
            if (connectedServer?.id == server.id) {
                lifecycleScope.launch {
                    tunnelManager.disconnect()
                    onDisconnected()
                }
            } else {
                lifecycleScope.launch {
                    tunnelManager.disconnect()
                    onDisconnected()
                    beginConnection(buildFailoverChain(server))
                }
            }
            return
        }
        pendingChain = buildFailoverChain(server)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            beginConnection(pendingChain!!)
        }
    }

    /** Attempts chain[attemptIndex]; on failure (interface never came up, OR it came
     *  up but couldn't actually reach the internet -- see ConnectivityCheckUtil),
     *  automatically tries the next candidate instead of just failing outright. */
    private fun beginConnection(chain: List<Server>, attemptIndex: Int = 0) {
        if (attemptIndex >= chain.size) {
            connectedServer = null
            updateStatusCard()
            updateActionButton()
            android.widget.Toast.makeText(
                this,
                "Couldn't establish a working connection through any nearby server. Check your internet connection or try again shortly.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (attemptIndex == 0) {
            // Ad-supported app: show an interstitial right before the first attempt --
            // a natural pause point. Never on failover retries, that would be terrible UX.
            AdManager.maybeShowInterstitial(this) { doConnect(chain, attemptIndex) }
        } else {
            android.widget.Toast.makeText(
                this, "${chain[attemptIndex - 1].name} didn't work, trying another server…", android.widget.Toast.LENGTH_SHORT
            ).show()
            doConnect(chain, attemptIndex)
        }
    }

    private fun doConnect(chain: List<Server>, attemptIndex: Int) {
        val server = chain[attemptIndex]
        binding.textConnectionStatus.text = "Connecting…"
        binding.textConnectionSubtitle.text = "${server.flagEmoji()} ${server.name}"
        lifecycleScope.launch {
            val privateKey = keyStore.clientPrivateKeyBase64()

            var connectServer = server
            var assignedAddressCidr: String? = null

            if (serverSource.isBackendMode()) {
                try {
                    val publicKey = keyStore.clientPublicKeyBase64()
                    val reg = serverSource.register(publicKey, preferredServerId = server.id)
                    connectServer = server.copy(
                        endpointHost = reg.endpointHost,
                        endpointPort = reg.endpointPort,
                        serverPublicKey = reg.serverPublicKey,
                        dns = reg.dns
                    )
                    assignedAddressCidr = "${reg.assignedAddress}/32"
                } catch (e: Exception) {
                    tryNextOrFail(chain, attemptIndex, "Registration failed: ${e.message}")
                    return@launch
                }
            } else {
                // Production connections must use the backend allocator. The old
                // client-side hash allocator has been removed because it can collide.
                tryNextOrFail(
                    chain,
                    attemptIndex,
                    "Automatic server registration is required. Configure the Backend API in Admin Panel."
                )
                return@launch
            }

            // Apply the user's DNS preference (Settings -> DNS) on top of whatever
            // the server itself specifies -- "Server default" leaves connectServer.dns
            // untouched; any other mode substitutes the chosen resolver.
            connectServer = connectServer.copy(dns = appSettings.resolveDns(connectServer.dns))

            val result = tunnelManager.connect(
                connectServer,
                privateKey,
                excludedPackages = appSettings.excludedPackages,
                assignedAddressCidr = assignedAddressCidr!!
            )
            result.onSuccess {
                // The interface coming up doesn't prove it actually works -- verify
                // real traffic flows through it before declaring success to the user.
                val working = com.easyvpn.app.util.ConnectivityCheckUtil.verifyInternetThroughVpnWithRetries(this@MainActivity)
                if (working) {
                    connectedServer = server
                    appSettings.lastConnectedServerId = server.id
                    updateStatusCard()
                    updateActionButton()
                    startConnectionStats()
                    renderRows()
                    NotificationHelper.showConnected(this@MainActivity, "${server.flagEmoji()} ${server.name}")
                } else {
                    tunnelManager.disconnect()
                    tryNextOrFail(chain, attemptIndex, null)
                }
            }
            result.onFailure {
                tryNextOrFail(chain, attemptIndex, "Connection failed: ${it.message}")
            }
        }
    }

    private fun restoreConnectedServerFromSettings() {
        if (tunnelManager.state != TunnelState.UP || connectedServer != null) return
        appSettings.lastConnectedServerId?.let { id ->
            allServers.find { it.id == id }?.let {
                connectedServer = it
                startConnectionStats()
            }
        }
    }

    private fun tryNextOrFail(chain: List<Server>, attemptIndex: Int, errorIfLast: String?) {
        val nextIndex = attemptIndex + 1
        if (nextIndex < chain.size) {
            beginConnection(chain, nextIndex)
        } else {
            connectedServer = null
            updateStatusCard()
            updateActionButton()
            val message = errorIfLast ?: "Couldn't establish a working internet connection through any nearby server."
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun onDisconnected() {
        connectedServer = null
        updateStatusCard()
        updateActionButton()
        stopConnectionStats()
        renderRows()
        NotificationHelper.clear(this)
    }

    private fun updateStatusCard() {
        val server = connectedServer
        when {
            server != null && tunnelManager.state == TunnelState.UP -> {
                binding.textConnectionStatus.text = "Connected"
                binding.textConnectionSubtitle.text = "${server.flagEmoji()} ${server.name} • ${server.countryName}"
            }
            tunnelManager.state == TunnelState.UP -> {
                // The VPN is definitely up even when the Activity has not yet
                // recovered the server metadata. Never display "Not connected"
                // while simultaneously offering a working Disconnect action.
                binding.textConnectionStatus.text = "Connected"
                binding.textConnectionSubtitle.text = "VPN active"
            }
            else -> {
                binding.textConnectionStatus.text = "Not connected"
                binding.textConnectionSubtitle.text = "Choose a server below"
            }
        }
    }

    private fun updateActionButton() {
        if (tunnelManager.state == TunnelState.UP) {
            binding.buttonFastest.text = "Disconnect"
            binding.buttonFastest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.danger)
            )
            binding.buttonFastest.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.buttonFastest.strokeWidth = 0
        } else {
            binding.buttonFastest.text = "⚡ Fastest"
            binding.buttonFastest.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT
            )
            binding.buttonFastest.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.buttonFastest.strokeWidth = resources.displayMetrics.density.toInt()
        }
    }

    private fun startConnectionStats() {
        binding.layoutConnectionStats.visibility = View.VISIBLE
        binding.chronometerConnected.base = SystemClock.elapsedRealtime()
        binding.chronometerConnected.start()

        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                val stats = tunnelManager.statistics()
                if (stats != null) {
                    binding.textDataUsage.text =
                        "↓${formatBytes(stats.totalRx())} ↑${formatBytes(stats.totalTx())}"
                }
                delay(2000)
            }
        }
    }

    private fun stopConnectionStats() {
        binding.chronometerConnected.stop()
        binding.layoutConnectionStats.visibility = View.GONE
        binding.textDataUsage.text = "↓0 KB ↑0 KB"
        statsJob?.cancel()
        statsJob = null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}
