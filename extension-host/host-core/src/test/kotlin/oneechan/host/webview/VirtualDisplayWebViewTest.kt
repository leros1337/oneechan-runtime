package oneechan.host.webview

import android.app.Application
import android.app.Presentation
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import oneechan.host.TestHost
import oneechan.host.rpc.InitParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An extension that renders its offscreen reader WebView on a private virtual display: the WebView goes into a
 * Presentation on a display that feeds an ImageReader drained by a HandlerThread, and the whole setup goes away when
 * the WebView leaves its parent, as the teardown of keiyoushi's WebView helpers removes it before destroying it.
 * Runs on the host's main Looper with the app's Application context and an app-backed WebView.
 */
class VirtualDisplayWebViewTest {
    private val host = TestHost.host

    @AfterEach
    fun tearDown() {
        host.init(InitParams(webView = false))
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test
    fun `a WebView hosted on a virtual display is released when it leaves its parent`() {
        host.init(InitParams(webView = true))
        val webView = onMain { WebView(Injekt.get<Application>()) }
        lateinit var hosted: OffscreenHost
        try {
            onMain {
                // Sized to the screen as Android would lay it out: layout params, an exact measure, a layout.
                val screen = Resources.getSystem().displayMetrics
                val width = screen.widthPixels
                val height = screen.heightPixels
                webView.layoutParams = ViewGroup.LayoutParams(width, height)
                val exactly = View.MeasureSpec.EXACTLY
                webView.measure(View.MeasureSpec.makeMeasureSpec(width, exactly), View.MeasureSpec.makeMeasureSpec(height, exactly))
                webView.layout(0, 0, width, height)
                hosted = OffscreenHost.attach(webView)
                webView.settings.setSupportMultipleWindows(true)
            }
            assertIs<ViewGroup>(webView.parent)
            assertTrue(hosted.presentation.isShowing)
            assertTrue(hosted.drain.isAlive)
            assertTrue(hosted.display.display.isValid)
            assertSame(hosted.display.display, hosted.presentation.display)
            assertTrue(hosted.reader.surface.isValid)
        } finally {
            // The usual teardown (also after a failure, so no WebView is left for other tests).
            onMain {
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                webView.stopLoading()
                webView.destroy()
            }
        }
        assertTrue(hosted.released.await(5, TimeUnit.SECONDS), "leaving the parent releases everything")
        assertFalse(hosted.drain.isAlive, "the drain thread ended")
        assertFalse(hosted.reader.surface.isValid, "the image reader is closed")
        assertFalse(hosted.presentation.isShowing)
        assertFalse(hosted.display.display.isValid, "the virtual display is released")
    }

    /** Puts a WebView on its own virtual display; [release] undoes it, triggered by the WebView's removal. */
    private class OffscreenHost(
        val reader: ImageReader,
        val drain: HandlerThread,
        val display: VirtualDisplay,
        val presentation: Presentation,
    ) {
        val released = CountDownLatch(1)

        fun release() {
            presentation.dismiss()
            display.release()
            reader.setOnImageAvailableListener(null, null)
            drain.quitSafely()
            drain.join()
            reader.close()
            released.countDown()
        }

        companion object {
            fun attach(webView: WebView): OffscreenHost {
                val metrics = webView.resources.displayMetrics
                val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
                val drain = HandlerThread("webview-drain").also(Thread::start)
                // Frames would pile up in the reader otherwise; none arrive here, but the listener is set up anyway.
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
                val displays = webView.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                val display =
                    checkNotNull(displays.createVirtualDisplay("reader-webview", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, reader.surface, flags))
                val presentation = Presentation(webView.context.applicationContext, display.display)
                presentation.setContentView(webView, ViewGroup.LayoutParams(metrics.widthPixels, metrics.heightPixels))
                presentation.show()
                val hosted = OffscreenHost(reader, drain, display, presentation)
                (webView.parent as ViewGroup).setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(
                            parent: View,
                            child: View,
                        ) = Unit

                        override fun onChildViewRemoved(
                            parent: View,
                            child: View,
                        ) {
                            if (child === webView) hosted.release()
                        }
                    },
                )
                return hosted
            }
        }
    }
}
