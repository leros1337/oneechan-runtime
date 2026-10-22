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
 * Drawable base class reduced to its state (bounds, alpha, level, state, callback, tint) so that
 * extensions defining drawables (login dialog icons) load and can draw onto a Canvas. Resources,
 * themes, XML inflation and the framework's tint filters are not supported.
 */

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import java.io.InputStream;
import java.lang.ref.WeakReference;

public abstract class Drawable {
    private static final Rect ZERO_BOUNDS_RECT = new Rect();
    private static final int[] EMPTY_STATE = new int[0];

    private int[] mStateSet = EMPTY_STATE;
    private int mLevel = 0;
    private int mChangingConfigurations = 0;
    private Rect mBounds = ZERO_BOUNDS_RECT;
    private WeakReference<Callback> mCallback = null;
    private boolean mVisible = true;
    private int mLayoutDirection;
    private boolean mAutoMirrored;
    private boolean mFilterBitmap = true;

    public Drawable() {}

    public abstract void draw(Canvas canvas);

    public void setBounds(int left, int top, int right, int bottom) {
        Rect oldBounds = mBounds;
        if (oldBounds == ZERO_BOUNDS_RECT) oldBounds = mBounds = new Rect();
        if (oldBounds.left != left || oldBounds.top != top || oldBounds.right != right || oldBounds.bottom != bottom) {
            mBounds.set(left, top, right, bottom);
            onBoundsChange(mBounds);
        }
    }

    public void setBounds(Rect bounds) {
        setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    public final void copyBounds(Rect bounds) {
        bounds.set(mBounds);
    }

    public final Rect copyBounds() {
        return new Rect(mBounds);
    }

    public final Rect getBounds() {
        if (mBounds == ZERO_BOUNDS_RECT) mBounds = new Rect();
        return mBounds;
    }

    public Rect getDirtyBounds() {
        return getBounds();
    }

    public void setChangingConfigurations(int configs) {
        mChangingConfigurations = configs;
    }

    public int getChangingConfigurations() {
        return mChangingConfigurations;
    }

    @Deprecated
    public void setDither(boolean dither) {}

    public void setFilterBitmap(boolean filter) {
        mFilterBitmap = filter;
    }

    public boolean isFilterBitmap() {
        return mFilterBitmap;
    }

    public interface Callback {
        void invalidateDrawable(Drawable who);

        void scheduleDrawable(Drawable who, Runnable what, long when);

        void unscheduleDrawable(Drawable who, Runnable what);
    }

    public final void setCallback(Callback cb) {
        mCallback = cb != null ? new WeakReference<>(cb) : null;
    }

    public Callback getCallback() {
        return mCallback != null ? mCallback.get() : null;
    }

    public void invalidateSelf() {
        Callback callback = getCallback();
        if (callback != null) callback.invalidateDrawable(this);
    }

    public void scheduleSelf(Runnable what, long when) {
        Callback callback = getCallback();
        if (callback != null) callback.scheduleDrawable(this, what, when);
    }

    public void unscheduleSelf(Runnable what) {
        Callback callback = getCallback();
        if (callback != null) callback.unscheduleDrawable(this, what);
    }

    public int getLayoutDirection() {
        return mLayoutDirection;
    }

    public final boolean setLayoutDirection(int layoutDirection) {
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            return onLayoutDirectionChanged(layoutDirection);
        }
        return false;
    }

    public boolean onLayoutDirectionChanged(int layoutDirection) {
        return false;
    }

    public abstract void setAlpha(int alpha);

    public int getAlpha() {
        return 0xFF;
    }

    public abstract void setColorFilter(ColorFilter colorFilter);

    @Deprecated
    public void setColorFilter(int color, PorterDuff.Mode mode) {}

    public ColorFilter getColorFilter() {
        return null;
    }

    public void clearColorFilter() {
        setColorFilter(null);
    }

    public void setTint(int tintColor) {}

    public void setTintList(ColorStateList tint) {}

    public void setTintMode(PorterDuff.Mode tintMode) {}

    public void setTintBlendMode(BlendMode blendMode) {}

    public void setHotspot(float x, float y) {}

    public void setHotspotBounds(int left, int top, int right, int bottom) {}

    public void getHotspotBounds(Rect outRect) {
        outRect.set(getBounds());
    }

    public boolean isProjected() {
        return false;
    }

    public boolean isStateful() {
        return false;
    }

    public boolean hasFocusStateSpecified() {
        return false;
    }

    public boolean setState(final int[] stateSet) {
        if (!java.util.Arrays.equals(mStateSet, stateSet)) {
            mStateSet = stateSet;
            return onStateChange(stateSet);
        }
        return false;
    }

    public int[] getState() {
        return mStateSet;
    }

    public void jumpToCurrentState() {}

    public Drawable getCurrent() {
        return this;
    }

    public final boolean setLevel(int level) {
        if (mLevel != level) {
            mLevel = level;
            return onLevelChange(level);
        }
        return false;
    }

    public final int getLevel() {
        return mLevel;
    }

    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = mVisible != visible;
        if (changed) {
            mVisible = visible;
            invalidateSelf();
        }
        return changed;
    }

    public final boolean isVisible() {
        return mVisible;
    }

    public void setAutoMirrored(boolean mirrored) {
        mAutoMirrored = mirrored;
    }

    public boolean isAutoMirrored() {
        return mAutoMirrored;
    }

    @Deprecated
    public abstract int getOpacity();

    public static int resolveOpacity(int op1, int op2) {
        if (op1 == op2) return op1;
        if (op1 == PixelFormat.UNKNOWN || op2 == PixelFormat.UNKNOWN) return PixelFormat.UNKNOWN;
        if (op1 == PixelFormat.TRANSLUCENT || op2 == PixelFormat.TRANSLUCENT) return PixelFormat.TRANSLUCENT;
        if (op1 == PixelFormat.TRANSPARENT || op2 == PixelFormat.TRANSPARENT) return PixelFormat.TRANSPARENT;
        return PixelFormat.OPAQUE;
    }

    protected boolean onStateChange(int[] state) {
        return false;
    }

    protected boolean onLevelChange(int level) {
        return false;
    }

    protected void onBoundsChange(Rect bounds) {}

    public int getIntrinsicWidth() {
        return -1;
    }

    public int getIntrinsicHeight() {
        return -1;
    }

    public int getMinimumWidth() {
        int intrinsicWidth = getIntrinsicWidth();
        return intrinsicWidth > 0 ? intrinsicWidth : 0;
    }

    public int getMinimumHeight() {
        int intrinsicHeight = getIntrinsicHeight();
        return intrinsicHeight > 0 ? intrinsicHeight : 0;
    }

    public boolean getPadding(Rect padding) {
        padding.set(0, 0, 0, 0);
        return false;
    }

    public Drawable mutate() {
        return this;
    }

    public void inflate(Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs) throws java.io.IOException,
            org.xmlpull.v1.XmlPullParserException {
        inflate(r, parser, attrs, null);
    }

    /** Drawables from XML resources are not supported. */
    public void inflate(Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs, Resources.Theme theme)
            throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        throw new UnsupportedOperationException("Drawable.inflate is not supported");
    }

    public void applyTheme(Resources.Theme t) {}

    public boolean canApplyTheme() {
        return false;
    }

    public void getOutline(android.graphics.Outline outline) {}

    /** AOSP returns Insets.NONE; the Insets class is a stub here. */
    public android.graphics.Insets getOpticalInsets() {
        return null;
    }

    public android.graphics.Region getTransparentRegion() {
        return null;
    }

    public ConstantState getConstantState() {
        return null;
    }

    /** Decoding drawables from streams needs BitmapDrawable, which is not supported: always null. */
    public static Drawable createFromStream(InputStream is, String srcName) {
        return null;
    }

    public abstract static class ConstantState {
        public abstract Drawable newDrawable();

        public Drawable newDrawable(Resources res) {
            return newDrawable();
        }

        public Drawable newDrawable(Resources res, Resources.Theme theme) {
            return newDrawable(res);
        }

        public abstract int getChangingConfigurations();

        public boolean canApplyTheme() {
            return false;
        }
    }
}
