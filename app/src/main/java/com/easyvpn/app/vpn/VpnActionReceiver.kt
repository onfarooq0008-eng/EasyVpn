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
 * VpnTunnelManager directly rather than routing through the Activity.
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
                val tunnelManager = VpnTunnelManager(context.applicationContext)
                // A fresh manager doesn't know the tunnel is up yet -- tell it, so
                // disconnect() actually has a tunnel reference to bring down.
                tunnelManager.syncStateFromSystem(isActive = true)
                tunnelManager.disconnect()
            } finally {
                NotificationHelper.clear(context.applicationContext)
                pendingResult.finish()
            }
        }
    }
}
