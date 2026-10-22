@file:OptIn(ExperimentalSerializationApi::class)

package oneechan.host.backup.komikku

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import oneechan.host.backup.BIG_SOURCE
import oneechan.host.backup.KAVITA_SOURCE
import oneechan.host.backup.MERGED_SOURCE
import oneechan.host.backup.richBackup
import oneechan.host.backup.richExtensionStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Komikku's own models (TachiyomiSY and J2K fields) read Oneechan's rich backup, `oneechan-rich.tachibk`, written by the
 * Swift `RichBackupFixtureTests`. Mihon's fields of the same file are checked in `MihonBackupInteropTest`.
 */
class KomikkuBackupInteropTest {
    private val dir: Path = Path(System.getProperty("oneechan.contractDir") ?: "../contract").resolve("fixtures/backup")

    @Test
    fun oneechanRichBackupKeepsKomikkuFields() {
        val file = dir.resolve("oneechan-rich.tachibk")
        assumeTrue(file.exists(), "written by the Swift RichBackupFixtureTests")
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), GZIPInputStream(file.readBytes().inputStream()).readBytes())

        // Custom info (Komikku's "Edit info": 602 and 800–805) and the source's values next to it.
        val frieren = backup.backupManga.single { it.url == "/manga/frieren" }
        assertEquals("Frieren", frieren.customTitle)
        assertEquals("Abe T.", frieren.customArtist)
        assertEquals("Yamada K.", frieren.customAuthor)
        assertEquals("My note on it.", frieren.customDescription)
        assertEquals(listOf("Fantasy", "Adventure"), frieren.customGenre)
        assertEquals(2, frieren.customStatus)
        assertNull(frieren.customThumbnailUrl, "Oneechan's covers are local files, never a Komikku URL")
        assertEquals("Frieren: Beyond Journey's End", frieren.title, "the source's title stays in field 3")
        assertEquals(1, frieren.status, "the source's status stays in field 8")

        // Unedited entries carry no custom values.
        val readOnly = backup.backupManga.single { it.url == "/manga/read-only" }
        assertNull(readOnly.customTitle)
        assertNull(readOnly.customGenre)
        assertEquals(0, readOnly.customStatus)

        // Merged entry (TachiyomiSY field 600 on the entry, one reference per member).
        val merged = backup.backupManga.single { it.url == "/merged/frieren" }
        assertEquals(MERGED_SOURCE, merged.source)
        assertEquals(
            listOf(
                BackupMergedMangaReference(
                    isInfoManga = true, getChapterUpdates = true, chapterSortMode = 1, chapterPriority = 2, downloadChapters = false,
                    mergeUrl = "/merged/frieren", mangaUrl = "/manga/frieren", mangaSourceId = BIG_SOURCE,
                ),
                BackupMergedMangaReference(
                    isInfoManga = false, getChapterUpdates = false, chapterSortMode = 0, chapterPriority = 0, downloadChapters = true,
                    mergeUrl = "/merged/frieren", mangaUrl = "/manga/read-only", mangaSourceId = KAVITA_SOURCE,
                ),
            ),
            merged.mergedMangaReferences,
        )

        // Hidden category (Komikku field 900).
        assertEquals(
            listOf("Plan to read" to false, "Reading" to false, "Secret" to true),
            backup.backupCategories.map { it.name to it.hidden }.sortedBy { it.first },
        )

        // Saved searches (600) and feeds (610), with the saved search embedded in its feed.
        assertEquals(
            listOf(
                BackupSavedSearch(name = "Romance", query = "love", filterList = "[]", source = BIG_SOURCE),
                BackupSavedSearch(name = "Unread", query = "", filterList = "[]", source = KAVITA_SOURCE),
            ),
            backup.backupSavedSearches.sortedBy { it.name },
        )
        assertEquals(
            setOf(
                BackupFeed(source = BIG_SOURCE, global = true, savedSearch = null),
                BackupFeed(source = BIG_SOURCE, global = false, savedSearch = BackupSavedSearch("Romance", "love", "[]", BIG_SOURCE)),
            ),
            backup.backupFeeds.toSet(),
        )
        assertEquals(2, backup.backupFeeds.size)

        // Mihon's models for the shared sections, read by Komikku's root.
        assertEquals(listOf(richExtensionStore), backup.backupExtensionStores)
        assertEquals(richBackup.backupPreferences, backup.backupPreferences)
        assertTrue(backup.backupSourcePreferences.isEmpty(), "Oneechan writes no source preferences yet")
    }
}
