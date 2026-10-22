package oneechan.host.platform;

/**
 * JNI entry points of the app's CoreGraphics/ImageIO/CoreText backend. The app's native shim
 * (OneechanJVM, oneechan_graphics.c) registers them with RegisterNatives before the host starts; in
 * any other JVM they are unbound and {@link NativeGraphicsBackend} reports itself unavailable.
 *
 * Handles are native bitmap pointers owned by {@link NativeGraphicsBackend}. Formats are
 * {@link PixelFormat} and {@link EncodeFormat} ordinals; the array layouts are those of
 * {@link PaintSpec}, {@link FontSpec} and {@link CanvasState}.
 */
final class NativeGraphics {
    private NativeGraphics() {}

    /** The native API version (also the availability probe). */
    static native int version();

    /** A zeroed bitmap, or 0 when it cannot be allocated. */
    static native long create(int width, int height, int format);

    static native void free(long handle);

    /** Header: fills info with width, height, hasAlpha (0/1), EXIF orientation (1..8); returns the MIME type, or null. */
    static native String probe(byte[] data, int offset, int length, int[] info);

    /** Decodes the first frame scaled to width x height, or returns 0. */
    static native long decode(byte[] data, int offset, int length, int width, int height, int sampleSize, int format, boolean orient);

    static native void getPixels(long handle, int[] dst, int offset, int stride, int x, int y, int width, int height);

    static native void setPixels(long handle, int[] src, int offset, int stride, int x, int y, int width, int height);

    static native void readRaw(long handle, byte[] dst, int offset);

    static native void writeRaw(long handle, byte[] src, int offset);

    /** The encoded image, or null. */
    static native byte[] encode(long handle, int format, int quality);

    static native void drawBitmap(long dst, long src, int[] srcRect, float[] dstRect, float[] matrix, float[] clips, int clipCount,
                                  int[] paintInts, float[] paintFloats, float[] colorMatrix);

    static native void drawPath(long dst, byte[] verbs, int verbCount, float[] coords, boolean evenOdd, float[] matrix, float[] clips,
                                int clipCount, int[] paintInts, float[] paintFloats);

    static native void drawColor(long dst, int color, int blendMode, float[] clips, int clipCount);

    static native void drawText(long dst, String text, float x, float y, String family, int weight, boolean italic, String file,
                                float[] font, float[] matrix, float[] clips, int clipCount, int[] paintInts, float[] paintFloats);

    static native float measureText(String text, String family, int weight, boolean italic, String file, float[] font);

    /** top, ascent, descent, bottom, leading into out. */
    static native void fontMetrics(String family, int weight, boolean italic, String file, float[] font, float[] out);

    /** left, top, right, bottom into out. */
    static native void textBounds(String text, String family, int weight, boolean italic, String file, float[] font, float[] out);
}
