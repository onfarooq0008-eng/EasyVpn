package com.easyvpn.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.easyvpn.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Disconnect" button on the persistent connected notification --
 * this can fire even if MainActivity isn't currently open, so it talks to
 * VpnTunnelManager directly rather than routing through the Activity. Uses
 * VpnTunnelManagerHolder to get the SAME shared instance the rest of the app
 * uses -- GoBackend tracks which tunnel handle is running as private instance
 * state, so a brand new GoBackend created here wouldn't actually have a real
 * handle to tear down, even after telling it the tunnel is up.
 */
class VpnActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISCONNECT = "com.easyvpn.app.ACTION_DISCONNECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISCONNECT) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tunnelManager = VpnTunnelManagerHolder.get(context)
                // Defensive fallback only -- if this is really the shared instance
                // that brought the tunnel up, its state is already correct and this
                // is a no-op. Only matters if this receiver somehow fires before
                // anything else in the app has touched the holder this process.
                tunnelManager.syncStateFromSystem(isActive = true)
                tunnelManager.disconnect()
            } finally {
                NotificationHelper.clear(context.applicationContext)
                pendingResult.finish()
            }
        }
    }
}
