package oneechan.host.platform

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * The android.graphics conformance suite ([GraphicsSelfTest]) on the AWT backend. The same checks
 * run on the native backend inside the app's JVM (EmbeddedHostTests, `debug.graphicsSelfTest`).
 */
class GraphicsConformanceTest {
    @TestFactory
    fun awt(): List<DynamicTest> =
        GraphicsSelfTest.run(AwtGraphicsBackend).map { result ->
            DynamicTest.dynamicTest(result.name) {
                println("${result.name}: ${if (result.skipped) "skipped (${result.detail})" else if (result.passed) "ok" else "FAILED"} ${result.millis} ms")
                if (!result.passed) fail(result.detail)
            }
        }

    @Test
    fun `plain JVMs select AWT`() {
        assertFalse(NativeGraphicsBackend.isAvailable)
        assertEquals("awt", Graphics.backend().name)
    }
}
