package oneechan.host.webview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import oneechan.host.EventSink
import oneechan.host.platform.AppWebViews
import oneechan.host.platform.JavascriptInterfaceSpec
import oneechan.host.platform.WebRequest
import oneechan.host.platform.WebResponse
import oneechan.host.platform.WebViewCommand
import oneechan.host.platform.WebViewConfig
import oneechan.host.platform.WebViewEvent
import oneechan.host.platform.WebViewHost
import oneechan.host.platform.WebViewState
import oneechan.host.rpc.AcceptedDto
import oneechan.host.rpc.JavascriptInterfaceDto
import oneechan.host.rpc.JavascriptMethodDto
import oneechan.host.rpc.RpcEvent
import oneechan.host.rpc.WebRequestDto
import oneechan.host.rpc.WebResponseDto
import oneechan.host.rpc.WebViewCommandDto
import oneechan.host.rpc.WebViewCommandEvent
import oneechan.host.rpc.WebViewConfigDto
import oneechan.host.rpc.WebViewEventDto
import oneechan.host.rpc.WebViewEventParams
import oneechan.host.rpc.WebViewInterceptResult
import oneechan.host.rpc.WebViewJsCallParams
import oneechan.host.rpc.WebViewJsCallResult
import oneechan.host.rpc.WebViewOverrideResult
import oneechan.host.rpc.WebViewRequestParams
import oneechan.host.rpc.WebViewStateDto
import java.util.Base64

/**
 * [WebViewHost] over the RPC events: each command becomes a `webview.command` event, which the app
 * runs on a WKWebView; the app reports back with the `webview.*` RPCs ([WebViewCalls]). The same
 * messages travel over JNI on device and over SSE in development.
 */
class RpcWebViewHost(
    private val events: EventSink,
    private val json: Json,
    private val appConnected: () -> Boolean,
) : WebViewHost {
    override val isAvailable: Boolean get() = appConnected()

    override fun send(
        viewId: String,
        command: WebViewCommand,
    ) {
        val event = WebViewCommandEvent(viewId, command.toDto())
        events.emit(RpcEvent("webview.command", json.encodeToJsonElement(WebViewCommandEvent.serializer(), event)))
    }
}

/** The `webview.*` RPC handlers. Client callbacks may block (network, the Looper), so they run on the IO pool. */
object WebViewCalls {
    fun event(params: WebViewEventParams): AcceptedDto = AcceptedDto(AppWebViews.dispatch(params.viewId, params.event.toEvent()))

    suspend fun intercept(params: WebViewRequestParams): WebViewInterceptResult =
        runInterruptible(Dispatchers.IO) { WebViewInterceptResult(AppWebViews.intercept(params.viewId, params.request.toRequest())?.toDto()) }

    suspend fun overrideUrl(params: WebViewRequestParams): WebViewOverrideResult =
        runInterruptible(Dispatchers.IO) { WebViewOverrideResult(AppWebViews.shouldOverrideUrlLoading(params.viewId, params.request.toRequest())) }

    suspend fun jsCall(params: WebViewJsCallParams): WebViewJsCallResult =
        runInterruptible(Dispatchers.IO) {
            val result = AppWebViews.callJavascriptInterface(params.viewId, params.interfaceName, params.method, params.args.map(::fromJson))
            WebViewJsCallResult(toJson(result))
        }

    /** A page value: strings, booleans and numbers as such; arrays and objects as their JSON text. */
    private fun fromJson(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull
            is JsonArray, is JsonObject -> element.toString()
        }

    private fun toJson(value: Any?): JsonElement? =
        when (value) {
            null, Unit -> null
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            else -> JsonPrimitive(value.toString())
        }
}

internal fun WebViewCommand.toDto(): WebViewCommandDto =
    when (this) {
        is WebViewCommand.Load -> WebViewCommandDto.Load(url, headers, method, body?.let(Base64.getEncoder()::encodeToString), config.toDto())
        is WebViewCommand.LoadData -> WebViewCommandDto.LoadData(data, baseUrl, mimeType, encoding, historyUrl, config.toDto())
        is WebViewCommand.Reload -> WebViewCommandDto.Reload(config.toDto())
        is WebViewCommand.Evaluate -> WebViewCommandDto.Evaluate(requestId, script)
        is WebViewCommand.Touch -> WebViewCommandDto.Touch(action, x, y, pointerId, pressure, radiusX, radiusY, timestampMillis)
        WebViewCommand.Stop -> WebViewCommandDto.Stop
        WebViewCommand.GoBack -> WebViewCommandDto.GoBack
        WebViewCommand.GoForward -> WebViewCommandDto.GoForward
        WebViewCommand.Destroy -> WebViewCommandDto.Destroy
    }

internal fun WebViewConfig.toDto() =
    WebViewConfigDto(
        userAgent, javaScriptEnabled, blockNetworkImage, interceptRequests, overrideUrlLoading, consoleMessages,
        javascriptInterfaces.map(JavascriptInterfaceSpec::toDto), supportMultipleWindows, createWindow,
    )

private fun JavascriptInterfaceSpec.toDto() = JavascriptInterfaceDto(name, methods.map { JavascriptMethodDto(it.name, it.returnsValue) })

internal fun WebViewEventDto.toEvent(): WebViewEvent =
    when (this) {
        is WebViewEventDto.PageStarted -> WebViewEvent.PageStarted(url, state.toState())
        is WebViewEventDto.PageFinished -> WebViewEvent.PageFinished(url, state.toState())
        is WebViewEventDto.ReceivedError -> WebViewEvent.ReceivedError(request.toRequest(), errorCode, description)
        is WebViewEventDto.Console -> WebViewEvent.ConsoleMessage(message, level, sourceId, lineNumber)
        is WebViewEventDto.EvaluateResult -> WebViewEvent.EvaluateResult(requestId, value)
        is WebViewEventDto.RenderProcessGone -> WebViewEvent.RenderProcessGone(didCrash)
    }

private fun WebViewStateDto.toState() = WebViewState(url, title, progress, canGoBack, canGoForward)

internal fun WebRequestDto.toRequest() = WebRequest(url, method, headers, isForMainFrame, hasGesture, isRedirect)

internal fun WebResponse.toDto() =
    WebResponseDto(statusCode, reasonPhrase, mimeType, encoding, headers, body.takeIf { it.isNotEmpty() }?.let(Base64.getEncoder()::encodeToString))
