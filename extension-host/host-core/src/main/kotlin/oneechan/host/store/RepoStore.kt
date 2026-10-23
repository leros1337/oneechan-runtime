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
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RepoDto
import oneechan.host.rpc.RpcException
import oneechan.host.util.AtomicJsonFile
import oneechan.host.util.FileStamp
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
    /** Komikku "disable repo"; repos saved before the flag existed are enabled. */
    val enabled: Boolean = true,
) {
    fun toDto() = RepoDto(indexUrl, name, badgeLabel, signingKeyFingerprint, website, discord, isLegacy, enabled)

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

/** A repo's offer as the update check sees it: the newest version of a package. */
data class OfferEntry(
    val pkgName: String,
    val repoIndexUrl: String,
    val versionCode: Long,
)

/**
 * The part of `available.json` the update check needs (repo, package, newest version), kept in its own
 * small file so the check doesn't parse the whole index. [source] is the index file it was made from;
 * when that file changed since, the summary is built again.
 */
@Serializable
data class OfferSummary(
    val source: FileStamp?,
    val versions: Map<String, Map<String, Long>>,
) {
    fun entries(): List<OfferEntry> = versions.flatMap { (repo, packages) -> packages.map { (pkg, version) -> OfferEntry(pkg, repo, version) } }

    companion object {
        fun of(
            available: List<AvailableExtensionDto>,
            source: FileStamp?,
        ) = OfferSummary(
            source,
            available.groupBy { it.repoIndexUrl }.mapValues { (_, offers) -> offers.groupBy { it.pkgName }.mapValues { (_, same) -> same.maxOf { it.versionCode } } },
        )
    }
}

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
    private val summaryFile =
        AtomicJsonFile(stateDir.resolve("available-versions.json"), OfferSummary.serializer(), json) { OfferSummary(null, emptyMap()) }
    private var offersMemo: OffersMemo? = null

    private class OffersMemo(
        val summary: OfferSummary,
        val repos: List<RepoRecord>,
        val offers: RepoOffers,
    )

    fun repos(): List<RepoRecord> = reposFile.read()

    /** The index URLs of the repos the user turned off. */
    fun disabledIndexUrls(): Set<String> = repos().filterNot { it.enabled }.map { it.indexUrl }.toSet()

    /** What the enabled repos offer (a disabled repo's cached list is kept for when it is turned on again). */
    fun available(): List<AvailableExtensionDto> {
        val disabled = disabledIndexUrls()
        return availableFile.read().filterNot { it.repoIndexUrl in disabled }
    }

    /**
     * Every repo's offers, with the disabled ones marked, for update checks of installed extensions. Built from
     * the small summary of the index, not the index itself, and kept until either changes.
     */
    @Synchronized
    fun offers(): RepoOffers {
        val summary = offerSummary()
        val repos = reposFile.read()
        offersMemo?.takeIf { it.summary === summary && it.repos === repos }?.let { return it.offers }
        val disabled = repos.filterNot { it.enabled }.map { it.indexUrl }.toSet()
        return RepoOffers(summary.entries(), disabled).also { offersMemo = OffersMemo(summary, repos, it) }
    }

    /** The summary of the index as it is now: from its file when that was made from this index, else built (and saved) from the index. */
    private fun offerSummary(): OfferSummary {
        // The stamp is taken before the read: an index replaced in between only makes the next check build the summary again.
        val stamp = availableFile.stamp()
        val summary = summaryFile.read()
        if (summary.source == stamp) return summary
        return OfferSummary.of(availableFile.read(), stamp).also(summaryFile::write)
    }

    /** Replaces the index and its summary (written after it, so the summary's stamp is the new file's). */
    @Synchronized
    private fun updateAvailable(transform: (List<AvailableExtensionDto>) -> List<AvailableExtensionDto>): List<AvailableExtensionDto> {
        val list = transform(availableFile.read())
        availableFile.write(list)
        summaryFile.write(OfferSummary.of(list, availableFile.stamp()))
        return list
    }

    /** Every repo's key, disabled or not: extensions installed from a disabled repo stay trusted. */
    fun fingerprints(): Set<String> = repos().map { it.signingKeyFingerprint.lowercase() }.filter { it.isNotBlank() }.toSet()

    suspend fun setEnabled(
        indexUrl: String,
        enabled: Boolean,
    ): RepoRecord =
        mutex.withLock {
            val repos = repos()
            val record = repos.find { it.indexUrl == indexUrl } ?: throw RpcException(ErrorCode.NOT_FOUND, "Repo $indexUrl is not configured")
            val updated = record.copy(enabled = enabled)
            reposFile.write(repos.map { if (it.indexUrl == indexUrl) updated else it })
            updated
        }

    /** The newest offer of [pkgName] from an enabled repo, preferring [repoIndexUrl] when given. */
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
            // Adding a repo again (or restoring it from a backup) keeps it turned off if it was.
            val wasEnabled = repos().find { it.indexUrl == store.indexUrl }?.enabled ?: true
            val record = RepoRecord.from(store).copy(enabled = wasEnabled)
            reposFile.update { repos -> repos.filterNot { it.indexUrl == record.indexUrl } + record }
            updateAvailable { list -> list.filterNot { it.repoIndexUrl == record.indexUrl } + extensions.map { it.toDto() } }
            record
        }

    suspend fun remove(indexUrl: String) =
        mutex.withLock {
            reposFile.update { repos -> repos.filterNot { it.indexUrl == indexUrl } }
            updateAvailable { list -> list.filterNot { it.repoIndexUrl == indexUrl } }
        }

    suspend fun refresh(): RefreshOutcome =
        mutex.withLock {
            val failed = mutableListOf<String>()
            val refreshed = mutableListOf<RepoRecord>()
            val available = availableFile.read().toMutableList()
            for (repo in repos()) {
                // A disabled repo isn't checked: its record and cached list stay as they are.
                if (!repo.enabled) {
                    refreshed += repo
                    continue
                }
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
            updateAvailable { available }
            val disabled = repos.filterNot { it.enabled }.map { it.indexUrl }.toSet()
            RefreshOutcome(repos, available.count { it.repoIndexUrl !in disabled }, failed)
        }
}

data class UpdateState(
    val hasUpdate: Boolean,
    val isObsolete: Boolean,
)

/** Every repo's offers; [disabled] repos are not checked for updates. */
class RepoOffers(
    all: Collection<OfferEntry>,
    private val disabled: Set<String>,
) {
    private val byPackage = all.groupBy { it.pkgName }

    /**
     * An installed extension has an update when an enabled repo offers a newer version (its own repo
     * first) and that repo isn't disabled. It is obsolete when no repo, enabled or not, offers it
     * any more, so turning a repo off never flags what was installed from it.
     */
    fun updateState(
        pkgName: String,
        versionCode: Long,
        repoIndexUrl: String?,
        isExternal: Boolean,
    ): UpdateState {
        val offers = byPackage[pkgName].orEmpty()
        val newestAnywhere = newest(offers, repoIndexUrl)
        val newestEnabled = if (repoIndexUrl != null && repoIndexUrl in disabled) null else newest(offers.filterNot { it.repoIndexUrl in disabled }, repoIndexUrl)
        return UpdateState(
            hasUpdate = newestEnabled != null && newestEnabled > versionCode,
            isObsolete = !isExternal && (newestAnywhere == null || newestAnywhere < versionCode),
        )
    }

    private fun newest(
        offers: List<OfferEntry>,
        repoIndexUrl: String?,
    ): Long? = offers.filter { repoIndexUrl == null || it.repoIndexUrl == repoIndexUrl }.ifEmpty { offers }.maxOfOrNull { it.versionCode }
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
