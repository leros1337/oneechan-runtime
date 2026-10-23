package oneechan.host

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Headers
import okhttp3.Response
import oneechan.host.install.ExtensionInstaller
import oneechan.host.install.InstalledRecord
import oneechan.host.install.InstalledRegistry
import oneechan.host.install.ProgressSink
import oneechan.host.install.TrustStore
import oneechan.host.net.NativeNet
import oneechan.host.net.NetworkPlatform
import oneechan.host.net.RpcChallengeSolver
import oneechan.host.net.RpcNativeNet
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import oneechan.host.platform.Graphics
import oneechan.host.platform.JsEngines
import oneechan.host.platform.WebViewBackends
import oneechan.host.rpc.AvailableExtensionDto
import oneechan.host.rpc.AcceptedDto
import oneechan.host.rpc.ImportedDto
import oneechan.host.rpc.PreferencesImportParams
import oneechan.host.rpc.BrowseParams
import oneechan.host.rpc.ChallengeResolvedParams
import oneechan.host.rpc.CookieDto
import oneechan.host.rpc.NetFetchResultParams
import oneechan.host.rpc.ChapterParams
import oneechan.host.rpc.CoverParams
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.ExtInstallFileParams
import oneechan.host.rpc.ExtInstallParams
import oneechan.host.rpc.ExtListAvailableParams
import oneechan.host.rpc.ExtProgressEvent
import oneechan.host.rpc.ExtTrustParams
import oneechan.host.rpc.FilterDto
import oneechan.host.rpc.HeadersDto
import oneechan.host.rpc.HelloResult
import oneechan.host.rpc.IconDto
import oneechan.host.rpc.ImageFileDto
import oneechan.host.rpc.InitParams
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.InstalledExtensionDto
import oneechan.host.rpc.JobDto
import oneechan.host.rpc.MangaParams
import oneechan.host.rpc.MangaUpdateDto
import oneechan.host.rpc.MangaUpdateParams
import oneechan.host.rpc.MangasPageDto
import oneechan.host.rpc.PROTOCOL_VERSION
import oneechan.host.rpc.PageDto
import oneechan.host.rpc.PageParams
import oneechan.host.rpc.PreferenceDto
import oneechan.host.rpc.RepoDto
import oneechan.host.rpc.RepoEnabledParams
import oneechan.host.rpc.RepoRefreshResult
import oneechan.host.rpc.RpcEvent
import oneechan.host.rpc.RpcException
import oneechan.host.rpc.SearchParams
import oneechan.host.rpc.SetPreferenceParams
import oneechan.host.rpc.SetPreferenceResult
import oneechan.host.rpc.SourceDto
import oneechan.host.rpc.UrlDto
import oneechan.host.rpc.toRpcError
import oneechan.host.source.FilterCodec
import oneechan.host.source.PreferenceCodec
import oneechan.host.source.SourceCalls
import oneechan.host.source.RelatedCalls
import oneechan.host.rpc.RelatedMangasDto
import oneechan.host.source.SourceRegistry
import oneechan.host.source.toDto
import oneechan.host.source.toPage
import oneechan.host.source.toSChapter
import oneechan.host.source.toSManga
import oneechan.host.store.ExtensionStoreClient
import oneechan.host.store.RepoStore
import oneechan.host.webview.RpcWebViewHost
import suwayomi.tachidesk.manga.impl.util.PackageTools
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

fun interface EventSink {
    fun emit(event: RpcEvent)
}

/** All operations exposed over RPC. Thread-safe; source calls run on the IO dispatcher. */
class ExtensionHost(
    val paths: HostPaths,
    /** The platform HTTP stack when embedded (JNI); in development it is reached over RPC. */
    private val platformNativeNet: NativeNet? = null,
    /** Whether an app is listening for events (dev host); the RPC transport needs one. */
    private val appConnected: () -> Boolean = { true },
    private val events: EventSink,
) {
    private val logger = KotlinLogging.logger {}

    val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }

    init {
        HostRuntime.ensureBooted(paths)
    }

    private val network: NetworkHelper by lazy { Injekt.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val installed = InstalledRegistry(paths.stateDir, json)
    private val trustStore = TrustStore(paths.stateDir, json)
    val repos = RepoStore(paths.stateDir, json, ExtensionStoreClient({ network.client }, json))
    val registry = SourceRegistry(paths.extensionsDir, installed) { isTrusted(it) }
    private val installer = ExtensionInstaller(paths, repos, installed, trustStore, registry) { network.client }

    private fun isTrusted(record: InstalledRecord): Boolean =
        trustStore.isTrusted(record.pkgName, record.versionCode, record.signatureHashes, repos.fingerprints())

    // ---- host -------------------------------------------------------------------------------

    fun hello(): HelloResult =
        HelloResult(
            protocolVersion = PROTOCOL_VERSION,
            hostVersion = HOST_VERSION,
            libVersionMin = PackageTools.LIB_VERSION_MIN,
            libVersionMax = PackageTools.LIB_VERSION_MAX,
            jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
            capabilities = listOf("repo.index.pb", "repo.legacy", "ext.installFile", "lib.legacyDispatch", "cloudflare.solver", "net.native", "net.cookies", "webview", "source.related"),
            engines = mapOf("js" to JsEngines.name, "graphics" to Graphics.backend().name, "webview" to WebViewBackends.name),
        )

    private val rpcNativeNet by lazy { RpcNativeNet(events, json, appConnected) }
    private val rpcChallengeSolver by lazy { RpcChallengeSolver(events, json, ::storeCookies) }
    private val rpcWebViewHost by lazy { RpcWebViewHost(events, json, appConnected) }

    fun init(params: InitParams) {
        params.replaceAndroidUserAgents?.let { NetworkPlatform.replaceAndroidUserAgents = it }
        params.bypassEnabled?.let { NetworkPlatform.bypassEnabled = it }
        params.userAgent?.takeIf { it.isNotBlank() && it != network.defaultUserAgentProvider() }?.let(::setUserAgent)
        params.nativeNet?.let { enabled -> NetworkPlatform.nativeNet = if (enabled) platformNativeNet ?: rpcNativeNet else null }
        // An app is connected: it can solve challenges hit on the JVM-network path too.
        NetworkPlatform.challengeSolver = rpcChallengeSolver
        // The app runs extension WebViews on WKWebView (webview.* events and RPCs).
        params.webView?.let { enabled -> WebViewBackends.host = if (enabled) rpcWebViewHost else null }
    }

    fun fetchResult(params: NetFetchResultParams): AcceptedDto = AcceptedDto(rpcNativeNet.complete(params))

    fun challengeResolved(params: ChallengeResolvedParams): AcceptedDto = AcceptedDto(rpcChallengeSolver.complete(params))

    fun cookies(url: String): List<CookieDto> =
        network.cookieStore.get(url.toHttpUrl()).map {
            CookieDto(it.name, it.value, it.domain, it.path, it.expiresAt.takeIf { _ -> it.persistent }, it.secure, it.httpOnly, it.hostOnly)
        }

    fun storeCookies(
        url: String,
        cookies: List<CookieDto>,
    ) {
        val httpUrl = url.toHttpUrl()
        network.cookieStore.addAll(
            httpUrl,
            cookies.map { dto ->
                Cookie
                    .Builder()
                    .name(dto.name)
                    .value(dto.value)
                    .apply { if (dto.hostOnly) hostOnlyDomain(dto.domain.trimStart('.')) else domain(dto.domain.trimStart('.')) }
                    .path(dto.path)
                    .apply { dto.expiresAt?.let(::expiresAt) }
                    .apply { if (dto.secure) secure() }
                    .apply { if (dto.httpOnly) httpOnly() }
                    .build()
            },
        )
    }

    fun clearCookies() {
        network.cookieStore.removeAll()
    }

    fun setUserAgent(userAgent: String) {
        network.setDefaultUserAgent(userAgent)
        registry.resetAll()
    }

    fun trimMemory() {
        registry.resetAll()
        System.gc()
    }

    // ---- repos ------------------------------------------------------------------------------

    fun repoList(): List<RepoDto> = repos.repos().map { it.toDto() }

    suspend fun repoAdd(url: String): RepoDto = withContext(Dispatchers.IO) { repos.add(url).toDto() }

    suspend fun repoRemove(url: String) = repos.remove(url)

    /** Komikku "disable repo": its extensions leave the available list and aren't checked for updates. */
    suspend fun repoSetEnabled(params: RepoEnabledParams): RepoDto = withContext(Dispatchers.IO) { repos.setEnabled(params.url, params.enabled).toDto() }

    suspend fun repoRefresh(): RepoRefreshResult =
        withContext(Dispatchers.IO) {
            val outcome = repos.refresh()
            RepoRefreshResult(outcome.repos.map { it.toDto() }, outcome.extensionCount, outcome.failedRepos)
        }

    // ---- extensions -------------------------------------------------------------------------

    fun extListAvailable(params: ExtListAvailableParams): List<AvailableExtensionDto> =
        repos
            .available()
            .filter { params.lang == null || it.lang == params.lang || it.lang == "all" }
            .filter { params.includeNsfw || it.contentWarning == oneechan.host.rpc.ContentWarningDto.SAFE }

    fun extListInstalled(): List<InstalledExtensionDto> {
        val records = installed.all()
        if (records.isEmpty()) return emptyList()
        // Disabled repos give no updates, and turning one off doesn't make its extensions obsolete.
        val offers = repos.offers()
        return records.map { record ->
            val state = offers.updateState(record.pkgName, record.versionCode, record.repoIndexUrl, record.isExternal)
            record.toDto(hasUpdate = state.hasUpdate, isObsolete = state.isObsolete)
        }
    }

    private fun InstalledRecord.toDto(
        hasUpdate: Boolean = false,
        isObsolete: Boolean = false,
    ) = InstalledExtensionDto(
        pkgName = pkgName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        libVersion = libVersion,
        lang = lang,
        contentWarning = contentWarning,
        repoIndexUrl = repoIndexUrl,
        signatureHash = signatureHashes.firstOrNull(),
        isTrusted = isTrusted(this),
        hasUpdate = hasUpdate,
        isObsolete = isObsolete,
        iconPath = installer.iconPath(pkgName)?.toString(),
        sources = sources.map { it.toSourceDto(this) },
    )

    private fun oneechan.host.install.InstalledSourceRecord.toSourceDto(record: InstalledRecord) =
        SourceDto(
            id = id.toString(),
            name = name,
            lang = lang,
            pkgName = record.pkgName,
            baseUrl = baseUrl,
            supportsLatest = supportsLatest,
            isConfigurable = isConfigurable,
            contentWarning = record.contentWarning,
            versionId = versionId,
        )

    fun extInstall(params: ExtInstallParams): JobDto =
        launchJob(params.pkgName) { sink ->
            installer.installFromRepo(params.pkgName, params.repoIndexUrl, isUpdate = false, sink, forceApk = params.forceApk)
        }

    fun extUpdate(pkgName: String): JobDto =
        launchJob(pkgName) { sink ->
            if (installed.get(pkgName) == null) throw RpcException(ErrorCode.NOT_INSTALLED, "Extension $pkgName is not installed")
            installer.installFromRepo(pkgName, installed.get(pkgName)?.repoIndexUrl, isUpdate = true, sink)
        }

    fun extInstallFile(params: ExtInstallFileParams): JobDto =
        launchJob(null) { sink -> installer.installFile(Path(params.path), sink) }

    suspend fun extUninstall(pkgName: String) = installer.uninstall(pkgName)

    suspend fun extTrust(params: ExtTrustParams): InstalledExtensionDto =
        withContext(Dispatchers.IO) { installer.trust(params.pkgName, params.versionCode, params.signatureHash).toDto() }

    fun extIcon(pkgName: String): IconDto = IconDto(installer.iconPath(pkgName)?.toString())

    private fun launchJob(
        pkgName: String?,
        block: suspend (ProgressSink) -> InstalledRecord,
    ): JobDto {
        val jobId = UUID.randomUUID().toString()
        fun emit(event: ExtProgressEvent) = events.emit(RpcEvent("ext.progress", json.encodeToJsonElement(event)))
        emit(ExtProgressEvent(jobId, pkgName, InstallPhase.QUEUED))
        scope.launch {
            try {
                val record = block { phase, fraction -> if (phase != InstallPhase.DONE) emit(ExtProgressEvent(jobId, pkgName, phase, fraction)) }
                emit(ExtProgressEvent(jobId, record.pkgName, InstallPhase.DONE, 1.0, extension = extListInstalled().first { it.pkgName == record.pkgName }))
            } catch (e: Throwable) {
                logger.warn(e) { "Extension job $jobId ($pkgName) failed" }
                emit(ExtProgressEvent(jobId, pkgName, InstallPhase.FAILED, error = e.toRpcError()))
            }
        }
        return JobDto(jobId)
    }

    // ---- sources ----------------------------------------------------------------------------

    fun sourceList(): List<SourceDto> =
        installed.all().filter(::isTrusted).flatMap { record -> record.sources.map { it.toSourceDto(record) } }

    private suspend fun source(id: String): Source =
        registry.get(id.toLongOrNull() ?: throw RpcException(ErrorCode.INVALID_PARAMS, "Invalid source id '$id'"))

    private suspend fun httpSource(id: String): HttpSource =
        source(id) as? HttpSource ?: throw RpcException(ErrorCode.INVALID_PARAMS, "Source $id is not an HTTP source")

    suspend fun popular(params: BrowseParams): MangasPageDto = io { source(params.sourceId).getPopularManga(params.page).toDto() }

    suspend fun latest(params: BrowseParams): MangasPageDto =
        io {
            val source = source(params.sourceId)
            if (!source.supportsLatest) throw RpcException(ErrorCode.INVALID_PARAMS, "Source ${source.name} has no latest updates")
            source.getLatestUpdates(params.page).toDto()
        }

    suspend fun search(params: SearchParams): MangasPageDto =
        io {
            val source = source(params.sourceId)
            val filters = source.getFilterList().let { fresh -> params.filters?.let { FilterCodec.apply(fresh, it) } ?: fresh }
            source.getSearchManga(params.page, params.query, filters).toDto()
        }

    suspend fun filters(sourceId: String): List<FilterDto> = io { FilterCodec.encode(source(sourceId).getFilterList()) }

    suspend fun mangaUpdate(params: MangaUpdateParams): MangaUpdateDto =
        io {
            val update =
                SourceCalls.mangaUpdate(
                    source(params.sourceId),
                    params.manga.toSManga(),
                    params.chapters.map { it.toSChapter() },
                    params.fetchDetails,
                    params.fetchChapters,
                )
            MangaUpdateDto(update.manga.toDto(), update.chapters.map { it.toDto() })
        }

    suspend fun pages(params: ChapterParams): List<PageDto> =
        io { SourceCalls.pageList(source(params.sourceId), params.chapter.toSChapter()).mapIndexed { i, p -> p.toDto().copy(index = i) } }

    suspend fun imageUrl(params: PageParams): UrlDto = io { UrlDto(httpSource(params.sourceId).getImageUrl(params.page.toPage())) }

    suspend fun fetchImage(params: PageParams): ImageFileDto =
        io {
            val source = httpSource(params.sourceId)
            val page = params.page.toPage()
            if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
            source.getImage(page).use(::saveImage)
        }

    suspend fun fetchCover(params: CoverParams): ImageFileDto =
        io {
            val source = source(params.sourceId)
            val http = source as? HttpSource
            val client = http?.client ?: network.client
            val headers = http?.headers ?: Headers.headersOf()
            client.newCall(GET(params.url, headers)).awaitSuccess().use(::saveImage)
        }

    private fun saveImage(response: Response): ImageFileDto {
        val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
        val target = paths.imageCacheDir.resolve(UUID.randomUUID().toString())
        val tmp = target.resolveSibling("${target.fileName}.part")
        try {
            response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.deleteIfExists()
        }
        return ImageFileDto(target.toString(), mime, Files.size(target))
    }

    suspend fun mangaUrl(params: MangaParams): UrlDto = io { UrlDto(httpSource(params.sourceId).getMangaUrl(params.manga.toSManga())) }

    /** Komikku's related titles from the source website (keiyoushi lib 1.6 `fetchRelatedMangaList`). */
    suspend fun related(params: MangaParams): RelatedMangasDto = io { RelatedCalls.related(source(params.sourceId), params.manga.toSManga()) }

    suspend fun chapterUrl(params: ChapterParams): UrlDto = io { UrlDto(httpSource(params.sourceId).getChapterUrl(params.chapter.toSChapter())) }

    suspend fun headers(sourceId: String): HeadersDto =
        io { HeadersDto(httpSource(sourceId).headers.toMultimap().mapValues { it.value.joinToString(", ") }) }

    suspend fun preferences(sourceId: String): List<PreferenceDto> =
        io {
            val source = source(sourceId) as? ConfigurableSource ?: return@io emptyList()
            PreferenceCodec.encode(preferenceScreen(source).preferences)
        }

    suspend fun setPreference(params: SetPreferenceParams): SetPreferenceResult =
        io {
            val source =
                source(params.sourceId) as? ConfigurableSource
                    ?: throw RpcException(ErrorCode.INVALID_PARAMS, "Source ${params.sourceId} has no preferences")
            val prefs = preferenceScreen(source).preferences
            val pref = prefs.getOrNull(params.index) ?: throw RpcException(ErrorCode.INVALID_PARAMS, "No preference at index ${params.index}")
            if (params.key != null && params.key != pref.key) {
                throw RpcException(ErrorCode.INVALID_PARAMS, "Preference at ${params.index} is '${pref.key}', not '${params.key}'")
            }
            val accepted =
                if (!pref.isEnabled) {
                    false
                } else {
                    val newValue = PreferenceCodec.decodeValue(pref, params.value)
                    // Android semantics: the change listener may veto before anything is persisted.
                    pref.callChangeListener(newValue).also { ok -> if (ok) pref.saveNewValue(newValue) }
                }
            if (accepted) installed.bySourceId(source.id)?.let(registry::reset)
            val fresh = source(params.sourceId) as ConfigurableSource
            SetPreferenceResult(accepted, PreferenceCodec.encode(preferenceScreen(fresh).preferences))
        }

    /**
     * Mihon `restoreSourcePreferences`: values are written as they are, also for sources that aren't
     * installed yet, so they apply once the extension is installed.
     */
    suspend fun importPreferences(params: PreferencesImportParams): ImportedDto =
        io {
            val application = Injekt.get<Application>()
            var imported = 0
            for (source in params.sources) {
                val sourceId = SOURCE_KEY.matchEntire(source.sourceKey)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val editor = application.getSharedPreferences(source.sourceKey, Context.MODE_PRIVATE).edit()
                for (pref in source.values) {
                    val value = pref.value
                    runCatching {
                        when (pref.type) {
                            "boolean" -> editor.putBoolean(pref.key, value.jsonPrimitive.boolean)
                            "int" -> editor.putInt(pref.key, value.jsonPrimitive.int)
                            "long" -> editor.putLong(pref.key, value.jsonPrimitive.long)
                            "float" -> editor.putFloat(pref.key, value.jsonPrimitive.float)
                            "string" -> editor.putString(pref.key, value.jsonPrimitive.content)
                            "stringSet" -> editor.putStringSet(pref.key, value.jsonArray.map { it.jsonPrimitive.content }.toSet())
                            else -> return@runCatching
                        }
                        imported++
                    }.onFailure { logger.warn { "Skipped preference ${pref.key} of ${source.sourceKey}: ${it.message}" } }
                }
                editor.commit()
                installed.bySourceId(sourceId)?.let(registry::reset)
            }
            ImportedDto(imported)
        }

    private fun preferenceScreen(source: ConfigurableSource): PreferenceScreen {
        val screen = PreferenceScreen(Injekt.get<Application>())
        screen.sharedPreferences = source.sourcePreferences()
        source.setupPreferenceScreen(screen)
        return screen
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val HOST_VERSION = "0.1.0"
        private val SOURCE_KEY = Regex("source_(-?\\d+)")
    }
}
