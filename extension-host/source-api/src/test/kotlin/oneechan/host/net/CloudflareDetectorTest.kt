package oneechan.host.net

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class CloudflareDetectorTest {
    @ParameterizedTest(name = "{0} server={1} cf-mitigated={2} -> {3}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            "403, cloudflare, null, true",
            "503, cloudflare, null, true",
            "429, cloudflare, null, true",
            "403, cloudflare-nginx, null, true",
            "403, Cloudflare, null, true",
            "200, cloudflare, null, false",
            "404, cloudflare, null, false",
            "403, nginx, null, false",
            "403, null, null, false",
            "200, nginx, challenge, true",
            "403, null, Challenge, true",
            "200, cloudflare, other, false",
        ],
    )
    fun `classifies challenge responses like Tachimanga`(
        code: Int,
        server: String?,
        cfMitigated: String?,
        expected: Boolean,
    ) {
        assertEquals(expected, CloudflareDetector.isChallenge(code, server, cfMitigated))
    }
}
