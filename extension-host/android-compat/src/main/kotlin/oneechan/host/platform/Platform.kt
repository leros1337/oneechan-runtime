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
    var js: JsEngineFactory = RhinoJsEngineFactory

    @Volatile
    var webView: WebViewBackend = UnsupportedWebViewBackend

    @Volatile
    var defaultUserAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
}

/** A single JavaScript context, mirroring the surface of `app.cash.quickjs.QuickJs`. */
interface JsEngine : AutoCloseable {
    /**
     * Evaluates [script] and converts the completion value to the types QuickJs returns:
     * null, Boolean, Int, Long, Double, String, or a primitive/String/Object array.
     */
    fun evaluate(script: String): Any?

    /** Exposes a host object to scripts under the global [name]. */
    fun set(
        name: String,
        value: Any?,
    )
}

fun interface JsEngineFactory {
    fun create(): JsEngine
}

fun interface WebViewBackend {
    fun createProvider(view: WebView): WebViewProvider
}
