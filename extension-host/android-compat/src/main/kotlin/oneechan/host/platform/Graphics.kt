package oneechan.host.platform

/**
 * The pixel engine behind android.graphics (Bitmap, BitmapFactory, Canvas, Paint text): the
 * android.graphics classes keep Android's API semantics (argument checks, Matrix and clip state,
 * Options, premultiplication rules) and hand whole operations to a backend.
 *
 * - [NativeGraphicsBackend]: CoreGraphics, ImageIO and CoreText through the app's JNI shim (iOS, and
 *   the macOS JNI tests). Pixels live in native memory.
 * - [AwtGraphicsBackend]: java.awt / javax.imageio (desktop host, plain JVM tests). AWT has no
 *   native libraries on iOS, so it is never loaded there.
 *
 * Backends are called with whole images or whole draw operations, never per pixel: the embedded VM
 * is an interpreter. A [PixelBuffer] is not thread-safe; android.graphics.Bitmap serializes access.
 */
interface GraphicsBackend {
    val name: String

    /** Header only: size and type, or null when the bytes are not an image this backend decodes. */
    fun probe(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ImageInfo?

    /**
     * Decodes the first frame into [format], scaled to exactly [width] x [height] (the caller has
     * applied the sample size); [sampleSize] is a power-of-two hint for decoders that can shrink
     * while decoding. [applyOrientation]: rotate per EXIF/container orientation (ImageDecoder does,
     * BitmapFactory does not). Null when the data cannot be decoded.
     */
    fun decode(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        sampleSize: Int,
        format: PixelFormat,
        applyOrientation: Boolean,
    ): PixelBuffer?

    /** A transparent (zeroed) buffer. Throws [OutOfMemoryError] when it cannot be allocated. */
    fun create(
        width: Int,
        height: Int,
        format: PixelFormat,
    ): PixelBuffer

    /** Android's Paint.FontMetrics of [font] into [out]: top, ascent, descent, bottom, leading. */
    fun fontMetrics(
        font: FontSpec,
        out: FloatArray,
    )

    /** The advance width of [text] (Paint.measureText). */
    fun measureText(
        font: FontSpec,
        text: String,
    ): Float

    /** Ink bounds of [text] drawn at the origin into [out]: left, top, right, bottom (y down). */
    fun textBounds(
        font: FontSpec,
        text: String,
        out: FloatArray,
    )
}

/**
 * The pixels of one bitmap. Colors are Android's: ARGB ints, unpremultiplied at the API
 * ([getPixels]/[setPixels]); raw buffers are Android's memory layout of [format], premultiplied.
 */
interface PixelBuffer {
    val width: Int
    val height: Int
    val format: PixelFormat
    val backend: GraphicsBackend

    /** Unpremultiplied ARGB colors of a region (Bitmap.getPixels). Arguments are already checked. */
    fun getPixels(
        dst: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /** Writes unpremultiplied ARGB colors to a region (Bitmap.setPixels). Arguments are already checked. */
    fun setPixels(
        src: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /** The whole bitmap in Android's memory layout of [format] (width * bytesPerPixel per row). */
    fun readRaw(
        dst: ByteArray,
        offset: Int,
    )

    /** Replaces the whole bitmap from Android's memory layout of [format]. */
    fun writeRaw(
        src: ByteArray,
        offset: Int,
    )

    /** Encodes the bitmap, or null when the format cannot be written. */
    fun encode(
        format: EncodeFormat,
        quality: Int,
    ): ByteArray?

    /**
     * Draws the [srcRect] pixels of [src] (left, top, right, bottom; inside src) into [dstRect]
     * (canvas coordinates) under [state], with [paint]'s alpha, filtering, color filter and blend mode.
     */
    fun drawBitmap(
        src: PixelBuffer,
        srcRect: IntArray,
        dstRect: FloatArray,
        state: CanvasState,
        paint: PaintSpec,
    )

    /** Fills and/or strokes a path (verbs from [PathVerbs], coordinates in canvas space). */
    fun drawPath(
        verbs: ByteArray,
        verbCount: Int,
        coords: FloatArray,
        evenOdd: Boolean,
        state: CanvasState,
        paint: PaintSpec,
    )

    /** Fills the whole clip with [color] (unpremultiplied ARGB) using [blendMode] ([BlendModes]). */
    fun drawColor(
        color: Int,
        blendMode: Int,
        state: CanvasState,
    )

    /** Draws [text] left-aligned with its baseline at ([x], [y]). */
    fun drawText(
        text: String,
        x: Float,
        y: Float,
        font: FontSpec,
        state: CanvasState,
        paint: PaintSpec,
    )

    /** Frees the pixels; the buffer must not be used afterwards. */
    fun release()
}

/** Storage formats (Bitmap.Config) and the size of a pixel in Android's raw memory layout. */
enum class PixelFormat(
    val bytesPerPixel: Int,
) {
    /** R, G, B, A bytes, premultiplied. */
    ARGB_8888(4),

    /** Opaque; a little-endian 16-bit 5-6-5 word per pixel. */
    RGB_565(2),

    /** Alpha only. */
    ALPHA_8(1),

    /** Four little-endian half floats, premultiplied (kept at 8 bits per channel internally). */
    RGBA_F16(8),
}

/** Formats Bitmap.compress can produce. */
enum class EncodeFormat {
    JPEG,
    PNG,
    WEBP_LOSSY,
    WEBP_LOSSLESS,
}

class ImageInfo(
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val mimeType: String?,
    @JvmField val hasAlpha: Boolean,
)

/** The parts of an android.graphics.Paint a draw call needs (immutable snapshot). */
class PaintSpec(
    color: Int,
    flags: Int,
    style: Int,
    strokeWidth: Float,
    cap: Int,
    join: Int,
    miter: Float,
    blendMode: Int,
    /** ColorMatrixColorFilter's 4x5 matrix (Android's 0..255 translation), or null. */
    @JvmField val colorMatrix: FloatArray?,
) {
    /** color, flags, style, cap, join, blendMode: the layout the native backend passes on. */
    @JvmField val ints: IntArray = intArrayOf(color, flags, style, cap, join, blendMode)

    /** strokeWidth, miter. */
    @JvmField val floats: FloatArray = floatArrayOf(strokeWidth, miter)

    val color: Int get() = ints[0]
    val flags: Int get() = ints[1]
    val style: Int get() = ints[2]
    val cap: Int get() = ints[3]
    val join: Int get() = ints[4]
    val blendMode: Int get() = ints[5]
    val strokeWidth: Float get() = floats[0]
    val miter: Float get() = floats[1]
    val antiAlias: Boolean get() = flags and FLAG_ANTI_ALIAS != 0
    val filterBitmap: Boolean get() = flags and FLAG_FILTER_BITMAP != 0

    companion object {
        const val FLAG_ANTI_ALIAS = 1
        const val FLAG_FILTER_BITMAP = 2
        const val STYLE_FILL = 0
        const val STYLE_STROKE = 1
        const val STYLE_FILL_AND_STROKE = 2

        /** What Android uses for a null Paint: opaque, no filtering, no antialiasing. */
        @JvmField val DEFAULT = PaintSpec(-0x1000000, 0, STYLE_FILL, 0f, 0, 0, 4f, BlendModes.SRC_OVER, null)
    }
}

/** A resolved font request: Typeface (family, weight, italic or a font file) and Paint's text size and transform. */
class FontSpec(
    /** "sans-serif", "serif", "monospace" or a family name. */
    @JvmField val family: String,
    @JvmField val weight: Int,
    @JvmField val italic: Boolean,
    /** A font file (Typeface.createFromFile), or null for a system family. */
    @JvmField val file: String?,
    @JvmField val size: Float,
    @JvmField val scaleX: Float,
    @JvmField val skewX: Float,
) {
    val bold: Boolean get() = weight >= 600

    /** size, scaleX, skewX. */
    @JvmField val floats: FloatArray = floatArrayOf(size, scaleX, skewX)
}

/**
 * A Canvas's transform and clip at the time of a draw call. [matrix] is affine (a, b, c, d, tx, ty:
 * x' = a*x + c*y + tx, y' = b*x + d*y + ty). Each clip is [CLIP_STRIDE] floats: its own transform
 * (6), its rect (left, top, right, bottom) and the op ([CLIP_INTERSECT] or [CLIP_DIFFERENCE]).
 */
class CanvasState(
    @JvmField val matrix: FloatArray,
    @JvmField val clips: FloatArray,
    @JvmField val clipCount: Int,
) {
    companion object {
        const val CLIP_STRIDE = 11
        const val CLIP_INTERSECT = 0f
        const val CLIP_DIFFERENCE = 1f

        @JvmField val IDENTITY = CanvasState(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), FloatArray(0), 0)
    }
}

/** Path verbs: MOVE (x, y), LINE (x, y), QUAD (x1, y1, x2, y2), CUBIC (6 floats), CLOSE. */
object PathVerbs {
    const val MOVE: Byte = 0
    const val LINE: Byte = 1
    const val QUAD: Byte = 2
    const val CUBIC: Byte = 3
    const val CLOSE: Byte = 4

    @JvmStatic
    fun pointCount(verb: Byte): Int =
        when (verb) {
            MOVE, LINE -> 1
            QUAD -> 2
            CUBIC -> 3
            else -> 0
        }
}

/** Blend modes, numbered like Skia's SkBlendMode (and PorterDuff.Mode's native values). */
object BlendModes {
    const val CLEAR = 0
    const val SRC = 1
    const val DST = 2
    const val SRC_OVER = 3
    const val DST_OVER = 4
    const val SRC_IN = 5
    const val DST_IN = 6
    const val SRC_OUT = 7
    const val DST_OUT = 8
    const val SRC_ATOP = 9
    const val DST_ATOP = 10
    const val XOR = 11
    const val PLUS = 12
    const val MODULATE = 13
    const val SCREEN = 14
    const val OVERLAY = 15
    const val DARKEN = 16
    const val LIGHTEN = 17
    const val MULTIPLY = 24

    /** PorterDuff.Mode by name (the enum comes from the Android stubs). */
    @JvmStatic
    fun ofPorterDuff(name: String): Int =
        when (name) {
            "CLEAR" -> CLEAR
            "SRC" -> SRC
            "DST" -> DST
            "SRC_OVER" -> SRC_OVER
            "DST_OVER" -> DST_OVER
            "SRC_IN" -> SRC_IN
            "DST_IN" -> DST_IN
            "SRC_OUT" -> SRC_OUT
            "DST_OUT" -> DST_OUT
            "SRC_ATOP" -> SRC_ATOP
            "DST_ATOP" -> DST_ATOP
            "XOR" -> XOR
            "ADD" -> PLUS
            "MULTIPLY" -> MODULATE
            "SCREEN" -> SCREEN
            "OVERLAY" -> OVERLAY
            "DARKEN" -> DARKEN
            "LIGHTEN" -> LIGHTEN
            else -> SRC_OVER
        }
}

/**
 * Picks the backend: the native one when the app registered its natives (probed once), else AWT.
 * `-Doneechan.graphics=awt` forces AWT (a reference run inside the app's JVM on macOS).
 */
object Graphics {
    @Volatile private var override: GraphicsBackend? = null

    private val selected: GraphicsBackend by lazy {
        if (System.getProperty("oneechan.graphics") != "awt" && NativeGraphicsBackend.isAvailable) NativeGraphicsBackend else AwtGraphicsBackend
    }

    @JvmStatic
    fun backend(): GraphicsBackend = override ?: selected

    /** Runs [block] with [backend] (tests and the self-test). Not for concurrent use. */
    fun <T> withBackend(
        backend: GraphicsBackend,
        block: () -> T,
    ): T {
        val previous = override
        override = backend
        try {
            return block()
        } finally {
            override = previous
        }
    }
}
