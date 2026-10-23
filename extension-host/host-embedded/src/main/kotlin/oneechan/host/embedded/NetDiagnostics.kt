package oneechan.host.embedded

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.management.ManagementFactory
import java.lang.management.PlatformManagedObject
import java.nio.file.Files
import kotlin.io.path.outputStream

/**
 * `debug.netFetch {requests: [{url, method?, bodySize?}]}`: fetches each URL, one after the other, the
 * way `source.fetchImage` fetches a page (the extensions' OkHttp client, a cacheless call with
 * progress, the body streamed to a file), so with native net on through the app's network stack.
 * A POST sends [Fetch.bodySize] bytes `i & 0xFF`. Replies with each body's file (the caller reads
 * and deletes it), the wall time and the JVM's CPU time. For the native net tests and benchmarks
 * (macOS JNI probe, DeviceBench).
 */
internal object NetDiagnostics {
    const val FETCH = "debug.netFetch"

    private val json = Json { ignoreUnknownKeys = true }

    fun handles(method: String): Boolean = method == FETCH

    @Serializable
    private class Params(
        val requests: List<Fetch>,
        /** Samples the Java stacks meanwhile and replies with the busiest methods. */
        val profile: Boolean = false,
    )

    @Serializable
    private class Fetch(
        val url: String,
        val method: String = "GET",
        val bodySize: Int? = null,
    )

    suspend fun call(request: RpcRequest): RpcResponse =
        withContext(Dispatchers.IO) {
            val params = json.decodeFromJsonElement(Params.serializer(), request.params)
            val client = Injekt.get<NetworkHelper>().client
            val sampler = if (params.profile) StackSampler().apply { start() } else null
            val cpu = JvmCpu.sample()
            val started = System.nanoTime()
            val results =
                params.requests.mapIndexed { index, fetch ->
                    val body = fetch.bodySize?.let { size -> ByteArray(size) { it.toByte() }.toRequestBody(OCTETS) }
                    val call = Request.Builder().url(fetch.url).method(fetch.method, body).build()
                    val fetchStarted = System.nanoTime()
                    val target = Files.createTempFile("netfetch-", ".bin")
                    client.newCachelessCallWithProgress(call, Page(index, imageUrl = fetch.url)).awaitSuccess().use { response ->
                        response.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                        JsonObject(
                            mapOf(
                                "url" to JsonPrimitive(fetch.url),
                                "code" to JsonPrimitive(response.code),
                                "path" to JsonPrimitive(target.toString()),
                                "size" to JsonPrimitive(Files.size(target)),
                                "millis" to JsonPrimitive((System.nanoTime() - fetchStarted) / 1_000_000),
                            ),
                        )
                    }
                }
            val wall = (System.nanoTime() - started) / 1_000_000
            val used = JvmCpu.since(cpu)
            sampler?.interrupt()
            sampler?.join()
            RpcResponse(
                request.id,
                result =
                    JsonObject(
                        mapOf(
                            "results" to JsonArray(results),
                            "wallMillis" to JsonPrimitive(wall),
                            "cpuMillis" to JsonObject(used.mapValues { JsonPrimitive(it.value / 1000) }),
                        ) + (sampler?.let { mapOf("profile" to it.report()) } ?: emptyMap()),
                    ),
            )
        }

    private val OCTETS = "application/octet-stream".toMediaType()
}

/**
 * Samples every thread's Java stack each millisecond: how often each method is on a running stack
 * (inclusive) and on top of it (self). Threads parked, waiting or sleeping are skipped.
 */
private class StackSampler : Thread("net-diagnostics-sampler") {
    private val inclusive = HashMap<String, Int>()
    private val self = HashMap<String, Int>()
    private var samples = 0

    init {
        isDaemon = true
    }

    override fun run() {
        while (!isInterrupted) {
            for ((thread, stack) in getAllStackTraces()) {
                if (thread === this || stack.isEmpty() || thread.state != State.RUNNABLE || stack[0].isIdle()) continue
                samples++
                self.merge(stack[0].key(), 1, Int::plus)
                stack.map { it.key() }.toSet().forEach { inclusive.merge(it, 1, Int::plus) }
            }
            try {
                sleep(1)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    fun report(): JsonObject =
        JsonObject(
            mapOf(
                "samples" to JsonPrimitive(samples),
                "inclusive" to top(inclusive, 60),
                "self" to top(self, 30),
            ),
        )

    private fun top(
        counts: Map<String, Int>,
        limit: Int,
    ) = JsonArray(counts.entries.sortedByDescending { it.value }.take(limit).map { JsonPrimitive("${it.value} ${it.key}") })

    private fun StackTraceElement.key() = "$className.$methodName"

    private fun StackTraceElement.isIdle() =
        (className == "jdk.internal.misc.Unsafe" && methodName == "park") || (className == "java.lang.Object" && methodName == "wait") ||
            (className == "java.lang.Thread" && methodName == "sleep") || isNativeMethod
}

/**
 * CPU time of this JVM in microseconds: Java threads (`application`) and VM threads (`system`, which
 * includes `gc`) on OpenJ9, or Java threads only elsewhere.
 */
private object JvmCpu {
    fun sample(): Map<String, Long> =
        runCatching {
            // com.ibm.lang.management is OpenJ9's (jdk.management); this host compiles against another JDK.
            @Suppress("UNCHECKED_CAST")
            val monitor = Class.forName("com.ibm.lang.management.JvmCpuMonitorMXBean") as Class<PlatformManagedObject>
            val info = monitor.getMethod("getThreadsCpuUsage").invoke(ManagementFactory.getPlatformMXBean(monitor))
            val type = Class.forName("com.ibm.lang.management.JvmCpuMonitorInfo")
            mapOf(
                "application" to type.getMethod("getApplicationCpuTime").invoke(info) as Long,
                "gc" to type.getMethod("getGcCpuTime").invoke(info) as Long,
                "system" to type.getMethod("getSystemJvmCpuTime").invoke(info) as Long,
            )
        }.getOrElse {
            val threads = ManagementFactory.getThreadMXBean()
            mapOf("application" to threads.allThreadIds.sumOf { threads.getThreadCpuTime(it).coerceAtLeast(0) } / 1000)
        }

    fun since(before: Map<String, Long>): Map<String, Long> = sample().mapValues { (key, value) -> value - (before[key] ?: 0) }
}
