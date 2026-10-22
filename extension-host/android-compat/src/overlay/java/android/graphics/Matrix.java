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
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"). The AOSP
 * Matrix API on a plain 3x3 float array, with Skia's SkMatrix semantics (pre/post order, sin/cos
 * snapping, setRectToRect); setPolyToPoly supports up to three points (affine).
 */

package android.graphics;

import java.util.Arrays;

public class Matrix {
    public static final int MSCALE_X = 0;
    public static final int MSKEW_X = 1;
    public static final int MTRANS_X = 2;
    public static final int MSKEW_Y = 3;
    public static final int MSCALE_Y = 4;
    public static final int MTRANS_Y = 5;
    public static final int MPERSP_0 = 6;
    public static final int MPERSP_1 = 7;
    public static final int MPERSP_2 = 8;

    public enum ScaleToFit {
        FILL(0),
        START(1),
        CENTER(2),
        END(3);

        ScaleToFit(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    private static final float NEARLY_ZERO = 1.0f / (1 << 12);

    private final float[] m = new float[9];

    public Matrix() {
        reset();
    }

    public Matrix(Matrix src) {
        set(src);
    }

    public boolean isIdentity() {
        return m[0] == 1 && m[1] == 0 && m[2] == 0 && m[3] == 0 && m[4] == 1 && m[5] == 0 && m[6] == 0 && m[7] == 0 && m[8] == 1;
    }

    public boolean isAffine() {
        return m[6] == 0 && m[7] == 0 && m[8] == 1;
    }

    public boolean rectStaysRect() {
        if (!isAffine()) return false;
        return (m[1] == 0 && m[3] == 0 && m[0] != 0 && m[4] != 0) || (m[0] == 0 && m[4] == 0 && m[1] != 0 && m[3] != 0);
    }

    public void set(Matrix src) {
        if (src == null) {
            reset();
        } else {
            System.arraycopy(src.m, 0, m, 0, 9);
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Matrix other && Arrays.equals(m, other.m);
    }

    @Override
    public int hashCode() {
        return 44;
    }

    public void reset() {
        set9(m, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    public void setTranslate(float dx, float dy) {
        set9(m, 1, 0, dx, 0, 1, dy, 0, 0, 1);
    }

    public void setScale(float sx, float sy, float px, float py) {
        set9(m, sx, 0, px - sx * px, 0, sy, py - sy * py, 0, 0, 1);
    }

    public void setScale(float sx, float sy) {
        set9(m, sx, 0, 0, 0, sy, 0, 0, 0, 1);
    }

    public void setRotate(float degrees, float px, float py) {
        double radians = Math.toRadians(degrees);
        setSinCos(snap((float) Math.sin(radians)), snap((float) Math.cos(radians)), px, py);
    }

    public void setRotate(float degrees) {
        setRotate(degrees, 0, 0);
    }

    public void setSinCos(float sinValue, float cosValue, float px, float py) {
        float oneMinusCos = 1 - cosValue;
        set9(m, cosValue, -sinValue, sinValue * py + oneMinusCos * px, sinValue, cosValue, -sinValue * px + oneMinusCos * py, 0, 0, 1);
    }

    public void setSinCos(float sinValue, float cosValue) {
        setSinCos(sinValue, cosValue, 0, 0);
    }

    public void setSkew(float kx, float ky, float px, float py) {
        set9(m, 1, kx, -kx * py, ky, 1, -ky * px, 0, 0, 1);
    }

    public void setSkew(float kx, float ky) {
        set9(m, 1, kx, 0, ky, 1, 0, 0, 0, 1);
    }

    /** Sets this to a * b. */
    public boolean setConcat(Matrix a, Matrix b) {
        float[] out = new float[9];
        multiply(out, a.m, b.m);
        System.arraycopy(out, 0, m, 0, 9);
        return true;
    }

    public boolean preTranslate(float dx, float dy) {
        return pre(1, 0, dx, 0, 1, dy);
    }

    public boolean preScale(float sx, float sy, float px, float py) {
        return pre(sx, 0, px - sx * px, 0, sy, py - sy * py);
    }

    public boolean preScale(float sx, float sy) {
        return pre(sx, 0, 0, 0, sy, 0);
    }

    public boolean preRotate(float degrees, float px, float py) {
        Matrix r = new Matrix();
        r.setRotate(degrees, px, py);
        return preConcat(r);
    }

    public boolean preRotate(float degrees) {
        return preRotate(degrees, 0, 0);
    }

    public boolean preSkew(float kx, float ky, float px, float py) {
        return pre(1, kx, -kx * py, ky, 1, -ky * px);
    }

    public boolean preSkew(float kx, float ky) {
        return pre(1, kx, 0, ky, 1, 0);
    }

    public boolean preConcat(Matrix other) {
        float[] out = new float[9];
        multiply(out, m, other.m);
        System.arraycopy(out, 0, m, 0, 9);
        return true;
    }

    public boolean postTranslate(float dx, float dy) {
        return post(1, 0, dx, 0, 1, dy);
    }

    public boolean postScale(float sx, float sy, float px, float py) {
        return post(sx, 0, px - sx * px, 0, sy, py - sy * py);
    }

    public boolean postScale(float sx, float sy) {
        return post(sx, 0, 0, 0, sy, 0);
    }

    public boolean postRotate(float degrees, float px, float py) {
        Matrix r = new Matrix();
        r.setRotate(degrees, px, py);
        return postConcat(r);
    }

    public boolean postRotate(float degrees) {
        return postRotate(degrees, 0, 0);
    }

    public boolean postSkew(float kx, float ky, float px, float py) {
        return post(1, kx, -kx * py, ky, 1, -ky * px);
    }

    public boolean postSkew(float kx, float ky) {
        return post(1, kx, 0, ky, 1, 0);
    }

    public boolean postConcat(Matrix other) {
        float[] out = new float[9];
        multiply(out, other.m, m);
        System.arraycopy(out, 0, m, 0, 9);
        return true;
    }

    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
        if (dst == null || src == null) throw new NullPointerException();
        if (src.isEmpty()) {
            reset();
            return false;
        }
        if (dst.isEmpty()) {
            Arrays.fill(m, 0);
            m[8] = 1;
            return true;
        }
        float sx = dst.width() / src.width();
        float sy = dst.height() / src.height();
        boolean xLarger = false;
        if (stf != ScaleToFit.FILL) {
            if (sx > sy) {
                xLarger = true;
                sx = sy;
            } else {
                sy = sx;
            }
        }
        float tx = dst.left - src.left * sx;
        float ty = dst.top - src.top * sy;
        if (stf == ScaleToFit.CENTER || stf == ScaleToFit.END) {
            float diff = xLarger ? dst.width() - src.width() * sy : dst.height() - src.height() * sy;
            if (stf == ScaleToFit.CENTER) diff = diff / 2;
            if (xLarger) {
                tx += diff;
            } else {
                ty += diff;
            }
        }
        set9(m, sx, 0, tx, 0, sy, ty, 0, 0, 1);
        return true;
    }

    /** Affine only: up to three point pairs; four (perspective) is not supported and returns false. */
    public boolean setPolyToPoly(float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount) {
        if (pointCount < 0 || pointCount > 4) throw new IllegalArgumentException();
        switch (pointCount) {
            case 0:
                reset();
                return true;
            case 1:
                setTranslate(dst[dstIndex] - src[srcIndex], dst[dstIndex + 1] - src[srcIndex + 1]);
                return true;
            case 2: {
                // Similarity: maps the vector between the two points onto the other.
                float sx = src[srcIndex + 2] - src[srcIndex], sy = src[srcIndex + 3] - src[srcIndex + 1];
                float dx = dst[dstIndex + 2] - dst[dstIndex], dy = dst[dstIndex + 3] - dst[dstIndex + 1];
                float len = sx * sx + sy * sy;
                if (len == 0) return false;
                float cos = (sx * dx + sy * dy) / len;
                float sin = (sx * dy - sy * dx) / len;
                float tx = dst[dstIndex] - (cos * src[srcIndex] - sin * src[srcIndex + 1]);
                float ty = dst[dstIndex + 1] - (sin * src[srcIndex] + cos * src[srcIndex + 1]);
                set9(m, cos, -sin, tx, sin, cos, ty, 0, 0, 1);
                return true;
            }
            case 3: {
                Matrix from = affineFromPoints(src, srcIndex);
                Matrix to = affineFromPoints(dst, dstIndex);
                Matrix inverse = new Matrix();
                if (!from.invert(inverse)) return false;
                setConcat(to, inverse);
                return true;
            }
            default:
                return false;
        }
    }

    private static Matrix affineFromPoints(float[] p, int i) {
        Matrix r = new Matrix();
        set9(r.m, p[i + 2] - p[i], p[i + 4] - p[i], p[i], p[i + 3] - p[i + 1], p[i + 5] - p[i + 1], p[i + 1], 0, 0, 1);
        return r;
    }

    public boolean invert(Matrix inverse) {
        float[] a = m;
        double det = a[0] * ((double) a[4] * a[8] - (double) a[5] * a[7])
                - a[1] * ((double) a[3] * a[8] - (double) a[5] * a[6])
                + a[2] * ((double) a[3] * a[7] - (double) a[4] * a[6]);
        if (Math.abs(det) < 1e-12 || Double.isNaN(det)) return false;
        if (inverse == null) return true;
        double inv = 1.0 / det;
        float[] out = new float[9];
        out[0] = (float) ((a[4] * (double) a[8] - a[5] * (double) a[7]) * inv);
        out[1] = (float) ((a[2] * (double) a[7] - a[1] * (double) a[8]) * inv);
        out[2] = (float) ((a[1] * (double) a[5] - a[2] * (double) a[4]) * inv);
        out[3] = (float) ((a[5] * (double) a[6] - a[3] * (double) a[8]) * inv);
        out[4] = (float) ((a[0] * (double) a[8] - a[2] * (double) a[6]) * inv);
        out[5] = (float) ((a[2] * (double) a[3] - a[0] * (double) a[5]) * inv);
        out[6] = (float) ((a[3] * (double) a[7] - a[4] * (double) a[6]) * inv);
        out[7] = (float) ((a[1] * (double) a[6] - a[0] * (double) a[7]) * inv);
        out[8] = (float) ((a[0] * (double) a[4] - a[1] * (double) a[3]) * inv);
        System.arraycopy(out, 0, inverse.m, 0, 9);
        return true;
    }

    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex, int pointCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, pointCount);
        float[] tmp = new float[pointCount * 2];
        for (int i = 0; i < pointCount; i++) {
            float x = src[srcIndex + 2 * i], y = src[srcIndex + 2 * i + 1];
            float w = m[6] * x + m[7] * y + m[8];
            if (w == 0) w = 1;
            tmp[2 * i] = (m[0] * x + m[1] * y + m[2]) / w;
            tmp[2 * i + 1] = (m[3] * x + m[4] * y + m[5]) / w;
        }
        System.arraycopy(tmp, 0, dst, dstIndex, tmp.length);
    }

    public void mapPoints(float[] dst, float[] src) {
        if (dst.length != src.length) throw new ArrayIndexOutOfBoundsException();
        mapPoints(dst, 0, src, 0, dst.length >> 1);
    }

    public void mapPoints(float[] pts) {
        mapPoints(pts, 0, pts, 0, pts.length >> 1);
    }

    public void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex, int vectorCount) {
        checkPointArrays(src, srcIndex, dst, dstIndex, vectorCount);
        float[] tmp = new float[vectorCount * 2];
        for (int i = 0; i < vectorCount; i++) {
            float x = src[srcIndex + 2 * i], y = src[srcIndex + 2 * i + 1];
            tmp[2 * i] = m[0] * x + m[1] * y;
            tmp[2 * i + 1] = m[3] * x + m[4] * y;
        }
        System.arraycopy(tmp, 0, dst, dstIndex, tmp.length);
    }

    public void mapVectors(float[] dst, float[] src) {
        if (dst.length != src.length) throw new ArrayIndexOutOfBoundsException();
        mapVectors(dst, 0, src, 0, dst.length >> 1);
    }

    public void mapVectors(float[] vecs) {
        mapVectors(vecs, 0, vecs, 0, vecs.length >> 1);
    }

    public boolean mapRect(RectF dst, RectF src) {
        if (dst == null || src == null) throw new NullPointerException();
        float[] pts = {src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom};
        mapPoints(pts);
        float l = Math.min(Math.min(pts[0], pts[2]), Math.min(pts[4], pts[6]));
        float r = Math.max(Math.max(pts[0], pts[2]), Math.max(pts[4], pts[6]));
        float t = Math.min(Math.min(pts[1], pts[3]), Math.min(pts[5], pts[7]));
        float b = Math.max(Math.max(pts[1], pts[3]), Math.max(pts[5], pts[7]));
        dst.set(l, t, r, b);
        return rectStaysRect();
    }

    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    public float mapRadius(float radius) {
        float[] v = {radius, 0, 0, radius};
        mapVectors(v);
        double d0 = Math.hypot(v[0], v[1]);
        double d1 = Math.hypot(v[2], v[3]);
        return (float) Math.sqrt(d0 * d1);
    }

    public void getValues(float[] values) {
        if (values.length < 9) throw new ArrayIndexOutOfBoundsException();
        System.arraycopy(m, 0, values, 0, 9);
    }

    public void setValues(float[] values) {
        if (values.length < 9) throw new ArrayIndexOutOfBoundsException();
        System.arraycopy(values, 0, m, 0, 9);
    }

    @Override
    public String toString() {
        return "Matrix{" + toShortString() + "}";
    }

    public String toShortString() {
        return "[" + m[0] + ", " + m[1] + ", " + m[2] + "][" + m[3] + ", " + m[4] + ", " + m[5] + "][" + m[6] + ", " + m[7] + ", " + m[8] + "]";
    }

    /** The affine part as a, b, c, d, tx, ty (x' = a*x + c*y + tx, y' = b*x + d*y + ty). */
    void getAffine(float[] out) {
        out[0] = m[0];
        out[1] = m[3];
        out[2] = m[1];
        out[3] = m[4];
        out[4] = m[2];
        out[5] = m[5];
    }

    private boolean pre(float a0, float a1, float a2, float a3, float a4, float a5) {
        float[] other = {a0, a1, a2, a3, a4, a5, 0, 0, 1};
        float[] out = new float[9];
        multiply(out, m, other);
        System.arraycopy(out, 0, m, 0, 9);
        return true;
    }

    private boolean post(float a0, float a1, float a2, float a3, float a4, float a5) {
        float[] other = {a0, a1, a2, a3, a4, a5, 0, 0, 1};
        float[] out = new float[9];
        multiply(out, other, m);
        System.arraycopy(out, 0, m, 0, 9);
        return true;
    }

    private static void multiply(float[] out, float[] a, float[] b) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = (float) ((double) a[r * 3] * b[c] + (double) a[r * 3 + 1] * b[3 + c] + (double) a[r * 3 + 2] * b[6 + c]);
            }
        }
    }

    private static void set9(float[] m, float a, float b, float c, float d, float e, float f, float g, float h, float i) {
        m[0] = a;
        m[1] = b;
        m[2] = c;
        m[3] = d;
        m[4] = e;
        m[5] = f;
        m[6] = g;
        m[7] = h;
        m[8] = i;
    }

    private static float snap(float v) {
        return Math.abs(v) <= NEARLY_ZERO ? 0f : v;
    }

    private static void checkPointArrays(float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount) {
        int srcStop = srcIndex + (pointCount << 1);
        int dstStop = dstIndex + (pointCount << 1);
        if ((pointCount | srcIndex | dstIndex | srcStop | dstStop) < 0 || srcStop > src.length || dstStop > dst.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
}
