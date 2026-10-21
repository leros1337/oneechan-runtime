package oneechan.host.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import oneechan.host.ExtensionHost
import oneechan.host.HostPaths
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RpcHttpServerTest {
    companion object {
        private val events = EventHub()
        private lateinit var server: RpcHttpServer
        private val port = 47000 + (ProcessHandle.current().pid() % 1000).toInt()
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeAll
        fun start() {
            val root = Files.createTempDirectory("oneechan-desktop-test")
            val host = ExtensionHost(HostPaths(root.resolve("d"), root.resolve("c"), root.resolve("t")), events = events)
            server = RpcHttpServer(host, events, InetSocketAddress("127.0.0.1", port), token = "secret")
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop()
    }

    private fun call(
        request: RpcRequest,
        token: String? = "secret",
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/rpc"))
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(RpcRequest.serializer(), request)))
        token?.let { builder.header("X-Oneechan-Token", it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `hello reports the protocol version`() {
        val response = json.decodeFromString(RpcResponse.serializer(), call(RpcRequest("1", "host.hello")).body())
        assertEquals("1", response.id)
        assertEquals(1, assertNotNull(response.result).jsonObject["protocolVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unknown methods and bad params map to protocol errors`() {
        val unknown = json.decodeFromString(RpcResponse.serializer(), call(RpcRequest("2", "nope.nope")).body())
        assertEquals(ErrorCode.METHOD_NOT_FOUND, unknown.error?.code)
        val bad = json.decodeFromString(RpcResponse.serializer(), call(RpcRequest("3", "repo.add", JsonObject(mapOf("x" to JsonPrimitive(1))))).body())
        assertEquals(ErrorCode.INVALID_PARAMS, bad.error?.code)
    }

    @Test
    fun `requests without the token are rejected`() {
        assertEquals(401, call(RpcRequest("4", "host.hello"), token = null).statusCode())
    }
}
