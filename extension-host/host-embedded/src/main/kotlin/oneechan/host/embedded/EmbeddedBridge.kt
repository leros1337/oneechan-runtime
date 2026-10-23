package oneechan.host.embedded

import com.sun.management.HotSpotDiagnosticMXBean
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory

/**
 * JNI entry points used by the app's native shim (OneechanJVM). `complete`, `emit`, `fetch` and
 * `fetchCancel` are registered by the shim with RegisterNatives; `start`, `call` and `fetchResult`
 * are invoked from its threads.
 */
object EmbeddedBridge {
    @Volatile private var core: EmbeddedHostCore? = null

    /** Boots the host. Returns null on success, else an error message for the app. */
    @JvmStatic
    fun start(config: ByteArray): String? =
        try {
            check(core == null) { "The extension host is already running" }
            check(!verifiesRemoteBytecode()) {
                "The JVM must not verify extension bytecode (extensions are R8-optimized for ART): run HotSpot with " +
                    "-XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote, OpenJ9 with -Xverify:none"
            }
            val parsed = Json.decodeFromString(EmbeddedConfig.serializer(), config.decodeToString())
            core = EmbeddedHostCore(parsed, reply = ::complete, emit = ::emit, fetch = ::fetch, fetchCancel = ::fetchCancel)
            null
        } catch (e: Throwable) {
            e.printStackTrace()
            "${e::class.simpleName}: ${e.message}"
        }

    @JvmStatic
    fun call(
        requestId: Long,
        request: ByteArray,
    ) {
        val core = core
        if (core == null) {
            complete(requestId, """{"id":"","error":{"code":"INTERNAL","message":"The extension host is not running","retryable":false}}""".encodeToByteArray())
            return
        }
        core.call(requestId, request)
    }

    /** The app's answer to [fetch] [id]: `net.fetchResult` params without the body (JSON), and the raw body. */
    @JvmStatic
    fun fetchResult(
        id: Long,
        meta: ByteArray,
        body: ByteArray?,
    ) {
        core?.fetchResult(id, meta, body)
    }

    @JvmStatic external fun complete(
        requestId: Long,
        response: ByteArray,
    )

    @JvmStatic external fun emit(event: ByteArray)

    /** Has the app perform extension HTTP request [id]: a `net.fetch` event without the body (JSON), and the raw body. */
    @JvmStatic external fun fetch(
        id: Long,
        meta: ByteArray,
        body: ByteArray?,
    )

    /** The answer to request [id] is no longer needed (cancelled or timed out). */
    @JvmStatic external fun fetchCancel(id: Long)

    private fun verifiesRemoteBytecode(): Boolean =
        BytecodeVerification.remoteVerificationEnabled(
            vmName = System.getProperty("java.vm.name").orEmpty(),
            inputArguments = runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrDefault(emptyList()),
            hotSpotFlag = {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).getVMOption("BytecodeVerificationRemote").value
            },
        )
}
