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
 * Modified for Oneechan: the public API of AOSP's android.view.MotionEvent (names and constant
 * values from the API signature), implemented from its documentation in plain Java: events live
 * on the heap (recycle() only marks them), history is a list of samples, and offsets and
 * transform(Matrix) are one affine map from the raw coordinates. Extensions build events to drive
 * a WebView (taps and swipes as MotionEvents); the app's WebView replays them in the page.
 */

package android.view;

import android.graphics.Matrix;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;

/** A movement of a finger, stylus, mouse or other pointer: an action, its pointers and their history. */
public final class MotionEvent extends InputEvent implements Parcelable {
    public static final int INVALID_POINTER_ID = -1;

    public static final int ACTION_MASK = 0xff;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_OUTSIDE = 4;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_SCROLL = 8;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;
    public static final int ACTION_BUTTON_PRESS = 11;
    public static final int ACTION_BUTTON_RELEASE = 12;
    public static final int ACTION_POINTER_INDEX_MASK = 0xff00;
    public static final int ACTION_POINTER_INDEX_SHIFT = 8;
    @Deprecated public static final int ACTION_POINTER_1_DOWN = ACTION_POINTER_DOWN | 0x0000;
    @Deprecated public static final int ACTION_POINTER_2_DOWN = ACTION_POINTER_DOWN | 0x0100;
    @Deprecated public static final int ACTION_POINTER_3_DOWN = ACTION_POINTER_DOWN | 0x0200;
    @Deprecated public static final int ACTION_POINTER_1_UP = ACTION_POINTER_UP | 0x0000;
    @Deprecated public static final int ACTION_POINTER_2_UP = ACTION_POINTER_UP | 0x0100;
    @Deprecated public static final int ACTION_POINTER_3_UP = ACTION_POINTER_UP | 0x0200;
    @Deprecated public static final int ACTION_POINTER_ID_MASK = 0xff00;
    @Deprecated public static final int ACTION_POINTER_ID_SHIFT = 8;

    public static final int FLAG_WINDOW_IS_OBSCURED = 0x1;
    public static final int FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x2;

    public static final int EDGE_TOP = 0x1;
    public static final int EDGE_BOTTOM = 0x2;
    public static final int EDGE_LEFT = 0x4;
    public static final int EDGE_RIGHT = 0x8;

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_PRESSURE = 2;
    public static final int AXIS_SIZE = 3;
    public static final int AXIS_TOUCH_MAJOR = 4;
    public static final int AXIS_TOUCH_MINOR = 5;
    public static final int AXIS_TOOL_MAJOR = 6;
    public static final int AXIS_TOOL_MINOR = 7;
    public static final int AXIS_ORIENTATION = 8;
    public static final int AXIS_VSCROLL = 9;
    public static final int AXIS_HSCROLL = 10;
    public static final int AXIS_Z = 11;
    public static final int AXIS_RX = 12;
    public static final int AXIS_RY = 13;
    public static final int AXIS_RZ = 14;
    public static final int AXIS_HAT_X = 15;
    public static final int AXIS_HAT_Y = 16;
    public static final int AXIS_LTRIGGER = 17;
    public static final int AXIS_RTRIGGER = 18;
    public static final int AXIS_THROTTLE = 19;
    public static final int AXIS_RUDDER = 20;
    public static final int AXIS_WHEEL = 21;
    public static final int AXIS_GAS = 22;
    public static final int AXIS_BRAKE = 23;
    public static final int AXIS_DISTANCE = 24;
    public static final int AXIS_TILT = 25;
    public static final int AXIS_SCROLL = 26;
    public static final int AXIS_RELATIVE_X = 27;
    public static final int AXIS_RELATIVE_Y = 28;
    public static final int AXIS_GENERIC_1 = 32;
    public static final int AXIS_GENERIC_2 = 33;
    public static final int AXIS_GENERIC_3 = 34;
    public static final int AXIS_GENERIC_4 = 35;
    public static final int AXIS_GENERIC_5 = 36;
    public static final int AXIS_GENERIC_6 = 37;
    public static final int AXIS_GENERIC_7 = 38;
    public static final int AXIS_GENERIC_8 = 39;
    public static final int AXIS_GENERIC_9 = 40;
    public static final int AXIS_GENERIC_10 = 41;
    public static final int AXIS_GENERIC_11 = 42;
    public static final int AXIS_GENERIC_12 = 43;
    public static final int AXIS_GENERIC_13 = 44;
    public static final int AXIS_GENERIC_14 = 45;
    public static final int AXIS_GENERIC_15 = 46;
    public static final int AXIS_GENERIC_16 = 47;

    public static final int BUTTON_PRIMARY = 1 << 0;
    public static final int BUTTON_SECONDARY = 1 << 1;
    public static final int BUTTON_TERTIARY = 1 << 2;
    public static final int BUTTON_BACK = 1 << 3;
    public static final int BUTTON_FORWARD = 1 << 4;
    public static final int BUTTON_STYLUS_PRIMARY = 1 << 5;
    public static final int BUTTON_STYLUS_SECONDARY = 1 << 6;

    public static final int CLASSIFICATION_NONE = 0;
    public static final int CLASSIFICATION_AMBIGUOUS_GESTURE = 1;
    public static final int CLASSIFICATION_DEEP_PRESS = 2;

    public static final int TOOL_TYPE_UNKNOWN = 0;
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_STYLUS = 2;
    public static final int TOOL_TYPE_MOUSE = 3;
    public static final int TOOL_TYPE_ERASER = 4;

    /** Not parcelable here (there is no binder to pass events through). */
    public static final Parcelable.Creator<MotionEvent> CREATOR = null;

    /** Axis ids fit in 6 bits. */
    private static final int AXIS_COUNT = 64;

    private static final String[] AXIS_NAMES = new String[AXIS_COUNT];

    static {
        String[] named = {
            "AXIS_X", "AXIS_Y", "AXIS_PRESSURE", "AXIS_SIZE", "AXIS_TOUCH_MAJOR", "AXIS_TOUCH_MINOR",
            "AXIS_TOOL_MAJOR", "AXIS_TOOL_MINOR", "AXIS_ORIENTATION", "AXIS_VSCROLL", "AXIS_HSCROLL", "AXIS_Z",
            "AXIS_RX", "AXIS_RY", "AXIS_RZ", "AXIS_HAT_X", "AXIS_HAT_Y", "AXIS_LTRIGGER", "AXIS_RTRIGGER",
            "AXIS_THROTTLE", "AXIS_RUDDER", "AXIS_WHEEL", "AXIS_GAS", "AXIS_BRAKE", "AXIS_DISTANCE", "AXIS_TILT",
            "AXIS_SCROLL", "AXIS_RELATIVE_X", "AXIS_RELATIVE_Y",
        };
        System.arraycopy(named, 0, AXIS_NAMES, 0, named.length);
        for (int i = 1; i <= 16; i++) AXIS_NAMES[AXIS_GENERIC_1 + i - 1] = "AXIS_GENERIC_" + i;
    }

    /** The pointers' coordinates at one time. */
    private static final class Sample {
        final long eventTime;
        final PointerCoords[] coords;

        Sample(long eventTime, PointerCoords[] coords) {
            this.eventTime = eventTime;
            this.coords = coords;
        }

        Sample copy() {
            return new Sample(eventTime, copyOf(coords, coords.length));
        }
    }

    private long downTime;
    private int action;
    private int actionButton;
    private int metaState;
    private int buttonState;
    private int flags;
    private int edgeFlags;
    private int deviceId;
    private int source;
    private int classification;
    private float xPrecision = 1f;
    private float yPrecision = 1f;
    private PointerProperties[] pointers;
    /** Oldest first; the last sample is the current one, the others are its history. */
    private final ArrayList<Sample> samples = new ArrayList<>(2);
    // Event coordinates from raw ones: x = scaleX * rawX + skewX * rawY + transX, y = skewY * rawX + scaleY * rawY + transY.
    private float scaleX = 1f;
    private float skewX;
    private float transX;
    private float skewY;
    private float scaleY = 1f;
    private float transY;
    private volatile boolean recycled;

    private MotionEvent() {
    }

    // ---- Construction -------------------------------------------------------------------------

    /** The full form: {@code pointerCount} pointers, their properties and coordinates (both copied). */
    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
            PointerProperties[] pointerProperties, PointerCoords[] pointerCoords, int metaState, int buttonState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) {
        checkPointerCount(pointerCount, pointerProperties == null ? 0 : pointerProperties.length, pointerCoords == null ? 0 : pointerCoords.length);
        MotionEvent event = new MotionEvent();
        event.pointers = new PointerProperties[pointerCount];
        for (int i = 0; i < pointerCount; i++) event.pointers[i] = new PointerProperties(pointerProperties[i]);
        event.init(downTime, eventTime, action, copyOf(pointerCoords, pointerCount), metaState, buttonState,
                xPrecision, yPrecision, deviceId, edgeFlags, source, flags);
        return event;
    }

    /** @deprecated Use the form with {@link PointerProperties}; tool types are unknown here. */
    @Deprecated
    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount, int[] pointerIds,
            PointerCoords[] pointerCoords, int metaState, float xPrecision, float yPrecision, int deviceId,
            int edgeFlags, int source, int flags) {
        checkPointerCount(pointerCount, pointerIds == null ? 0 : pointerIds.length, pointerCoords == null ? 0 : pointerCoords.length);
        PointerProperties[] properties = new PointerProperties[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            properties[i] = new PointerProperties();
            properties[i].id = pointerIds[i];
        }
        return obtain(downTime, eventTime, action, pointerCount, properties, pointerCoords, metaState, 0,
                xPrecision, yPrecision, deviceId, edgeFlags, source, flags);
    }

    /** One pointer (id 0) at ({@code x}, {@code y}); the source is unknown. */
    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, float pressure,
            float size, int metaState, float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        PointerProperties properties = new PointerProperties();
        properties.id = 0;
        PointerCoords coords = new PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = pressure;
        coords.size = size;
        MotionEvent event = new MotionEvent();
        event.pointers = new PointerProperties[] {properties};
        event.init(downTime, eventTime, action, new PointerCoords[] {coords}, metaState, 0, xPrecision, yPrecision,
                deviceId, edgeFlags, InputDevice.SOURCE_UNKNOWN, 0);
        return event;
    }

    /** @deprecated {@code pointerCount} is ignored: one pointer, like the form without it. */
    @Deprecated
    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount, float x, float y,
            float pressure, float size, int metaState, float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        return obtain(downTime, eventTime, action, x, y, pressure, size, metaState, xPrecision, yPrecision, deviceId, edgeFlags);
    }

    /** One pointer with pressure and size 1, precision 1, device 0 and no edge flags. */
    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) {
        return obtain(downTime, eventTime, action, x, y, 1f, 1f, metaState, 1f, 1f, 0, 0);
    }

    /** A copy of {@code other}, history included. */
    public static MotionEvent obtain(MotionEvent other) {
        return copy(other, true);
    }

    /** A copy of {@code other} without its history. */
    public static MotionEvent obtainNoHistory(MotionEvent other) {
        return copy(other, false);
    }

    private static MotionEvent copy(MotionEvent other, boolean withHistory) {
        if (other == null) throw new IllegalArgumentException("other motion event must not be null");
        MotionEvent event = new MotionEvent();
        event.downTime = other.downTime;
        event.action = other.action;
        event.actionButton = other.actionButton;
        event.metaState = other.metaState;
        event.buttonState = other.buttonState;
        event.flags = other.flags;
        event.edgeFlags = other.edgeFlags;
        event.deviceId = other.deviceId;
        event.source = other.source;
        event.classification = other.classification;
        event.xPrecision = other.xPrecision;
        event.yPrecision = other.yPrecision;
        event.pointers = new PointerProperties[other.pointers.length];
        for (int i = 0; i < other.pointers.length; i++) event.pointers[i] = new PointerProperties(other.pointers[i]);
        if (withHistory) {
            for (Sample sample : other.samples) event.samples.add(sample.copy());
        } else {
            event.samples.add(other.current().copy());
        }
        event.scaleX = other.scaleX;
        event.skewX = other.skewX;
        event.transX = other.transX;
        event.skewY = other.skewY;
        event.scaleY = other.scaleY;
        event.transY = other.transY;
        return event;
    }

    private void init(long downTime, long eventTime, int action, PointerCoords[] coords, int metaState,
            int buttonState, float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) {
        this.downTime = downTime;
        this.action = action;
        this.metaState = metaState;
        this.buttonState = buttonState;
        this.xPrecision = xPrecision;
        this.yPrecision = yPrecision;
        this.deviceId = deviceId;
        this.edgeFlags = edgeFlags;
        this.source = source;
        this.flags = flags;
        samples.add(new Sample(eventTime, coords));
    }

    private static void checkPointerCount(int pointerCount, int properties, int coords) {
        if (pointerCount < 1) throw new IllegalArgumentException("pointerCount must be at least 1");
        if (properties < pointerCount) throw new IllegalArgumentException("pointer properties array must be at least pointerCount long");
        if (coords < pointerCount) throw new IllegalArgumentException("pointer coords array must be at least pointerCount long");
    }

    private static PointerCoords[] copyOf(PointerCoords[] source, int count) {
        PointerCoords[] copy = new PointerCoords[count];
        for (int i = 0; i < count; i++) {
            if (source[i] == null) throw new IllegalArgumentException("pointer coords must not be null");
            copy[i] = new PointerCoords(source[i]);
        }
        return copy;
    }

    /** Events are plain heap objects here: recycling only marks the event (the garbage collector frees it). */
    public void recycle() {
        recycled = true;
    }

    // ---- Action and state ---------------------------------------------------------------------

    public int getAction() {
        return action;
    }

    public void setAction(int action) {
        this.action = action;
    }

    public int getActionMasked() {
        return action & ACTION_MASK;
    }

    public int getActionIndex() {
        return (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT;
    }

    public int getActionButton() {
        return actionButton;
    }

    public int getButtonState() {
        return buttonState;
    }

    public boolean isButtonPressed(int button) {
        return button != 0 && (buttonState & button) == button;
    }

    public int getClassification() {
        return classification;
    }

    public int getMetaState() {
        return metaState;
    }

    public int getFlags() {
        return flags;
    }

    public int getEdgeFlags() {
        return edgeFlags;
    }

    public void setEdgeFlags(int flags) {
        edgeFlags = flags;
    }

    @Override
    public int getDeviceId() {
        return deviceId;
    }

    @Override
    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public long getDownTime() {
        return downTime;
    }

    @Override
    public long getEventTime() {
        return current().eventTime;
    }

    public float getXPrecision() {
        return xPrecision;
    }

    public float getYPrecision() {
        return yPrecision;
    }

    // ---- Pointers -----------------------------------------------------------------------------

    public int getPointerCount() {
        return pointers.length;
    }

    public int getPointerId(int pointerIndex) {
        return pointer(pointerIndex).id;
    }

    public int getToolType(int pointerIndex) {
        return pointer(pointerIndex).toolType;
    }

    /** The index of the pointer with {@code pointerId}, or -1. */
    public int findPointerIndex(int pointerId) {
        for (int i = 0; i < pointers.length; i++) {
            if (pointers[i].id == pointerId) return i;
        }
        return -1;
    }

    public void getPointerProperties(int pointerIndex, PointerProperties outPointerProperties) {
        outPointerProperties.copyFrom(pointer(pointerIndex));
    }

    public float getX() {
        return getX(0);
    }

    public float getY() {
        return getY(0);
    }

    public float getX(int pointerIndex) {
        PointerCoords c = coords(pointerIndex, -1);
        return mapX(c.x, c.y);
    }

    public float getY(int pointerIndex) {
        PointerCoords c = coords(pointerIndex, -1);
        return mapY(c.x, c.y);
    }

    /** Where the pointer was before offsets and transforms (the screen position for synthesized events). */
    public float getRawX() {
        return getRawX(0);
    }

    public float getRawY() {
        return getRawY(0);
    }

    public float getRawX(int pointerIndex) {
        return coords(pointerIndex, -1).x;
    }

    public float getRawY(int pointerIndex) {
        return coords(pointerIndex, -1).y;
    }

    public float getPressure() {
        return getPressure(0);
    }

    public float getPressure(int pointerIndex) {
        return coords(pointerIndex, -1).pressure;
    }

    public float getSize() {
        return getSize(0);
    }

    public float getSize(int pointerIndex) {
        return coords(pointerIndex, -1).size;
    }

    public float getTouchMajor() {
        return getTouchMajor(0);
    }

    public float getTouchMajor(int pointerIndex) {
        return coords(pointerIndex, -1).touchMajor;
    }

    public float getTouchMinor() {
        return getTouchMinor(0);
    }

    public float getTouchMinor(int pointerIndex) {
        return coords(pointerIndex, -1).touchMinor;
    }

    public float getToolMajor() {
        return getToolMajor(0);
    }

    public float getToolMajor(int pointerIndex) {
        return coords(pointerIndex, -1).toolMajor;
    }

    public float getToolMinor() {
        return getToolMinor(0);
    }

    public float getToolMinor(int pointerIndex) {
        return coords(pointerIndex, -1).toolMinor;
    }

    public float getOrientation() {
        return getOrientation(0);
    }

    public float getOrientation(int pointerIndex) {
        return coords(pointerIndex, -1).orientation;
    }

    public float getAxisValue(int axis) {
        return getAxisValue(axis, 0);
    }

    public float getAxisValue(int axis, int pointerIndex) {
        return axisValue(axis, pointerIndex, -1);
    }

    public void getPointerCoords(int pointerIndex, PointerCoords outPointerCoords) {
        copyMapped(coords(pointerIndex, -1), outPointerCoords);
    }

    // ---- History ------------------------------------------------------------------------------

    public int getHistorySize() {
        return samples.size() - 1;
    }

    public long getHistoricalEventTime(int pos) {
        return samples.get(historyIndex(pos)).eventTime;
    }

    public float getHistoricalX(int pos) {
        return getHistoricalX(0, pos);
    }

    public float getHistoricalY(int pos) {
        return getHistoricalY(0, pos);
    }

    public float getHistoricalX(int pointerIndex, int pos) {
        PointerCoords c = coords(pointerIndex, pos);
        return mapX(c.x, c.y);
    }

    public float getHistoricalY(int pointerIndex, int pos) {
        PointerCoords c = coords(pointerIndex, pos);
        return mapY(c.x, c.y);
    }

    public float getHistoricalPressure(int pos) {
        return getHistoricalPressure(0, pos);
    }

    public float getHistoricalPressure(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).pressure;
    }

    public float getHistoricalSize(int pos) {
        return getHistoricalSize(0, pos);
    }

    public float getHistoricalSize(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).size;
    }

    public float getHistoricalTouchMajor(int pos) {
        return getHistoricalTouchMajor(0, pos);
    }

    public float getHistoricalTouchMajor(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).touchMajor;
    }

    public float getHistoricalTouchMinor(int pos) {
        return getHistoricalTouchMinor(0, pos);
    }

    public float getHistoricalTouchMinor(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).touchMinor;
    }

    public float getHistoricalToolMajor(int pos) {
        return getHistoricalToolMajor(0, pos);
    }

    public float getHistoricalToolMajor(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).toolMajor;
    }

    public float getHistoricalToolMinor(int pos) {
        return getHistoricalToolMinor(0, pos);
    }

    public float getHistoricalToolMinor(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).toolMinor;
    }

    public float getHistoricalOrientation(int pos) {
        return getHistoricalOrientation(0, pos);
    }

    public float getHistoricalOrientation(int pointerIndex, int pos) {
        return coords(pointerIndex, pos).orientation;
    }

    public float getHistoricalAxisValue(int axis, int pos) {
        return getHistoricalAxisValue(axis, 0, pos);
    }

    public float getHistoricalAxisValue(int axis, int pointerIndex, int pos) {
        return axisValue(axis, pointerIndex, pos);
    }

    public void getHistoricalPointerCoords(int pointerIndex, int pos, PointerCoords outPointerCoords) {
        copyMapped(coords(pointerIndex, pos), outPointerCoords);
    }

    /** A new current sample for a single-pointer event; the previous one becomes history. Coordinates are in event space. */
    public void addBatch(long eventTime, float x, float y, float pressure, float size, int metaState) {
        PointerCoords[] next = copyOf(current().coords, pointers.length);
        PointerCoords first = next[0];
        first.x = unmapX(x, y);
        first.y = unmapY(x, y);
        first.pressure = pressure;
        first.size = size;
        samples.add(new Sample(eventTime, next));
        this.metaState |= metaState;
    }

    /** A new current sample with one coordinate per pointer (in event space); the previous one becomes history. */
    public void addBatch(long eventTime, PointerCoords[] pointerCoords, int metaState) {
        if (pointerCoords == null || pointerCoords.length < pointers.length) {
            throw new IllegalArgumentException("pointerCoords array must be at least pointerCount long");
        }
        PointerCoords[] next = copyOf(pointerCoords, pointers.length);
        for (PointerCoords c : next) {
            float x = c.x;
            float y = c.y;
            c.x = unmapX(x, y);
            c.y = unmapY(x, y);
        }
        samples.add(new Sample(eventTime, next));
        this.metaState |= metaState;
    }

    // ---- Location -----------------------------------------------------------------------------

    public void offsetLocation(float deltaX, float deltaY) {
        transX += deltaX;
        transY += deltaY;
    }

    /** Offsets the event so that the first pointer is at ({@code x}, {@code y}). */
    public void setLocation(float x, float y) {
        offsetLocation(x - getX(), y - getY());
    }

    /** Applies {@code matrix} to the event's coordinates (not to the raw ones). Perspective is ignored. */
    public void transform(Matrix matrix) {
        if (matrix == null) throw new IllegalArgumentException("matrix must not be null");
        float[] m = new float[9];
        matrix.getValues(m);
        float a = m[Matrix.MSCALE_X], b = m[Matrix.MSKEW_X], tx = m[Matrix.MTRANS_X];
        float c = m[Matrix.MSKEW_Y], d = m[Matrix.MSCALE_Y], ty = m[Matrix.MTRANS_Y];
        float newScaleX = a * scaleX + b * skewY;
        float newSkewX = a * skewX + b * scaleY;
        float newTransX = a * transX + b * transY + tx;
        float newSkewY = c * scaleX + d * skewY;
        float newScaleY = c * skewX + d * scaleY;
        float newTransY = c * transX + d * transY + ty;
        scaleX = newScaleX;
        skewX = newSkewX;
        transX = newTransX;
        skewY = newSkewY;
        scaleY = newScaleY;
        transY = newTransY;
    }

    private float mapX(float x, float y) {
        return scaleX * x + skewX * y + transX;
    }

    private float mapY(float x, float y) {
        return skewY * x + scaleY * y + transY;
    }

    private float determinant() {
        return scaleX * scaleY - skewX * skewY;
    }

    private float unmapX(float x, float y) {
        float det = determinant();
        return det == 0f ? x - transX : (scaleY * (x - transX) - skewX * (y - transY)) / det;
    }

    private float unmapY(float x, float y) {
        float det = determinant();
        return det == 0f ? y - transY : (scaleX * (y - transY) - skewY * (x - transX)) / det;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private Sample current() {
        return samples.get(samples.size() - 1);
    }

    private int historyIndex(int pos) {
        if (pos < 0 || pos >= samples.size() - 1) throw new IllegalArgumentException("historyPos out of range");
        return pos;
    }

    private PointerProperties pointer(int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= pointers.length) throw new IllegalArgumentException("pointerIndex out of range");
        return pointers[pointerIndex];
    }

    /** The raw coordinates of a pointer: the current sample for {@code pos} -1, else history entry {@code pos}. */
    private PointerCoords coords(int pointerIndex, int pos) {
        pointer(pointerIndex);
        Sample sample = pos < 0 ? current() : samples.get(historyIndex(pos));
        return sample.coords[pointerIndex];
    }

    private float axisValue(int axis, int pointerIndex, int pos) {
        PointerCoords c = coords(pointerIndex, pos);
        switch (axis) {
            case AXIS_X:
                return mapX(c.x, c.y);
            case AXIS_Y:
                return mapY(c.x, c.y);
            default:
                return c.getAxisValue(axis);
        }
    }

    private void copyMapped(PointerCoords raw, PointerCoords out) {
        out.copyFrom(raw);
        out.x = mapX(raw.x, raw.y);
        out.y = mapY(raw.x, raw.y);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        throw new RuntimeException("Stub!");
    }

    // ---- Names --------------------------------------------------------------------------------

    /** "ACTION_DOWN", "ACTION_POINTER_UP(1)", ... or the number for an unknown action. */
    public static String actionToString(int action) {
        switch (action) {
            case ACTION_DOWN: return "ACTION_DOWN";
            case ACTION_UP: return "ACTION_UP";
            case ACTION_MOVE: return "ACTION_MOVE";
            case ACTION_CANCEL: return "ACTION_CANCEL";
            case ACTION_OUTSIDE: return "ACTION_OUTSIDE";
            case ACTION_HOVER_MOVE: return "ACTION_HOVER_MOVE";
            case ACTION_SCROLL: return "ACTION_SCROLL";
            case ACTION_HOVER_ENTER: return "ACTION_HOVER_ENTER";
            case ACTION_HOVER_EXIT: return "ACTION_HOVER_EXIT";
            case ACTION_BUTTON_PRESS: return "ACTION_BUTTON_PRESS";
            case ACTION_BUTTON_RELEASE: return "ACTION_BUTTON_RELEASE";
            default:
                int index = (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT;
                switch (action & ACTION_MASK) {
                    case ACTION_POINTER_DOWN: return "ACTION_POINTER_DOWN(" + index + ")";
                    case ACTION_POINTER_UP: return "ACTION_POINTER_UP(" + index + ")";
                    default: return Integer.toString(action);
                }
        }
    }

    /** "AXIS_X", ... or the number for an unnamed axis. */
    public static String axisToString(int axis) {
        String name = axis >= 0 && axis < AXIS_COUNT ? AXIS_NAMES[axis] : null;
        return name != null ? name : Integer.toString(axis);
    }

    /** The axis named {@code symbolicName} ("AXIS_X" or a number), or -1. */
    public static int axisFromString(String symbolicName) {
        if (symbolicName == null) return -1;
        String name = symbolicName.startsWith("AXIS_") ? symbolicName : "AXIS_" + symbolicName;
        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            if (name.equals(AXIS_NAMES[axis])) return axis;
        }
        try {
            return Integer.parseInt(symbolicName, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String toolTypeToString(int toolType) {
        switch (toolType) {
            case TOOL_TYPE_UNKNOWN: return "TOOL_TYPE_UNKNOWN";
            case TOOL_TYPE_FINGER: return "TOOL_TYPE_FINGER";
            case TOOL_TYPE_STYLUS: return "TOOL_TYPE_STYLUS";
            case TOOL_TYPE_MOUSE: return "TOOL_TYPE_MOUSE";
            case TOOL_TYPE_ERASER: return "TOOL_TYPE_ERASER";
            default: return Integer.toString(toolType);
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("MotionEvent { action=").append(actionToString(action));
        text.append(", actionButton=").append(actionButton);
        for (int i = 0; i < pointers.length; i++) {
            text.append(", id[").append(i).append("]=").append(pointers[i].id);
            text.append(", x[").append(i).append("]=").append(getX(i));
            text.append(", y[").append(i).append("]=").append(getY(i));
            text.append(", toolType[").append(i).append("]=").append(toolTypeToString(pointers[i].toolType));
        }
        text.append(", buttonState=").append(buttonState);
        text.append(", metaState=").append(metaState);
        text.append(", flags=0x").append(Integer.toHexString(flags));
        text.append(", edgeFlags=0x").append(Integer.toHexString(edgeFlags));
        text.append(", pointerCount=").append(pointers.length);
        text.append(", historySize=").append(getHistorySize());
        text.append(", eventTime=").append(getEventTime());
        text.append(", downTime=").append(downTime);
        text.append(", deviceId=").append(deviceId);
        text.append(", source=0x").append(Integer.toHexString(source));
        if (recycled) text.append(", recycled");
        return text.append(" }").toString();
    }

    // ---- Pointer data -------------------------------------------------------------------------

    /** One pointer's axis values. The common axes are fields; the others are kept by axis id. */
    public static final class PointerCoords {
        public float x;
        public float y;
        public float pressure;
        public float size;
        public float touchMajor;
        public float touchMinor;
        public float toolMajor;
        public float toolMinor;
        public float orientation;
        /** Values of the other axes by id; null until one is set. */
        private float[] others;

        public PointerCoords() {
        }

        public PointerCoords(PointerCoords other) {
            copyFrom(other);
        }

        public void clear() {
            x = 0f;
            y = 0f;
            pressure = 0f;
            size = 0f;
            touchMajor = 0f;
            touchMinor = 0f;
            toolMajor = 0f;
            toolMinor = 0f;
            orientation = 0f;
            others = null;
        }

        public void copyFrom(PointerCoords other) {
            x = other.x;
            y = other.y;
            pressure = other.pressure;
            size = other.size;
            touchMajor = other.touchMajor;
            touchMinor = other.touchMinor;
            toolMajor = other.toolMajor;
            toolMinor = other.toolMinor;
            orientation = other.orientation;
            others = other.others == null ? null : Arrays.copyOf(other.others, other.others.length);
        }

        public float getAxisValue(int axis) {
            switch (axis) {
                case AXIS_X: return x;
                case AXIS_Y: return y;
                case AXIS_PRESSURE: return pressure;
                case AXIS_SIZE: return size;
                case AXIS_TOUCH_MAJOR: return touchMajor;
                case AXIS_TOUCH_MINOR: return touchMinor;
                case AXIS_TOOL_MAJOR: return toolMajor;
                case AXIS_TOOL_MINOR: return toolMinor;
                case AXIS_ORIENTATION: return orientation;
                default:
                    checkAxis(axis);
                    return others == null ? 0f : others[axis];
            }
        }

        public void setAxisValue(int axis, float value) {
            switch (axis) {
                case AXIS_X: x = value; break;
                case AXIS_Y: y = value; break;
                case AXIS_PRESSURE: pressure = value; break;
                case AXIS_SIZE: size = value; break;
                case AXIS_TOUCH_MAJOR: touchMajor = value; break;
                case AXIS_TOUCH_MINOR: touchMinor = value; break;
                case AXIS_TOOL_MAJOR: toolMajor = value; break;
                case AXIS_TOOL_MINOR: toolMinor = value; break;
                case AXIS_ORIENTATION: orientation = value; break;
                default:
                    checkAxis(axis);
                    if (others == null) others = new float[AXIS_COUNT];
                    others[axis] = value;
            }
        }

        private static void checkAxis(int axis) {
            if (axis < 0 || axis >= AXIS_COUNT) throw new IllegalArgumentException("Axis out of range.");
        }
    }

    /** A pointer's id and tool. A new one has an invalid id and an unknown tool. */
    public static final class PointerProperties {
        public int id;
        public int toolType;

        public PointerProperties() {
            clear();
        }

        public PointerProperties(PointerProperties other) {
            copyFrom(other);
        }

        public void clear() {
            id = INVALID_POINTER_ID;
            toolType = TOOL_TYPE_UNKNOWN;
        }

        public void copyFrom(PointerProperties other) {
            id = other.id;
            toolType = other.toolType;
        }
    }
}
