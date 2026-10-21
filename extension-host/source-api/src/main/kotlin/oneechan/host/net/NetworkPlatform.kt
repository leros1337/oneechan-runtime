package oneechan.host.net

/**
 * Network policy knobs and platform hooks consulted by the overlaid OkHttp interceptors.
 * Registered by the host at startup (desktop harness or embedded iOS runtime).
 */
object NetworkPlatform {
    /** Platform HTTP stack (NSURLSession). When set, extension traffic never touches JVM sockets. */
    @Volatile
    var nativeNet: NativeNet? = null

    /** Solves anti-bot challenges (a WKWebView on iOS). `null` means challenges fail fast. */
    @Volatile
    var challengeSolver: ChallengeSolver? = null

    /** Global "disable bypass" switch: when false, detected challenges fail immediately. */
    @Volatile
    var bypassEnabled: Boolean = true

    /**
     * Tachimanga parity: Android user agents set by extensions are swapped for the platform user
     * agent, because the platform TLS fingerprint (Safari) would contradict an Android UA.
     */
    @Volatile
    var replaceAndroidUserAgents: Boolean = true

    /** Upper bound a blocked request waits for an in-flight solve (interactive solves included). */
    @Volatile
    var challengeWaitTimeoutMillis: Long = 120_000
}
