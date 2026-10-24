/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.view.Surface (names and constant values
 * from the API signature), implemented as a handle with no pixels: ImageReader and VirtualDisplay
 * hand one out (an offscreen WebView on a virtual display renders "into" one); it is valid until
 * released. Drawing on it (lockCanvas) and parcelling still throw "Stub!" as in the generated stub.
 */

package android.view;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Parcel;
import android.os.Parcelable;

/** A buffer a producer draws into and a consumer reads; here only a handle that can be released. */
public class Surface implements Parcelable {
    public static final int ROTATION_0 = 0;
    public static final int ROTATION_90 = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;
    public static final int FRAME_RATE_COMPATIBILITY_DEFAULT = 0;
    public static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;

    /** Not parcelable here (there is no binder to pass surfaces through). */
    public static final Parcelable.Creator<Surface> CREATOR = null;

    private final String name;
    private volatile boolean released;

    /** @hide Oneechan: a surface of a producer in this process (ImageReader, VirtualDisplay). */
    public Surface(String name) {
        this.name = name;
    }

    public Surface(SurfaceControl from) {
        throw new RuntimeException("Stub!");
    }

    public Surface(SurfaceTexture surfaceTexture) {
        throw new RuntimeException("Stub!");
    }

    /** Whether the surface can still be used (it was not released). */
    public boolean isValid() {
        return !released;
    }

    /** Releases the surface; it is invalid afterwards. Releasing twice is harmless. */
    public void release() {
        released = true;
    }

    /** Frame rates are a hint for the compositor; there is none here. */
    public void setFrameRate(float frameRate, int compatibility) {
        if (Float.isNaN(frameRate) || frameRate < 0f) throw new IllegalArgumentException("Invalid frame rate " + frameRate);
    }

    public Canvas lockCanvas(Rect inOutDirty) throws IllegalArgumentException, OutOfResourcesException {
        throw new RuntimeException("Stub!");
    }

    public Canvas lockHardwareCanvas() {
        throw new RuntimeException("Stub!");
    }

    public void unlockCanvasAndPost(Canvas canvas) {
        throw new RuntimeException("Stub!");
    }

    @Deprecated
    public void unlockCanvas(Canvas canvas) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel source) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public String toString() {
        return "Surface(name=" + name + (released ? ", released" : "") + ")";
    }

    /** No buffer could be allocated. */
    public static class OutOfResourcesException extends RuntimeException {
        public OutOfResourcesException() {
        }

        public OutOfResourcesException(String name) {
            super(name);
        }
    }
}
