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
 * Modified for Oneechan: the public API of AOSP's android.app.Presentation (from the API
 * signature): a Dialog for a display (extensions host an offscreen WebView in one on a virtual
 * display). Its resources have the display's metrics; display changes are never reported.
 */

package android.app;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Display;

/** A dialog shown on another display. */
public class Presentation extends Dialog {
    private final Display display;
    private final Resources resources;

    public Presentation(Context outerContext, Display display) {
        this(outerContext, display, 0);
    }

    public Presentation(Context outerContext, Display display, int theme) {
        super(outerContext, theme);
        if (display == null) throw new IllegalArgumentException("display must not be null");
        this.display = display;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        resources = display.getDisplayId() == Display.DEFAULT_DISPLAY ? Resources.getSystem() : new Resources(null, metrics, null);
    }

    public Display getDisplay() {
        return display;
    }

    /** Resources with the display's metrics. */
    public Resources getResources() {
        return resources;
    }

    /** Called when the display is removed (never here: the owner releases it). */
    public void onDisplayRemoved() {
    }

    /** Called when the display's size or density changes (never reported here). */
    public void onDisplayChanged() {
    }
}
