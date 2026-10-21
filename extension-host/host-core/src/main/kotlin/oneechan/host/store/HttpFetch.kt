package oneechan.host.store

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * GitHub-hosted repos are frequently unreachable or rate limited in some regions; like Tachimanga,
 * retry through the jsDelivr GitHub mirror when the direct request fails.
 */
object JsDelivr {
    private val raw = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
    private val githubRaw = Regex("""^https://github\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)$""")

    fun mirror(url: String): String? {
        val match = raw.matchEntire(url) ?: githubRaw.matchEntire(url) ?: return null
        val (owner, repo, ref, path) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$path"
    }
}

suspend fun OkHttpClient.getWithFallback(
    url: String,
    forceNetwork: Boolean = true,
): Response {
    val cache = if (forceNetwork) CacheControl.FORCE_NETWORK else CacheControl.Builder().build()
    val primaryError: Exception =
        try {
            val response = newCall(GET(url, cache = cache)).await()
            if (response.isSuccessful) return response
            response.close()
            HttpException(response.code)
        } catch (e: IOException) {
            e
        }
    val mirror = JsDelivr.mirror(url) ?: throw primaryError
    val response = newCall(GET(mirror, cache = cache)).await()
    if (!response.isSuccessful) {
        response.close()
        throw primaryError
    }
    return response
}

suspend fun OkHttpClient.getBytesWithFallback(url: String): ByteArray = getWithFallback(url).use { it.body.bytes() }
