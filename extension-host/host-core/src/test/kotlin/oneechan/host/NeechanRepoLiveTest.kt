package oneechan.host

import oneechan.host.TestHost.awaitJob
import oneechan.host.TestHost.blocking
import oneechan.host.rpc.BrowseParams
import oneechan.host.rpc.ChapterParams
import oneechan.host.rpc.ContentWarningDto
import oneechan.host.rpc.ExtInstallParams
import oneechan.host.rpc.ExtListAvailableParams
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.MangaUpdateParams
import oneechan.host.rpc.PageParams
import oneechan.host.rpc.SearchParams
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Neechan extension repository used for App Review (neechan-website, `extension-repo/`).
 * Skipped unless NEECHAN_REPO_URL names its index, e.g. a local server or the published site:
 *
 *     NEECHAN_REPO_URL=http://127.0.0.1:8799/repo/index.json ./gradlew :host-core:test -Plive --tests '*NeechanRepoLiveTest*'
 */
@Tag("live")
class NeechanRepoLiveTest {
    private val host get() = TestHost.host
    private val safePkg = "pro.neechan.blackjack"
    private val adultPkg = "pro.neechan.blackjack.adult"

    @Test
    fun `neechan repo gates the 18+ package and both packages install trusted and read`() {
        val url = System.getenv("NEECHAN_REPO_URL")
        assumeTrue(!url.isNullOrBlank(), "NEECHAN_REPO_URL not set")

        val repo = blocking { host.repoAdd(url!!) }
        assertEquals("Neechan Repository", repo.name)

        // The warning travels per package: the SAFE one is listed on its own, the 18+ one is flagged.
        val available = host.extListAvailable(ExtListAvailableParams()).associateBy { it.pkgName }
        assertEquals(ContentWarningDto.SAFE, assertNotNull(available[safePkg]).contentWarning)
        assertEquals(ContentWarningDto.NSFW, assertNotNull(available[adultPkg]).contentWarning)
        assertEquals("all", available.getValue(safePkg).lang)

        for ((pkg, warning) in listOf(safePkg to ContentWarningDto.SAFE, adultPkg to ContentWarningDto.NSFW)) {
            val done = awaitJob(host.extInstall(ExtInstallParams(pkg)))
            assertEquals(InstallPhase.DONE, done.phase, "install of $pkg failed: ${done.error}")
            val installed = assertNotNull(done.extension)
            assertEquals("1.4", installed.libVersion)
            assertEquals(warning, installed.contentWarning, "the manifest must agree with the index")

            // Signed by the repo key listed in its index: trusted at install, no Trust step.
            assertTrue(installed.isTrusted, "$pkg is signed by the repo key and must install trusted")
            assertEquals(repo.signingKeyFingerprint, installed.signatureHash, "the signer is the repo key")
            val source = host.sourceList().single { it.pkgName == pkg }
            assertEquals(warning, source.contentWarning, "the source inherits the package's warning")

            val popular = blocking { host.popular(BrowseParams(source.id, 1)) }
            val manga = popular.mangas.first()
            assertTrue(blocking { host.search(SearchParams(source.id, 1, manga.title.take(4))) }.mangas.isNotEmpty())
            val update = blocking { host.mangaUpdate(MangaUpdateParams(source.id, manga, fetchDetails = true, fetchChapters = true)) }
            assertTrue(update.manga.initialized)
            assertTrue(update.chapters.isNotEmpty())
            val pages = blocking { host.pages(ChapterParams(source.id, update.chapters.last())) }
            assertTrue(pages.size >= 2)
            val image = blocking { host.fetchImage(PageParams(source.id, pages.first())) }
            assertTrue(image.size > 100, "first page must download")
        }
    }
}
