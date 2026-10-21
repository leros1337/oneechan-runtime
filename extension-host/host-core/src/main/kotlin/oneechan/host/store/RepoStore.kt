package oneechan.host.store

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import oneechan.host.rpc.AvailableExtensionDto
import oneechan.host.rpc.AvailableSourceDto
import oneechan.host.rpc.ContentWarningDto
import oneechan.host.rpc.RepoDto
import oneechan.host.util.AtomicJsonFile
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class RepoRecord(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKeyFingerprint: String,
    val website: String,
    val discord: String? = null,
    val isLegacy: Boolean,
    val extensionListUrl: String? = null,
) {
    fun toStore() =
        ExtensionStore(indexUrl, name, badgeLabel, signingKeyFingerprint, ExtensionStore.Contact(website, discord), isLegacy, extensionListUrl)

    fun toDto() = RepoDto(indexUrl, name, badgeLabel, signingKeyFingerprint, website, discord, isLegacy)

    companion object {
        fun from(store: ExtensionStore) =
            RepoRecord(
                indexUrl = store.indexUrl,
                name = store.name,
                badgeLabel = store.badgeLabel,
                signingKeyFingerprint = store.signingKey,
                website = store.contact.website,
                discord = store.contact.discord,
                isLegacy = store.isLegacy,
                extensionListUrl = store.extensionListUrl,
            )
    }
}

data class RefreshOutcome(
    val repos: List<RepoRecord>,
    val extensionCount: Int,
    val failedRepos: List<String>,
)

/** Configured repos and the last successfully fetched extension list of each (works offline). */
class RepoStore(
    stateDir: Path,
    json: Json,
    private val client: ExtensionStoreClient,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private val reposFile = AtomicJsonFile(stateDir.resolve("repos.json"), ListSerializer(RepoRecord.serializer()), json) { emptyList() }
    private val availableFile =
        AtomicJsonFile(stateDir.resolve("available.json"), ListSerializer(AvailableExtensionDto.serializer()), json) { emptyList() }

    fun repos(): List<RepoRecord> = reposFile.read()

    fun available(): List<AvailableExtensionDto> = availableFile.read()

    fun fingerprints(): Set<String> = repos().map { it.signingKeyFingerprint.lowercase() }.filter { it.isNotBlank() }.toSet()

    /** The newest offer of [pkgName], preferring [repoIndexUrl] when given. */
    fun findAvailable(
        pkgName: String,
        repoIndexUrl: String? = null,
    ): AvailableExtensionDto? {
        val offers = available().filter { it.pkgName == pkgName }
        val preferred = repoIndexUrl?.let { url -> offers.filter { it.repoIndexUrl == url } }.orEmpty()
        return (preferred.ifEmpty { offers }).maxByOrNull { it.versionCode }
    }

    suspend fun add(url: String): RepoRecord =
        mutex.withLock {
            val store = client.fetchStore(url.trim())
            val extensions = client.fetchExtensions(store)
            val record = RepoRecord.from(store)
            reposFile.update { repos -> repos.filterNot { it.indexUrl == record.indexUrl } + record }
            availableFile.update { list -> list.filterNot { it.repoIndexUrl == record.indexUrl } + extensions.map { it.toDto() } }
            record
        }

    suspend fun remove(indexUrl: String) =
        mutex.withLock {
            reposFile.update { repos -> repos.filterNot { it.indexUrl == indexUrl } }
            availableFile.update { list -> list.filterNot { it.repoIndexUrl == indexUrl } }
        }

    suspend fun refresh(): RefreshOutcome =
        mutex.withLock {
            val failed = mutableListOf<String>()
            val refreshed = mutableListOf<RepoRecord>()
            val available = available().toMutableList()
            for (repo in repos()) {
                try {
                    val store = client.fetchStore(repo.indexUrl)
                    val extensions = client.fetchExtensions(store)
                    // A store may announce a new index location; it replaces the old entry.
                    available.removeAll { it.repoIndexUrl == repo.indexUrl || it.repoIndexUrl == store.indexUrl }
                    available += extensions.map { it.toDto() }
                    refreshed += RepoRecord.from(store)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn(e) { "Failed to refresh repo ${repo.indexUrl}" }
                    failed += repo.indexUrl
                    refreshed += repo
                }
            }
            val repos = refreshed.distinctBy { it.indexUrl }
            reposFile.write(repos)
            availableFile.write(available)
            RefreshOutcome(repos, available.size, failed)
        }
}

fun ContentWarning.toDto(): ContentWarningDto =
    when (this) {
        ContentWarning.SAFE -> ContentWarningDto.SAFE
        ContentWarning.MIXED -> ContentWarningDto.MIXED
        ContentWarning.NSFW -> ContentWarningDto.NSFW
    }

fun ExtensionInfo.toDto() =
    AvailableExtensionDto(
        pkgName = pkgName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        libVersion = extensionLib,
        lang = lang,
        contentWarning = contentWarning.toDto(),
        repoIndexUrl = storeIndexUrl,
        apkUrl = apkUrl,
        jarUrl = jarUrl,
        iconUrl = iconUrl,
        sources = sources.map { AvailableSourceDto(it.id.toString(), it.name, it.lang, it.homeUrl) },
    )
