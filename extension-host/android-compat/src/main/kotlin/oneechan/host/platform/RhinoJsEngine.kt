package oneechan.host.platform

import app.cash.quickjs.QuickJsException
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.SerializableCallable
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import java.math.BigInteger

object RhinoJsEngineFactory : JsEngineFactory {
    override val name: String = "rhino"

    override fun create(): JsEngine = RhinoJsEngine()
}

/**
 * Pure-Java JavaScript backend (interpreted mode, no bytecode generation). It runs anywhere the JVM
 * runs and is the fallback when the app's JavaScriptCore natives are not registered (desktop host,
 * plain JVM tests); slow on the embedded interpreter-only JVM. The global scope has the standard
 * objects only: no LiveConnect, so scripts from websites cannot reach Java (QuickJS cannot either).
 */
class RhinoJsEngine : JsEngine {
    private val factory =
        object : ContextFactory() {
            override fun makeContext(): Context =
                super.makeContext().apply {
                    isInterpretedMode = true
                    languageVersion = Context.VERSION_ES6
                    // Interpreted frames live on the heap: bound runaway recursion before memory.
                    maximumInterpreterStackDepth = MAX_STACK_DEPTH
                }
        }

    // Rhino contexts are thread-bound; entering per call lets one QuickJs instance be used from
    // whichever thread the extension happens to be on.
    private val scope: ScriptableObject = factory.call { cx -> cx.initSafeStandardObjects() } as ScriptableObject

    /** A host method's exception, and the JS error that carries it, while a script runs. */
    private var hostFailure: Throwable? = null
    private var hostError: EcmaError? = null

    @Volatile private var closed = false

    @Synchronized
    override fun evaluate(
        script: String,
        fileName: String,
    ): Any? = run { cx -> toJava(cx.evaluateString(scope, script, fileName, 1, null), 0) }

    @Synchronized
    override fun compile(
        script: String,
        fileName: String,
    ) {
        run { cx -> cx.compileString(script, fileName, 1, null) }
    }

    @Synchronized
    override fun bind(host: JsHostObject) {
        run { cx ->
            if (ScriptableObject.hasProperty(scope, host.name)) throw IllegalArgumentException("A global object called ${host.name} already exists")
            val target = cx.newObject(scope)
            host.methods.forEachIndexed { index, method ->
                val function =
                    LambdaFunction(scope, method.name, method.parameterCount, SerializableCallable { cx2, _, _, args -> callHost(cx2, host, index, args) })
                ScriptableObject.putProperty(target, method.name, function)
            }
            ScriptableObject.putProperty(scope, host.name, target)
        }
    }

    override fun close() {
        closed = true
    }

    private fun <T> run(block: (Context) -> T): T {
        check(!closed) { "QuickJs is closed" }
        hostFailure = null
        hostError = null
        @Suppress("UNCHECKED_CAST")
        return factory.call { cx ->
            try {
                block(cx)
            } catch (e: EcmaError) {
                val failure = hostFailure
                if (failure != null && e === hostError) throw failure
                throw QuickJsException(e.errorMessage, e)
            } catch (e: JavaScriptException) {
                throw QuickJsException(thrownMessage(e.value), e)
            } catch (e: WrappedException) {
                throw e.wrappedException
            } catch (e: EvaluatorException) {
                throw QuickJsException(e.details(), e)
            } catch (e: RhinoException) {
                throw QuickJsException(e.details(), e)
            } catch (e: StackOverflowError) {
                throw QuickJsException("stack overflow", e)
            } finally {
                hostFailure = null
                hostError = null
            }
        } as T
    }

    private fun callHost(
        cx: Context,
        host: JsHostObject,
        index: Int,
        args: Array<Any?>,
    ): Any? {
        val result =
            try {
                host.invoke(index, Array(args.size) { toJava(args[it], -1) })
            } catch (e: JsError) {
                throw ScriptRuntime.constructError(e.type, e.message)
            } catch (e: Throwable) {
                // As QuickJS: scripts see an InternalError; if it escapes, the caller gets e itself.
                val error = ScriptRuntime.constructError("InternalError", "Java Exception")
                hostFailure = e
                hostError = error
                throw error
            }
        return toRhino(cx, result)
    }

    /** As the message QuickJS reports: the thrown value's `message`, else the value itself. */
    private fun thrownMessage(value: Any?): String {
        if (value is Scriptable) {
            val message = ScriptableObject.getProperty(value, "message")
            if (message != Scriptable.NOT_FOUND && message !is Undefined) return ScriptRuntime.toString(message)
        }
        return ScriptRuntime.toString(value)
    }

    /** [depth] 0 for a completion value, > 0 inside its arrays; < 0 for a host method argument and its arrays. */
    private fun toJava(
        value: Any?,
        depth: Int,
    ): Any? =
        when (value) {
            null, Scriptable.NOT_FOUND, is Undefined -> null
            is Boolean -> value
            is CharSequence -> value.toString()
            is BigInteger -> unsupported(value, depth)
            is Number -> JsValues.number(value.toDouble())
            is NativeArray -> {
                if (depth >= JsValues.MAX_DEPTH || -depth >= JsValues.MAX_DEPTH) throw JsValues.cannotMarshal("(nested too deeply)")
                val length = value.length
                if (length > Int.MAX_VALUE) throw JsValues.cannotMarshal(describe(value))
                val inner = if (depth < 0) depth - 1 else depth + 1
                Array(length.toInt()) { toJava(ScriptableObject.getProperty(value, it), inner) }
            }
            else -> unsupported(value, depth)
        }

    private fun unsupported(
        value: Any,
        depth: Int,
    ): Any? =
        when {
            depth == 0 -> null
            depth > 0 -> throw JsValues.cannotMarshal(describe(value))
            else -> JsValues.Unsupported(describe(value))
        }

    private fun describe(value: Any): String =
        try {
            if (value is BigInteger) value.toString() else ScriptRuntime.toString(value)
        } catch (e: RuntimeException) {
            "(null)"
        }

    private fun toRhino(
        cx: Context,
        value: Any?,
    ): Any? =
        when (value) {
            JsValues.UNDEFINED -> Undefined.instance
            is Array<*> -> cx.newArray(scope, Array(value.size) { toRhino(cx, value[it]) })
            else -> value
        }

    private companion object {
        const val MAX_STACK_DEPTH = 10_000
    }
}
