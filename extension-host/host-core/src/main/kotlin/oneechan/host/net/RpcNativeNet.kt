package oneechan.host.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import oneechan.host.EventSink
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.NetFetchEvent
import oneechan.host.rpc.NetFetchResultParams
import oneechan.host.rpc.RpcEvent
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Development [NativeNet]: the host (desktop JVM) asks the connected app to perform each request
 * with its real URLSession + Cloudflare solver, via the `net.fetch` event and the `net.fetchResult`
 * RPC. On device the same Swift code is called over JNI instead.
 */
class RpcNativeNet(
    private val events: EventSink,
    private val json: Json,
    private val appConnected: () -> Boolean,
) : NativeNet {
    override val isAvailable: Boolean get() = appConnected()

    private val pending = ConcurrentHashMap<String, CompletableFuture<NetFetchResultParams>>()

    override fun execute(
        request: NativeRequest,
        isCancelled: () -> Boolean,
    ): NativeResponse {
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<NetFetchResultParams>()
        pending[id] = future
        try {
            val event =
                NetFetchEvent(
                    id = id,
                    url = request.url,
                    method = request.method,
                    headers = request.headers.map { listOf(it.first, it.second) },
                    bodyBase64 = request.body?.let(Base64.getEncoder()::encodeToString),
                    timeoutMillis = request.timeoutMillis,
                )
            events.emit(RpcEvent("net.fetch", json.encodeToJsonElement(event)))
            // The app may hold the request while a Cloudflare challenge is shown to the user.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis + NetworkPlatform.challengeWaitTimeoutMillis)
            while (true) {
                if (isCancelled()) {
                    events.emit(RpcEvent("net.fetchCancel", json.encodeToJsonElement(mapOf("id" to id))))
                    throw IOException("Canceled")
                }
                val result =
                    try {
                        future.get(100, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        if (System.nanoTime() > deadline) throw SocketTimeoutException("No response from the app for ${request.url}")
                        continue
                    }
                return result.toNativeResponse(request.url)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Called by the `net.fetchResult` RPC. */
    fun complete(result: NetFetchResultParams): Boolean = pending[result.id]?.complete(result) ?: false

    private fun NetFetchResultParams.toNativeResponse(url: String): NativeResponse {
        error?.let { failure ->
            throw when (failure.code) {
                ErrorCode.CLOUDFLARE -> CloudflareBlockedException(url, failure.message)
                ErrorCode.TIMEOUT -> SocketTimeoutException(failure.message)
                ErrorCode.CANCELLED -> IOException("Canceled")
                else -> IOException(failure.message)
            }
        }
        return NativeResponse(
            code = code,
            message = message,
            headers = headers.mapNotNull { pair -> pair.takeIf { it.size == 2 }?.let { it[0] to it[1] } },
            body = bodyBase64?.let(Base64.getDecoder()::decode),
            protocol = protocol,
        )
    }
}
