package dev.openbg.open_background_geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLogicTest {
    private val now = 1_800_000_000L

    @Test
    fun parsesCamelCaseResponseWithAbsoluteExpiry() {
        val t = AuthLogic.parseTokenResponse(
            """{"accessToken":"a1","refreshToken":"r1","expires":1800003600}""", now
        )!!
        assertEquals("a1", t.accessToken)
        assertEquals("r1", t.refreshToken)
        assertEquals(1_800_003_600L, t.expires)
    }

    @Test
    fun parsesSnakeCaseResponseWithRelativeExpiresIn() {
        val t = AuthLogic.parseTokenResponse(
            """{"access_token":"a2","refresh_token":"r2","expires_in":3600}""", now
        )!!
        assertEquals("a2", t.accessToken)
        assertEquals("r2", t.refreshToken)
        assertEquals(now + 3600, t.expires)
    }

    @Test
    fun treatsLargeExpiresAsMillisAndSmallAsRelativeSeconds() {
        assertEquals(
            1_800_003_600L,
            AuthLogic.parseTokenResponse("""{"access_token":"a","expires":1800003600000}""", now)!!.expires
        )
        assertEquals(
            now + 900,
            AuthLogic.parseTokenResponse("""{"access_token":"a","expires":900}""", now)!!.expires
        )
    }

    @Test
    fun missingExpiryIsUnknownAndMissingRefreshTokenIsNull() {
        val t = AuthLogic.parseTokenResponse("""{"access_token":"a"}""", now)!!
        assertEquals(-1L, t.expires)
        assertNull(t.refreshToken)
    }

    @Test
    fun rejectsResponsesWithoutAnAccessTokenOrNotJson() {
        assertNull(AuthLogic.parseTokenResponse("""{"refresh_token":"r"}""", now))
        assertNull(AuthLogic.parseTokenResponse("<html>502</html>", now))
        assertNull(AuthLogic.parseTokenResponse("", now))
    }

    @Test
    fun needsRefreshHonoursTheSkewAndUnknownExpiry() {
        assertFalse(AuthLogic.needsRefresh(-1, now))
        assertFalse(AuthLogic.needsRefresh(0, now))
        assertFalse(AuthLogic.needsRefresh(now + 3600, now))
        assertTrue(AuthLogic.needsRefresh(now + 30, now))
        assertTrue(AuthLogic.needsRefresh(now - 1, now))
    }

    @Test
    fun substitutesPlaceholderInPayloadValues() {
        val body = AuthLogic.substitute(
            mapOf("grant_type" to "refresh_token", "refresh_token" to "{refreshToken}"), "abc"
        )
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "abc"), body)
    }

    @Test
    fun formEncodesSpecialCharacters() {
        assertEquals(
            "a=b%26c&d=e+f",
            AuthLogic.formEncode(linkedMapOf("a" to "b&c", "d" to "e f"))
        )
    }

    @Test
    fun bearerOnlyForJwtWithAToken() {
        assertEquals("Bearer tok", AuthLogic.bearer("JWT", "tok"))
        assertEquals("Bearer tok", AuthLogic.bearer("jwt", "tok"))
        assertNull(AuthLogic.bearer("JWT", ""))
        assertNull(AuthLogic.bearer("", "tok"))
    }
}
