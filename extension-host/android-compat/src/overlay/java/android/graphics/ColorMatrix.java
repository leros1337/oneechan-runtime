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
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"), reduced from
 * AOSP frameworks/base graphics/java/android/graphics/ColorMatrix.java; the matrix math is AOSP's.
 */

package android.graphics;

import java.util.Arrays;

/** 4x5 matrix transforming RGBA colors (AOSP math). */
public class ColorMatrix {
    private final float[] mArray = new float[20];

    public ColorMatrix() { reset(); }
    public ColorMatrix(float[] src) { System.arraycopy(src, 0, mArray, 0, 20); }
    public ColorMatrix(ColorMatrix src) { System.arraycopy(src.mArray, 0, mArray, 0, 20); }

    public final float[] getArray() { return mArray; }

    public void reset() {
        Arrays.fill(mArray, 0);
        mArray[0] = mArray[6] = mArray[12] = mArray[18] = 1;
    }

    public void set(ColorMatrix src) { System.arraycopy(src.mArray, 0, mArray, 0, 20); }
    public void set(float[] src) { System.arraycopy(src, 0, mArray, 0, 20); }

    public void setScale(float rScale, float gScale, float bScale, float aScale) {
        Arrays.fill(mArray, 0);
        mArray[0] = rScale;
        mArray[6] = gScale;
        mArray[12] = bScale;
        mArray[18] = aScale;
    }

    public void setRotate(int axis, float degrees) {
        reset();
        double radians = degrees * Math.PI / 180d;
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        switch (axis) {
            case 0 -> { mArray[6] = mArray[12] = cosine; mArray[7] = sine; mArray[11] = -sine; }
            case 1 -> { mArray[0] = mArray[12] = cosine; mArray[2] = -sine; mArray[10] = sine; }
            case 2 -> { mArray[0] = mArray[6] = cosine; mArray[1] = sine; mArray[5] = -sine; }
            default -> throw new RuntimeException();
        }
    }

    public void setConcat(ColorMatrix matA, ColorMatrix matB) {
        float[] tmp = (matA == this || matB == this) ? new float[20] : mArray;
        float[] a = matA.mArray;
        float[] b = matB.mArray;
        int index = 0;
        for (int j = 0; j < 20; j += 5) {
            for (int i = 0; i < 4; i++) {
                tmp[index++] = a[j] * b[i] + a[j + 1] * b[i + 5] + a[j + 2] * b[i + 10] + a[j + 3] * b[i + 15];
            }
            tmp[index++] = a[j] * b[4] + a[j + 1] * b[9] + a[j + 2] * b[14] + a[j + 3] * b[19] + a[j + 4];
        }
        if (tmp != mArray) System.arraycopy(tmp, 0, mArray, 0, 20);
    }

    public void preConcat(ColorMatrix prematrix) { setConcat(this, prematrix); }
    public void postConcat(ColorMatrix postmatrix) { setConcat(postmatrix, this); }

    public void setSaturation(float sat) {
        reset();
        float[] m = mArray;
        float invSat = 1 - sat;
        float r = 0.213f * invSat;
        float g = 0.715f * invSat;
        float b = 0.072f * invSat;
        m[0] = r + sat; m[1] = g; m[2] = b;
        m[5] = r; m[6] = g + sat; m[7] = b;
        m[10] = r; m[11] = g; m[12] = b + sat;
    }

    public void setRGB2YUV() {
        reset();
        float[] m = mArray;
        m[0] = 0.299f; m[1] = 0.587f; m[2] = 0.114f;
        m[5] = -0.16874f; m[6] = -0.33126f; m[7] = 0.5f;
        m[10] = 0.5f; m[11] = -0.41869f; m[12] = -0.08131f;
    }

    public void setYUV2RGB() {
        reset();
        float[] m = mArray;
        m[2] = 1.402f;
        m[5] = 1; m[6] = -0.34414f; m[7] = -0.71414f;
        m[10] = 1; m[11] = 1.772f; m[12] = 0;
    }

    /** Applies the matrix to one ARGB color (used by the software canvas). */
    public int apply(int argb) {
        float a = (argb >>> 24), r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        float[] m = mArray;
        int nr = clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]);
        int ng = clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]);
        int nb = clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]);
        int na = clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]);
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    private static int clamp(float v) { return v <= 0 ? 0 : v >= 255 ? 255 : Math.round(v); }

    @Override public boolean equals(Object obj) { return obj instanceof ColorMatrix other && Arrays.equals(mArray, other.mArray); }
    @Override public int hashCode() { return Arrays.hashCode(mArray); }
}
