package oneechan.host.embedded

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BytecodeVerificationTest {
    private val openJ9 = "Eclipse OpenJ9 VM"
    private val hotSpot = "OpenJDK 64-Bit Server VM"
    private val noHotSpotFlag: () -> String? = { throw IllegalArgumentException("VM option \"BytecodeVerificationRemote\" does not exist") }

    @Test
    fun hotSpotFollowsTheDiagnosticFlag() {
        assertFalse(BytecodeVerification.remoteVerificationEnabled(hotSpot, emptyList()) { "false" })
        assertTrue(BytecodeVerification.remoteVerificationEnabled(hotSpot, emptyList()) { "true" })
        // -Xverify:none does not matter to HotSpot: only its own flag counts.
        assertTrue(BytecodeVerification.remoteVerificationEnabled(hotSpot, listOf("-Xverify:none")) { "true" })
        assertFalse(BytecodeVerification.remoteVerificationEnabled("OpenJDK 64-Bit Zero VM", emptyList()) { "false" })
    }

    @Test
    fun aHotSpotWithoutTheFlagIsAssumedToVerify() {
        assertTrue(BytecodeVerification.remoteVerificationEnabled(hotSpot, emptyList(), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled(hotSpot, listOf("-Xverify:none"), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled("Some Other VM", emptyList()) { null })
    }

    @Test
    fun openJ9FollowsItsLastVerifyOption() {
        assertFalse(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-Xint", "-Xverify:none", "-Xmx512m"), noHotSpotFlag))
        assertFalse(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-noverify"), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-Xint"), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled(openJ9, emptyList(), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-Xverify:none", "-Xverify:all"), noHotSpotFlag))
        assertTrue(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-Xverify:none", "-Xverify"), noHotSpotFlag))
        assertFalse(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-Xverify:all", "-Xverify:none"), noHotSpotFlag))
        // Look-alikes are not verification options.
        assertTrue(BytecodeVerification.remoteVerificationEnabled(openJ9, listOf("-XX:+VerifyErrorDetails", "-Xverifyfoo"), noHotSpotFlag))
    }
}
