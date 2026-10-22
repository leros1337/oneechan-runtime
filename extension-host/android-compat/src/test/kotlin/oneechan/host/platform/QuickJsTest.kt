package oneechan.host.platform

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The QuickJs overlay on the default engine (Rhino in a plain JVM), with Android's value mapping. */
class QuickJsTest {
    private fun eval(script: String): Any? = QuickJs.create().use { it.evaluate(script) }

    @Test
    fun `scalars map to QuickJs java types`() {
        assertEquals(3, eval("1 + 2"))
        assertEquals(2.5, eval("5 / 2"))
        // quickjs-android has no Long: numbers outside int32 are doubles.
        assertEquals(4_294_967_296.0, eval("Math.pow(2, 32)"))
        assertEquals("ab", eval("'a' + 'b'"))
        assertEquals(true, eval("1 < 2"))
        assertNull(eval("undefined"))
        assertNull(eval("null"))
        assertNull(eval("({a: 1})"))
    }

    @Test
    fun `arrays map to Object arrays`() {
        assertEquals(listOf<Any?>(1, 2, 3), (eval("[1, 2, 3]") as Array<*>).toList())
        assertEquals(listOf<Any?>(1.5, 2), (eval("[1.5, 2]") as Array<*>).toList())
        assertEquals(listOf<Any?>("x", "y"), (eval("['x', 'y']") as Array<*>).toList())
        assertEquals<Class<*>>(Array<Any?>::class.java, eval("['x', 'y']")!!.javaClass)
        assertEquals(listOf<Any?>(), (eval("[]") as Array<*>).toList())
    }

    @Test
    fun `es6 syntax used by obfuscated sources works`() {
        assertEquals("c,b,a", eval("const f = (xs) => [...xs].reverse(); f(['a','b','c']).join(',')"))
        assertEquals(42, eval("let {a} = {a: 42}; a"))
    }

    @Test
    fun `state persists across evaluations`() {
        QuickJs.create().use { js ->
            js.evaluate("var counter = 1")
            js.evaluate("counter += 41")
            assertEquals(42, js.evaluate("counter"))
        }
    }

    @Test
    fun `script errors surface as QuickJsException with the JS message`() {
        assertEquals("boom", assertThrows<QuickJsException> { eval("throw new Error('boom')") }.message)
        assertThrows<QuickJsException> { eval("this is not javascript") }
    }

    @Test
    fun `compile reports syntax errors and execute runs the result`() {
        QuickJs.create().use { js ->
            assertEquals(7, js.execute(js.compile("3 + 4", "x.js")))
            assertThrows<QuickJsException> { js.compile("this is not javascript", "x.js") }
        }
    }

    @Test
    fun `scripts cannot reach Java`() {
        assertEquals("undefined,undefined", eval("typeof Packages + ',' + typeof java"))
    }
}
