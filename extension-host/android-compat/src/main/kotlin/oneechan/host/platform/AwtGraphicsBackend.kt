package oneechan.host.platform

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam

/**
 * android.graphics on java.awt and javax.imageio (desktop host and plain JVM tests): the behaviour
 * of Suwayomi's AWT-backed classes, behind [GraphicsBackend]. Every bitmap is a TYPE_INT_ARGB_PRE
 * image; the Config's semantics (opaque RGB_565, alpha-only ALPHA_8) are applied when pixels are
 * read or written. Image codecs come from ImageIO plugins (TwelveMonkeys, webp-imageio).
 */
object AwtGraphicsBackend : GraphicsBackend {
    override val name: String = "awt"

    override fun probe(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ImageInfo? =
        withReader(data, offset, length) { reader ->
            val alpha = runCatching { reader.getImageTypes(0).next().colorModel.hasAlpha() }.getOrDefault(true)
            ImageInfo(reader.getWidth(0), reader.getHeight(0), reader.originatingProvider?.mimeTypes?.firstOrNull()?.let(::normalizeMime), alpha)
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
        val image =
            withReader(data, offset, length) { reader ->
                val param = reader.defaultReadParam
                if (sampleSize > 1) param.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
                reader.read(0, param)
            } ?: return null
        val buffer = AwtPixelBuffer(width, height, format)
        val source = if (image.width == width && image.height == height) image else scaled(image, width, height)
        val pixels = IntArray(width * height)
        source.getRGB(0, 0, width, height, pixels, 0, width)
        buffer.storeColors(pixels, 0, width, 0, 0, width, height)
        return buffer
    }

    override fun create(
        width: Int,
        height: Int,
        format: PixelFormat,
    ): PixelBuffer = AwtPixelBuffer(width, height, format)

    override fun fontMetrics(
        font: FontSpec,
        out: FloatArray,
    ) {
        val awt = awtFont(font)
        val metrics = awt.getLineMetrics("Xg", FRC)
        val bounds = awt.getMaxCharBounds(FRC)
        out[0] = minOf(bounds.y.toFloat(), -metrics.ascent)
        out[1] = -metrics.ascent
        out[2] = metrics.descent
        out[3] = maxOf((bounds.y + bounds.height).toFloat(), metrics.descent)
        out[4] = metrics.leading
    }

    override fun measureText(
        font: FontSpec,
        text: String,
    ): Float = if (text.isEmpty()) 0f else TextLayout(text, awtFont(font), FRC).advance

    override fun textBounds(
        font: FontSpec,
        text: String,
        out: FloatArray,
    ) {
        if (text.isEmpty()) {
            out.fill(0f)
            return
        }
        val bounds = TextLayout(text, awtFont(font), FRC).getOutline(null).bounds2D
        out[0] = bounds.minX.toFloat()
        out[1] = bounds.minY.toFloat()
        out[2] = bounds.maxX.toFloat()
        out[3] = bounds.maxY.toFloat()
    }

    private val FRC = FontRenderContext(null, true, true)
    private val fonts = ConcurrentHashMap<String, Font>()

    internal fun awtFont(spec: FontSpec): Font {
        val key = "${spec.family}|${spec.weight}|${spec.italic}|${spec.file}"
        val base =
            fonts.getOrPut(key) {
                val style = (if (spec.bold) Font.BOLD else 0) or (if (spec.italic) Font.ITALIC else 0)
                val fromFile =
                    spec.file?.let { path ->
                        runCatching { Font.createFont(Font.TRUETYPE_FONT, File(path)) }
                            .recoverCatching { Font.createFont(Font.TYPE1_FONT, File(path)) }
                            .getOrNull()
                            ?.deriveFont(style)
                    }
                fromFile ?: Font(logicalFamily(spec.family), style, 12)
            }
        var font = base.deriveFont(spec.size)
        if (spec.scaleX != 1f || spec.skewX != 0f) font = font.deriveFont(AffineTransform(spec.scaleX.toDouble(), 0.0, spec.skewX.toDouble(), 1.0, 0.0, 0.0))
        return font
    }

    private fun logicalFamily(family: String): String =
        when (family.lowercase()) {
            "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-condensed", "sans-serif-thin", "sans-serif-black" -> Font.SANS_SERIF
            "serif" -> Font.SERIF
            "monospace", "sans-serif-monospace", "serif-monospace" -> Font.MONOSPACED
            else -> family
        }

    private fun <T> withReader(
        data: ByteArray,
        offset: Int,
        length: Int,
        block: (ImageReader) -> T,
    ): T? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(data, offset, length)) ?: return null
        input.use {
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            return try {
                reader.setInput(input, true, true)
                block(reader)
            } catch (e: Exception) {
                null
            } finally {
                reader.dispose()
            }
        }
    }

    private fun normalizeMime(mime: String): String =
        when (mime) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "image/x-png" -> "image/png"
            else -> mime
        }

    private fun scaled(
        image: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.composite = AlphaComposite.Src
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    internal fun composite(
        blendMode: Int,
        alpha: Float,
    ): Composite {
        val rule =
            when (blendMode) {
                BlendModes.CLEAR -> AlphaComposite.CLEAR
                BlendModes.SRC -> AlphaComposite.SRC
                BlendModes.DST -> AlphaComposite.DST
                BlendModes.DST_OVER -> AlphaComposite.DST_OVER
                BlendModes.SRC_IN -> AlphaComposite.SRC_IN
                BlendModes.DST_IN -> AlphaComposite.DST_IN
                BlendModes.SRC_OUT -> AlphaComposite.SRC_OUT
                BlendModes.DST_OUT -> AlphaComposite.DST_OUT
                BlendModes.SRC_ATOP -> AlphaComposite.SRC_ATOP
                BlendModes.DST_ATOP -> AlphaComposite.DST_ATOP
                BlendModes.XOR -> AlphaComposite.XOR
                // The separable blend modes have no AWT composite; source-over is the closest.
                else -> AlphaComposite.SRC_OVER
            }
        return AlphaComposite.getInstance(rule, alpha.coerceIn(0f, 1f))
    }
}

/** One bitmap: premultiplied ARGB ints in a TYPE_INT_ARGB_PRE image. */
internal class AwtPixelBuffer(
    override val width: Int,
    override val height: Int,
    override val format: PixelFormat,
) : PixelBuffer {
    override val backend: GraphicsBackend get() = AwtGraphicsBackend

    val image: BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
    val data: IntArray = (image.raster.dataBuffer as DataBufferInt).data

    override fun getPixels(
        dst: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        for (row in 0 until height) {
            val from = (y + row) * this.width + x
            val to = offset + row * stride
            for (col in 0 until width) dst[to + col] = colorOf(data[from + col])
        }
    }

    override fun setPixels(
        src: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = storeColors(src, offset, stride, x, y, width, height)

    /** Unpremultiplied colors in, stored per the format's rules. */
    fun storeColors(
        src: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        for (row in 0 until height) {
            val from = offset + row * stride
            val to = (y + row) * this.width + x
            for (col in 0 until width) data[to + col] = storedOf(src[from + col])
        }
    }

    private fun colorOf(stored: Int): Int =
        when (format) {
            PixelFormat.RGB_565 -> PixelMath.quantize565(stored)
            PixelFormat.ALPHA_8 -> stored and -0x1000000
            else -> PixelMath.unpremultiply(stored)
        }

    private fun storedOf(color: Int): Int =
        when (format) {
            PixelFormat.RGB_565 -> color or -0x1000000
            PixelFormat.ALPHA_8 -> color and -0x1000000
            else -> PixelMath.premultiply(color)
        }

    override fun readRaw(
        dst: ByteArray,
        offset: Int,
    ) {
        var o = offset
        for (p in data) {
            when (format) {
                PixelFormat.ARGB_8888 -> {
                    dst[o++] = (p shr 16).toByte()
                    dst[o++] = (p shr 8).toByte()
                    dst[o++] = p.toByte()
                    dst[o++] = (p ushr 24).toByte()
                }
                PixelFormat.RGB_565 -> {
                    val packed = PixelMath.pack565(p)
                    dst[o++] = packed.toByte()
                    dst[o++] = (packed shr 8).toByte()
                }
                PixelFormat.ALPHA_8 -> dst[o++] = (p ushr 24).toByte()
                PixelFormat.RGBA_F16 -> {
                    for (shift in intArrayOf(16, 8, 0, 24)) {
                        val half = PixelMath.floatToHalf(((p ushr shift) and 0xff) / 255f)
                        dst[o++] = half.toByte()
                        dst[o++] = (half shr 8).toByte()
                    }
                }
            }
        }
    }

    override fun writeRaw(
        src: ByteArray,
        offset: Int,
    ) {
        var o = offset
        for (i in data.indices) {
            data[i] =
                when (format) {
                    PixelFormat.ARGB_8888 -> {
                        val r = src[o].toInt() and 0xff
                        val g = src[o + 1].toInt() and 0xff
                        val b = src[o + 2].toInt() and 0xff
                        val a = src[o + 3].toInt() and 0xff
                        o += 4
                        (a shl 24) or (minOf(r, a) shl 16) or (minOf(g, a) shl 8) or minOf(b, a)
                    }
                    PixelFormat.RGB_565 -> {
                        val packed = (src[o].toInt() and 0xff) or ((src[o + 1].toInt() and 0xff) shl 8)
                        o += 2
                        PixelMath.expand565(packed)
                    }
                    PixelFormat.ALPHA_8 -> (src[o++].toInt() and 0xff) shl 24
                    PixelFormat.RGBA_F16 -> {
                        val c = IntArray(4)
                        for (k in 0 until 4) {
                            c[k] = PixelMath.halfToByte((src[o].toInt() and 0xff) or ((src[o + 1].toInt() and 0xff) shl 8))
                            o += 2
                        }
                        (c[3] shl 24) or (minOf(c[0], c[3]) shl 16) or (minOf(c[1], c[3]) shl 8) or minOf(c[2], c[3])
                    }
                }
        }
    }

    override fun encode(
        format: EncodeFormat,
        quality: Int,
    ): ByteArray? {
        val opaque = this.format == PixelFormat.RGB_565 || data.all { it ushr 24 == 255 }
        val (name, rgbOnly) =
            when (format) {
                EncodeFormat.JPEG -> "jpeg" to true
                EncodeFormat.PNG -> "png" to false
                EncodeFormat.WEBP_LOSSY, EncodeFormat.WEBP_LOSSLESS -> "webp" to false
            }
        val writers = ImageIO.getImageWritersByFormatName(name)
        if (!writers.hasNext()) {
            // No WebP writer: lossless as PNG, lossy as JPEG unless there is alpha to keep.
            return if (format == EncodeFormat.WEBP_LOSSLESS || !opaque) encode(EncodeFormat.PNG, quality) else encode(EncodeFormat.JPEG, quality)
        }
        val writer = writers.next()
        val image = if (rgbOnly || this.format == PixelFormat.RGB_565) copyAs(BufferedImage.TYPE_INT_RGB) else copyAs(BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        val stream = ImageIO.createImageOutputStream(out)
        try {
            writer.output = stream
            val param = writer.defaultWriteParam
            if (format != EncodeFormat.PNG && param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                if (format == EncodeFormat.WEBP_LOSSLESS || format == EncodeFormat.WEBP_LOSSY) {
                    val types = param.compressionTypes
                    val wanted = if (format == EncodeFormat.WEBP_LOSSLESS) "Lossless" else "Lossy"
                    types?.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { param.compressionType = it }
                }
                if (param.compressionType != null || param.compressionTypes == null) {
                    runCatching { param.compressionQuality = quality / 100f }
                }
            }
            writer.write(null, IIOImage(image, null, null), param)
        } finally {
            stream.close()
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** A copy without premultiplication (encoders); JPEG gets the premultiplied colors, i.e. over black. */
    private fun copyAs(type: Int): BufferedImage {
        val out = BufferedImage(width, height, type)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            pixels[i] =
                if (type == BufferedImage.TYPE_INT_RGB) {
                    if (format == PixelFormat.RGB_565) PixelMath.quantize565(data[i]) else data[i] or -0x1000000
                } else {
                    colorOf(data[i])
                }
        }
        out.setRGB(0, 0, width, height, pixels, 0, width)
        return out
    }

    override fun drawBitmap(
        src: PixelBuffer,
        srcRect: IntArray,
        dstRect: FloatArray,
        state: CanvasState,
        paint: PaintSpec,
    ) {
        val source = src as? AwtPixelBuffer ?: adopt(src)
        val sw = srcRect[2] - srcRect[0]
        val sh = srcRect[3] - srcRect[1]
        if (sw <= 0 || sh <= 0) return
        var image = source.image.getSubimage(srcRect[0], srcRect[1], sw, sh)
        var alpha = (paint.color ushr 24) / 255f
        val matrix = paint.colorMatrix
        if (matrix != null || source.format == PixelFormat.ALPHA_8) {
            // Per-pixel work on a copy: the color filter, or an alpha mask tinted with the paint color.
            val filtered = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB_PRE)
            val out = (filtered.raster.dataBuffer as DataBufferInt).data
            val tint = paint.color and 0xffffff
            val paintAlpha = paint.color ushr 24
            for (row in 0 until sh) {
                val from = (srcRect[1] + row) * source.width + srcRect[0]
                for (col in 0 until sw) {
                    val stored = source.data[from + col]
                    var color =
                        if (source.format == PixelFormat.ALPHA_8) {
                            (PixelMath.mul255(stored ushr 24, paintAlpha) shl 24) or tint
                        } else {
                            PixelMath.unpremultiply(stored)
                        }
                    if (matrix != null) color = PixelMath.applyColorMatrix(matrix, color)
                    out[row * sw + col] = PixelMath.premultiply(color)
                }
            }
            if (source.format == PixelFormat.ALPHA_8) alpha = 1f
            image = filtered
        } else if (source === this) {
            image = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB_PRE).also { copy -> image.copyData(copy.raster) }
        }
        val g = graphics(state, paint, alpha)
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (paint.filterBitmap) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            val transform = AffineTransform(g.transform)
            transform.translate(dstRect[0].toDouble(), dstRect[1].toDouble())
            transform.scale((dstRect[2] - dstRect[0]).toDouble() / sw, (dstRect[3] - dstRect[1]).toDouble() / sh)
            g.transform = transform
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        afterDraw()
    }

    /** A buffer of another backend (tests that switch backends), copied through its raw pixels. */
    private fun adopt(other: PixelBuffer): AwtPixelBuffer {
        val copy = AwtPixelBuffer(other.width, other.height, other.format)
        val raw = ByteArray(other.width * other.height * other.format.bytesPerPixel)
        other.readRaw(raw, 0)
        copy.writeRaw(raw, 0)
        return copy
    }

    override fun drawPath(
        verbs: ByteArray,
        verbCount: Int,
        coords: FloatArray,
        evenOdd: Boolean,
        state: CanvasState,
        paint: PaintSpec,
    ) {
        val path = Path2D.Float(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
        var c = 0
        var open = false
        for (i in 0 until verbCount) {
            when (verbs[i]) {
                PathVerbs.MOVE -> {
                    path.moveTo(coords[c], coords[c + 1])
                    open = true
                }
                PathVerbs.LINE -> {
                    if (!open) path.moveTo(0f, 0f)
                    path.lineTo(coords[c], coords[c + 1])
                    open = true
                }
                PathVerbs.QUAD -> {
                    if (!open) path.moveTo(0f, 0f)
                    path.quadTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3])
                    open = true
                }
                PathVerbs.CUBIC -> {
                    if (!open) path.moveTo(0f, 0f)
                    path.curveTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3], coords[c + 4], coords[c + 5])
                    open = true
                }
                PathVerbs.CLOSE -> if (open) path.closePath()
            }
            c += 2 * PathVerbs.pointCount(verbs[i])
        }
        val g = graphics(state, paint, 1f)
        try {
            g.color = paintColor(paint)
            if (paint.style != PaintSpec.STYLE_STROKE) g.fill(path)
            if (paint.style != PaintSpec.STYLE_FILL) {
                g.stroke = stroke(paint)
                g.draw(path)
            }
        } finally {
            g.dispose()
        }
        afterDraw()
    }

    override fun drawColor(
        color: Int,
        blendMode: Int,
        state: CanvasState,
    ) {
        val g = graphics(CanvasState(CanvasState.IDENTITY.matrix, state.clips, state.clipCount), null, 1f)
        try {
            g.composite = AwtGraphicsBackend.composite(blendMode, 1f)
            g.color = Color(color, true)
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        afterDraw()
    }

    override fun drawText(
        text: String,
        x: Float,
        y: Float,
        font: FontSpec,
        state: CanvasState,
        paint: PaintSpec,
    ) {
        if (text.isEmpty()) return
        val g = graphics(state, paint, 1f)
        try {
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                if (paint.antiAlias) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
            )
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.color = paintColor(paint)
            val layout = TextLayout(text, AwtGraphicsBackend.awtFont(font), g.fontRenderContext)
            if (paint.style == PaintSpec.STYLE_FILL) {
                layout.draw(g, x, y)
            } else {
                val outline: Shape = layout.getOutline(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
                if (paint.style == PaintSpec.STYLE_FILL_AND_STROKE) g.fill(outline)
                g.stroke = stroke(paint)
                g.draw(outline)
            }
        } finally {
            g.dispose()
        }
        afterDraw()
    }

    override fun release() {}

    private fun paintColor(paint: PaintSpec): Color {
        val color = paint.colorMatrix?.let { PixelMath.applyColorMatrix(it, paint.color) } ?: paint.color
        return Color(color, true)
    }

    private fun stroke(paint: PaintSpec): BasicStroke {
        val cap =
            when (paint.cap) {
                1 -> BasicStroke.CAP_ROUND
                2 -> BasicStroke.CAP_SQUARE
                else -> BasicStroke.CAP_BUTT
            }
        val join =
            when (paint.join) {
                1 -> BasicStroke.JOIN_ROUND
                2 -> BasicStroke.JOIN_BEVEL
                else -> BasicStroke.JOIN_MITER
            }
        return BasicStroke(paint.strokeWidth, cap, join, maxOf(1f, paint.miter))
    }

    /**
     * A Graphics2D with the canvas clip and transform and the paint's composite and hints;
     * [alpha] scales what is drawn (a bitmap takes the paint's alpha; shapes carry it in their color).
     */
    private fun graphics(
        state: CanvasState,
        paint: PaintSpec?,
        alpha: Float,
    ): Graphics2D {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val clips = state.clips
        for (i in 0 until state.clipCount) {
            val o = i * CanvasState.CLIP_STRIDE
            g.transform = affine(clips, o)
            val rect = Rectangle2D.Float(clips[o + 6], clips[o + 7], clips[o + 8] - clips[o + 6], clips[o + 9] - clips[o + 7])
            if (clips[o + 10] == CanvasState.CLIP_DIFFERENCE) {
                val area = Area(g.transform.createInverse().createTransformedShape(Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat())))
                area.subtract(Area(rect))
                g.clip(area)
            } else {
                g.clip(rect)
            }
        }
        g.transform = affine(state.matrix, 0)
        if (paint != null) {
            g.composite = AwtGraphicsBackend.composite(paint.blendMode, alpha)
            val aa = if (paint.antiAlias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa)
        }
        return g
    }

    private fun affine(
        m: FloatArray,
        o: Int,
    ) = AffineTransform(m[o].toDouble(), m[o + 1].toDouble(), m[o + 2].toDouble(), m[o + 3].toDouble(), m[o + 4].toDouble(), m[o + 5].toDouble())

    /** Keeps the format's invariants after AWT drew into the image. */
    private fun afterDraw() {
        when (format) {
            PixelFormat.RGB_565 -> for (i in data.indices) data[i] = data[i] or -0x1000000
            PixelFormat.ALPHA_8 -> for (i in data.indices) data[i] = data[i] and -0x1000000
            else -> {}
        }
    }
}
