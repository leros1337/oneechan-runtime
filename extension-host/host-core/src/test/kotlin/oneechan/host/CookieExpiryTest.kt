package oneechan.host

import android.webkit.CookieManager
import oneechan.host.rpc.CookieDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host's cookie jar deletes like Android's CookieManager: a cookie stored already expired
 * (`Max-Age=0`) removes the one it names, and expired cookies are neither sent nor read back.
 * Com-X's DLE guard clears `__guard_trust` this way, then polls getCookie for a new one; an empty
 * leftover made it "solved" at once and every request went back to the guard page.
 */
class CookieExpiryTest {
    private val host get() = TestHost.host

    @Test
    fun `Max-Age 0 through CookieManager deletes the cookie`() {
        val url = "https://guard.cookie-test.example/page"
        // The host's network (and with it the CookieManager's jar) first.
        assertTrue(host.cookies(url).isEmpty())
        val manager = CookieManager.getInstance()
        manager.setCookie(url, "__guard_trust=abc; Path=/")
        assertTrue(manager.getCookie(url).contains("__guard_trust=abc"))

        manager.setCookie(url, "__guard_trust=; Max-Age=0; Path=/")
        assertFalse(manager.getCookie(url).contains("__guard_trust"), manager.getCookie(url))
        assertTrue(host.cookies(url).none { it.name == "__guard_trust" })
    }

    @Test
    fun `an expired cookie from the app replaces and removes the stored one`() {
        val url = "https://expiry.cookie-test.example/"
        val domain = "expiry.cookie-test.example"
        host.storeCookies(url, listOf(CookieDto("session", "1", domain, "/", null, false, false, true)))
        assertEquals(listOf("1"), host.cookies(url).filter { it.name == "session" }.map { it.value })

        host.storeCookies(url, listOf(CookieDto("session", "", domain, "/", System.currentTimeMillis() - 60_000, false, false, true)))
        assertTrue(host.cookies(url).none { it.name == "session" })
    }

    @Test
    fun `live cookies are kept`() {
        val url = "https://live.cookie-test.example/"
        val domain = "live.cookie-test.example"
        val inAnHour = System.currentTimeMillis() + 3_600_000
        host.storeCookies(url, listOf(CookieDto("cf_clearance", "v", domain, "/", inAnHour, true, true, false)))
        assertEquals(listOf("v"), host.cookies(url).filter { it.name == "cf_clearance" }.map { it.value })
    }
}
