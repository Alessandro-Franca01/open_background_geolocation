package dev.openbg.open_background_geolocation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bearer-token authorization for HTTP sync. Tokens live in the `authorization` config group so
 * they survive restarts; a refreshed pair is written back there and announced with an
 * `authorization` event.
 */
object Auth {
    private const val TAG = "auth"

    private fun now() = System.currentTimeMillis() / 1000

    fun header(): String? =
        AuthLogic.bearer(
            Settings.string("authorization", "strategy"),
            Settings.string("authorization", "accessToken"),
        )

    /** True when a refresh endpoint and refresh token are configured. */
    fun canRefresh(): Boolean =
        header() != null &&
            Settings.string("authorization", "refreshUrl").isNotEmpty() &&
            Settings.string("authorization", "refreshToken").isNotEmpty()

    /** Refreshes ahead of expiry. Returns false only when a needed refresh failed. */
    @Synchronized
    fun refreshIfNeeded(): Boolean {
        if (!canRefresh()) return true
        val expires = Settings.long("authorization", "expires", -1L)
        if (!AuthLogic.needsRefresh(expires, now())) return true
        Logger.i(TAG, "access token expired or about to; refreshing")
        return refresh()
    }

    /** Exchanges the refresh token for a new pair. Blocking; call from a worker thread. */
    @Synchronized
    fun refresh(): Boolean {
        val url = Settings.string("authorization", "refreshUrl")
        val refreshToken = Settings.string("authorization", "refreshToken")
        if (url.isEmpty() || refreshToken.isEmpty()) return false
        val template = Settings.obj("authorization", "refreshPayload")?.toMap()
            ?: mapOf("refresh_token" to AuthLogic.REFRESH_PLACEHOLDER)
        val timeout = Settings.int("http", "timeout", 60000)

        var status = 0
        var text = ""
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                Settings.obj("authorization", "refreshHeaders")?.let { h ->
                    h.keys().forEach { conn.setRequestProperty(it, h.get(it).toString()) }
                }
                val body = AuthLogic.formEncode(AuthLogic.substitute(template, refreshToken))
                conn.outputStream.use { it.write(body.toByteArray()) }
                status = conn.responseCode
                val stream = if (status < 400) conn.inputStream else conn.errorStream
                text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }

            val tokens = if (status in 200..299) AuthLogic.parseTokenResponse(text, now()) else null
            if (tokens == null) {
                Logger.w(TAG, "refresh failed: HTTP $status")
                announce(false, "Refresh failed (HTTP $status)", text)
                return false
            }
            val update = mutableMapOf<String, Any?>(
                "accessToken" to tokens.accessToken,
                "expires" to tokens.expires,
            )
            // Servers that rotate refresh tokens return a new one; otherwise keep the current.
            tokens.refreshToken?.let { update["refreshToken"] = it }
            Settings.update(mapOf("authorization" to update))
            Logger.i(TAG, "token refreshed; expires=${tokens.expires}")
            announce(true, null, text)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "refresh error: ${e.message}")
            announce(false, e.message ?: "Refresh failed", text)
            false
        }
    }

    private fun announce(success: Boolean, error: String?, responseText: String) {
        val response = runCatching { JSONObject(responseText).toMap() }.getOrDefault(emptyMap())
        EventBus.emit(
            "authorization",
            mapOf("success" to success, "error" to error, "response" to response)
        )
    }
}
