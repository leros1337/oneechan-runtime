package android.app

import android.content.ContextWrapper
import android.content.DialogInterface
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** android.app.Dialog and Presentation: extensions host an offscreen WebView in a Presentation on a virtual display. */
class DialogTest {
    private val context = ContextWrapper(null)

    @Test
    fun `content views, showing and the listeners`() {
        val dialog = Dialog(context)
        val events = mutableListOf<String>()
        dialog.setOnShowListener { events += "show" }
        dialog.setOnCancelListener { events += "cancel" }
        dialog.setOnDismissListener { events += "dismiss" }
        val content = View(context).apply { id = 7 }
        dialog.setContentView(content, ViewGroup.LayoutParams(100, 200))
        assertTrue(content.parent is ViewGroup)
        assertEquals(100, content.layoutParams.width)
        assertSame(content, dialog.findViewById(7))
        assertNull(dialog.findViewById<View>(8))
        assertSame(context, dialog.context)
        assertNull(dialog.window)

        assertFalse(dialog.isShowing)
        dialog.dismiss()
        assertTrue(events.isEmpty(), "dismissing a dialog that is not showing does nothing")
        dialog.show()
        assertTrue(dialog.isShowing)
        dialog.hide()
        assertFalse(dialog.isShowing)
        dialog.show()
        dialog.cancel()
        assertFalse(dialog.isShowing)
        assertEquals(listOf("show", "show", "cancel", "dismiss"), events)

        // Another content view replaces the first.
        val other = View(context)
        dialog.setContentView(other)
        assertNull(content.parent)
        assertTrue(other.parent is ViewGroup)
        val dismissed = mutableListOf<DialogInterface>()
        dialog.setOnDismissListener { dismissed += it }
        dialog.show()
        dialog.dismiss()
        assertEquals(listOf<DialogInterface>(dialog), dismissed)
    }

    @Test
    fun `a presentation is shown on its display`() {
        val displays = DisplayManager(null)
        val virtual = assertNotNull(displays.createVirtualDisplay("p", 300, 600, 480, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
        val presentation = Presentation(context, virtual.display)
        assertSame(virtual.display, presentation.display)
        assertEquals(3f, presentation.resources.displayMetrics.density)
        val view = View(context)
        presentation.setContentView(view, ViewGroup.LayoutParams(300, 600))
        presentation.show()
        assertTrue(presentation.isShowing)
        presentation.onDisplayChanged()
        presentation.onDisplayRemoved()
        presentation.dismiss()
        assertFalse(presentation.isShowing)
        assertTrue(view.parent is ViewGroup, "dismissing keeps the content")
        virtual.release()
        assertThrows<IllegalArgumentException> { Presentation(context, null as Display?) }
    }
}
