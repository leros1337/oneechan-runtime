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
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's Typeface. Upstream wraps
 * java.awt.Font (its class initializer loads AWT's native library, which iOS lacks, so even
 * `new Paint()` failed there); this version only describes the font (family, weight, italic or a
 * font file) and the GraphicsBackend resolves it (CoreText on iOS, AWT fonts on desktop).
 */

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileDescriptor;
import java.util.Objects;
import oneechan.host.platform.FontSpec;

public class Typeface {
    public static final Typeface DEFAULT;
    public static final Typeface DEFAULT_BOLD;
    public static final Typeface SANS_SERIF;
    public static final Typeface SERIF;
    public static final Typeface MONOSPACE;

    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;
    private static final int STYLE_MASK = 0x03;

    public static final String DEFAULT_FAMILY = "sans-serif";

    static {
        DEFAULT = new Typeface(DEFAULT_FAMILY, Builder.NORMAL_WEIGHT, false, null);
        DEFAULT_BOLD = new Typeface(DEFAULT_FAMILY, Builder.BOLD_WEIGHT, false, null);
        SANS_SERIF = new Typeface("sans-serif", Builder.NORMAL_WEIGHT, false, null);
        SERIF = new Typeface("serif", Builder.NORMAL_WEIGHT, false, null);
        MONOSPACE = new Typeface("monospace", Builder.NORMAL_WEIGHT, false, null);
    }

    private final String family;
    private final int weight;
    private final boolean italic;
    private final String file;

    private Typeface(String family, int weight, boolean italic, String file) {
        this.family = family == null ? DEFAULT_FAMILY : family;
        this.weight = Math.max(1, Math.min(1000, weight));
        this.italic = italic;
        this.file = file;
    }

    public int getWeight() {
        return weight;
    }

    public int getStyle() {
        return (isBold() ? BOLD : 0) | (italic ? ITALIC : 0);
    }

    public final boolean isBold() {
        return weight >= 600;
    }

    public final boolean isItalic() {
        return italic;
    }

    @Nullable
    public final String getSystemFontFamilyName() {
        return file == null ? family : null;
    }

    /** What the graphics backend needs to resolve this typeface at a size. */
    FontSpec spec(float size, float scaleX, float skewX, boolean fakeBold) {
        int w = fakeBold && weight < 600 ? Builder.BOLD_WEIGHT : weight;
        return new FontSpec(family, w, italic, file, size, scaleX, skewX);
    }

    public static Typeface create(String familyName, int style) {
        if (familyName == null) return defaultFromStyle(style);
        return create(new Typeface(familyName, Builder.NORMAL_WEIGHT, false, null), style);
    }

    public static Typeface create(Typeface family, int style) {
        if ((style & ~STYLE_MASK) != 0) style = NORMAL;
        if (family == null) family = DEFAULT;
        int weight = (style & BOLD) != 0 ? Math.max(family.weight, Builder.BOLD_WEIGHT) : (family.isBold() ? Builder.NORMAL_WEIGHT : family.weight);
        return new Typeface(family.family, weight, (style & ITALIC) != 0, family.file);
    }

    public static @NonNull Typeface create(@Nullable Typeface family, int weight, boolean italic) {
        if (weight < 1 || weight > 1000) throw new IllegalArgumentException("weight must be 1..1000");
        if (family == null) family = DEFAULT;
        return new Typeface(family.family, weight, italic, family.file);
    }

    public static Typeface defaultFromStyle(int style) {
        return create(DEFAULT, style);
    }

    public static Typeface createFromAsset(AssetManager mgr, String path) {
        throw new RuntimeException("Font asset not found " + path);
    }

    public static Typeface createFromFile(@Nullable File file) {
        if (file == null || !file.exists()) throw new RuntimeException("Font asset not found " + file);
        return new Typeface(DEFAULT_FAMILY, Builder.NORMAL_WEIGHT, false, file.getAbsolutePath());
    }

    public static Typeface createFromFile(@Nullable String path) {
        if (path == null) throw new NullPointerException();
        return createFromFile(new File(path));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Typeface other)) return false;
        return weight == other.weight && italic == other.italic && family.equals(other.family) && Objects.equals(file, other.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(family, weight, italic, file);
    }

    public static final class Builder {
        public static final int NORMAL_WEIGHT = 400;
        public static final int BOLD_WEIGHT = 700;

        private final String path;
        private int weight = -1;
        private boolean italic;
        private boolean italicSet;
        private String fallbackFamily;

        public Builder(@NonNull File path) {
            this.path = path.getAbsolutePath();
        }

        public Builder(@NonNull FileDescriptor fd) {
            this.path = null;
        }

        public Builder(@NonNull String path) {
            this.path = path;
        }

        public Builder(@NonNull AssetManager assetManager, @NonNull String path) {
            this.path = null;
        }

        public Builder(@NonNull AssetManager assetManager, @NonNull String path, boolean isAsset, int cookie) {
            this.path = null;
        }

        public Builder setWeight(int weight) {
            this.weight = weight;
            return this;
        }

        public Builder setItalic(boolean italic) {
            this.italic = italic;
            this.italicSet = true;
            return this;
        }

        public Builder setTtcIndex(int ttcIndex) {
            return this;
        }

        public Builder setFontVariationSettings(@Nullable String variationSettings) {
            return this;
        }

        public Builder setFallback(@Nullable String familyName) {
            fallbackFamily = familyName;
            return this;
        }

        public Typeface build() {
            int w = weight > 0 ? weight : NORMAL_WEIGHT;
            if (path != null && new File(path).isFile()) return new Typeface(DEFAULT_FAMILY, w, italicSet && italic, path);
            if (fallbackFamily == null) return null;
            return new Typeface(fallbackFamily, w, italicSet && italic, null);
        }
    }
}
