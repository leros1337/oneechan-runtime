package oneechan.stubs

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * Declaration order of enum constants. Signature files sort members alphabetically, but ordinals
 * and `values()` follow the declaration, and stub enums are usable (javac gives them a working
 * constructor), so the order is taken from the AOSP sources the API was generated from.
 *
 * File format (`api/enum-order.txt`): `#` comments, then one enum per line:
 * `android.webkit.ConsoleMessage.MessageLevel TIP LOG WARNING ERROR DEBUG`.
 */
object EnumOrder {
    fun parse(text: String): Map<String, List<String>> =
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
            val parts = line.split(Regex("\\s+"))
            parts[0] to parts.drop(1)
        }

    /** The constants of `enum [simpleName]` in a Java source file, in declaration order. */
    fun constantsIn(source: String, simpleName: String): List<String>? {
        val code = stripCommentsAndLiterals(source)
        val header = Regex("\\benum\\s+${Regex.escape(simpleName)}\\b[^{;]*\\{").find(code) ?: return null
        val out = mutableListOf<String>()
        var i = header.range.last + 1
        var depth = 0
        var expectName = true
        while (i < code.length) {
            val c = code[i]
            when {
                depth == 0 && (c == ';' || c == '}') -> return out
                c == '(' || c == '{' -> depth++
                c == ')' || c == '}' -> depth--
                depth == 0 && c == ',' -> expectName = true
                depth == 0 && c == '@' -> {
                    // An annotation on the constant: skip its name; arguments are skipped as a group.
                    i++
                    while (i < code.length && (Character.isJavaIdentifierPart(code[i]) || code[i] == '.')) i++
                    continue
                }
                depth == 0 && expectName && Character.isJavaIdentifierStart(c) -> {
                    var j = i + 1
                    while (j < code.length && Character.isJavaIdentifierPart(code[j])) j++
                    out += code.substring(i, j)
                    expectName = false
                    i = j
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Replaces comments and string/char literals with spaces, keeping offsets. */
    private fun stripCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            val start = i
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    i++
                    while (i < source.length && source[i] != quote) i += if (source[i] == '\\') 2 else 1
                    i++
                }
                else -> {
                    i++
                    continue
                }
            }
            for (k in start until minOf(i, source.length)) if (out[k] != '\n') out.setCharAt(k, ' ')
        }
        return out.toString()
    }
}

/** An AOSP repository at the release the API signature comes from. */
private class SourceRepo(val project: String, val commit: String, val roots: List<String>)

private val FRAMEWORKS_BASE =
    SourceRepo(
        "platform/frameworks/base",
        "34a1b9c951c38537ab96b69bc308f6e0884823f5", // android-11.0.0_r1
        listOf(
            "core/java", "graphics/java", "media/java", "location/java", "telephony/java", "wifi/java",
            "rs/java", "opengl/java", "keystore/java", "drm/java", "sax/java", "services/core/java",
        ),
    )
private val OPT_TELEPHONY =
    SourceRepo("platform/frameworks/opt/telephony", "1e1f3eb29e97c00b03a09197eea9469fd4cb3239", listOf("src/java")) // android-11.0.0_r1
private val EXTERNAL_ICU =
    SourceRepo("platform/external/icu", "d9dce1df12d15409e0701366c845418e75445a34", listOf("android_icu4j/src/main/java")) // android-11.0.0_r1

/**
 * `UpdateEnumOrder --api api/current.txt --out api/enum-order.txt` (network): reads every
 * android.* / dalvik.* enum's declaration order from its AOSP source file.
 */
fun main(args: Array<String>) {
    val argMap = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val apiPath = Path(argMap["--api"] ?: usage())
    val outPath = Path(argMap["--out"] ?: usage())
    val api = SignatureParser.parse(Files.readString(apiPath), apiPath.fileName.toString())
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()
    val cache = HashMap<String, String?>()

    fun fetch(repo: SourceRepo, path: String): String? =
        cache.getOrPut("${repo.project}/$path") {
            val url = "https://android.googlesource.com/${repo.project}/+/${repo.commit}/$path?format=TEXT"
            var attempt = 0
            while (true) {
                val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString())
                when {
                    response.statusCode() == 200 -> return@getOrPut String(Base64.getMimeDecoder().decode(response.body()))
                    response.statusCode() == 404 -> return@getOrPut null
                    ++attempt > 6 -> error("$url: HTTP ${response.statusCode()}")
                    else -> Thread.sleep(2_000L * attempt) // throttled (429) or transient
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    val lines = mutableListOf<String>()
    val failures = mutableListOf<String>()
    for (c in api.classes) {
        if (c.kind != ClassKind.ENUM || !(c.pkg == "android" || c.pkg.startsWith("android.") || c.pkg.startsWith("dalvik."))) continue
        val repos = if (c.pkg.startsWith("android.icu")) listOf(EXTERNAL_ICU) else listOf(FRAMEWORKS_BASE, OPT_TELEPHONY)
        val file = "${c.pkg.replace('.', '/')}/${c.name.substringBefore('.')}.java"
        val source = repos.firstNotNullOfOrNull { repo -> repo.roots.firstNotNullOfOrNull { root -> fetch(repo, "$root/$file") } }
        val order = source?.let { EnumOrder.constantsIn(it, c.simpleName) }
        val expected = c.enumConstants.map { it.name }
        when {
            order == null -> failures += "${c.qualifiedName}: source not found"
            // The source may declare hidden constants; keep the API ones in source order.
            !order.containsAll(expected) -> failures += "${c.qualifiedName}: source lacks ${expected - order.toSet()}"
            else -> lines += "${c.qualifiedName} ${order.filter { it in expected }.joinToString(" ")}"
        }
    }
    val header =
        """
        # Declaration order of the enum constants of the API signature (current.txt sorts them), read
        # from the AOSP sources at android-11.0.0_r1 by `./gradlew :android-stubs:updateEnumOrder`:
        #   ${FRAMEWORKS_BASE.project} ${FRAMEWORKS_BASE.commit} (Apache-2.0)
        #   ${OPT_TELEPHONY.project} ${OPT_TELEPHONY.commit} (Apache-2.0)
        #   ${EXTERNAL_ICU.project} ${EXTERNAL_ICU.commit} (android.icu.*, ICU licence)
        # Only constant names and their order are recorded.
        """.trimIndent()
    Files.writeString(outPath, header + "\n" + lines.joinToString("\n", postfix = "\n"))
    println("enum-order: ${lines.size} enums written to $outPath")
    // Enums missing here keep the signature file's order.
    failures.forEach { System.err.println("warning: $it") }
}

private fun usage(): Nothing {
    System.err.println("usage: UpdateEnumOrder --api <current.txt> --out <enum-order.txt>")
    exitProcess(2)
}
