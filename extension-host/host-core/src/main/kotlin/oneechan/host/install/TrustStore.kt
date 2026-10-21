package oneechan.host.install

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import oneechan.host.util.AtomicJsonFile
import java.nio.file.Path

/**
 * Mihon's extension trust model: an extension is trusted when one of its signing certificates
 * matches a configured repo's signing key fingerprint, or when the user explicitly trusted this
 * exact `pkgName:versionCode:signatureHash` (any change in version or signer asks again).
 */
class TrustStore(
    stateDir: Path,
    json: Json,
) {
    private val file = AtomicJsonFile(stateDir.resolve("trust.json"), ListSerializer(String.serializer()), json) { emptyList() }

    fun isTrusted(
        pkgName: String,
        versionCode: Long,
        signatureHashes: List<String>,
        repoFingerprints: Set<String>,
    ): Boolean {
        val hashes = signatureHashes.map { it.lowercase() }
        if (hashes.any { it in repoFingerprints }) return true
        val userTrusted = file.read().toSet()
        return hashes.any { "$pkgName:$versionCode:$it" in userTrusted }
    }

    fun trust(
        pkgName: String,
        versionCode: Long,
        signatureHash: String,
    ) {
        file.update { keys ->
            // Trusting a new version supersedes older trust entries of the same package.
            keys.filterNot { it.startsWith("$pkgName:") } + "$pkgName:$versionCode:${signatureHash.lowercase()}"
        }
    }

    fun revoke(pkgName: String) {
        file.update { keys -> keys.filterNot { it.startsWith("$pkgName:") } }
    }
}
