package oneechan.host.platform

import app.cash.quickjs.QuickJsException

/**
 * How JavaScript values reach Java, as quickjs-android 0.9.2 converts them (Context::toJavaObject):
 * `undefined` and `null` become null, booleans Boolean, numbers Integer when QuickJS holds an int
 * (an int32 value, not -0) and Double otherwise, strings String, arrays an Object[] of converted
 * elements. Any other value (object, function, symbol, BigInt) is null as a completion value and a
 * QuickJsException ("Cannot marshal value … to Java") inside an array.
 *
 * QuickJS also keeps some integral results of floating-point arithmetic as doubles (`1.5 * 2` is
 * 3.0 there); JavaScriptCore and Rhino do not expose that difference, so every int32 is an Integer.
 * Strings are returned as they are in JS (QuickJS cuts them at a NUL character and replaces lone
 * surrogates, a transcoding artefact no extension relies on).
 */
object JsValues {
    /** What a `void` host method returns; JavaScript sees `undefined`. */
    @JvmField val UNDEFINED: Any = Any()

    /** Nested arrays deeper than this are refused (QuickJS would overflow its stack). */
    const val MAX_DEPTH = 512

    @JvmStatic
    fun isInt(value: Double): Boolean {
        val int = value.toInt()
        return int.toDouble() == value && (int != 0 || 1.0 / value > 0)
    }

    /** The Java value of a JavaScript number. */
    @JvmStatic
    fun number(value: Double): Any = if (isInt(value)) value.toInt() else value

    @JvmStatic
    fun cannotMarshal(description: String): QuickJsException = QuickJsException("Cannot marshal value $description to Java", null)

    /** JavaScript's Number::toString for the common cases (error messages only). */
    @JvmStatic
    fun numberToString(value: Double): String =
        when {
            value.isNaN() -> "NaN"
            value == Double.POSITIVE_INFINITY -> "Infinity"
            value == Double.NEGATIVE_INFINITY -> "-Infinity"
            isInt(value) || value == 0.0 -> value.toLong().toString()
            else -> value.toString().removeSuffix(".0")
        }

    /** ToString of a converted value, for error messages. */
    fun describe(value: Any?): String =
        when (value) {
            null -> "null"
            is Double -> numberToString(value)
            is Array<*> -> value.joinToString(",") { if (it == null) "" else describe(it) }
            is Unsupported -> value.description
            else -> value.toString()
        }

    /** A JavaScript value with no Java counterpart (an object, function, symbol), as a host method argument. */
    class Unsupported(
        val description: String,
    ) {
        override fun toString(): String = description
    }

    /**
     * [value] (a host method's result) as a JavaScript literal, for engines that hand values to
     * scripts as source. Supports what host methods may return: null, Boolean, Number, String and
     * arrays of those.
     */
    fun literal(value: Any?): String =
        when (value) {
            null -> "null"
            UNDEFINED -> "undefined"
            is Boolean -> value.toString()
            is Int, is Short, is Byte -> value.toString()
            is Number -> {
                val d = value.toDouble()
                when {
                    d.isNaN() -> "NaN"
                    d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
                    d == 0.0 && 1.0 / d < 0 -> "-0"
                    else -> d.toString()
                }
            }
            is String -> quote(value)
            is Char -> quote(value.toString())
            else -> boxedArray(value)?.joinToString(",", "[", "]") { literal(it) } ?: throw IllegalArgumentException("Unsupported Java type ${value.javaClass.name}")
        }

    /** A JavaScript string literal (JSON escaping, plus U+2028/U+2029 and lone surrogates). */
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (ch in text) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch < ' ' || ch == ' ' || ch == ' ' || ch.isSurrogate() -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    /** Primitive and object arrays as Object[] (boxed elements), anything else null. */
    @JvmStatic
    fun boxedArray(value: Any?): Array<Any?>? =
        when (value) {
            is Array<*> -> Array(value.size) { value[it] }
            is IntArray -> Array(value.size) { value[it] }
            is DoubleArray -> Array(value.size) { value[it] }
            is BooleanArray -> Array(value.size) { value[it] }
            is LongArray -> Array(value.size) { value[it] }
            is FloatArray -> Array(value.size) { value[it] }
            is ShortArray -> Array(value.size) { value[it] }
            is ByteArray -> Array(value.size) { value[it] }
            is CharArray -> Array(value.size) { value[it].toString() }
            else -> null
        }
}

/** Thrown by host-object glue for a JavaScript error of [type] that has no Java exception behind it. */
class JsError(
    val type: String,
    message: String,
) : RuntimeException(message)
