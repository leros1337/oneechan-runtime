package eu.kanade.tachiyomi.network.interceptor

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: overlay of Suwayomi's CloudflareInterceptor
 * (server/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt).
 * Upstream delegates to FlareSolverr; here a detected challenge is handed to the platform
 * ChallengeSolver (a WKWebView on iOS), concurrent challenges per host are collapsed into one solve,
 * and the request is retried exactly once.
 */

import oneechan.host.net.ChallengeCoordinator
import oneechan.host.net.ChallengeOutcome
import oneechan.host.net.ChallengeRequest
import oneechan.host.net.CloudflareBlockedException
import oneechan.host.net.CloudflareDetector
import oneechan.host.net.NetworkPlatform
import okhttp3.Interceptor
import okhttp3.Response

class CloudflareInterceptor(
    @Suppress("unused") private val setUserAgent: (String) -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isChallenge()) return response

        response.close()
        if (!NetworkPlatform.bypassEnabled) {
            throw CloudflareBlockedException(request.url.toString(), "bypass disabled")
        }

        val outcome =
            coordinator.solve(
                ChallengeRequest(
                    url = request.url.toString(),
                    host = request.url.host,
                    method = request.method,
                    userAgent = request.header("User-Agent"),
                ),
            )
        if (outcome is ChallengeOutcome.Failed) {
            throw CloudflareBlockedException(request.url.toString(), outcome.reason)
        }

        // The cookie jar is consulted again on the retry, so the fresh clearance cookie is sent.
        val retried = chain.proceed(request)
        if (retried.isChallenge()) {
            retried.close()
            throw CloudflareBlockedException(request.url.toString(), "still challenged after solving")
        }
        return retried
    }

    private fun Response.isChallenge() = CloudflareDetector.isChallenge(code, header("Server"), header("cf-mitigated"))

    companion object {
        val COOKIE_NAMES = listOf("cf_clearance")

        private val coordinator =
            ChallengeCoordinator(
                solver = { NetworkPlatform.challengeSolver },
                waitTimeoutMillis = { NetworkPlatform.challengeWaitTimeoutMillis },
            )
    }
}
