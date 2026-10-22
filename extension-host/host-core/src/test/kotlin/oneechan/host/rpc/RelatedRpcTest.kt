package oneechan.host.rpc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import oneechan.host.TestHost
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `source.related` is routed like the other source calls, and hosts that have it say so. */
class RelatedRpcTest {
    @Test
    fun `hosts advertise related titles`() {
        assertTrue("source.related" in TestHost.host.hello().capabilities)
        assertTrue("source.related" in RpcDispatcher(TestHost.host).methods)
    }

    @Test
    fun `an unknown source is reported as such`() {
        val params =
            buildJsonObject {
                put("sourceId", "424242")
                put("manga", buildJsonObject { put("url", "/manga/1"); put("title", "One") })
            }
        val response = TestHost.blocking { RpcDispatcher(TestHost.host).dispatch(RpcRequest("1", "source.related", params)) }
        assertEquals(ErrorCode.SOURCE_NOT_FOUND, response.error?.code)
        assertEquals("424242", response.error?.sourceId)
    }

    @Test
    fun `invalid params are refused`() {
        val response =
            TestHost.blocking {
                RpcDispatcher(TestHost.host).dispatch(RpcRequest("2", "source.related", buildJsonObject { put("sourceId", JsonPrimitive("1")) }))
            }
        assertEquals(ErrorCode.INVALID_PARAMS, response.error?.code)
    }
}
