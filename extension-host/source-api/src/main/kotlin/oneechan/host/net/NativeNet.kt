package oneechan.host.net

import java.io.IOException
import java.nio.file.Path

/** A request executed by the platform's HTTP stack (NSURLSession on Apple platforms). */
data class NativeRequest(
    val url: String,
    val method: String,
    /** Header lines in order; repeated names are allowed. */
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
    val timeoutMillis: Long,
) {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

/**
 * The platform's answer. Redirects are never followed natively (OkHttp follows them so every hop
 * gets the jar's cookies); the body is already content-decoded, so no `Content-Encoding` header.
 */
class NativeResponse(
    val code: Int,
    val message: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray? = null,
    /** Alternative to [body] for large payloads: a temp file handed over to the JVM (deleted on close). */
    val bodyFile: Path? = null,
    val protocol: String? = null,
)

/**
 * Executes HTTP requests on the platform network stack. Implementations block the calling
 * (OkHttp) thread, poll [isCancelled] to abort, and throw [IOException]s:
 * `java.net.SocketTimeoutException` for timeouts, [CloudflareBlockedException] for unsolved challenges.
 */
fun interface NativeNet {
    @Throws(IOException::class)
    fun execute(
        request: NativeRequest,
        isCancelled: () -> Boolean,
    ): NativeResponse

    /** When false, calls use OkHttp's own network stack (e.g. no app connected to a dev host). */
    val isAvailable: Boolean get() = true
}
