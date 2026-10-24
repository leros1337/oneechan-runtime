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
 * Modified for Oneechan: the public API of AOSP's android.view.Display (names and constant values
 * from the API signature), implemented as plain values: the default display is the device display
 * of Resources.getSystem() (an iPhone-like 3x screen) and virtual displays are the sizes
 * DisplayManager#createVirtualDisplay was given. Nothing is ever shown on them. The constructor and
 * setters are hidden Oneechan internals for android.hardware.display.
 */

package android.view;

import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

/** A logical display: its size, density and state. */
public final class Display {
    public static final int DEFAULT_DISPLAY = 0;
    public static final int INVALID_DISPLAY = -1;

    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 << 0;
    public static final int FLAG_SECURE = 1 << 1;
    public static final int FLAG_PRIVATE = 1 << 2;
    public static final int FLAG_PRESENTATION = 1 << 3;
    public static final int FLAG_ROUND = 1 << 4;

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_OFF = 1;
    public static final int STATE_ON = 2;
    public static final int STATE_DOZE = 3;
    public static final int STATE_DOZE_SUSPEND = 4;
    public static final int STATE_VR = 5;
    public static final int STATE_ON_SUSPEND = 6;

    private static final float REFRESH_RATE = 60f;

    private final int displayId;
    private final String name;
    private final int flags;
    private volatile int width;
    private volatile int height;
    private volatile int densityDpi;
    private volatile boolean valid = true;

    /** @hide Oneechan: displays are made by android.hardware.display.DisplayManager. */
    public Display(int displayId, String name, int flags, int width, int height, int densityDpi) {
        this.displayId = displayId;
        this.name = name;
        this.flags = flags;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    /** @hide Oneechan: the device display, the size of Resources.getSystem()'s metrics. */
    public static Display device() {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        return new Display(DEFAULT_DISPLAY, "Built-in Screen", 0, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    /** @hide Oneechan: VirtualDisplay#resize. */
    public void setSize(int width, int height, int densityDpi) {
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    /** @hide Oneechan: VirtualDisplay#release. */
    public void invalidate() {
        valid = false;
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getName() {
        return name;
    }

    public int getFlags() {
        return flags;
    }

    public boolean isValid() {
        return valid;
    }

    public int getState() {
        return valid ? STATE_ON : STATE_OFF;
    }

    /** The display's metrics: the device's for the default display, else its size and density. */
    @Deprecated
    public void getMetrics(DisplayMetrics outMetrics) {
        if (displayId == DEFAULT_DISPLAY) {
            outMetrics.setTo(Resources.getSystem().getDisplayMetrics());
            return;
        }
        int dpi = densityDpi;
        outMetrics.widthPixels = width;
        outMetrics.heightPixels = height;
        outMetrics.densityDpi = dpi;
        outMetrics.density = dpi / (float) DisplayMetrics.DENSITY_DEFAULT;
        outMetrics.scaledDensity = outMetrics.density;
        outMetrics.xdpi = dpi;
        outMetrics.ydpi = dpi;
    }

    /** No system decorations take space here: the same as {@link #getMetrics}. */
    public void getRealMetrics(DisplayMetrics outMetrics) {
        getMetrics(outMetrics);
    }

    @Deprecated
    public void getSize(Point outSize) {
        outSize.x = width;
        outSize.y = height;
    }

    public void getRealSize(Point outSize) {
        getSize(outSize);
    }

    @Deprecated
    public void getRectSize(Rect outSize) {
        outSize.set(0, 0, width, height);
    }

    public void getCurrentSizeRange(Point outSmallestSize, Point outLargestSize) {
        int smallest = Math.min(width, height);
        int largest = Math.max(width, height);
        outSmallestSize.x = smallest;
        outSmallestSize.y = smallest;
        outLargestSize.x = largest;
        outLargestSize.y = largest;
    }

    @Deprecated
    public int getWidth() {
        return width;
    }

    @Deprecated
    public int getHeight() {
        return height;
    }

    /** Displays here are never rotated. */
    public int getRotation() {
        return Surface.ROTATION_0;
    }

    @Deprecated
    public int getOrientation() {
        return getRotation();
    }

    @Deprecated
    public int getPixelFormat() {
        return PixelFormat.RGBA_8888;
    }

    public float getRefreshRate() {
        return REFRESH_RATE;
    }

    @Deprecated
    public float[] getSupportedRefreshRates() {
        return new float[] {REFRESH_RATE};
    }

    public Mode getMode() {
        return new Mode(1, width, height, REFRESH_RATE);
    }

    public Mode[] getSupportedModes() {
        return new Mode[] {getMode()};
    }

    public long getAppVsyncOffsetNanos() {
        return 0L;
    }

    public long getPresentationDeadlineNanos() {
        return (long) (1_000_000_000L / REFRESH_RATE);
    }

    /** No cutout. */
    public DisplayCutout getCutout() {
        return null;
    }

    public HdrCapabilities getHdrCapabilities() {
        return new HdrCapabilities();
    }

    public boolean isHdr() {
        return false;
    }

    public boolean isWideColorGamut() {
        return false;
    }

    public ColorSpace getPreferredWideGamutColorSpace() {
        return null;
    }

    public boolean isMinimalPostProcessingSupported() {
        return false;
    }

    @Override
    public String toString() {
        return "Display(id=" + displayId + ", name=" + name + ", " + width + "x" + height + ", " + densityDpi + "dpi"
                + (valid ? "" : ", invalid") + ")";
    }

    /** A size and refresh rate the display can run at. */
    public static final class Mode implements Parcelable {
        /** Not parcelable here. */
        public static final Parcelable.Creator<Mode> CREATOR = null;

        private final int modeId;
        private final int width;
        private final int height;
        private final float refreshRate;

        private Mode(int modeId, int width, int height, float refreshRate) {
            this.modeId = modeId;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        public int getModeId() {
            return modeId;
        }

        public int getPhysicalWidth() {
            return width;
        }

        public int getPhysicalHeight() {
            return height;
        }

        public float getRefreshRate() {
            return refreshRate;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Mode)) return false;
            Mode mode = (Mode) other;
            return modeId == mode.modeId && width == mode.width && height == mode.height && refreshRate == mode.refreshRate;
        }

        @Override
        public int hashCode() {
            return ((modeId * 31 + width) * 31 + height) * 31 + Float.floatToIntBits(refreshRate);
        }

        @Override
        public String toString() {
            return "Mode(id=" + modeId + ", " + width + "x" + height + ", " + refreshRate + "fps)";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int parcelableFlags) {
            throw new RuntimeException("Stub!");
        }
    }

    /** The display's HDR support: none here. */
    public static final class HdrCapabilities implements Parcelable {
        public static final int HDR_TYPE_DOLBY_VISION = 1;
        public static final int HDR_TYPE_HDR10 = 2;
        public static final int HDR_TYPE_HLG = 3;
        public static final int HDR_TYPE_HDR10_PLUS = 4;
        public static final float INVALID_LUMINANCE = -1.0f;

        /** Not parcelable here. */
        public static final Parcelable.Creator<HdrCapabilities> CREATOR = null;

        private HdrCapabilities() {
        }

        public int[] getSupportedHdrTypes() {
            return new int[0];
        }

        public float getDesiredMaxLuminance() {
            return INVALID_LUMINANCE;
        }

        public float getDesiredMaxAverageLuminance() {
            return INVALID_LUMINANCE;
        }

        public float getDesiredMinLuminance() {
            return INVALID_LUMINANCE;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            throw new RuntimeException("Stub!");
        }
    }
}
