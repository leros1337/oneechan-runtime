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
 * Modified for Oneechan: the public API of AOSP's android.content.res.Resources (from the API
 * signature). Extensions have no resource tables here, so lookups still throw "Stub!" like the
 * generated stub; what works is the display: the constructor, getDisplayMetrics(),
 * getConfiguration(), updateConfiguration() and getSystem(), an iPhone-like 3x screen (390 x 844
 * points). android.webkit.WebView#getResources() returns it, so a page's CSS pixels are
 * physical pixels / 3 (extensions that tap a WebView with MotionEvents size and place them with it).
 */

package android.content.res;

import android.graphics.Movie;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.content.res.loader.ResourcesLoader;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.io.IOException;
import java.io.InputStream;

import org.xmlpull.v1.XmlPullParserException;

/** An application's resources. Only the display is known here. */
public class Resources {
    public static final int ID_NULL = 0;

    /** Points of the screen the app's WebViews are laid out for (an iPhone's portrait screen). */
    private static final int SCREEN_WIDTH_POINTS = 390;
    private static final int SCREEN_HEIGHT_POINTS = 844;
    /** Pixels per point. */
    private static final int SCALE = 3;
    /** An iPhone's pixels per inch at 3x. */
    private static final float PIXELS_PER_INCH = 460f;

    private final DisplayMetrics metrics = new DisplayMetrics();
    private Configuration configuration;

    /** @deprecated As on Android; here it only records the display and configuration. */
    @Deprecated
    public Resources(AssetManager assets, DisplayMetrics metrics, Configuration config) {
        if (metrics != null) {
            this.metrics.setTo(metrics);
        } else {
            this.metrics.setToDefaults();
        }
        configuration = config;
    }

    private static final class SystemHolder {
        static final Resources INSTANCE = new Resources(null, deviceMetrics(), null);
    }

    /** The device's display, without application resources. */
    public static Resources getSystem() {
        return SystemHolder.INSTANCE;
    }

    private static DisplayMetrics deviceMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.densityDpi = DisplayMetrics.DENSITY_XXHIGH;
        metrics.density = SCALE;
        metrics.scaledDensity = SCALE;
        metrics.xdpi = PIXELS_PER_INCH;
        metrics.ydpi = PIXELS_PER_INCH;
        metrics.widthPixels = SCREEN_WIDTH_POINTS * SCALE;
        metrics.heightPixels = SCREEN_HEIGHT_POINTS * SCALE;
        return metrics;
    }

    /** The live metrics (changes to them are seen by later readers, as on Android). */
    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }

    public Configuration getConfiguration() {
        if (configuration == null) throw stub();
        return configuration;
    }

    @Deprecated
    public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
        if (config != null) configuration = config;
        if (metrics != null) this.metrics.setTo(metrics);
    }

    private static RuntimeException stub() {
        return new RuntimeException("Stub!");
    }

    // ---- No resource tables: as the generated stub ----------------------------------------

    public void addLoaders(ResourcesLoader... loaders) {
        throw stub();
    }

    public void removeLoaders(ResourcesLoader... loaders) {
        throw stub();
    }

    public final void finishPreloading() {
        throw stub();
    }

    public final void flushLayoutCache() {
        throw stub();
    }

    public XmlResourceParser getAnimation(int id) throws NotFoundException {
        throw stub();
    }

    public final AssetManager getAssets() {
        throw stub();
    }

    public static int getAttributeSetSourceResId(AttributeSet set) {
        throw stub();
    }

    public boolean getBoolean(int id) throws NotFoundException {
        throw stub();
    }

    @Deprecated
    public int getColor(int id) throws NotFoundException {
        throw stub();
    }

    public int getColor(int id, Theme theme) throws NotFoundException {
        throw stub();
    }

    @Deprecated
    public ColorStateList getColorStateList(int id) throws NotFoundException {
        throw stub();
    }

    public ColorStateList getColorStateList(int id, Theme theme) throws NotFoundException {
        throw stub();
    }

    public float getDimension(int id) throws NotFoundException {
        throw stub();
    }

    public int getDimensionPixelOffset(int id) throws NotFoundException {
        throw stub();
    }

    public int getDimensionPixelSize(int id) throws NotFoundException {
        throw stub();
    }

    @Deprecated
    public Drawable getDrawable(int id) throws NotFoundException {
        throw stub();
    }

    public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
        throw stub();
    }

    @Deprecated
    public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
        throw stub();
    }

    public Drawable getDrawableForDensity(int id, int density, Theme theme) {
        throw stub();
    }

    public float getFloat(int id) {
        throw stub();
    }

    public Typeface getFont(int id) throws NotFoundException {
        throw stub();
    }

    public float getFraction(int id, int base, int pbase) {
        throw stub();
    }

    public int getIdentifier(String name, String defType, String defPackage) {
        throw stub();
    }

    public int[] getIntArray(int id) throws NotFoundException {
        throw stub();
    }

    public int getInteger(int id) throws NotFoundException {
        throw stub();
    }

    public XmlResourceParser getLayout(int id) throws NotFoundException {
        throw stub();
    }

    @Deprecated
    public Movie getMovie(int id) throws NotFoundException {
        throw stub();
    }

    public String getQuantityString(int id, int quantity, Object... formatArgs) throws NotFoundException {
        throw stub();
    }

    public String getQuantityString(int id, int quantity) throws NotFoundException {
        throw stub();
    }

    public CharSequence getQuantityText(int id, int quantity) throws NotFoundException {
        throw stub();
    }

    public String getResourceEntryName(int resid) throws NotFoundException {
        throw stub();
    }

    public String getResourceName(int resid) throws NotFoundException {
        throw stub();
    }

    public String getResourcePackageName(int resid) throws NotFoundException {
        throw stub();
    }

    public String getResourceTypeName(int resid) throws NotFoundException {
        throw stub();
    }

    public String getString(int id) throws NotFoundException {
        throw stub();
    }

    public String getString(int id, Object... formatArgs) throws NotFoundException {
        throw stub();
    }

    public String[] getStringArray(int id) throws NotFoundException {
        throw stub();
    }

    public CharSequence getText(int id) throws NotFoundException {
        throw stub();
    }

    public CharSequence getText(int id, CharSequence def) {
        throw stub();
    }

    public CharSequence[] getTextArray(int id) throws NotFoundException {
        throw stub();
    }

    public void getValue(int id, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
        throw stub();
    }

    public void getValue(String name, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
        throw stub();
    }

    public void getValueForDensity(int id, int density, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
        throw stub();
    }

    public XmlResourceParser getXml(int id) throws NotFoundException {
        throw stub();
    }

    public final Theme newTheme() {
        throw stub();
    }

    public TypedArray obtainAttributes(AttributeSet set, int[] attrs) {
        throw stub();
    }

    public TypedArray obtainTypedArray(int id) throws NotFoundException {
        throw stub();
    }

    public InputStream openRawResource(int id) throws NotFoundException {
        throw stub();
    }

    public InputStream openRawResource(int id, TypedValue value) throws NotFoundException {
        throw stub();
    }

    public AssetFileDescriptor openRawResourceFd(int id) throws NotFoundException {
        throw stub();
    }

    public void parseBundleExtra(String tagName, AttributeSet attrs, Bundle outBundle) throws XmlPullParserException {
        throw stub();
    }

    public void parseBundleExtras(XmlResourceParser parser, Bundle outBundle) throws IOException, XmlPullParserException {
        throw stub();
    }

    /** A resource that does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {
        }

        public NotFoundException(String name) {
            super(name);
        }

        public NotFoundException(String name, Exception cause) {
            super(name, cause);
        }
    }

    /** Theme attributes: none here (themes cannot be created). */
    public final class Theme {
        private Theme() {
            throw stub();
        }

        public void applyStyle(int resId, boolean force) {
            throw stub();
        }

        public void dump(int priority, String tag, String prefix) {
            throw stub();
        }

        public int[] getAttributeResolutionStack(int defStyleAttr, int defStyleRes, int explicitStyleRes) {
            throw stub();
        }

        public int getChangingConfigurations() {
            throw stub();
        }

        public Drawable getDrawable(int id) throws NotFoundException {
            throw stub();
        }

        public int getExplicitStyle(AttributeSet set) {
            throw stub();
        }

        public Resources getResources() {
            return Resources.this;
        }

        public TypedArray obtainStyledAttributes(int[] attrs) {
            throw stub();
        }

        public TypedArray obtainStyledAttributes(int resId, int[] attrs) throws NotFoundException {
            throw stub();
        }

        public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
            throw stub();
        }

        public void rebase() {
            throw stub();
        }

        public boolean resolveAttribute(int resid, TypedValue outValue, boolean resolveRefs) {
            throw stub();
        }

        public void setTo(Theme other) {
            throw stub();
        }
    }
}
