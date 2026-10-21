package oneechan.host.embedded

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import oneechan.host.ExtensionHost
import oneechan.host.HostPaths
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RpcDispatcher
import oneechan.host.rpc.RpcErrorDto
import oneechan.host.rpc.RpcEvent
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse
import kotlin.io.path.Path

/** Where the app keeps the host's files (Application Support, Caches, tmp). */
@Serializable
data class EmbeddedConfig(
    val dataDir: String,
    val cacheDir: String,
    val tmpDir: String,
)

/**
 * The in-process host behind the JNI bridge: UTF-8 JSON bytes in, bytes out. Replies carry the
 * caller's native request id; events go to [emit]. The app is always connected, so extension
 * HTTP goes through the app's network stack (NativeNet events).
 */
class EmbeddedHostCore(
    config: EmbeddedConfig,
    private val reply: (Long, ByteArray) -> Unit,
    private val emit: (ByteArray) -> Unit,
) {
    private val host: ExtensionHost =
        ExtensionHost(
            HostPaths(Path(config.dataDir), Path(config.cacheDir), Path(config.tmpDir)),
            appConnected = { true },
            events = { event -> emit(host.json.encodeToString(RpcEvent.serializer(), event).encodeToByteArray()) },
        )
    private val json = host.json
    private val dispatcher = RpcDispatcher(host)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Returns immediately; the reply is delivered later through [reply]. */
    fun call(
        requestId: Long,
        request: ByteArray,
    ) {
        scope.launch {
            val response =
                try {
                    dispatcher.dispatch(json.decodeFromString(RpcRequest.serializer(), request.decodeToString()))
                } catch (e: Exception) {
                    RpcResponse("", error = RpcErrorDto(ErrorCode.INVALID_PARAMS, "Malformed request: ${e.message}"))
                }
            reply(requestId, json.encodeToString(RpcResponse.serializer(), response).encodeToByteArray())
        }
    }
}
