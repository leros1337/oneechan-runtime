package android.hardware.display

import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Displays, virtual displays and image readers: what an extension sets up to host an offscreen WebView. */
class DisplayManagerTest {
    private val displays = DisplayManager(null)

    @Test
    fun `an image reader has a surface and never a frame`() {
        val reader = ImageReader.newInstance(1170, 2532, PixelFormat.RGBA_8888, 2)
        assertEquals(1170, reader.width)
        assertEquals(2532, reader.height)
        assertEquals(PixelFormat.RGBA_8888, reader.imageFormat)
        assertEquals(2, reader.maxImages)
        val surface = reader.surface
        assertTrue(surface.isValid)
        assertSame(surface, reader.surface)
        assertNull(reader.acquireLatestImage())
        assertNull(reader.acquireNextImage())
        val drain = HandlerThread("reader-test").apply { start() }
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
        reader.setOnImageAvailableListener(null, null)
        drain.quitSafely()
        drain.join()
        reader.close()
        assertFalse(surface.isValid, "closing the reader releases its surface")
        reader.close()
        assertThrows<IllegalArgumentException> { ImageReader.newInstance(0, 10, PixelFormat.RGBA_8888, 2) }
        assertThrows<IllegalArgumentException> { ImageReader.newInstance(10, 10, PixelFormat.RGBA_8888, 0) }
    }

    @Test
    fun `the default display is the device's`() {
        val display = assertNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
        assertEquals(Display.DEFAULT_DISPLAY, display.displayId)
        assertTrue(display.isValid)
        val metrics = DisplayMetrics()
        display.getMetrics(metrics)
        assertTrue(metrics.equals(Resources.getSystem().displayMetrics))
        display.getRealMetrics(metrics)
        assertEquals(1170, metrics.widthPixels)
        assertEquals(0, display.rotation)
        assertEquals(60f, display.refreshRate)
        assertEquals(Display.STATE_ON, display.state)
        assertEquals(1170, display.mode.physicalWidth)
        assertEquals(2532, display.mode.physicalHeight)
        val rect = Rect()
        display.getRectSize(rect)
        assertEquals(Rect(0, 0, 1170, 2532), rect)
        assertContains(displays.displays.toList(), display)
    }

    @Test
    fun `virtual displays come and go`() {
        val reader = ImageReader.newInstance(300, 600, PixelFormat.RGBA_8888, 2)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val virtual = assertNotNull(displays.createVirtualDisplay("reader-webview", 300, 600, 480, reader.surface, flags))
        val display = virtual.display
        assertTrue(display.displayId > Display.DEFAULT_DISPLAY)
        assertEquals("reader-webview", display.name)
        assertTrue(display.flags and Display.FLAG_PRESENTATION != 0)
        assertTrue(display.flags and Display.FLAG_PRIVATE != 0, "not public")
        assertSame(reader.surface, virtual.surface)
        assertSame(display, displays.getDisplay(display.displayId))
        assertContains(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList(), display)
        val metrics = DisplayMetrics()
        display.getMetrics(metrics)
        assertEquals(300, metrics.widthPixels)
        assertEquals(600, metrics.heightPixels)
        assertEquals(480, metrics.densityDpi)
        assertEquals(3f, metrics.density)

        virtual.resize(320, 640, 320)
        display.getMetrics(metrics)
        assertEquals(320, metrics.widthPixels)
        assertEquals(2f, metrics.density)
        virtual.setSurface(null)
        assertNull(virtual.surface)

        virtual.release()
        assertFalse(display.isValid)
        assertNull(displays.getDisplay(display.displayId))
        assertFalse(display in displays.displays)
        virtual.release()
        reader.close()
        assertThrows<IllegalArgumentException> { displays.createVirtualDisplay("x", 0, 10, 480, null, 0) }
        displays.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit
            },
            null,
        )
    }
}
