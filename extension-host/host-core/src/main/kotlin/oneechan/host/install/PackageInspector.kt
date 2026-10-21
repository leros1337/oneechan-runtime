package oneechan.host.install

import com.android.apksig.ApkVerifier
import oneechan.host.util.sha256Hex
import org.apache.commons.compress.archivers.zip.ZipFile
import suwayomi.tachidesk.manga.impl.util.AndroidManifestParser
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Path

/** Everything the installer needs from an extension package's manifest and signature. */
data class PackageMeta(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val label: String?,
    val reqFeatures: List<String>,
    val metaData: Map<String, String>,
    val signatureHashes: List<String>,
)

object PackageInspector {
    fun inspectApk(apk: Path): PackageMeta {
        val info = PackageTools.getPackageInfo(apk)
        val bundle = info.applicationInfo.metaData
        val metaData = bundle?.keySet()?.associateWith { key -> bundle.get(key)?.toString().orEmpty() }.orEmpty()
        return PackageMeta(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.versionCode.toLong(),
            label = info.applicationInfo.nonLocalizedLabel?.toString(),
            reqFeatures = info.reqFeatures.orEmpty().mapNotNull { it.name },
            metaData = metaData,
            signatureHashes = signatureHashes(apk).ifEmpty { listOfNotNull(PackageTools.getSignatureHash(info)) },
        )
    }

    /** Pre-converted jars carry the (text) AndroidManifest.xml but no APK signature. */
    fun inspectJar(jar: Path): PackageMeta {
        val manifest =
            ZipFile.builder().setPath(jar).get().use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: error("Jar has no AndroidManifest.xml")
                zip.getInputStream(entry).use { AndroidManifestParser.parse(it) }
            }
        val application = manifest.application ?: error("Manifest has no <application>")
        return PackageMeta(
            packageName = manifest.packageName,
            versionName = manifest.versionName ?: error("Manifest has no versionName"),
            versionCode = (manifest.versionCode ?: error("Manifest has no versionCode")).toLong(),
            label = application.label,
            reqFeatures = manifest.usesFeatures.mapNotNull { it.name },
            metaData = application.metaData.associate { it.name to it.value.orEmpty() },
            signatureHashes = emptyList(),
        )
    }

    /**
     * SHA-256 of the signer certificate(s) of a v1-signed jar (keiyoushi signs its JVM jars with
     * the repo key). Every non-META-INF entry must verify and share the same signers; any unsigned
     * or tampered entry yields an empty list (the jar is then not trusted by signature).
     */
    fun jarSignerHashes(jar: Path): List<String> =
        runCatching {
            java.util.jar.JarFile(jar.toFile(), true).use { file ->
                var signers: Set<String>? = null
                for (entry in file.entries()) {
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    // Reading the entry fully triggers digest verification (SecurityException on tamper).
                    file.getInputStream(entry).use { it.readAllBytes() }
                    val entrySigners =
                        entry.codeSigners
                            ?.map { sha256Hex(it.signerCertPath.certificates.first().encoded) }
                            ?.toSet()
                            ?: return emptyList()
                    if (signers == null) signers = entrySigners else if (signers != entrySigners) return emptyList()
                }
                signers.orEmpty().toList()
            }
        }.getOrDefault(emptyList())

    /** SHA-256 of every signer certificate (APK signature schemes v1-v4 via apksig). */
    fun signatureHashes(apk: Path): List<String> =
        runCatching {
            val result = ApkVerifier.Builder(apk.toFile()).build().verify()
            if (!result.isVerified) return emptyList()
            result.signerCertificates.map { sha256Hex(it.encoded) }
        }.getOrDefault(emptyList())
}
