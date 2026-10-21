package oneechan.host.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeNetInterceptorTest {
    private val server = MockWebServer()
    private val nativeCalls = AtomicInteger()

    /** Behaves like NSURLSession in Oneechan: no redirects, no cookies, decoded body, no Content-Encoding. */
    private val platform =
        object : NativeNet {
            val raw = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

            override fun execute(
                request: NativeRequest,
                isCancelled: () -> Boolean,
            ): NativeResponse {
                nativeCalls.incrementAndGet()
                val builder = Request.Builder().url(request.url)
                // NSURLSession negotiates and decodes content encodings itself.
                request.headers.filterNot { it.first.equals("Accept-Encoding", true) }.forEach { (k, v) -> builder.addHeader(k, v) }
                builder.method(request.method, request.body?.toRequestBody())
                raw.newCall(builder.build()).execute().use { response ->
                    val headers = response.headers.filterNot { it.first.equals("Content-Encoding", true) || it.first.equals("Content-Length", true) }
                    return NativeResponse(response.code, response.message, headers, response.body.bytes(), protocol = "http/1.1")
                }
            }
        }

    private val jar =
        object : CookieJar {
            val cookies = mutableListOf<Cookie>()

            override fun saveFromResponse(
                url: HttpUrl,
                cookies: List<Cookie>,
            ) {
                this.cookies += cookies
            }

            override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
        }

    private fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .cookieJar(jar)
            .addInterceptor(NativeNetInterceptor())
            .build()

    @BeforeEach
    fun setUp() {
        server.start()
        NetworkPlatform.nativeNet = platform
    }

    @AfterEach
    fun tearDown() {
        NetworkPlatform.nativeNet = null
        server.close()
    }

    private fun get(
        client: OkHttpClient,
        path: String,
    ) = client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body.string() }

    @Test
    fun `requests run on the platform stack`() {
        server.enqueue(MockResponse.Builder().body("hello").build())
        assertEquals("hello", get(client(), "/a"))
        assertEquals(1, nativeCalls.get())
    }

    @Test
    fun `without a platform stack OkHttp connects itself`() {
        NetworkPlatform.nativeNet = null
        server.enqueue(MockResponse.Builder().body("direct").build())
        assertEquals("direct", get(client(), "/a"))
        assertEquals(0, nativeCalls.get())
    }

    @Test
    fun `extension application and network interceptors still run`() {
        val app = AtomicInteger()
        val network = AtomicInteger()
        val derived =
            client()
                .newBuilder()
                .addInterceptor(Interceptor { app.incrementAndGet(); it.proceed(it.request().newBuilder().header("X-Ext", "1").build()) })
                .addNetworkInterceptor(Interceptor { network.incrementAndGet(); it.proceed(it.request()) })
                .build()
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", get(derived, "/b"))
        assertEquals(1, app.get())
        assertEquals(1, network.get())
        assertEquals("1", server.takeRequest().headers["X-Ext"])
    }

    @Test
    fun `redirects are followed by OkHttp with cookies on every hop`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/second").addHeader("Set-Cookie", "session=abc; Path=/").build())
        server.enqueue(MockResponse.Builder().body("landed").build())
        assertEquals("landed", get(client(), "/first"))
        server.takeRequest()
        assertEquals("session=abc", server.takeRequest().headers["Cookie"])
        assertEquals(2, nativeCalls.get())
    }

    @Test
    fun `decoded bodies are not decoded twice`() {
        val gzipped = Buffer().also { buffer -> GzipSink(buffer).buffer().use { it.writeUtf8("compressed text") } }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(gzipped).build())
        assertEquals("compressed text", get(client(), "/gz"))
    }

    @Test
    fun `post bodies and content type reach the platform`() {
        server.enqueue(MockResponse.Builder().body("posted").build())
        val request = Request.Builder().url(server.url("/p")).post("{\"a\":1}".toRequestBody("application/json".toMediaType())).build()
        client().newCall(request).execute().use { assertEquals("posted", it.body.string()) }
        val recorded = server.takeRequest()
        assertEquals("{\"a\":1}", recorded.body?.utf8())
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun `cancelling the call aborts the native request`() {
        val entered = CountDownLatch(1)
        NetworkPlatform.nativeNet =
            NativeNet { _, isCancelled ->
                entered.countDown()
                while (!isCancelled()) Thread.sleep(10)
                throw IOException("Canceled")
            }
        val call = client().newCall(Request.Builder().url(server.url("/slow")).build())
        var error: Throwable? = null
        val worker = thread { error = runCatching { call.execute() }.exceptionOrNull() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        call.cancel()
        worker.join(5000)
        assertTrue(error is IOException, "expected IOException, got $error")
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `okhttp layout matches what the rewrite expects`() {
        NativeNetInterceptor.verifyOkHttpLayout()
    }

    @Test
    fun `cloudflare failures from the platform surface unchanged`() {
        NetworkPlatform.nativeNet = NativeNet { request, _ -> throw CloudflareBlockedException(request.url, "timeout") }
        val error = assertThrows<CloudflareBlockedException> { get(client(), "/cf") }
        assertEquals("timeout", error.reason)
    }
}
