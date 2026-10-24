package android.view

import android.content.res.Resources
import android.graphics.Matrix
import android.util.DisplayMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** android.view.MotionEvent as extensions build them to tap a WebView, plus the display they measure with. */
class MotionEventTest {
    private fun finger(
        x: Float,
        y: Float,
        id: Int = 0,
    ): Pair<MotionEvent.PointerProperties, MotionEvent.PointerCoords> =
        MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } to
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 0.4f
                touchMajor = 60f
                touchMinor = 45f
                toolMajor = 60f
                toolMinor = 45f
                orientation = 0.3f
                size = 0.02f
            }

    @Test
    fun `constants have Android's values`() {
        assertEquals(0, MotionEvent.ACTION_DOWN)
        assertEquals(1, MotionEvent.ACTION_UP)
        assertEquals(2, MotionEvent.ACTION_MOVE)
        assertEquals(3, MotionEvent.ACTION_CANCEL)
        assertEquals(5, MotionEvent.ACTION_POINTER_DOWN)
        assertEquals(6, MotionEvent.ACTION_POINTER_UP)
        assertEquals(0xff, MotionEvent.ACTION_MASK)
        assertEquals(0xff00, MotionEvent.ACTION_POINTER_INDEX_MASK)
        assertEquals(8, MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals(1, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(2, MotionEvent.AXIS_PRESSURE)
        assertEquals(8, MotionEvent.AXIS_ORIENTATION)
        assertEquals(-1, MotionEvent.INVALID_POINTER_ID)
        assertEquals(4098, InputDevice.SOURCE_TOUCHSCREEN)
    }

    /** A fingertip's event: the 14-argument obtain, dispatched, then recycled. */
    @Test
    fun `the pointer form keeps every value and copies its arrays`() {
        val (properties, coords) = finger(90f, 180f)
        val event =
            MotionEvent.obtain(
                1_000L, 1_016L, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        coords.x = -1f
        properties.id = 9
        assertEquals(MotionEvent.ACTION_DOWN, event.action)
        assertEquals(MotionEvent.ACTION_DOWN, event.actionMasked)
        assertEquals(0, event.actionIndex)
        assertEquals(1, event.pointerCount)
        assertEquals(0, event.getPointerId(0))
        assertEquals(0, event.findPointerIndex(0))
        assertEquals(-1, event.findPointerIndex(3))
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, event.getToolType(0))
        assertEquals(90f, event.x)
        assertEquals(180f, event.y)
        assertEquals(90f, event.getX(0))
        assertEquals(180f, event.getY(0))
        assertEquals(90f, event.rawX)
        assertEquals(180f, event.rawY)
        assertEquals(0.4f, event.pressure)
        assertEquals(0.02f, event.size)
        assertEquals(60f, event.touchMajor)
        assertEquals(45f, event.touchMinor)
        assertEquals(60f, event.toolMajor)
        assertEquals(45f, event.toolMinor)
        assertEquals(0.3f, event.orientation)
        assertEquals(0.4f, event.getAxisValue(MotionEvent.AXIS_PRESSURE))
        assertEquals(1_000L, event.downTime)
        assertEquals(1_016L, event.eventTime)
        assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source)
        assertTrue(event.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
        assertEquals(0, event.metaState)
        assertEquals(0, event.buttonState)
        assertEquals(0, event.deviceId)
        assertEquals(0, event.edgeFlags)
        assertEquals(0, event.flags)
        assertEquals(1f, event.xPrecision)
        assertEquals(0, event.historySize)
        assertNull(event.device)
        val out = MotionEvent.PointerCoords()
        event.getPointerCoords(0, out)
        assertEquals(90f, out.x)
        val props = MotionEvent.PointerProperties()
        event.getPointerProperties(0, props)
        assertEquals(0, props.id)
        assertTrue(event.toString().startsWith("MotionEvent { action=ACTION_DOWN"), event.toString())
        event.recycle()
    }

    @Test
    fun `the short form fills Android's defaults`() {
        val event = MotionEvent.obtain(5L, 7L, MotionEvent.ACTION_UP, 10f, 20f, 0)
        assertEquals(MotionEvent.ACTION_UP, event.action)
        assertEquals(1, event.pointerCount)
        assertEquals(0, event.getPointerId(0))
        assertEquals(10f, event.x)
        assertEquals(20f, event.y)
        assertEquals(1f, event.pressure)
        assertEquals(1f, event.size)
        assertEquals(1f, event.xPrecision)
        assertEquals(1f, event.yPrecision)
        assertEquals(0, event.deviceId)
        assertEquals(0, event.edgeFlags)
        assertEquals(5L, event.downTime)
        assertEquals(7L, event.eventTime)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertEquals(InputDevice.SOURCE_TOUCHSCREEN, event.source)
    }

    @Test
    fun `pointer actions carry the pointer index`() {
        val (p0, c0) = finger(1f, 2f, id = 0)
        val (p1, c1) = finger(3f, 4f, id = 7)
        val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(0L, 0L, action, 2, arrayOf(p0, p1), arrayOf(c0, c1), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, event.actionMasked)
        assertEquals(1, event.actionIndex)
        assertEquals(7, event.getPointerId(1))
        assertEquals(1, event.findPointerIndex(7))
        assertEquals(3f, event.getX(1))
        assertEquals("ACTION_POINTER_DOWN(1)", MotionEvent.actionToString(action))
        assertEquals("ACTION_MOVE", MotionEvent.actionToString(MotionEvent.ACTION_MOVE))
        event.action = MotionEvent.ACTION_MOVE
        assertEquals(MotionEvent.ACTION_MOVE, event.actionMasked)
        assertThrows<IllegalArgumentException> { event.getX(2) }
    }

    @Test
    fun `offsets move the event but not its raw coordinates`() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 20f, 0)
        event.offsetLocation(5f, -5f)
        assertEquals(15f, event.x)
        assertEquals(15f, event.y)
        assertEquals(10f, event.rawX)
        assertEquals(20f, event.rawY)
        event.setLocation(100f, 200f)
        assertEquals(100f, event.x)
        assertEquals(200f, event.y)
        assertEquals(100f, event.getAxisValue(MotionEvent.AXIS_X))
        event.transform(Matrix().apply { setScale(2f, 3f) })
        assertEquals(200f, event.x)
        assertEquals(600f, event.y)
        assertEquals(10f, event.rawX)
    }

    @Test
    fun `copies are independent and batches become history`() {
        val event = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 1f, 1f, 0)
        event.addBatch(20L, 2f, 3f, 0.5f, 0.1f, 0)
        assertEquals(1, event.historySize)
        assertEquals(1f, event.getHistoricalX(0))
        assertEquals(10L, event.getHistoricalEventTime(0))
        assertEquals(2f, event.x)
        assertEquals(3f, event.y)
        assertEquals(0.5f, event.pressure)
        assertEquals(20L, event.eventTime)

        val copy = MotionEvent.obtain(event)
        assertNotSame(event, copy)
        assertEquals(1, copy.historySize)
        copy.offsetLocation(10f, 0f)
        assertEquals(2f, event.x)
        assertEquals(12f, copy.x)
        val noHistory = MotionEvent.obtainNoHistory(event)
        assertEquals(0, noHistory.historySize)
        assertEquals(2f, noHistory.x)
        event.recycle()
        copy.recycle()
    }

    @Test
    fun `pointer coords and properties`() {
        val coords = MotionEvent.PointerCoords()
        coords.setAxisValue(MotionEvent.AXIS_X, 4f)
        coords.setAxisValue(MotionEvent.AXIS_GENERIC_1, 9f)
        assertEquals(4f, coords.x)
        assertEquals(9f, coords.getAxisValue(MotionEvent.AXIS_GENERIC_1))
        val copy = MotionEvent.PointerCoords(coords)
        assertEquals(9f, copy.getAxisValue(MotionEvent.AXIS_GENERIC_1))
        coords.clear()
        assertEquals(0f, coords.x)
        assertEquals(0f, coords.getAxisValue(MotionEvent.AXIS_GENERIC_1))
        assertEquals(4f, copy.x)
        assertThrows<IllegalArgumentException> { coords.getAxisValue(64) }

        val properties = MotionEvent.PointerProperties().apply { id = 3 }
        assertEquals(MotionEvent.TOOL_TYPE_UNKNOWN, properties.toolType)
        assertEquals(3, MotionEvent.PointerProperties(properties).id)
        properties.clear()
        assertEquals(MotionEvent.INVALID_POINTER_ID, properties.id)

        assertEquals("AXIS_PRESSURE", MotionEvent.axisToString(MotionEvent.AXIS_PRESSURE))
        assertEquals(MotionEvent.AXIS_PRESSURE, MotionEvent.axisFromString("AXIS_PRESSURE"))
        assertEquals(-1, MotionEvent.axisFromString("AXIS_NOPE"))
    }

    @Test
    fun `buttons`() {
        val (properties, coords) = finger(0f, 0f)
        val event =
            MotionEvent.obtain(
                0L, 0L, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coords),
                0, MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
            )
        assertTrue(event.isButtonPressed(MotionEvent.BUTTON_PRIMARY))
        assertFalse(event.isButtonPressed(MotionEvent.BUTTON_SECONDARY))
    }

    /** What an extension reads to size a fingertip: an iPhone-like 3x display. */
    @Test
    fun `the system display is an iPhone's`() {
        val metrics = Resources.getSystem().displayMetrics
        assertEquals(3f, metrics.density)
        assertEquals(DisplayMetrics.DENSITY_XXHIGH, metrics.densityDpi)
        assertTrue(metrics.xdpi in 100f..1000f)
        assertEquals(390 * 3, metrics.widthPixels)
        assertEquals(844 * 3, metrics.heightPixels)
        val copy = DisplayMetrics().apply { setTo(metrics) }
        assertTrue(copy.equals(metrics))
        assertEquals(metrics.hashCode(), copy.hashCode())
        assertEquals(0, DisplayMetrics().widthPixels)
        assertThrows<RuntimeException> { Resources.getSystem().getString(1) }
    }
}
