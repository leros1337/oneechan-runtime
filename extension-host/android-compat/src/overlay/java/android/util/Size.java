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
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"); needed by
 * android.graphics.ImageDecoder.ImageInfo.getSize().
 */

package android.util;

public final class Size {
    private final int mWidth;
    private final int mHeight;

    public Size(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (obj instanceof Size other) return mWidth == other.mWidth && mHeight == other.mHeight;
        return false;
    }

    @Override
    public String toString() {
        return mWidth + "x" + mHeight;
    }

    public static Size parseSize(String string) throws NumberFormatException {
        if (string == null) throw new NullPointerException("string must not be null");
        int sep = string.indexOf('*');
        if (sep < 0) sep = string.indexOf('x');
        if (sep < 0) throw new NumberFormatException("Invalid Size: \"" + string + "\"");
        try {
            return new Size(Integer.parseInt(string.substring(0, sep)), Integer.parseInt(string.substring(sep + 1)));
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid Size: \"" + string + "\"");
        }
    }

    @Override
    public int hashCode() {
        return mHeight ^ ((mWidth << (Integer.SIZE / 2)) | (mWidth >>> (Integer.SIZE / 2)));
    }
}
