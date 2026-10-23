package oneechan.host.net

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import oneechan.host.TestHost
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.NetFetchResultParams
import oneechan.host.rpc.RpcErrorDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The embedded transport: metadata as JSON, bodies as raw bytes; here "the app" answers on another thread. */
class BinaryNativeNetTest {
    private val json = Json { encodeDefaults = true }
    private val sent = ConcurrentLinkedQueue<Sent>()
    private val cancelled = ConcurrentLinkedQueue<Long>()

    private class Sent(
        val id: Long,
        val meta: JsonObject,
        val body: ByteArray?,
    )

    /** A host whose app answers each request with [answer] (metadata params and raw body), unless it returns null. */
    private fun net(answer: (Sent) -> Pair<NetFetchResultParams, ByteArray?>?): BinaryNativeNet {
        lateinit var net: BinaryNativeNet
        net =
            BinaryNativeNet(
                send = { id, meta, body ->
                    val request = Sent(id, Json.parseToJsonElement(meta.decodeToString()).jsonObject, body)
                    sent += request
                    thread {
                        answer(request)?.let { (params, bytes) ->
                            net.complete(id, json.encodeToString(NetFetchResultParams.serializer(), params).encodeToByteArray(), bytes)
                        }
                    }
                },
                cancel = { cancelled += it },
            )
        return net
    }

    private fun request(
        body: ByteArray? = null,
        method: String = if (body == null) "GET" else "POST",
    ) = NativeRequest("https://site.test/page?q=1", method, listOf("User-Agent" to "UA/1", "Cookie" to "a=1", "Cookie" to "b=2"), body, 30_000)

    @AfterEach
    fun tearDown() {
        NetworkPlatform.nativeNet = null
    }

    @Test
    fun `metadata is JSON without the body and bodies cross untouched`() {
        val page = Random(1).nextBytes(6 * 1024 * 1024)
        val upload = ByteArray(256 * 4) { it.toByte() }
        val net = net { NetFetchResultParams(it.id.toString(), 200, "OK", listOf(listOf("Content-Type", "image/jpeg")), protocol = "h2") to page }

        val response = net.execute(request(upload)) { false }

        val request = sent.single()
        assertEquals("https://site.test/page?q=1", request.meta["url"]?.jsonPrimitive?.content)
        assertEquals("POST", request.meta["method"]?.jsonPrimitive?.content)
        assertEquals("30000", request.meta["timeoutMillis"]?.jsonPrimitive?.content)
        assertEquals(request.id.toString(), request.meta["id"]?.jsonPrimitive?.content)
        assertEquals("""[["User-Agent","UA/1"],["Cookie","a=1"],["Cookie","b=2"]]""", request.meta["headers"].toString())
        assertFalse("bodyBase64" in request.meta, "the body is not in the JSON")
        assertSame(upload, request.body, "the request body is handed over as is")
        assertEquals(200, response.code)
        assertEquals("OK", response.message)
        assertEquals(listOf("Content-Type" to "image/jpeg"), response.headers)
        assertEquals("h2", response.protocol)
        assertSame(page, response.body, "the response body is the app's array, not a copy")
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `absent and empty bodies stay apart`() {
        val net =
            net { request ->
                NetFetchResultParams(request.id.toString(), 204, "No Content") to request.body?.let { ByteArray(0) }
            }

        val none = net.execute(request(body = null)) { false }
        val empty = net.execute(request(body = ByteArray(0))) { false }

        val (first, second) = sent.toList()
        assertNull(first.body)
        assertContentEquals(ByteArray(0), second.body)
        assertNull(none.body)
        assertContentEquals(ByteArray(0), empty.body)
    }

    @Test
    fun `app errors become the exceptions OkHttp expects`() {
        fun failing(code: ErrorCode) = net { NetFetchResultParams(it.id.toString(), error = RpcErrorDto(code, "from app")) to null }

        assertThrows<CloudflareBlockedException> { failing(ErrorCode.CLOUDFLARE).execute(request()) { false } }
        assertThrows<SocketTimeoutException> { failing(ErrorCode.TIMEOUT).execute(request()) { false } }
        val network = assertThrows<IOException> { failing(ErrorCode.NETWORK).execute(request()) { false } }
        assertEquals("from app", network.message)
    }

    @Test
    fun `a cancelled call tells the app and ignores its late answer`() {
        val net = net { null }
        val cancelling = AtomicBoolean(false)
        val call = thread { assertThrows<IOException> { net.execute(request()) { cancelling.get() } } }
        while (sent.isEmpty()) Thread.sleep(5)
        cancelling.set(true)
        call.join(5_000)

        val id = sent.single().id
        assertEquals(listOf(id), cancelled.toList())
        assertFalse(net.complete(id, "{}".encodeToByteArray(), null), "nobody waits for it any more")
    }

    @Test
    fun `extension requests through OkHttp keep their bodies byte for byte`() {
        val server = MockWebServer().apply { start() }
        try {
            val page = Random(2).nextBytes(5 * 1024 * 1024 + 7)
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/webp").body(Buffer().write(page)).build())
            server.enqueue(MockResponse.Builder().body("stored").build())
            // The app's side: performs each request with a plain client, like NSURLSession would.
            val raw = OkHttpClient.Builder().followRedirects(false).build()
            NetworkPlatform.nativeNet =
                net { request ->
                    val builder = Request.Builder().url(request.meta["url"]!!.jsonPrimitive.content)
                    request.meta["headers"]!!.jsonArray.forEach { pair ->
                        val (name, value) = pair.jsonArray.map { it.jsonPrimitive.content }
                        builder.addHeader(name, value)
                    }
                    builder.method(request.meta["method"]!!.jsonPrimitive.content, request.body?.toRequestBody())
                    raw.newCall(builder.build()).execute().use { response ->
                        NetFetchResultParams(
                            request.id.toString(),
                            response.code,
                            response.message,
                            response.headers.filterNot { it.first.equals("Content-Length", true) }.map { listOf(it.first, it.second) },
                        ) to response.body.bytes()
                    }
                }
            TestHost.host
            val client = Injekt.get<NetworkHelper>().client

            val received = client.newCall(Request.Builder().url(server.url("/page.webp")).build()).execute().use { it.body.bytes() }
            val upload = ByteArray(1024 * 1024) { (it * 31).toByte() }
            val stored =
                client
                    .newCall(Request.Builder().url(server.url("/upload")).post(upload.toRequestBody("application/octet-stream".toMediaType())).build())
                    .execute()
                    .use { it.body.string() }

            assertContentEquals(page, received)
            assertEquals("stored", stored)
            server.takeRequest()
            assertContentEquals(upload, server.takeRequest().body!!.toByteArray())
        } finally {
            server.close()
        }
    }
}
