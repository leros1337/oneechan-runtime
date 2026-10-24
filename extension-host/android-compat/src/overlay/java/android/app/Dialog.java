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
 * Modified for Oneechan: the public API of AOSP's android.app.Dialog (from the API signature),
 * implemented without a window: the content view goes into a private ViewGroup (so it has a
 * parent, as a WebView an extension shows in a Presentation needs), show()/hide()/dismiss()/
 * cancel() track the showing state and call the listeners (on the calling thread), and
 * findViewById() searches the content. There is no window (getWindow() is null), no layout
 * inflation, menu, key or touch dispatch: those members throw "Stub!" as in the generated stub.
 */

package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;

/** A dialog: content, a showing state and its listeners. */
public class Dialog implements DialogInterface, android.view.KeyEvent.Callback, android.view.View.OnCreateContextMenuListener,
        android.view.Window.Callback {
    private final Context context;
    private final ContentView content;
    private boolean created;
    private boolean showing;
    private boolean cancelable = true;
    private boolean canceledOnTouchOutside;
    private CharSequence title;
    private Activity ownerActivity;
    private DialogInterface.OnCancelListener onCancel;
    private DialogInterface.OnDismissListener onDismiss;
    private DialogInterface.OnShowListener onShow;
    private DialogInterface.OnKeyListener onKey;

    /** The content's parent: a ViewGroup that keeps its children (there is no layout pass here). */
    private static final class ContentView extends ViewGroup {
        ContentView(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }
    }

    public Dialog(Context context) {
        this(context, 0);
    }

    /** {@code themeResId} is ignored: there are no themes here. */
    public Dialog(Context context, int themeResId) {
        this.context = context;
        content = new ContentView(context);
    }

    protected Dialog(Context context, boolean cancelable, DialogInterface.OnCancelListener cancelListener) {
        this(context, 0);
        this.cancelable = cancelable;
        onCancel = cancelListener;
    }

    public final Context getContext() {
        return context;
    }

    /** No window here. */
    public android.view.Window getWindow() {
        return null;
    }

    public void setContentView(View view) {
        content.removeAllViews();
        content.addView(view);
    }

    public void setContentView(View view, ViewGroup.LayoutParams params) {
        content.removeAllViews();
        content.addView(view, params);
    }

    public void addContentView(View view, ViewGroup.LayoutParams params) {
        content.addView(view, params);
    }

    public <T extends View> T findViewById(int id) {
        return content.findViewById(id);
    }

    public final <T extends View> T requireViewById(int id) {
        T view = findViewById(id);
        if (view == null) throw new IllegalArgumentException("ID does not reference a View inside this Dialog");
        return view;
    }

    /** Runs onCreate(null) once, as the first show() does. */
    public void create() {
        if (!created) {
            created = true;
            onCreate(null);
        }
    }

    public void show() {
        if (showing) return;
        create();
        onStart();
        showing = true;
        if (onShow != null) onShow.onShow(this);
    }

    public void hide() {
        showing = false;
    }

    public boolean isShowing() {
        return showing;
    }

    @Override
    public void dismiss() {
        if (!showing) return;
        showing = false;
        onStop();
        if (onDismiss != null) onDismiss.onDismiss(this);
    }

    @Override
    public void cancel() {
        if (onCancel != null) onCancel.onCancel(this);
        dismiss();
    }

    public void onBackPressed() {
        if (cancelable) cancel();
    }

    public void setCancelable(boolean flag) {
        cancelable = flag;
    }

    public void setCanceledOnTouchOutside(boolean cancel) {
        if (cancel) cancelable = true;
        canceledOnTouchOutside = cancel;
    }

    public void setOnCancelListener(DialogInterface.OnCancelListener listener) {
        onCancel = listener;
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        onDismiss = listener;
    }

    public void setOnShowListener(DialogInterface.OnShowListener listener) {
        onShow = listener;
    }

    public void setOnKeyListener(DialogInterface.OnKeyListener onKeyListener) {
        onKey = onKeyListener;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public final void setOwnerActivity(Activity activity) {
        ownerActivity = activity;
    }

    public final Activity getOwnerActivity() {
        return ownerActivity;
    }

    /** No focus without a window. */
    public View getCurrentFocus() {
        return null;
    }

    protected void onCreate(android.os.Bundle savedInstanceState) {
    }

    protected void onStart() {
    }

    protected void onStop() {
    }

    public void onAttachedToWindow() {
    }

    public void onDetachedFromWindow() {
    }

    public void onContentChanged() {
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams params) {
    }

    // ---- Without a window: as the generated stub --------------------------------------------

    public void closeOptionsMenu() {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchGenericMotionEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchKeyEvent(android.view.KeyEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchKeyShortcutEvent(android.view.KeyEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchTouchEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean dispatchTrackballEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public final int getVolumeControlStream() {
        throw new RuntimeException("Stub!");
    }

    public void invalidateOptionsMenu() {
        throw new RuntimeException("Stub!");
    }

    public void onActionModeFinished(android.view.ActionMode p0) {
        throw new RuntimeException("Stub!");
    }

    public void onActionModeStarted(android.view.ActionMode p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onContextItemSelected(android.view.MenuItem p0) {
        throw new RuntimeException("Stub!");
    }

    public void onContextMenuClosed(android.view.Menu p0) {
        throw new RuntimeException("Stub!");
    }

    public void onCreateContextMenu(android.view.ContextMenu p0, android.view.View p1, android.view.ContextMenu.ContextMenuInfo p2) {
        throw new RuntimeException("Stub!");
    }

    public boolean onCreateOptionsMenu(android.view.Menu p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onCreatePanelMenu(int p0, android.view.Menu p1) {
        throw new RuntimeException("Stub!");
    }

    public android.view.View onCreatePanelView(int p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onGenericMotionEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onKeyDown(int p0, android.view.KeyEvent p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onKeyLongPress(int p0, android.view.KeyEvent p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onKeyMultiple(int p0, int p1, android.view.KeyEvent p2) {
        throw new RuntimeException("Stub!");
    }

    public boolean onKeyShortcut(int p0, android.view.KeyEvent p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onKeyUp(int p0, android.view.KeyEvent p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onMenuItemSelected(int p0, android.view.MenuItem p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onMenuOpened(int p0, android.view.Menu p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onOptionsItemSelected(android.view.MenuItem p0) {
        throw new RuntimeException("Stub!");
    }

    public void onOptionsMenuClosed(android.view.Menu p0) {
        throw new RuntimeException("Stub!");
    }

    public void onPanelClosed(int p0, android.view.Menu p1) {
        throw new RuntimeException("Stub!");
    }

    public boolean onPrepareOptionsMenu(android.view.Menu p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onPreparePanel(int p0, android.view.View p1, android.view.Menu p2) {
        throw new RuntimeException("Stub!");
    }

    public void onRestoreInstanceState(android.os.Bundle p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onSearchRequested(android.view.SearchEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onSearchRequested() {
        throw new RuntimeException("Stub!");
    }

    public boolean onTouchEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public boolean onTrackballEvent(android.view.MotionEvent p0) {
        throw new RuntimeException("Stub!");
    }

    public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback p0) {
        throw new RuntimeException("Stub!");
    }

    public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback p0, int p1) {
        throw new RuntimeException("Stub!");
    }

    public void openContextMenu(android.view.View p0) {
        throw new RuntimeException("Stub!");
    }

    public void openOptionsMenu() {
        throw new RuntimeException("Stub!");
    }

    public void registerForContextMenu(android.view.View p0) {
        throw new RuntimeException("Stub!");
    }

    public final boolean requestWindowFeature(int p0) {
        throw new RuntimeException("Stub!");
    }

    public void setCancelMessage(android.os.Message p0) {
        throw new RuntimeException("Stub!");
    }

    public void setContentView(int p0) {
        throw new RuntimeException("Stub!");
    }

    public void setDismissMessage(android.os.Message p0) {
        throw new RuntimeException("Stub!");
    }

    public final void setFeatureDrawable(int p0, android.graphics.drawable.Drawable p1) {
        throw new RuntimeException("Stub!");
    }

    public final void setFeatureDrawableAlpha(int p0, int p1) {
        throw new RuntimeException("Stub!");
    }

    public final void setFeatureDrawableResource(int p0, int p1) {
        throw new RuntimeException("Stub!");
    }

    public final void setFeatureDrawableUri(int p0, android.net.Uri p1) {
        throw new RuntimeException("Stub!");
    }

    public void setTitle(int p0) {
        throw new RuntimeException("Stub!");
    }

    public final void setVolumeControlStream(int p0) {
        throw new RuntimeException("Stub!");
    }

    public void takeKeyEvents(boolean p0) {
        throw new RuntimeException("Stub!");
    }

    public void unregisterForContextMenu(android.view.View p0) {
        throw new RuntimeException("Stub!");
    }
}
