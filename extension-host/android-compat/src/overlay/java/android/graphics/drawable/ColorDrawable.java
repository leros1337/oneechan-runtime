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
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"): a color
 * filling its bounds, drawn through android.graphics.Canvas; tint lists are not supported.
 */

package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;

public class ColorDrawable extends Drawable {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mBaseColor;
    private int mUseColor;
    private ColorFilter mColorFilter;

    public ColorDrawable() {
        this(0);
    }

    public ColorDrawable(int color) {
        setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        if ((mUseColor >>> 24) != 0 || mColorFilter != null) {
            mPaint.setColor(mUseColor);
            mPaint.setColorFilter(mColorFilter);
            canvas.drawRect(getBounds(), mPaint);
        }
    }

    public int getColor() {
        return mUseColor;
    }

    public void setColor(int color) {
        if (mBaseColor != color || mUseColor != color) {
            mBaseColor = mUseColor = color;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mUseColor >>> 24;
    }

    @Override
    public void setAlpha(int alpha) {
        alpha += alpha >> 7;
        int baseAlpha = mBaseColor >>> 24;
        int useAlpha = baseAlpha * alpha >> 8;
        int useColor = (mBaseColor << 8 >>> 8) | (useAlpha << 24);
        if (mUseColor != useColor) {
            mUseColor = useColor;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mColorFilter = colorFilter;
    }

    @Override
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    @Override
    public int getOpacity() {
        if (mColorFilter != null) return PixelFormat.TRANSLUCENT;
        switch (mUseColor >>> 24) {
            case 255:
                return PixelFormat.OPAQUE;
            case 0:
                return PixelFormat.TRANSPARENT;
            default:
                return PixelFormat.TRANSLUCENT;
        }
    }
}
