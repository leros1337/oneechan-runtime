package oneechan.host.platform

/**
 * Pixel conversions shared by the backends (the native one does the same in C): premultiplication
 * rounds to nearest, RGB_565 expands by bit replication, like Skia.
 */
object PixelMath {
    /** round(a * b / 255) for 0..255 inputs. */
    @JvmStatic
    fun mul255(
        a: Int,
        b: Int,
    ): Int {
        val p = a * b + 128
        return (p + (p shr 8)) shr 8
    }

    /** Unpremultiplied ARGB to premultiplied ARGB. */
    @JvmStatic
    fun premultiply(color: Int): Int {
        val a = color ushr 24
        if (a == 255) return color
        if (a == 0) return 0
        val r = mul255((color shr 16) and 0xff, a)
        val g = mul255((color shr 8) and 0xff, a)
        val b = mul255(color and 0xff, a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Premultiplied ARGB to unpremultiplied ARGB. */
    @JvmStatic
    fun unpremultiply(color: Int): Int {
        val a = color ushr 24
        if (a == 255) return color
        if (a == 0) return 0
        val half = a / 2
        val r = minOf(255, (((color shr 16) and 0xff) * 255 + half) / a)
        val g = minOf(255, (((color shr 8) and 0xff) * 255 + half) / a)
        val b = minOf(255, ((color and 0xff) * 255 + half) / a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** An opaque color as RGB_565 stores it, expanded back to 8 bits per channel. */
    @JvmStatic
    fun quantize565(color: Int): Int = expand565(pack565(color))

    @JvmStatic
    fun pack565(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
    }

    @JvmStatic
    fun expand565(packed: Int): Int {
        val r5 = (packed shr 11) and 0x1f
        val g6 = (packed shr 5) and 0x3f
        val b5 = packed and 0x1f
        val r = (r5 shl 3) or (r5 shr 2)
        val g = (g6 shl 2) or (g6 shr 4)
        val b = (b5 shl 3) or (b5 shr 2)
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    /**
     * Applies a ColorMatrix (4x5, Android's 0..255 translation) to an unpremultiplied ARGB color.
     */
    @JvmStatic
    fun applyColorMatrix(
        m: FloatArray,
        color: Int,
    ): Int {
        val a = (color ushr 24).toFloat()
        val r = ((color shr 16) and 0xff).toFloat()
        val g = ((color shr 8) and 0xff).toFloat()
        val b = (color and 0xff).toFloat()
        val nr = clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4])
        val ng = clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9])
        val nb = clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14])
        val na = clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19])
        return (na shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    /** IEEE half float bits to float. */
    @JvmStatic
    fun halfToFloat(bits: Int): Float {
        val sign = (bits shr 15) and 1
        val exp = (bits shr 10) and 0x1f
        val mant = bits and 0x3ff
        val value =
            when (exp) {
                0 -> mant / 1024f / 16384f
                31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + mant / 1024f) * Math.scalb(1f, exp - 15)
            }
        return if (sign == 1) -value else value
    }

    /** Float to IEEE half float bits (round to nearest even), as a 0..65535 int. */
    @JvmStatic
    fun floatToHalf(value: Float): Int = java.lang.Float.floatToFloat16(value).toInt() and 0xffff

    /** A premultiplied 0..255 channel from a half float, clamped to 0..1. */
    @JvmStatic
    fun halfToByte(bits: Int): Int {
        val v = halfToFloat(bits)
        return if (v.isNaN() || v <= 0f) 0 else if (v >= 1f) 255 else (v * 255f + 0.5f).toInt()
    }
}
