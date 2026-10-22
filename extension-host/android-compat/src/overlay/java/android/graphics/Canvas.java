/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Portions Copyright (C) 2006 The Android Open Source Project, licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations.
 *
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's Canvas. Upstream draws with
 * java.awt.Graphics2D (unusable on iOS); this version keeps the canvas state like Skia (a Matrix,
 * a clip stack of rects under their matrices, save/restore) and hands each draw to the active
 * oneechan.host.platform.GraphicsBackend. Adds the drawBitmap overloads (Rect/RectF/Matrix/colors),
 * shapes (rect, oval, circle, arc, round rect, line(s), point(s), path, paint), clipRect variants,
 * concat/setMatrix/skew, quickReject. saveLayer is a plain save (no offscreen layer); clipPath
 * clips to the path's bounds.
 */

package android.graphics;

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import oneechan.host.platform.BlendModes;
import oneechan.host.platform.CanvasState;
import oneechan.host.platform.FontSpec;
import oneechan.host.platform.PaintSpec;
import oneechan.host.platform.PixelBuffer;

public class Canvas {
    public static final int MATRIX_SAVE_FLAG = 0x01;
    public static final int CLIP_SAVE_FLAG = 0x02;
    public static final int HAS_ALPHA_LAYER_SAVE_FLAG = 0x04;
    public static final int FULL_COLOR_LAYER_SAVE_FLAG = 0x08;
    public static final int CLIP_TO_LAYER_SAVE_FLAG = 0x10;
    public static final int ALL_SAVE_FLAG = 0x1F;

    public enum EdgeType {
        BW(0),
        AA(1);

        EdgeType(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    public enum VertexMode {
        TRIANGLES(0),
        TRIANGLE_STRIP(1),
        TRIANGLE_FAN(2);

        VertexMode(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    private static final class SavedState {
        final Matrix matrix;
        final int clipCount;
        final RectF clipBounds;

        SavedState(Matrix matrix, int clipCount, RectF clipBounds) {
            this.matrix = new Matrix(matrix);
            this.clipCount = clipCount;
            this.clipBounds = new RectF(clipBounds);
        }
    }

    private Bitmap mBitmap;
    private int mDensity = Bitmap.DENSITY_NONE;
    private final Matrix mMatrix = new Matrix();
    private float[] mClips = new float[CanvasState.CLIP_STRIDE * 4];
    private int mClipCount;
    /** Conservative device-space bounds of the clip. */
    private final RectF mClipBounds = new RectF();
    private final ArrayList<SavedState> mStack = new ArrayList<>();
    private CanvasState mState;
    private final Path mScratch = new Path();

    public Canvas() {}

    public Canvas(@NonNull Bitmap bitmap) {
        if (bitmap == null) throw new NullPointerException();
        if (bitmap.isRecycled()) throw new RuntimeException("Canvas: trying to use a recycled bitmap " + bitmap);
        mBitmap = bitmap;
        mDensity = bitmap.getDensity();
        resetState();
    }

    public void setBitmap(@Nullable Bitmap bitmap) {
        if (bitmap != null && bitmap.isRecycled()) throw new RuntimeException("Canvas: trying to use a recycled bitmap " + bitmap);
        mBitmap = bitmap;
        mDensity = bitmap == null ? Bitmap.DENSITY_NONE : bitmap.getDensity();
        resetState();
    }

    private void resetState() {
        mMatrix.reset();
        mClipCount = 0;
        mStack.clear();
        mClipBounds.set(0, 0, getWidth(), getHeight());
        mState = null;
    }

    public boolean isHardwareAccelerated() {
        return false;
    }

    public boolean isOpaque() {
        return mBitmap != null && !mBitmap.hasAlpha();
    }

    public int getWidth() {
        return mBitmap == null ? 0 : mBitmap.getWidth();
    }

    public int getHeight() {
        return mBitmap == null ? 0 : mBitmap.getHeight();
    }

    public int getDensity() {
        return mDensity;
    }

    public void setDensity(int density) {
        if (mBitmap != null) mBitmap.setDensity(density);
        mDensity = density;
    }

    public int getMaximumBitmapWidth() {
        return 32766;
    }

    public int getMaximumBitmapHeight() {
        return 32766;
    }

    // --- Save and restore.

    public int save() {
        mStack.add(new SavedState(mMatrix, mClipCount, mClipBounds));
        return mStack.size();
    }

    public int save(int saveFlags) {
        return save();
    }

    public int saveLayer(@Nullable RectF bounds, @Nullable Paint paint, int saveFlags) {
        return save();
    }

    public int saveLayer(@Nullable RectF bounds, @Nullable Paint paint) {
        return save();
    }

    public int saveLayer(float left, float top, float right, float bottom, @Nullable Paint paint, int saveFlags) {
        return save();
    }

    public int saveLayer(float left, float top, float right, float bottom, @Nullable Paint paint) {
        return save();
    }

    public int saveLayerAlpha(@Nullable RectF bounds, int alpha, int saveFlags) {
        return save();
    }

    public int saveLayerAlpha(@Nullable RectF bounds, int alpha) {
        return save();
    }

    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha, int saveFlags) {
        return save();
    }

    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) {
        return save();
    }

    public void restore() {
        if (mStack.isEmpty()) throw new IllegalStateException("Underflow in restore - more restores than saves");
        restoreState(mStack.remove(mStack.size() - 1));
    }

    public int getSaveCount() {
        return mStack.size() + 1;
    }

    public void restoreToCount(int saveCount) {
        if (saveCount < 1) throw new IllegalArgumentException("Underflow in restoreToCount - more restores than saves");
        if (saveCount > getSaveCount()) throw new IllegalArgumentException("Overflow in restoreToCount");
        while (getSaveCount() > saveCount) restore();
    }

    private void restoreState(SavedState saved) {
        mMatrix.set(saved.matrix);
        mClipCount = saved.clipCount;
        mClipBounds.set(saved.clipBounds);
        mState = null;
    }

    // --- Matrix.

    public void translate(float dx, float dy) {
        if (dx == 0.0f && dy == 0.0f) return;
        mMatrix.preTranslate(dx, dy);
        mState = null;
    }

    public void scale(float sx, float sy) {
        if (sx == 1.0f && sy == 1.0f) return;
        mMatrix.preScale(sx, sy);
        mState = null;
    }

    public final void scale(float sx, float sy, float px, float py) {
        if (sx == 1.0f && sy == 1.0f) return;
        translate(px, py);
        scale(sx, sy);
        translate(-px, -py);
    }

    public void rotate(float degrees) {
        if (degrees == 0.0f) return;
        mMatrix.preRotate(degrees);
        mState = null;
    }

    public final void rotate(float degrees, float px, float py) {
        if (degrees == 0.0f) return;
        translate(px, py);
        rotate(degrees);
        translate(-px, -py);
    }

    public void skew(float sx, float sy) {
        if (sx == 0.0f && sy == 0.0f) return;
        mMatrix.preSkew(sx, sy);
        mState = null;
    }

    public void concat(@Nullable Matrix matrix) {
        if (matrix == null) return;
        mMatrix.preConcat(matrix);
        mState = null;
    }

    public void setMatrix(@Nullable Matrix matrix) {
        mMatrix.set(matrix);
        mState = null;
    }

    @Deprecated
    public void getMatrix(@NonNull Matrix ctm) {
        ctm.set(mMatrix);
    }

    @Deprecated
    public final @NonNull Matrix getMatrix() {
        return new Matrix(mMatrix);
    }

    // --- Clip.

    private boolean addClip(float left, float top, float right, float bottom, float op) {
        int need = (mClipCount + 1) * CanvasState.CLIP_STRIDE;
        if (need > mClips.length) mClips = Arrays.copyOf(mClips, Math.max(need, mClips.length * 2));
        float[] affine = new float[6];
        mMatrix.getAffine(affine);
        int o = mClipCount * CanvasState.CLIP_STRIDE;
        System.arraycopy(affine, 0, mClips, o, 6);
        mClips[o + 6] = left;
        mClips[o + 7] = top;
        mClips[o + 8] = right;
        mClips[o + 9] = bottom;
        mClips[o + 10] = op;
        mClipCount++;
        if (op == CanvasState.CLIP_INTERSECT) {
            RectF device = new RectF(left, top, right, bottom);
            device.sort();
            mMatrix.mapRect(device);
            if (!mClipBounds.intersect(device)) mClipBounds.setEmpty();
        }
        mState = null;
        return !mClipBounds.isEmpty();
    }

    public boolean clipRect(@NonNull RectF rect) {
        return addClip(rect.left, rect.top, rect.right, rect.bottom, CanvasState.CLIP_INTERSECT);
    }

    public boolean clipRect(@NonNull Rect rect) {
        return addClip(rect.left, rect.top, rect.right, rect.bottom, CanvasState.CLIP_INTERSECT);
    }

    public boolean clipRect(float left, float top, float right, float bottom) {
        return addClip(left, top, right, bottom, CanvasState.CLIP_INTERSECT);
    }

    public boolean clipRect(int left, int top, int right, int bottom) {
        return addClip(left, top, right, bottom, CanvasState.CLIP_INTERSECT);
    }

    @Deprecated
    public boolean clipRect(@NonNull RectF rect, @NonNull Region.Op op) {
        return clipWithOp(rect.left, rect.top, rect.right, rect.bottom, op);
    }

    @Deprecated
    public boolean clipRect(@NonNull Rect rect, @NonNull Region.Op op) {
        return clipWithOp(rect.left, rect.top, rect.right, rect.bottom, op);
    }

    @Deprecated
    public boolean clipRect(float left, float top, float right, float bottom, @NonNull Region.Op op) {
        return clipWithOp(left, top, right, bottom, op);
    }

    private boolean clipWithOp(float left, float top, float right, float bottom, Region.Op op) {
        String name = op.name();
        if (name.equals("DIFFERENCE")) return addClip(left, top, right, bottom, CanvasState.CLIP_DIFFERENCE);
        if (name.equals("INTERSECT")) return addClip(left, top, right, bottom, CanvasState.CLIP_INTERSECT);
        throw new IllegalArgumentException("Invalid Region.Op - only INTERSECT and DIFFERENCE are allowed");
    }

    public boolean clipOutRect(@NonNull RectF rect) {
        return addClip(rect.left, rect.top, rect.right, rect.bottom, CanvasState.CLIP_DIFFERENCE);
    }

    public boolean clipOutRect(@NonNull Rect rect) {
        return addClip(rect.left, rect.top, rect.right, rect.bottom, CanvasState.CLIP_DIFFERENCE);
    }

    public boolean clipOutRect(float left, float top, float right, float bottom) {
        return addClip(left, top, right, bottom, CanvasState.CLIP_DIFFERENCE);
    }

    public boolean clipOutRect(int left, int top, int right, int bottom) {
        return addClip(left, top, right, bottom, CanvasState.CLIP_DIFFERENCE);
    }

    /** Clips to the path's bounds (path clips are not supported by the backends). */
    public boolean clipPath(@NonNull Path path) {
        RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        return clipRect(bounds);
    }

    public boolean clipOutPath(@NonNull Path path) {
        return !mClipBounds.isEmpty();
    }

    public boolean getClipBounds(@Nullable Rect bounds) {
        Rect out = bounds != null ? bounds : new Rect();
        if (mClipBounds.isEmpty()) {
            out.setEmpty();
            return false;
        }
        Rect device = new Rect();
        mClipBounds.roundOut(device);
        Matrix inverse = new Matrix();
        if (!mMatrix.invert(inverse)) {
            out.setEmpty();
            return false;
        }
        RectF local = new RectF(device);
        inverse.mapRect(local);
        local.round(out);
        return !out.isEmpty();
    }

    public final @NonNull Rect getClipBounds() {
        Rect r = new Rect();
        getClipBounds(r);
        return r;
    }

    public boolean quickReject(@NonNull RectF rect, @NonNull EdgeType type) {
        return quickReject(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean quickReject(@NonNull RectF rect) {
        return quickReject(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean quickReject(@NonNull Path path, @NonNull EdgeType type) {
        return quickReject(path);
    }

    public boolean quickReject(@NonNull Path path) {
        RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        return quickReject(bounds);
    }

    public boolean quickReject(float left, float top, float right, float bottom, @NonNull EdgeType type) {
        return quickReject(left, top, right, bottom);
    }

    public boolean quickReject(float left, float top, float right, float bottom) {
        RectF device = new RectF(left, top, right, bottom);
        device.sort();
        mMatrix.mapRect(device);
        return mClipBounds.isEmpty() || !RectF.intersects(device, mClipBounds);
    }

    // --- Drawing.

    private CanvasState state() {
        CanvasState state = mState;
        if (state == null) {
            float[] affine = new float[6];
            mMatrix.getAffine(affine);
            state = new CanvasState(affine, Arrays.copyOf(mClips, mClipCount * CanvasState.CLIP_STRIDE), mClipCount);
            mState = state;
        }
        return state;
    }

    private Bitmap target() {
        if (mBitmap == null) throw new IllegalStateException("Canvas has no bitmap to draw into");
        return mBitmap;
    }

    private static PaintSpec specOf(@Nullable Paint paint) {
        return paint == null ? PaintSpec.DEFAULT : paint.spec();
    }

    private static PaintSpec withStyle(PaintSpec spec, int style) {
        if (spec.getStyle() == style) return spec;
        return new PaintSpec(spec.getColor(), spec.getFlags(), style, spec.getStrokeWidth(), spec.getCap(), spec.getJoin(), spec.getMiter(),
                spec.getBlendMode(), spec.colorMatrix);
    }

    public void drawARGB(int a, int r, int g, int b) {
        drawColor(Color.argb(a, r, g, b));
    }

    public void drawRGB(int r, int g, int b) {
        drawColor(Color.rgb(r, g, b));
    }

    public void drawColor(@ColorInt int color) {
        fillClip(color, BlendModes.SRC_OVER);
    }

    public void drawColor(@ColorLong long color) {
        fillClip(Color.toArgb(color), BlendModes.SRC_OVER);
    }

    public void drawColor(@ColorInt int color, @NonNull PorterDuff.Mode mode) {
        fillClip(color, BlendModes.ofPorterDuff(mode.name()));
    }

    public void drawColor(@ColorInt int color, @NonNull BlendMode mode) {
        fillClip(color, BlendModes.ofPorterDuff(mode.name()));
    }

    public void drawColor(@ColorLong long color, @NonNull BlendMode mode) {
        fillClip(Color.toArgb(color), BlendModes.ofPorterDuff(mode.name()));
    }

    public void drawPaint(@NonNull Paint paint) {
        PaintSpec spec = paint.spec();
        int color = spec.colorMatrix != null ? oneechan.host.platform.PixelMath.applyColorMatrix(spec.colorMatrix, spec.getColor()) : spec.getColor();
        fillClip(color, spec.getBlendMode());
    }

    private void fillClip(int color, int blend) {
        Bitmap target = target();
        CanvasState state = state();
        synchronized (target.lock) {
            target.pixels().drawColor(color, blend, state);
            target.noteChanged();
        }
    }

    private void fill(Path path, PaintSpec spec) {
        Bitmap target = target();
        CanvasState state = state();
        synchronized (target.lock) {
            target.pixels().drawPath(path.verbs(), path.verbCount(), path.coords(), path.isEvenOdd(), state, spec);
            target.noteChanged();
        }
    }

    public void drawPath(@NonNull Path path, @NonNull Paint paint) {
        if (path.isEmpty()) return;
        fill(path, paint.spec());
    }

    public void drawRect(@NonNull RectF rect, @NonNull Paint paint) {
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint);
    }

    public void drawRect(@NonNull Rect r, @NonNull Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRect(float left, float top, float right, float bottom, @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.addRect(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom), Path.Direction.CW);
        fill(path, paint.spec());
    }

    public void drawOval(@NonNull RectF oval, @NonNull Paint paint) {
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    public void drawOval(float left, float top, float right, float bottom, @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.addOval(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom), Path.Direction.CW);
        fill(path, paint.spec());
    }

    public void drawCircle(float cx, float cy, float radius, @NonNull Paint paint) {
        if (radius <= 0) return;
        drawOval(cx - radius, cy - radius, cx + radius, cy + radius, paint);
    }

    public void drawArc(@NonNull RectF oval, float startAngle, float sweepAngle, boolean useCenter, @NonNull Paint paint) {
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint);
    }

    public void drawArc(float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean useCenter,
                        @NonNull Paint paint) {
        if (sweepAngle == 0) return;
        if (Math.abs(sweepAngle) >= 360) {
            drawOval(left, top, right, bottom, paint);
            return;
        }
        Path path = mScratch;
        path.rewind();
        if (useCenter) {
            path.moveTo((left + right) / 2, (top + bottom) / 2);
            path.arcTo(left, top, right, bottom, startAngle, sweepAngle, false);
            path.close();
        } else {
            path.arcTo(left, top, right, bottom, startAngle, sweepAngle, true);
        }
        fill(path, paint.spec());
    }

    public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.addRoundRect(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom), rx, ry, Path.Direction.CW);
        fill(path, paint.spec());
    }

    public void drawLine(float startX, float startY, float stopX, float stopY, @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.moveTo(startX, startY);
        path.lineTo(stopX, stopY);
        fill(path, withStyle(paint.spec(), PaintSpec.STYLE_STROKE));
    }

    public void drawLines(@NonNull float[] pts, int offset, int count, @NonNull Paint paint) {
        if ((offset | count) < 0 || offset + count > pts.length) throw new ArrayIndexOutOfBoundsException();
        Path path = mScratch;
        path.rewind();
        for (int i = offset; i + 3 < offset + count; i += 4) {
            path.moveTo(pts[i], pts[i + 1]);
            path.lineTo(pts[i + 2], pts[i + 3]);
        }
        if (!path.isEmpty()) fill(path, withStyle(paint.spec(), PaintSpec.STYLE_STROKE));
    }

    public void drawLines(@NonNull float[] pts, @NonNull Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    public void drawPoint(float x, float y, @NonNull Paint paint) {
        drawPoints(new float[] {x, y}, 0, 2, paint);
    }

    public void drawPoints(@NonNull float[] pts, int offset, int count, @NonNull Paint paint) {
        if ((offset | count) < 0 || offset + count > pts.length) throw new ArrayIndexOutOfBoundsException();
        Path path = mScratch;
        path.rewind();
        float width = paint.getStrokeWidth();
        boolean round = paint.getStrokeCap() == Paint.Cap.ROUND && width > 0;
        for (int i = offset; i + 1 < offset + count; i += 2) {
            float x = pts[i], y = pts[i + 1];
            if (width <= 0) {
                path.addRect((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(x) + 1, (float) Math.floor(y) + 1, Path.Direction.CW);
            } else if (round) {
                path.addCircle(x, y, width / 2, Path.Direction.CW);
            } else {
                path.addRect(x - width / 2, y - width / 2, x + width / 2, y + width / 2, Path.Direction.CW);
            }
        }
        if (!path.isEmpty()) fill(path, withStyle(paint.spec(), PaintSpec.STYLE_FILL));
    }

    public void drawPoints(@NonNull float[] pts, @NonNull Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    // --- Bitmaps.

    public void drawBitmap(@NonNull Bitmap bitmap, float left, float top, @Nullable Paint paint) {
        float width = bitmap.getWidth(), height = bitmap.getHeight();
        int canvasDensity = mDensity, bitmapDensity = bitmap.getDensity();
        if (canvasDensity != Bitmap.DENSITY_NONE && bitmapDensity != Bitmap.DENSITY_NONE && canvasDensity != bitmapDensity) {
            float scale = (float) canvasDensity / bitmapDensity;
            width *= scale;
            height *= scale;
        }
        drawBitmapRect(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), left, top, left + width, top + height, paint);
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull RectF dst, @Nullable Paint paint) {
        if (dst == null) throw new NullPointerException();
        if (src == null) {
            drawBitmapRect(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), dst.left, dst.top, dst.right, dst.bottom, paint);
        } else {
            drawBitmapRect(bitmap, src.left, src.top, src.right, src.bottom, dst.left, dst.top, dst.right, dst.bottom, paint);
        }
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull Rect dst, @Nullable Paint paint) {
        if (dst == null) throw new NullPointerException();
        if (src == null) {
            drawBitmapRect(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), dst.left, dst.top, dst.right, dst.bottom, paint);
        } else {
            drawBitmapRect(bitmap, src.left, src.top, src.right, src.bottom, dst.left, dst.top, dst.right, dst.bottom, paint);
        }
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @NonNull Matrix matrix, @Nullable Paint paint) {
        save();
        concat(matrix);
        drawBitmapRect(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), 0, 0, bitmap.getWidth(), bitmap.getHeight(), paint);
        restore();
    }

    @Deprecated
    public void drawBitmap(@NonNull int[] colors, int offset, int stride, float x, float y, int width, int height, boolean hasAlpha,
                           @Nullable Paint paint) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("width and height must be >= 0");
        if (width == 0 || height == 0) return;
        Bitmap bitmap = Bitmap.createBitmap(colors, offset, stride, width, height, hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        try {
            drawBitmap(bitmap, x, y, paint);
        } finally {
            bitmap.recycle();
        }
    }

    @Deprecated
    public void drawBitmap(@NonNull int[] colors, int offset, int stride, int x, int y, int width, int height, boolean hasAlpha,
                           @Nullable Paint paint) {
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height, hasAlpha, paint);
    }

    /** Like Skia's drawImageRect: the source rect is clipped to the bitmap and the destination follows. */
    private void drawBitmapRect(Bitmap bitmap, float sl, float st, float sr, float sb, float dl, float dt, float dr, float db, Paint paint) {
        if (bitmap == null) throw new NullPointerException();
        if (sr <= sl || sb <= st || dr <= dl || db <= dt) return;
        float cl = Math.max(sl, 0), ct = Math.max(st, 0), cr = Math.min(sr, bitmap.getWidth()), cb = Math.min(sb, bitmap.getHeight());
        if (cr <= cl || cb <= ct) return;
        if (cl != sl || ct != st || cr != sr || cb != sb) {
            float sx = (dr - dl) / (sr - sl), sy = (db - dt) / (sb - st);
            float nl = dl + (cl - sl) * sx, nt = dt + (ct - st) * sy, nr = dr - (sr - cr) * sx, nb = db - (sb - cb) * sy;
            dl = nl;
            dt = nt;
            dr = nr;
            db = nb;
        }
        int[] src = {(int) cl, (int) ct, (int) Math.ceil(cr), (int) Math.ceil(cb)};
        float[] dst = {dl, dt, dr, db};
        Bitmap target = target();
        CanvasState state = state();
        PaintSpec spec = specOf(paint);
        Object first = target.id() <= bitmap.id() ? target.lock : bitmap.lock;
        Object second = first == target.lock ? bitmap.lock : target.lock;
        synchronized (first) {
            synchronized (second) {
                PixelBuffer source = bitmap.pixels();
                target.pixels().drawBitmap(source, src, dst, state, spec);
                target.noteChanged();
            }
        }
    }

    // --- Text.

    public void drawText(@NonNull char[] text, int index, int count, float x, float y, @NonNull Paint paint) {
        if ((index | count | (index + count) | (text.length - index - count)) < 0) throw new IndexOutOfBoundsException();
        drawText(new String(text, index, count), x, y, paint);
    }

    public void drawText(@NonNull String text, float x, float y, @NonNull Paint paint) {
        if (text.isEmpty()) return;
        float width = -1;
        Paint.Align align = paint.getTextAlign();
        if (align != Paint.Align.LEFT || paint.isUnderlineText() || paint.isStrikeThruText()) width = paint.measureText(text);
        if (align == Paint.Align.CENTER) x -= width / 2;
        else if (align == Paint.Align.RIGHT) x -= width;
        FontSpec font = paint.fontSpec();
        PaintSpec spec = paint.spec();
        Bitmap target = target();
        CanvasState state = state();
        synchronized (target.lock) {
            target.pixels().drawText(text, x, y, font, state, spec);
            target.noteChanged();
        }
        if (paint.isUnderlineText() || paint.isStrikeThruText()) {
            PaintSpec fill = withStyle(spec, PaintSpec.STYLE_FILL);
            Path path = mScratch;
            path.rewind();
            if (paint.isUnderlineText()) {
                float top = y + paint.getUnderlinePosition();
                path.addRect(x, top, x + width, top + paint.getUnderlineThickness(), Path.Direction.CW);
            }
            if (paint.isStrikeThruText()) {
                float top = y + paint.getStrikeThruPosition();
                path.addRect(x, top, x + width, top + paint.getStrikeThruThickness(), Path.Direction.CW);
            }
            fill(path, fill);
        }
    }

    public void drawText(@NonNull String text, int start, int end, float x, float y, @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        drawText(text.substring(start, end), x, y, paint);
    }

    public void drawText(@NonNull CharSequence text, int start, int end, float x, float y, @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        drawText(text.subSequence(start, end).toString(), x, y, paint);
    }

    public void drawTextRun(@NonNull char[] text, int index, int count, int contextIndex, int contextCount, float x, float y, boolean isRtl,
                            @NonNull Paint paint) {
        drawText(text, index, count, x, y, paint);
    }

    public void drawTextRun(@NonNull CharSequence text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean isRtl,
                            @NonNull Paint paint) {
        drawText(text, start, end, x, y, paint);
    }

    public void drawPicture(@NonNull Picture picture) {
        throw new UnsupportedOperationException("Canvas.drawPicture is not supported");
    }

    public void drawPicture(@NonNull Picture picture, @NonNull RectF dst) {
        throw new UnsupportedOperationException("Canvas.drawPicture is not supported");
    }

    public void drawPicture(@NonNull Picture picture, @NonNull Rect dst) {
        throw new UnsupportedOperationException("Canvas.drawPicture is not supported");
    }

    public void drawDoubleRoundRect(@NonNull RectF outer, float outerRx, float outerRy, @NonNull RectF inner, float innerRx, float innerRy,
                                    @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(outer, outerRx, outerRy, Path.Direction.CW);
        path.addRoundRect(inner, innerRx, innerRy, Path.Direction.CW);
        fill(path, paint.spec());
        path.setFillType(Path.FillType.WINDING);
    }

    public void drawDoubleRoundRect(@NonNull RectF outer, @NonNull float[] outerRadii, @NonNull RectF inner, @NonNull float[] innerRadii,
                                    @NonNull Paint paint) {
        Path path = mScratch;
        path.rewind();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(outer, outerRadii, Path.Direction.CW);
        path.addRoundRect(inner, innerRadii, Path.Direction.CW);
        fill(path, paint.spec());
        path.setFillType(Path.FillType.WINDING);
    }

    @Deprecated
    public void drawPosText(@NonNull char[] text, int index, int count, @NonNull float[] pos, @NonNull Paint paint) {
        if (index < 0 || index + count > text.length || count * 2 > pos.length) throw new IndexOutOfBoundsException();
        for (int i = 0; i < count; i++) drawText(String.valueOf(text[index + i]), pos[i * 2], pos[i * 2 + 1], paint);
    }

    @Deprecated
    public void drawPosText(@NonNull String text, @NonNull float[] pos, @NonNull Paint paint) {
        drawPosText(text.toCharArray(), 0, text.length(), pos, paint);
    }

    public void drawTextOnPath(@NonNull char[] text, int index, int count, @NonNull Path path, float hOffset, float vOffset, @NonNull Paint paint) {
        throw new UnsupportedOperationException("Canvas.drawTextOnPath is not supported");
    }

    public void drawTextOnPath(@NonNull String text, @NonNull Path path, float hOffset, float vOffset, @NonNull Paint paint) {
        throw new UnsupportedOperationException("Canvas.drawTextOnPath is not supported");
    }

    public void drawTextRun(@NonNull android.graphics.text.MeasuredText text, int start, int end, int contextStart, int contextEnd, float x, float y,
                            boolean isRtl, @NonNull Paint paint) {
        throw new UnsupportedOperationException("Canvas.drawTextRun(MeasuredText) is not supported");
    }

    public void drawBitmapMesh(@NonNull Bitmap bitmap, int meshWidth, int meshHeight, @NonNull float[] verts, int vertOffset,
                               @Nullable int[] colors, int colorOffset, @Nullable Paint paint) {
        throw new UnsupportedOperationException("Canvas.drawBitmapMesh is not supported");
    }

    public void drawVertices(@NonNull VertexMode mode, int vertexCount, @NonNull float[] verts, int vertOffset, @Nullable float[] texs, int texOffset,
                             @Nullable int[] colors, int colorOffset, @Nullable short[] indices, int indexOffset, int indexCount,
                             @NonNull Paint paint) {
        throw new UnsupportedOperationException("Canvas.drawVertices is not supported");
    }

    public void drawRenderNode(@NonNull RenderNode renderNode) {
        throw new UnsupportedOperationException("Canvas.drawRenderNode is not supported");
    }

    public void enableZ() {}

    public void disableZ() {}
}
