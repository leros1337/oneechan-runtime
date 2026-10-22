package oneechan.host.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Komikku's related titles as keiyoushi's extension-lib 1.6 declares them on `CatalogueSource`. The
 * host API does not declare them, so these fakes define the same JVM members a compiled extension has
 * (`getSupportsRelatedMangas()Z`, `fetchRelatedMangaList(SManga, Continuation)`, ...).
 */
class RelatedCallsTest {
    private open class PlainSource : CatalogueSource {
        override val id = 1L
        override val name = "Plain"
        override val lang = "en"
        override val supportsLatest = false
    }

    /** A Madara-like theme: the site's own list, and no search fallback (keiyoushi's KeiSource default). */
    private open class WebsiteSource(
        private val related: List<String> = listOf("/manga/a", "/manga/self", "/manga/b", "/manga/a"),
        private val failure: Throwable? = null,
    ) : PlainSource() {
        var fetches = 0
        var lastManga: SManga? = null

        open val supportsRelatedMangas: Boolean get() = true
        val disableRelatedMangasBySearch: Boolean get() = true
        val disableRelatedMangas: Boolean get() = false

        suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
            fetches++
            lastManga = manga
            delay(5) // really suspends, as an HTTP call does
            yield()
            failure?.let { throw it }
            return related.map { url -> SManga.create().also { it.url = url; it.title = "Title $url" } }
        }
    }

    /** KeiSource's defaults: declared, but not supported (no request is made). */
    private class UnsupportedKeiSource : WebsiteSource() {
        override val supportsRelatedMangas: Boolean get() = false
    }

    private class DisabledSource : PlainSource() {
        val supportsRelatedMangas: Boolean get() = true
        val disableRelatedMangas: Boolean get() = true

        suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = error("must not be called")
    }

    private val self = SManga.create().also {
        it.url = "/manga/self"
        it.title = "Self"
        it.memo = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("42")) }
    }

    @Test
    fun `the website list comes from the extension, without the entry itself or repeats`() =
        runBlocking {
            val source = WebsiteSource()
            val result = RelatedCalls.related(source, self)
            assertTrue(result.supported)
            assertFalse(result.disabled)
            assertTrue(result.searchDisabled)
            assertEquals(listOf("/manga/a", "/manga/b"), result.mangas.map { it.url })
            assertEquals("Title /manga/a", result.mangas.first().title)
            assertEquals(1, source.fetches)
            assertEquals("42", source.lastManga?.memo?.get("id")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }, "the memo reaches the extension")
        }

    @Test
    fun `sources without the API are unsupported`() =
        runBlocking {
            val result = RelatedCalls.related(PlainSource(), self)
            assertFalse(result.supported)
            assertFalse(result.disabled)
            assertFalse(result.searchDisabled)
            assertTrue(result.mangas.isEmpty())
        }

    @Test
    fun `declared but unsupported lists are not fetched`() =
        runBlocking {
            val source = UnsupportedKeiSource()
            val result = RelatedCalls.related(source, self)
            assertFalse(result.supported)
            assertTrue(result.searchDisabled)
            assertEquals(0, source.fetches)
        }

    @Test
    fun `disabled sources report it and are not called`() =
        runBlocking {
            val result = RelatedCalls.related(DisabledSource(), self)
            assertTrue(result.disabled)
            assertFalse(result.supported)
        }

    @Test
    fun `a failing list still reports the flags, with no titles`() =
        runBlocking {
            val source = WebsiteSource(failure = IOException("HTTP error 503"))
            val result = RelatedCalls.related(source, self)
            assertEquals(1, source.fetches)
            assertFalse(result.supported)
            assertFalse(result.disabled)
            assertTrue(result.searchDisabled, "the flags survive the failure")
            assertTrue(result.mangas.isEmpty())
        }

    @Test
    fun `cancellation is not swallowed`() {
        assertFailsWith<CancellationException> {
            runBlocking { RelatedCalls.related(WebsiteSource(failure = CancellationException("stop")), self) }
        }
    }
}
