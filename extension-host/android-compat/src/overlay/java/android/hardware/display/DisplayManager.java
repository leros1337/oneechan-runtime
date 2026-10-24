/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.hardware.display.DisplayManager (names
 * and constant values from the API signature), implemented as a process-wide list of displays: the
 * device display (id 0) and the virtual displays created here, which exist until released and show
 * nothing (extensions host offscreen WebViews on them). Display listeners are accepted and
 * never called (displays do not change on their own). The context constructor is hidden, as on
 * Android; CustomContext provides the instance for Context.DISPLAY_SERVICE.
 */

package android.hardware.display;

import android.content.Context;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Displays: the device's and virtual ones. */
public final class DisplayManager {
    public static final String DISPLAY_CATEGORY_PRESENTATION = "android.hardware.display.category.PRESENTATION";

    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;

    /** Every display of the process by id, the device display first. */
    private static final Map<Integer, Display> DISPLAYS = new ConcurrentSkipListMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(Display.DEFAULT_DISPLAY + 1);

    static {
        DISPLAYS.put(Display.DEFAULT_DISPLAY, Display.device());
    }

    private final Context context;

    /** @hide As on Android: apps get the manager from {@code Context#getSystemService(Context.DISPLAY_SERVICE)}. */
    public DisplayManager(Context context) {
        this.context = context;
    }

    /** The display with {@code displayId}, or null when there is none (or it was released). */
    public Display getDisplay(int displayId) {
        return DISPLAYS.get(displayId);
    }

    public Display[] getDisplays() {
        return DISPLAYS.values().toArray(new Display[0]);
    }

    /** The displays of a category: {@link #DISPLAY_CATEGORY_PRESENTATION} are those meant for presentations. */
    public Display[] getDisplays(String category) {
        if (!DISPLAY_CATEGORY_PRESENTATION.equals(category)) return getDisplays();
        List<Display> presentations = new ArrayList<>();
        for (Display display : DISPLAYS.values()) {
            if ((display.getFlags() & Display.FLAG_PRESENTATION) != 0) presentations.add(display);
        }
        return presentations.toArray(new Display[0]);
    }

    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags) {
        return createVirtualDisplay(name, width, height, densityDpi, surface, flags, null, null);
    }

    /** A new display of the given size and density; {@code callback} is never called (nothing pauses or stops it here). */
    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags,
            VirtualDisplay.Callback callback, Handler handler) {
        if (name == null) throw new IllegalArgumentException("name must be non-null");
        if (width <= 0) throw new IllegalArgumentException("width must be greater than 0");
        if (height <= 0) throw new IllegalArgumentException("height must be greater than 0");
        if (densityDpi <= 0) throw new IllegalArgumentException("densityDpi must be greater than 0");
        int displayFlags = 0;
        if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0) displayFlags |= Display.FLAG_PRIVATE;
        if ((flags & VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) displayFlags |= Display.FLAG_PRESENTATION;
        if ((flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0) displayFlags |= Display.FLAG_SECURE;
        Display display = new Display(NEXT_ID.getAndIncrement(), name, displayFlags, width, height, densityDpi);
        DISPLAYS.put(display.getDisplayId(), display);
        return new VirtualDisplay(display, surface);
    }

    /** VirtualDisplay#release. */
    static void remove(Display display) {
        DISPLAYS.remove(display.getDisplayId(), display);
    }

    /** Hears of displays added, changed and removed. */
    public interface DisplayListener {
        void onDisplayAdded(int displayId);

        void onDisplayRemoved(int displayId);

        void onDisplayChanged(int displayId);
    }
}
