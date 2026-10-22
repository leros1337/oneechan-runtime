package oneechan.host.embedded

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import oneechan.host.platform.Graphics
import oneechan.host.platform.GraphicsSelfTest
import oneechan.host.platform.NativeGraphicsBackend
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse

/**
 * `debug.graphicsSelfTest {only?}`: runs the android.graphics conformance suite on the backend this
 * JVM selected (the app's CoreGraphics one when the shim registered it). For the macOS JNI tests
 * and on-device diagnostics; it only reads bundled fixtures and allocates small bitmaps.
 */
internal object GraphicsDiagnostics {
    const val SELF_TEST = "debug.graphicsSelfTest"

    fun handles(method: String): Boolean = method == SELF_TEST

    fun call(request: RpcRequest): RpcResponse {
        val only = (request.params as? JsonObject)?.get("only")?.jsonPrimitive?.contentOrNull
        val backend = Graphics.backend()
        val results = GraphicsSelfTest.run(backend, only)
        val result =
            JsonObject(
                mapOf(
                    "backend" to JsonPrimitive(backend.name),
                    "nativeAvailable" to JsonPrimitive(NativeGraphicsBackend.isAvailable),
                    "jvm" to JsonPrimitive(System.getProperty("java.vm.name").orEmpty()),
                    "summary" to JsonPrimitive(GraphicsSelfTest.summary(results)),
                    "passed" to JsonPrimitive(results.all { it.passed }),
                    "results" to
                        JsonArray(
                            results.map {
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive(it.name),
                                        "passed" to JsonPrimitive(it.passed),
                                        "skipped" to JsonPrimitive(it.skipped),
                                        "detail" to JsonPrimitive(it.detail),
                                        "millis" to JsonPrimitive(it.millis),
                                    ),
                                )
                            },
                        ),
                ),
            )
        return RpcResponse(request.id, result = result)
    }
}
