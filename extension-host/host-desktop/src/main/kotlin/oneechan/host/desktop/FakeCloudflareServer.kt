package oneechan.host.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Deterministic stand-in for a Cloudflare-protected site (tests only). Clearance cookies are bound
 * to the requester's User-Agent like the real thing.
 *  - `/auto/...`        challenge page sets `cf_clearance` by script after 2 s
 *  - `/interactive/...` clearance only after clicking the `#turnstile-wrapper` button
 *  - `/never/...`       never solvable
 *  - `/redirect/...`    302 to `/auto/...` (redirects + cookies across hops)
 */
class FakeCloudflareServer(
    port: Int,
) {
    private val secret = UUID.randomUUID().toString()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/", ::handle)
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    private fun token(userAgent: String): String =
        MessageDigest.getInstance("SHA-256").digest("$secret|$userAgent".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val path = exchange.requestURI.path
            val ua = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val cookies =
                exchange.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }.associate {
                    val (k, v) = (it.split('=', limit = 2) + "").take(2)
                    k.trim() to v.trim()
                }
            val mode = path.trim('/').substringBefore('/')
            if (mode == "redirect") {
                exchange.responseHeaders.add("Location", path.replaceFirst("/redirect", "/auto"))
                exchange.sendResponseHeaders(302, -1)
                return
            }
            val token = token(ua)
            if (mode != "never" && cookies["cf_clearance"] == token) {
                respond(exchange, 200, "<html><head><title>Protected</title></head><body>Protected content ($mode)</body></html>", emptyMap())
                return
            }
            val solve =
                when (mode) {
                    "auto" -> "<script>setTimeout(function(){document.cookie='cf_clearance=$token; path=/';location.reload();},2000)</script>"
                    "interactive" ->
                        "<div id=\"turnstile-wrapper\"><button id=\"verify\" style=\"font-size:32px\" " +
                            "onclick=\"document.cookie='cf_clearance=$token; path=/';location.reload();\">Verify</button></div>"
                    else -> ""
                }
            respond(
                exchange,
                403,
                "<html><head><title>Just a moment...</title></head><body><div id=\"challenge-stage\">Checking your browser</div>$solve</body></html>",
                mapOf("Server" to "cloudflare", "cf-mitigated" to "challenge"),
            )
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        html: String,
        headers: Map<String, String>,
    ) {
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        val bytes = html.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
