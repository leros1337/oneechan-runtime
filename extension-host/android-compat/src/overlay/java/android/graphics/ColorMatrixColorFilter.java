package android.graphics;

/*
 * Oneechan AndroidCompat addition (the android.jar stub throws "Stub!"). Behaviour follows AOSP
 * (Apache-2.0); span styling is kept as data since the host never renders text spans itself.
 */

public class ColorMatrixColorFilter extends ColorFilter {
    private final ColorMatrix matrix;

    public ColorMatrixColorFilter(ColorMatrix matrix) { this.matrix = new ColorMatrix(matrix); }
    public ColorMatrixColorFilter(float[] array) { this.matrix = new ColorMatrix(array); }

    public void getColorMatrix(ColorMatrix colorMatrix) { colorMatrix.set(matrix); }

    /** The matrix for renderers. */
    public ColorMatrix matrix() { return matrix; }
}
