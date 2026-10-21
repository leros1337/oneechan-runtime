package oneechan.host

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * On-disk layout of the extension host.
 *  - [dataDir] must survive app updates and must not be purged by the OS (iOS: Application Support).
 *  - [cacheDir] may be purged at any time (iOS: Caches).
 *  - [tmpDir] holds downloads and conversion scratch space.
 */
class HostPaths(
    val dataDir: Path,
    val cacheDir: Path,
    val tmpDir: Path,
) {
    constructor(dataDir: String, cacheDir: String, tmpDir: String) : this(Path(dataDir), Path(cacheDir), Path(tmpDir))

    /** AndroidCompat root: emulated app dirs, extension SharedPreferences (`settings/`), cookies. */
    val compatRoot: Path = dataDir.resolve("compat")
    val stateDir: Path = dataDir.resolve("state")
    val extensionsDir: Path = dataDir.resolve("extensions")
    val iconsDir: Path = dataDir.resolve("icons")
    val imageCacheDir: Path = cacheDir.resolve("images")
    val downloadsTmpDir: Path = tmpDir.resolve("downloads")

    fun ensure() {
        listOf(dataDir, cacheDir, tmpDir, compatRoot, stateDir, extensionsDir, iconsDir, imageCacheDir, downloadsTmpDir)
            .forEach { it.createDirectories() }
    }
}
