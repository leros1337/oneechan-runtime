@file:OptIn(ExperimentalSerializationApi::class)

package oneechan.host.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `.tachibk` interop with Mihon's own serializer. `contract/fixtures/backup/mihon-sample.tachibk` is
 * what Mihon writes for [sample]; the Swift tests decode it. `oneechan-written.tachibk` is written by
 * the Swift tests and must decode here as Mihon would read it. `oneechan-rich.tachibk` is the rich backup
 * (every field Oneechan exports), checked against [richBackup]; Komikku's fields are in `KomikkuBackupInteropTest`.
 */
class MihonBackupInteropTest {
    private val dir: Path = Path(System.getProperty("oneechan.contractDir") ?: "../contract").resolve("fixtures/backup")
    private val update = System.getProperty("oneechan.updateContract") == "true"
    private val parser = ProtoBuf

    @Test
    fun mihonSampleIsCurrent() {
        val bytes = gzip(parser.encodeToByteArray(Backup.serializer(), sample))
        val file = dir.resolve("mihon-sample.tachibk")
        if (update || !file.exists()) {
            dir.createDirectories()
            file.writeBytes(bytes)
        }
        assertContentEquals(bytes, file.readBytes(), "stale fixture: run with -PupdateContract=true")
    }

    @Test
    fun oneechanBackupsDecodeAsMihonReadsThem() {
        val file = dir.resolve("oneechan-written.tachibk")
        assumeTrue(file.exists(), "written by the Swift BackupInteropTests")
        val backup = parser.decodeFromByteArray(Backup.serializer(), GZIPInputStream(file.readBytes().inputStream()).readBytes())
        // The Swift test writes back the sample it decoded.
        assertEquals(sample.backupManga.map { it.url }, backup.backupManga.map { it.url })
        assertEquals(sample.backupCategories, backup.backupCategories)
        assertEquals(sample.backupSources, backup.backupSources)
        val frieren = backup.backupManga.first()
        val expected = sample.backupManga.first()
        assertEquals(expected.source, frieren.source)
        assertEquals(expected.chapters.map { Triple(it.url, it.read, it.chapterNumber) }, frieren.chapters.map { Triple(it.url, it.read, it.chapterNumber) })
        assertEquals(expected.history, frieren.history)
        assertEquals(expected.categories, frieren.categories)
        assertEquals(expected.tracking.map { it.copy(mediaIdInt = 0) }, frieren.tracking)
        assertEquals(expected.viewer_flags, frieren.viewer_flags)
        assertEquals(expected.chapterFlags, frieren.chapterFlags)
        assertEquals(expected.excludedScanlators, frieren.excludedScanlators)
        assertEquals(expected.notes, frieren.notes)
        assertEquals(expected.memo.decodeToString(), frieren.memo.decodeToString())
        assertEquals(expected.nextUpdate, frieren.nextUpdate)
        assertEquals(sample.backupExtensionStores, backup.backupExtensionStores)
    }

    /**
     * `oneechan-rich.tachibk` (every field Oneechan exports, from the Swift `RichBackupFixtureTests`) decodes with Mihon's
     * models to the values in [richBackup]. Entries are compared field by field, byte for byte, with chapters, tracking
     * and history in a fixed order; categories by name, since their orders come from the database.
     */
    @Test
    fun oneechanRichBackupKeepsEveryValueForMihon() {
        val file = dir.resolve("oneechan-rich.tachibk")
        assumeTrue(file.exists(), "written by the Swift RichBackupFixtureTests")
        val backup = parser.decodeFromByteArray(Backup.serializer(), GZIPInputStream(file.readBytes().inputStream()).readBytes())

        assertEquals(richBackup.backupManga.map { it.url }.sorted(), backup.backupManga.map { it.url }.sorted())
        for (expected in richBackup.backupManga) {
            val actual = backup.backupManga.single { it.url == expected.url }
            assertEquals(expected.source, actual.source, expected.url)
            assertEquals(richBackup.categoryNames(expected), backup.categoryNames(actual), expected.url)
            assertContentEquals(expected.canonical().wire(), actual.canonical().wire(), expected.url)
        }
        assertEquals(richBackup.backupCategories.map { it.name to it.flags }.sortedBy { it.first }, backup.backupCategories.map { it.name to it.flags }.sortedBy { it.first })
        assertEquals(richBackup.backupSources.sortedBy { it.sourceId }, backup.backupSources.sortedBy { it.sourceId })
        assertEquals(richBackup.backupPreferences, backup.backupPreferences)
        assertEquals(richBackup.backupExtensionStores, backup.backupExtensionStores)
    }

    private fun Backup.categoryNames(manga: BackupManga): List<String> =
        manga.categories.map { order -> backupCategories.single { it.order == order }.name }

    private fun BackupManga.canonical(): BackupManga =
        copy(
            chapters = chapters.sortedBy { it.url },
            tracking = tracking.sortedBy { it.syncId },
            history = history.sortedBy { it.url },
            categories = emptyList(),
        )

    private fun BackupManga.wire(): ByteArray = ProtoBuf.encodeToByteArray(BackupManga.serializer(), this)

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    companion object {
        private const val BIG_SOURCE = 7_266_875_667_263_635_239L

        val sample =
            Backup(
                backupManga =
                    listOf(
                        BackupManga(
                            source = BIG_SOURCE,
                            url = "/manga/frieren",
                            title = "Frieren: Beyond Journey's End",
                            artist = "Abe Tsukasa",
                            author = "Yamada Kanehito",
                            description = "An elf mage outlives her party.",
                            genre = listOf("Adventure", "Fantasy"),
                            status = 1,
                            thumbnailUrl = "https://example.org/frieren.jpg",
                            dateAdded = 1_700_000_000_000,
                            viewer = 2,
                            chapters =
                                listOf(
                                    BackupChapter(
                                        url = "/chapter/1", name = "Vol.1 Ch.1", scanlator = "Official", read = true, bookmark = true,
                                        lastPageRead = 5, dateFetch = 1_700_000_001_000, dateUpload = 1_690_000_000_000,
                                        chapterNumber = 1f, sourceOrder = 1, memo = """{"cid":"a1"}""".encodeToByteArray(),
                                    ),
                                    BackupChapter(
                                        url = "/chapter/2", name = "Vol.1 Ch.2.5", scanlator = "Group", chapterNumber = 2.5f, sourceOrder = 0,
                                        dateFetch = 1_700_000_002_000, dateUpload = 1_695_000_000_000,
                                    ),
                                ),
                            categories = listOf(1),
                            tracking =
                                listOf(
                                    BackupTracking(
                                        syncId = 2, libraryId = 5, mediaId = 12_345, trackingUrl = "https://anilist.co/manga/12345", title = "Sousou no Frieren",
                                        lastChapterRead = 1f, totalChapters = 120, score = 8.5f, status = 1, startedReadingDate = 1_700_000_000_000,
                                    ),
                                ),
                            favorite = true,
                            chapterFlags = 0x101,
                            viewer_flags = 0x12,
                            history = listOf(BackupHistory("/chapter/1", 1_700_000_100_000, 60_000)),
                            excludedScanlators = listOf("Bad Scans"),
                            notes = "Reread arc 2",
                            initialized = true,
                            memo = """{"mdId":"b0b721ff"}""".encodeToByteArray(),
                            nextUpdate = 1_700_500_000_000,
                        ),
                        BackupManga(
                            source = 2499283573021220255,
                            url = "/manga/read-only",
                            title = "Read but not in library",
                            favorite = false,
                            chapters = listOf(BackupChapter(url = "/c/1", name = "Chapter 1", read = true, chapterNumber = 1f)),
                            history = listOf(BackupHistory("/c/1", 1_700_000_200_000, 1_000)),
                        ),
                    ),
                backupCategories = listOf(BackupCategory("Reading", order = 1, id = 1), BackupCategory("Plan to read", order = 2, id = 2, flags = 64)),
                backupSources = listOf(BackupSource("MangaDex", BIG_SOURCE), BackupSource("Kavita", 2499283573021220255)),
                backupPreferences = listOf(BackupPreference("pref_library_columns_portrait_key", IntPreferenceValue(3))),
                backupSourcePreferences =
                    listOf(
                        BackupSourcePreferences(
                            "source_$BIG_SOURCE",
                            listOf(
                                BackupPreference("coverQuality", StringPreferenceValue("data-saver")),
                                BackupPreference("blockedGroups", StringSetPreferenceValue(setOf("a", "b"))),
                                BackupPreference("dataSaver", BooleanPreferenceValue(true)),
                            ),
                        ),
                    ),
                backupExtensionStores =
                    listOf(
                        BackupExtensionStore(
                            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json", name = "Keiyoushi", badgeLabel = "KS",
                            signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2", contactWebsite = "https://keiyoushi.github.io",
                            contactDiscord = null, isLegacy = false, extensionListUrl = null,
                        ),
                    ),
            )
    }
}
