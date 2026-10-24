package android.widget;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Modified for Oneechan: show() also hands the text to the app (oneechan.host.platform.Toasts, the
// host.toast event), which shows it like its own toasts; the duration and setText(CharSequence) are
// kept, a Toast can be created with its constructor, and cancel() is a no-op.

import oneechan.host.platform.Toasts;

public class Toast {
    public static final int LENGTH_LONG = 1;
    public static final int LENGTH_SHORT = 0;

    private CharSequence text;
    private int duration;

    private Toast(CharSequence text, int duration) {
        this.text = text;
        this.duration = duration;
    }

    public Toast(android.content.Context context) {
        this("", LENGTH_SHORT);
    }

    public void show() {
        String message = text == null ? "" : text.toString();
        System.out.printf("made a Toast: \"%s\"\n", message);
        Toasts.show(message, duration == LENGTH_LONG);
    }

    /** The app's toast goes on its own; nothing to hide early. */
    public void cancel() {
    }

    public void setView(android.view.View view) {
        throw new RuntimeException("Stub!");
    }

    public android.view.View getView() {
        throw new RuntimeException("Stub!");
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return duration;
    }

    public void setMargin(float horizontalMargin, float verticalMargin) {
        throw new RuntimeException("Stub!");
    }

    public float getHorizontalMargin() {
        throw new RuntimeException("Stub!");
    }

    public float getVerticalMargin() {
        throw new RuntimeException("Stub!");
    }

    public void setGravity(int gravity, int xOffset, int yOffset) {
        throw new RuntimeException("Stub!");
    }

    public int getGravity() {
        throw new RuntimeException("Stub!");
    }

    public int getXOffset() {
        throw new RuntimeException("Stub!");
    }

    public int getYOffset() {
        throw new RuntimeException("Stub!");
    }

    public static Toast makeText(android.content.Context context, java.lang.CharSequence text, int duration) {
        return new Toast(text, duration);
    }

    public static android.widget.Toast makeText(android.content.Context context, int resId, int duration) throws android.content.res.Resources.NotFoundException {
        throw new RuntimeException("Stub!");
    }

    public void setText(int resId) {
        throw new RuntimeException("Stub!");
    }

    public void setText(java.lang.CharSequence s) {
        text = s;
    }
}
