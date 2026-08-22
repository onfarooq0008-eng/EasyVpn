package com.easyvpn.app.vpn

import android.content.Context
import com.easyvpn.app.data.Server
import com.easyvpn.app.util.DeviceAddressUtil
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TunnelState { DOWN, CONNECTING, UP }

/**
 * Thin wrapper around the official com.wireguard.android GoBackend.
 * The client's own private key is generated once on-device and never leaves it;
 * you only need to register its PUBLIC key (and its derived tunnel address --
 * see DeviceAddressUtil) as a peer on each VPS (see server-setup/add-client.sh
 * and README for the exact command).
 */
class VpnTunnelManager(private val context: Context) {

    private val backend = GoBackend(context)
    private var currentTunnel: SimpleTunnel? = null

    var state: TunnelState = TunnelState.DOWN
        private set

    private class SimpleTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) { /* observed via manager */ }
    }

    /** Call once and persist the returned key pair (store private key securely, e.g. EncryptedSharedPreferences). */
    fun generateKeyPair(): com.wireguard.crypto.KeyPair = com.wireguard.crypto.KeyPair()

    suspend fun connect(
        server: Server,
        clientPrivateKeyBase64: String,
        excludedPackages: Set<String> = emptySet(),
        assignedAddressOverride: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            state = TunnelState.CONNECTING
            val peerPublicKey = com.wireguard.crypto.Key.fromBase64(server.serverPublicKey)

            // If a backend API assigned this device's address (see /backend), use that exact
            // value -- it's authoritative and guaranteed unique. Otherwise fall back to the
            // local hash-derived address for manual/local-only setups (see DeviceAddressUtil).
            val myAddress = assignedAddressOverride
                ?: DeviceAddressUtil.deviceAddressCidr(server.clientAddress, clientPrivateKeyBase64)

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(clientPrivateKeyBase64)
                .parseAddresses(myAddress)
                .parseDnsServers(server.dns)

            if (excludedPackages.isNotEmpty()) {
                ifaceBuilder.excludeApplications(excludedPackages)
            }

            val peerBuilder = Peer.Builder()
                .setPublicKey(peerPublicKey)
                .parseAllowedIPs("0.0.0.0/0, ::/0")
                .parseEndpoint(server.endpoint)
                .setPersistentKeepalive(25)

            if (server.presharedKey.isNotBlank()) {
                peerBuilder.parsePreSharedKey(server.presharedKey)
            }

            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()

            val tunnel = SimpleTunnel("easyvpn")
            currentTunnel = tunnel
            backend.setState(tunnel, Tunnel.State.UP, config)
            state = TunnelState.UP
            Result.success(Unit)
        } catch (e: Exception) {
            state = TunnelState.DOWN
            Result.failure(e)
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
            state = TunnelState.DOWN
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Call after (re)creating this manager (e.g. Activity restarted after being
     * backgrounded) to catch up with reality: the actual tunnel is owned by the
     * OS-level VpnService and keeps running independently of our Activity, but a
     * fresh VpnTunnelManager instance otherwise starts assuming state = DOWN
     * regardless of what's really happening. See VpnStateUtil.isSystemVpnActive.
     */
    fun syncStateFromSystem(isActive: Boolean) {
        if (isActive) {
            currentTunnel = SimpleTunnel("easyvpn") // GoBackend keys by name, so this is safe to recreate
            state = TunnelState.UP
        } else {
            currentTunnel = null
            state = TunnelState.DOWN
        }
    }

    fun statistics() = currentTunnel?.let { runCatching { backend.getStatistics(it) }.getOrNull() }
}
