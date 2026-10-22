/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.webkit.RenderProcessGoneDetail, with a
 * usable constructor so the app's WebView backend can subclass it (the generated stub's throws "Stub!").
 */

package android.webkit;

/** Why a WebView's render process ended, passed to WebViewClient#onRenderProcessGone. */
public abstract class RenderProcessGoneDetail {
    /** For WebView backends only (hidden on Android). */
    public RenderProcessGoneDetail() {}

    /** True when the render process crashed; false when the system killed it (e.g. for memory). */
    public abstract boolean didCrash();

    /** The renderer priority (a WebView.RENDERER_PRIORITY_* value) the process had when it ended. */
    public abstract int rendererPriorityAtExit();
}
