package com.easyvpn.app.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.KeyPair

/** Generates the device's WireGuard key pair once, stores the private key encrypted-at-rest.
 *
 *  Uses EncryptedSharedPreferences/MasterKey, which security-crypto 1.1.0 deprecates in
 *  favor of Jetpack DataStore + Tink. Left as-is here since the deprecated APIs are still
 *  fully functional and migrating would require a data migration for already-stored keys
 *  (so existing installs don't lose their WireGuard identity) -- worth planning as its own
 *  change rather than folding it into an unrelated dependency bump. */
class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, "easyvpn_secure", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOrCreateKeyPair(): KeyPair {
        val existing = prefs.getString("priv_key", null)
        if (existing != null) return KeyPair(com.wireguard.crypto.Key.fromBase64(existing))
        val pair = KeyPair()
        prefs.edit().putString("priv_key", pair.privateKey.toBase64()).apply()
        return pair
    }

    /** Give this public key to your admin panel / server-setup script to register the client as a peer. */
    fun clientPublicKeyBase64(): String = getOrCreateKeyPair().publicKey.toBase64()
    fun clientPrivateKeyBase64(): String = getOrCreateKeyPair().privateKey.toBase64()
}
