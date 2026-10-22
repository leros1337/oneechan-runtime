package oneechan.host.platform

import app.cash.quickjs.QuickJsException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A Java object exposed to JavaScript by QuickJs.set: the methods of [type], an interface that
 * extends no other and has no overloads, checked and converted as quickjs-android does. Engines
 * define a global object [name] whose methods call [invoke] with the JS arguments converted by
 * [JsValues] rules ([JsValues.Unsupported] for values Java has no counterpart for).
 *
 * Errors behave as on Android: a wrong argument count is a JS RangeError ([JsError]); any other
 * exception (an argument that does not convert, or one thrown by the method) is a JS error the
 * script may catch and, if it escapes the evaluation, is rethrown to the caller unchanged.
 */
class JsHostObject(
    val name: String,
    type: Class<*>,
    private val target: Any,
) {
    val methods: List<Method>

    init {
        if (!type.isInterface) throw UnsupportedOperationException("Only interfaces can be bound. Received: $type")
        if (type.interfaces.isNotEmpty()) throw UnsupportedOperationException("$type must not extend other interfaces")
        if (!type.isInstance(target)) throw IllegalArgumentException("${target.javaClass} is not an instance of $type")
        methods = JsBindings.methodsOf(type)
        for (method in methods) {
            method.parameterTypes.forEach(JsBindings::checkSupported)
            JsBindings.checkSupported(method.returnType)
            method.isAccessible = true
        }
    }

    val methodNames: Array<String> get() = Array(methods.size) { methods[it].name }

    /** Calls method [index] with converted JS arguments; returns its result for JS ([JsValues.UNDEFINED] for void). */
    fun invoke(
        index: Int,
        args: Array<Any?>,
    ): Any? {
        val method = methods[index]
        val types = method.parameterTypes
        val fixed = if (method.isVarArgs) types.size - 1 else types.size
        if (args.size < fixed || (!method.isVarArgs && args.size > fixed)) throw JsError("RangeError", "Wrong number of arguments")
        val javaArgs = arrayOfNulls<Any?>(types.size)
        for (i in 0 until fixed) javaArgs[i] = JsBindings.toJava(args[i], types[i], boxed = false)
        if (method.isVarArgs) javaArgs[fixed] = JsBindings.toJava(args.copyOfRange(fixed, args.size), types[fixed], boxed = false)
        val result =
            try {
                method.invoke(target, *javaArgs)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        return if (method.returnType == Void.TYPE) JsValues.UNDEFINED else JsBindings.toJs(result)
    }
}

/** QuickJs.set/get conversions shared by the engines (quickjs-android's Context converters). */
object JsBindings {
    private val supported =
        setOf<Class<*>>(
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java, Integer.TYPE, Integer::class.java,
            java.lang.Double.TYPE, java.lang.Double::class.java, String::class.java, Any::class.java, Void.TYPE,
        )

    fun methodsOf(type: Class<*>): List<Method> {
        val byName = LinkedHashMap<String, Method>()
        for (method in type.methods) {
            if (byName.put(method.name, method) != null) throw UnsupportedOperationException("${method.name} is overloaded in $type")
        }
        return byName.values.toList()
    }

    fun checkSupported(type: Class<*>) {
        if (type.isArray) return checkSupported(type.componentType)
        if (type !in supported) throw IllegalArgumentException("Unsupported Java type ${type.name}")
    }

    /** A converted JS value as a parameter of [type]; [boxed] as for QuickJs.get results (lenient conversions). */
    fun toJava(
        value: Any?,
        type: Class<*>,
        boxed: Boolean,
    ): Any? {
        if (type.isArray) {
            if (value == null) return null
            val items = value as? Array<*> ?: throw IllegalArgumentException("Cannot convert value ${JsValues.describe(value)} to ${type.simpleName}")
            val element = type.componentType
            val out = java.lang.reflect.Array.newInstance(element, items.size)
            for (i in items.indices) java.lang.reflect.Array.set(out, i, toJava(items[i], element, boxed = !element.isPrimitive))
            return out
        }
        return when (type) {
            String::class.java ->
                when (value) {
                    null, is String -> value
                    else -> throw IllegalArgumentException("Cannot convert value ${JsValues.describe(value)} to String")
                }
            java.lang.Double::class.java -> if (value == null) null else toNumber(value)
            java.lang.Double.TYPE ->
                if (boxed && value != null) {
                    toNumber(value)
                } else {
                    (value as? Number)?.toDouble() ?: throw IllegalArgumentException("Cannot convert value ${JsValues.describe(value)} to double")
                }
            Integer::class.java -> if (value == null) null else toInt32(toNumber(value))
            Integer.TYPE ->
                if (boxed && value != null) {
                    toInt32(toNumber(value))
                } else {
                    value as? Int ?: throw IllegalArgumentException("Cannot convert value ${JsValues.describe(value)} to int")
                }
            java.lang.Boolean::class.java -> if (value == null) null else truthy(value)
            java.lang.Boolean.TYPE ->
                if (boxed && value != null) {
                    truthy(value)
                } else {
                    value as? Boolean ?: throw IllegalArgumentException("Cannot convert value ${JsValues.describe(value)} to boolean")
                }
            Any::class.java -> plain(value)
            Void.TYPE -> null
            else -> throw IllegalArgumentException("Unsupported Java type ${type.name}")
        }
    }

    /** A host method's result as a value engines hand to JS: null, Boolean, Number, String or Object[] of those. */
    fun toJs(value: Any?): Any? =
        when (value) {
            null, is Boolean, is Int, is Double, is String -> value
            is Number -> value.toDouble()
            is Char -> value.toString()
            else -> JsValues.boxedArray(value)?.let { items -> Array(items.size) { toJs(items[it]) } } ?: throw IllegalArgumentException("Unsupported Java type ${value.javaClass.name}")
        }

    /** As `Object` QuickJS converts with toJavaObject, which refuses unsupported values. */
    private fun plain(value: Any?): Any? =
        when (value) {
            is JsValues.Unsupported -> throw JsValues.cannotMarshal(value.description)
            is Array<*> -> Array(value.size) { plain(value[it]) }
            else -> value
        }

    private fun toNumber(value: Any): Double =
        when (value) {
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            is String -> value.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
            else -> Double.NaN
        }

    private fun toInt32(value: Double): Int = if (value.isNaN() || value.isInfinite()) 0 else value.toLong().toInt()

    private fun truthy(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
            is String -> value.isNotEmpty()
            else -> true
        }
}

/**
 * QuickJs.get: a Java proxy of [type] whose methods call the methods of the JavaScript global
 * [name], checked as quickjs-android does. Calls go through [engine] as small scripts, with the
 * Java arguments as literals and the results converted per the method's return type.
 */
object JsProxies {
    fun <T> create(
        engine: () -> JsEngine,
        name: String,
        type: Class<T>,
    ): T {
        if (!type.isInterface) throw UnsupportedOperationException("Only interfaces can be proxied. Received: $type")
        if (type.interfaces.isNotEmpty()) throw UnsupportedOperationException("$type must not extend other interfaces")
        val methods = JsBindings.methodsOf(type)
        for (method in methods) {
            method.parameterTypes.forEach(JsBindings::checkSupported)
            JsBindings.checkSupported(method.returnType)
        }
        val global = "globalThis[${JsValues.quote(name)}]"
        val names = methods.joinToString(",", "[", "]") { JsValues.quote(it.name) }
        val status =
            engine().evaluate(
                "(function (o, names) {" +
                    " if (o === undefined) return 'missing';" +
                    " if (o === null || (typeof o !== 'object' && typeof o !== 'function')) return 'not-object';" +
                    " for (var i = 0; i < names.length; i++) { var p = o[names[i]];" +
                    "  if (typeof p !== 'function') return (p === undefined ? 'no-method:' : 'not-callable:') + names[i]; }" +
                    " return 'ok'; })($global, $names)",
                "?",
            ) as String
        when {
            status == "missing" -> throw IllegalArgumentException("A global JavaScript object called $name was not found")
            status == "not-object" -> throw IllegalArgumentException("JavaScript global called $name is not an object")
            status.startsWith("no-method:") -> throw QuickJsException("JavaScript global $name has no method called ${status.substringAfter(':')}", null)
            status.startsWith("not-callable:") -> throw QuickJsException("JavaScript property $name.${status.substringAfter(':')} not callable", null)
        }
        val handler =
            object : InvocationHandler {
                override fun invoke(
                    proxy: Any,
                    method: Method,
                    args: Array<out Any?>?,
                ): Any? {
                    if (method.declaringClass == Any::class.java) {
                        return when (method.name) {
                            "equals" -> proxy === args?.get(0)
                            "hashCode" -> System.identityHashCode(proxy)
                            else -> "QuickJsProxy{name=$name, type=${type.name}}"
                        }
                    }
                    val given = args ?: emptyArray()
                    // As QuickJS: extra arguments are dropped; a varargs array is spread.
                    val values = ArrayList<String>()
                    val count = minOf(given.size, method.parameterCount)
                    for (i in 0 until count) {
                        if (method.isVarArgs && i == count - 1) {
                            JsValues.boxedArray(given[i])?.forEach { values += JsValues.literal(it) } ?: values.add(JsValues.literal(given[i]))
                        } else {
                            values += JsValues.literal(given[i])
                        }
                    }
                    val result = engine().evaluate("$global[${JsValues.quote(method.name)}](${values.joinToString(",")})", "?")
                    return if (method.returnType == Void.TYPE) null else JsBindings.toJava(result, method.returnType, boxed = true)
                }
            }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
    }
}
