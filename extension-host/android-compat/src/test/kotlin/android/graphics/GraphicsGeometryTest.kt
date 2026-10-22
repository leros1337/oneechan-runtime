package android.graphics

import oneechan.host.platform.PathVerbs
import oneechan.host.platform.PixelFormat
import oneechan.host.platform.PixelMath
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-Java parts of the android.graphics overlay: geometry, Matrix, Path, Options and config rules. */
class GraphicsGeometryTest {
    @Test
    fun `rect follows AOSP`() {
        val r = Rect(10, 20, 30, 60)
        assertEquals(20, r.width())
        assertEquals(40, r.height())
        assertEquals(20, r.centerX())
        assertEquals(40, r.centerY())
        assertEquals(20.0f, r.exactCenterX())
        assertEquals(40.0f, Rect(10, 21, 30, 60).exactCenterY() - 0.5f)
        assertEquals(20, r.getWidth())
        assertTrue(r.contains(10, 20))
        assertFalse(r.contains(30, 20))
        assertTrue(Rect(r).intersect(25, 50, 100, 100))
        val i = Rect(r).apply { intersect(25, 50, 100, 100) }
        assertEquals(Rect(25, 50, 30, 60), i)
        assertFalse(Rect(r).intersect(30, 0, 40, 10))
        assertEquals(Rect(0, 0, 30, 60), Rect(r).apply { union(0, 0, 5, 5) })
        assertEquals(Rect(15, 25, 35, 65), Rect(r).apply { offset(5, 5) })
        assertEquals(Rect(12, 22, 28, 58), Rect(r).apply { inset(2, 2) })
        assertEquals(Rect(0, 0, 20, 40), Rect(r).apply { offsetTo(0, 0) })
        assertEquals(r, Rect.unflattenFromString(r.flattenToString()))
        assertEquals("[10,20][30,60]", r.toShortString())
        assertTrue(Rect(5, 5, 5, 10).isEmpty)
        assertTrue(Rect.intersects(r, Rect(29, 59, 40, 70)))
    }

    @Test
    fun `rectF rounds like AOSP`() {
        val f = RectF(0.4f, 0.6f, 9.5f, 10.4f)
        assertEquals(Rect(0, 1, 10, 10), Rect().also { f.round(it) })
        assertEquals(Rect(0, 0, 10, 11), Rect().also { f.roundOut(it) })
        assertEquals(9.1f, f.width(), 1e-5f)
        assertEquals(RectF(Rect(1, 2, 3, 4)), RectF(1f, 2f, 3f, 4f))
    }

    @Test
    fun `matrix pre and post order`() {
        val m = Matrix()
        m.setTranslate(10f, 20f)
        m.preScale(2f, 3f)
        // pre: scale first, then translate.
        assertContentEquals(floatArrayOf(12f, 23f), floatArrayOf(1f, 1f).also { m.mapPoints(it) })
        val n = Matrix()
        n.setTranslate(10f, 20f)
        n.postScale(2f, 3f)
        // post: translate first, then scale.
        assertContentEquals(floatArrayOf(22f, 63f), floatArrayOf(1f, 1f).also { n.mapPoints(it) })
    }

    @Test
    fun `rotation snaps to exact quarter turns`() {
        val m = Matrix()
        m.setRotate(90f)
        assertContentEquals(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f).also { m.mapPoints(it) })
        val values = FloatArray(9).also { m.getValues(it) }
        assertEquals(0f, values[Matrix.MSCALE_X])
        assertEquals(-1f, values[Matrix.MSKEW_X])
        assertTrue(m.rectStaysRect())
        m.setRotate(45f, 5f, 5f)
        assertFalse(m.rectStaysRect())
        assertContentEquals(floatArrayOf(5f, 5f), floatArrayOf(5f, 5f).also { m.mapPoints(it) })
    }

    @Test
    fun `matrix maps rects and inverts`() {
        val m = Matrix()
        m.postScale(2f, 2f, 10f, 10f)
        m.postTranslate(-5f, 3f)
        val r = RectF(10f, 10f, 20f, 30f)
        assertTrue(m.mapRect(r))
        assertEquals(RectF(5f, 13f, 25f, 53f), r)
        val inverse = Matrix()
        assertTrue(m.invert(inverse))
        val p = floatArrayOf(25f, 53f).also { inverse.mapPoints(it) }
        assertEquals(20f, p[0], 1e-4f)
        assertEquals(30f, p[1], 1e-4f)
        assertFalse(Matrix().apply { setScale(0f, 1f) }.invert(Matrix()))
        val rot = Matrix().apply { setRotate(90f) }
        val bounds = RectF(0f, 0f, 10f, 20f).also { rot.mapRect(it) }
        assertEquals(RectF(-20f, 0f, 0f, 10f), bounds)
        assertEquals(2f, Matrix().apply { setScale(2f, 2f) }.mapRadius(1f), 1e-6f)
    }

    @Test
    fun `setRectToRect scale-to-fit modes`() {
        val src = RectF(0f, 0f, 100f, 50f)
        val dst = RectF(0f, 0f, 200f, 200f)
        fun map(stf: Matrix.ScaleToFit) = RectF(src).also { Matrix().apply { assertTrue(setRectToRect(src, dst, stf)) }.mapRect(it) }
        assertEquals(RectF(0f, 0f, 200f, 200f), map(Matrix.ScaleToFit.FILL))
        assertEquals(RectF(0f, 0f, 200f, 100f), map(Matrix.ScaleToFit.START))
        assertEquals(RectF(0f, 50f, 200f, 150f), map(Matrix.ScaleToFit.CENTER))
        assertEquals(RectF(0f, 100f, 200f, 200f), map(Matrix.ScaleToFit.END))
        assertFalse(Matrix().setRectToRect(RectF(), dst, Matrix.ScaleToFit.FILL))
    }

    @Test
    fun `poly to poly up to three points`() {
        val m = Matrix()
        val src = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val dst = floatArrayOf(10f, 10f, 12f, 10f, 10f, 13f)
        assertTrue(m.setPolyToPoly(src, 0, dst, 0, 3))
        val p = floatArrayOf(1f, 1f).also { m.mapPoints(it) }
        assertEquals(12f, p[0], 1e-4f)
        assertEquals(13f, p[1], 1e-4f)
        assertFalse(m.setPolyToPoly(FloatArray(8), 0, FloatArray(8), 0, 4))
    }

    @Test
    fun `path records verbs and bounds`() {
        val path = Path()
        path.lineTo(5f, 5f)
        assertEquals(listOf(PathVerbs.MOVE, PathVerbs.LINE), path.verbs().take(path.verbCount()))
        path.close()
        path.quadTo(1f, 2f, 3f, 4f)
        // After close, a new contour starts at the last moveTo.
        assertEquals(PathVerbs.MOVE, path.verbs()[3])
        val circle = Path().apply { addCircle(10f, 10f, 5f, Path.Direction.CW) }
        val bounds = RectF().also { circle.computeBounds(it, true) }
        assertEquals(5f, bounds.left, 1e-4f)
        assertEquals(15f, bounds.bottom, 1e-4f)
        val rect = Path().apply { addRect(0f, 0f, 2f, 3f, Path.Direction.CCW) }
        assertEquals(5, rect.verbCount())
        val round = Path().apply { addRoundRect(0f, 0f, 10f, 10f, 2f, 2f, Path.Direction.CCW) }
        assertTrue(round.verbCount() > 8)
        assertTrue(Path().isEmpty)
    }

    @Test
    fun `options sample sizes and configs follow AOSP`() {
        assertEquals(1, BitmapFactory.sampleSize(0))
        assertEquals(2, BitmapFactory.sampleSize(3))
        assertEquals(4, BitmapFactory.sampleSize(7))
        assertEquals(50, BitmapFactory.sampledDimension(101, 2, "image/png"))
        assertEquals(51, BitmapFactory.sampledDimension(101, 2, "image/jpeg"))
        assertEquals(1, BitmapFactory.sampledDimension(3, 8, "image/png"))
        assertEquals(Bitmap.Config.RGB_565, BitmapFactory.chooseConfig(Bitmap.Config.RGB_565, false))
        assertEquals(Bitmap.Config.ARGB_8888, BitmapFactory.chooseConfig(Bitmap.Config.RGB_565, true))
        assertEquals(Bitmap.Config.ARGB_8888, BitmapFactory.chooseConfig(null, true))
        assertEquals(Bitmap.Config.ARGB_8888, BitmapFactory.chooseConfig(Bitmap.Config.HARDWARE, false))
        val options = BitmapFactory.Options()
        assertTrue(options.inScaled && options.inPremultiplied)
        assertEquals(Bitmap.Config.ARGB_8888, options.inPreferredConfig)
        assertNull(BitmapFactory.decodeByteArray(byteArrayOf(1, 2, 3), 0, 3, options))
        assertEquals(-1, options.outWidth)
    }

    @Test
    fun `configs map to storage formats`() {
        assertEquals(PixelFormat.ARGB_8888, Bitmap.formatOf(Bitmap.Config.ARGB_4444))
        assertEquals(Bitmap.Config.ARGB_8888, Bitmap.storedConfig(Bitmap.Config.ARGB_4444))
        assertEquals(PixelFormat.RGB_565, Bitmap.formatOf(Bitmap.Config.RGB_565))
        assertEquals(PixelFormat.ALPHA_8, Bitmap.formatOf(Bitmap.Config.ALPHA_8))
        assertEquals(PixelFormat.RGBA_F16, Bitmap.formatOf(Bitmap.Config.RGBA_F16))
        assertEquals(2, PixelFormat.RGB_565.bytesPerPixel)
    }

    @Test
    fun `pixel math rounds to nearest and round-trips`() {
        assertEquals(0x80800000.toInt(), PixelMath.premultiply(0x80FF0000.toInt()))
        assertEquals(0x80FF0000.toInt(), PixelMath.unpremultiply(0x80800000.toInt()))
        assertEquals(0, PixelMath.premultiply(0x00FFFFFF))
        for (a in 1..255) {
            for (c in 0..255 step 17) {
                val color = (a shl 24) or (c shl 16) or (c shl 8) or c
                val pm = PixelMath.premultiply(color)
                assertEquals(Math.round(c * a / 255.0).toInt(), (pm shr 16) and 0xff)
            }
        }
        assertEquals(0xFFFF0000.toInt(), PixelMath.quantize565(0xFFFF0000.toInt()))
        assertEquals(0xFF08_0C_08.toInt(), PixelMath.quantize565(0xFF0A0D0B.toInt()))
        assertEquals(1.0f, PixelMath.halfToFloat(PixelMath.floatToHalf(1.0f)))
        assertEquals(128, PixelMath.halfToByte(PixelMath.floatToHalf(128 / 255f)))
    }
}
