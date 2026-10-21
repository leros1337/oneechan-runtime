package eu.kanade.tachiyomi.network.interceptor

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: overlay of Suwayomi's UserAgentInterceptor
 * (server/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/UserAgentInterceptor.kt);
 * additionally replaces Android user agents (see oneechan.host.net.UserAgentPolicy).
 */

import oneechan.host.net.UserAgentPolicy
import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val userAgentProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!UserAgentPolicy.shouldReplace(originalRequest.header("User-Agent"))) {
            return chain.proceed(originalRequest)
        }
        val newRequest =
            originalRequest
                .newBuilder()
                .header("User-Agent", userAgentProvider())
                .build()
        return chain.proceed(newRequest)
    }
}
