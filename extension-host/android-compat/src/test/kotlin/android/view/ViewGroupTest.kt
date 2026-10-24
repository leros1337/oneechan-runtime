package android.view

import android.content.ContextWrapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** View parents and ViewGroup children, as a WebView hosted in a Presentation and keiyoushi's runWebView teardown use them. */
class ViewGroupTest {
    private val context = ContextWrapper(null)

    private class Group(
        context: android.content.Context,
    ) : ViewGroup(context) {
        val events = mutableListOf<String>()

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) = Unit

        override fun onViewAdded(child: View) {
            events += "added ${child.id}"
        }

        override fun onViewRemoved(child: View) {
            events += "removed ${child.id}"
        }
    }

    private fun view(id: Int) = View(context).apply { this.id = id }

    @Test
    fun `children get a parent and the listener hears of them`() {
        val group = Group(context)
        val heard = mutableListOf<String>()
        group.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(
                    parent: View,
                    child: View,
                ) {
                    assertSame(group, parent)
                    heard += "added ${child.id} parent=${child.parent === group}"
                }

                override fun onChildViewRemoved(
                    parent: View,
                    child: View,
                ) {
                    assertSame(group, parent)
                    heard += "removed ${child.id} parent=${child.parent}"
                }
            },
        )
        val a = view(1)
        val b = view(2)
        val c = view(3)
        assertNull(a.parent)
        group.addView(a)
        group.addView(b, ViewGroup.LayoutParams(10, 20))
        group.addView(c, 0)
        assertSame(group, a.parent)
        assertEquals(3, group.childCount)
        assertEquals(listOf(c, a, b), List(group.childCount) { group.getChildAt(it) })
        assertEquals(2, group.indexOfChild(b))
        assertEquals(-1, group.indexOfChild(view(9)))
        assertNull(group.getChildAt(5))
        assertEquals(10, b.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, a.layoutParams.width, "default layout params")

        group.removeView(a)
        assertNull(a.parent)
        group.removeView(view(8))
        group.removeViewAt(0)
        assertNull(c.parent)
        assertEquals(listOf(b), List(group.childCount) { group.getChildAt(it) })
        group.removeAllViews()
        assertEquals(0, group.childCount)
        assertEquals(
            listOf(
                "added 1 parent=true",
                "added 2 parent=true",
                "added 3 parent=true",
                "removed 1 parent=null",
                "removed 3 parent=null",
                "removed 2 parent=null",
            ),
            heard,
        )
        assertEquals(listOf("added 1", "added 2", "added 3", "removed 1", "removed 3", "removed 2"), group.events)
    }

    @Test
    fun `a child has one parent`() {
        val first = Group(context)
        val second = Group(context)
        val child = view(1)
        first.addView(child)
        val error = assertThrows<IllegalStateException> { second.addView(child) }
        assertTrue(error.message!!.startsWith("The specified child already has a parent"))
        first.removeView(child)
        second.addView(child, 40, 50)
        assertSame(second, child.parent)
        assertEquals(40, child.layoutParams.width)
        assertEquals(50, child.layoutParams.height)
        assertThrows<IllegalArgumentException> { second.addView(null) }
        assertThrows<IndexOutOfBoundsException> { second.removeViewAt(3) }
    }

    @Test
    fun `views are found by id`() {
        val root = Group(context).apply { id = 10 }
        val inner = Group(context).apply { id = 11 }
        val leaf = view(12)
        inner.addView(leaf)
        root.addView(inner)
        assertSame(leaf, root.findViewById(12))
        assertSame(inner, root.findViewById(11))
        assertSame(root, root.findViewById(10))
        assertNull(root.findViewById(13))
        assertNull(root.findViewById(View.NO_ID))
        assertEquals(View.NO_ID, View(context).id)
    }

    /** keiyoushi's setupWebView: layout params, measure and layout to the display size. */
    @Test
    fun `layout params, measure and layout are kept`() {
        val view = View(context)
        view.layoutParams = ViewGroup.LayoutParams(1170, 2532)
        assertEquals(1170, view.layoutParams.width)
        assertThrows<NullPointerException> { view.layoutParams = null }
        val exactly = View.MeasureSpec.makeMeasureSpec(1170, View.MeasureSpec.EXACTLY)
        assertEquals(View.MeasureSpec.EXACTLY, View.MeasureSpec.getMode(exactly))
        assertEquals(1170, View.MeasureSpec.getSize(exactly))
        view.measure(exactly, View.MeasureSpec.makeMeasureSpec(2532, View.MeasureSpec.AT_MOST))
        assertEquals(1170, view.measuredWidth)
        assertEquals(2532, view.measuredHeight)
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), exactly)
        assertEquals(0, view.measuredWidth)
        view.layout(0, 10, 1170, 2542)
        assertEquals(1170, view.width)
        assertEquals(2532, view.height)
        assertEquals(10, view.top)
        assertEquals(2542, view.bottom)
        val group = Group(context)
        group.layout(0, 0, 300, 600)
        assertEquals(600, group.height)
    }
}
