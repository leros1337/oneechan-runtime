package oneechan.host.webview

import android.app.Application
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import oneechan.host.TestHost
import oneechan.host.platform.AppWebViews
import oneechan.host.rpc.InitParams
import oneechan.host.rpc.RpcDispatcher
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.WebRequestDto
import oneechan.host.rpc.WebViewCommandDto
import oneechan.host.rpc.WebViewCommandEvent
import oneechan.host.rpc.WebViewEventDto
import oneechan.host.rpc.WebViewEventParams
import oneechan.host.rpc.WebViewInterceptResult
import oneechan.host.rpc.WebViewJsCallParams
import oneechan.host.rpc.WebViewJsCallResult
import oneechan.host.rpc.WebViewRequestParams
import oneechan.host.rpc.WebViewStateDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * android.webkit.WebView through the whole host: `webview.command` events out, `webview.*` RPCs in
 * (real JSON through [RpcDispatcher]), with a thread playing the app's WKWebView.
 */
class WebViewRpcTest {
    private val host = TestHost.host
    private val dispatcher = RpcDispatcher(host)
    private val stop = AtomicBoolean(false)
    private val commands = LinkedBlockingQueue<WebViewCommandEvent>()

    /** The app: localStorage per origin; documents "load" at once. */
    private val localStorage = mapOf("https://mangalib.me" to mapOf("auth" to """{"token":{"access_token":"t"}}"""))

    private val fakeApp =
        thread(isDaemon = true, name = "fake-app") {
            while (!stop.get()) {
                val event = TestHost.events.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (event.event != "webview.command") continue
                val command = host.json.decodeFromJsonElement<WebViewCommandEvent>(event.data)
                commands.add(command)
                when (val c = command.command) {
                    is WebViewCommandDto.LoadData -> {
                        val url = c.baseUrl ?: "about:blank"
                        val state = WebViewStateDto(url = url, progress = 100)
                        report(command.viewId, WebViewEventDto.PageStarted(url, state))
                        report(command.viewId, WebViewEventDto.PageFinished(url, state))
                    }
                    is WebViewCommandDto.Evaluate -> {
                        // The app evaluates `localStorage.getItem("auth")` and returns the JSON of the value.
                        val key = Regex("""getItem\("(.+)"\)""").find(c.script)?.groupValues?.get(1)
                        val value = localStorage["https://mangalib.me"]?.get(key)
                        report(command.viewId, WebViewEventDto.EvaluateResult(c.requestId, Json.encodeToString(value)))
                    }
                    else -> Unit
                }
            }
        }

    private fun report(
        viewId: String,
        event: WebViewEventDto,
    ) {
        call("webview.event", host.json.encodeToJsonElement(WebViewEventParams(viewId, event)))
    }

    private fun call(
        method: String,
        params: JsonElement,
    ): JsonElement {
        val response = runBlocking { dispatcher.dispatch(RpcRequest("t", method, params)) }
        assertNull(response.error, "$method: ${response.error}")
        return response.result!!
    }

    @AfterEach
    fun tearDown() {
        host.init(InitParams(webView = false))
        stop.set(true)
        fakeApp.join(2000)
    }

    /** keiyoushi's getLocalStorage (core/.../utils/WebView.kt), the call LibGroup makes for its token. */
    private suspend fun getLocalStorage(
        url: String,
        key: String,
    ): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val webView = WebView(Injekt.get<Application>())
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockNetworkImage = false
            }
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        view.evaluateJavascript("localStorage.getItem(${Json.encodeToString(key)})") { value ->
                            deferred.complete(Json.decodeFromString<String?>(value))
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = null
                }
            webView.webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
                }
            try {
                webView.loadDataWithBaseURL(url, """<link rel="icon" href="data:,">""", "text/html", "UTF-8", null)
                withTimeout(10_000) { deferred.await() }
            } finally {
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                webView.stopLoading()
                webView.destroy()
            }
        }

    @Test
    fun `WebView is unsupported until the app offers its WebViews`() {
        host.init(InitParams(webView = false))
        val error = assertThrows<UnsupportedOperationException> { runBlocking { getLocalStorage("https://mangalib.me", "auth") } }
        assertTrue(error.message!!.contains("WebView is not available"))
        assertTrue("webview" in host.hello().capabilities)
        assertEquals("unsupported", host.hello().engines["webview"])
    }

    @Test
    fun `LibGroup reads its token from the app WebView's localStorage`() {
        host.init(InitParams(webView = true))
        assertEquals("app", host.hello().engines["webview"])
        val token = runBlocking { withTimeout(15_000) { getLocalStorage("https://mangalib.me", "auth") } }
        assertEquals("""{"token":{"access_token":"t"}}""", token)

        val load = commands.poll(1, TimeUnit.SECONDS)!!.command as WebViewCommandDto.LoadData
        assertEquals("https://mangalib.me", load.baseUrl)
        assertTrue(load.config.interceptRequests && load.config.consoleMessages)
        assertTrue(commands.poll(1, TimeUnit.SECONDS)!!.command is WebViewCommandDto.Evaluate)
        assertEquals(WebViewCommandDto.Stop, commands.poll(1, TimeUnit.SECONDS)!!.command)
        assertEquals(WebViewCommandDto.Destroy, commands.poll(1, TimeUnit.SECONDS)!!.command)
        assertEquals(0, AppWebViews.count)

        // Not logged in on the site: null, and LibGroup carries on without a token.
        assertNull(runBlocking { withTimeout(15_000) { getLocalStorage("https://mangalib.me", "missing") } })
    }

    class Bridge {
        val messages = LinkedBlockingQueue<String>()

        @JavascriptInterface
        fun post(message: String) {
            messages.add(message)
        }
    }

    @Test
    fun `javascript interfaces and request interception over RPC`() {
        host.init(InitParams(webView = true))
        val bridge = Bridge()
        val webView =
            runBlocking {
                withContext(Dispatchers.Main) {
                    WebView(Injekt.get<Application>()).apply {
                        addJavascriptInterface(bridge, "Bridge")
                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? =
                                    if (request.url.path == "/api") WebResourceResponse("text/plain", "utf-8", "from jvm".byteInputStream()) else null
                            }
                        loadUrl("https://site.test/")
                    }
                }
            }
        val viewId = commands.poll(5, TimeUnit.SECONDS)!!.viewId

        val called = call("webview.jsCall", host.json.encodeToJsonElement(WebViewJsCallParams(viewId, "Bridge", "post", listOf(JsonPrimitive("hi")))))
        assertNull(host.json.decodeFromJsonElement<WebViewJsCallResult>(called).result)
        assertEquals("hi", bridge.messages.poll(1, TimeUnit.SECONDS))

        val intercepted = call("webview.intercept", host.json.encodeToJsonElement(WebViewRequestParams(viewId, WebRequestDto("https://site.test/api", isForMainFrame = false))))
        val response = assertNotNull(host.json.decodeFromJsonElement<WebViewInterceptResult>(intercepted).response)
        assertEquals("text/plain", response.mimeType)
        assertEquals("from jvm", Base64.getDecoder().decode(response.bodyBase64).decodeToString())
        val passed = call("webview.intercept", host.json.encodeToJsonElement(WebViewRequestParams(viewId, WebRequestDto("https://site.test/app.js"))))
        assertNull(host.json.decodeFromJsonElement<WebViewInterceptResult>(passed).response)

        val missing = runBlocking { dispatcher.dispatch(RpcRequest("t", "webview.jsCall", host.json.encodeToJsonElement(WebViewJsCallParams(viewId, "Nope", "post")))) }
        assertNotNull(missing.error, "unknown interfaces are refused")
        runBlocking { withContext(Dispatchers.Main) { webView.destroy() } }
    }
}
