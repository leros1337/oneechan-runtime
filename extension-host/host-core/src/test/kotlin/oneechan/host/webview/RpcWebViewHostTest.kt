package oneechan.host.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import oneechan.host.platform.WebViewCommand
import oneechan.host.platform.WebViewConfig
import oneechan.host.rpc.RpcEvent
import oneechan.host.rpc.WebViewCommandDto
import oneechan.host.rpc.WebViewCommandEvent
import oneechan.host.rpc.WebViewConfigDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Commands as the app receives them: `webview.command` events. */
class RpcWebViewHostTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }

    @Test
    fun `touches travel as webview command events`() {
        val events = mutableListOf<RpcEvent>()
        val host = RpcWebViewHost({ events += it }, json) { true }
        host.send("v1", WebViewCommand.Touch(WebViewCommand.Touch.MOVE, 12.5, 40.0, 1, 0.3, 8.0, 6.0, 99))
        val event = events.single()
        assertEquals("webview.command", event.event)
        assertEquals("touch", event.data.jsonObject["command"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val decoded = json.decodeFromJsonElement<WebViewCommandEvent>(event.data)
        assertEquals(WebViewCommandEvent("v1", WebViewCommandDto.Touch("move", 12.5, 40.0, 1, 0.3, 8.0, 6.0, 99)), decoded)
    }

    @Test
    fun `navigations carry the multiple-windows setting`() {
        val events = mutableListOf<RpcEvent>()
        val host = RpcWebViewHost({ events += it }, json) { true }
        host.send("v1", WebViewCommand.Load("https://site.test/", emptyMap(), WebViewConfig("UA/1", supportMultipleWindows = true)))
        host.send("v1", WebViewCommand.Reload(WebViewConfig("UA/1", supportMultipleWindows = true, createWindow = true)))
        val (load, reload) = events.map { json.decodeFromJsonElement<WebViewCommandEvent>(it.data).command }
        load as WebViewCommandDto.Load
        assertTrue(load.config.supportMultipleWindows)
        assertFalse(load.config.createWindow)
        reload as WebViewCommandDto.Reload
        assertTrue(reload.config.supportMultipleWindows && reload.config.createWindow)
        // An older app's config without the fields reads as Android's default.
        val older = json.decodeFromString<WebViewConfigDto>("""{"userAgent":"UA/1"}""")
        assertFalse(older.supportMultipleWindows || older.createWindow)
    }
}
