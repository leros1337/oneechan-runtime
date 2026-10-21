package oneechan.host.install

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.bean.Icon
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import oneechan.host.HostPaths
import oneechan.host.rpc.ContentWarningDto
import oneechan.host.rpc.ErrorCode
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.RpcException
import oneechan.host.rpc.describe
import oneechan.host.source.SourceRegistry
import oneechan.host.store.JsDelivr
import oneechan.host.store.RepoStore
import oneechan.host.util.sha256Hex
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.ResourceArscIconParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

fun interface ProgressSink {
    fun report(
        phase: InstallPhase,
        fraction: Double?,
    )
}

/**
 * Installs Tachiyomi/Mihon extension packages the way Suwayomi does (APK manifest validation,
 * extension-lib range check, dex2jar + Android class fix-ups, asset merging, Source/SourceFactory
 * instantiation), with Mihon's trust model and Android's "same signer for updates" rule.
 * Converted jars are cached per (package, version, APK hash), so reinstalling is instant.
 */
@OptIn(ExperimentalPathApi::class)
class ExtensionInstaller(
    private val paths: HostPaths,
    private val repos: RepoStore,
    private val installed: InstalledRegistry,
    private val trust: TrustStore,
    private val registry: SourceRegistry,
    private val client: () -> OkHttpClient,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()

    /**
     * Like Suwayomi, a repo-provided JVM jar (keiyoushi's `jarUrl`) is preferred over converting
     * the APK: it is built for the JVM and signed with the repo key, so it is verified and trusted
     * exactly like the APK would be. The APK + dex2jar path is used when no jar is offered, when
     * the jar cannot be downloaded, or when [forceApk] is set.
     */
    suspend fun installFromRepo(
        pkgName: String,
        repoIndexUrl: String?,
        isUpdate: Boolean,
        progress: ProgressSink,
        forceApk: Boolean = false,
    ): InstalledRecord =
        mutex.withLock {
            val offer =
                repos.findAvailable(pkgName, repoIndexUrl)
                    ?: throw RpcException(ErrorCode.NOT_FOUND, "Extension $pkgName is not offered by any configured repo")
            if (!isUpdate && installed.get(pkgName) != null) {
                throw RpcException(ErrorCode.ALREADY_INSTALLED, "Extension $pkgName is already installed")
            }
            progress.report(InstallPhase.DOWNLOAD, 0.0)
            val jarUrl = offer.jarUrl?.takeUnless { forceApk }
            if (jarUrl != null) {
                val jar =
                    try {
                        download(jarUrl, ".jar", progress)
                    } catch (e: IOException) {
                        logger.warn(e) { "Jar download failed for $pkgName, falling back to the APK" }
                        null
                    }
                if (jar != null) {
                    return@withLock installJar(jar, offer.repoIndexUrl, isExternal = false, isUpdate = isUpdate, expected = offer, progress = progress)
                }
            }
            val apk = download(offer.apkUrl, ".apk", progress)
            installApk(apk, offer.repoIndexUrl, isExternal = false, isUpdate = isUpdate, progress)
        }

    suspend fun installFile(
        source: Path,
        progress: ProgressSink,
    ): InstalledRecord =
        mutex.withLock {
            val name = source.name
            if (!source.exists()) throw RpcException(ErrorCode.INVALID_PARAMS, "File not found: $source")
            val copy = paths.downloadsTmpDir.createDirectories().resolve("${UUID.randomUUID()}-$name")
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING)
            when {
                name.endsWith(".apk", ignoreCase = true) -> installApk(copy, null, isExternal = true, isUpdate = false, progress)
                name.endsWith(".jar", ignoreCase = true) -> installJar(copy, null, isExternal = true, isUpdate = false, expected = null, progress = progress)
                else -> {
                    copy.deleteIfExists()
                    throw RpcException(ErrorCode.INVALID_PARAMS, "Unsupported extension file '$name' (expected .apk or .jar)")
                }
            }
        }

    suspend fun uninstall(pkgName: String) =
        mutex.withLock {
            val record = installed.get(pkgName) ?: throw RpcException(ErrorCode.NOT_INSTALLED, "Extension $pkgName is not installed")
            registry.unload(record)
            installed.remove(pkgName)
            trust.revoke(pkgName)
            paths.extensionsDir.resolve(pkgName).deleteRecursively()
            paths.iconsDir.listDirectoryEntries("$pkgName.*").forEach { it.deleteIfExists() }
        }

    /** Trusts the exact package version/signer and loads its sources. */
    suspend fun trust(
        pkgName: String,
        versionCode: Long,
        signatureHash: String,
    ): InstalledRecord =
        mutex.withLock {
            val record = installed.get(pkgName) ?: throw RpcException(ErrorCode.NOT_INSTALLED, "Extension $pkgName is not installed")
            if (record.versionCode != versionCode || signatureHash.lowercase() !in record.signatureHashes.map { it.lowercase() }) {
                throw RpcException(ErrorCode.INVALID_PARAMS, "Trust request does not match the installed $pkgName")
            }
            trust.trust(pkgName, versionCode, signatureHash)
            val sources = registry.load(record).map(SourceRegistry::describe)
            val updated = record.copy(sources = sources, lang = languageOf(sources.map { it.lang }))
            installed.put(updated)
            updated
        }

    // -----------------------------------------------------------------------------------------

    private suspend fun installApk(
        apk: Path,
        repoIndexUrl: String?,
        isExternal: Boolean,
        isUpdate: Boolean,
        progress: ProgressSink,
    ): InstalledRecord {
        try {
            progress.report(InstallPhase.VERIFY, null)
            val meta =
                try {
                    PackageInspector.inspectApk(apk)
                } catch (e: Exception) {
                    throw RpcException(ErrorCode.INVALID_EXTENSION, "Not a valid APK: ${e.message}", cause = e)
                }
            val spec = validate(meta, isExternal, isUpdate)
            val apkSha = sha256Hex(apk)
            val versionDir = versionDir(meta, apkSha)
            val jar = versionDir.resolve(JAR_NAME)
            if (!jar.exists()) {
                progress.report(InstallPhase.CONVERT, null)
                convert(apk, versionDir)
            }
            extractIcon(apk, meta.packageName)
            return activate(meta, spec, versionDir, apkSha, repoIndexUrl, isExternal, progress)
        } finally {
            apk.deleteIfExists()
        }
    }

    private suspend fun installJar(
        jarFile: Path,
        repoIndexUrl: String?,
        isExternal: Boolean,
        isUpdate: Boolean,
        expected: oneechan.host.rpc.AvailableExtensionDto?,
        progress: ProgressSink,
    ): InstalledRecord {
        try {
            progress.report(InstallPhase.VERIFY, null)
            val jarSha = sha256Hex(jarFile)
            val signers = PackageInspector.jarSignerHashes(jarFile)
            val meta =
                try {
                    // An unsigned jar has no signer: its content hash acts as the trust identity.
                    PackageInspector.inspectJar(jarFile).copy(signatureHashes = signers.ifEmpty { listOf(jarSha) })
                } catch (e: Exception) {
                    throw RpcException(ErrorCode.INVALID_EXTENSION, "Not a valid extension jar: ${e.message}", cause = e)
                }
            if (expected != null && (meta.packageName != expected.pkgName || meta.versionCode != expected.versionCode)) {
                throw RpcException(
                    ErrorCode.INVALID_EXTENSION,
                    "Jar is ${meta.packageName} v${meta.versionCode}, repo announced ${expected.pkgName} v${expected.versionCode}",
                )
            }
            val spec = validate(meta, isExternal, isUpdate)
            val versionDir = versionDir(meta, jarSha)
            if (!versionDir.resolve(JAR_NAME).exists()) {
                val work = workDir(versionDir)
                // The signature was verified above; META-INF is dropped like for converted APKs.
                stripMetaInf(jarFile, work.resolve(JAR_NAME))
                commit(work, versionDir)
            }
            extractJarIcon(jarFile, meta.packageName)
            return activate(meta, spec, versionDir, jarSha, repoIndexUrl, isExternal, progress)
        } finally {
            jarFile.deleteIfExists()
        }
    }

    private fun stripMetaInf(
        jar: Path,
        out: Path,
    ) {
        ZipOutputStream(out.outputStream().buffered()).use { output ->
            ZipInputStream(jar.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                        output.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(output)
                        output.closeEntry()
                    }
                }
            }
        }
    }

    private fun extractJarIcon(
        jar: Path,
        pkgName: String,
    ) {
        try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(jar).get().use { zip ->
                ResourceArscIconParser.extractIcon(zip).use { saveIcon(pkgName, it.readBytes()) }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract icon from jar $pkgName" }
        }
    }

    private data class ValidatedPackage(
        val className: String,
        val name: String,
        val libVersion: String,
        val contentWarning: ContentWarningDto,
    )

    /** Upstream (Suwayomi `Extension.installExtension`) validation rules, in the same order. */
    private fun validate(
        meta: PackageMeta,
        isExternal: Boolean,
        isUpdate: Boolean,
    ): ValidatedPackage {
        val existing = installed.get(meta.packageName)
        val isExternalUpdate = isExternal && meta.versionCode > (existing?.versionCode ?: -1)
        if (existing != null && !isExternalUpdate && !isUpdate) {
            throw RpcException(ErrorCode.ALREADY_INSTALLED, "Extension \"${meta.packageName} (v${meta.versionName})\" already installed")
        }
        if (PackageTools.EXTENSION_FEATURE !in meta.reqFeatures) {
            throw RpcException(ErrorCode.INVALID_EXTENSION, "This file is not a Tachiyomi extension")
        }
        val libVersion =
            meta.versionName.substringBeforeLast('.').toDoubleOrNull()
                ?: throw RpcException(ErrorCode.INVALID_EXTENSION, "Invalid extension version '${meta.versionName}'")
        if (libVersion < PackageTools.LIB_VERSION_MIN || libVersion > PackageTools.LIB_VERSION_MAX) {
            throw RpcException(
                ErrorCode.UNSUPPORTED_LIB,
                "Lib version is $libVersion, while only versions ${PackageTools.LIB_VERSION_MIN} to ${PackageTools.LIB_VERSION_MAX} are allowed",
            )
        }
        // Android refuses updates signed by a different certificate; so do we. Jar installs carry
        // their content hash instead of a signer, so they are exempt.
        val existingIsJar = existing != null && existing.signatureHashes == listOf(existing.apkSha256)
        if (existing != null && !existingIsJar && existing.signatureHashes.isNotEmpty() && meta.signatureHashes.isNotEmpty() &&
            existing.signatureHashes.map { it.lowercase() }.intersect(meta.signatureHashes.map { it.lowercase() }.toSet()).isEmpty()
        ) {
            throw RpcException(ErrorCode.UNTRUSTED, "Signature of ${meta.packageName} changed; uninstall the existing extension first")
        }

        val contentWarning =
            listOf(PackageTools.METADATA_CONTENT_WARNING, PackageTools.METADATA_NSFW)
                .firstNotNullOfOrNull { key -> meta.metaData[key]?.trim()?.toIntOrNull()?.takeIf { it != 0 } }
                ?: 0
        val sourceClass =
            meta.metaData[PackageTools.METADATA_SOURCE_CLASS]?.trim()
                ?: throw RpcException(ErrorCode.INVALID_EXTENSION, "Missing ${PackageTools.METADATA_SOURCE_CLASS} metadata")
        val className = if (sourceClass.startsWith(".")) meta.packageName + sourceClass else sourceClass
        val name =
            meta.metaData[PackageTools.METADATA_NAME]
                ?: meta.label?.substringAfter("Tachiyomi: ")
                ?: throw RpcException(ErrorCode.INVALID_EXTENSION, "Could not resolve extension name")
        val extensionLib =
            meta.metaData[PackageTools.METADATA_EXTENSION_LIB]?.takeUnless { it == "0" }
                ?: meta.versionName.substringBeforeLast('.')
        return ValidatedPackage(
            className = className,
            name = name,
            libVersion = extensionLib,
            contentWarning =
                when (contentWarning) {
                    0 -> ContentWarningDto.SAFE
                    1 -> ContentWarningDto.MIXED
                    else -> ContentWarningDto.NSFW
                },
        )
    }

    private suspend fun activate(
        meta: PackageMeta,
        spec: ValidatedPackage,
        versionDir: Path,
        packageSha: String,
        repoIndexUrl: String?,
        isExternal: Boolean,
        progress: ProgressSink,
    ): InstalledRecord {
        progress.report(InstallPhase.LOAD, null)
        val previous = installed.get(meta.packageName)
        val candidate =
            InstalledRecord(
                pkgName = meta.packageName,
                name = spec.name,
                versionName = meta.versionName,
                versionCode = meta.versionCode,
                libVersion = spec.libVersion,
                lang = "",
                contentWarning = spec.contentWarning,
                className = spec.className,
                jarPath = paths.extensionsDir.relativize(versionDir.resolve(JAR_NAME)).toString(),
                apkSha256 = packageSha,
                signatureHashes = meta.signatureHashes,
                repoIndexUrl = repoIndexUrl ?: previous?.repoIndexUrl,
                isExternal = isExternal,
                installedAt = System.currentTimeMillis(),
            )
        val trusted = trust.isTrusted(candidate.pkgName, candidate.versionCode, candidate.signatureHashes, repos.fingerprints())

        if (previous != null) registry.unload(previous)
        val sources =
            if (trusted) {
                try {
                    registry.load(candidate).map(SourceRegistry::describe)
                } catch (e: Throwable) {
                    registry.unload(candidate)
                    versionDir.deleteRecursively()
                    throw RpcException(ErrorCode.INVALID_EXTENSION, "Failed to load ${candidate.pkgName}: ${e.describe()}", cause = e)
                }
            } else {
                // Untrusted code is never executed; show what the repo says it provides.
                repos
                    .findAvailable(candidate.pkgName, candidate.repoIndexUrl)
                    ?.sources
                    ?.map { InstalledSourceRecord(id = it.id.toLong(), name = it.name, lang = it.lang, baseUrl = it.homeUrl.ifBlank { null }) }
                    .orEmpty()
            }
        val record = candidate.copy(sources = sources, lang = languageOf(sources.map { it.lang }))
        installed.put(record)
        removeOtherVersions(record.pkgName, keep = versionDir)
        progress.report(InstallPhase.DONE, 1.0)
        return record
    }

    private fun languageOf(langs: List<String>): String =
        langs.toSet().let {
            when (it.size) {
                0 -> ""
                1 -> it.first()
                else -> "all"
            }
        }

    private fun versionDir(
        meta: PackageMeta,
        packageSha: String,
    ): Path = paths.extensionsDir.resolve(meta.packageName).resolve("${meta.versionCode}-${packageSha.take(16)}-c$CONVERTER_VERSION")

    private fun workDir(versionDir: Path): Path {
        val work = versionDir.resolveSibling("${versionDir.name}.work")
        if (work.exists()) work.deleteRecursively()
        return work.createDirectories()
    }

    private fun commit(
        work: Path,
        versionDir: Path,
    ) {
        if (versionDir.exists()) versionDir.deleteRecursively()
        Files.move(work, versionDir, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun removeOtherVersions(
        pkgName: String,
        keep: Path,
    ) {
        val pkgDir = paths.extensionsDir.resolve(pkgName)
        if (!pkgDir.isDirectory()) return
        pkgDir.listDirectoryEntries().filter { it != keep }.forEach {
            runCatching { it.deleteRecursively() }.onFailure { e -> logger.warn(e) { "Could not delete $it" } }
        }
    }

    /** dex2jar (+ BytecodeEditor), then merge `assets/` from the APK and drop `META-INF/`. */
    private fun convert(
        apk: Path,
        versionDir: Path,
    ) {
        val work = workDir(versionDir)
        val rawJar = work.resolve("raw.jar")
        try {
            PackageTools.dex2jar(apk, rawJar)
        } catch (e: Exception) {
            throw RpcException(ErrorCode.DEX2JAR_FAILED, "dex2jar failed: ${e.message}", cause = e)
        }
        mergeAssets(apk, rawJar, work.resolve(JAR_NAME))
        rawJar.deleteIfExists()
        commit(work, versionDir)
    }

    private fun mergeAssets(
        apk: Path,
        jar: Path,
        out: Path,
    ) {
        val written = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { output ->
            ZipInputStream(jar.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    if (!entry.name.startsWith("META-INF/") && written.add(entry.name)) {
                        output.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(output)
                        output.closeEntry()
                    }
                }
            }
            ZipFile(apk.toFile()).use { apkZip ->
                apkZip.entries().asSequence().filter { it.name.startsWith("assets/") && !it.isDirectory }.forEach { entry ->
                    if (written.add(entry.name)) {
                        output.putNextEntry(ZipEntry(entry.name))
                        apkZip.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
        }
    }

    private fun extractIcon(
        apk: Path,
        pkgName: String,
    ) {
        try {
            val data =
                ApkFile(apk.toFile()).use { parsed ->
                    parsed.allIcons
                        .filterIsInstance<Icon>()
                        .mapNotNull { icon -> icon.data?.let { it to icon.density } }
                        .maxByOrNull { it.second }
                        ?.first
                } ?: return
            saveIcon(pkgName, data)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract icon from $pkgName" }
        }
    }

    private fun saveIcon(
        pkgName: String,
        data: ByteArray,
    ) {
        val ext =
            when {
                data.size > 12 && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                data.size > 3 && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() -> "png"
                else -> "img"
            }
        paths.iconsDir.createDirectories()
        paths.iconsDir.listDirectoryEntries("$pkgName.*").forEach { it.deleteIfExists() }
        paths.iconsDir.resolve("$pkgName.$ext").writeBytes(data)
    }

    fun iconPath(pkgName: String): Path? =
        paths.iconsDir.takeIf { it.isDirectory() }?.listDirectoryEntries("$pkgName.*")?.firstOrNull()

    private suspend fun download(
        url: String,
        extension: String,
        progress: ProgressSink,
    ): Path {
        val target = paths.downloadsTmpDir.createDirectories().resolve("${UUID.randomUUID()}$extension")
        val candidates = listOfNotNull(url, JsDelivr.mirror(url))
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                client().newCall(GET(candidate, cache = CacheControl.FORCE_NETWORK)).await().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $candidate")
                    val total = response.body.contentLength()
                    response.body.byteStream().use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0 && copied - lastReport > total / 20) {
                                    lastReport = copied
                                    progress.report(InstallPhase.DOWNLOAD, copied.toDouble() / total)
                                }
                            }
                        }
                    }
                }
                return target
            } catch (e: IOException) {
                lastError = e
                logger.warn(e) { "Download failed from $candidate" }
            }
        }
        target.deleteIfExists()
        throw lastError ?: IOException("Download failed: $url")
    }

    companion object {
        const val JAR_NAME = "ext.jar"

        /** Bump when the conversion pipeline changes so cached jars are regenerated. */
        const val CONVERTER_VERSION = 1
    }
}
