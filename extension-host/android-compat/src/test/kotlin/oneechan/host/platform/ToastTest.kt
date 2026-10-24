package oneechan.host.platform

import android.widget.Toast
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** android.widget.Toast#show goes to the app ([Toasts.sink]): an extension's hint while a chapter list loads. */
class ToastTest {
    private val shown = mutableListOf<Pair<String, Boolean>>()

    @AfterEach
    fun tearDown() {
        Toasts.sink = null
    }

    @Test
    fun `shown toasts reach the sink with their length`() {
        Toasts.sink = { text, long -> shown += text to long }
        Toast.makeText(null, "Chapter List loading takes time for first time", Toast.LENGTH_LONG).show()
        Toast.makeText(null, "Copied", Toast.LENGTH_SHORT).show()
        val toast = Toast.makeText(null, "a", Toast.LENGTH_SHORT)
        toast.setText("b")
        toast.duration = Toast.LENGTH_LONG
        toast.show()
        toast.cancel()
        assertEquals(Toast.LENGTH_LONG, toast.duration)
        assertEquals(listOf("Chapter List loading takes time for first time" to true, "Copied" to false, "b" to true), shown)
    }

    @Test
    fun `blank toasts and a throwing sink are ignored`() {
        Toasts.sink = { text, _ ->
            shown += text to false
            error("app gone")
        }
        Toast.makeText(null, "  ", Toast.LENGTH_SHORT).show()
        Toast.makeText(null, "x", Toast.LENGTH_SHORT).show()
        assertEquals(listOf("x" to false), shown)
        Toasts.sink = null
        Toast.makeText(null, "printed only", Toast.LENGTH_SHORT).show()
    }
}
