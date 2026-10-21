package oneechan.host.net

import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class CloudflareInterceptorTest {
    private val server = MockWebServer()
    private val cleared = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().addInterceptor(CloudflareInterceptor()).build()

    private val challenge =
        MockResponse
            .Builder()
            .code(403)
            .addHeader("Server", "cloudflare")
            .addHeader("cf-mitigated", "challenge")
            .body("<div id=\"challenge-stage\"></div>")
            .build()

    @BeforeEach
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (cleared.get()) MockResponse.Builder().body("ok").build() else challenge
            }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
        NetworkPlatform.challengeSolver = null
        NetworkPlatform.bypassEnabled = true
    }

    private fun get(): String =
        client.newCall(Request.Builder().url(server.url("/page")).build()).execute().use { it.body.string() }

    @Test
    fun `solved challenge retries the request once`() {
        val solves = AtomicInteger()
        NetworkPlatform.challengeSolver =
            ChallengeSolver { request ->
                solves.incrementAndGet()
                assertEquals(server.hostName, request.host)
                cleared.set(true)
                ChallengeOutcome.Solved
            }

        assertEquals("ok", get())
        assertEquals(1, solves.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `concurrent blocked requests share one solve`() {
        val solves = AtomicInteger()
        val release = CountDownLatch(1)
        NetworkPlatform.challengeSolver =
            ChallengeSolver {
                solves.incrementAndGet()
                release.await(5, TimeUnit.SECONDS)
                cleared.set(true)
                ChallengeOutcome.Solved
            }

        val pool = Executors.newFixedThreadPool(4)
        val results = (1..4).map { pool.submit(Callable { get() }) }
        Thread.sleep(300)
        release.countDown()

        assertEquals(List(4) { "ok" }, results.map { it.get(10, TimeUnit.SECONDS) })
        assertEquals(1, solves.get())
        pool.shutdown()
    }

    @Test
    fun `failed solve surfaces a CloudflareBlockedException`() {
        NetworkPlatform.challengeSolver = ChallengeSolver { ChallengeOutcome.Failed("timeout") }
        val error = assertThrows<CloudflareBlockedException> { get() }
        assertEquals("timeout", error.reason)
    }

    @Test
    fun `still challenged after solving fails instead of looping`() {
        NetworkPlatform.challengeSolver = ChallengeSolver { ChallengeOutcome.Solved }
        val error = assertThrows<CloudflareBlockedException> { get() }
        assertEquals("still challenged after solving", error.reason)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `disabled bypass and missing solver fail fast`() {
        assertThrows<CloudflareBlockedException> { get() }
        NetworkPlatform.challengeSolver = ChallengeSolver { error("must not be called") }
        NetworkPlatform.bypassEnabled = false
        assertThrows<CloudflareBlockedException> { get() }
    }
}
