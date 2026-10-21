package eu.kanade.tachiyomi

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: overlay of Suwayomi's AppInfo
 * (server/src/main/kotlin/eu/kanade/tachiyomi/AppInfo.kt); reports the host's own version and the
 * image formats the iOS reader decodes natively.
 */

/**
 * Used by extensions.
 *
 * @since extension-lib 1.3
 */
object AppInfo {
    const val VERSION_NAME = "1.0.0"

    /** @since extension-lib 1.3 */
    fun getVersionCode(): Int = VERSION_NAME.split('.').joinToString("").toInt()

    /** @since extension-lib 1.3 */
    fun getVersionName(): String = VERSION_NAME

    /** @since extension-lib 1.5 */
    fun getSupportedImageMimeTypes(): List<String> =
        listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heif", "image/avif", "image/jxl")
}
