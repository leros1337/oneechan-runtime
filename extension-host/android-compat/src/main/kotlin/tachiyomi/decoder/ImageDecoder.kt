/*
 * The public API of tachiyomiorg/image-decoder (https://github.com/tachiyomiorg/image-decoder),
 * licensed under the Mozilla Public License, v. 2.0. This Source Code Form is subject to the terms
 * of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: the same Kotlin API (ImageDecoder, ImageType, Format) reimplemented on
 * android.graphics, i.e. the app's graphics backend (ImageIO on iOS), instead of the native
 * libimagedecoder. displayProfile (color management) is ignored; cropBorders trims uniform
 * borders by sampling edge rows and columns.
 */

package tachiyomi.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

class ImageDecoder private constructor(
    private val data: ByteArray,
    /** The part of the encoded image this decoder exposes (after cropBorders). */
    private val bounds: Rect,
    val width: Int,
    val height: Int,
) {
    var isRecycled = false
        private set

    private var shouldRecycle = false
    private val decoding = AtomicInteger()
    private val lock = ReentrantReadWriteLock()

    fun decode(
        region: Rect = Rect(0, 0, width, height),
        sampleSize: Int = 1,
    ): Bitmap? {
        checkValidInput(region, sampleSize)
        return try {
            lock.read {
                decoding.incrementAndGet()
                require(!isRecycled) { "The decoder has been recycled" }
            }
            decodeRegion(region, sampleSize)
        } finally {
            if (decoding.decrementAndGet() == 0) checkRecycle()
        }
    }

    private fun decodeRegion(
        region: Rect,
        sampleSize: Int,
    ): Bitmap? {
        val full = Rect(0, 0, bounds.width(), bounds.height())
        val outWidth = maxOf(1, region.width() / sampleSize)
        val outHeight = maxOf(1, region.height() / sampleSize)
        if (region == full && bounds.left == 0 && bounds.top == 0) {
            // The whole image: let the decoder subsample while decoding.
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
            if (bitmap.width == outWidth && bitmap.height == outHeight) return bitmap
            return Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true).also { if (it !== bitmap) bitmap.recycle() }
        }
        val source = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        try {
            val crop = Bitmap.createBitmap(source, bounds.left + region.left, bounds.top + region.top, region.width(), region.height())
            if (crop.width == outWidth && crop.height == outHeight) return crop
            return Bitmap.createScaledBitmap(crop, outWidth, outHeight, true).also { if (it !== crop && crop !== source) crop.recycle() }
        } finally {
            source.recycle()
        }
    }

    private fun checkValidInput(
        region: Rect,
        sampleSize: Int,
    ) {
        val x = region.left
        val y = region.top
        val width = region.width()
        val height = region.height()
        if (x < 0 || x > this.width || y < 0 || y > this.height || width <= 0 || width + x > this.width || height <= 0 || height + y > this.height) {
            throw IllegalStateException("Requested region is invalid")
        }
        if (!(sampleSize > 0 && Integer.bitCount(sampleSize) == 1)) {
            throw IllegalStateException("Sample size must be a power of 2 but got $sampleSize")
        }
    }

    fun recycle() {
        if (isRecycled) return
        shouldRecycle = true
        checkRecycle()
    }

    private fun checkRecycle() {
        lock.write {
            if (!isRecycled && shouldRecycle && decoding.get() == 0) isRecycled = true
        }
    }

    protected fun finalize() {
        recycle()
    }

    companion object {
        fun newInstance(
            stream: InputStream,
            cropBorders: Boolean = false,
            displayProfile: ByteArray? = null,
        ): ImageDecoder? {
            val data = stream.use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var bounds = Rect(0, 0, options.outWidth, options.outHeight)
            if (cropBorders) bounds = findBorders(data) ?: bounds
            return ImageDecoder(data, bounds, bounds.width(), bounds.height())
        }

        fun findType(bytes: ByteArray): ImageType? = sniff(bytes)

        /** The content bounds inside uniform (near-white or near-black) borders, or null. */
        private fun findBorders(data: ByteArray): Rect? {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            try {
                val w = bitmap.width
                val h = bitmap.height
                val corner = bitmap.getPixel(0, 0)
                val row = IntArray(w)
                val column = IntArray(h)
                fun uniformRow(y: Int): Boolean {
                    bitmap.getPixels(row, 0, w, 0, y, w, 1)
                    return row.all { close(it, corner) }
                }
                fun uniformColumn(x: Int): Boolean {
                    bitmap.getPixels(column, 0, 1, x, 0, 1, h)
                    return column.all { close(it, corner) }
                }
                var top = 0
                while (top < h - 1 && uniformRow(top)) top++
                var bottom = h
                while (bottom > top + 1 && uniformRow(bottom - 1)) bottom--
                var left = 0
                while (left < w - 1 && uniformColumn(left)) left++
                var right = w
                while (right > left + 1 && uniformColumn(right - 1)) right--
                return Rect(left, top, right, bottom)
            } finally {
                bitmap.recycle()
            }
        }

        private fun close(
            a: Int,
            b: Int,
        ): Boolean {
            for (shift in intArrayOf(16, 8, 0)) {
                if (abs(((a shr shift) and 0xff) - ((b shr shift) and 0xff)) > 16) return false
            }
            return true
        }

        internal fun sniff(bytes: ByteArray): ImageType? {
            fun at(
                i: Int,
                s: String,
            ) = bytes.size >= i + s.length && s.indices.all { bytes[i + it] == s[it].code.toByte() }
            return when {
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ImageType(Format.Jpeg, false)
                bytes.size >= 4 && bytes[0] == 0x89.toByte() && at(1, "PNG") -> ImageType(Format.Png, at(37, "acTL") || containsChunk(bytes, "acTL"))
                at(0, "RIFF") && at(8, "WEBP") -> ImageType(Format.Webp, at(12, "VP8X") && bytes.size > 20 && (bytes[20].toInt() and 0x02) != 0)
                at(0, "GIF8") -> ImageType(Format.Gif, false)
                at(4, "ftyp") && (at(8, "avif") || at(8, "avis")) -> ImageType(Format.Avif, at(8, "avis"))
                at(4, "ftyp") && (at(8, "heic") || at(8, "heix") || at(8, "mif1") || at(8, "msf1") || at(8, "heim") || at(8, "heis")) ->
                    ImageType(Format.Heif, at(8, "msf1"))
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x0A.toByte() -> ImageType(Format.Jxl, false)
                at(4, "JXL ") -> ImageType(Format.Jxl, false)
                else -> null
            }
        }

        private fun containsChunk(
            bytes: ByteArray,
            name: String,
        ): Boolean {
            val limit = minOf(bytes.size - 4, 4096)
            for (i in 8 until limit) {
                if (name.indices.all { bytes[i + it] == name[it].code.toByte() }) return true
            }
            return false
        }
    }
}

data class ImageType internal constructor(
    val format: Format,
    val isAnimated: Boolean,
)

enum class Format {
    Jpeg,
    Png,
    Webp,
    Gif,
    Heif,
    Avif,
    Jxl,
}
