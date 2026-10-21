package oneechan.host.embedded

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedHostCoreTest {
    @TempDir lateinit var dir: Path

    private val replies = ConcurrentHashMap<Long, JsonObject>()
    private val latch = CountDownLatch(3)

    private fun core() =
        EmbeddedHostCore(
            EmbeddedConfig(dataDir = dir.resolve("data").toString(), cacheDir = dir.resolve("cache").toString(), tmpDir = dir.resolve("tmp").toString()),
            reply = { id, bytes ->
                replies[id] = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                latch.countDown()
            },
            emit = {},
        )

    @Test
    fun answersRequestsByNativeIdWithTheRpcEnvelope() {
        val core = core()
        core.call(7, """{"id":"a","method":"host.hello","params":{}}""".encodeToByteArray())
        core.call(8, """{"id":"b","method":"no.such","params":{}}""".encodeToByteArray())
        core.call(9, "not json".encodeToByteArray())
        assertTrue(latch.await(30, TimeUnit.SECONDS))

        val hello = replies.getValue(7)
        assertEquals("a", hello["id"]?.jsonPrimitive?.content)
        assertTrue(hello["result"]!!.jsonObject.containsKey("hostVersion"))
        assertEquals("METHOD_NOT_FOUND", replies.getValue(8)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("INVALID_PARAMS", replies.getValue(9)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
