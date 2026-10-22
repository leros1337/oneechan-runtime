package oneechan.host.platform

import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicLong

/**
 * android.graphics on CoreGraphics, ImageIO and CoreText through the app's JNI shim
 * ([NativeGraphics]). Pixels stay in native bitmap-context memory; a [NativePixelBuffer] holds the
 * handle, freed by Bitmap.recycle or, when the bitmap becomes unreachable, a Cleaner.
 */
object NativeGraphicsBackend : GraphicsBackend {
    override val name: String = "native"

    /** Whether the natives are registered (probed once). */
    val isAvailable: Boolean by lazy {
        try {
            NativeGraphics.version() >= 1
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: Throwable) {
            false
        }
    }

    private val cleaner: Cleaner = Cleaner.create()

    /** Native pixel bytes held by live buffers; the collector does not see them. */
    private val outstanding = AtomicLong()
    private val nextCollection = AtomicLong(GC_STEP)

    override fun probe(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ImageInfo? {
        val info = IntArray(4)
        val mime = NativeGraphics.probe(data, offset, length, info) ?: return null
        if (info[0] <= 0 || info[1] <= 0) return null
        return ImageInfo(info[0], info[1], mime, info[2] != 0)
    }

    /** The EXIF/container orientation (1..8) of an image, 1 when unknown. */
    fun orientation(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val info = IntArray(4)
        NativeGraphics.probe(data, offset, length, info) ?: return 1
        return info[3].takeIf { it in 1..8 } ?: 1
    }

    override fun decode(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        sampleSize: Int,
        format: PixelFormat,
        applyOrientation: Boolean,
    ): PixelBuffer? {
        val bytes = reserve(width, height)
        val handle = NativeGraphics.decode(data, offset, length, width, height, sampleSize, format.ordinal, applyOrientation)
        if (handle == 0L) {
            outstanding.addAndGet(-bytes)
            return null
        }
        return NativePixelBuffer(handle, width, height, format, bytes)
    }

    override fun create(
        width: Int,
        height: Int,
        format: PixelFormat,
    ): PixelBuffer {
        val bytes = reserve(width, height)
        val handle = NativeGraphics.create(width, height, format.ordinal)
        if (handle == 0L) {
            outstanding.addAndGet(-bytes)
            throw OutOfMemoryError("Cannot allocate a ${width}x$height bitmap")
        }
        return NativePixelBuffer(handle, width, height, format, bytes)
    }

    override fun fontMetrics(
        font: FontSpec,
        out: FloatArray,
    ) = NativeGraphics.fontMetrics(font.family, font.weight, font.italic, font.file, font.floats, out)

    override fun measureText(
        font: FontSpec,
        text: String,
    ): Float = if (text.isEmpty()) 0f else NativeGraphics.measureText(text, font.family, font.weight, font.italic, font.file, font.floats)

    override fun textBounds(
        font: FontSpec,
        text: String,
        out: FloatArray,
    ) {
        if (text.isEmpty()) out.fill(0f) else NativeGraphics.textBounds(text, font.family, font.weight, font.italic, font.file, font.floats, out)
    }

    /**
     * Accounts for a new buffer. Native pixels do not pressure the Java heap, so unreachable
     * bitmaps would pile up until some unrelated collection: past every [GC_STEP] of outstanding
     * pixels, a collection is requested so their Cleaners run.
     */
    private fun reserve(
        width: Int,
        height: Int,
    ): Long {
        val bytes = width.toLong() * height * 4
        val total = outstanding.addAndGet(bytes)
        val threshold = nextCollection.get()
        if (total > threshold && nextCollection.compareAndSet(threshold, total + GC_STEP)) System.gc()
        return bytes
    }

    internal fun released(bytes: Long) {
        val total = outstanding.addAndGet(-bytes)
        while (true) {
            val threshold = nextCollection.get()
            if (threshold <= total + GC_STEP || nextCollection.compareAndSet(threshold, total + GC_STEP)) break
        }
    }

    internal fun register(
        buffer: NativePixelBuffer,
        handle: NativeHandle,
    ): Cleaner.Cleanable = cleaner.register(buffer, handle)

    private const val GC_STEP = 192L shl 20
}

/** The cleaning action: owns the handle, so it must not reference the buffer. */
internal class NativeHandle(
    @Volatile var handle: Long,
    private val bytes: Long,
) : Runnable {
    override fun run() {
        val h = handle
        handle = 0
        if (h != 0L) {
            NativeGraphics.free(h)
            NativeGraphicsBackend.released(bytes)
        }
    }
}

internal class NativePixelBuffer(
    handle: Long,
    override val width: Int,
    override val height: Int,
    override val format: PixelFormat,
    bytes: Long,
) : PixelBuffer {
    private val owner = NativeHandle(handle, bytes)
    private val cleanable = NativeGraphicsBackend.register(this, owner)

    override val backend: GraphicsBackend get() = NativeGraphicsBackend

    val handle: Long
        get() = owner.handle.also { check(it != 0L) { "The bitmap has been recycled" } }

    override fun getPixels(
        dst: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = NativeGraphics.getPixels(handle, dst, offset, stride, x, y, width, height)

    override fun setPixels(
        src: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = NativeGraphics.setPixels(handle, src, offset, stride, x, y, width, height)

    override fun readRaw(
        dst: ByteArray,
        offset: Int,
    ) = NativeGraphics.readRaw(handle, dst, offset)

    override fun writeRaw(
        src: ByteArray,
        offset: Int,
    ) = NativeGraphics.writeRaw(handle, src, offset)

    override fun encode(
        format: EncodeFormat,
        quality: Int,
    ): ByteArray? = NativeGraphics.encode(handle, format.ordinal, quality)

    override fun drawBitmap(
        src: PixelBuffer,
        srcRect: IntArray,
        dstRect: FloatArray,
        state: CanvasState,
        paint: PaintSpec,
    ) {
        val source = src as? NativePixelBuffer ?: adopt(src)
        NativeGraphics.drawBitmap(
            handle, source.handle, srcRect, dstRect, state.matrix, state.clips, state.clipCount, paint.ints, paint.floats, paint.colorMatrix,
        )
        if (source !== src) source.release()
    }

    override fun drawPath(
        verbs: ByteArray,
        verbCount: Int,
        coords: FloatArray,
        evenOdd: Boolean,
        state: CanvasState,
        paint: PaintSpec,
    ) = NativeGraphics.drawPath(handle, verbs, verbCount, coords, evenOdd, state.matrix, state.clips, state.clipCount, solidInts(paint), paint.floats)

    override fun drawColor(
        color: Int,
        blendMode: Int,
        state: CanvasState,
    ) = NativeGraphics.drawColor(handle, color, blendMode, state.clips, state.clipCount)

    override fun drawText(
        text: String,
        x: Float,
        y: Float,
        font: FontSpec,
        state: CanvasState,
        paint: PaintSpec,
    ) {
        if (text.isEmpty()) return
        NativeGraphics.drawText(
            handle, text, x, y, font.family, font.weight, font.italic, font.file, font.floats,
            state.matrix, state.clips, state.clipCount, solidInts(paint), paint.floats,
        )
    }

    /** Shapes and text are one color: the color filter is applied to it here. */
    private fun solidInts(paint: PaintSpec): IntArray {
        val matrix = paint.colorMatrix ?: return paint.ints
        return paint.ints.copyOf().also { it[0] = PixelMath.applyColorMatrix(matrix, it[0]) }
    }

    override fun release() = cleanable.clean()

    /** A buffer of another backend (tests that switch backends), copied through its raw pixels. */
    private fun adopt(other: PixelBuffer): NativePixelBuffer {
        val copy = NativeGraphicsBackend.create(other.width, other.height, other.format) as NativePixelBuffer
        val raw = ByteArray(other.width * other.height * other.format.bytesPerPixel)
        other.readRaw(raw, 0)
        copy.writeRaw(raw, 0)
        return copy
    }
}
