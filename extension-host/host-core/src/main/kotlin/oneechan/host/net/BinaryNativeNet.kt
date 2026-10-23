package oneechan.host.net

import kotlinx.serialization.json.Json
import oneechan.host.rpc.NetFetchEvent
import oneechan.host.rpc.NetFetchResultParams
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded [NativeNet]: the app performs each request with its platform stack, reached in-process
 * (JNI, see EmbeddedBridge). Only the metadata is JSON, a [NetFetchEvent] and a
 * [NetFetchResultParams] without `bodyBase64`; bodies cross as raw bytes, so the interpreter
 * never parses or decodes them. [RpcNativeNet] is the same exchange over JSON-RPC (development).
 */
class BinaryNativeNet(
    /** Hands request `id` to the app: its metadata (UTF-8 JSON) and raw body. Returns at once. */
    private val send: (id: Long, meta: ByteArray, body: ByteArray?) -> Unit,
    /** Tells the app the answer to request `id` is no longer needed. */
    private val cancel: (id: Long) -> Unit,
) : NativeNet {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Reply>>()

    private class Reply(
        val meta: ByteArray,
        val body: ByteArray?,
    )

    override fun execute(
        request: NativeRequest,
        isCancelled: () -> Boolean,
    ): NativeResponse {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<Reply>()
        pending[id] = future
        try {
            val meta =
                NetFetchEvent(
                    id = id.toString(),
                    url = request.url,
                    method = request.method,
                    headers = request.headers.map { listOf(it.first, it.second) },
                    timeoutMillis = request.timeoutMillis,
                )
            send(id, json.encodeToString(NetFetchEvent.serializer(), meta).encodeToByteArray(), request.body)
            // The app may hold the request while a Cloudflare challenge is shown to the user.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis + NetworkPlatform.challengeWaitTimeoutMillis)
            while (true) {
                if (isCancelled()) {
                    cancel(id)
                    throw IOException("Canceled")
                }
                val reply =
                    try {
                        future.get(100, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        if (System.nanoTime() > deadline) {
                            cancel(id)
                            throw SocketTimeoutException("No response from the app for ${request.url}")
                        }
                        continue
                    }
                val result = json.decodeFromString(NetFetchResultParams.serializer(), reply.meta.decodeToString())
                return result.toNativeResponse(request.url, reply.body)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** The app's answer to request [id]: [meta] is `net.fetchResult` params without the body. False when no longer awaited. */
    fun complete(
        id: Long,
        meta: ByteArray,
        body: ByteArray?,
    ): Boolean = pending[id]?.complete(Reply(meta, body)) ?: false

    private companion object {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}
