package oneechan.host.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * JSON-RPC contract between the Swift app and the extension host (protocol v1, additive changes
 * only). Transport-agnostic: HTTP + SSE in development, JNI byte arrays on device.
 * Source ids are 64-bit and therefore always encoded as JSON strings.
 */

const val PROTOCOL_VERSION = 1

@Serializable
data class RpcRequest(
    val id: String,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class RpcResponse(
    val id: String,
    val result: JsonElement? = null,
    val error: RpcErrorDto? = null,
)

@Serializable
data class RpcErrorDto(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
    val url: String? = null,
    val sourceId: String? = null,
    /** Trimmed stack trace for extension and internal failures (bug reports, diagnostics). */
    val stack: String? = null,
)

@Serializable
data class RpcEvent(
    val event: String,
    val data: JsonElement,
)

@Serializable
enum class ErrorCode {
    INVALID_PARAMS,
    METHOD_NOT_FOUND,
    SOURCE_NOT_FOUND,
    NOT_INSTALLED,
    ALREADY_INSTALLED,
    NOT_FOUND,
    HTTP,
    NETWORK,
    TIMEOUT,
    CLOUDFLARE,
    SOURCE_EXCEPTION,
    UNSUPPORTED_LIB,
    INVALID_EXTENSION,
    UNTRUSTED,
    DEX2JAR_FAILED,
    FILTER_MISMATCH,
    CANCELLED,
    INTERNAL,
}

// ---------------------------------------------------------------------------------------------
// host.*

@Serializable
data class HelloResult(
    val protocolVersion: Int,
    val hostVersion: String,
    val libVersionMin: Double,
    val libVersionMax: Double,
    val jvm: String,
    val capabilities: List<String>,
    /** What runs extension code: `js` (javascriptcore or rhino) and `graphics` (native or awt). */
    val engines: Map<String, String> = emptyMap(),
)

@Serializable
data class InitParams(
    val userAgent: String? = null,
    val replaceAndroidUserAgents: Boolean? = null,
    val bypassEnabled: Boolean? = null,
    /** Route extension HTTP through the app's platform stack (NSURLSession + Cloudflare solver). */
    val nativeNet: Boolean? = null,
    /** The app runs extension WebViews (`webview.*`); until then android.webkit.WebView is unsupported. */
    val webView: Boolean? = null,
)

// ---------------------------------------------------------------------------------------------
// net.* (platform HTTP stack, cookies)

/** Event `net.fetch`: the host asks the app to perform one HTTP request (no redirects, no cookie jar). */
@Serializable
data class NetFetchEvent(
    val id: String,
    val url: String,
    val method: String,
    /** `[name, value]` pairs in order (repeated names allowed). */
    val headers: List<List<String>>,
    val bodyBase64: String? = null,
    val timeoutMillis: Long,
)

/** RPC `net.fetchResult`: the app's answer to a `net.fetch` (body already content-decoded). */
@Serializable
data class NetFetchResultParams(
    val id: String,
    val code: Int = 0,
    val message: String = "",
    val headers: List<List<String>> = emptyList(),
    val bodyBase64: String? = null,
    val protocol: String? = null,
    val error: RpcErrorDto? = null,
)

@Serializable
data class CookieDto(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    /** Epoch milliseconds; null for session cookies. */
    val expiresAt: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = false,
)

/** Event `net.challengeRequired` (JVM-network path only). */
@Serializable
data class ChallengeRequiredEvent(
    val challengeId: String,
    val url: String,
    val host: String,
    val userAgent: String? = null,
)

@Serializable
data class ChallengeResolvedParams(
    val challengeId: String,
    val solved: Boolean,
    val reason: String? = null,
    val cookies: List<CookieDto> = emptyList(),
)

@Serializable
data class AcceptedDto(
    val accepted: Boolean,
)

@Serializable
data class CookiesParams(
    val url: String,
    val cookies: List<CookieDto> = emptyList(),
)

@Serializable
data class UserAgentParams(
    val userAgent: String,
)

// ---------------------------------------------------------------------------------------------
// repo.*

@Serializable
data class RepoDto(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKeyFingerprint: String,
    val website: String,
    val discord: String? = null,
    val isLegacy: Boolean,
    /**
     * Komikku "disable repo": a disabled repo's extensions are not offered and not checked for
     * updates; extensions already installed from it keep working. Absent from older hosts (true).
     */
    val enabled: Boolean = true,
)

@Serializable
data class RepoUrlParams(
    val url: String,
)

/** `repo.setEnabled`: [url] is the repo's `indexUrl`. Returns the updated [RepoDto]. */
@Serializable
data class RepoEnabledParams(
    val url: String,
    val enabled: Boolean,
)

@Serializable
data class RepoRefreshResult(
    val repos: List<RepoDto>,
    val extensionCount: Int,
    val failedRepos: List<String>,
)

// ---------------------------------------------------------------------------------------------
// ext.*

@Serializable
enum class ContentWarningDto { SAFE, MIXED, NSFW }

@Serializable
data class AvailableSourceDto(
    val id: String,
    val name: String,
    val lang: String,
    val homeUrl: String = "",
)

@Serializable
data class AvailableExtensionDto(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val lang: String,
    val contentWarning: ContentWarningDto,
    val repoIndexUrl: String,
    val apkUrl: String,
    val jarUrl: String? = null,
    val iconUrl: String,
    val sources: List<AvailableSourceDto>,
)

@Serializable
data class SourceDto(
    val id: String,
    val name: String,
    val lang: String,
    val pkgName: String,
    val baseUrl: String? = null,
    val supportsLatest: Boolean,
    val isConfigurable: Boolean,
    val contentWarning: ContentWarningDto,
    val versionId: Int? = null,
)

@Serializable
data class InstalledExtensionDto(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val lang: String,
    val contentWarning: ContentWarningDto,
    val repoIndexUrl: String? = null,
    val signatureHash: String? = null,
    val isTrusted: Boolean,
    val hasUpdate: Boolean,
    val isObsolete: Boolean,
    val iconPath: String? = null,
    val sources: List<SourceDto>,
)

@Serializable
data class ExtPkgParams(
    val pkgName: String,
)

@Serializable
data class ExtInstallParams(
    val pkgName: String,
    val repoIndexUrl: String? = null,
    /** Diagnostics: ignore a repo-provided jar and convert the APK on device. */
    val forceApk: Boolean = false,
)

@Serializable
data class ExtInstallFileParams(
    val path: String,
)

@Serializable
data class ExtTrustParams(
    val pkgName: String,
    val versionCode: Long,
    val signatureHash: String,
)

@Serializable
data class ExtListAvailableParams(
    val lang: String? = null,
    val includeNsfw: Boolean = true,
)

@Serializable
enum class InstallPhase { QUEUED, DOWNLOAD, VERIFY, CONVERT, LOAD, DONE, FAILED }

@Serializable
data class ExtProgressEvent(
    val jobId: String,
    val pkgName: String?,
    val phase: InstallPhase,
    val fraction: Double? = null,
    val error: RpcErrorDto? = null,
    val extension: InstalledExtensionDto? = null,
)

@Serializable
data class JobDto(
    val jobId: String,
)

@Serializable
data class IconDto(
    val path: String?,
)

// ---------------------------------------------------------------------------------------------
// source.*

@Serializable
data class MangaDto(
    val url: String,
    val title: String,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val thumbnailUrl: String? = null,
    val updateStrategy: String = "ALWAYS_UPDATE",
    val initialized: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ChapterDto(
    val url: String,
    val name: String,
    val dateUpload: Long = 0,
    val chapterNumber: Float = -1f,
    val scanlator: String? = null,
    val memo: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PageDto(
    val index: Int,
    val url: String = "",
    val imageUrl: String? = null,
)

@Serializable
data class MangasPageDto(
    val mangas: List<MangaDto>,
    val hasNextPage: Boolean,
)

@Serializable
data class MangaUpdateDto(
    val manga: MangaDto,
    val chapters: List<ChapterDto>,
)

@Serializable
data class SourceIdParams(
    val sourceId: String,
)

@Serializable
data class BrowseParams(
    val sourceId: String,
    val page: Int = 1,
)

@Serializable
data class SearchParams(
    val sourceId: String,
    val page: Int = 1,
    val query: String = "",
    val filters: List<FilterDto>? = null,
)

@Serializable
data class MangaParams(
    val sourceId: String,
    val manga: MangaDto,
)

@Serializable
data class MangaUpdateParams(
    val sourceId: String,
    val manga: MangaDto,
    val chapters: List<ChapterDto> = emptyList(),
    val fetchDetails: Boolean = true,
    val fetchChapters: Boolean = true,
)

@Serializable
data class ChapterParams(
    val sourceId: String,
    val chapter: ChapterDto,
)

@Serializable
data class PageParams(
    val sourceId: String,
    val page: PageDto,
)

@Serializable
data class CoverParams(
    val sourceId: String,
    val url: String,
)

@Serializable
data class UrlDto(
    val url: String,
)

@Serializable
data class ImageFileDto(
    val path: String,
    val mimeType: String? = null,
    val size: Long,
)

@Serializable
data class HeadersDto(
    val headers: Map<String, String>,
)

/**
 * `source.related` (params [MangaParams]): Komikku's related titles from the source website, as
 * keiyoushi's extension-lib 1.6 declares them on `CatalogueSource`. [supported]: the extension lists
 * related titles itself (`supportsRelatedMangas`), and [mangas] is that list (without the entry).
 * [disabled] (`disableRelatedMangas`): show none at all. [searchDisabled]
 * (`disableRelatedMangasBySearch`): the extension asks apps not to look for them by searching.
 */
@Serializable
data class RelatedMangasDto(
    val supported: Boolean,
    val mangas: List<MangaDto> = emptyList(),
    val disabled: Boolean = false,
    val searchDisabled: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Filters: positional tree mirroring eu.kanade.tachiyomi.source.model.Filter.

@Serializable
sealed class FilterDto {
    abstract val name: String

    @Serializable
    @SerialName("header")
    data class Header(
        override val name: String,
    ) : FilterDto()

    @Serializable
    @SerialName("separator")
    data class Separator(
        override val name: String = "",
    ) : FilterDto()

    @Serializable
    @SerialName("select")
    data class Select(
        override val name: String,
        val values: List<String>,
        val state: Int,
    ) : FilterDto()

    @Serializable
    @SerialName("text")
    data class Text(
        override val name: String,
        val state: String,
    ) : FilterDto()

    @Serializable
    @SerialName("checkbox")
    data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : FilterDto()

    @Serializable
    @SerialName("tristate")
    data class TriState(
        override val name: String,
        val state: Int,
    ) : FilterDto()

    @Serializable
    @SerialName("group")
    data class Group(
        override val name: String,
        val state: List<FilterDto>,
    ) : FilterDto()

    @Serializable
    @SerialName("sort")
    data class Sort(
        override val name: String,
        val values: List<String>,
        val state: SortSelectionDto? = null,
    ) : FilterDto()
}

@Serializable
data class SortSelectionDto(
    val index: Int,
    val ascending: Boolean,
)

// ---------------------------------------------------------------------------------------------
// Source preferences (androidx.preference subset supported by extensions).

@Serializable
sealed class PreferenceDto {
    abstract val key: String?
    abstract val title: String?
    abstract val summary: String?
    abstract val visible: Boolean
    abstract val enabled: Boolean

    @Serializable
    @SerialName("editText")
    data class EditText(
        override val key: String?,
        override val title: String?,
        override val summary: String?,
        override val visible: Boolean,
        override val enabled: Boolean,
        val dialogTitle: String? = null,
        val dialogMessage: String? = null,
        val text: String? = null,
        val default: String? = null,
        val value: String? = null,
    ) : PreferenceDto()

    @Serializable
    @SerialName("list")
    data class List(
        override val key: String?,
        override val title: String?,
        override val summary: String?,
        override val visible: Boolean,
        override val enabled: Boolean,
        val entries: kotlin.collections.List<String>,
        val entryValues: kotlin.collections.List<String>,
        val default: String? = null,
        val value: String? = null,
    ) : PreferenceDto()

    @Serializable
    @SerialName("multiSelectList")
    data class MultiSelectList(
        override val key: String?,
        override val title: String?,
        override val summary: String?,
        override val visible: Boolean,
        override val enabled: Boolean,
        val dialogTitle: String? = null,
        val dialogMessage: String? = null,
        val entries: kotlin.collections.List<String>,
        val entryValues: kotlin.collections.List<String>,
        val default: kotlin.collections.List<String>? = null,
        val value: kotlin.collections.List<String>? = null,
    ) : PreferenceDto()

    @Serializable
    @SerialName("switch")
    data class Switch(
        override val key: String?,
        override val title: String?,
        override val summary: String?,
        override val visible: Boolean,
        override val enabled: Boolean,
        val default: Boolean = false,
        val value: Boolean = false,
    ) : PreferenceDto()

    @Serializable
    @SerialName("checkBox")
    data class CheckBox(
        override val key: String?,
        override val title: String?,
        override val summary: String?,
        override val visible: Boolean,
        override val enabled: Boolean,
        val default: Boolean = false,
        val value: Boolean = false,
    ) : PreferenceDto()
}

@Serializable
data class SetPreferenceParams(
    val sourceId: String,
    val index: Int,
    val key: String? = null,
    val value: JsonElement,
)

@Serializable
data class SetPreferenceResult(
    val accepted: Boolean,
    val preferences: List<PreferenceDto>,
)

/** `prefs.importAll`: source preferences from a backup (Mihon `.tachibk` or Tachimanga `.tmb`). */
@Serializable
data class PreferencesImportParams(
    val sources: List<SourcePreferencesDto>,
)

@Serializable
data class SourcePreferencesDto(
    /** The SharedPreferences name, `source_<id>`. */
    val sourceKey: String,
    val values: List<PreferenceValueDto>,
)

/** One stored value; `type` is `boolean`, `int`, `long`, `float`, `string` or `stringSet` (a JSON array). */
@Serializable
data class PreferenceValueDto(
    val key: String,
    val type: String,
    val value: JsonElement,
)

@Serializable
data class ImportedDto(
    val imported: Int,
)

// ---------------------------------------------------------------------------------------------
// webview.* (android.webkit.WebView on a WKWebView in the app)

/** Event `webview.command`: what the app's WebView [viewId] must do (created on the first command). */
@Serializable
data class WebViewCommandEvent(
    val viewId: String,
    val command: WebViewCommandDto,
)

@Serializable
sealed class WebViewCommandDto {
    /** `loadUrl` (GET with extra headers) or `postUrl` (POST with a body). */
    @Serializable
    @SerialName("load")
    data class Load(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val method: String = "GET",
        val bodyBase64: String? = null,
        val config: WebViewConfigDto,
    ) : WebViewCommandDto()

    /** `loadData`/`loadDataWithBaseURL`: [baseUrl] is the document's URL and origin; [encoding] "base64" decodes [data]. */
    @Serializable
    @SerialName("loadData")
    data class LoadData(
        val data: String,
        val baseUrl: String? = null,
        val mimeType: String? = null,
        val encoding: String? = null,
        val historyUrl: String? = null,
        val config: WebViewConfigDto,
    ) : WebViewCommandDto()

    @Serializable
    @SerialName("reload")
    data class Reload(
        val config: WebViewConfigDto,
    ) : WebViewCommandDto()

    /** Answered by a `webview.event` `evaluateResult` with the same [requestId]. */
    @Serializable
    @SerialName("evaluate")
    data class Evaluate(
        val requestId: Long,
        val script: String,
    ) : WebViewCommandDto()

    @Serializable
    @SerialName("stop")
    data object Stop : WebViewCommandDto()

    @Serializable
    @SerialName("goBack")
    data object GoBack : WebViewCommandDto()

    @Serializable
    @SerialName("goForward")
    data object GoForward : WebViewCommandDto()

    @Serializable
    @SerialName("destroy")
    data object Destroy : WebViewCommandDto()
}

/** Applied before each navigation: the WebView's settings and which client callbacks are overridden. */
@Serializable
data class WebViewConfigDto(
    val userAgent: String,
    val javaScriptEnabled: Boolean = true,
    val blockNetworkImage: Boolean = false,
    /** Offer every request to the JVM (`webview.intercept`) before loading it. */
    val interceptRequests: Boolean = false,
    /** Ask the JVM before following a navigation (`webview.overrideUrl`). */
    val overrideUrlLoading: Boolean = false,
    /** Forward the page's console (`webview.event` `console`). */
    val consoleMessages: Boolean = false,
    /** `window[name]` objects whose methods call `webview.jsCall`. */
    val javascriptInterfaces: List<JavascriptInterfaceDto> = emptyList(),
)

@Serializable
data class JavascriptInterfaceDto(
    val name: String,
    val methods: List<JavascriptMethodDto>,
)

@Serializable
data class JavascriptMethodDto(
    val name: String,
    /** Non-void: the page gets a Promise of the result; void methods return undefined at once. */
    val returnsValue: Boolean = false,
)

/** RPC `webview.event`: something happened in the app's WebView (answered with [AcceptedDto]). */
@Serializable
data class WebViewEventParams(
    val viewId: String,
    val event: WebViewEventDto,
)

@Serializable
sealed class WebViewEventDto {
    /** The main frame committed a new document. */
    @Serializable
    @SerialName("pageStarted")
    data class PageStarted(
        val url: String,
        val state: WebViewStateDto = WebViewStateDto(),
    ) : WebViewEventDto()

    @Serializable
    @SerialName("pageFinished")
    data class PageFinished(
        val url: String,
        val state: WebViewStateDto = WebViewStateDto(),
    ) : WebViewEventDto()

    /** A load failed; [errorCode] is a WebViewClient.ERROR_* constant. */
    @Serializable
    @SerialName("error")
    data class ReceivedError(
        val request: WebRequestDto,
        val errorCode: Int,
        val description: String,
    ) : WebViewEventDto()

    @Serializable
    @SerialName("console")
    data class Console(
        val message: String,
        /** tip, log, warning, error or debug. */
        val level: String = "log",
        val sourceId: String = "",
        val lineNumber: Int = 0,
    ) : WebViewEventDto()

    /** [value] is the JSON of the completion value, like Android ("null" for undefined or when the script threw; null reads as "null"). */
    @Serializable
    @SerialName("evaluateResult")
    data class EvaluateResult(
        val requestId: Long,
        val value: String? = null,
    ) : WebViewEventDto()

    @Serializable
    @SerialName("renderProcessGone")
    data class RenderProcessGone(
        val didCrash: Boolean = false,
    ) : WebViewEventDto()
}

@Serializable
data class WebViewStateDto(
    val url: String? = null,
    val title: String? = null,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

@Serializable
data class WebRequestDto(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val isForMainFrame: Boolean = true,
    val hasGesture: Boolean = false,
    val isRedirect: Boolean = false,
)

/** RPC `webview.intercept` (→ [WebViewInterceptResult]) and `webview.overrideUrl` (→ [WebViewOverrideResult]). */
@Serializable
data class WebViewRequestParams(
    val viewId: String,
    val request: WebRequestDto,
)

/** The WebViewClient's response; null: the app loads the request itself. */
@Serializable
data class WebViewInterceptResult(
    val response: WebResponseDto? = null,
)

@Serializable
data class WebResponseDto(
    val statusCode: Int = 200,
    val reasonPhrase: String = "OK",
    val mimeType: String? = null,
    val encoding: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val bodyBase64: String? = null,
)

/** [handled]: shouldOverrideUrlLoading returned true, so the app cancels the navigation. */
@Serializable
data class WebViewOverrideResult(
    val handled: Boolean,
)

/**
 * RPC `webview.jsCall`: the page called `window[interfaceName][method](...args)`. Calls of void
 * methods are sent in order with the `webview.event`s (on Android they run before the page finishes).
 */
@Serializable
data class WebViewJsCallParams(
    val viewId: String,
    val interfaceName: String,
    val method: String,
    val args: List<JsonElement> = emptyList(),
)

@Serializable
data class WebViewJsCallResult(
    val result: JsonElement? = null,
)
