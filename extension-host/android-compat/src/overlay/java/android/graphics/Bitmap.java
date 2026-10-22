/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Portions Copyright (C) 2006 The Android Open Source Project, licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations.
 *
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's Bitmap. Upstream wraps a
 * java.awt BufferedImage, which cannot work on iOS (no AWT natives); this version keeps Android's
 * API and checks (AOSP) and stores its pixels in a oneechan.host.platform.PixelBuffer of the active
 * GraphicsBackend (CoreGraphics on iOS, AWT on desktop). Adds the AOSP createBitmap overloads
 * (colors, Matrix), createScaledBitmap, copy, raw buffer copies, premultiplied ARGB_8888 storage,
 * RGB_565/ALPHA_8 semantics and recycle (frees native pixels; a Cleaner frees unreachable ones).
 */

package android.graphics;

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.DisplayMetrics;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicLong;
import oneechan.host.platform.EncodeFormat;
import oneechan.host.platform.Graphics;
import oneechan.host.platform.PixelBuffer;
import oneechan.host.platform.PixelFormat;

public final class Bitmap {
    public static final int DENSITY_NONE = 0;

    private static final AtomicLong IDS = new AtomicLong();

    private final int width;
    private final int height;
    private final Config config;
    private final long id = IDS.incrementAndGet();
    private PixelBuffer buffer;
    private boolean mutable;
    private boolean hasAlpha;
    private boolean premultiplied = true;
    private int density = DisplayMetrics.DENSITY_DEFAULT;
    private int generationId;
    private boolean hasMipMap;

    /** Serializes access to the pixels (backends are not thread-safe). */
    final Object lock = new Object();

    Bitmap(PixelBuffer buffer, Config config, boolean mutable, boolean hasAlpha) {
        this.buffer = buffer;
        this.width = buffer.getWidth();
        this.height = buffer.getHeight();
        this.config = config;
        this.mutable = mutable;
        this.hasAlpha = hasAlpha && config != Config.RGB_565;
    }

    public enum CompressFormat {
        JPEG(0),
        PNG(1),
        @Deprecated WEBP(2),
        WEBP_LOSSY(3),
        WEBP_LOSSLESS(4);

        CompressFormat(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    public enum Config {
        ALPHA_8(1),
        RGB_565(3),
        @Deprecated ARGB_4444(4),
        ARGB_8888(5),
        RGBA_F16(6),
        HARDWARE(7),
        RGBA_1010102(8);

        final int nativeInt;

        Config(int ni) {
            this.nativeInt = ni;
        }
    }

    /** How a Config is stored (ARGB_4444, HARDWARE and RGBA_1010102 are kept as ARGB_8888). */
    static PixelFormat formatOf(Config config) {
        if (config == null) return PixelFormat.ARGB_8888;
        switch (config) {
            case ALPHA_8:
                return PixelFormat.ALPHA_8;
            case RGB_565:
                return PixelFormat.RGB_565;
            case RGBA_F16:
                return PixelFormat.RGBA_F16;
            default:
                return PixelFormat.ARGB_8888;
        }
    }

    /** The Config Android reports for a requested one (ARGB_4444 is ARGB_8888 since KitKat). */
    static Config storedConfig(Config config) {
        if (config == null || config == Config.ARGB_4444 || config == Config.HARDWARE || config == Config.RGBA_1010102) return Config.ARGB_8888;
        return config;
    }

    // --- Creation.

    public static Bitmap createBitmap(int width, int height, @NonNull Config config) {
        return createBitmap(width, height, config, true);
    }

    public static Bitmap createBitmap(@Nullable DisplayMetrics display, int width, int height, @NonNull Config config) {
        return createBitmap(width, height, config, true);
    }

    public static Bitmap createBitmap(int width, int height, @NonNull Config config, boolean hasAlpha) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be > 0");
        if (config == null) throw new NullPointerException("config");
        if (config == Config.HARDWARE) throw new IllegalArgumentException("can't create mutable bitmap with Config.HARDWARE");
        Config stored = storedConfig(config);
        Bitmap bitmap = new Bitmap(Graphics.backend().create(width, height, formatOf(stored)), stored, true, hasAlpha);
        if (!hasAlpha && stored != Config.ALPHA_8) bitmap.eraseColor(0xff000000);
        return bitmap;
    }

    public static Bitmap createBitmap(int width, int height, @NonNull Config config, boolean hasAlpha, @NonNull ColorSpace colorSpace) {
        return createBitmap(width, height, config, hasAlpha);
    }

    public static Bitmap createBitmap(@Nullable DisplayMetrics display, int width, int height, @NonNull Config config, boolean hasAlpha) {
        return createBitmap(width, height, config, hasAlpha);
    }

    public static Bitmap createBitmap(@Nullable DisplayMetrics display, int width, int height, @NonNull Config config, boolean hasAlpha,
                                      @NonNull ColorSpace colorSpace) {
        return createBitmap(width, height, config, hasAlpha);
    }

    public static Bitmap createBitmap(@NonNull Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight());
    }

    public static Bitmap createBitmap(@NonNull Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }

    public static Bitmap createBitmap(@NonNull Bitmap source, int x, int y, int width, int height, @Nullable Matrix m, boolean filter) {
        checkXYSign(x, y);
        checkWidthHeight(width, height);
        if (x + width > source.getWidth()) throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        if (y + height > source.getHeight()) throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        source.checkRecycled("Can't create a bitmap from a recycled bitmap");

        // AOSP: an immutable source asked for as a whole, untransformed, is returned as is.
        if (!source.isMutable() && x == 0 && y == 0 && width == source.getWidth() && height == source.getHeight() && (m == null || m.isIdentity())) {
            return source;
        }

        Rect srcR = new Rect(x, y, x + width, y + height);
        RectF dstR = new RectF(0, 0, width, height);
        RectF deviceR = new RectF();
        Config newConfig = source.getConfig() == Config.ALPHA_8 ? Config.ALPHA_8 : Config.ARGB_8888;
        if (source.getConfig() == Config.RGB_565 || source.getConfig() == Config.RGBA_F16) newConfig = source.getConfig();
        Paint paint = null;
        boolean transformed = false;
        if (m != null && !m.isIdentity()) {
            transformed = !m.rectStaysRect();
            m.mapRect(deviceR, dstR);
            if (transformed && newConfig == Config.RGB_565) newConfig = Config.ARGB_8888;
            paint = new Paint();
            paint.setFilterBitmap(filter);
            if (transformed) paint.setAntiAlias(true);
        } else {
            deviceR.set(dstR);
        }
        int neww = Math.round(deviceR.width());
        int newh = Math.round(deviceR.height());
        if (neww <= 0 || newh <= 0) throw new IllegalArgumentException("width and height must be > 0");

        Bitmap bitmap = createBitmap(neww, newh, newConfig, transformed || source.hasAlpha());
        bitmap.density = source.density;
        bitmap.premultiplied = source.premultiplied;
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-deviceR.left, -deviceR.top);
        if (m != null && !m.isIdentity()) canvas.concat(m);
        if (paint == null) {
            paint = new Paint(0);
            paint.setXfermodeBlend(oneechan.host.platform.BlendModes.SRC);
        }
        canvas.drawBitmap(source, srcR, dstR, paint);
        canvas.setBitmap(null);
        return bitmap;
    }

    public static Bitmap createBitmap(@NonNull @ColorInt int[] colors, int offset, int stride, int width, int height, @NonNull Config config) {
        checkWidthHeight(width, height);
        if (Math.abs(stride) < width) throw new IllegalArgumentException("abs(stride) must be >= width");
        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0 || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        Bitmap bitmap = createBitmap(width, height, config);
        bitmap.setPixels(colors, offset, stride, 0, 0, width, height);
        bitmap.mutable = false;
        return bitmap;
    }

    public static Bitmap createBitmap(@Nullable DisplayMetrics display, @NonNull @ColorInt int[] colors, int offset, int stride, int width,
                                      int height, @NonNull Config config) {
        return createBitmap(colors, offset, stride, width, height, config);
    }

    public static Bitmap createBitmap(@NonNull @ColorInt int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createBitmap(@Nullable DisplayMetrics display, @NonNull @ColorInt int[] colors, int width, int height,
                                      @NonNull Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createScaledBitmap(@NonNull Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        Matrix m = new Matrix();
        final int width = src.getWidth();
        final int height = src.getHeight();
        if (width != dstWidth || height != dstHeight) {
            m.setScale((float) dstWidth / width, (float) dstHeight / height);
        }
        return createBitmap(src, 0, 0, width, height, m, filter);
    }

    // --- Properties.

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Nullable
    public final Config getConfig() {
        return config;
    }

    public final boolean isMutable() {
        return mutable;
    }

    public void setImmutable() {
        mutable = false;
    }

    public final boolean isRecycled() {
        synchronized (lock) {
            return buffer == null;
        }
    }

    public final boolean isPremultiplied() {
        return premultiplied && config != Config.ALPHA_8 && config != Config.RGB_565;
    }

    /** Kept as a flag: pixels are always stored premultiplied. */
    public final void setPremultiplied(boolean premultiplied) {
        this.premultiplied = premultiplied;
    }

    public final boolean hasAlpha() {
        return hasAlpha;
    }

    public void setHasAlpha(boolean hasAlpha) {
        this.hasAlpha = hasAlpha && config != Config.RGB_565;
    }

    public final boolean hasMipMap() {
        return hasMipMap;
    }

    public final void setHasMipMap(boolean hasMipMap) {
        this.hasMipMap = hasMipMap;
    }

    public int getDensity() {
        return density;
    }

    public void setDensity(int density) {
        this.density = density;
    }

    public int getScaledWidth(Canvas canvas) {
        return scaleFromDensity(width, density, canvas.getDensity());
    }

    public int getScaledHeight(Canvas canvas) {
        return scaleFromDensity(height, density, canvas.getDensity());
    }

    public int getScaledWidth(DisplayMetrics metrics) {
        return scaleFromDensity(width, density, metrics.densityDpi);
    }

    public int getScaledHeight(DisplayMetrics metrics) {
        return scaleFromDensity(height, density, metrics.densityDpi);
    }

    public int getScaledWidth(int targetDensity) {
        return scaleFromDensity(width, density, targetDensity);
    }

    public int getScaledHeight(int targetDensity) {
        return scaleFromDensity(height, density, targetDensity);
    }

    static int scaleFromDensity(int size, int sdensity, int tdensity) {
        if (sdensity == DENSITY_NONE || tdensity == DENSITY_NONE || sdensity == tdensity) return size;
        return ((size * tdensity) + (sdensity >> 1)) / sdensity;
    }

    public final int getRowBytes() {
        return width * formatOf(config).getBytesPerPixel();
    }

    public final int getByteCount() {
        return getRowBytes() * height;
    }

    public final int getAllocationByteCount() {
        return getByteCount();
    }

    public int getGenerationId() {
        return generationId;
    }

    @Nullable
    public final ColorSpace getColorSpace() {
        return config == Config.ALPHA_8 ? null : ColorSpace.get(ColorSpace.Named.SRGB);
    }

    public void setColorSpace(@NonNull ColorSpace colorSpace) {}

    public void prepareToDraw() {}

    public Bitmap asShared() {
        return this;
    }

    @Override
    public String toString() {
        return "Bitmap(" + width + "x" + height + ", " + config + ")";
    }

    // --- Lifecycle.

    public void recycle() {
        PixelBuffer released;
        synchronized (lock) {
            released = buffer;
            buffer = null;
        }
        if (released != null) released.release();
    }

    /** The pixels, for Canvas and the decoders; the caller holds {@link #lock}. */
    PixelBuffer pixels() {
        PixelBuffer b = buffer;
        if (b == null) throw new RuntimeException("Canvas: trying to use a recycled bitmap " + this);
        return b;
    }

    long id() {
        return id;
    }

    void noteChanged() {
        generationId++;
    }

    private void checkRecycled(String message) {
        if (buffer == null) throw new IllegalStateException(message);
    }

    // --- Pixel access.

    @ColorInt
    public int getPixel(int x, int y) {
        synchronized (lock) {
            checkRecycled("Can't call getPixel() on a recycled bitmap");
            checkPixelAccess(x, y);
            int[] out = new int[1];
            buffer.getPixels(out, 0, 1, x, y, 1, 1);
            return out[0];
        }
    }

    @NonNull
    public Color getColor(int x, int y) {
        return Color.valueOf(getPixel(x, y));
    }

    public void getPixels(@ColorInt int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        synchronized (lock) {
            checkRecycled("Can't call getPixels() on a recycled bitmap");
            if (width == 0 || height == 0) return;
            checkPixelsAccess(x, y, width, height, offset, stride, pixels);
            buffer.getPixels(pixels, offset, stride, x, y, width, height);
        }
    }

    public void setPixel(int x, int y, @ColorInt int color) {
        synchronized (lock) {
            checkRecycled("Can't call setPixel() on a recycled bitmap");
            checkPixelAccess(x, y);
            buffer.setPixels(new int[] {color}, 0, 1, x, y, 1, 1);
            noteChanged();
        }
    }

    public void setPixels(@NonNull @ColorInt int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        synchronized (lock) {
            checkRecycled("Can't call setPixels() on a recycled bitmap");
            if (width == 0 || height == 0) return;
            checkPixelsAccess(x, y, width, height, offset, stride, pixels);
            buffer.setPixels(pixels, offset, stride, x, y, width, height);
            noteChanged();
        }
    }

    public void eraseColor(@ColorInt int c) {
        synchronized (lock) {
            checkRecycled("Can't erase a recycled bitmap");
            buffer.drawColor(c, oneechan.host.platform.BlendModes.SRC, oneechan.host.platform.CanvasState.IDENTITY);
            noteChanged();
        }
    }

    public void eraseColor(@ColorLong long color) {
        eraseColor(Color.toArgb(color));
    }

    public void copyPixelsToBuffer(@NonNull Buffer dst) {
        int shift = shiftOf(dst);
        int byteCount = getByteCount();
        long remaining = (long) dst.remaining() << shift;
        if (remaining < byteCount) throw new RuntimeException("Buffer not large enough for pixels");
        byte[] raw = new byte[byteCount];
        synchronized (lock) {
            checkRecycled("Can't copy a recycled bitmap");
            buffer.readRaw(raw, 0);
        }
        ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
        if (dst instanceof ByteBuffer b) {
            b.put(raw);
        } else if (dst instanceof IntBuffer b) {
            b.put(bytes.asIntBuffer());
        } else if (dst instanceof ShortBuffer b) {
            b.put(bytes.asShortBuffer());
        }
    }

    public void copyPixelsFromBuffer(@NonNull Buffer src) {
        int shift = shiftOf(src);
        int byteCount = getByteCount();
        long remaining = (long) src.remaining() << shift;
        if (remaining < byteCount) throw new RuntimeException("Buffer not large enough for pixels");
        byte[] raw = new byte[byteCount];
        ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
        if (src instanceof ByteBuffer b) {
            b.get(raw);
        } else if (src instanceof IntBuffer b) {
            IntBuffer view = bytes.asIntBuffer();
            IntBuffer slice = b.slice();
            slice.limit(byteCount >> 2);
            view.put(slice);
            b.position(b.position() + (byteCount >> 2));
        } else if (src instanceof ShortBuffer b) {
            ShortBuffer view = bytes.asShortBuffer();
            ShortBuffer slice = b.slice();
            slice.limit(byteCount >> 1);
            view.put(slice);
            b.position(b.position() + (byteCount >> 1));
        }
        synchronized (lock) {
            checkRecycled("copyPixelsFromBuffer called on recycled bitmap");
            buffer.writeRaw(raw, 0);
            noteChanged();
        }
    }

    private static int shiftOf(Buffer buffer) {
        if (buffer instanceof ByteBuffer) return 0;
        if (buffer instanceof ShortBuffer) return 1;
        if (buffer instanceof IntBuffer) return 2;
        throw new RuntimeException("unsupported Buffer subclass");
    }

    // --- Conversion.

    public boolean compress(@NonNull CompressFormat format, int quality, @NonNull OutputStream stream) {
        if (stream == null || format == null) throw new NullPointerException();
        if (quality < 0 || quality > 100) throw new IllegalArgumentException("quality must be 0..100");
        byte[] encoded;
        synchronized (lock) {
            checkRecycled("Can't compress a recycled bitmap");
            encoded = buffer.encode(encodeFormatOf(format, quality), quality);
        }
        if (encoded == null) return false;
        try {
            stream.write(encoded);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static EncodeFormat encodeFormatOf(CompressFormat format, int quality) {
        switch (format) {
            case JPEG:
                return EncodeFormat.JPEG;
            case PNG:
                return EncodeFormat.PNG;
            case WEBP_LOSSLESS:
                return EncodeFormat.WEBP_LOSSLESS;
            case WEBP:
                // The deprecated WEBP is lossless at quality 100.
                return quality == 100 ? EncodeFormat.WEBP_LOSSLESS : EncodeFormat.WEBP_LOSSY;
            default:
                return EncodeFormat.WEBP_LOSSY;
        }
    }

    public Bitmap copy(@NonNull Config config, boolean isMutable) {
        checkRecycled("Can't copy a recycled bitmap");
        if (config == Config.HARDWARE && isMutable) throw new IllegalArgumentException("Hardware bitmaps are always immutable");
        Config stored = storedConfig(config);
        Bitmap copy = createBitmap(width, height, stored, hasAlpha || stored == Config.ALPHA_8);
        Canvas canvas = new Canvas(copy);
        Paint paint = new Paint(0);
        paint.setXfermodeBlend(oneechan.host.platform.BlendModes.SRC);
        canvas.drawBitmap(this, 0, 0, paint);
        canvas.setBitmap(null);
        copy.density = density;
        copy.mutable = isMutable;
        return copy;
    }

    public Bitmap extractAlpha() {
        return extractAlpha(null, null);
    }

    public Bitmap extractAlpha(Paint paint, int[] offsetXY) {
        if (offsetXY != null && offsetXY.length >= 2) {
            offsetXY[0] = 0;
            offsetXY[1] = 0;
        }
        return copy(Config.ALPHA_8, true);
    }

    public boolean sameAs(Bitmap other) {
        if (this == other) return true;
        if (other == null || width != other.width || height != other.height || config != other.config) return false;
        int[] a = new int[width * height];
        int[] b = new int[width * height];
        getPixels(a, 0, width, 0, 0, width, height);
        other.getPixels(b, 0, width, 0, 0, width, height);
        return java.util.Arrays.equals(a, b);
    }

    public void reconfigure(int width, int height, Config config) {
        throw new UnsupportedOperationException("Bitmap.reconfigure is not supported");
    }

    // --- Checks (AOSP).

    private static void checkXYSign(int x, int y) {
        if (x < 0) throw new IllegalArgumentException("x must be >= 0");
        if (y < 0) throw new IllegalArgumentException("y must be >= 0");
    }

    private static void checkWidthHeight(int width, int height) {
        if (width <= 0) throw new IllegalArgumentException("width must be > 0");
        if (height <= 0) throw new IllegalArgumentException("height must be > 0");
    }

    private void checkPixelsAccess(int x, int y, int width, int height, int offset, int stride, int[] pixels) {
        checkXYSign(x, y);
        if (width < 0) throw new IllegalArgumentException("width must be >= 0");
        if (height < 0) throw new IllegalArgumentException("height must be >= 0");
        if (x + width > getWidth()) throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        if (y + height > getHeight()) throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        if (Math.abs(stride) < width) throw new IllegalArgumentException("abs(stride) must be >= width");
        int lastScanline = offset + (height - 1) * stride;
        int length = pixels.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0 || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private void checkPixelAccess(int x, int y) {
        checkXYSign(x, y);
        if (x >= getWidth()) throw new IllegalArgumentException("x must be < bitmap.width()");
        if (y >= getHeight()) throw new IllegalArgumentException("y must be < bitmap.height()");
    }
}
