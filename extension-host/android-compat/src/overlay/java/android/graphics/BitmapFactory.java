/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Portions Copyright (C) 2007 The Android Open Source Project, licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations.
 *
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's BitmapFactory. Decoding goes
 * through the active oneechan.host.platform.GraphicsBackend (ImageIO on iOS, javax.imageio on
 * desktop) instead of javax.imageio directly; Options follow AOSP: inJustDecodeBounds,
 * inSampleSize (rounded down to a power of two), inPreferredConfig, inMutable, density scaling,
 * outWidth/outHeight/outMimeType/outConfig. Failures return null like Android; outPadding is
 * filled with -1 (no nine-patch) instead of throwing; decodeFile/decodeFileDescriptor added.
 */

package android.graphics;

import android.annotation.Nullable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import oneechan.host.platform.Graphics;
import oneechan.host.platform.GraphicsBackend;
import oneechan.host.platform.ImageInfo;
import oneechan.host.platform.PixelBuffer;

public class BitmapFactory {
    public static class Options {
        public Options() {
            inScaled = true;
            inPremultiplied = true;
        }

        public Bitmap inBitmap;
        public boolean inMutable;
        public boolean inJustDecodeBounds;
        public int inSampleSize;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public ColorSpace inPreferredColorSpace = null;
        public boolean inPremultiplied;
        @Deprecated public boolean inDither;
        public int inDensity;
        public int inTargetDensity;
        public int inScreenDensity;
        public boolean inScaled;
        @Deprecated public boolean inPurgeable;
        @Deprecated public boolean inInputShareable;
        @Deprecated public boolean inPreferQualityOverSpeed;
        public int outWidth;
        public int outHeight;
        public String outMimeType;
        public Bitmap.Config outConfig;
        public ColorSpace outColorSpace;
        public byte[] inTempStorage;
        @Deprecated public boolean mCancel;

        @Deprecated
        public void requestCancelDecode() {
            mCancel = true;
        }

        static void validate(Options opts) {
            if (opts == null) return;
            if (opts.inBitmap != null && opts.inBitmap.isRecycled()) {
                throw new IllegalArgumentException("Cannot reuse a recycled Bitmap");
            }
            if (opts.inMutable && opts.inPreferredConfig == Bitmap.Config.HARDWARE) {
                throw new IllegalArgumentException("Bitmaps with Config.HARDWARE cannot be decoded into - they are immutable");
            }
        }
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        Options.validate(opts);
        if (pathName == null) return null;
        try (InputStream stream = new FileInputStream(pathName)) {
            return decodeStream(stream, null, opts);
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        if ((offset | length) < 0 || data.length < offset + length) throw new ArrayIndexOutOfBoundsException();
        Options.validate(opts);
        return decode(data, offset, length, opts);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    @Nullable
    public static Bitmap decodeStream(@Nullable InputStream is, @Nullable Rect outPadding, @Nullable Options opts) {
        if (is == null) return null;
        Options.validate(opts);
        if (outPadding != null) outPadding.set(-1, -1, -1, -1);
        byte[] data;
        try {
            data = is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        return decode(data, 0, data.length, opts);
    }

    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    public static Bitmap decodeFileDescriptor(FileDescriptor fd, Rect outPadding, Options opts) {
        if (fd == null) return null;
        try {
            return decodeStream(new FileInputStream(fd), outPadding, opts);
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap decodeFileDescriptor(FileDescriptor fd) {
        return decodeFileDescriptor(fd, null, null);
    }

    /** The decode itself: header, sampling and config per Options, then pixels from the backend. */
    static Bitmap decode(byte[] data, int offset, int length, Options opts) {
        GraphicsBackend backend = Graphics.backend();
        ImageInfo info = backend.probe(data, offset, length);
        if (opts != null) {
            opts.outWidth = -1;
            opts.outHeight = -1;
            opts.outMimeType = null;
            opts.outConfig = null;
            opts.outColorSpace = null;
        }
        if (info == null) return null;
        int sample = sampleSize(opts == null ? 1 : opts.inSampleSize);
        int width = sampledDimension(info.width, sample, info.mimeType);
        int height = sampledDimension(info.height, sample, info.mimeType);
        Bitmap.Config config = chooseConfig(opts == null ? null : opts.inPreferredConfig, info.hasAlpha);
        if (opts != null) {
            // Like AOSP: the sampled size, before any density scaling, also for inJustDecodeBounds.
            opts.outWidth = width;
            opts.outHeight = height;
            opts.outMimeType = info.mimeType;
            opts.outConfig = config;
            opts.outColorSpace = config == Bitmap.Config.ALPHA_8 ? null : ColorSpace.get(ColorSpace.Named.SRGB);
            if (opts.inJustDecodeBounds) return null;
        }
        float scale = densityScale(opts);
        int outWidth = width, outHeight = height;
        if (scale != 1.0f) {
            outWidth = Math.max(1, (int) (width * scale + 0.5f));
            outHeight = Math.max(1, (int) (height * scale + 0.5f));
        }
        PixelBuffer pixels = backend.decode(data, offset, length, outWidth, outHeight, sample, Bitmap.formatOf(config), false);
        if (pixels == null) return null;
        boolean mutable = opts != null && opts.inMutable;
        Bitmap bitmap = new Bitmap(pixels, config, mutable, info.hasAlpha);
        if (opts != null) {
            if (scale != 1.0f) bitmap.setDensity(opts.inTargetDensity);
            else if (opts.inDensity != 0) bitmap.setDensity(opts.inDensity);
        }
        return bitmap;
    }

    /** inSampleSize below 1 is 1; others round down to a power of two. */
    static int sampleSize(int requested) {
        if (requested <= 1) return 1;
        return Integer.highestOneBit(requested);
    }

    /** JPEG scales in the DCT (rounding up); other formats keep every n-th pixel (rounding down). */
    static int sampledDimension(int size, int sample, String mimeType) {
        if (sample <= 1) return size;
        if ("image/jpeg".equals(mimeType)) return Math.max(1, (size + sample - 1) / sample);
        return Math.max(1, size / sample);
    }

    static Bitmap.Config chooseConfig(Bitmap.Config preferred, boolean hasAlpha) {
        if (preferred == Bitmap.Config.RGB_565 && !hasAlpha) return Bitmap.Config.RGB_565;
        if (preferred == Bitmap.Config.ALPHA_8) return Bitmap.Config.ALPHA_8;
        if (preferred == Bitmap.Config.RGBA_F16) return Bitmap.Config.RGBA_F16;
        return Bitmap.Config.ARGB_8888;
    }

    private static float densityScale(Options opts) {
        if (opts == null || !opts.inScaled) return 1.0f;
        int density = opts.inDensity;
        int target = opts.inTargetDensity;
        if (density == 0 || target == 0 || density == opts.inScreenDensity || density == target) return 1.0f;
        return (float) target / density;
    }
}
