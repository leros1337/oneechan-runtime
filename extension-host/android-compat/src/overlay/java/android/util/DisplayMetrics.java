/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.util.DisplayMetrics (names and constant
 * values from the API signature), implemented so extensions can read a display (the generated
 * stub's constructor throws "Stub!"). The device is an iPhone-like 3x screen: see
 * android.content.res.Resources#getSystem().
 */

package android.util;

/** A display's size, density and font scaling. */
public class DisplayMetrics {
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_140 = 140;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_180 = 180;
    public static final int DENSITY_200 = 200;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_220 = 220;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_260 = 260;
    public static final int DENSITY_280 = 280;
    public static final int DENSITY_300 = 300;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_340 = 340;
    public static final int DENSITY_360 = 360;
    public static final int DENSITY_400 = 400;
    public static final int DENSITY_420 = 420;
    public static final int DENSITY_440 = 440;
    public static final int DENSITY_450 = 450;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_560 = 560;
    public static final int DENSITY_600 = 600;
    public static final int DENSITY_XXXHIGH = 640;
    public static final int DENSITY_DEFAULT = DENSITY_MEDIUM;
    /** This device's density bucket (not a compile-time constant, as on Android). */
    public static final int DENSITY_DEVICE_STABLE = deviceDensity();

    public int widthPixels;
    public int heightPixels;
    public float density;
    public int densityDpi;
    public float scaledDensity;
    public float xdpi;
    public float ydpi;

    /** All zero, like Android's; {@link #setToDefaults()} fills the device's density. */
    public DisplayMetrics() {
    }

    private static int deviceDensity() {
        return DENSITY_XXHIGH;
    }

    public void setTo(DisplayMetrics o) {
        if (this == o) return;
        widthPixels = o.widthPixels;
        heightPixels = o.heightPixels;
        density = o.density;
        densityDpi = o.densityDpi;
        scaledDensity = o.scaledDensity;
        xdpi = o.xdpi;
        ydpi = o.ydpi;
    }

    /** The device's density with no size. */
    public void setToDefaults() {
        widthPixels = 0;
        heightPixels = 0;
        densityDpi = DENSITY_DEVICE_STABLE;
        density = densityDpi / (float) DENSITY_DEFAULT;
        scaledDensity = density;
        xdpi = densityDpi;
        ydpi = densityDpi;
    }

    public boolean equals(DisplayMetrics other) {
        return other != null
                && widthPixels == other.widthPixels
                && heightPixels == other.heightPixels
                && density == other.density
                && densityDpi == other.densityDpi
                && scaledDensity == other.scaledDensity
                && xdpi == other.xdpi
                && ydpi == other.ydpi;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DisplayMetrics && equals((DisplayMetrics) o);
    }

    @Override
    public int hashCode() {
        return widthPixels * heightPixels * densityDpi;
    }

    @Override
    public String toString() {
        return "DisplayMetrics{density=" + density + ", width=" + widthPixels + ", height=" + heightPixels
                + ", scaledDensity=" + scaledDensity + ", xdpi=" + xdpi + ", ydpi=" + ydpi + "}";
    }
}
