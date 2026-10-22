package oneechan.host.platform

import android.net.Uri
import android.os.Handler
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The WebViews of [WebViewBackends] when an app is connected: each android.webkit.WebView drives one
 * WKWebView in the app through [WebViewHost], and the app's reports come back through these entry
 * points (called by host-core's RPC handlers).
 *
 * Threading follows Android: WebViewClient and WebChromeClient callbacks and evaluateJavascript
 * results run on the WebView's Looper (the main Looper for every extension seen so far);
 * shouldInterceptRequest and @JavascriptInterface methods run on the calling background thread.
 */
object AppWebViews {
    // Weak: a WebView the extension drops without destroy() is collected like on Android, and the
    // cleaner then releases the app's WKWebView.
    private val views = ConcurrentHashMap<String, WeakReference<AppWebView>>()
    private val cleaner = Cleaner.create()

    internal fun create(
        view: WebView,
        host: WebViewHost,
    ): WebViewProvider {
        val id = UUID.randomUUID().toString()
        val webView = AppWebView(id, view, host) { views.remove(id) }
        views[id] = WeakReference(webView)
        cleaner.register(webView, Release(id, host))
        return webView.provider
    }

    /** Destroys the app's view of a collected WebView (holds no reference to it). */
    private class Release(
        private val id: String,
        private val host: WebViewHost,
    ) : Runnable {
        override fun run() {
            if (views.remove(id) != null) host.send(id, WebViewCommand.Destroy)
        }
    }

    private fun view(id: String): AppWebView? = views[id]?.get()

    /** Live (not destroyed, not collected) WebViews. */
    val count: Int get() = views.values.count { it.get() != null }

    /** A page event of [viewId]; false when the view is unknown or destroyed. */
    fun dispatch(
        viewId: String,
        event: WebViewEvent,
    ): Boolean {
        val target = view(viewId) ?: return false
        target.onEvent(event)
        return true
    }

    /** shouldInterceptRequest on this (background) thread; null lets the app load the request itself. */
    fun intercept(
        viewId: String,
        request: WebRequest,
    ): WebResponse? = view(viewId)?.intercept(request)

    /** shouldOverrideUrlLoading on the WebView's Looper; true cancels the navigation. */
    fun shouldOverrideUrlLoading(
        viewId: String,
        request: WebRequest,
    ): Boolean = view(viewId)?.shouldOverrideUrlLoading(request) ?: false

    /**
     * The page called `window[name][method](args)`: invokes the @JavascriptInterface method on this
     * (background) thread. [args] are JSON values (String, Boolean, Number, null). Returns the
     * method's result (null for void); throws [IllegalArgumentException] for anything that is not an
     * annotated public method of a registered object.
     */
    fun callJavascriptInterface(
        viewId: String,
        name: String,
        method: String,
        args: List<Any?>,
    ): Any? {
        val target = requireNotNull(view(viewId)) { "No WebView $viewId" }
        return target.callJavascriptInterface(name, method, args)
    }
}

/**
 * One WebView's provider. Its public functions are WebViewProvider methods, matched by name and
 * parameter types through [provider] (a dynamic proxy); every other provider method is a no-op that
 * returns its type's default, like a view that is never drawn.
 */
internal class AppWebView(
    val id: String,
    private val view: WebView,
    private val host: WebViewHost,
    private val onDestroyed: () -> Unit,
) {
    private val handler = Handler(view.webViewLooper)

    // The vendored WebSettings implementation: plain values, the user agent defaulting to http.agent
    // (the host's current default user agent).
    private val settings: WebSettings = KcefWebSettings()

    @Volatile private var client = WebViewClient()

    @Volatile private var chromeClient: WebChromeClient? = null

    private val interfaces = ConcurrentHashMap<String, Any>()
    private val evaluations = ConcurrentHashMap<Long, ValueCallback<String>>()
    private val nextRequest = AtomicLong()

    // Written on the WebView's Looper (state, originalUrl) or the caller's thread (destroyed).
    @Volatile private var state = WebViewState()

    @Volatile private var originalUrl: String? = null

    @Volatile private var destroyed = false

    val provider: WebViewProvider = forward(WebViewProvider::class.java, this)
    private val viewDelegate: WebViewProvider.ViewDelegate = forward(WebViewProvider.ViewDelegate::class.java, Unit)
    private val scrollDelegate: WebViewProvider.ScrollDelegate = forward(WebViewProvider.ScrollDelegate::class.java, Unit)

    // ---- WebViewProvider ---------------------------------------------------------------------

    fun init(
        javaScriptInterfaces: Map<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        javaScriptInterfaces?.forEach { (name, target) -> interfaces[name] = target }
    }

    fun getSettings(): WebSettings = settings

    fun getViewDelegate(): WebViewProvider.ViewDelegate = viewDelegate

    fun getScrollDelegate(): WebViewProvider.ScrollDelegate = scrollDelegate

    fun setWebViewClient(client: WebViewClient) {
        this.client = client
    }

    fun getWebViewClient(): WebViewClient = client

    fun setWebChromeClient(client: WebChromeClient?) {
        chromeClient = client
    }

    fun getWebChromeClient(): WebChromeClient? = chromeClient

    fun addJavascriptInterface(
        target: Any,
        interfaceName: String,
    ) {
        interfaces[interfaceName] = target
    }

    fun removeJavascriptInterface(interfaceName: String) {
        interfaces.remove(interfaceName)
    }

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String>?,
    ) {
        if (url.startsWith("javascript:", ignoreCase = true)) {
            // Android runs it in the current page, like evaluateJavascript without a result.
            evaluateJavaScript(Uri.decode(url.substring("javascript:".length)), null)
            return
        }
        originalUrl = url
        send(WebViewCommand.Load(url, additionalHttpHeaders.orEmpty(), config()))
    }

    fun loadUrl(url: String) = loadUrl(url, null)

    fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        originalUrl = url
        send(WebViewCommand.Load(url, emptyMap(), config(), method = "POST", body = postData))
    }

    fun loadData(
        data: String,
        mimeType: String?,
        encoding: String?,
    ) {
        originalUrl = null
        send(WebViewCommand.LoadData(data, null, mimeType, encoding, null, config()))
    }

    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        originalUrl = baseUrl
        // Only loadData decodes base64; here the encoding names the charset.
        send(WebViewCommand.LoadData(data, baseUrl, mimeType, encoding?.takeUnless { it.equals("base64", ignoreCase = true) }, historyUrl, config()))
    }

    fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>?,
    ) {
        if (destroyed) return
        val requestId = nextRequest.incrementAndGet()
        resultCallback?.let { evaluations[requestId] = it }
        send(WebViewCommand.Evaluate(requestId, script))
    }

    fun reload() = send(WebViewCommand.Reload(config()))

    fun stopLoading() = send(WebViewCommand.Stop)

    fun canGoBack(): Boolean = state.canGoBack

    fun goBack() = send(WebViewCommand.GoBack)

    fun canGoForward(): Boolean = state.canGoForward

    fun goForward() = send(WebViewCommand.GoForward)

    fun canGoBackOrForward(steps: Int): Boolean =
        when {
            steps < 0 -> steps == -1 && state.canGoBack
            steps > 0 -> steps == 1 && state.canGoForward
            else -> true
        }

    fun goBackOrForward(steps: Int) {
        when {
            steps < 0 -> repeat(-steps) { goBack() }
            steps > 0 -> repeat(steps) { goForward() }
        }
    }

    fun getUrl(): String? = state.url

    fun getOriginalUrl(): String? = originalUrl ?: state.url

    fun getTitle(): String? = state.title

    fun getProgress(): Int = state.progress

    fun insertVisualStateCallback(
        requestId: Long,
        callback: WebView.VisualStateCallback,
    ) {
        post { callback.onComplete(requestId) }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        evaluations.clear()
        interfaces.clear()
        onDestroyed()
        host.send(id, WebViewCommand.Destroy)
    }

    // ---- From the app ------------------------------------------------------------------------

    fun onEvent(event: WebViewEvent) {
        when (event) {
            is WebViewEvent.PageStarted ->
                post {
                    state = event.state
                    client.onPageStarted(view, event.url, null)
                }
            is WebViewEvent.PageFinished ->
                post {
                    state = event.state
                    client.onPageFinished(view, event.url)
                }
            is WebViewEvent.ReceivedError ->
                post { client.onReceivedError(view, ResourceRequest(event.request), ResourceError(event.errorCode, event.description)) }
            is WebViewEvent.ConsoleMessage ->
                post {
                    val level = ConsoleMessage.MessageLevel.entries.firstOrNull { it.name.equals(event.level, ignoreCase = true) } ?: ConsoleMessage.MessageLevel.LOG
                    chromeClient?.onConsoleMessage(ConsoleMessage(event.message, event.sourceId, event.lineNumber, level))
                }
            is WebViewEvent.EvaluateResult ->
                evaluations.remove(event.requestId)?.let { callback -> post { callback.onReceiveValue(event.value ?: "null") } }
            is WebViewEvent.RenderProcessGone ->
                post {
                    if (!client.onRenderProcessGone(view, GoneDetail(event.didCrash))) {
                        Log.w(TAG, "WebView content process ended and the client did not handle it")
                    }
                }
        }
    }

    fun intercept(request: WebRequest): WebResponse? {
        if (destroyed) return null
        val response =
            try {
                client.shouldInterceptRequest(view, ResourceRequest(request))
            } catch (e: Throwable) {
                Log.w(TAG, "shouldInterceptRequest threw", e)
                null
            } ?: return null
        val body = response.data?.use { it.readBytes() } ?: ByteArray(0)
        val status = response.statusCode.takeIf { it > 0 } ?: 200
        return WebResponse(status, response.reasonPhrase ?: "OK", response.mimeType, response.encoding, response.responseHeaders.orEmpty(), body)
    }

    fun shouldOverrideUrlLoading(request: WebRequest): Boolean {
        if (destroyed) return false
        val result = CompletableFuture<Boolean>()
        handler.post {
            result.complete(
                try {
                    !destroyed && client.shouldOverrideUrlLoading(view, ResourceRequest(request))
                } catch (e: Throwable) {
                    Log.w(TAG, "shouldOverrideUrlLoading threw", e)
                    false
                },
            )
        }
        return try {
            result.get(OVERRIDE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            Log.w(TAG, "shouldOverrideUrlLoading did not return within ${OVERRIDE_TIMEOUT_SECONDS}s: the navigation proceeds")
            false
        }
    }

    fun callJavascriptInterface(
        name: String,
        methodName: String,
        args: List<Any?>,
    ): Any? {
        check(!destroyed) { "WebView $id is destroyed" }
        val target = requireNotNull(interfaces[name]) { "No JavaScript interface $name" }
        val method =
            requireNotNull(javascriptMethods(target.javaClass).firstOrNull { it.name == methodName && it.parameterCount == args.size }) {
                "$name has no @JavascriptInterface method $methodName with ${args.size} arguments"
            }
        val converted = Array(args.size) { JsArguments.convert(args[it], method.parameterTypes[it]) }
        return try {
            method.invoke(target, *converted)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun send(command: WebViewCommand) {
        if (!destroyed) host.send(id, command)
    }

    /** Runs [block] on the WebView's Looper unless the view was destroyed; a throwing callback is logged, not fatal. */
    private fun post(block: () -> Unit) {
        handler.post {
            if (destroyed) return@post
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "WebView callback threw", e)
            }
        }
    }

    private fun config(): WebViewConfig {
        val client = client
        val chrome = chromeClient
        return WebViewConfig(
            userAgent = settings.userAgentString?.takeIf { it.isNotBlank() } ?: Platform.defaultUserAgent,
            javaScriptEnabled = settings.javaScriptEnabled,
            blockNetworkImage = settings.blockNetworkImage || !settings.loadsImagesAutomatically,
            interceptRequests =
                overrides(client, WebViewClient::class.java, "shouldInterceptRequest", WebView::class.java, WebResourceRequest::class.java) ||
                    overrides(client, WebViewClient::class.java, "shouldInterceptRequest", WebView::class.java, String::class.java),
            overrideUrlLoading =
                overrides(client, WebViewClient::class.java, "shouldOverrideUrlLoading", WebView::class.java, WebResourceRequest::class.java) ||
                    overrides(client, WebViewClient::class.java, "shouldOverrideUrlLoading", WebView::class.java, String::class.java),
            consoleMessages =
                chrome != null &&
                    (
                        overrides(chrome, WebChromeClient::class.java, "onConsoleMessage", ConsoleMessage::class.java) ||
                            overrides(chrome, WebChromeClient::class.java, "onConsoleMessage", String::class.java, Int::class.javaPrimitiveType!!, String::class.java)
                    ),
            javascriptInterfaces =
                interfaces.entries.sortedBy { it.key }.map { (name, target) ->
                    JavascriptInterfaceSpec(
                        name,
                        javascriptMethods(target.javaClass).groupBy { it.name }.map { (method, overloads) ->
                            JavascriptMethodSpec(method, overloads.any { it.returnType != Void.TYPE })
                        },
                    )
                },
        )
    }

    private class ResourceRequest(
        private val request: WebRequest,
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(request.url)

        override fun isForMainFrame(): Boolean = request.isForMainFrame

        override fun isRedirect(): Boolean = request.isRedirect

        override fun hasGesture(): Boolean = request.hasGesture

        override fun getMethod(): String = request.method

        override fun getRequestHeaders(): Map<String, String> = request.headers
    }

    private class ResourceError(
        private val code: Int,
        private val description: String,
    ) : WebResourceError() {
        override fun getErrorCode(): Int = code

        override fun getDescription(): CharSequence = description
    }

    private class GoneDetail(
        private val crashed: Boolean,
    ) : RenderProcessGoneDetail() {
        override fun didCrash(): Boolean = crashed

        override fun rendererPriorityAtExit(): Int = 0
    }

    companion object {
        private const val TAG = "AppWebView"
        private const val OVERRIDE_TIMEOUT_SECONDS = 10L

        private val javascriptMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()

        /** Public methods annotated @JavascriptInterface (Android exposes nothing else since API 17). */
        fun javascriptMethods(type: Class<*>): List<Method> =
            javascriptMethodCache.getOrPut(type) {
                type.methods
                    .filter { it.isAnnotationPresent(JavascriptInterface::class.java) && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    // Extension objects are often anonymous (non-public) classes.
                    .onEach { it.trySetAccessible() }
                    .sortedBy { it.name }
            }

        private fun overrides(
            target: Any,
            base: Class<*>,
            name: String,
            vararg parameters: Class<*>,
        ): Boolean =
            try {
                target.javaClass.getMethod(name, *parameters).declaringClass != base
            } catch (_: NoSuchMethodException) {
                false
            }

        /** A proxy of [type] whose calls go to [target]'s public method of the same signature, else return defaults. */
        private fun <T> forward(
            type: Class<T>,
            target: Any,
        ): T {
            val resolved = ConcurrentHashMap<Method, Any>()
            val none = Any()
            val proxy =
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                    when (method.name) {
                        "equals" -> if (method.parameterCount == 1) return@newProxyInstance self === args?.get(0)
                        "hashCode" -> if (method.parameterCount == 0) return@newProxyInstance System.identityHashCode(self)
                        "toString" -> if (method.parameterCount == 0) return@newProxyInstance "${type.simpleName}(${(target as? AppWebView)?.id})"
                    }
                    val implementation =
                        resolved.getOrPut(method) {
                            try {
                                target.javaClass.getMethod(method.name, *method.parameterTypes).takeIf { it.declaringClass == target.javaClass } ?: none
                            } catch (_: NoSuchMethodException) {
                                none
                            }
                        }
                    if (implementation is Method) {
                        try {
                            implementation.invoke(target, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    } else {
                        defaultValue(method.returnType)
                    }
                }
            return type.cast(proxy)
        }
    }
}

/** JSON values from the page to Java parameters, like Android's JavaBridge for the types extensions use. */
internal object JsArguments {
    fun convert(
        value: Any?,
        type: Class<*>,
    ): Any? {
        val number = value as? Number
        val converted: Any? =
            when (type.kotlin.javaObjectType) {
                String::class.java, CharSequence::class.java -> value?.let(::text)
                java.lang.Boolean::class.java -> value as? Boolean
                Integer::class.java -> number?.toInt()
                java.lang.Long::class.java -> number?.toLong()
                java.lang.Double::class.java -> number?.toDouble()
                java.lang.Float::class.java -> number?.toFloat()
                java.lang.Short::class.java -> number?.toShort()
                java.lang.Byte::class.java -> number?.toByte()
                Character::class.java -> (value as? String)?.firstOrNull()
                else -> value.takeIf { type.isInstance(it) }
            }
        return converted ?: defaultValue(type)
    }

    /** Integral numbers without a fraction, as JavaScript prints them. */
    private fun text(value: Any): String = if (value is Double && value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
}

/** The zero value of a primitive type, null for reference types. */
internal fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> 0.toChar()
        else -> null
    }
