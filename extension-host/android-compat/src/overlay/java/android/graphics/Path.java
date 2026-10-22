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
 * Path API recording move/line/quad/cubic/close verbs for the graphics backend; arcs, ovals and
 * round rects are converted to cubics here. Boolean ops (op) and approximate() are not supported.
 */

package android.graphics;

import java.util.Arrays;
import oneechan.host.platform.PathVerbs;

public class Path {
    public enum FillType {
        WINDING(0),
        EVEN_ODD(1),
        INVERSE_WINDING(2),
        INVERSE_EVEN_ODD(3);

        FillType(int ni) {
            nativeInt = ni;
        }

        final int nativeInt;
    }

    public enum Direction {
        CW(0),
        CCW(1);

        Direction(int ni) {
            nativeInt = ni;
        }

        final int nativeInt;
    }

    public enum Op {
        DIFFERENCE,
        INTERSECT,
        UNION,
        XOR,
        REVERSE_DIFFERENCE
    }

    private byte[] verbs = new byte[16];
    private float[] coords = new float[32];
    private int verbCount;
    private int coordCount;
    private FillType fillType = FillType.WINDING;
    private float moveX, moveY;
    private boolean contourOpen;

    public Path() {}

    public Path(Path src) {
        if (src != null) set(src);
    }

    public void reset() {
        verbCount = 0;
        coordCount = 0;
        fillType = FillType.WINDING;
        moveX = moveY = 0;
        contourOpen = false;
    }

    public void rewind() {
        FillType keep = fillType;
        reset();
        fillType = keep;
    }

    public void set(Path src) {
        if (src == this) return;
        verbs = Arrays.copyOf(src.verbs, Math.max(16, src.verbCount));
        coords = Arrays.copyOf(src.coords, Math.max(32, src.coordCount));
        verbCount = src.verbCount;
        coordCount = src.coordCount;
        fillType = src.fillType;
        moveX = src.moveX;
        moveY = src.moveY;
        contourOpen = src.contourOpen;
    }

    /** Boolean path operations are not supported. */
    public boolean op(Path path, Op op) {
        return false;
    }

    public boolean op(Path path1, Path path2, Op op) {
        return false;
    }

    @Deprecated
    public boolean isConvex() {
        return false;
    }

    public FillType getFillType() {
        return fillType;
    }

    public void setFillType(FillType ft) {
        fillType = ft;
    }

    public boolean isInverseFillType() {
        return fillType == FillType.INVERSE_WINDING || fillType == FillType.INVERSE_EVEN_ODD;
    }

    public void toggleInverseFillType() {
        fillType = FillType.values()[fillType.ordinal() ^ 2];
    }

    public boolean isEmpty() {
        return verbCount == 0;
    }

    public boolean isRect(RectF rect) {
        return false;
    }

    public void computeBounds(RectF bounds, boolean exact) {
        if (coordCount == 0) {
            bounds.set(0, 0, 0, 0);
            return;
        }
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < coordCount; i += 2) {
            l = Math.min(l, coords[i]);
            r = Math.max(r, coords[i]);
            t = Math.min(t, coords[i + 1]);
            b = Math.max(b, coords[i + 1]);
        }
        bounds.set(l, t, r, b);
    }

    public void incReserve(int extraPtCount) {
        ensure(extraPtCount, extraPtCount * 2);
    }

    public void moveTo(float x, float y) {
        add(PathVerbs.MOVE, x, y);
        moveX = x;
        moveY = y;
        contourOpen = true;
    }

    public void rMoveTo(float dx, float dy) {
        float[] last = lastPoint();
        moveTo(last[0] + dx, last[1] + dy);
    }

    public void lineTo(float x, float y) {
        injectMove();
        add(PathVerbs.LINE, x, y);
    }

    public void rLineTo(float dx, float dy) {
        float[] last = lastPoint();
        lineTo(last[0] + dx, last[1] + dy);
    }

    public void quadTo(float x1, float y1, float x2, float y2) {
        injectMove();
        add(PathVerbs.QUAD, x1, y1, x2, y2);
    }

    public void rQuadTo(float dx1, float dy1, float dx2, float dy2) {
        float[] last = lastPoint();
        quadTo(last[0] + dx1, last[1] + dy1, last[0] + dx2, last[1] + dy2);
    }

    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        injectMove();
        add(PathVerbs.CUBIC, x1, y1, x2, y2, x3, y3);
    }

    public void rCubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        float[] last = lastPoint();
        cubicTo(last[0] + x1, last[1] + y1, last[0] + x2, last[1] + y2, last[0] + x3, last[1] + y3);
    }

    public void arcTo(RectF oval, float startAngle, float sweepAngle, boolean forceMoveTo) {
        arcTo(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, forceMoveTo);
    }

    public void arcTo(RectF oval, float startAngle, float sweepAngle) {
        arcTo(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, false);
    }

    public void arcTo(float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean forceMoveTo) {
        float cx = (left + right) / 2, cy = (top + bottom) / 2, rx = (right - left) / 2, ry = (bottom - top) / 2;
        double start = Math.toRadians(startAngle);
        float sx = (float) (cx + rx * Math.cos(start)), sy = (float) (cy + ry * Math.sin(start));
        if (forceMoveTo || verbCount == 0 || !contourOpen) {
            moveTo(sx, sy);
        } else {
            lineTo(sx, sy);
        }
        appendArc(cx, cy, rx, ry, startAngle, sweepAngle);
    }

    public void close() {
        if (verbCount > 0 && verbs[verbCount - 1] != PathVerbs.CLOSE) {
            ensure(1, 0);
            verbs[verbCount++] = PathVerbs.CLOSE;
        }
        contourOpen = false;
    }

    public void addRect(RectF rect, Direction dir) {
        addRect(rect.left, rect.top, rect.right, rect.bottom, dir);
    }

    public void addRect(float left, float top, float right, float bottom, Direction dir) {
        moveTo(left, top);
        if (dir == Direction.CCW) {
            lineTo(left, bottom);
            lineTo(right, bottom);
            lineTo(right, top);
        } else {
            lineTo(right, top);
            lineTo(right, bottom);
            lineTo(left, bottom);
        }
        close();
    }

    public void addOval(RectF oval, Direction dir) {
        addOval(oval.left, oval.top, oval.right, oval.bottom, dir);
    }

    public void addOval(float left, float top, float right, float bottom, Direction dir) {
        float cx = (left + right) / 2, cy = (top + bottom) / 2;
        moveTo(right, cy);
        appendArc(cx, cy, (right - left) / 2, (bottom - top) / 2, 0, dir == Direction.CCW ? -360 : 360);
        close();
    }

    public void addCircle(float x, float y, float radius, Direction dir) {
        addOval(x - radius, y - radius, x + radius, y + radius, dir);
    }

    public void addArc(RectF oval, float startAngle, float sweepAngle) {
        addArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle);
    }

    public void addArc(float left, float top, float right, float bottom, float startAngle, float sweepAngle) {
        arcTo(left, top, right, bottom, startAngle, sweepAngle, true);
    }

    public void addRoundRect(RectF rect, float rx, float ry, Direction dir) {
        addRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, dir);
    }

    public void addRoundRect(float left, float top, float right, float bottom, float rx, float ry, Direction dir) {
        addRoundRect(left, top, right, bottom, new float[] {rx, ry, rx, ry, rx, ry, rx, ry}, dir);
    }

    public void addRoundRect(RectF rect, float[] radii, Direction dir) {
        addRoundRect(rect.left, rect.top, rect.right, rect.bottom, radii, dir);
    }

    /** Radii: top-left, top-right, bottom-right, bottom-left (x, y each). */
    public void addRoundRect(float left, float top, float right, float bottom, float[] radii, Direction dir) {
        if (radii.length < 8) throw new ArrayIndexOutOfBoundsException("radii[] needs 8 values");
        float w = right - left, h = bottom - top;
        float[] r = new float[8];
        for (int i = 0; i < 8; i += 2) {
            r[i] = Math.max(0, Math.min(radii[i], w / 2));
            r[i + 1] = Math.max(0, Math.min(radii[i + 1], h / 2));
        }
        Path cw = new Path();
        cw.moveTo(left + r[0], top);
        cw.lineTo(right - r[2], top);
        cw.corner(right - r[2], top + r[3], r[2], r[3], 270);
        cw.lineTo(right, bottom - r[5]);
        cw.corner(right - r[4], bottom - r[5], r[4], r[5], 0);
        cw.lineTo(left + r[6], bottom);
        cw.corner(left + r[6], bottom - r[7], r[6], r[7], 90);
        cw.lineTo(left, top + r[1]);
        cw.corner(left + r[0], top + r[1], r[0], r[1], 180);
        cw.close();
        if (dir == Direction.CCW) cw = cw.reversedClosedContour();
        addPath(cw);
    }

    private void corner(float cx, float cy, float rx, float ry, float startAngle) {
        if (rx > 0 && ry > 0) appendArc(cx, cy, rx, ry, startAngle, 90);
    }

    /** A single closed contour of lines and cubics, reversed. */
    private Path reversedClosedContour() {
        Path out = new Path();
        float[] last = lastPoint();
        out.moveTo(last[0], last[1]);
        int c = coordCount;
        for (int i = verbCount - 1; i > 0; i--) {
            byte verb = verbs[i];
            if (verb == PathVerbs.CLOSE) continue;
            int n = 2 * PathVerbs.pointCount(verb);
            c -= n;
            float startX = coords[c - 2], startY = coords[c - 1];
            if (verb == PathVerbs.LINE) {
                out.lineTo(startX, startY);
            } else if (verb == PathVerbs.CUBIC) {
                out.cubicTo(coords[c + 2], coords[c + 3], coords[c], coords[c + 1], startX, startY);
            } else if (verb == PathVerbs.QUAD) {
                out.quadTo(coords[c], coords[c + 1], startX, startY);
            }
        }
        out.close();
        return out;
    }

    public void addPath(Path src, float dx, float dy) {
        Matrix m = new Matrix();
        m.setTranslate(dx, dy);
        addPath(src, m);
    }

    public void addPath(Path src) {
        addPath(src, null);
    }

    public void addPath(Path src, Matrix matrix) {
        float[] pts = Arrays.copyOf(src.coords, src.coordCount);
        if (matrix != null) matrix.mapPoints(pts);
        int c = 0;
        for (int i = 0; i < src.verbCount; i++) {
            byte verb = src.verbs[i];
            int n = 2 * PathVerbs.pointCount(verb);
            switch (verb) {
                case PathVerbs.MOVE -> moveTo(pts[c], pts[c + 1]);
                case PathVerbs.LINE -> lineTo(pts[c], pts[c + 1]);
                case PathVerbs.QUAD -> quadTo(pts[c], pts[c + 1], pts[c + 2], pts[c + 3]);
                case PathVerbs.CUBIC -> cubicTo(pts[c], pts[c + 1], pts[c + 2], pts[c + 3], pts[c + 4], pts[c + 5]);
                default -> close();
            }
            c += n;
        }
    }

    public void offset(float dx, float dy, Path dst) {
        if (dst != null && dst != this) {
            dst.set(this);
            dst.offset(dx, dy);
            return;
        }
        offset(dx, dy);
    }

    public void offset(float dx, float dy) {
        for (int i = 0; i < coordCount; i += 2) {
            coords[i] += dx;
            coords[i + 1] += dy;
        }
        moveX += dx;
        moveY += dy;
    }

    public void setLastPoint(float dx, float dy) {
        if (coordCount == 0) {
            moveTo(dx, dy);
        } else {
            coords[coordCount - 2] = dx;
            coords[coordCount - 1] = dy;
        }
    }

    public void transform(Matrix matrix, Path dst) {
        if (dst != null && dst != this) {
            dst.set(this);
            dst.transform(matrix);
            return;
        }
        transform(matrix);
    }

    public void transform(Matrix matrix) {
        float[] pts = Arrays.copyOf(coords, coordCount);
        matrix.mapPoints(pts);
        System.arraycopy(pts, 0, coords, 0, coordCount);
        float[] move = {moveX, moveY};
        matrix.mapPoints(move);
        moveX = move[0];
        moveY = move[1];
    }

    // --- For Canvas and the graphics backend.

    byte[] verbs() {
        return verbs;
    }

    int verbCount() {
        return verbCount;
    }

    float[] coords() {
        return coords;
    }

    boolean isEvenOdd() {
        return fillType == FillType.EVEN_ODD || fillType == FillType.INVERSE_EVEN_ODD;
    }

    private void appendArc(float cx, float cy, float rx, float ry, float startAngle, float sweepAngle) {
        if (sweepAngle == 0) return;
        int segments = (int) Math.ceil(Math.abs(sweepAngle) / 90f - 1e-4);
        double step = Math.toRadians(sweepAngle) / segments;
        double angle = Math.toRadians(startAngle);
        double k = 4.0 / 3.0 * Math.tan(step / 4);
        for (int i = 0; i < segments; i++) {
            double a0 = angle, a1 = angle + step;
            double cos0 = Math.cos(a0), sin0 = Math.sin(a0), cos1 = Math.cos(a1), sin1 = Math.sin(a1);
            cubicTo(
                    (float) (cx + rx * (cos0 - k * sin0)), (float) (cy + ry * (sin0 + k * cos0)),
                    (float) (cx + rx * (cos1 + k * sin1)), (float) (cy + ry * (sin1 - k * cos1)),
                    (float) (cx + rx * cos1), (float) (cy + ry * sin1));
            angle = a1;
        }
    }

    private void injectMove() {
        if (!contourOpen) moveTo(moveX, moveY);
    }

    private float[] lastPoint() {
        if (coordCount == 0) return new float[] {0, 0};
        if (!contourOpen) return new float[] {moveX, moveY};
        return new float[] {coords[coordCount - 2], coords[coordCount - 1]};
    }

    private void add(byte verb, float... pts) {
        ensure(1, pts.length);
        verbs[verbCount++] = verb;
        System.arraycopy(pts, 0, coords, coordCount, pts.length);
        coordCount += pts.length;
    }

    private void ensure(int moreVerbs, int moreCoords) {
        if (verbCount + moreVerbs > verbs.length) verbs = Arrays.copyOf(verbs, Math.max(verbs.length * 2, verbCount + moreVerbs));
        if (coordCount + moreCoords > coords.length) coords = Arrays.copyOf(coords, Math.max(coords.length * 2, coordCount + moreCoords));
    }
}
