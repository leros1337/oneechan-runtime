package oneechan.host.platform

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper

object RhinoJsEngineFactory : JsEngineFactory {
    override fun create(): JsEngine = RhinoJsEngine()
}

/**
 * Pure-Java JavaScript backend (interpreted mode, no bytecode generation). It runs anywhere the JVM
 * runs, including the embedded iOS JVM, and is the reference implementation for [JsEngine].
 */
class RhinoJsEngine : JsEngine {
    private val factory =
        object : ContextFactory() {
            override fun makeContext(): Context =
                super.makeContext().apply {
                    isInterpretedMode = true
                    languageVersion = Context.VERSION_ES6
                }
        }

    // Rhino contexts are thread-bound; entering per call lets one QuickJs instance be used from
    // whichever thread the extension happens to be on.
    private val scope: ScriptableObject = factory.call { cx -> cx.initStandardObjects() } as ScriptableObject

    override fun evaluate(script: String): Any? =
        factory.call { cx ->
            toQuickJsValue(cx.evaluateString(scope, script, "<quickjs>", 1, null))
        }

    override fun set(
        name: String,
        value: Any?,
    ) {
        factory.call { _ ->
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope))
        }
    }

    override fun close() = Unit

    private fun toQuickJsValue(value: Any?): Any? =
        when (value) {
            null, is Undefined -> null
            is Wrapper -> value.unwrap()
            is Boolean -> value
            is Number -> narrowNumber(value)
            is CharSequence -> value.toString()
            is NativeArray -> toArray(value)
            else -> null
        }

    private fun narrowNumber(value: Number): Any {
        val d = value.toDouble()
        val isWhole = !d.isNaN() && !d.isInfinite() && d == Math.floor(d)
        return when {
            isWhole && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE -> d.toInt()
            isWhole && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE -> d.toLong()
            else -> d
        }
    }

    private fun toArray(array: NativeArray): Any {
        val items = (0 until array.length).map { toQuickJsValue(array.get(it.toInt(), array)) }
        if (items.isEmpty()) return IntArray(0)
        return when (items.first()) {
            is Boolean -> BooleanArray(items.size) { items[it] as Boolean }
            is Number -> numberArray(items)
            is String -> Array(items.size) { items[it] as String? }
            else -> items.toTypedArray()
        }
    }

    private fun numberArray(items: List<Any?>): Any {
        val numbers = items.map { (it as Number) }
        return when {
            numbers.all { it is Int } -> IntArray(numbers.size) { numbers[it].toInt() }
            numbers.all { it is Int || it is Long } -> LongArray(numbers.size) { numbers[it].toLong() }
            else -> DoubleArray(numbers.size) { numbers[it].toDouble() }
        }
    }
}
