package oneechan.host.embedded

import android.app.Application
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import oneechan.host.platform.WebViewBackends
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `debug.webViewSelfTest {origin?}`: android.webkit.WebView end to end on the app's WKWebViews, the
 * way extensions use it: keiyoushi's getLocalStorage (LibGroup's token: loadDataWithBaseURL, then
 * evaluateJavascript on onPageFinished) after a page of the same origin stored a value, and a page
 * calling an addJavascriptInterface object and the console. No network: documents are loaded as data
 * with [origin] (default `https://oneechan.invalid`) as their URL. For the macOS JNI tests and
 * on-device diagnostics.
 */
internal object WebViewDiagnostics {
    const val SELF_TEST = "debug.webViewSelfTest"
    private const val KEY = "oneechan-selftest"

    fun handles(method: String): Boolean = method == SELF_TEST

    suspend fun call(request: RpcRequest): RpcResponse {
        val origin = (request.params as? JsonObject)?.get("origin")?.jsonPrimitive?.contentOrNull ?: "https://oneechan.invalid"
        val checks = mutableListOf<JsonObject>()

        suspend fun step(
            name: String,
            block: suspend () -> String,
        ) {
            val started = System.nanoTime()
            val outcome = runCatching { block() }
            val millis = (System.nanoTime() - started) / 1_000_000
            checks +=
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "passed" to JsonPrimitive(outcome.isSuccess),
                        "detail" to JsonPrimitive(outcome.fold({ it }, { "${it::class.simpleName}: ${it.message}" })),
                        "millis" to JsonPrimitive(millis),
                    ),
                )
        }

        val stored = """{"token":"selftest-${System.currentTimeMillis()}"}"""
        step("localStorage.write") {
            val html = "<script>localStorage.setItem(${Json.encodeToString(KEY)}, ${Json.encodeToString(stored)})</script>"
            runWebView(origin, html) { _, url, done -> done.complete("finished $url") }
        }
        step("getLocalStorage") {
            // keiyoushi core/.../utils/WebView.kt getLocalStorage, as LibGroup calls it for its token.
            val value =
                runWebView(origin, """<link rel="icon" href="data:,">""") { view, _, done ->
                    view.evaluateJavascript("localStorage.getItem(${Json.encodeToString(KEY)})") { done.complete(Json.decodeFromString<String?>(it).toString()) }
                }
            check(value == stored) { "read $value, stored $stored" }
            value
        }
        step("javascriptInterface+console") {
            val messages = LinkedBlockingQueue<String>()
            val bridge =
                object {
                    @JavascriptInterface
                    fun post(message: String) {
                        messages.add(message)
                    }
                }
            val console = LinkedBlockingQueue<String>()
            runWebView(
                origin,
                "<script>console.warn('selftest', 1); OneechanProbe.post(location.origin + ' ' + typeof OneechanProbe.post)</script>",
                setup = { view ->
                    view.addJavascriptInterface(bridge, "OneechanProbe")
                    view.webChromeClient =
                        object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                console.add("${consoleMessage.messageLevel()} ${consoleMessage.message()}")
                                return true
                            }
                        }
                },
            ) { _, _, done -> done.complete("") }
            val message = messages.poll(10, TimeUnit.SECONDS)
            check(message == "$origin function") { "interface got $message" }
            val logged = console.poll(10, TimeUnit.SECONDS)
            check(logged == "WARNING selftest 1") { "console got $logged" }
            "$message; $logged"
        }

        val result =
            JsonObject(
                mapOf(
                    "backend" to JsonPrimitive(WebViewBackends.name),
                    "passed" to JsonPrimitive(checks.all { it["passed"] == JsonPrimitive(true) }),
                    "results" to JsonArray(checks),
                ),
            )
        return RpcResponse(request.id, result = result)
    }

    /** A WebView on the main Looper (keiyoushi's runWebView): loads [html] at [origin], completes in onPageFinished. */
    private suspend fun runWebView(
        origin: String,
        html: String,
        setup: (WebView) -> Unit = {},
        onFinished: (WebView, String?, CompletableDeferred<String>) -> Unit,
    ): String =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<String>()
            val view = WebView(Injekt.get<Application>())
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        onFinished(view, url, done)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = null
                }
            setup(view)
            try {
                view.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null)
                withTimeout(20_000) { done.await() }
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
}
