package xyz.nulldev.androidcompat

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Originally part of TachiWeb-Server's AndroidCompat (Apache License 2.0,
 * Copyright 2019 Andy Bao and contributors).
 *
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's AndroidCompatInitializer
 * (AndroidCompat/src/main/java/xyz/nulldev/androidcompat/AndroidCompatInitializer.kt).
 * The KCEF (desktop Chromium) WebView is replaced by the platform WebView backend.
 */

import android.webkit.WebView
import oneechan.host.platform.Platform
import xyz.nulldev.androidcompat.config.ApplicationInfoConfigModule
import xyz.nulldev.androidcompat.config.FilesConfigModule
import xyz.nulldev.androidcompat.config.SystemConfigModule
import xyz.nulldev.ts.config.GlobalConfigManager

class AndroidCompatInitializer {
    fun init() {
        GlobalConfigManager.registerModules(
            FilesConfigModule.register(GlobalConfigManager.config),
            ApplicationInfoConfigModule.register(GlobalConfigManager.config),
            SystemConfigModule.register(GlobalConfigManager.config),
        )

        WebView.setProviderFactory { view: WebView -> Platform.webView.createProvider(view) }

        // Some extensions read this to build their default headers.
        System.setProperty("http.agent", Platform.defaultUserAgent)
    }
}
