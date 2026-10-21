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
)

@Serializable
data class InitParams(
    val userAgent: String? = null,
    val replaceAndroidUserAgents: Boolean? = null,
    val bypassEnabled: Boolean? = null,
    /** Route extension HTTP through the app's platform stack (NSURLSession + Cloudflare solver). */
    val nativeNet: Boolean? = null,
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
)

@Serializable
data class RepoUrlParams(
    val url: String,
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
data class PreferencesDto(
    val preferences: List<PreferenceDto>,
)

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
