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
 * Modified for Oneechan: the public API of AOSP's android.hardware.display.VirtualDisplay (from the
 * API signature), implemented over DisplayManager's process-wide display list: a display and the
 * surface it would render into, until released.
 */

package android.hardware.display;

import android.view.Display;
import android.view.Surface;

/** A virtual display created by {@link DisplayManager#createVirtualDisplay}. */
public final class VirtualDisplay {
    private final Display display;
    private volatile Surface surface;

    VirtualDisplay(Display display, Surface surface) {
        this.display = display;
        this.surface = surface;
    }

    public Display getDisplay() {
        return display;
    }

    public Surface getSurface() {
        return surface;
    }

    /** Where the display renders; null pauses it. */
    public void setSurface(Surface surface) {
        this.surface = surface;
    }

    public void resize(int width, int height, int densityDpi) {
        if (width <= 0 || height <= 0 || densityDpi <= 0) throw new IllegalArgumentException("Size and density must be greater than 0");
        display.setSize(width, height, densityDpi);
    }

    /** Removes the display; it is invalid afterwards. Releasing twice is harmless. The surface stays its owner's. */
    public void release() {
        display.invalidate();
        DisplayManager.remove(display);
    }

    @Override
    public String toString() {
        return "VirtualDisplay{display=" + display + ", surface=" + surface + "}";
    }

    /** Hears when the display pauses, resumes or stops (never here). */
    public abstract static class Callback {
        public Callback() {
        }

        public void onPaused() {
        }

        public void onResumed() {
        }

        public void onStopped() {
        }
    }
}
