package oneechan.host.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Conformance checks of android.graphics, run through the public Android API on a backend: the
 * same suite runs on AWT in Gradle and on the native backend inside the app's JVM (the embedded
 * host's `debug.graphicsSelfTest`). Fixtures (graphics-selftest/) come from
 * scripts/make_graphics_fixtures.py; [Patterns] mirrors its pixel formulas.
 */
object GraphicsSelfTest {
    class Result(
        val name: String,
        val passed: Boolean,
        val skipped: Boolean,
        val detail: String,
        val millis: Long,
    )

    private class Skip(
        reason: String,
    ) : RuntimeException(reason)

    private class Check(
        val name: String,
        val body: () -> Unit,
    )

    /** Runs every check (or those whose name starts with [only]) on [backend]. */
    fun run(
        backend: GraphicsBackend = Graphics.backend(),
        only: String? = null,
    ): List<Result> =
        Graphics.withBackend(backend) {
            checks.filter { only == null || it.name.startsWith(only) }.map { check ->
                val started = System.nanoTime()
                val (passed, skipped, detail) =
                    try {
                        check.body()
                        Triple(true, false, "")
                    } catch (e: Skip) {
                        Triple(true, true, e.message.orEmpty())
                    } catch (e: Throwable) {
                        Triple(false, false, "${e::class.java.simpleName}: ${e.message}")
                    }
                Result(check.name, passed, skipped, detail, (System.nanoTime() - started) / 1_000_000)
            }
        }

    fun summary(results: List<Result>): String =
        "passed=${results.count { it.passed && !it.skipped }} skipped=${results.count { it.skipped }} failed=${results.count { !it.passed }}"

    // --- Patterns (mirrors scripts/make_graphics_fixtures.py).

    object Patterns {
        fun rgba(
            x: Int,
            y: Int,
        ): Int {
            val a = if (x % 4 == 0) 255 else (x * 9 + y * 17 + 1) and 0xff
            val r = (x * 11 + y * 3) and 0xff
            val g = (x * 5 + y * 13) and 0xff
            val b = (x * x + y * 7) and 0xff
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun smooth(
            x: Int,
            y: Int,
        ): Int = Color.rgb(x * 4, y * 5, (x + y) * 2)

        fun palette(
            x: Int,
            y: Int,
        ): Int = Color.rgb(x * 16, y * 16, 128)

        /** An opaque pattern with distinct neighbours, for exact copies. */
        fun opaque(
            x: Int,
            y: Int,
        ): Int = Color.rgb((x * 37 + y * 11) and 0xff, (x * 7 + y * 53) and 0xff, (x * y + 31) and 0xff)
    }

    private fun fixture(name: String): ByteArray =
        GraphicsSelfTest::class.java.getResourceAsStream("graphics-selftest/$name")?.use { it.readBytes() }
            ?: error("missing fixture $name")

    private fun isNative(): Boolean = Graphics.backend().name == "native"

    // --- Assertions.

    private fun expect(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw AssertionError(message())
    }

    private fun hex(c: Int) = "%08X".format(c)

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun raw(bitmap: Bitmap): ByteArray = ByteBuffer.allocate(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it) }.array()

    /** Compares the premultiplied storage against unpremultiplied expectations, within [tolerance]. */
    private fun expectPremultiplied(
        bitmap: Bitmap,
        tolerance: Int,
        expected: (Int, Int) -> Int,
    ) {
        val bytes = raw(bitmap)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val want = PixelMath.premultiply(expected(x, y))
                val o = (y * bitmap.width + x) * 4
                val got = ((bytes[o + 3].toInt() and 0xff) shl 24) or ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
                expect(distance(got, want) <= tolerance) { "($x,$y): stored ${hex(got)}, expected ${hex(want)}" }
            }
        }
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int {
        var max = 0
        for (shift in intArrayOf(24, 16, 8, 0)) max = maxOf(max, abs(((a ushr shift) and 0xff) - ((b ushr shift) and 0xff)))
        return max
    }

    private fun expectPixels(
        bitmap: Bitmap,
        tolerance: Int,
        what: String = "",
        expected: (Int, Int) -> Int,
    ) {
        val px = pixels(bitmap)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val got = px[y * bitmap.width + x]
                val want = expected(x, y)
                expect(distance(got, want) <= tolerance) { "$what($x,$y): got ${hex(got)}, expected ${hex(want)}" }
            }
        }
    }

    private fun meanError(
        bitmap: Bitmap,
        expected: (Int, Int) -> Int,
    ): Double {
        val px = pixels(bitmap)
        var sum = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val got = px[y * bitmap.width + x]
                val want = expected(x, y)
                for (shift in intArrayOf(16, 8, 0)) sum += abs(((got shr shift) and 0xff) - ((want shr shift) and 0xff))
            }
        }
        return sum / (bitmap.width * bitmap.height * 3.0)
    }

    private fun bitmapOf(
        width: Int,
        height: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        color: (Int, Int) -> Int,
    ): Bitmap {
        val colors = IntArray(width * height) { color(it % width, it / width) }
        return Bitmap.createBitmap(width, height, config).also { it.setPixels(colors, 0, width, 0, 0, width, height) }
    }

    private fun decode(name: String): Bitmap = BitmapFactory.decodeByteArray(fixture(name), 0, fixture(name).size) ?: error("$name did not decode")

    private val checks: List<Check> =
        listOf(
            Check("decode.png") {
                val bitmap = decode("pattern.png")
                expect(bitmap.width == 23 && bitmap.height == 17) { "size ${bitmap.width}x${bitmap.height}" }
                expect(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.hasAlpha()) { "config ${bitmap.config} alpha ${bitmap.hasAlpha()}" }
                expect(!bitmap.isMutable) { "decoded bitmaps are immutable" }
                expectPremultiplied(bitmap, 1, Patterns::rgba)
                expectPixels(bitmap, 0, "opaque ") { x, y -> if (x % 4 == 0) Patterns.rgba(x, y) else pixels(bitmap)[y * 23 + x] }
            },
            Check("decode.options") {
                val data = fixture("pattern.png")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                expect(BitmapFactory.decodeByteArray(data, 0, data.size, bounds) == null) { "bounds-only decode returned a bitmap" }
                expect(bounds.outWidth == 23 && bounds.outHeight == 17 && bounds.outMimeType == "image/png") {
                    "bounds ${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType}"
                }
                val sampled = BitmapFactory.Options().apply { inSampleSize = 3 }
                val half = BitmapFactory.decodeByteArray(data, 0, data.size, sampled)!!
                expect(half.width == 11 && half.height == 8 && sampled.outWidth == 11) { "sample 3 (as 2): ${half.width}x${half.height}" }
                val mutable = BitmapFactory.decodeStream(ByteArrayInputStream(data), Rect(), BitmapFactory.Options().apply { inMutable = true })!!
                expect(mutable.isMutable) { "inMutable" }
                val padding = Rect()
                BitmapFactory.decodeStream(ByteArrayInputStream(data), padding, null)
                expect(padding == Rect(-1, -1, -1, -1)) { "padding $padding" }
                val jpeg = fixture("smooth.jpg")
                val rgb565 = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })!!
                expect(rgb565.config == Bitmap.Config.RGB_565 && !rgb565.hasAlpha()) { "565 ${rgb565.config}" }
                val jpegBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 2 }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, jpegBounds)
                expect(jpegBounds.outWidth == 32 && jpegBounds.outHeight == 24 && jpegBounds.outMimeType == "image/jpeg") { "jpeg bounds ${jpegBounds.outWidth}" }
            },
            Check("decode.jpeg") {
                val bitmap = decode("smooth.jpg")
                expect(bitmap.width == 64 && bitmap.height == 48 && !bitmap.hasAlpha()) { "size ${bitmap.width}x${bitmap.height} alpha ${bitmap.hasAlpha()}" }
                val error = meanError(bitmap, Patterns::smooth)
                expect(error < 3.0) { "mean error $error" }
                expectPixels(bitmap, 24, "jpeg ", Patterns::smooth)
            },
            Check("decode.webp") {
                val bitmap = decode("pattern.webp")
                expect(bitmap.width == 23 && bitmap.height == 17) { "size ${bitmap.width}x${bitmap.height}" }
                expectPremultiplied(bitmap, 1, Patterns::rgba)
            },
            Check("decode.gif") {
                val bitmap = decode("palette.gif")
                expect(bitmap.width == 16 && bitmap.height == 16) { "size" }
                expectPixels(bitmap, 0, "gif ", Patterns::palette)
            },
            Check("decode.heif-avif") {
                if (!isNative()) throw Skip("only ImageIO decodes HEIF and AVIF")
                for (name in listOf("smooth.heic", "smooth.avif")) {
                    val data = fixture(name)
                    val options = BitmapFactory.Options()
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: error("$name did not decode")
                    expect(bitmap.width == 64 && bitmap.height == 48) { "$name size ${bitmap.width}x${bitmap.height}" }
                    expect(options.outMimeType == if (name.endsWith("heic")) "image/heif" else "image/avif") { "$name mime ${options.outMimeType}" }
                    val error = meanError(bitmap, Patterns::smooth)
                    expect(error < 6.0) { "$name mean error $error" }
                }
            },
            Check("decode.invalid") {
                expect(BitmapFactory.decodeByteArray(ByteArray(64) { it.toByte() }, 0, 64) == null) { "garbage decoded" }
                expect(BitmapFactory.decodeStream(ByteArrayInputStream(ByteArray(0))) == null) { "empty stream decoded" }
                expect(BitmapFactory.decodeFile("/nonexistent/file.png") == null) { "missing file decoded" }
            },
            Check("pixels.roundtrip") {
                val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
                expect(bitmap.isMutable && bitmap.getPixel(3, 3) == 0) { "new bitmaps are transparent" }
                val source = IntArray(3 + 9 * 5) { if (it < 3) 0 else Patterns.rgba((it - 3) % 9, (it - 3) / 9) }
                bitmap.setPixels(source, 3, 9, 0, 0, 7, 5)
                val out = IntArray(2 + 8 * 5)
                bitmap.getPixels(out, 2, 8, 0, 0, 7, 5)
                for (y in 0 until 5) {
                    for (x in 0 until 7) {
                        val want = PixelMath.unpremultiply(PixelMath.premultiply(Patterns.rgba(x, y)))
                        expect(out[2 + y * 8 + x] == want) { "($x,$y) ${hex(out[2 + y * 8 + x])} != ${hex(want)}" }
                    }
                }
                val flipped = IntArray(7 * 5)
                bitmap.getPixels(flipped, 7 * 4, -7, 0, 0, 7, 5)
                expect(flipped[0] == bitmap.getPixel(0, 4)) { "negative stride" }
                bitmap.setPixel(1, 1, 0x80FF0000.toInt())
                expect(bitmap.getPixel(1, 1) == 0x80FF0000.toInt()) { "half-transparent red: ${hex(bitmap.getPixel(1, 1))}" }
                bitmap.setPixel(2, 2, 0x00123456)
                expect(bitmap.getPixel(2, 2) == 0) { "transparent pixels read back as 0" }
                bitmap.eraseColor(0xFF336699.toInt())
                expect(pixels(bitmap).all { it == 0xFF336699.toInt() }) { "eraseColor" }
                val rgb565 = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
                expect(rgb565.getPixel(0, 0) == 0xFF000000.toInt()) { "565 starts black" }
                rgb565.setPixel(0, 0, 0x800A0D0B.toInt())
                expect(rgb565.getPixel(0, 0) == PixelMath.quantize565(0xFF0A0D0B.toInt())) { "565 quantizes: ${hex(rgb565.getPixel(0, 0))}" }
                val alpha8 = Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8)
                alpha8.setPixel(1, 0, 0x80123456.toInt())
                expect(alpha8.getPixel(1, 0) == 0x80000000.toInt()) { "ALPHA_8 keeps alpha only: ${hex(alpha8.getPixel(1, 0))}" }
                var threw = false
                try {
                    bitmap.getPixel(7, 0)
                } catch (e: IllegalArgumentException) {
                    threw = true
                }
                expect(threw) { "out of bounds getPixel" }
            },
            Check("pixels.buffers") {
                val bitmap = bitmapOf(5, 3, color = Patterns::rgba)
                expect(bitmap.rowBytes == 20 && bitmap.byteCount == 60) { "row bytes ${bitmap.rowBytes}" }
                val bytes = raw(bitmap)
                val p = PixelMath.premultiply(Patterns.rgba(1, 2))
                val o = (2 * 5 + 1) * 4
                expect(
                    bytes[o].toInt() and 0xff == (p shr 16) and 0xff && bytes[o + 1].toInt() and 0xff == (p shr 8) and 0xff &&
                        bytes[o + 2].toInt() and 0xff == p and 0xff && bytes[o + 3].toInt() and 0xff == p ushr 24,
                ) { "raw layout is premultiplied R, G, B, A" }
                val copy = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888)
                copy.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                expect(raw(copy).contentEquals(bytes)) { "byte buffer round trip" }
                val ints = ByteBuffer.allocate(60).order(ByteOrder.nativeOrder()).asIntBuffer()
                bitmap.copyPixelsToBuffer(ints)
                expect(ints.position() == 15) { "int buffer position ${ints.position()}" }
                ints.rewind()
                val fromInts = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(ints) }
                expect(raw(fromInts).contentEquals(bytes)) { "int buffer round trip" }
                val rgb565 = Bitmap.createBitmap(2, 1, Bitmap.Config.RGB_565)
                rgb565.setPixel(0, 0, 0xFFFF0000.toInt())
                rgb565.setPixel(1, 0, 0xFF0000FF.toInt())
                val packed = raw(rgb565)
                expect(packed.size == 4 && packed[0] == 0x00.toByte() && packed[1] == 0xF8.toByte() && packed[2] == 0x1F.toByte() && packed[3] == 0.toByte()) {
                    "565 raw ${packed.joinToString { "%02X".format(it) }}"
                }
                var threw = false
                try {
                    bitmap.copyPixelsToBuffer(ByteBuffer.allocate(10))
                } catch (e: RuntimeException) {
                    threw = true
                }
                expect(threw) { "small buffer" }
            },
            Check("create.colors") {
                val colors = IntArray(6 * 4) { Patterns.opaque(it % 6, it / 6) }
                val bitmap = Bitmap.createBitmap(colors, 6, 4, Bitmap.Config.ARGB_8888)
                expect(!bitmap.isMutable) { "colors bitmaps are immutable" }
                expect(pixels(bitmap).contentEquals(colors)) { "colors round trip" }
                val strided = Bitmap.createBitmap(colors, 1, 6, 4, 4, Bitmap.Config.ARGB_8888)
                expectPixels(strided, 0) { x, y -> Patterns.opaque(x + 1, y) }
                val sub = Bitmap.createBitmap(bitmap, 2, 1, 3, 2)
                expect(sub.width == 3 && sub.height == 2) { "subset size" }
                expectPixels(sub, 0) { x, y -> Patterns.opaque(x + 2, y + 1) }
                expect(Bitmap.createBitmap(bitmap) === bitmap) { "an immutable source asked for whole is returned as is" }
                val mutable = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
                expect(Bitmap.createBitmap(mutable) !== mutable) { "a mutable source is copied" }
            },
            Check("descramble.tiles") {
                // GigaViewer-style: a grid of tiles moved by a permutation, plus an unscrambled remainder.
                val width = 43
                val height = 31
                val src = bitmapOf(width, height, color = Patterns::opaque)
                val cols = 4
                val rows = 3
                val tw = 10
                val th = 10
                val dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(dst)
                canvas.drawBitmap(src, 0f, 0f, null)
                val paint = Paint()
                val rect = Rect()
                val target = Rect()
                for (i in 0 until cols * rows) {
                    val from = (i * 5 + 3) % (cols * rows)
                    rect.set((from % cols) * tw, (from / cols) * th, (from % cols) * tw + tw, (from / cols) * th + th)
                    target.set((i % cols) * tw, (i / cols) * th, (i % cols) * tw + tw, (i / cols) * th + th)
                    canvas.drawBitmap(src, rect, target, if (i % 2 == 0) null else paint)
                }
                expectPixels(dst, 0, "descrambled ") { x, y ->
                    if (x >= cols * tw || y >= rows * th) {
                        Patterns.opaque(x, y)
                    } else {
                        val i = (y / th) * cols + (x / tw)
                        val from = (i * 5 + 3) % (cols * rows)
                        Patterns.opaque((from % cols) * tw + x % tw, (from / cols) * th + y % th)
                    }
                }
            },
            Check("descramble.jpeg") {
                val photo = decode("smooth.jpg")
                val out = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                for (i in 0 until 4) {
                    canvas.drawBitmap(photo, Rect(i * 16, 0, i * 16 + 16, 48), RectF((3 - i) * 16f, 0f, (4 - i) * 16f, 48f), null)
                }
                val reference = pixels(photo)
                val expected = { x: Int, y: Int -> reference[y * 64 + (3 - x / 16) * 16 + x % 16] }
                expectPixels(out, 0, "strips ", expected)
                val stream = ByteArrayOutputStream()
                expect(out.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "compress" }
                val again = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())!!
                expect(again.width == 64 && again.height == 48) { "re-decoded size" }
                val error = meanError(again, expected)
                expect(error < 4.0) { "re-encoded mean error $error" }
            },
            Check("draw.alpha") {
                val background = bitmapOf(4, 4) { _, _ -> 0xFF0000FF.toInt() }
                val overlay = bitmapOf(4, 4) { x, _ -> if (x < 2) 0x80FF0000.toInt() else 0x00000000 }
                Canvas(background).drawBitmap(overlay, 0f, 0f, null)
                expectPixels(background, 2, "src-over ") { x, _ -> if (x < 2) 0xFF80007F.toInt() else 0xFF0000FF.toInt() }
                val faded = bitmapOf(2, 2) { _, _ -> 0xFFFFFFFF.toInt() }
                val black = bitmapOf(2, 2) { _, _ -> 0xFF000000.toInt() }
                Canvas(faded).drawBitmap(black, 0f, 0f, Paint().apply { alpha = 64 })
                expectPixels(faded, 2, "paint alpha ") { _, _ -> 0xFFBFBFBF.toInt() }
                val mask = Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8).also { it.eraseColor(0xFF000000.toInt()) }
                val tinted = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                Canvas(tinted).drawBitmap(mask, 0f, 0f, Paint().apply { color = 0xFF00FF00.toInt() })
                expectPixels(tinted, 1, "alpha mask ") { _, _ -> 0xFF00FF00.toInt() }
            },
            Check("clip") {
                val bitmap = bitmapOf(30, 30) { _, _ -> Color.WHITE }
                val canvas = Canvas(bitmap)
                val saved = canvas.save()
                expect(canvas.clipRect(10, 10, 20, 20)) { "clip not empty" }
                expect(canvas.clipRect(5f, 5f, 15f, 15f)) { "nested clip not empty" }
                expect(canvas.clipBounds == Rect(10, 10, 15, 15)) { "clip bounds ${canvas.clipBounds}" }
                canvas.drawColor(Color.RED)
                canvas.restoreToCount(saved)
                canvas.save()
                canvas.translate(20f, 0f)
                canvas.clipRect(0f, 20f, 5f, 25f)
                canvas.drawColor(Color.BLUE)
                canvas.restore()
                expect(canvas.clipBounds == Rect(0, 0, 30, 30)) { "restored clip ${canvas.clipBounds}" }
                canvas.save()
                canvas.clipOutRect(0, 0, 29, 30)
                canvas.drawColor(Color.GREEN)
                canvas.restore()
                expectPixels(bitmap, 0, "clipped ") { x, y ->
                    when {
                        x >= 29 -> Color.GREEN
                        x in 10 until 15 && y in 10 until 15 -> Color.RED
                        x in 20 until 25 && y in 20 until 25 -> Color.BLUE
                        else -> Color.WHITE
                    }
                }
                expect(canvas.quickReject(40f, 40f, 50f, 50f) && !canvas.quickReject(0f, 0f, 1f, 1f)) { "quickReject" }
            },
            Check("colorfilter") {
                val src = bitmapOf(8, 8, color = Patterns::opaque)
                val dst = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                val gray = ColorMatrix().apply { setSaturation(0f) }
                Canvas(dst).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
                expectPixels(dst, 1, "gray ") { x, y -> gray.apply(Patterns.opaque(x, y)) }
                val inverted = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
                val shapes = bitmapOf(4, 4) { _, _ -> Color.BLACK }
                Canvas(shapes).drawRect(0f, 0f, 4f, 4f, Paint().apply { color = 0xFF102030.toInt(); colorFilter = ColorMatrixColorFilter(inverted) })
                expectPixels(shapes, 0, "filtered shape ") { _, _ -> 0xFFEFDFCF.toInt() }
            },
            Check("compress") {
                val pattern = decode("pattern.png")
                val png = ByteArrayOutputStream()
                expect(pattern.compress(Bitmap.CompressFormat.PNG, 100, png)) { "png compress" }
                val again = BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size())!!
                expect(again.width == 23 && again.height == 17) { "png size" }
                expectPremultiplied(again, 1, Patterns::rgba)
                val photo = bitmapOf(32, 32) { x, y -> Patterns.smooth(x, y) }
                for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP, Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
                    val out = ByteArrayOutputStream()
                    expect(photo.compress(format, 85, out) && out.size() > 0) { "$format compress" }
                    val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: error("$format output does not decode")
                    expect(decoded.width == 32 && decoded.height == 32) { "$format size" }
                    val error = meanError(decoded, Patterns::smooth)
                    expect(error < 4.0) { "$format mean error $error" }
                }
                var threw = false
                try {
                    photo.compress(Bitmap.CompressFormat.PNG, 101, ByteArrayOutputStream())
                } catch (e: IllegalArgumentException) {
                    threw = true
                }
                expect(threw) { "quality 101" }
            },
            Check("scale") {
                val src = bitmapOf(4, 3, color = Patterns::opaque)
                val doubled = Bitmap.createScaledBitmap(src, 8, 6, false)
                expect(doubled.width == 8 && doubled.height == 6) { "size" }
                expectPixels(doubled, 0, "nearest ") { x, y -> Patterns.opaque(x / 2, y / 2) }
                val uniform = bitmapOf(10, 10) { _, _ -> 0xFF4080C0.toInt() }
                val smaller = Bitmap.createScaledBitmap(uniform, 3, 7, true)
                expectPixels(smaller, 1, "filtered uniform ") { _, _ -> 0xFF4080C0.toInt() }
                val half = Bitmap.createScaledBitmap(doubled, 4, 3, false)
                expectPixels(half, 0, "nearest down ") { x, y -> Patterns.opaque(x, y) }
                expect(Bitmap.createScaledBitmap(src, 4, 3, true) !== src) { "a mutable source is copied" }
            },
            Check("matrix.draw") {
                val src = bitmapOf(3, 2, color = Patterns::opaque)
                val rotate = Matrix().apply { setRotate(90f) }
                val rotated = Bitmap.createBitmap(src, 0, 0, 3, 2, rotate, false)
                expect(rotated.width == 2 && rotated.height == 3) { "rotated size ${rotated.width}x${rotated.height}" }
                // 90 degrees clockwise: dst(x, y) = src(y, H - 1 - x).
                expectPixels(rotated, 0, "rotated ") { x, y -> Patterns.opaque(y, 1 - x) }
                val dst = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
                val m = Matrix().apply { postScale(2f, 2f); postTranslate(0f, 0f) }
                Canvas(dst).drawBitmap(src, m, null)
                expectPixels(dst, 0, "scaled by matrix ") { x, y -> Patterns.opaque(x / 2, y / 2) }
                val flipped = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
                Canvas(flipped).apply {
                    scale(-1f, 1f, 1.5f, 1f)
                    drawBitmap(src, 0f, 0f, null)
                }
                expectPixels(flipped, 0, "mirrored ") { x, y -> Patterns.opaque(2 - x, y) }
            },
            Check("canvas.transform") {
                val bitmap = bitmapOf(20, 20) { _, _ -> Color.WHITE }
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { isAntiAlias = false; color = Color.BLACK }
                canvas.save()
                canvas.translate(2f, 3f)
                canvas.scale(2f, 2f)
                canvas.drawRect(1f, 1f, 3f, 2f, paint)
                canvas.restore()
                canvas.save()
                canvas.rotate(90f, 15f, 15f)
                canvas.drawRect(15f, 15f, 18f, 16f, Paint(paint).apply { color = Color.RED })
                canvas.restore()
                expect(canvas.saveCount == 1) { "save count ${canvas.saveCount}" }
                expectPixels(bitmap, 0, "transformed rects ") { x, y ->
                    when {
                        x in 4 until 8 && y in 5 until 7 -> Color.BLACK
                        x in 14 until 15 && y in 15 until 18 -> Color.RED
                        else -> Color.WHITE
                    }
                }
            },
            Check("shapes") {
                val bitmap = bitmapOf(40, 40) { _, _ -> Color.WHITE }
                val canvas = Canvas(bitmap)
                val solid = Paint().apply { isAntiAlias = false; color = Color.BLACK }
                canvas.drawRect(Rect(1, 1, 5, 4), solid)
                canvas.drawCircle(20f, 20f, 6f, Paint().apply { color = Color.RED; style = Paint.Style.FILL })
                canvas.drawLine(0f, 35.5f, 40f, 35.5f, Paint(solid).apply { strokeWidth = 1f; color = Color.BLUE })
                val triangle = Path().apply { moveTo(30f, 1f); lineTo(39f, 1f); lineTo(39f, 10f); close() }
                canvas.drawPath(triangle, Paint(solid).apply { color = Color.GREEN })
                val ring = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                canvas.drawCircle(8f, 24f, 4f, ring)
                val px = pixels(bitmap)
                fun at(
                    x: Int,
                    y: Int,
                ) = px[y * 40 + x]
                expect(at(1, 1) == Color.BLACK && at(4, 3) == Color.BLACK && at(5, 3) == Color.WHITE && at(4, 4) == Color.WHITE) { "rect coverage" }
                expect(at(20, 20) == Color.RED && at(15, 20) == Color.RED && at(26, 26) == Color.WHITE) { "circle ${hex(at(20, 20))}" }
                expect(at(10, 35) == Color.BLUE && at(10, 34) == Color.WHITE && at(10, 36) == Color.WHITE) { "line ${hex(at(10, 35))} ${hex(at(10, 36))}" }
                expect(at(38, 2) == Color.GREEN && at(31, 8) == Color.WHITE) { "triangle" }
                expect(at(8, 24) == Color.WHITE && distance(at(4, 24), Color.BLACK) < 128) { "ring ${hex(at(4, 24))}" }
            },
            Check("text") {
                val paint = TextPaint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true; typeface = Typeface.DEFAULT }
                val width = paint.measureText("Hello")
                expect(width in 20f..120f) { "measureText $width" }
                val bold = TextPaint(paint).apply { typeface = Typeface.DEFAULT_BOLD }
                expect(bold.measureText("Hello") >= width - 0.01f) { "bold narrower" }
                val metrics = paint.fontMetrics
                expect(metrics.ascent < 0 && metrics.descent > 0 && metrics.top <= metrics.ascent && metrics.bottom >= metrics.descent) { "metrics $metrics" }
                val bounds = Rect()
                paint.getTextBounds("Hello", 0, 5, bounds)
                expect(bounds.width() > 10 && bounds.top < 0 && bounds.bottom >= -1) { "bounds $bounds" }
                expect(paint.breakText("Hello world", true, width, null) in 4..6) { "breakText" }
                val bitmap = bitmapOf(160, 40) { _, _ -> Color.WHITE }
                Canvas(bitmap).drawText("Hello", 10f, 30f, paint)
                val px = pixels(bitmap)
                val inked = (0 until 160 * 40).count { px[it] != Color.WHITE }
                val stray = (0 until 160 * 40).count { px[it] != Color.WHITE && (it % 160 < 8 || it % 160 > 12 + width.toInt() + 4) }
                expect(inked > 40 && stray == 0) { "drawText inked $inked stray $stray" }
                val text = "The quick brown fox jumps over the lazy dog. Comic notes wrap onto several lines here."
                val layout = StaticLayout(text, paint, 200, Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
                expect(layout.lineCount in 3..12 && layout.height > layout.lineCount * 18) { "layout lines ${layout.lineCount} height ${layout.height}" }
                expect(layout.getLineStart(1) > 0 && text[layout.getLineStart(1) - 1] == ' ') { "breaks at spaces" }
                val page = Bitmap.createBitmap(240, layout.height + 20, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(page)
                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.translate(20f, 10f)
                layout.draw(canvas)
                canvas.restore()
                val drawn = pixels(page).count { it != Color.WHITE }
                expect(drawn > 300) { "layout drew $drawn pixels" }
                val centered = Paint(paint).apply { textAlign = Paint.Align.CENTER }
                val mid = bitmapOf(160, 40) { _, _ -> Color.WHITE }
                Canvas(mid).drawText("Hi", 80f, 30f, centered)
                val cols = pixels(mid).withIndex().filter { it.value != Color.WHITE }.map { it.index % 160 }
                expect(cols.isNotEmpty() && cols.min() < 80 && cols.max() > 80) { "centered text spans the anchor" }
            },
            Check("decoders.tachiyomi") {
                val data = fixture("pattern.png")
                val decoder = tachiyomi.decoder.ImageDecoder.newInstance(ByteArrayInputStream(data)) ?: error("newInstance returned null")
                expect(decoder.width == 23 && decoder.height == 17) { "size" }
                val whole = decoder.decode() ?: error("decode returned null")
                expectPremultiplied(whole, 1, Patterns::rgba)
                val region = decoder.decode(Rect(4, 2, 12, 10), 2) ?: error("region decode returned null")
                expect(region.width == 4 && region.height == 4) { "region size ${region.width}x${region.height}" }
                expect(tachiyomi.decoder.ImageDecoder.findType(data)?.format == tachiyomi.decoder.Format.Png) { "findType" }
                expect(tachiyomi.decoder.ImageDecoder.findType(fixture("smooth.jpg"))?.format == tachiyomi.decoder.Format.Jpeg) { "findType jpeg" }
                decoder.recycle()
                expect(decoder.isRecycled) { "recycled" }
                expect(tachiyomi.decoder.ImageDecoder.newInstance(ByteArrayInputStream(ByteArray(16))) == null) { "garbage decoder" }
            },
            Check("decoders.mpreg") {
                val data = fixture("smooth.jpg")
                ca.mpreg.imagedecoder.ImageDecoder.open(ByteArrayInputStream(data)).use { decoder ->
                    expect(decoder.width == 64 && decoder.height == 48 && !decoder.isHdr && decoder.format == ca.mpreg.imagedecoder.ImageDecoder.Format.JPEG) {
                        "decoder ${decoder.width}x${decoder.height} ${decoder.format}"
                    }
                    decoder.decodeNext().use { frame ->
                        expect(frame.width == 64 && frame.height == 48 && frame.image.remaining() == 64 * 48 * 4) { "frame" }
                        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(frame.image)
                        expect(pixels(bitmap).contentEquals(pixels(decode("smooth.jpg")))) { "frame pixels match BitmapFactory" }
                    }
                    var threw = false
                    try {
                        decoder.decodeNext()
                    } catch (e: ca.mpreg.imagedecoder.ImageDecoder.DecodeException) {
                        threw = true
                    }
                    expect(threw) { "a still has one frame" }
                    expect(decoder.decodeToBitmap(Rect(0, 0, 32, 16), 2).let { it.width == 16 && it.height == 8 }) { "decodeToBitmap region" }
                }
                var unknown = false
                try {
                    ca.mpreg.imagedecoder.ImageDecoder.open(ByteArrayInputStream(ByteArray(128)))
                } catch (e: ca.mpreg.imagedecoder.ImageDecoder.UnknownFormatException) {
                    unknown = true
                }
                expect(unknown) { "unknown format" }
            },
            Check("decoders.android") {
                val data = fixture("pattern.png")
                var header: ImageDecoder.ImageInfo? = null
                val bitmap =
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(data)) { decoder, info, _ ->
                        header = info
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSize(46, 34)
                    }
                expect(header?.size?.width == 23 && header?.mimeType == "image/png") { "header ${header?.size} ${header?.mimeType}" }
                expect(bitmap.width == 46 && bitmap.height == 34 && !bitmap.isMutable) { "target size ${bitmap.width}x${bitmap.height}" }
                val buffer = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, _, _ ->
                    decoder.crop = Rect(1, 1, 5, 4)
                    decoder.isMutableRequired = true
                }
                expect(buffer.width == 4 && buffer.height == 3 && buffer.isMutable) { "crop ${buffer.width}x${buffer.height}" }
                expectPremultiplied(buffer, 1) { x, y -> Patterns.rgba(x + 1, y + 1) }
                if (isNative()) {
                    val rotated = ImageDecoder.decodeBitmap(ImageDecoder.createSource(fixture("rotated.jpg")))
                    expect(rotated.width == 48 && rotated.height == 64) { "EXIF orientation ${rotated.width}x${rotated.height}" }
                    // Orientation 6: displayed rotated 90 degrees clockwise.
                    expect(meanError(rotated) { x, y -> Patterns.smooth(y, 47 - x) } < 4.0) { "rotated pixels" }
                }
                val raw = BitmapFactory.decodeByteArray(fixture("rotated.jpg"), 0, fixture("rotated.jpg").size)!!
                expect(raw.width == 64 && raw.height == 48) { "BitmapFactory ignores EXIF orientation" }
                var threw = false
                try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteArray(32)))
                } catch (e: ImageDecoder.DecodeException) {
                    threw = true
                }
                expect(threw) { "DecodeException" }
            },
            Check("copy") {
                val src = bitmapOf(4, 4, color = Patterns::rgba)
                val rgb565 = src.copy(Bitmap.Config.RGB_565, false)
                expect(rgb565.config == Bitmap.Config.RGB_565 && !rgb565.isMutable && !rgb565.hasAlpha()) { "565 copy ${rgb565.config}" }
                val opaque = bitmapOf(4, 4, color = Patterns::opaque)
                expectPixels(opaque.copy(Bitmap.Config.RGB_565, true), 0, "565 ") { x, y -> PixelMath.quantize565(Patterns.opaque(x, y)) }
                val alpha = src.copy(Bitmap.Config.ALPHA_8, true)
                expectPixels(alpha, 1, "alpha copy ") { x, y -> Patterns.rgba(x, y) and -0x1000000 }
                val same = src.copy(Bitmap.Config.ARGB_8888, true)
                expect(raw(same).contentEquals(raw(src)) && same.isMutable) { "8888 copy" }
                expect(src.sameAs(same) && !src.sameAs(opaque)) { "sameAs" }
            },
            Check("drawables") {
                // Extensions draw their own Drawables (e.g. login dialog icons) onto a Canvas.
                val bitmap = bitmapOf(10, 10) { _, _ -> Color.WHITE }
                val canvas = Canvas(bitmap)
                val fill = android.graphics.drawable.ColorDrawable(0xFF00FF00.toInt())
                fill.setBounds(2, 2, 6, 6)
                fill.draw(canvas)
                fill.alpha = 0
                expect(fill.opacity == android.graphics.PixelFormat.TRANSPARENT && fill.color ushr 24 == 0) { "ColorDrawable alpha" }
                val custom =
                    object : android.graphics.drawable.Drawable() {
                        val paint = Paint().apply { color = Color.BLUE; isAntiAlias = false }

                        override fun draw(canvas: Canvas) {
                            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), 1.5f, paint)
                        }

                        override fun setAlpha(alpha: Int) {}

                        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

                        @Deprecated("Deprecated in Java")
                        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                    }
                custom.setBounds(6, 6, 10, 10)
                custom.draw(canvas)
                val px = pixels(bitmap)
                expect(px[3 * 10 + 3] == Color.GREEN && px[1 * 10 + 1] == Color.WHITE && px[8 * 10 + 8] == Color.BLUE) { "drawables drew" }
            },
            Check("recycle") {
                val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
                bitmap.recycle()
                bitmap.recycle()
                expect(bitmap.isRecycled && bitmap.width == 3) { "recycled keeps its size" }
                var threw = false
                try {
                    bitmap.getPixel(0, 0)
                } catch (e: IllegalStateException) {
                    threw = true
                }
                expect(threw) { "getPixel on a recycled bitmap" }
                threw = false
                try {
                    Canvas(Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)).drawBitmap(bitmap, 0f, 0f, null)
                } catch (e: RuntimeException) {
                    threw = true
                }
                expect(threw) { "drawing a recycled bitmap" }
            },
            Check("threads") {
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val jobs =
                        (0 until 8).map { n ->
                            Callable {
                                val photo = decode("smooth.jpg")
                                val out = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(out)
                                for (i in 0 until 16) {
                                    val sx = (i % 4) * 16
                                    val sy = (i / 4) * 12
                                    val j = (i + n) % 16
                                    canvas.drawBitmap(photo, Rect(sx, sy, sx + 16, sy + 12), Rect((j % 4) * 16, (j / 4) * 12, (j % 4) * 16 + 16, (j / 4) * 12 + 12), null)
                                }
                                val stream = ByteArrayOutputStream()
                                out.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                photo.recycle()
                                out.recycle()
                                stream.size()
                            }
                        }
                    val sizes = pool.invokeAll(jobs).map { it.get(60, TimeUnit.SECONDS) }
                    expect(sizes.all { it > 0 }) { "outputs $sizes" }
                } finally {
                    pool.shutdownNow()
                }
            },
        )
}
