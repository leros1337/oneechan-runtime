package oneechan.host.platform

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickJsTest {
    private fun eval(script: String): Any? = QuickJs.create().use { it.evaluate(script) }

    @Test
    fun `scalars map to QuickJs java types`() {
        assertEquals(3, eval("1 + 2"))
        assertEquals(2.5, eval("5 / 2"))
        assertEquals(4_294_967_296L, eval("Math.pow(2, 32)"))
        assertEquals("ab", eval("'a' + 'b'"))
        assertEquals(true, eval("1 < 2"))
        assertNull(eval("undefined"))
        assertNull(eval("null"))
    }

    @Test
    fun `arrays map to typed java arrays`() {
        assertContentEquals(intArrayOf(1, 2, 3), eval("[1, 2, 3]") as IntArray)
        assertContentEquals(doubleArrayOf(1.5, 2.0), eval("[1.5, 2]") as DoubleArray)
        assertEquals(listOf("x", "y"), (eval("['x', 'y']") as Array<*>).toList())
        assertContentEquals(booleanArrayOf(true, false), eval("[true, false]") as BooleanArray)
        assertContentEquals(IntArray(0), eval("[]") as IntArray)
    }

    @Test
    fun `es6 syntax used by obfuscated sources works`() {
        assertEquals("c,b,a", eval("const f = (xs) => [...xs].reverse(); f(['a','b','c']).join(',')"))
        assertEquals(42, eval("let {a} = {a: 42}; a"))
    }

    @Test
    fun `state persists across evaluations and host objects are visible`() {
        QuickJs.create().use { js ->
            js.evaluate("var counter = 1")
            js.evaluate("counter += 41")
            assertEquals(42, js.evaluate("counter"))
            js.set("host", String::class.java, "from-java")
            assertEquals("from-java!", js.evaluate("host + '!'"))
        }
    }

    @Test
    fun `script errors surface as QuickJsException`() {
        assertThrows<QuickJsException> { eval("throw new Error('boom')") }
        assertThrows<QuickJsException> { eval("this is not javascript") }
    }

    @Test
    fun `compile and execute round trip`() {
        QuickJs.create().use { js ->
            assertEquals(7, js.execute(js.compile("3 + 4", "x.js")))
        }
    }
}
