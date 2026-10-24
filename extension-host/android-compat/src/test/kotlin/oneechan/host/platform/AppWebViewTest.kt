package oneechan.host.platform

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * android.webkit.WebView on [AppWebViews] with a fake app ([FakeHost]): commands out, events in, on
 * a Looper thread like the host's main Looper.
 */
class AppWebViewTest {
    private class FakeHost : WebViewHost {
        @Volatile var connected = true
        val commands = LinkedBlockingQueue<Pair<String, WebViewCommand>>()

        override val isAvailable: Boolean get() = connected

        override fun send(
            viewId: String,
            command: WebViewCommand,
        ) {
            commands.add(viewId to command)
        }

        fun next(): Pair<String, WebViewCommand> = assertNotNull(commands.poll(5, TimeUnit.SECONDS), "no command")
    }

    private val host = FakeHost()
    private lateinit var looper: Looper

    @BeforeEach
    fun setUp() {
        WebView.setProviderFactory { view: WebView -> Platform.webView.createProvider(view) }
        val ready = CompletableFuture<Looper>()
        Thread({
            Looper.prepare()
            ready.complete(Looper.myLooper())
            Looper.loop()
        }, "webview-test-looper").apply {
            isDaemon = true
            start()
        }
        looper = ready.get(5, TimeUnit.SECONDS)
        WebViewBackends.host = host
    }

    @AfterEach
    fun tearDown() {
        WebViewBackends.host = null
        System.clearProperty("oneechan.webview")
        looper.quitSafely()
    }

    /** Runs [block] on the Looper thread (WebView methods must be called there). */
    private fun <T> onLooper(block: () -> T): T {
        val result = CompletableFuture<T>()
        android.os.Handler(looper).post {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return try {
            result.get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause!!
        }
    }

    private fun newWebView(): WebView = onLooper { WebView(ContextWrapper(null)) }

    @Test
    fun `without a connected app WebView fails like before`() {
        WebViewBackends.host = null
        val error = assertThrows<UnsupportedOperationException> { newWebView() }
        assertTrue(error.message!!.startsWith("WebView is not available on this platform"))
        WebViewBackends.host = host
        host.connected = false
        assertThrows<UnsupportedOperationException> { newWebView() }
        assertEquals("unsupported", WebViewBackends.name)
    }

    @Test
    fun `the system property forces the unsupported backend`() {
        System.setProperty("oneechan.webview", "unsupported")
        assertThrows<UnsupportedOperationException> { newWebView() }
        System.clearProperty("oneechan.webview")
        assertEquals("app", WebViewBackends.name)
    }

    /** What keiyoushi's getLocalStorage (LibGroup's token) does: loadDataWithBaseURL, then evaluate on onPageFinished. */
    @Test
    fun `local storage read through page finished and evaluateJavascript`() {
        val finished = CompletableFuture<Pair<String, Thread>>()
        val webView = newWebView()
        onLooper {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        finished.complete(url!! to Thread.currentThread())
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = null
                }
            webView.loadDataWithBaseURL("https://mangalib.me", "<link rel=\"icon\" href=\"data:,\">", "text/html", "UTF-8", null)
        }
        val (viewId, load) = host.next()
        load as WebViewCommand.LoadData
        assertEquals("https://mangalib.me", load.baseUrl)
        assertEquals("UTF-8", load.encoding)
        assertEquals(Platform.defaultUserAgent, load.config.userAgent)
        assertTrue(load.config.javaScriptEnabled)
        assertTrue(load.config.interceptRequests, "the client overrides shouldInterceptRequest")
        assertFalse(load.config.overrideUrlLoading)

        assertTrue(AppWebViews.dispatch(viewId, WebViewEvent.PageFinished("https://mangalib.me/", WebViewState(url = "https://mangalib.me/", title = "t", progress = 100))))
        val (url, thread) = finished.get(5, TimeUnit.SECONDS)
        assertEquals("https://mangalib.me/", url)
        assertEquals(looper.thread, thread, "callbacks run on the WebView's Looper")
        assertEquals("https://mangalib.me/", onLooper { webView.url })
        assertEquals("t", onLooper { webView.title })

        val value = CompletableFuture<Pair<String, Thread>>()
        onLooper { webView.evaluateJavascript("localStorage.getItem(\"auth\")") { value.complete(it to Thread.currentThread()) } }
        val evaluate = host.next().second as WebViewCommand.Evaluate
        assertEquals("localStorage.getItem(\"auth\")", evaluate.script)
        AppWebViews.dispatch(viewId, WebViewEvent.EvaluateResult(evaluate.requestId, "\"{\\\"token\\\":1}\""))
        assertEquals("\"{\\\"token\\\":1}\"" to looper.thread, value.get(5, TimeUnit.SECONDS))

        // A script that threw: Android passes "null".
        val failed = CompletableFuture<String>()
        onLooper { webView.evaluateJavascript("throw 1") { failed.complete(it) } }
        val second = host.next().second as WebViewCommand.Evaluate
        AppWebViews.dispatch(viewId, WebViewEvent.EvaluateResult(second.requestId, null))
        assertEquals("null", failed.get(5, TimeUnit.SECONDS))

        val before = AppWebViews.count
        onLooper {
            webView.stopLoading()
            webView.destroy()
        }
        assertEquals(WebViewCommand.Stop, host.next().second)
        assertEquals(viewId to WebViewCommand.Destroy, host.next())
        assertEquals(before - 1, AppWebViews.count)
        assertFalse(AppWebViews.dispatch(viewId, WebViewEvent.PageFinished("x", WebViewState())), "a destroyed view gets no more callbacks")
    }

    @Test
    fun `navigations carry the settings and clients the app needs`() {
        val webView = newWebView()
        onLooper {
            webView.settings.userAgentString = "UA/2"
            webView.settings.blockNetworkImage = true
            webView.loadUrl("https://site.test/a", mapOf("Referer" to "https://site.test/"))
        }
        val (_, load) = host.next()
        load as WebViewCommand.Load
        assertEquals("https://site.test/a", load.url)
        assertEquals(mapOf("Referer" to "https://site.test/"), load.headers)
        assertEquals("GET", load.method)
        assertEquals(WebViewConfig(userAgent = "UA/2", blockNetworkImage = true), load.config, "a plain WebViewClient intercepts nothing")

        onLooper {
            webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean = false
                }
            webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
                }
            webView.postUrl("https://site.test/form", "a=1".toByteArray())
            webView.loadUrl("javascript:window.x%20%3D%201")
            webView.reload()
            webView.goBack()
        }
        val post = host.next().second as WebViewCommand.Load
        assertEquals("POST", post.method)
        assertEquals("a=1", post.body!!.decodeToString())
        assertTrue(post.config.overrideUrlLoading)
        assertTrue(post.config.consoleMessages)
        assertFalse(post.config.interceptRequests)
        assertEquals("window.x = 1", (host.next().second as WebViewCommand.Evaluate).script, "javascript: URLs run in the page")
        assertTrue(host.next().second is WebViewCommand.Reload)
        assertEquals(WebViewCommand.GoBack, host.next().second)
        onLooper { webView.destroy() }
    }

    @Test
    fun `page events reach the clients on the Looper`() {
        val events = LinkedBlockingQueue<String>()
        val webView = newWebView()
        onLooper {
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        events.add("started $url ${view === webView}")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        events.add("legacy error $errorCode $failingUrl")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        events.add("error ${request!!.url} ${request.isForMainFrame} ${error!!.errorCode} ${error.description}")
                        super.onReceivedError(view, request, error)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?,
                    ): Boolean {
                        events.add("gone ${detail!!.didCrash()}")
                        return true
                    }
                }
            webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        events.add("console ${consoleMessage.messageLevel()} ${consoleMessage.message()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                        return true
                    }
                }
            webView.loadUrl("https://site.test/")
        }
        val viewId = host.next().first
        AppWebViews.dispatch(viewId, WebViewEvent.PageStarted("https://site.test/", WebViewState(url = "https://site.test/", canGoBack = true)))
        AppWebViews.dispatch(viewId, WebViewEvent.ConsoleMessage("hi", "warning", "app.js", 3))
        AppWebViews.dispatch(viewId, WebViewEvent.ReceivedError(WebRequest("https://site.test/x", isForMainFrame = true), WebViewClient.ERROR_HOST_LOOKUP, "offline"))
        AppWebViews.dispatch(viewId, WebViewEvent.RenderProcessGone(didCrash = false))
        val seen = List(5) { events.poll(5, TimeUnit.SECONDS) }
        assertEquals(
            listOf(
                "started https://site.test/ true",
                "console WARNING hi app.js:3",
                "error https://site.test/x true -2 offline",
                "legacy error -2 https://site.test/x",
                "gone false",
            ),
            seen,
        )
        assertTrue(onLooper { webView.canGoBack() })
        onLooper { webView.destroy() }
    }

    class Bridge {
        val received = LinkedBlockingQueue<String>()

        @JavascriptInterface
        fun post(message: String) {
            received.add(message)
        }

        @JavascriptInterface
        fun sum(
            a: Int,
            b: Double,
        ): Double = a + b

        fun hidden(): String = "not exposed"
    }

    @Test
    fun `javascript interfaces expose only annotated methods`() {
        val bridge = Bridge()
        val anonymous =
            object {
                @JavascriptInterface
                fun postMessage(message: String) {
                    bridge.received.add("anon $message")
                }
            }
        val webView = newWebView()
        onLooper {
            webView.addJavascriptInterface(bridge, "Bridge")
            webView.addJavascriptInterface(anonymous, "Anon")
            webView.loadUrl("https://site.test/")
        }
        val (viewId, load) = host.next()
        assertEquals(
            listOf(
                JavascriptInterfaceSpec("Anon", listOf(JavascriptMethodSpec("postMessage", false))),
                JavascriptInterfaceSpec("Bridge", listOf(JavascriptMethodSpec("post", false), JavascriptMethodSpec("sum", true))),
            ),
            (load as WebViewCommand.Load).config.javascriptInterfaces,
        )

        assertNull(AppWebViews.callJavascriptInterface(viewId, "Bridge", "post", listOf("hello")))
        assertEquals("hello", bridge.received.poll(1, TimeUnit.SECONDS))
        assertEquals(3.5, AppWebViews.callJavascriptInterface(viewId, "Bridge", "sum", listOf(1.0, 2.5)))
        AppWebViews.callJavascriptInterface(viewId, "Anon", "postMessage", listOf(42.0))
        assertEquals("anon 42", bridge.received.poll(1, TimeUnit.SECONDS))
        assertThrows<IllegalArgumentException> { AppWebViews.callJavascriptInterface(viewId, "Bridge", "hidden", emptyList()) }
        assertThrows<IllegalArgumentException> { AppWebViews.callJavascriptInterface(viewId, "Missing", "post", listOf("x")) }

        onLooper {
            webView.removeJavascriptInterface("Anon")
            webView.reload()
        }
        assertEquals(listOf("Bridge"), (host.next().second as WebViewCommand.Reload).config.javascriptInterfaces.map { it.name })
        onLooper { webView.destroy() }
    }

    @Test
    fun `requests are offered to shouldInterceptRequest and navigations to shouldOverrideUrlLoading`() {
        val overrideThread = CompletableFuture<Thread>()
        val webView = newWebView()
        onLooper {
            webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): WebResourceResponse? =
                        when {
                            request.url.path == "/blocked.png" -> WebResourceResponse("image/png", null, null)
                            request.url.host == "api.site.test" ->
                                WebResourceResponse(
                                    "application/json", "utf-8", 201, "Created", mapOf("X-From" to "jvm"),
                                    "{\"ok\":true}".byteInputStream(),
                                )
                            else -> null
                        }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): Boolean {
                        overrideThread.complete(Thread.currentThread())
                        return request.url.scheme == "intent"
                    }
                }
            webView.loadUrl("https://site.test/")
        }
        val viewId = host.next().first
        assertNull(AppWebViews.intercept(viewId, WebRequest("https://site.test/app.js", isForMainFrame = false)))
        val api = assertNotNull(AppWebViews.intercept(viewId, WebRequest("https://api.site.test/v1", method = "POST", isForMainFrame = false)))
        assertEquals(201, api.statusCode)
        assertEquals("Created", api.reasonPhrase)
        assertEquals("application/json", api.mimeType)
        assertEquals(mapOf("X-From" to "jvm"), api.headers)
        assertEquals("{\"ok\":true}", api.body.decodeToString())
        val blocked = assertNotNull(AppWebViews.intercept(viewId, WebRequest("https://site.test/blocked.png", isForMainFrame = false)))
        assertEquals(200, blocked.statusCode)
        assertEquals(0, blocked.body.size)

        assertTrue(AppWebViews.shouldOverrideUrlLoading(viewId, WebRequest("intent://x", hasGesture = true)))
        assertEquals(looper.thread, overrideThread.get(5, TimeUnit.SECONDS))
        assertFalse(AppWebViews.shouldOverrideUrlLoading(viewId, WebRequest("https://site.test/next")))
        assertFalse(AppWebViews.shouldOverrideUrlLoading("unknown", WebRequest("https://site.test/next")))
        onLooper { webView.destroy() }
        assertNull(AppWebViews.intercept(viewId, WebRequest("https://api.site.test/v1")))
    }

    @Test
    fun `a WebView dropped without destroy releases the app's view when collected`() {
        val viewId = loadAndDrop()
        var released: Pair<String, WebViewCommand>? = null
        for (attempt in 0 until 50) {
            System.gc()
            released = host.commands.poll(100, TimeUnit.MILLISECONDS)
            if (released != null) break
        }
        assertEquals(viewId to WebViewCommand.Destroy, released)
        assertFalse(AppWebViews.dispatch(viewId, WebViewEvent.PageFinished("x", WebViewState())))
    }

    /** A WebView that loaded a page and is no longer referenced (a separate frame, so no local keeps it). */
    private fun loadAndDrop(): String {
        val webView = newWebView()
        onLooper { webView.loadUrl("https://site.test/") }
        return host.next().first
    }

    @Test
    fun `view methods without a WebKit counterpart are harmless`() {
        val webView = newWebView()
        val completed = CompletableFuture<Long>()
        onLooper {
            runCatching { webView.layoutParams = android.view.ViewGroup.LayoutParams(100, 100) }
            webView.postVisualStateCallback(7, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    completed.complete(requestId)
                }
            })
            webView.setNetworkAvailable(true)
            webView.clearCache(true)
        }
        assertEquals(7, completed.get(5, TimeUnit.SECONDS))
        assertEquals(0, onLooper { webView.progress })
        onLooper { webView.destroy() }
    }

    private fun touch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        vararg pointers: Triple<Int, Float, Float>,
    ): MotionEvent {
        val properties =
            pointers
                .map { (id, _, _) ->
                    MotionEvent.PointerProperties().apply {
                        this.id = id
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }.toTypedArray()
        val coords =
            pointers
                .map { (_, x, y) ->
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = 0.5f
                        touchMajor = 60f
                        touchMinor = 30f
                    }
                }.toTypedArray()
        return MotionEvent.obtain(downTime, eventTime, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /**
     * An extension tapping a WebView: CSS px × `resources.displayMetrics.density` in, dispatchTouchEvent, recycle. The
     * app gets each touch in order, back in CSS px of the page.
     */
    @Test
    fun `dispatched touches reach the app in CSS pixels`() {
        val webView = newWebView()
        val density = onLooper { webView.resources.displayMetrics.density }
        assertEquals(3f, density)
        val consumed =
            onLooper {
                listOf(
                    touch(100, 100, MotionEvent.ACTION_DOWN, Triple(0, 30f * density, 60f * density)),
                    touch(100, 116, MotionEvent.ACTION_MOVE, Triple(0, 30f * density, 90f * density)),
                    touch(100, 132, MotionEvent.ACTION_UP, Triple(0, 30f * density, 120f * density)),
                ).map { event -> webView.dispatchTouchEvent(event).also { event.recycle() } }
            }
        assertEquals(listOf(true, true, true), consumed)
        val (viewId, down) = host.next()
        assertEquals(WebViewCommand.Touch("down", 30.0, 60.0, 0, 0.5, 10.0, 5.0, 100), down)
        assertEquals(viewId to WebViewCommand.Touch("move", 30.0, 90.0, 0, 0.5, 10.0, 5.0, 116), host.next())
        assertEquals(viewId to WebViewCommand.Touch("up", 30.0, 120.0, 0, 0.5, 10.0, 5.0, 132), host.next())
        onLooper { webView.destroy() }
        assertEquals(WebViewCommand.Destroy, host.next().second)
        assertFalse(onLooper { webView.dispatchTouchEvent(touch(0, 0, MotionEvent.ACTION_DOWN, Triple(0, 1f, 1f))) }, "a destroyed view takes no touches")
        assertNull(host.commands.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a second finger and a cancel`() {
        val webView = newWebView()
        val second = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        onLooper {
            webView.dispatchTouchEvent(touch(0, 1, MotionEvent.ACTION_DOWN, Triple(0, 3f, 3f)))
            webView.dispatchTouchEvent(touch(0, 2, second, Triple(0, 3f, 3f), Triple(4, 6f, 6f)))
            webView.dispatchTouchEvent(touch(0, 3, MotionEvent.ACTION_MOVE, Triple(0, 9f, 9f), Triple(4, 12f, 12f)))
            webView.dispatchTouchEvent(touch(0, 4, MotionEvent.ACTION_CANCEL, Triple(0, 9f, 9f), Triple(4, 12f, 12f)))
        }
        val sent = List(6) { host.next().second as WebViewCommand.Touch }.map { "${it.action} ${it.pointerId} ${it.x}" }
        assertEquals(listOf("down 0 1.0", "down 4 2.0", "move 0 3.0", "move 4 4.0", "cancel 0 3.0", "cancel 4 4.0"), sent)
        onLooper { webView.destroy() }
    }

    /** Extensions refuse a site's ad windows with setSupportMultipleWindows(true) and no onCreateWindow. */
    @Test
    fun `navigations say whether new windows are refused`() {
        val webView = newWebView()
        onLooper { webView.loadUrl("https://site.test/") }
        val plain = (host.next().second as WebViewCommand.Load).config
        assertFalse(plain.supportMultipleWindows, "Android's default: new windows load in this view")
        assertFalse(plain.createWindow)

        onLooper {
            webView.settings.setSupportMultipleWindows(true)
            webView.webChromeClient = WebChromeClient()
            webView.loadUrl("https://site.test/reader")
        }
        val refusing = (host.next().second as WebViewCommand.Load).config
        assertTrue(refusing.supportMultipleWindows)
        assertFalse(refusing.createWindow, "the default onCreateWindow refuses the window")

        onLooper {
            webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }
            webView.reload()
        }
        val creating = (host.next().second as WebViewCommand.Reload).config
        assertTrue(creating.supportMultipleWindows && creating.createWindow)
        onLooper { webView.destroy() }
    }
}
