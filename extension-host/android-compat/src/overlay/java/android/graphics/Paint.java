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
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's Paint. Upstream keeps its text state
 * in java.awt.Font (unusable on iOS) and leaves most setters as "Stub!"; this version holds the
 * AOSP Paint state as plain fields (flags, color, style, stroke cap/join/miter, color filter,
 * xfermode, typeface, text size/scale/skew/align) and measures text through the active
 * oneechan.host.platform.GraphicsBackend. Shaders, mask filters, path effects and shadow layers
 * are kept but not rendered.
 */

package android.graphics;

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.LocaleList;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.Objects;
import oneechan.host.platform.BlendModes;
import oneechan.host.platform.FontSpec;
import oneechan.host.platform.Graphics;
import oneechan.host.platform.PaintSpec;

public class Paint {
    @Retention(RetentionPolicy.SOURCE)
    public @interface PaintFlag {}

    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int FILTER_BITMAP_FLAG = 0x02;
    public static final int DITHER_FLAG = 0x04;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int LINEAR_TEXT_FLAG = 0x40;
    public static final int SUBPIXEL_TEXT_FLAG = 0x80;
    public static final int DEV_KERN_TEXT_FLAG = 0x100;
    public static final int LCD_RENDER_TEXT_FLAG = 0x200;
    public static final int EMBEDDED_BITMAP_TEXT_FLAG = 0x400;
    public static final int AUTO_HINTING_TEXT_FLAG = 0x800;
    public static final int VERTICAL_TEXT_FLAG = 0x1000;
    public static final int TEXT_RUN_FLAG_LEFT_EDGE = 0x2000;
    public static final int TEXT_RUN_FLAG_RIGHT_EDGE = 0x4000;

    static final int HIDDEN_DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG | FILTER_BITMAP_FLAG;

    public static final int HINTING_OFF = 0x0;
    public static final int HINTING_ON = 0x1;

    public static final int BIDI_LTR = 0x0;
    public static final int BIDI_RTL = 0x1;
    public static final int BIDI_DEFAULT_LTR = 0x2;
    public static final int BIDI_DEFAULT_RTL = 0x3;
    public static final int BIDI_FORCE_LTR = 0x4;
    public static final int BIDI_FORCE_RTL = 0x5;
    private static final int BIDI_MAX_FLAG_VALUE = BIDI_FORCE_RTL;
    private static final int BIDI_FLAG_MASK = 0x7;

    public static final int DIRECTION_LTR = 0;
    public static final int DIRECTION_RTL = 1;

    @Retention(RetentionPolicy.SOURCE)
    public @interface CursorOption {}

    public static final int CURSOR_AFTER = 0;
    public static final int CURSOR_AT_OR_AFTER = 1;
    public static final int CURSOR_BEFORE = 2;
    public static final int CURSOR_AT_OR_BEFORE = 3;
    public static final int CURSOR_AT = 4;

    @Retention(RetentionPolicy.SOURCE)
    public @interface StartHyphenEdit {}

    public static final int START_HYPHEN_EDIT_NO_EDIT = 0x00;
    public static final int START_HYPHEN_EDIT_INSERT_HYPHEN = 0x01;
    public static final int START_HYPHEN_EDIT_INSERT_ZWJ = 0x02;

    @Retention(RetentionPolicy.SOURCE)
    public @interface EndHyphenEdit {}

    public static final int END_HYPHEN_EDIT_NO_EDIT = 0x00;
    public static final int END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN = 0x01;
    public static final int END_HYPHEN_EDIT_INSERT_HYPHEN = 0x02;
    public static final int END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN = 0x03;
    public static final int END_HYPHEN_EDIT_INSERT_MAQAF = 0x04;
    public static final int END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN = 0x05;
    public static final int END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN = 0x06;

    public enum Style {
        FILL(0),
        STROKE(1),
        FILL_AND_STROKE(2);

        Style(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    public enum Cap {
        BUTT(0),
        ROUND(1),
        SQUARE(2);

        Cap(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    public enum Join {
        MITER(0),
        ROUND(1),
        BEVEL(2);

        Join(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    public enum Align {
        LEFT(0),
        CENTER(1),
        RIGHT(2);

        Align(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        final int nativeInt;
    }

    private int mFlags;
    @ColorInt private int mColor;
    private Style mStyle;
    private float mStrokeWidth;
    private float mStrokeMiter;
    private Cap mStrokeCap;
    private Join mStrokeJoin;
    private ColorFilter mColorFilter;
    private MaskFilter mMaskFilter;
    private PathEffect mPathEffect;
    private Shader mShader;
    private Xfermode mXfermode;
    /** Skia blend code from the xfermode or blend mode; SRC_OVER by default. */
    private int mBlend;
    private Typeface mTypeface;
    private float mTextSize;
    private float mTextScaleX;
    private float mTextSkewX;
    private float mLetterSpacing;
    private float mWordSpacing;
    private Align mTextAlign;
    private Locale mLocale;
    private LocaleList mLocales;
    private int mHinting;
    private boolean mElegantTextHeight;
    private String mFontFeatureSettings;
    private String mFontVariationSettings;
    private float mShadowLayerRadius;
    private float mShadowLayerDx;
    private float mShadowLayerDy;
    @ColorInt private int mShadowLayerColor;
    private int mStartHyphenEdit;
    private int mEndHyphenEdit;
    public int mBidiFlags = BIDI_DEFAULT_LTR;

    private PaintSpec mSpec;

    public Paint() {
        this(ANTI_ALIAS_FLAG);
    }

    public Paint(int flags) {
        reset();
        mFlags = flags | HIDDEN_DEFAULT_PAINT_FLAGS;
    }

    public Paint(Paint paint) {
        set(paint);
    }

    public void reset() {
        mFlags = HIDDEN_DEFAULT_PAINT_FLAGS | ANTI_ALIAS_FLAG;
        mColor = Color.BLACK;
        mStyle = Style.FILL;
        mStrokeWidth = 0.0f;
        mStrokeMiter = 4.0f;
        mStrokeCap = Cap.BUTT;
        mStrokeJoin = Join.MITER;
        mColorFilter = null;
        mMaskFilter = null;
        mPathEffect = null;
        mShader = null;
        mXfermode = null;
        mBlend = BlendModes.SRC_OVER;
        mTypeface = Typeface.DEFAULT;
        mTextSize = 12.0f;
        mTextScaleX = 1.0f;
        mTextSkewX = 0.0f;
        mLetterSpacing = 0.0f;
        mWordSpacing = 0.0f;
        mTextAlign = Align.LEFT;
        mLocale = null;
        mLocales = null;
        mHinting = HINTING_ON;
        mElegantTextHeight = false;
        mFontFeatureSettings = null;
        mFontVariationSettings = null;
        mShadowLayerRadius = 0.0f;
        mShadowLayerDx = 0.0f;
        mShadowLayerDy = 0.0f;
        mShadowLayerColor = 0;
        mStartHyphenEdit = START_HYPHEN_EDIT_NO_EDIT;
        mEndHyphenEdit = END_HYPHEN_EDIT_NO_EDIT;
        mBidiFlags = BIDI_DEFAULT_LTR;
        mSpec = null;
    }

    public void set(Paint src) {
        if (this == src) return;
        mFlags = src.mFlags;
        mColor = src.mColor;
        mStyle = src.mStyle;
        mStrokeWidth = src.mStrokeWidth;
        mStrokeMiter = src.mStrokeMiter;
        mStrokeCap = src.mStrokeCap;
        mStrokeJoin = src.mStrokeJoin;
        mColorFilter = src.mColorFilter;
        mMaskFilter = src.mMaskFilter;
        mPathEffect = src.mPathEffect;
        mShader = src.mShader;
        mXfermode = src.mXfermode;
        mBlend = src.mBlend;
        mTypeface = src.mTypeface;
        mTextSize = src.mTextSize;
        mTextScaleX = src.mTextScaleX;
        mTextSkewX = src.mTextSkewX;
        mLetterSpacing = src.mLetterSpacing;
        mWordSpacing = src.mWordSpacing;
        mTextAlign = src.mTextAlign;
        mLocale = src.mLocale;
        mLocales = src.mLocales;
        mHinting = src.mHinting;
        mElegantTextHeight = src.mElegantTextHeight;
        mFontFeatureSettings = src.mFontFeatureSettings;
        mFontVariationSettings = src.mFontVariationSettings;
        mShadowLayerRadius = src.mShadowLayerRadius;
        mShadowLayerDx = src.mShadowLayerDx;
        mShadowLayerDy = src.mShadowLayerDy;
        mShadowLayerColor = src.mShadowLayerColor;
        mStartHyphenEdit = src.mStartHyphenEdit;
        mEndHyphenEdit = src.mEndHyphenEdit;
        mBidiFlags = src.mBidiFlags;
        mSpec = null;
    }

    public void setCompatibilityScaling(float factor) {}

    public int getBidiFlags() {
        return mBidiFlags;
    }

    public void setBidiFlags(int flags) {
        flags &= BIDI_FLAG_MASK;
        if (flags > BIDI_MAX_FLAG_VALUE) throw new IllegalArgumentException("unknown bidi flag: " + flags);
        mBidiFlags = flags;
    }

    public int getFlags() {
        return mFlags;
    }

    public void setFlags(int flags) {
        mFlags = flags;
        mSpec = null;
    }

    private void setFlag(int flag, boolean on) {
        setFlags(on ? (mFlags | flag) : (mFlags & ~flag));
    }

    public int getHinting() {
        return mHinting;
    }

    public void setHinting(int mode) {
        mHinting = mode;
    }

    public final boolean isAntiAlias() {
        return (mFlags & ANTI_ALIAS_FLAG) != 0;
    }

    public void setAntiAlias(boolean aa) {
        setFlag(ANTI_ALIAS_FLAG, aa);
    }

    public final boolean isDither() {
        return (mFlags & DITHER_FLAG) != 0;
    }

    public void setDither(boolean dither) {
        setFlag(DITHER_FLAG, dither);
    }

    public final boolean isLinearText() {
        return (mFlags & LINEAR_TEXT_FLAG) != 0;
    }

    public void setLinearText(boolean linearText) {
        setFlag(LINEAR_TEXT_FLAG, linearText);
    }

    public final boolean isSubpixelText() {
        return (mFlags & SUBPIXEL_TEXT_FLAG) != 0;
    }

    public void setSubpixelText(boolean subpixelText) {
        setFlag(SUBPIXEL_TEXT_FLAG, subpixelText);
    }

    public final boolean isUnderlineText() {
        return (mFlags & UNDERLINE_TEXT_FLAG) != 0;
    }

    public void setUnderlineText(boolean underlineText) {
        setFlag(UNDERLINE_TEXT_FLAG, underlineText);
    }

    public float getUnderlinePosition() {
        return mTextSize / 9.0f;
    }

    public float getUnderlineThickness() {
        return mTextSize / 18.0f;
    }

    public final boolean isStrikeThruText() {
        return (mFlags & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    public void setStrikeThruText(boolean strikeThruText) {
        setFlag(STRIKE_THRU_TEXT_FLAG, strikeThruText);
    }

    public float getStrikeThruPosition() {
        return -mTextSize * 0.25f;
    }

    public float getStrikeThruThickness() {
        return mTextSize / 18.0f;
    }

    public final boolean isFakeBoldText() {
        return (mFlags & FAKE_BOLD_TEXT_FLAG) != 0;
    }

    public void setFakeBoldText(boolean fakeBoldText) {
        setFlag(FAKE_BOLD_TEXT_FLAG, fakeBoldText);
    }

    public final boolean isFilterBitmap() {
        return (mFlags & FILTER_BITMAP_FLAG) != 0;
    }

    public void setFilterBitmap(boolean filter) {
        setFlag(FILTER_BITMAP_FLAG, filter);
    }

    public Style getStyle() {
        return mStyle;
    }

    public void setStyle(Style style) {
        mStyle = style;
        mSpec = null;
    }

    @ColorInt
    public int getColor() {
        return mColor;
    }

    @ColorLong
    public long getColorLong() {
        return Color.pack(mColor);
    }

    public void setColor(@ColorInt int color) {
        mColor = color;
        mSpec = null;
    }

    public void setColor(@ColorLong long color) {
        setColor(Color.toArgb(color));
    }

    public int getAlpha() {
        return mColor >>> 24;
    }

    public void setAlpha(int a) {
        setColor(((a & 0xff) << 24) | (mColor & 0xffffff));
    }

    public void setARGB(int a, int r, int g, int b) {
        setColor(((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff));
    }

    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    public void setStrokeWidth(float width) {
        if (width >= 0) {
            mStrokeWidth = width;
            mSpec = null;
        }
    }

    public float getStrokeMiter() {
        return mStrokeMiter;
    }

    public void setStrokeMiter(float miter) {
        if (miter >= 0) {
            mStrokeMiter = miter;
            mSpec = null;
        }
    }

    public Cap getStrokeCap() {
        return mStrokeCap;
    }

    public void setStrokeCap(Cap cap) {
        mStrokeCap = cap;
        mSpec = null;
    }

    public Join getStrokeJoin() {
        return mStrokeJoin;
    }

    public void setStrokeJoin(Join join) {
        mStrokeJoin = join;
        mSpec = null;
    }

    public boolean getFillPath(Path src, Path dst) {
        dst.set(src);
        return mStyle != Style.STROKE;
    }

    public Shader getShader() {
        return mShader;
    }

    public Shader setShader(Shader shader) {
        mShader = shader;
        return shader;
    }

    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    public ColorFilter setColorFilter(ColorFilter filter) {
        mColorFilter = filter;
        mSpec = null;
        return filter;
    }

    public Xfermode getXfermode() {
        return mXfermode;
    }

    public Xfermode setXfermode(Xfermode xfermode) {
        mXfermode = xfermode;
        mBlend = xfermode instanceof PorterDuffXfermode pd ? BlendModes.ofPorterDuff(pd.getMode().name()) : BlendModes.SRC_OVER;
        mSpec = null;
        return xfermode;
    }

    @Nullable
    public BlendMode getBlendMode() {
        return null;
    }

    public void setBlendMode(@Nullable BlendMode blendmode) {
        mXfermode = null;
        mBlend = blendmode == null ? BlendModes.SRC_OVER : BlendModes.ofPorterDuff(blendmode.name());
        mSpec = null;
    }

    /** For the framework: a blend mode by its Skia code. */
    void setXfermodeBlend(int blend) {
        mXfermode = null;
        mBlend = blend;
        mSpec = null;
    }

    public PathEffect getPathEffect() {
        return mPathEffect;
    }

    public PathEffect setPathEffect(PathEffect effect) {
        mPathEffect = effect;
        return effect;
    }

    public MaskFilter getMaskFilter() {
        return mMaskFilter;
    }

    public MaskFilter setMaskFilter(MaskFilter maskfilter) {
        mMaskFilter = maskfilter;
        return maskfilter;
    }

    public Typeface getTypeface() {
        return mTypeface;
    }

    public Typeface setTypeface(Typeface typeface) {
        mTypeface = typeface;
        return typeface;
    }

    public void setShadowLayer(float radius, float dx, float dy, @ColorInt int shadowColor) {
        mShadowLayerRadius = radius;
        mShadowLayerDx = dx;
        mShadowLayerDy = dy;
        mShadowLayerColor = shadowColor;
    }

    public void setShadowLayer(float radius, float dx, float dy, @ColorLong long shadowColor) {
        setShadowLayer(radius, dx, dy, Color.toArgb(shadowColor));
    }

    public void clearShadowLayer() {
        setShadowLayer(0, 0, 0, 0);
    }

    public boolean hasShadowLayer() {
        return mShadowLayerRadius > 0;
    }

    public float getShadowLayerRadius() {
        return mShadowLayerRadius;
    }

    public float getShadowLayerDx() {
        return mShadowLayerDx;
    }

    public float getShadowLayerDy() {
        return mShadowLayerDy;
    }

    public @ColorInt int getShadowLayerColor() {
        return mShadowLayerColor;
    }

    public @ColorLong long getShadowLayerColorLong() {
        return Color.pack(mShadowLayerColor);
    }

    public Align getTextAlign() {
        return mTextAlign;
    }

    public void setTextAlign(Align align) {
        if (align == null) throw new NullPointerException();
        mTextAlign = align;
    }

    @NonNull
    public Locale getTextLocale() {
        return mLocale != null ? mLocale : Locale.getDefault();
    }

    @Nullable
    public LocaleList getTextLocales() {
        return mLocales;
    }

    public void setTextLocale(@NonNull Locale locale) {
        if (locale == null) throw new IllegalArgumentException("locale cannot be null");
        mLocale = locale;
    }

    public void setTextLocales(@NonNull LocaleList locales) {
        if (locales == null) throw new IllegalArgumentException("locales cannot be null or empty");
        mLocales = locales;
    }

    @Deprecated
    public boolean isElegantTextHeight() {
        return mElegantTextHeight;
    }

    @Deprecated
    public void setElegantTextHeight(boolean elegant) {
        mElegantTextHeight = elegant;
    }

    public float getTextSize() {
        return mTextSize;
    }

    public void setTextSize(float textSize) {
        if (textSize > 0) mTextSize = textSize;
    }

    public float getTextScaleX() {
        return mTextScaleX;
    }

    public void setTextScaleX(float scaleX) {
        mTextScaleX = scaleX;
    }

    public float getTextSkewX() {
        return mTextSkewX;
    }

    public void setTextSkewX(float skewX) {
        mTextSkewX = skewX;
    }

    public float getLetterSpacing() {
        return mLetterSpacing;
    }

    public void setLetterSpacing(float letterSpacing) {
        mLetterSpacing = letterSpacing;
    }

    public float getWordSpacing() {
        return mWordSpacing;
    }

    public void setWordSpacing(float wordSpacing) {
        mWordSpacing = wordSpacing;
    }

    public String getFontFeatureSettings() {
        return mFontFeatureSettings;
    }

    public void setFontFeatureSettings(String settings) {
        mFontFeatureSettings = settings == null || settings.isEmpty() ? null : settings;
    }

    public String getFontVariationSettings() {
        return mFontVariationSettings;
    }

    public boolean setFontVariationSettings(String fontVariationSettings) {
        mFontVariationSettings = fontVariationSettings == null || fontVariationSettings.isEmpty() ? null : fontVariationSettings;
        return true;
    }

    public boolean setFontVariationSettings(String fontVariationSettings, int wghtAdjust) {
        return setFontVariationSettings(fontVariationSettings);
    }

    public @StartHyphenEdit int getStartHyphenEdit() {
        return mStartHyphenEdit;
    }

    public @EndHyphenEdit int getEndHyphenEdit() {
        return mEndHyphenEdit;
    }

    public void setStartHyphenEdit(@StartHyphenEdit int startHyphen) {
        mStartHyphenEdit = startHyphen;
    }

    public void setEndHyphenEdit(@EndHyphenEdit int endHyphen) {
        mEndHyphenEdit = endHyphen;
    }

    // --- Font metrics and measurement (graphics backend).

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
        public float leading;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FontMetrics that)) return false;
            return that.top == top && that.ascent == ascent && that.descent == descent && that.bottom == bottom && that.leading == leading;
        }

        @Override
        public int hashCode() {
            return Objects.hash(top, ascent, descent, bottom, leading);
        }

        @Override
        public String toString() {
            return "FontMetrics{top=" + top + ", ascent=" + ascent + ", descent=" + descent + ", bottom=" + bottom + ", leading=" + leading + '}';
        }
    }

    public static class FontMetricsInt {
        public int top;
        public int ascent;
        public int descent;
        public int bottom;
        public int leading;

        public void set(@NonNull FontMetricsInt fontMetricsInt) {
            top = fontMetricsInt.top;
            ascent = fontMetricsInt.ascent;
            descent = fontMetricsInt.descent;
            bottom = fontMetricsInt.bottom;
            leading = fontMetricsInt.leading;
        }

        public void set(@NonNull FontMetrics fontMetrics) {
            top = (int) Math.floor(fontMetrics.top);
            ascent = Math.round(fontMetrics.ascent);
            descent = Math.round(fontMetrics.descent);
            bottom = (int) Math.ceil(fontMetrics.bottom);
            leading = Math.round(fontMetrics.leading);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FontMetricsInt that)) return false;
            return that.top == top && that.ascent == ascent && that.descent == descent && that.bottom == bottom && that.leading == leading;
        }

        @Override
        public int hashCode() {
            return Objects.hash(top, ascent, descent, bottom, leading);
        }

        @Override
        public String toString() {
            return "FontMetricsInt: top=" + top + " ascent=" + ascent + " descent=" + descent + " bottom=" + bottom + " leading=" + leading;
        }
    }

    public static final class RunInfo {
        private int mClusterCount = 0;

        public int getClusterCount() {
            return mClusterCount;
        }

        public void setClusterCount(int clusterCount) {
            mClusterCount = clusterCount;
        }
    }

    /** The backend's font for this paint. */
    FontSpec fontSpec() {
        Typeface typeface = mTypeface != null ? mTypeface : Typeface.DEFAULT;
        return typeface.spec(mTextSize, mTextScaleX, mTextSkewX, isFakeBoldText());
    }

    public float ascent() {
        return getFontMetrics().ascent;
    }

    public float descent() {
        return getFontMetrics().descent;
    }

    public float getFontMetrics(FontMetrics metrics) {
        float[] out = new float[5];
        Graphics.backend().fontMetrics(fontSpec(), out);
        if (metrics != null) {
            metrics.top = out[0];
            metrics.ascent = out[1];
            metrics.descent = out[2];
            metrics.bottom = out[3];
            metrics.leading = out[4];
        }
        return out[2] - out[1] + out[4];
    }

    public FontMetrics getFontMetrics() {
        FontMetrics fm = new FontMetrics();
        getFontMetrics(fm);
        return fm;
    }

    public void getFontMetricsForLocale(@NonNull FontMetrics metrics) {
        getFontMetrics(metrics);
    }

    public int getFontMetricsInt(FontMetricsInt fmi) {
        FontMetrics fm = new FontMetrics();
        getFontMetrics(fm);
        FontMetricsInt target = fmi != null ? fmi : new FontMetricsInt();
        target.set(fm);
        return target.descent - target.ascent + target.leading;
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fm = new FontMetricsInt();
        getFontMetricsInt(fm);
        return fm;
    }

    public void getFontMetricsInt(@NonNull CharSequence text, int start, int count, int contextStart, int contextCount, boolean isRtl,
                                  @NonNull FontMetricsInt outMetrics) {
        getFontMetricsInt(outMetrics);
    }

    public void getFontMetricsInt(@NonNull char[] text, int start, int count, int contextStart, int contextCount, boolean isRtl,
                                  @NonNull FontMetricsInt outMetrics) {
        getFontMetricsInt(outMetrics);
    }

    public float getFontSpacing() {
        return getFontMetrics(null);
    }

    public float measureText(char[] text, int index, int count) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if ((index | count) < 0 || index + count > text.length) throw new ArrayIndexOutOfBoundsException();
        return measure(new String(text, index, count));
    }

    public float measureText(String text, int start, int end) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        return measure(text.substring(start, end));
    }

    public float measureText(String text) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        return measure(text);
    }

    public float measureText(CharSequence text, int start, int end) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        return measure(text.subSequence(start, end).toString());
    }

    private float measure(String text) {
        if (text.isEmpty()) return 0;
        float width = Graphics.backend().measureText(fontSpec(), text);
        if (mLetterSpacing != 0) width += mLetterSpacing * mTextSize * text.codePointCount(0, text.length());
        if (mWordSpacing != 0) width += mWordSpacing * countSpaces(text);
        return width;
    }

    private static int countSpaces(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ' ') n++;
        return n;
    }

    public int breakText(char[] text, int index, int count, float maxWidth, float[] measuredWidth) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if (index < 0 || text.length - index < Math.abs(count)) throw new ArrayIndexOutOfBoundsException();
        String s = count >= 0 ? new String(text, index, count) : new String(text, index + count, -count);
        return breakText(s, count >= 0, maxWidth, measuredWidth);
    }

    public int breakText(CharSequence text, int start, int end, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        return breakText(text.subSequence(start, end).toString(), measureForwards, maxWidth, measuredWidth);
    }

    /** How many characters fit in maxWidth (measured from the front or the back). */
    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        int n = text.length();
        int lo = 0, hi = n;
        float fitWidth = 0;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String part = measureForwards ? text.substring(0, mid) : text.substring(n - mid);
            float w = measure(part);
            if (w <= maxWidth) {
                lo = mid;
                fitWidth = w;
            } else {
                hi = mid - 1;
            }
        }
        if (measuredWidth != null && measuredWidth.length > 0) measuredWidth[0] = fitWidth;
        return lo;
    }

    public int getTextWidths(char[] text, int index, int count, float[] widths) {
        return getTextWidths(new String(text, index, count), 0, count, widths);
    }

    public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
        return getTextWidths(text.toString(), start, end, widths);
    }

    public int getTextWidths(String text, int start, int end, float[] widths) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        if (end - start > widths.length) throw new ArrayIndexOutOfBoundsException();
        for (int i = start; i < end; i++) widths[i - start] = measure(String.valueOf(text.charAt(i)));
        return end - start;
    }

    public int getTextWidths(String text, float[] widths) {
        return getTextWidths(text, 0, text.length(), widths);
    }

    public float getTextRunAdvances(@NonNull char[] chars, int index, int count, int contextIndex, int contextCount, boolean isRtl,
                                    @Nullable float[] advances, int advancesIndex) {
        if (advances != null) {
            for (int i = 0; i < count; i++) advances[advancesIndex + i] = measure(String.valueOf(chars[index + i]));
        }
        return measure(new String(chars, index, count));
    }

    public float getRunAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
        return measure(new String(text, start, offset - start));
    }

    public float getRunAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
        return measure(text.subSequence(start, offset).toString());
    }

    public int getOffsetForAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
        return start + breakText(new String(text, start, end - start), true, advance, null);
    }

    public int getOffsetForAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
        return start + breakText(text.subSequence(start, end).toString(), true, advance, null);
    }

    public int getTextRunCursor(@NonNull char[] text, int contextStart, int contextLength, boolean isRtl, int offset, int cursorOpt) {
        return offset;
    }

    public int getTextRunCursor(@NonNull CharSequence text, int contextStart, int contextEnd, boolean isRtl, int offset, int cursorOpt) {
        return offset;
    }

    public void getTextBounds(String text, int start, int end, Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) throw new IndexOutOfBoundsException();
        if (bounds == null) throw new NullPointerException("need bounds Rect");
        float[] out = new float[4];
        Graphics.backend().textBounds(fontSpec(), text.substring(start, end), out);
        bounds.set((int) Math.floor(out[0]), (int) Math.floor(out[1]), (int) Math.ceil(out[2]), (int) Math.ceil(out[3]));
    }

    public void getTextBounds(@NonNull CharSequence text, int start, int end, @NonNull Rect bounds) {
        getTextBounds(text.toString(), start, end, bounds);
    }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        getTextBounds(new String(text, index, count), 0, count, bounds);
    }

    public boolean hasGlyph(String string) {
        return string != null && !string.isEmpty();
    }

    public void getTextPath(char[] text, int index, int count, float x, float y, Path path) {
        throw new UnsupportedOperationException("Paint.getTextPath is not supported");
    }

    public void getTextPath(String text, int start, int end, float x, float y, Path path) {
        throw new UnsupportedOperationException("Paint.getTextPath is not supported");
    }

    public boolean equalsForTextMeasurement(@NonNull Paint other) {
        return Objects.equals(mTypeface, other.mTypeface) && mTextSize == other.mTextSize && mTextScaleX == other.mTextScaleX
                && mTextSkewX == other.mTextSkewX && mLetterSpacing == other.mLetterSpacing && mWordSpacing == other.mWordSpacing
                && (mFlags & FAKE_BOLD_TEXT_FLAG) == (other.mFlags & FAKE_BOLD_TEXT_FLAG);
    }

    // --- For Canvas.

    /** The draw-call snapshot of this paint (cached until a setter changes it). */
    PaintSpec spec() {
        PaintSpec spec = mSpec;
        if (spec == null) {
            float[] matrix = mColorFilter instanceof ColorMatrixColorFilter filter ? filter.matrix().getArray().clone() : null;
            spec = new PaintSpec(mColor, (mFlags & (ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG)), mStyle.nativeInt, mStrokeWidth, mStrokeCap.nativeInt,
                    mStrokeJoin.nativeInt, mStrokeMiter, mBlend, matrix);
            mSpec = spec;
        }
        return spec;
    }
}
