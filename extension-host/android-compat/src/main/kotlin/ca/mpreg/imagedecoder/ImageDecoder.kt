/*
 * The public API of ca.mpreg:imagedecoder (https://github.com/mpreg-ca/imagedecoder), 17.
 *
 * MIT License
 *
 * Copyright (c) 2026 mpreg-ca
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
 * OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Modified for Oneechan: the Kotlin API reimplemented on android.graphics, i.e. the app's graphics
 * backend (ImageIO on iOS), instead of the native libimagedecoder. Stills only (the first frame of
 * an animation), never HDR (isHdr is false, no gain map), no Exif tags. Frame.image holds the
 * bitmap's premultiplied RGBA bytes (identical to upstream for opaque images).
 */

package ca.mpreg.imagedecoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class ImageDecoder private constructor(
    private var buffer: ByteArrayOutputStream,
    private var complete: Boolean,
    private val formatRaw: String,
    val width: Int,
    val height: Int,
) : Closeable {
    /** HDR pixels are never produced. */
    val isHdr: Boolean = false

    val hdrHeadroom: Float = 0f

    val format: Format = Format.fromNative(formatRaw)

    val pages: Int
        @Throws(DecodeException::class)
        @Synchronized get() {
            checkOpen()
            return 1
        }

    private val closed = AtomicBoolean(false)

    enum class Format {
        PNG,
        JPEG,
        TIFF,
        WEBP,
        GIF,
        AVIF,
        HEIF,
        JXL,
        JP2,
        UNKNOWN,
        ;

        companion object {
            internal fun fromNative(name: String): Format = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }

    enum class HdrKind {
        NONE,
        GAINMAP,
        PQ,
        HLG,
        LINEAR,
    }

    val hdrKind: HdrKind get() = HdrKind.NONE

    /** Per-pixel multiplier from the SDR base to HDR (never produced: [getGainmap] returns null). */
    class Gainmap private constructor(
        private val pixelBuffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val channels: Int,
        val gamma: FloatArray,
        val minContentBoost: FloatArray,
        val maxContentBoost: FloatArray,
        val offsetSdr: FloatArray,
        val offsetHdr: FloatArray,
        val baseHdrHeadroom: Float,
        val alternateHdrHeadroom: Float,
    ) {
        val pixels: ByteBuffer get() = pixelBuffer.duplicate().order(ByteOrder.nativeOrder())

        fun toByteArray(): ByteArray {
            val src = pixels
            return ByteArray(src.remaining()).also { src.get(it) }
        }

        val headroomStops: Float get() = 0f

        fun gainFor(
            value: Int,
            channel: Int,
        ): Float = 1f

        fun applyTo(
            linear: Float,
            value: Int,
            channel: Int,
        ): Float = linear
    }

    open class DecodeException internal constructor(
        message: String,
    ) : Exception(message)

    class UnknownFormatException internal constructor(
        message: String,
    ) : DecodeException(message)

    class OutOfMemoryException internal constructor(
        message: String,
    ) : DecodeException(message)

    class NeedMoreDataException internal constructor(
        message: String,
    ) : DecodeException(message)

    /** One decoded frame: packed RGBA bytes in native order. */
    class Frame internal constructor(
        buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val changed: Boolean,
        val partial: Boolean,
        val dirtyX: Int,
        val dirtyY: Int,
        val dirtyWidth: Int,
        val dirtyHeight: Int,
        val index: Int,
        val duration: Int,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        @Volatile
        private var pixels: ByteBuffer? = buffer

        val image: ByteBuffer
            get() {
                val p = pixels
                check(!closed.get() && p != null) { "Frame has been closed" }
                return p.duplicate().order(ByteOrder.nativeOrder())
            }

        override fun close() {
            if (closed.compareAndSet(false, true)) pixels = null
        }

        fun toByteArray(): ByteArray {
            val src = image
            return ByteArray(src.remaining()).also { src.get(it) }
        }

        fun copyTo(dst: ByteBuffer) {
            val src = image
            require(dst.remaining() >= src.remaining()) { "destination holds ${dst.remaining()} bytes, need ${src.remaining()}" }
            dst.put(src)
        }
    }

    @Volatile
    private var more = true

    @Volatile
    var page: Int = 0
        private set

    val hasNext: Boolean get() = more && !closed.get()

    val isAnimated: Boolean
        @Throws(DecodeException::class)
        get() = pages > 1

    @Synchronized
    @Throws(DecodeException::class)
    fun decodeNext(): Frame {
        checkOpen()
        if (!more) throw DecodeException("No more frames")
        val bitmap = decodeBitmap(null, 1)
        more = false
        page = 0
        return frameOf(bitmap)
    }

    @JvmOverloads
    @Synchronized
    @Throws(DecodeException::class)
    fun decode(
        region: Rect? = null,
        sampleSize: Int = 1,
    ): Frame = frameOf(decodeBitmap(region, sampleSize))

    @JvmOverloads
    @Synchronized
    @Throws(DecodeException::class)
    fun decodeToBitmap(
        region: Rect? = null,
        sampleSize: Int = 1,
    ): Bitmap = decodeBitmap(region, sampleSize)

    private fun decodeBitmap(
        region: Rect?,
        sampleSize: Int,
    ): Bitmap {
        checkOpen()
        require(sampleSize >= 1) { "sampleSize $sampleSize is below 1" }
        if (region != null) {
            require(!region.isEmpty && region.left >= 0 && region.top >= 0 && region.right <= width && region.bottom <= height) {
                "region $region is not inside ${width}x$height"
            }
        }
        val data = buffer.toByteArray()
        val options = BitmapFactory.Options().apply { inMutable = true }
        if (region == null) options.inSampleSize = sampleSize
        val decoded =
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: throw if (complete) DecodeException("Cannot decode the image") else NeedMoreDataException("More data is needed")
        val r = region ?: Rect(0, 0, width, height)
        val outWidth = ((r.width().toLong() + sampleSize - 1) / sampleSize).toInt()
        val outHeight = ((r.height().toLong() + sampleSize - 1) / sampleSize).toInt()
        var bitmap = decoded
        if (region != null) {
            bitmap = Bitmap.createBitmap(decoded, r.left, r.top, r.width(), r.height())
            if (bitmap !== decoded) decoded.recycle()
        }
        if (bitmap.width != outWidth || bitmap.height != outHeight) {
            val scaled = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun frameOf(bitmap: Bitmap): Frame {
        val bytes = ByteBuffer.allocate(bitmap.width * bitmap.height * 4).order(ByteOrder.nativeOrder())
        val rgba = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        rgba.copyPixelsToBuffer(bytes)
        if (rgba !== bitmap) rgba.recycle()
        val frame = Frame(bytes.rewind() as ByteBuffer, bitmap.width, bitmap.height, true, false, 0, 0, bitmap.width, bitmap.height, 0, 0)
        bitmap.recycle()
        return frame
    }

    @Synchronized
    @Throws(DecodeException::class)
    fun rewind() {
        checkOpen()
        more = true
        page = 0
    }

    @Synchronized
    @Throws(DecodeException::class)
    fun getGainmap(): Gainmap? {
        checkOpen()
        return null
    }

    @JvmOverloads
    @Synchronized
    @Throws(DecodeException::class)
    fun pushData(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        checkOpen()
        if (complete) throw DecodeException("markComplete has been called")
        require(offset >= 0 && length >= 0 && offset <= data.size && length <= data.size - offset) {
            "offset $offset length $length out of bounds for ${data.size}"
        }
        buffer.write(data, offset, length)
    }

    @Synchronized
    @Throws(DecodeException::class)
    fun markComplete() {
        checkOpen()
        complete = true
    }

    @Synchronized
    @Throws(DecodeException::class)
    fun listTags(): List<String> {
        checkOpen()
        return emptyList()
    }

    @Synchronized
    @Throws(DecodeException::class)
    fun getTag(name: String): String? {
        checkOpen()
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) { buffer = ByteArrayOutputStream(0) }
    }

    private fun checkOpen() {
        if (closed.get()) throw DecodeException("ImageDecoder has been closed")
    }

    companion object {
        /**
         * Reads [inputStream] to the end, or with [complete] false only what it holds now; feed the
         * rest with [pushData], then [markComplete].
         */
        @JvmStatic
        @JvmOverloads
        @Throws(DecodeException::class, IOException::class)
        fun open(
            inputStream: InputStream,
            complete: Boolean = true,
        ): ImageDecoder {
            val buffer = ByteArrayOutputStream()
            if (complete) {
                inputStream.copyTo(buffer)
            } else {
                val available = ByteArray(maxOf(inputStream.available(), 0))
                val read = inputStream.read(available)
                if (read > 0) buffer.write(available, 0, read)
            }
            val bytes = buffer.toByteArray()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                if (!complete && bytes.size < 64) throw DecodeException("The header is incomplete")
                throw UnknownFormatException("Not an image this build reads")
            }
            val format =
                when (options.outMimeType) {
                    "image/png" -> "PNG"
                    "image/jpeg" -> "JPEG"
                    "image/webp" -> "WEBP"
                    "image/gif" -> "GIF"
                    "image/avif" -> "AVIF"
                    "image/heif", "image/heic" -> "HEIF"
                    "image/jxl" -> "JXL"
                    "image/tiff" -> "TIFF"
                    "image/jp2" -> "JP2"
                    else -> "UNKNOWN"
                }
            return ImageDecoder(buffer, complete, format, options.outWidth, options.outHeight)
        }
    }
}
