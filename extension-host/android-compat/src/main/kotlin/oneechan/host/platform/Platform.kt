package oneechan.host.platform

import android.webkit.WebView
import android.webkit.WebViewProvider

/**
 * Seams between the vendored Android compatibility layer and the platform the extension host runs
 * on (desktop JVM in development, embedded JVM inside the iOS app in production).
 *
 * Implementations are registered once at host startup, before any extension is loaded.
 */
object Platform {
    @Volatile
    var js: JsEngineFactory = JsEngines

    /** The app's WKWebViews once an app registers a WebViewHost, else unsupported ([WebViewBackends]). */
    @Volatile
    var webView: WebViewBackend = WebViewBackends

    @Volatile
    var defaultUserAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
}

/**
 * A single JavaScript context behind `app.cash.quickjs.QuickJs`, with the semantics extensions get
 * on Android (quickjs-android 0.9.2): completion values are converted by [JsValues] rules, script
 * errors throw QuickJsException with the JS error's `message`, and an exception thrown by a bound
 * host method ([bind]) propagates unchanged.
 *
 * An engine is used by one thread at a time (QuickJs is not thread-safe); independent engines may
 * run concurrently. [close] frees it deterministically.
 */
interface JsEngine : AutoCloseable {
    fun evaluate(
        script: String,
        fileName: String,
    ): Any?

    /** Parses [script] without running it (QuickJs.compile): QuickJsException on a syntax error. */
    fun compile(
        script: String,
        fileName: String,
    )

    /** Defines the global [JsHostObject.name] as an object whose methods call [host] (QuickJs.set). */
    fun bind(host: JsHostObject)
}

fun interface JsEngineFactory {
    fun create(): JsEngine

    val name: String get() = javaClass.simpleName
}

/**
 * Picks the engine: JavaScriptCore when the app registered its natives (probed once), else Rhino.
 * `-Doneechan.js=rhino` forces Rhino (a reference run inside the app's JVM).
 */
object JsEngines : JsEngineFactory {
    private val override = ThreadLocal<JsEngineFactory?>()

    val selected: JsEngineFactory by lazy {
        if (System.getProperty("oneechan.js") != "rhino" && JavaScriptCoreJsEngineFactory.isAvailable) JavaScriptCoreJsEngineFactory else RhinoJsEngineFactory
    }

    override val name: String get() = selected.name

    override fun create(): JsEngine = (override.get() ?: selected).create()

    /** Runs [block] with QuickJs instances created on this thread backed by [factory] (tests, the self-test). */
    fun <T> withFactory(
        factory: JsEngineFactory,
        block: () -> T,
    ): T {
        val previous = override.get()
        override.set(factory)
        try {
            return block()
        } finally {
            override.set(previous)
        }
    }
}

fun interface WebViewBackend {
    fun createProvider(view: WebView): WebViewProvider
}
