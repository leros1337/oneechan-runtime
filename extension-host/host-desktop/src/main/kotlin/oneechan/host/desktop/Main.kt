package oneechan.host.desktop

import com.sun.management.HotSpotDiagnosticMXBean
import oneechan.host.ExtensionHost
import oneechan.host.HostPaths
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * Development/test harness: serves the extension host over HTTP + SSE so the iOS simulator app can
 * use it. Never required at runtime on device (the app embeds the JVM there).
 *
 *   --data DIR      host data dir (default ~/.oneechan-host)
 *   --port N        listen port (default 4568)
 *   --lan           bind 0.0.0.0 instead of 127.0.0.1 (physical device testing)
 *   --token T       require `X-Oneechan-Token: T` on every request
 *   --fake-cf N     also serve the fake Cloudflare-protected site on port N (tests)
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    options["fake-cf"]?.toIntOrNull()?.let { port ->
        FakeCloudflareServer(port).start()
        println("Fake Cloudflare site on http://127.0.0.1:$port/{auto,interactive,never,redirect}/")
    }
    requireArtVerificationMode()

    val data = options["data"]?.let(::Path) ?: Path(System.getProperty("user.home"), ".oneechan-host")
    val paths = HostPaths(data.resolve("data"), data.resolve("cache"), data.resolve("tmp"))
    val events = EventHub()
    val host = ExtensionHost(paths, appConnected = { events.subscriberCount > 0 }, events = events)
    val bind = if ("lan" in options) "0.0.0.0" else "127.0.0.1"
    val port = options["port"]?.toInt() ?: 4568
    val server = RpcHttpServer(host, events, InetSocketAddress(bind, port), options["token"])
    server.start()
    println("Oneechan extension host listening on http://$bind:$port (data: $data)")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
}

/** Extensions are R8-optimized for ART; the host must not run the strict JVM verifier on them. */
private fun requireArtVerificationMode() {
    val bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
    val verifying = runCatching { bean.getVMOption("BytecodeVerificationRemote").value.toBoolean() }.getOrDefault(true)
    if (verifying) {
        System.err.println("Run with -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote (see buildSrc JvmFlags)")
        exitProcess(2)
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i].removePrefix("--")
        val value = args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }
        result[key] = value ?: ""
        i += if (value != null) 2 else 1
    }
    return result
}

@Suppress("unused")
private fun Path.describe() = toAbsolutePath().toString()
