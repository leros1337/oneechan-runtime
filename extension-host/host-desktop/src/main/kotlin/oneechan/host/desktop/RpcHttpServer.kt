package oneechan.host.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import oneechan.host.EventSink
import oneechan.host.ExtensionHost
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RpcDispatcher
import oneechan.host.rpc.RpcErrorDto
import oneechan.host.rpc.RpcEvent
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Fan-out of host events to every connected SSE client. */
class EventHub : EventSink {
    private val clients = CopyOnWriteArrayList<LinkedBlockingQueue<RpcEvent>>()

    override fun emit(event: RpcEvent) = clients.forEach { it.offer(event) }

    fun subscribe(): LinkedBlockingQueue<RpcEvent> = LinkedBlockingQueue<RpcEvent>().also(clients::add)

    val subscriberCount: Int get() = clients.size

    fun unsubscribe(queue: LinkedBlockingQueue<RpcEvent>) {
        clients.remove(queue)
    }
}

/**
 * `POST /rpc` takes one [RpcRequest] and answers with its [RpcResponse] (requests run concurrently;
 * cancel one with `rpc.cancel {id}`). `GET /events` is a Server-Sent Events stream of [RpcEvent]s.
 */
class RpcHttpServer(
    private val host: ExtensionHost,
    private val events: EventHub,
    address: InetSocketAddress,
    private val token: String?,
) {
    private val json: Json = host.json
    private val dispatcher = RpcDispatcher(host)
    private val server = HttpServer.create(address, 64)

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "rpc-http").apply { isDaemon = true } }
        server.createContext("/rpc", ::handleRpc)
        server.createContext("/events", ::handleEvents)
        server.createContext("/file", ::handleFile)
        server.createContext("/health") { exchange -> exchange.respond(200, "ok".toByteArray(), "text/plain") }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    private fun authorized(exchange: HttpExchange): Boolean {
        if (token.isNullOrEmpty() || exchange.requestHeaders.getFirst("X-Oneechan-Token") == token) return true
        exchange.respond(401, "unauthorized".toByteArray(), "text/plain")
        return false
    }

    private fun handleRpc(exchange: HttpExchange) {
        exchange.use {
            if (!authorized(exchange)) return
            if (exchange.requestMethod != "POST") return exchange.respond(405, ByteArray(0), "text/plain")
            val response =
                try {
                    val request = json.decodeFromString(RpcRequest.serializer(), exchange.requestBody.readBytes().decodeToString())
                    runBlocking { dispatcher.dispatch(request) }
                } catch (e: Exception) {
                    RpcResponse("", error = RpcErrorDto(ErrorCode.INVALID_PARAMS, "Malformed request: ${e.message}"))
                }
            exchange.respond(200, json.encodeToString(RpcResponse.serializer(), response).toByteArray(), "application/json")
        }
    }

    /**
     * Serves a file the host produced (images, icons) to a client on another machine. Only files
     * under the host's cache dir or icon dir are served; `consume=1` deletes cache files afterwards
     * (ownership of fetched images passes to the client).
     */
    private fun handleFile(exchange: HttpExchange) {
        exchange.use {
            if (!authorized(exchange)) return
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull {
                val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
            }.toMap()
            val file = query["path"]?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }
            val roots = listOf(host.paths.cacheDir, host.paths.iconsDir).map { it.toAbsolutePath().normalize() }
            if (file == null || roots.none { file.startsWith(it) } || !java.nio.file.Files.isRegularFile(file)) {
                return exchange.respond(404, "not found".toByteArray(), "text/plain")
            }
            exchange.respond(200, java.nio.file.Files.readAllBytes(file), "application/octet-stream")
            if (query["consume"] == "1" && file.startsWith(host.paths.cacheDir.toAbsolutePath().normalize())) {
                java.nio.file.Files.deleteIfExists(file)
            }
        }
    }

    private fun handleEvents(exchange: HttpExchange) {
        if (!authorized(exchange)) return exchange.close()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val queue = events.subscribe()
        try {
            exchange.responseBody.use { out ->
                out.write(": connected\n\n".toByteArray())
                out.flush()
                while (true) {
                    val event = queue.poll(15, TimeUnit.SECONDS)
                    val frame =
                        if (event == null) {
                            ": keep-alive\n\n"
                        } else {
                            "event: ${event.event}\ndata: ${json.encodeToString(RpcEvent.serializer(), event)}\n\n"
                        }
                    out.write(frame.toByteArray())
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // client went away
        } finally {
            events.unsubscribe(queue)
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray,
        contentType: String,
    ) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
    }
}
