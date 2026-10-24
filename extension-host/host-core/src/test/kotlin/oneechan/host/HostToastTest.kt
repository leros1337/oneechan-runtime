package oneechan.host

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.decodeFromJsonElement
import oneechan.host.rpc.HostToastEvent
import oneechan.host.rpc.InitParams
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** An extension's Toast reaches the app as a `host.toast` event once the app is connected (`host.init`). */
class HostToastTest {
    @Test
    fun `shown toasts become host toast events`() {
        val host = TestHost.host
        host.init(InitParams())
        // Extensions post their toasts to the main Looper.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(null, "Chapter List loading takes time for first time", Toast.LENGTH_LONG).show()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var toast: HostToastEvent? = null
        while (toast == null && System.nanoTime() < deadline) {
            val event = TestHost.events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (event.event == "host.toast") toast = host.json.decodeFromJsonElement<HostToastEvent>(event.data)
        }
        assertEquals(HostToastEvent("Chapter List loading takes time for first time", long = true), toast)
    }
}
