package oneechan.host.net

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Response classification shared by the JVM fallback interceptor and the native transport. */
object CloudflareDetector {
    private val serverNames = setOf("cloudflare", "cloudflare-nginx")
    private val statusCodes = setOf(403, 429, 503)

    fun isChallenge(
        code: Int,
        server: String?,
        cfMitigated: String?,
    ): Boolean {
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true
        val isCloudflare = server?.trim()?.lowercase() in serverNames
        return isCloudflare && code in statusCodes
    }
}

data class ChallengeRequest(
    val url: String,
    val host: String,
    val method: String,
    val userAgent: String?,
)

sealed interface ChallengeOutcome {
    data object Solved : ChallengeOutcome

    data class Failed(
        val reason: String,
    ) : ChallengeOutcome
}

/** Blocking solver contract; implementations may present UI and take tens of seconds. */
fun interface ChallengeSolver {
    fun solve(request: ChallengeRequest): ChallengeOutcome
}

class CloudflareBlockedException(
    val url: String,
    val reason: String,
) : IOException("Blocked by Cloudflare: $reason")

/**
 * Collapses concurrent challenges for the same host into a single solve: the first blocked
 * request runs the solver, the rest wait for its outcome.
 */
class ChallengeCoordinator(
    private val solver: () -> ChallengeSolver?,
    private val waitTimeoutMillis: () -> Long,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ChallengeOutcome>>()

    fun solve(request: ChallengeRequest): ChallengeOutcome {
        val activeSolver = solver() ?: return ChallengeOutcome.Failed("no challenge solver available")
        val mine = CompletableFuture<ChallengeOutcome>()
        val existing = inFlight.putIfAbsent(request.host, mine)
        if (existing != null) return await(existing)

        try {
            mine.complete(
                try {
                    activeSolver.solve(request)
                } catch (e: Exception) {
                    ChallengeOutcome.Failed(e.message ?: e.javaClass.simpleName)
                },
            )
        } finally {
            inFlight.remove(request.host, mine)
        }
        return mine.get()
    }

    private fun await(future: CompletableFuture<ChallengeOutcome>): ChallengeOutcome =
        try {
            future.get(waitTimeoutMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            ChallengeOutcome.Failed("timed out waiting for challenge solve")
        } catch (e: ExecutionException) {
            ChallengeOutcome.Failed(e.cause?.message ?: "challenge solve failed")
        }
}
