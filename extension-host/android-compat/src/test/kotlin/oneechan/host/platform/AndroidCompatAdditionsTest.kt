package oneechan.host.platform

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AndroidCompatAdditionsTest {
    private class Bold

    @Test
    fun `spannable strings keep text and spans`() {
        val bold = Bold()
        val text = SpannableString("Hello world")
        text.setSpan(bold, 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals("Hello world", text.toString())
        assertEquals(6, text.getSpanStart(bold))
        assertEquals(listOf(bold), text.getSpans(0, text.length, Bold::class.java).toList())
        assertEquals(0, text.getSpans(0, 5, Bold::class.java).size)
        assertEquals(6, text.nextSpanTransition(0, text.length, Bold::class.java))
        assertEquals("world", text.subSequence(6, 11).toString())
        assertEquals(0, (text.subSequence(6, 11) as Spanned).getSpanStart(bold))
        assertEquals(6, SpannedString(text).getSpanStart(bold))
    }

    @Test
    fun `builder edits shift spans`() {
        val bold = Bold()
        val builder = SpannableStringBuilder("ab")
        builder.append("cd", bold, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.insert(0, "xx")
        assertEquals("xxabcd", builder.toString())
        assertEquals(4, builder.getSpanStart(bold))
        assertEquals(6, builder.getSpanEnd(bold))
        builder.delete(0, 2)
        assertEquals(2, builder.getSpanStart(bold))
    }

    @Test
    fun `color matrix math matches AOSP`() {
        val grey = ColorMatrix().apply { setSaturation(0f) }
        assertEquals(0xff_36_36_36.toInt(), grey.apply(0xff_ff_00_00.toInt())) // 0.213 * 255 luminance of red
        val scale = ColorMatrix().apply { setScale(0.5f, 1f, 1f, 1f) }
        val concat = ColorMatrix().apply { setConcat(scale, ColorMatrix()) }
        assertContentEquals(scale.array, concat.array)
        val out = ColorMatrix()
        ColorMatrixColorFilter(scale).getColorMatrix(out)
        assertEquals(scale, out)
    }
}
