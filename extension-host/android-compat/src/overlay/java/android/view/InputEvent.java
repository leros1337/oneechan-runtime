/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.view.InputEvent (from the API signature),
 * implemented so MotionEvent can be constructed (the generated stub's constructor throws "Stub!").
 * Input devices are not modelled: getDevice() returns null, as for an event without a known device.
 */

package android.view;

import android.os.Parcelable;

/** Common base class for input events. */
public abstract class InputEvent implements Parcelable {
    /** Not parcelable here (there is no binder to pass events through). */
    public static final Parcelable.Creator<InputEvent> CREATOR = null;

    InputEvent() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** The device this event came from; null: events here are synthesized, not read from a device. */
    public final InputDevice getDevice() {
        return null;
    }

    public abstract int getDeviceId();

    /** In the {@link android.os.SystemClock#uptimeMillis} time base. */
    public abstract long getEventTime();

    public abstract int getSource();

    /** Whether every bit of {@code source} is set in this event's source. */
    public boolean isFromSource(int source) {
        return (getSource() & source) == source;
    }
}
