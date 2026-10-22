package oneechan.host.platform

import java.lang.ref.Cleaner

/**
 * app.cash.quickjs.QuickJs on JavaScriptCore through the app's JNI shim ([NativeJs]): the iOS
 * system framework, so scripts run at native speed instead of on Rhino inside the
 * interpreter-only JVM. Available when the shim registered its natives (the app, the macOS JNI
 * tests); elsewhere [JsEngines] picks Rhino.
 */
object JavaScriptCoreJsEngineFactory : JsEngineFactory {
    override val name: String = "javascriptcore"

    /** Whether the natives are registered (probed once). */
    val isAvailable: Boolean by lazy {
        try {
            NativeJs.version() >= 1
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: Throwable) {
            false
        }
    }

    internal val cleaner: Cleaner by lazy { Cleaner.create() }

    override fun create(): JsEngine = JavaScriptCoreJsEngine()

    /** Native contexts not yet released (diagnostics: dropped engines are released by a Cleaner). */
    val liveContexts: Int get() = NativeJs.liveContexts()

    /** Native stack sizes in bytes of the calling thread and of the script worker (diagnostics). */
    fun stackSizes(): LongArray = NativeJs.stackSizes()
}

/**
 * One JavaScriptCore global context in its own context group, so engines never contend for a VM
 * lock and run concurrently. Calls are serialized per engine (QuickJs is single-threaded, and this
 * keeps a racing close from freeing a running context). [close] releases the context; an engine
 * dropped open is released by a Cleaner.
 */
class JavaScriptCoreJsEngine internal constructor() : JsEngine {
    private val owner = ContextHandle(NativeJs.create().also { if (it == 0L) throw OutOfMemoryError("Cannot create a JavaScriptCore context") })
    private val cleanable = JavaScriptCoreJsEngineFactory.cleaner.register(this, owner)
    private val hosts = ArrayList<JsHostObject>()

    /** Evaluations in progress on the owning thread (a host method may close its own QuickJs). */
    private var running = 0
    private var closeRequested = false

    private val handle: Long
        get() = owner.handle.also { check(it != 0L && !closeRequested) { "QuickJs is closed" } }

    @Synchronized
    override fun evaluate(
        script: String,
        fileName: String,
    ): Any? {
        val context = handle
        running++
        try {
            return NativeJs.evaluate(context, script, fileName, this)
        } finally {
            if (--running == 0 && closeRequested) cleanable.clean()
        }
    }

    @Synchronized
    override fun compile(
        script: String,
        fileName: String,
    ) = NativeJs.compile(handle, script, fileName)

    @Synchronized
    override fun bind(host: JsHostObject) {
        if (!NativeJs.bind(handle, host.name, hosts.size, host.methodNames)) throw IllegalArgumentException("A global object called ${host.name} already exists")
        hosts += host
    }

    @Synchronized
    override fun close() {
        if (running > 0) closeRequested = true else cleanable.clean()
    }

    /** A script called method [method] of bound object [id] (on the evaluating thread). */
    fun invokeHost(
        id: Int,
        method: Int,
        args: Array<Any?>,
    ): Any? = hosts[id].invoke(method, args)

    /** The cleaning action: owns the handle, so it must not reference the engine. */
    private class ContextHandle(
        @Volatile var handle: Long,
    ) : Runnable {
        override fun run() {
            val h = handle
            handle = 0
            if (h != 0L) NativeJs.release(h)
        }
    }
}
