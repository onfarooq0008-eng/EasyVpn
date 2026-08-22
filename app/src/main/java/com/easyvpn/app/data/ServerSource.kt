package com.easyvpn.app.data

import android.content.Context

/**
 * Single place that decides: is this app pointed at a backend API (either the
 * compiled-in default from app/build.gradle, or a per-device override set in
 * Admin Panel -> Backend API URL) or running purely off the local, manually
 * managed Admin Panel list? Everything else (home screen, country screen)
 * just calls getServers() and doesn't need to care which mode it's in.
 */
class ServerSource(context: Context) {
    private val repo = ServerRepository(context)
    private val appSettings = AppSettings(context)
    private val backendClient = BackendApiClient()

    fun isBackendMode(): Boolean = appSettings.backendApiUrl.isNotBlank()

    suspend fun getServers(): List<Server> {
        val backendUrl = appSettings.backendApiUrl
        if (backendUrl.isBlank()) return repo.getAll()
        return try {
            backendClient.fetchServers(backendUrl)
        } catch (e: Exception) {
            // Backend hiccup shouldn't make the whole app unusable -- fall back to
            // whatever's in the local list (may be empty, which is still safe).
            repo.getAll()
        }
    }

    /** Only meaningful in backend mode: registers this device and returns its assigned config. */
    suspend fun register(devicePublicKeyBase64: String, preferredServerId: String?): BackendRegistration {
        val backendUrl = appSettings.backendApiUrl
        check(backendUrl.isNotBlank()) { "Backend API URL is not configured" }
        return backendClient.register(backendUrl, devicePublicKeyBase64, preferredServerId)
    }
}
