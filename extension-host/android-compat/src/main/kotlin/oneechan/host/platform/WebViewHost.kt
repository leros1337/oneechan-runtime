package oneechan.host.platform

import android.webkit.WebView
import android.webkit.WebViewProvider

/**
 * The app side of android.webkit.WebView: the app runs one WKWebView per WebView an extension
 * creates. host-core implements this over RPC (`webview.command` events, the same on device over
 * JNI and in development over SSE); the app answers through [AppWebViews] (page events, intercepted
 * requests, navigations, JavaScript interface calls).
 */
interface WebViewHost {
    /** Whether an app is connected to run commands; WebViews created otherwise are unsupported. */
    val isAvailable: Boolean

    /** Runs [command] on the app's WebView [viewId]. Asynchronous; commands of a view keep their order. */
    fun send(
        viewId: String,
        command: WebViewCommand,
    )
}

/**
 * Picks the backend when a WebView is constructed: the app's WebViews once an app registered a
 * connected [host], else [UnsupportedWebViewBackend] (desktop host without an app, plain JVM tests).
 * `-Doneechan.webview=unsupported` forces the fallback.
 */
object WebViewBackends : WebViewBackend {
    @Volatile
    var host: WebViewHost? = null

    val name: String
        get() = if (selectsApp()) "app" else "unsupported"

    override fun createProvider(view: WebView): WebViewProvider {
        val host = host
        return if (host != null && selectsApp()) AppWebViews.create(view, host) else UnsupportedWebViewBackend.createProvider(view)
    }

    private fun selectsApp(): Boolean = System.getProperty("oneechan.webview") != "unsupported" && host?.isAvailable == true
}

/** What the app does with a WebView. Navigations carry the [WebViewConfig] to apply first. */
sealed interface WebViewCommand {
    /** `loadUrl` (GET with extra headers) and `postUrl` ([method] POST with a [body]). */
    data class Load(
        val url: String,
        val headers: Map<String, String>,
        val config: WebViewConfig,
        val method: String = "GET",
        val body: ByteArray? = null,
    ) : WebViewCommand

    /**
     * `loadData` / `loadDataWithBaseURL`: [data] is the document, [baseUrl] its URL and origin (null:
     * an opaque origin). [encoding] "base64" means [data] is base64 (loadData only).
     */
    data class LoadData(
        val data: String,
        val baseUrl: String?,
        val mimeType: String?,
        val encoding: String?,
        val historyUrl: String?,
        val config: WebViewConfig,
    ) : WebViewCommand

    data class Reload(
        val config: WebViewConfig,
    ) : WebViewCommand

    /** `evaluateJavascript`: answered by [WebViewEvent.EvaluateResult] with the same [requestId]. */
    data class Evaluate(
        val requestId: Long,
        val script: String,
    ) : WebViewCommand

    /**
     * One pointer of a MotionEvent the extension dispatched to the WebView (`dispatchTouchEvent`), in
     * CSS pixels of the page (the event's pixels / the WebView's display density). [action] is
     * [DOWN], [MOVE], [UP] or [CANCEL]; [radiusX] and [radiusY] are half the contact's major and minor
     * axes; [timestampMillis] is the event time (uptime). The app replays it in the page.
     */
    data class Touch(
        val action: String,
        val x: Double,
        val y: Double,
        val pointerId: Int,
        val pressure: Double,
        val radiusX: Double,
        val radiusY: Double,
        val timestampMillis: Long,
    ) : WebViewCommand {
        companion object {
            const val DOWN = "down"
            const val MOVE = "move"
            const val UP = "up"
            const val CANCEL = "cancel"
        }
    }

    data object Stop : WebViewCommand

    data object GoBack : WebViewCommand

    data object GoForward : WebViewCommand

    /** The WebView was destroyed: the app releases its WKWebView. */
    data object Destroy : WebViewCommand
}

/** The WebView's settings and clients, as far as the app must know them. */
data class WebViewConfig(
    val userAgent: String,
    val javaScriptEnabled: Boolean = true,
    val blockNetworkImage: Boolean = false,
    /** The WebViewClient overrides shouldInterceptRequest: every request is offered to it ([AppWebViews.intercept]). */
    val interceptRequests: Boolean = false,
    /** The WebViewClient overrides shouldOverrideUrlLoading ([AppWebViews.shouldOverrideUrlLoading]). */
    val overrideUrlLoading: Boolean = false,
    /** The WebChromeClient overrides onConsoleMessage: the page's console is forwarded. */
    val consoleMessages: Boolean = false,
    /** addJavascriptInterface objects: what the page sees as `window[name]`. */
    val javascriptInterfaces: List<JavascriptInterfaceSpec> = emptyList(),
    /**
     * WebSettings.setSupportMultipleWindows(true): new windows (target=_blank, window.open) go to
     * WebChromeClient.onCreateWindow instead of loading in this view; without [createWindow] they are refused.
     */
    val supportMultipleWindows: Boolean = false,
    /** The WebChromeClient overrides onCreateWindow. */
    val createWindow: Boolean = false,
)

/** A `window[name]` object: the @JavascriptInterface methods of an addJavascriptInterface object. */
data class JavascriptInterfaceSpec(
    val name: String,
    val methods: List<JavascriptMethodSpec>,
)

data class JavascriptMethodSpec(
    val name: String,
    /** Non-void: the page gets a Promise of the value (WebKit has no synchronous bridge). */
    val returnsValue: Boolean,
)

/** What the app reports about a WebView. */
sealed interface WebViewEvent {
    /** The main frame committed a new document (Chromium's onPageStarted timing). */
    data class PageStarted(
        val url: String,
        val state: WebViewState,
    ) : WebViewEvent

    data class PageFinished(
        val url: String,
        val state: WebViewState,
    ) : WebViewEvent

    /** A load failed: [errorCode] is a WebViewClient.ERROR_* constant. */
    data class ReceivedError(
        val request: WebRequest,
        val errorCode: Int,
        val description: String,
    ) : WebViewEvent

    data class ConsoleMessage(
        val message: String,
        /** tip, log, warning, error or debug (ConsoleMessage.MessageLevel). */
        val level: String,
        val sourceId: String,
        val lineNumber: Int,
    ) : WebViewEvent

    /** The JSON of the script's completion value, like Android ("null" when it threw). */
    data class EvaluateResult(
        val requestId: Long,
        val value: String?,
    ) : WebViewEvent

    data class RenderProcessGone(
        val didCrash: Boolean,
    ) : WebViewEvent
}

/** Navigation state behind getUrl, getTitle, getProgress, canGoBack and canGoForward. */
data class WebViewState(
    val url: String? = null,
    val title: String? = null,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/** A request of the page (WebResourceRequest). */
data class WebRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val isForMainFrame: Boolean = true,
    val hasGesture: Boolean = false,
    val isRedirect: Boolean = false,
)

/** A response the WebViewClient supplied in shouldInterceptRequest (WebResourceResponse, read fully). */
data class WebResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val mimeType: String?,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
)
