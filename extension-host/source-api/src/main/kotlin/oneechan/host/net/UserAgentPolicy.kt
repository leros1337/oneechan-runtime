package oneechan.host.net

object UserAgentPolicy {
    private val androidPrefixes = listOf("Mozilla/5.0 (Linux; Android", "Mozilla/5.0 (Android")

    /** Whether the request's User-Agent should be replaced with the platform default. */
    fun shouldReplace(userAgent: String?): Boolean {
        if (userAgent.isNullOrBlank()) return true
        return NetworkPlatform.replaceAndroidUserAgents && androidPrefixes.any { userAgent.startsWith(it) }
    }
}
