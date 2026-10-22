/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"). A reduced
 * AOSP ImageDecoder (API 28) on the active oneechan.host.platform.GraphicsBackend: byte array,
 * ByteBuffer and File sources; header listener; target size, sample size, crop, mutable and
 * post-processor options. Bitmaps are always software ARGB_8888 (the allocator is recorded but a
 * HARDWARE bitmap is never produced); EXIF orientation is applied where the backend can (native).
 * Drawables, animated images, resource/asset/content sources are not supported.
 */

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Size;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Locale;
import oneechan.host.platform.Graphics;
import oneechan.host.platform.GraphicsBackend;
import oneechan.host.platform.NativeGraphicsBackend;
import oneechan.host.platform.PixelBuffer;
import oneechan.host.platform.PixelFormat;

public final class ImageDecoder implements AutoCloseable {
    public static final int ALLOCATOR_DEFAULT = 0;
    public static final int ALLOCATOR_SOFTWARE = 1;
    public static final int ALLOCATOR_SHARED_MEMORY = 2;
    public static final int ALLOCATOR_HARDWARE = 3;

    public static final int MEMORY_POLICY_LOW_RAM = 0;
    public static final int MEMORY_POLICY_DEFAULT = 1;

    /** Where encoded image data comes from. */
    public abstract static class Source {
        Source() {}

        abstract byte[] bytes() throws IOException;
    }

    private static final class ByteArraySource extends Source {
        private final byte[] data;
        private final int offset;
        private final int length;

        ByteArraySource(byte[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        @Override
        byte[] bytes() {
            if (offset == 0 && length == data.length) return data;
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            return copy;
        }
    }

    private static final class ByteBufferSource extends Source {
        private final ByteBuffer buffer;

        ByteBufferSource(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        byte[] bytes() {
            ByteBuffer view = buffer.duplicate();
            byte[] copy = new byte[view.remaining()];
            view.get(copy);
            return copy;
        }
    }

    private static final class FileSource extends Source {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        byte[] bytes() throws IOException {
            return Files.readAllBytes(file.toPath());
        }
    }

    public static class ImageInfo {
        private final ImageDecoder mDecoder;

        private ImageInfo(ImageDecoder decoder) {
            mDecoder = decoder;
        }

        @NonNull
        public Size getSize() {
            return new Size(mDecoder.mWidth, mDecoder.mHeight);
        }

        @NonNull
        public String getMimeType() {
            return mDecoder.mMimeType == null ? "" : mDecoder.mMimeType;
        }

        public boolean isAnimated() {
            return false;
        }

        @Nullable
        public ColorSpace getColorSpace() {
            return ColorSpace.get(ColorSpace.Named.SRGB);
        }
    }

    public static final class DecodeException extends IOException {
        public static final int SOURCE_EXCEPTION = 1;
        public static final int SOURCE_INCOMPLETE = 2;
        public static final int SOURCE_MALFORMED_DATA = 3;

        private final int mError;
        private final Source mSource;

        public DecodeException(int error, @Nullable Throwable cause, @NonNull Source source) {
            super(errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        public DecodeException(int error, @Nullable String msg, @Nullable Throwable cause, @NonNull Source source) {
            super(msg + errorMessage(error, cause), cause);
            mError = error;
            mSource = source;
        }

        public int getError() {
            return mError;
        }

        @NonNull
        public Source getSource() {
            return mSource;
        }

        private static String errorMessage(int error, @Nullable Throwable cause) {
            switch (error) {
                case SOURCE_EXCEPTION:
                    return "Exception in input: " + cause;
                case SOURCE_INCOMPLETE:
                    return "Input was incomplete.";
                case SOURCE_MALFORMED_DATA:
                    return "Input contained an error.";
                default:
                    return "";
            }
        }
    }

    public interface OnHeaderDecodedListener {
        void onHeaderDecoded(@NonNull ImageDecoder decoder, @NonNull ImageInfo info, @NonNull Source source);
    }

    public interface OnPartialImageListener {
        boolean onPartialImage(@NonNull DecodeException exception);
    }

    private final Source mSource;
    private final byte[] mData;
    private final int mWidth;
    private final int mHeight;
    private final String mMimeType;
    private final boolean mHasAlpha;
    private int mDesiredWidth;
    private int mDesiredHeight;
    private int mAllocator = ALLOCATOR_DEFAULT;
    private boolean mUnpremultipliedRequired;
    private boolean mMutable;
    private boolean mDecodeAsAlphaMask;
    private int mMemoryPolicy = MEMORY_POLICY_DEFAULT;
    private Rect mCropRect;
    private PostProcessor mPostProcessor;
    private OnPartialImageListener mOnPartialImageListener;
    private ColorSpace mDesiredColorSpace;
    private boolean mClosed;

    private ImageDecoder(Source source, byte[] data, int width, int height, String mimeType, boolean hasAlpha) {
        mSource = source;
        mData = data;
        mWidth = width;
        mHeight = height;
        mMimeType = mimeType;
        mHasAlpha = hasAlpha;
        mDesiredWidth = width;
        mDesiredHeight = height;
    }

    @NonNull
    public static Source createSource(@NonNull byte[] data, int offset, int length) throws ArrayIndexOutOfBoundsException {
        if (data == null) throw new NullPointerException("null byte[] in createSource!");
        if (offset < 0 || length < 0 || offset >= data.length || offset + length > data.length) {
            throw new ArrayIndexOutOfBoundsException("invalid offset/length!");
        }
        return new ByteArraySource(data, offset, length);
    }

    @NonNull
    public static Source createSource(@NonNull byte[] data) {
        return createSource(data, 0, data.length);
    }

    @NonNull
    public static Source createSource(@NonNull ByteBuffer buffer) {
        return new ByteBufferSource(buffer);
    }

    @NonNull
    public static Source createSource(@NonNull File file) {
        return new FileSource(file);
    }

    public static boolean isMimeTypeSupported(@NonNull String mimeType) {
        if (mimeType == null) throw new NullPointerException("mimeType must not be null");
        switch (mimeType.toLowerCase(Locale.US)) {
            case "image/png":
            case "image/jpeg":
            case "image/webp":
            case "image/gif":
            case "image/heif":
            case "image/heic":
            case "image/avif":
            case "image/bmp":
            case "image/x-ico":
            case "image/vnd.wap.wbmp":
                return true;
            default:
                return false;
        }
    }

    public void setTargetSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Dimensions must be positive! provided (" + width + ", " + height + ")");
        mDesiredWidth = width;
        mDesiredHeight = height;
    }

    public void setTargetSampleSize(int sampleSize) {
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be positive! provided " + sampleSize);
        int sample = BitmapFactory.sampleSize(sampleSize);
        setTargetSize(BitmapFactory.sampledDimension(mWidth, sample, mMimeType), BitmapFactory.sampledDimension(mHeight, sample, mMimeType));
    }

    public void setTargetColorSpace(ColorSpace colorSpace) {
        mDesiredColorSpace = colorSpace;
    }

    public void setAllocator(int allocator) {
        if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_HARDWARE) throw new IllegalArgumentException("invalid allocator " + allocator);
        mAllocator = allocator;
    }

    public int getAllocator() {
        return mAllocator;
    }

    public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {
        mUnpremultipliedRequired = unpremultipliedRequired;
    }

    public boolean isUnpremultipliedRequired() {
        return mUnpremultipliedRequired;
    }

    public void setPostProcessor(@Nullable PostProcessor postProcessor) {
        mPostProcessor = postProcessor;
    }

    @Nullable
    public PostProcessor getPostProcessor() {
        return mPostProcessor;
    }

    public void setOnPartialImageListener(@Nullable OnPartialImageListener listener) {
        mOnPartialImageListener = listener;
    }

    @Nullable
    public OnPartialImageListener getOnPartialImageListener() {
        return mOnPartialImageListener;
    }

    public void setCrop(@Nullable Rect subset) {
        mCropRect = subset;
    }

    @Nullable
    public Rect getCrop() {
        return mCropRect;
    }

    public void setMutableRequired(boolean mutable) {
        mMutable = mutable;
    }

    public boolean isMutableRequired() {
        return mMutable;
    }

    public void setMemorySizePolicy(int policy) {
        mMemoryPolicy = policy;
    }

    public int getMemorySizePolicy() {
        return mMemoryPolicy;
    }

    public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
        mDecodeAsAlphaMask = enabled;
    }

    public boolean isDecodeAsAlphaMaskEnabled() {
        return mDecodeAsAlphaMask;
    }

    @Override
    public void close() {
        mClosed = true;
    }

    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src, @NonNull OnHeaderDecodedListener listener) throws IOException {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null! Use decodeBitmap(Source) to not have a listener");
        return decodeBitmapImpl(src, listener);
    }

    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmapImpl(src, null);
    }

    private static Bitmap decodeBitmapImpl(Source src, OnHeaderDecodedListener listener) throws IOException {
        byte[] data;
        try {
            data = src.bytes();
        } catch (IOException e) {
            throw new DecodeException(DecodeException.SOURCE_EXCEPTION, e, src);
        }
        GraphicsBackend backend = Graphics.backend();
        oneechan.host.platform.ImageInfo info = backend.probe(data, 0, data.length);
        if (info == null) throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to create image decoder with message 'unimplemented'", null, src);
        int width = info.width, height = info.height;
        if (backend == NativeGraphicsBackend.INSTANCE && NativeGraphicsBackend.INSTANCE.orientation(data, 0, data.length) >= 5) {
            width = info.height;
            height = info.width;
        }
        try (ImageDecoder decoder = new ImageDecoder(src, data, width, height, info.mimeType, info.hasAlpha)) {
            if (listener != null) listener.onHeaderDecoded(decoder, new ImageInfo(decoder), src);
            return decoder.decode(backend);
        }
    }

    private Bitmap decode(GraphicsBackend backend) throws IOException {
        if (mCropRect != null && (mCropRect.left < 0 || mCropRect.top < 0 || mCropRect.right > mDesiredWidth || mCropRect.bottom > mDesiredHeight
                || mCropRect.isEmpty())) {
            throw new IllegalStateException("Subset " + mCropRect + " not contained by scaled image bounds: (" + mDesiredWidth + " x " + mDesiredHeight + ")");
        }
        int sample = 1;
        while (sample * 2 <= mWidth / Math.max(1, mDesiredWidth) && sample * 2 <= mHeight / Math.max(1, mDesiredHeight)) sample *= 2;
        PixelFormat format = mDecodeAsAlphaMask ? PixelFormat.ALPHA_8 : PixelFormat.ARGB_8888;
        Bitmap.Config config = mDecodeAsAlphaMask ? Bitmap.Config.ALPHA_8 : Bitmap.Config.ARGB_8888;
        PixelBuffer pixels = backend.decode(mData, 0, mData.length, mDesiredWidth, mDesiredHeight, sample, format, true);
        if (pixels == null) throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to decode image", null, mSource);
        Bitmap bitmap = new Bitmap(pixels, config, true, mHasAlpha);
        if (mCropRect != null) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, mCropRect.left, mCropRect.top, mCropRect.width(), mCropRect.height());
            if (cropped != bitmap) bitmap.recycle();
            bitmap = cropped;
        }
        if (mPostProcessor != null) {
            int result = mPostProcessor.onPostProcess(new Canvas(bitmap));
            if (result == -1 /* PixelFormat.OPAQUE */) bitmap.setHasAlpha(false);
            else if (result == -3 /* PixelFormat.TRANSLUCENT */) bitmap.setHasAlpha(true);
            else if (result != 0 /* PixelFormat.UNKNOWN */) throw new IllegalArgumentException("PostProcessor returned invalid value " + result);
        }
        if (!mMutable) bitmap.setImmutable();
        return bitmap;
    }
}
