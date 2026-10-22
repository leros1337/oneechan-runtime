package oneechan.host.platform

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * The QuickJs conformance suite ([JsSelfTest]) on Rhino. The same checks run on JavaScriptCore
 * inside the app's JVM (EmbeddedHostTests, `debug.jsSelfTest`).
 */
class JsConformanceTest {
    @TestFactory
    fun rhino(): List<DynamicTest> =
        JsSelfTest.run(RhinoJsEngineFactory).map { result ->
            DynamicTest.dynamicTest(result.name) {
                println("${result.name}: ${if (result.skipped) "skipped (${result.detail})" else if (result.passed) "ok ${result.detail.take(300)}" else "FAILED"} ${result.millis} ms")
                if (!result.passed) fail(result.detail)
            }
        }

    @Test
    fun `plain JVMs select Rhino`() {
        assertFalse(JavaScriptCoreJsEngineFactory.isAvailable)
        assertEquals("rhino", JsEngines.name)
    }

    @Test
    fun `benchmark runs`() {
        for (timing in JsSelfTest.benchmark(RhinoJsEngineFactory)) println("bench ${timing.engine} ${timing.name}: ${String.format(java.util.Locale.ROOT, "%.1f", timing.millis)} ms (${timing.runs} runs)")
    }
}
