package oneechan.host.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import oneechan.host.ExtensionHost
import oneechan.host.webview.WebViewCalls
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Routes RPC requests to [ExtensionHost] and supports cancellation by request id. */
class RpcDispatcher(
    private val host: ExtensionHost,
) {
    private val json = host.json
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = ConcurrentHashMap<String, Job>()

    private val handlers: Map<String, suspend (JsonElement) -> JsonElement> =
        buildMap {
            put("host.hello", unit { host.hello() })
            put("host.init", typed<InitParams, Unit> { host.init(it) })
            put("host.setUserAgent", typed<UserAgentParams, Unit> { host.setUserAgent(it.userAgent) })
            put("host.trimMemory", unit { host.trimMemory() })

            put("net.fetchResult", typed<NetFetchResultParams, AcceptedDto> { host.fetchResult(it) })
            put("net.challengeResolved", typed<ChallengeResolvedParams, AcceptedDto> { host.challengeResolved(it) })
            put("net.cookies.get", typed<CookiesParams, List<CookieDto>> { host.cookies(it.url) })
            put("net.cookies.set", typed<CookiesParams, Unit> { host.storeCookies(it.url, it.cookies) })
            put("net.cookies.clear", unit { host.clearCookies() })

            put("webview.event", typed<WebViewEventParams, AcceptedDto> { WebViewCalls.event(it) })
            put("webview.intercept", typed<WebViewRequestParams, WebViewInterceptResult> { WebViewCalls.intercept(it) })
            put("webview.overrideUrl", typed<WebViewRequestParams, WebViewOverrideResult> { WebViewCalls.overrideUrl(it) })
            put("webview.jsCall", typed<WebViewJsCallParams, WebViewJsCallResult> { WebViewCalls.jsCall(it) })

            put("repo.list", unit { host.repoList() })
            put("repo.add", typed<RepoUrlParams, RepoDto> { host.repoAdd(it.url) })
            put("repo.remove", typed<RepoUrlParams, Unit> { host.repoRemove(it.url) })
            put("repo.setEnabled", typed<RepoEnabledParams, RepoDto> { host.repoSetEnabled(it) })
            put("repo.refresh", unit { host.repoRefresh() })

            put("ext.listAvailable", typed<ExtListAvailableParams, List<AvailableExtensionDto>> { host.extListAvailable(it) })
            put("ext.listInstalled", unit { host.extListInstalled() })
            put("ext.install", typed<ExtInstallParams, JobDto> { host.extInstall(it) })
            put("ext.update", typed<ExtPkgParams, JobDto> { host.extUpdate(it.pkgName) })
            put("ext.installFile", typed<ExtInstallFileParams, JobDto> { host.extInstallFile(it) })
            put("ext.uninstall", typed<ExtPkgParams, Unit> { host.extUninstall(it.pkgName) })
            put("ext.trust", typed<ExtTrustParams, InstalledExtensionDto> { host.extTrust(it) })
            put("ext.icon", typed<ExtPkgParams, IconDto> { host.extIcon(it.pkgName) })

            put("source.list", unit { host.sourceList() })
            put("source.popular", typed<BrowseParams, MangasPageDto> { host.popular(it) })
            put("source.latest", typed<BrowseParams, MangasPageDto> { host.latest(it) })
            put("source.search", typed<SearchParams, MangasPageDto> { host.search(it) })
            put("source.filters", typed<SourceIdParams, List<FilterDto>> { host.filters(it.sourceId) })
            put("source.mangaUpdate", typed<MangaUpdateParams, MangaUpdateDto> { host.mangaUpdate(it) })
            put("source.pages", typed<ChapterParams, List<PageDto>> { host.pages(it) })
            put("source.imageUrl", typed<PageParams, UrlDto> { host.imageUrl(it) })
            put("source.fetchImage", typed<PageParams, ImageFileDto> { host.fetchImage(it) })
            put("source.fetchCover", typed<CoverParams, ImageFileDto> { host.fetchCover(it) })
            put("source.mangaUrl", typed<MangaParams, UrlDto> { host.mangaUrl(it) })
            put("source.chapterUrl", typed<ChapterParams, UrlDto> { host.chapterUrl(it) })
            put("source.related", typed<MangaParams, RelatedMangasDto> { host.related(it) })
            put("source.headers", typed<SourceIdParams, HeadersDto> { host.headers(it.sourceId) })
            put("source.preferences", typed<SourceIdParams, List<PreferenceDto>> { host.preferences(it.sourceId) })
            put("source.setPreference", typed<SetPreferenceParams, SetPreferenceResult> { host.setPreference(it) })
            put("prefs.importAll", typed<PreferencesImportParams, ImportedDto> { host.importPreferences(it) })
        }

    val methods: Set<String> get() = handlers.keys + "rpc.cancel"

    /** Executes [request]; a concurrent `rpc.cancel {id}` cancels it with `CANCELLED`. */
    suspend fun dispatch(request: RpcRequest): RpcResponse {
        if (request.method == "rpc.cancel") return cancel(request)
        val handler = handlers[request.method] ?: return error(request, RpcErrorDto(ErrorCode.METHOD_NOT_FOUND, "Unknown method ${request.method}"))

        val result = CompletableDeferred<RpcResponse>()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                result.complete(
                    try {
                        RpcResponse(request.id, result = handler(request.params))
                    } catch (e: Throwable) {
                        if (e is CancellationException) {
                            RpcResponse(request.id, error = RpcErrorDto(ErrorCode.CANCELLED, "Cancelled"))
                        } else {
                            RpcResponse(request.id, error = e.toRpcError(request.method.startsWith("source."), request.sourceId()))
                        }
                    },
                )
            }
        active[request.id] = job
        job.invokeOnCompletion {
            active.remove(request.id, job)
            result.complete(RpcResponse(request.id, error = RpcErrorDto(ErrorCode.CANCELLED, "Cancelled")))
        }
        job.start()
        return result.await()
    }

    private fun cancel(request: RpcRequest): RpcResponse {
        val target = (request.params as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val cancelled = target?.let { active[it]?.cancel() != null } ?: false
        return RpcResponse(request.id, result = JsonObject(mapOf("cancelled" to JsonPrimitive(cancelled))))
    }

    private fun error(
        request: RpcRequest,
        error: RpcErrorDto,
    ) = RpcResponse(request.id, error = error)

    private fun RpcRequest.sourceId(): Long? =
        runCatching { (params.jsonObject["sourceId"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.getOrNull()

    private inline fun <reified R> unit(crossinline block: suspend () -> R): suspend (JsonElement) -> JsonElement {
        val out = serializer<R>()
        return { json.encodeToJsonElement(out, block()) }
    }

    private inline fun <reified P, reified R> typed(crossinline block: suspend (P) -> R): suspend (JsonElement) -> JsonElement {
        val input: KSerializer<P> = serializer()
        val out: KSerializer<R> = serializer()
        return { params ->
            val decoded =
                try {
                    json.decodeFromJsonElement(input, params)
                } catch (e: SerializationException) {
                    throw RpcException(ErrorCode.INVALID_PARAMS, e.message ?: "Invalid params")
                } catch (e: IllegalArgumentException) {
                    throw RpcException(ErrorCode.INVALID_PARAMS, e.message ?: "Invalid params")
                }
            json.encodeToJsonElement(out, block(decoded))
        }
    }
}
