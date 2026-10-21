package oneechan.host.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import oneechan.host.rpc.ContentWarningDto
import oneechan.host.util.AtomicJsonFile
import java.nio.file.Path

@Serializable
data class InstalledSourceRecord(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String? = null,
    val supportsLatest: Boolean = false,
    val isConfigurable: Boolean = false,
    val versionId: Int? = null,
)

@Serializable
data class InstalledRecord(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val lang: String,
    val contentWarning: ContentWarningDto,
    val className: String,
    /** Converted jar, relative to the extensions dir. */
    val jarPath: String,
    val apkSha256: String,
    val signatureHashes: List<String>,
    val repoIndexUrl: String? = null,
    val isExternal: Boolean = false,
    val sources: List<InstalledSourceRecord> = emptyList(),
    val installedAt: Long,
) {
    val libVersionNumber: Double get() = libVersion.toDoubleOrNull() ?: 0.0
}

class InstalledRegistry(
    stateDir: Path,
    json: Json,
) {
    private val file = AtomicJsonFile(stateDir.resolve("installed.json"), ListSerializer(InstalledRecord.serializer()), json) { emptyList() }

    fun all(): List<InstalledRecord> = file.read()

    fun get(pkgName: String): InstalledRecord? = all().firstOrNull { it.pkgName == pkgName }

    fun bySourceId(sourceId: Long): InstalledRecord? = all().firstOrNull { record -> record.sources.any { it.id == sourceId } }

    fun put(record: InstalledRecord) {
        file.update { list -> list.filterNot { it.pkgName == record.pkgName } + record }
    }

    fun remove(pkgName: String) {
        file.update { list -> list.filterNot { it.pkgName == pkgName } }
    }
}
