package oneechan.host

import oneechan.host.TestHost.awaitJob
import oneechan.host.TestHost.blocking
import oneechan.host.rpc.BrowseParams
import oneechan.host.rpc.ChapterParams
import oneechan.host.rpc.ExtInstallParams
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.MangaUpdateParams
import oneechan.host.rpc.PageParams
import oneechan.host.rpc.PreferenceDto
import oneechan.host.rpc.SearchParams
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end against the user's reference repos (network required): `./gradlew :host-core:test -Plive`. */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExtensionHostLiveTest {
    private val host get() = TestHost.host

    @Test
    @Order(1)
    fun `installs the lib 1_4 Kavita extension from its legacy repo`() {
        val repo = blocking { host.repoAdd("https://raw.githubusercontent.com/Kareadita/tach-extension/repo/index.min.json") }
        assertEquals("Kavita Extension Repo", repo.name)

        val done = awaitJob(host.extInstall(ExtInstallParams("eu.kanade.tachiyomi.extension.all.kavita")))
        assertEquals(InstallPhase.DONE, done.phase, "install failed: ${done.error}")
        val ext = assertNotNull(done.extension)
        assertTrue(ext.isTrusted, "signed by the repo key, so it must be trusted")
        assertEquals("1.4", ext.libVersion)
        assertTrue(ext.sources.size >= 3, "Kavita is a SourceFactory with several server slots")
        assertTrue(ext.sources.all { it.isConfigurable })

        val prefs = blocking { host.preferences(ext.sources.first().id) }
        assertTrue(prefs.any { it is PreferenceDto.EditText }, "Kavita exposes the server address as an EditText preference: $prefs")
    }

    @Test
    @Order(2)
    fun `installs the lib 1_4 Suwayomi extension`() {
        blocking { host.repoAdd("https://raw.githubusercontent.com/Suwayomi/tachiyomi-extension/repo/index.min.json") }
        val done = awaitJob(host.extInstall(ExtInstallParams("eu.kanade.tachiyomi.extension.all.tachidesk")))
        assertEquals(InstallPhase.DONE, done.phase, "install failed: ${done.error}")
        assertEquals(3100117499901280806L.toString(), done.extension!!.sources.single().id)
    }

    @Test
    @Order(3)
    fun `installs MangaDex from the converted APK (patched dex2jar)`() {
        blocking { host.repoAdd("https://github.com/keiyoushi/extensions/raw/repo/index.pb") }
        val done = awaitJob(host.extInstall(ExtInstallParams("eu.kanade.tachiyomi.extension.all.mangadex", forceApk = true)))
        assertEquals(InstallPhase.DONE, done.phase, "install failed: ${done.error}")
        val ext = done.extension!!
        assertTrue(ext.isTrusted)
        val english = ext.sources.first { it.lang == "en" }
        assertTrue(blocking { host.popular(BrowseParams(english.id, 1)) }.mangas.isNotEmpty())
        blocking { host.extUninstall(ext.pkgName) }
    }

    @Test
    @Order(4)
    fun `installs MangaDex from keiyoushi's signed jar and reads a chapter`() {
        blocking { host.repoAdd("https://github.com/keiyoushi/extensions/raw/repo/index.pb") }
        val done = awaitJob(host.extInstall(ExtInstallParams("eu.kanade.tachiyomi.extension.all.mangadex")))
        assertEquals(InstallPhase.DONE, done.phase, "install failed: ${done.error}")
        assertTrue(done.extension!!.isTrusted, "jar signer must match the keiyoushi fingerprint")
        val ext = done.extension!!
        println("MangaDex ${ext.versionName} lib ${ext.libVersion}, ${ext.sources.size} sources")
        val english = ext.sources.first { it.lang == "en" }

        val popular = blocking { host.popular(BrowseParams(english.id, 1)) }
        assertTrue(popular.mangas.isNotEmpty())
        val search = blocking { host.search(SearchParams(english.id, 1, "Sousou no Frieren")) }
        val manga = search.mangas.first { it.title.contains("Frieren") }
        println("Search hit: ${manga.title} ${manga.url}")

        val details = blocking { host.mangaUpdate(MangaUpdateParams(english.id, manga, fetchDetails = true, fetchChapters = false)) }
        println("Details: initialized=${details.manga.initialized} author=${details.manga.author}")
        assertTrue(details.manga.initialized)

        // Licensed titles have no chapters on MangaDex; read the first popular title that has some.
        val (withChapters, chapters) =
            popular.mangas.take(10).firstNotNullOf { candidate ->
                val update = blocking { host.mangaUpdate(MangaUpdateParams(english.id, candidate, fetchDetails = false, fetchChapters = true)) }
                update.chapters.takeIf { it.isNotEmpty() }?.let { candidate to it }
            }
        println("Reading ${withChapters.title}: ${chapters.size} chapters")
        val chapter = chapters.first()

        val pages = blocking { host.pages(ChapterParams(english.id, chapter)) }
        assertTrue(pages.isNotEmpty())
        val image = blocking { host.fetchImage(PageParams(english.id, pages.first())) }
        assertTrue(image.size > 1000)
        assertTrue(Files.exists(Path(image.path)))
        println("First page ${image.mimeType} ${image.size} bytes")

        val filters = blocking { host.filters(english.id) }
        assertTrue(filters.isNotEmpty())
    }
}
