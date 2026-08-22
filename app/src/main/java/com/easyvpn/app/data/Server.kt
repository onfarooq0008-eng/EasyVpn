package com.easyvpn.app.data

/**
 * One VPN server entry (maps to one of your VPS boxes -- add as many as you want,
 * no limit).
 *
 * endpoint       -> "your.vps.ip.address:51820"
 * serverPublicKey-> WireGuard public key of the VPS (from `wg show` on the server)
 * clientAddress  -> the SUBNET this server's clients live in, e.g. "10.8.0.0/24"
 *                    (matches setup-wireguard.sh's default). This is shared by every
 *                    device that installs the app -- each device's actual unique
 *                    address within it is derived on-device (see DeviceAddressUtil),
 *                    so two different users connecting to the same server don't both
 *                    end up claiming the same tunnel IP.
 * presharedKey   -> optional extra layer, can be blank
 * dns            -> DNS to use inside tunnel, e.g. "1.1.1.1"
 */
data class Server(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var countryName: String = "",
    var countryCode: String = "US",   // ISO 3166-1 alpha-2, used to render flag emoji
    var city: String = "",
    var endpointHost: String = "",
    var endpointPort: Int = 51820,
    var serverPublicKey: String = "",
    var presharedKey: String = "",
    var clientAddress: String = "10.8.0.0/24",
    var dns: String = "1.1.1.1",
    var maxRecommendedUsers: Int = 40, // rough capacity hint for a 1GB RAM VPS
    var enabled: Boolean = true,

    // runtime-only fields (not persisted from admin form, filled in at runtime)
    @Transient var pingMs: Int = -1,   // -1 = not tested yet, -2 = unreachable
    @Transient var isConnecting: Boolean = false
) {
    val endpoint: String get() = "$endpointHost:$endpointPort"

    fun flagEmoji(): String {
        if (countryCode.length != 2) return "🌐"
        val base = 0x1F1E6
        val first = Character.codePointAt(countryCode.uppercase(), 0) - 'A'.code + base
        val second = Character.codePointAt(countryCode.uppercase(), 1) - 'A'.code + base
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
