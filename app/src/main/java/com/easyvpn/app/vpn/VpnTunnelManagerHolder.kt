package com.easyvpn.app.vpn

import android.content.Context

/**
 * GoBackend tracks which tunnel is currently up as PRIVATE INSTANCE STATE
 * (currentTunnel / currentTunnelHandle) -- not something a fresh GoBackend
 * instance can discover from the OS by itself. Creating a brand new
 * VpnTunnelManager (and therefore a brand new GoBackend) every time a
 * different part of the app needed one -- MainActivity, the notification's
 * Disconnect action, the diagnostics screen -- meant disconnect() calls from
 * anywhere except the ORIGINAL instance that actually brought the tunnel up
 * had no real handle to tear down. That's exactly why tapping Disconnect
 * from the notification looked like it did nothing: VpnActionReceiver's own
 * fresh GoBackend had currentTunnelHandle = -1 the whole time, regardless of
 * what syncStateFromSystem() told our own wrapper about the OS-level state.
 *
 * One shared instance for the whole app process fixes this at the root.
 */
object VpnTunnelManagerHolder {
    @Volatile private var instance: VpnTunnelManager? = null

    fun get(context: Context): VpnTunnelManager {
        return instance ?: synchronized(this) {
            instance ?: VpnTunnelManager(context.applicationContext).also { instance = it }
        }
    }
}
