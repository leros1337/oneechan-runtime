package oneechan.host.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import oneechan.host.EventSink
import oneechan.host.rpc.ChallengeRequiredEvent
import oneechan.host.rpc.ChallengeResolvedParams
import oneechan.host.rpc.RpcEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [ChallengeSolver] for the JVM-network path (native transport off): the app solves the challenge
 * in a WKWebView after `net.challengeRequired` and answers with `net.challengeResolved`, including
 * the WebView's cookies, which are stored in the host jar before the request is retried.
 */
class RpcChallengeSolver(
    private val events: EventSink,
    private val json: Json,
    private val storeCookies: (url: String, cookies: List<oneechan.host.rpc.CookieDto>) -> Unit,
) : ChallengeSolver {
    private val pending = ConcurrentHashMap<String, CompletableFuture<ChallengeResolvedParams>>()

    override fun solve(request: ChallengeRequest): ChallengeOutcome {
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<ChallengeResolvedParams>()
        pending[id] = future
        try {
            events.emit(RpcEvent("net.challengeRequired", json.encodeToJsonElement(ChallengeRequiredEvent(id, request.url, request.host, request.userAgent))))
            val result =
                try {
                    future.get(NetworkPlatform.challengeWaitTimeoutMillis, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    return ChallengeOutcome.Failed("timed out waiting for the app")
                }
            if (!result.solved) return ChallengeOutcome.Failed(result.reason ?: "not solved")
            storeCookies(request.url, result.cookies)
            return ChallengeOutcome.Solved
        } finally {
            pending.remove(id)
        }
    }

    fun complete(result: ChallengeResolvedParams): Boolean = pending[result.challengeId]?.complete(result) ?: false
}
