package android.os

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** android.os.HandlerThread on AndroidCompat's Looper, like the drain thread of an offscreen WebView's image reader. */
class HandlerThreadTest {
    @Test
    fun `runnables run on its looper until it quits`() {
        val prepared = CompletableFuture<Thread>()
        val thread =
            object : HandlerThread("drain-test") {
                override fun onLooperPrepared() {
                    prepared.complete(currentThread())
                }
            }
        assertNull(thread.looper, "not started: no looper")
        assertFalse(thread.quitSafely())
        assertEquals(-1, thread.threadId)
        thread.start()
        val looper = assertNotNull(thread.looper)
        assertSame(thread, looper.thread)
        assertSame(thread, prepared.get(5, TimeUnit.SECONDS))
        assertNotEquals(-1, thread.threadId)

        val ran = CompletableFuture<Thread>()
        Handler(looper).post { ran.complete(Thread.currentThread()) }
        assertSame(thread, ran.get(5, TimeUnit.SECONDS))

        val later = CompletableFuture<Boolean>()
        Handler(looper).post { later.complete(true) }
        assertTrue(thread.quitSafely())
        thread.join(5_000)
        assertFalse(thread.isAlive, "quitSafely ends the thread")
        assertTrue(later.getNow(false), "quitSafely runs what was already posted")
        assertNull(thread.looper)
    }

    @Test
    fun `quit ends it too`() {
        val thread = HandlerThread("quit-test", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        assertNotNull(thread.looper)
        assertTrue(thread.quit())
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertEquals("quit-test", thread.name)
    }
}
