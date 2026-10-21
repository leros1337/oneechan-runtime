package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: overlay of Suwayomi's NetworkHelper
 * (server/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt). Differences:
 *  - no dependency on the server's source registry (user-agent changes are observed by the host);
 *  - the default user agent comes from the platform (the iOS WebView UA) and can be updated;
 *  - the HTTP cache lives in the Android context cache dir instead of a temp dir per launch;
 *  - NativeNetInterceptor first, so requests can run on the platform HTTP stack (NSURLSession).
 * Like upstream, no Brotli interceptor: extension-lib 1.6 sources assert it is absent.
 * Public surface used by extensions: client, cloudflareClient, defaultUserAgentProvider().
 */

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import oneechan.host.net.NativeNetInterceptor
import oneechan.host.platform.Platform
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

class NetworkHelper(
    context: Context,
) {
    val cookieStore = PersistentCookieStore(context)

    init {
        CookieHandler.setDefault(CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL))
    }

    private val userAgent = MutableStateFlow(Platform.defaultUserAgent)
    val userAgentFlow: StateFlow<String> = userAgent.asStateFlow()

    fun defaultUserAgentProvider(): String = userAgent.value

    /** Sources must be re-instantiated after this: their headers are computed lazily once. */
    fun setDefaultUserAgent(value: String) {
        Platform.defaultUserAgent = value
        System.setProperty("http.agent", value)
        userAgent.value = value
    }

    private val cacheDir = File(context.cacheDir, "network_cache")

    private val baseClientBuilder: OkHttpClient.Builder
        get() {
            val logger = KotlinLogging.logger("oneechan.host.http")
            return OkHttpClient
                .Builder()
                .cookieJar(PersistentCookieJar(cookieStore))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.MINUTES)
                .cache(Cache(directory = cacheDir, maxSize = 5L * 1024 * 1024))
                // Must stay first: routes the call through the platform HTTP stack when enabled.
                .addInterceptor(NativeNetInterceptor())
                .addInterceptor(UncaughtExceptionInterceptor())
                .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
                .addNetworkInterceptor(
                    HttpLoggingInterceptor { message -> logger.debug { message } }
                        .apply { level = HttpLoggingInterceptor.Level.BASIC },
                ).addInterceptor(CloudflareInterceptor())
        }

    val client: OkHttpClient by lazy { baseClientBuilder.build() }

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient by lazy { client }
}
