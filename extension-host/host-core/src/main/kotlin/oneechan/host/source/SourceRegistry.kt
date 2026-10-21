package oneechan.host.source

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import oneechan.host.install.InstalledRecord
import oneechan.host.install.InstalledRegistry
import oneechan.host.install.InstalledSourceRecord
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.RpcException
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

/**
 * Lazily instantiated sources of installed extensions. Instances are dropped (and re-created on
 * next use) when the default user agent or a source preference changes, mirroring Suwayomi, since
 * sources compute headers and base URLs once.
 */
class SourceRegistry(
    private val extensionsDir: Path,
    private val installed: InstalledRegistry,
    private val isTrusted: (InstalledRecord) -> Boolean,
) {
    private val logger = KotlinLogging.logger {}
    private val instances = ConcurrentHashMap<Long, Source>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()

    fun jarPath(record: InstalledRecord): Path = extensionsDir.resolve(record.jarPath)

    /** Instantiates every source of [record] (Source or SourceFactory) and caches them. */
    suspend fun load(record: InstalledRecord): List<Source> =
        loadLocks.computeIfAbsent(record.pkgName) { Mutex() }.withLock {
            val instance = PackageTools.loadExtensionSources(jarPath(record), record.className)
            val sources =
                when (instance) {
                    is Source -> listOf(instance)
                    is SourceFactory -> instance.createSources()
                    else -> throw RpcException(ErrorCode.INVALID_EXTENSION, "Unknown source class type ${instance.javaClass.name}")
                }
            sources.forEach { instances[it.id] = it }
            sources
        }

    suspend fun get(sourceId: Long): Source {
        instances[sourceId]?.let { return it }
        val record =
            installed.bySourceId(sourceId)
                ?: throw RpcException(ErrorCode.SOURCE_NOT_FOUND, "No installed extension provides source $sourceId")
        if (!isTrusted(record)) throw RpcException(ErrorCode.UNTRUSTED, "Extension ${record.pkgName} is not trusted")
        load(record)
        return instances[sourceId] ?: throw RpcException(ErrorCode.SOURCE_NOT_FOUND, "Source $sourceId is no longer provided by ${record.pkgName}")
    }

    /** Drops cached instances of [record] so they are re-created on next use. */
    fun reset(record: InstalledRecord) {
        record.sources.forEach { instances.remove(it.id) }
    }

    fun resetAll() {
        instances.clear()
    }

    /** Forgets the extension entirely and releases its class loader (uninstall/update). */
    fun unload(record: InstalledRecord) {
        reset(record)
        PackageTools.jarLoaderMap.remove(jarPath(record).absolutePathString())?.let {
            runCatching { it.close() }.onFailure { e -> logger.warn(e) { "Failed to close class loader of ${record.pkgName}" } }
        }
    }

    companion object {
        fun describe(source: Source): InstalledSourceRecord =
            InstalledSourceRecord(
                id = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = (source as? HttpSource)?.let { runCatching { it.baseUrl }.getOrNull() },
                supportsLatest = runCatching { source.supportsLatest }.getOrDefault(false),
                isConfigurable = source is ConfigurableSource,
                versionId = (source as? HttpSource)?.let { runCatching { it.versionId }.getOrNull() },
            )
    }
}
