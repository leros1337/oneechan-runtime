package oneechan.host.embedded

import app.cash.quickjs.QuickJs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import oneechan.host.platform.JavaScriptCoreJsEngineFactory
import oneechan.host.platform.JsEngineFactory
import oneechan.host.platform.JsEngines
import oneechan.host.platform.JsSelfTest
import oneechan.host.platform.RhinoJsEngineFactory
import oneechan.host.rpc.RpcRequest
import oneechan.host.rpc.RpcResponse

/**
 * `debug.jsSelfTest {only?, bench?, rhino?, script?}`: runs the QuickJs conformance suite on the
 * engine this JVM selected (JavaScriptCore when the shim registered it), optionally also on Rhino;
 * `bench` times extension-style scripts on each, and `script` times one given script (a real
 * extension workload) in a fresh QuickJs per engine. For the macOS JNI tests and on-device diagnostics.
 */
internal object JsDiagnostics {
    const val SELF_TEST = "debug.jsSelfTest"

    fun handles(method: String): Boolean = method == SELF_TEST

    fun call(request: RpcRequest): RpcResponse {
        val params = request.params as? JsonObject
        val only = params?.get("only")?.jsonPrimitive?.contentOrNull
        val bench = params?.get("bench")?.jsonPrimitive?.booleanOrNull == true
        val alsoRhino = params?.get("rhino")?.jsonPrimitive?.booleanOrNull == true
        val script = params?.get("script")?.jsonPrimitive?.contentOrNull
        val engines = listOfNotNull(JsEngines.selected, RhinoJsEngineFactory.takeIf { alsoRhino && JsEngines.selected !== it })
        val stacks = if (JavaScriptCoreJsEngineFactory.isAvailable) runCatching { JavaScriptCoreJsEngineFactory.stackSizes() }.getOrNull() else null
        val result =
            JsonObject(
                buildMap {
                    put("engine", JsonPrimitive(JsEngines.selected.name))
                    put("nativeAvailable", JsonPrimitive(JavaScriptCoreJsEngineFactory.isAvailable))
                    put("jvm", JsonPrimitive(System.getProperty("java.vm.name").orEmpty()))
                    if (stacks != null) {
                        put("callerStackBytes", JsonPrimitive(stacks[0]))
                        put("workerStackBytes", JsonPrimitive(stacks[1]))
                    }
                    val runs = engines.map { it to JsSelfTest.run(it, only) }
                    val selected = runs.first().second
                    put("summary", JsonPrimitive(JsSelfTest.summary(selected)))
                    put("passed", JsonPrimitive(runs.all { (_, results) -> results.all { it.passed } }))
                    put("results", JsonArray(runs.flatMap { (engine, results) -> results.map { result(engine, it) } }))
                    if (bench) put("timings", JsonArray(engines.flatMap { JsSelfTest.benchmark(it) }.map(::timing)))
                    if (script != null) put("script", JsonArray(engines.map { scriptRun(it, script) }))
                },
            )
        return RpcResponse(request.id, result = result)
    }

    private fun result(
        engine: JsEngineFactory,
        it: JsSelfTest.Result,
    ) = JsonObject(
        mapOf(
            "engine" to JsonPrimitive(engine.name),
            "name" to JsonPrimitive(it.name),
            "passed" to JsonPrimitive(it.passed),
            "skipped" to JsonPrimitive(it.skipped),
            "detail" to JsonPrimitive(it.detail),
            "millis" to JsonPrimitive(it.millis),
        ),
    )

    private fun scriptRun(
        engine: JsEngineFactory,
        script: String,
    ): JsonObject {
        val started = System.nanoTime()
        val outcome = runCatching { JsEngines.withFactory(engine) { QuickJs.create().use { it.evaluate(script, "script.js") } } }
        val millis = (System.nanoTime() - started) / 1e6
        val value = outcome.getOrNull()?.toString().orEmpty()
        return JsonObject(
            mapOf(
                "engine" to JsonPrimitive(engine.name),
                "millis" to JsonPrimitive(millis),
                "ok" to JsonPrimitive(outcome.isSuccess),
                "length" to JsonPrimitive(value.length),
                "hash" to JsonPrimitive(value.hashCode()),
                "head" to JsonPrimitive(outcome.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message}" } ?: value.take(200)),
            ),
        )
    }

    private fun timing(it: JsSelfTest.Timing) =
        JsonObject(
            mapOf(
                "engine" to JsonPrimitive(it.engine),
                "name" to JsonPrimitive(it.name),
                "millis" to JsonPrimitive(it.millis),
                "runs" to JsonPrimitive(it.runs),
            ),
        )
}
