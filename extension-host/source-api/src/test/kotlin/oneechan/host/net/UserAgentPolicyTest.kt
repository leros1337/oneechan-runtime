package oneechan.host.net

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserAgentPolicyTest {
    @AfterEach
    fun reset() {
        NetworkPlatform.replaceAndroidUserAgents = true
    }

    @Test
    fun `missing user agents are always replaced`() {
        assertTrue(UserAgentPolicy.shouldReplace(null))
        assertTrue(UserAgentPolicy.shouldReplace(""))
        NetworkPlatform.replaceAndroidUserAgents = false
        assertTrue(UserAgentPolicy.shouldReplace("  "))
    }

    @Test
    fun `android user agents are replaced only when the policy is on`() {
        val android = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/149.0.0.0 Mobile Safari/537.36"
        assertTrue(UserAgentPolicy.shouldReplace(android))
        assertTrue(UserAgentPolicy.shouldReplace("Mozilla/5.0 (Android 14; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"))
        NetworkPlatform.replaceAndroidUserAgents = false
        assertFalse(UserAgentPolicy.shouldReplace(android))
    }

    @Test
    fun `desktop and ios user agents are kept`() {
        assertFalse(UserAgentPolicy.shouldReplace("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"))
        assertFalse(UserAgentPolicy.shouldReplace("Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)"))
    }
}
