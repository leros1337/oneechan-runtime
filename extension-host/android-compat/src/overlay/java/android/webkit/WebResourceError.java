/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * Modified for Oneechan: the public API of AOSP's android.webkit.WebResourceError, with a usable
 * constructor so the app's WebView backend can subclass it (the generated stub's throws "Stub!").
 */

package android.webkit;

/** Information about a resource loading error, passed to WebViewClient#onReceivedError. */
public abstract class WebResourceError {
    /** For WebView backends only (hidden on Android). */
    public WebResourceError() {}

    /** One of the WebViewClient.ERROR_* constants. */
    public abstract int getErrorCode();

    /** A string describing the error, localized by the WebView. */
    public abstract CharSequence getDescription();
}
