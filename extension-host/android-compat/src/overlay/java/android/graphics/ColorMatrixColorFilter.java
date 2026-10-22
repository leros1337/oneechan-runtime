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
 * Modified for Oneechan: AndroidCompat addition (the android.jar stub throws "Stub!"); behaviour follows
 * AOSP. The graphics backends apply the matrix when Paint carries this filter.
 */

package android.graphics;

public class ColorMatrixColorFilter extends ColorFilter {
    private final ColorMatrix matrix;

    public ColorMatrixColorFilter(ColorMatrix matrix) { this.matrix = new ColorMatrix(matrix); }
    public ColorMatrixColorFilter(float[] array) { this.matrix = new ColorMatrix(array); }

    public void getColorMatrix(ColorMatrix colorMatrix) { colorMatrix.set(matrix); }

    /** The matrix for renderers. */
    public ColorMatrix matrix() { return matrix; }
}
