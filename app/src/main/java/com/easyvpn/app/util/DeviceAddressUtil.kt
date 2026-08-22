package com.easyvpn.app.util

import java.security.MessageDigest

/**
 * Each server's "client subnet" (e.g. 10.8.0.0/24) is shared by every device
 * that installs the app -- but each individual device still needs its OWN
 * unique address inside that subnet, or two different users connecting to
 * the same server would both claim to be e.g. 10.8.0.2 and WireGuard's
 * strict per-peer address filtering would break the connection for
 * everyone but whoever connected most recently.
 *
 * This derives a stable, unique-enough host number (2-254) from the
 * device's own private key, so the same device always gets the same
 * address on every server, and different devices get different addresses.
 * It's not a formal collision-free allocator (a real backend doing dynamic
 * IP assignment would be, and is worth adding once you have real scale --
 * see README) but for hash-based assignment across a /24 (253 usable
 * addresses) it's solid for hobby/beta-scale traffic.
 */
object DeviceAddressUtil {

    /** Returns e.g. "10.8.0.137" given subnetBase="10.8.0.0/24" (or "10.8.0.x") and this device's key. */
    fun deviceAddress(subnetCidrOrAddress: String, devicePrivateKeyBase64: String): String {
        val base = subnetBaseThreeOctets(subnetCidrOrAddress)
        val suffix = suffixForDevice(devicePrivateKeyBase64)
        return "$base.$suffix"
    }

    /** Same as [deviceAddress] but with /32 appended, ready for WireGuard's Interface.Address field. */
    fun deviceAddressCidr(subnetCidrOrAddress: String, devicePrivateKeyBase64: String): String =
        "${deviceAddress(subnetCidrOrAddress, devicePrivateKeyBase64)}/32"

    /** Deterministic 2-254 host number derived from the device's own key -- stable across app restarts. */
    fun suffixForDevice(devicePrivateKeyBase64: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(devicePrivateKeyBase64.toByteArray())
        val unsigned = digest[0].toInt() and 0xFF
        // Map into 2..254 (avoid .0/.1/.255 which are commonly reserved for network/gateway/broadcast).
        return 2 + (unsigned % 253)
    }

    /** Strips the last octet and any /CIDR suffix, e.g. "10.8.0.0/24" or "10.8.0.2/32" -> "10.8.0". */
    private fun subnetBaseThreeOctets(subnetCidrOrAddress: String): String {
        val withoutCidr = subnetCidrOrAddress.substringBefore('/')
        val octets = withoutCidr.split('.')
        return if (octets.size == 4) octets.take(3).joinToString(".") else "10.8.0"
    }
}
