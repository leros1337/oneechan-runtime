/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.media.ImageReader (from the API
 * signature), implemented as a consumer that never receives a frame: nothing renders into its
 * Surface here (extensions give it to a virtual display only so an offscreen WebView keeps drawing
 * on Android), so the listener is never called and acquire* return null.
 */

package android.media;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/** Reads images rendered into its {@link Surface}; no images arrive here. */
public class ImageReader implements AutoCloseable {
    private final int width;
    private final int height;
    private final int format;
    private final int maxImages;
    private final long usage;
    private final Surface surface;
    private OnImageAvailableListener listener;
    private Handler listenerHandler;
    private volatile boolean closed;

    private ImageReader(int width, int height, int format, int maxImages, long usage) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("The image dimensions must be positive");
        if (maxImages < 1) throw new IllegalArgumentException("Maximum outstanding image count must be at least 1");
        this.width = width;
        this.height = height;
        this.format = format;
        this.maxImages = maxImages;
        this.usage = usage;
        surface = new Surface("ImageReader " + width + "x" + height);
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader(width, height, format, maxImages, 0L);
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages, long usage) {
        return new ImageReader(width, height, format, maxImages, usage);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getImageFormat() {
        return format;
    }

    public int getMaxImages() {
        return maxImages;
    }

    /** Where producers render (the same surface every time; released by {@link #close()}). */
    public Surface getSurface() {
        return surface;
    }

    /** Null: no image is ever available. */
    public Image acquireLatestImage() {
        checkOpen();
        return null;
    }

    /** Null: no image is ever available. */
    public Image acquireNextImage() {
        checkOpen();
        return null;
    }

    /**
     * Sets the listener for new images (never called here: no images arrive), on {@code handler}'s
     * thread or, without one, the calling thread's Looper.
     */
    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        synchronized (this) {
            if (listener != null && handler == null && Looper.myLooper() == null) {
                throw new IllegalArgumentException("handler is null but the current thread is not a looper");
            }
            this.listener = listener;
            listenerHandler = listener == null ? null : handler;
        }
    }

    public void discardFreeBuffers() {
        checkOpen();
    }

    /** Releases the surface; the reader cannot be used afterwards. Closing twice is harmless. */
    @Override
    public void close() {
        synchronized (this) {
            listener = null;
            listenerHandler = null;
        }
        closed = true;
        surface.release();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("ImageReader is closed");
    }

    /** Called when a new image is available. */
    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }
}
