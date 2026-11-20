package dev.openbg.open_background_geolocation

import org.json.JSONObject
import java.net.URLEncoder

/** Pure token helpers (no Android dependencies), kept separate so they can be unit-tested on the JVM. */
object AuthLogic {
    /** Refresh this many seconds before the token actually expires. */
    const val EXPIRY_SKEW_SECONDS = 60L
    const val REFRESH_PLACEHOLDER = "{refreshToken}"

    data class Tokens(val accessToken: String, val refreshToken: String?, val expires: Long)

    /** True when [expires] (epoch seconds, <= 0 meaning unknown) is within the skew window of [nowSec]. */
    fun needsRefresh(expires: Long, nowSec: Long): Boolean =
        expires > 0 && nowSec + EXPIRY_SKEW_SECONDS >= expires

    /**
     * Reads a token endpoint response. Accepts `accessToken` / `access_token`, `refreshToken` /
     * `refresh_token` and either an absolute `expires` (epoch seconds or millis) or a relative
     * `expires_in` / `expires` in seconds. Returns null when no access token is present.
     * An absent expiry yields -1 (unknown).
     */
    fun parseTokenResponse(body: String, nowSec: Long): Tokens? {
        val json = try { JSONObject(body) } catch (e: Exception) { return null }
        val access = json.firstString("accessToken", "access_token") ?: return null
        val refresh = json.firstString("refreshToken", "refresh_token")
        val expires = when {
            json.has("expires_in") -> nowSec + json.optLong("expires_in")
            json.has("expires") -> absoluteSeconds(json.optLong("expires"), nowSec)
            else -> -1L
        }
        return Tokens(access, refresh, expires)
    }

    /** `expires` may be epoch millis, epoch seconds or a small relative number of seconds. */
    private fun absoluteSeconds(value: Long, nowSec: Long): Long = when {
        value > 100_000_000_000L -> value / 1000
        value > 1_000_000_000L -> value
        value > 0 -> nowSec + value
        else -> -1L
    }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> optString(k, "").takeIf { it.isNotEmpty() } }

    /** Replaces `{refreshToken}` inside string values of [template]. */
    fun substitute(template: Map<String, Any?>, refreshToken: String): Map<String, String> =
        template.mapValues { (_, v) -> v.toString().replace(REFRESH_PLACEHOLDER, refreshToken) }

    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    fun bearer(strategy: String, accessToken: String): String? =
        if (strategy.equals("JWT", ignoreCase = true) && accessToken.isNotEmpty()) "Bearer $accessToken" else null
}
