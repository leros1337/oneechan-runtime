package oneechan.host.net

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.decodeFromJsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import oneechan.host.TestHost
import oneechan.host.rpc.CookieDto
import oneechan.host.rpc.InitParams
import oneechan.host.rpc.NetFetchEvent
import oneechan.host.rpc.NetFetchResultParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dev transport: the host asks "the app" to fetch; here the app is a thread using OkHttp. */
class RpcNativeNetTest {
    private val host = TestHost.host
    private val server = MockWebServer().apply { start() }
    private val stop = AtomicBoolean(false)
    private val appFetches = AtomicInteger()

    private val fakeApp =
        thread(isDaemon = true) {
            val raw = OkHttpClient.Builder().followRedirects(false).build()
            while (!stop.get()) {
                val event = TestHost.events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (event.event != "net.fetch") continue
                val fetch = host.json.decodeFromJsonElement<NetFetchEvent>(event.data)
                appFetches.incrementAndGet()
                val request = Request.Builder().url(fetch.url).apply { fetch.headers.forEach { addHeader(it[0], it[1]) } }.build()
                raw.newCall(request).execute().use { response ->
                    host.fetchResult(
                        NetFetchResultParams(
                            id = fetch.id,
                            code = response.code,
                            message = response.message,
                            headers = response.headers.map { listOf(it.first, it.second) },
                            bodyBase64 = Base64.getEncoder().encodeToString(response.body.bytes()),
                        ),
                    )
                }
            }
        }

    @AfterEach
    fun tearDown() {
        stop.set(true)
        fakeApp.join(2000)
        NetworkPlatform.nativeNet = null
        server.close()
    }

    @Test
    fun `host requests go through the app and keep the cookie jar in the JVM`() {
        host.init(InitParams(nativeNet = true))
        val url = server.url("/page").toString()
        host.storeCookies(url, listOf(CookieDto("cf_clearance", "solved", server.hostName)))
        server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", "session=1; Path=/").body("from app").build())

        val client = Injekt.get<NetworkHelper>().client
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        assertEquals("from app", body)
        assertEquals(1, appFetches.get())
        assertTrue(server.takeRequest().headers["Cookie"]!!.contains("cf_clearance=solved"))
        assertTrue(host.cookies(url).any { it.name == "session" && it.value == "1" }, "Set-Cookie must land in the host jar")
    }
}
