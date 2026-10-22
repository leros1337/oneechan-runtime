package oneechan.host.backup

import eu.kanade.tachiyomi.source.model.UpdateStrategy

/*
 * The rich backup that Oneechan's Swift `RichBackupFixtureTests` writes to `contract/fixtures/backup/oneechan-rich.tachibk`
 * (restored from `RichBackupFixtureTests.input()`, then exported with `BackupCreator`). The values here are what Mihon
 * and Komikku should read back; keep them in step with the Swift input.
 */

const val BIG_SOURCE = 7_266_875_667_263_635_239L
const val KAVITA_SOURCE = 2_499_283_573_021_220_255L
const val MERGED_SOURCE = 6969L

val richPreferences =
    listOf(
        BackupPreference("pref_library_columns_portrait_key", IntPreferenceValue(3)),
        BackupPreference("pref_long_example", LongPreferenceValue(1_700_000_000_123)),
        BackupPreference("pref_float_example", FloatPreferenceValue(1.5f)),
        BackupPreference("pref_string_example", StringPreferenceValue("data-saver")),
        BackupPreference("pref_string_set_example", StringSetPreferenceValue(setOf("a", "b"))),
        BackupPreference("pref_bool_example", BooleanPreferenceValue(true)),
    )

val richExtensionStore =
    BackupExtensionStore(
        indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
        name = "Keiyoushi",
        badgeLabel = null,
        signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        contactWebsite = "https://keiyoushi.github.io",
        contactDiscord = null,
        isLegacy = null,
        extensionListUrl = null,
    )

/** Mihon's fields of the rich backup. Komikku-only fields are checked in `KomikkuBackupInteropTest`. */
val richBackup =
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
                                url = "/chapter/1", name = "Vol.1 Ch.1", scanlator = "Official", read = true, bookmark = true, lastPageRead = 5,
                                dateFetch = 1_700_000_001_000, dateUpload = 1_690_000_000_000, chapterNumber = 1f, sourceOrder = 1,
                                memo = """{"cid":"a1"}""".encodeToByteArray(),
                            ),
                            BackupChapter(
                                url = "/chapter/2", name = "Vol.1 Ch.2.5", scanlator = "Group", chapterNumber = 2.5f, dateFetch = 1_700_000_002_000,
                                dateUpload = 1_695_000_000_000,
                            ),
                        ),
                    categories = listOf(1),
                    tracking =
                        listOf(
                            BackupTracking(
                                syncId = 2, libraryId = 5, trackingUrl = "https://anilist.co/manga/12345", title = "Sousou no Frieren", lastChapterRead = 1f,
                                totalChapters = 120, score = 8.5f, status = 1, startedReadingDate = 1_700_000_000_000,
                                finishedReadingDate = 1_700_400_000_000, private = true, mediaId = 12_345,
                            ),
                        ),
                    favorite = true,
                    chapterFlags = 0x101,
                    viewer_flags = 0x12,
                    history = listOf(BackupHistory("/chapter/1", 1_700_000_100_000, 60_000)),
                    updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
                    excludedScanlators = listOf("Bad Scans"),
                    notes = "Reread arc 2",
                    initialized = true,
                    memo = """{"mdId":"b0b721ff"}""".encodeToByteArray(),
                    nextUpdate = 1_900_000_000_000,
                ),
                BackupManga(
                    source = KAVITA_SOURCE,
                    url = "/manga/read-only",
                    title = "Read but not in library",
                    favorite = false,
                    viewer_flags = 0,
                    chapters = listOf(BackupChapter(url = "/c/1", name = "Chapter 1", read = true, chapterNumber = 1f)),
                    history = listOf(BackupHistory("/c/1", 1_700_000_200_000, 1_000)),
                    nextUpdate = 1_900_000_000_000,
                ),
                BackupManga(
                    source = MERGED_SOURCE,
                    url = "/merged/frieren",
                    title = "Merged Frieren",
                    dateAdded = 1_700_100_000_000,
                    viewer_flags = 0,
                    categories = listOf(2),
                    nextUpdate = 1_900_000_000_000,
                ),
            ),
        backupCategories =
            listOf(
                BackupCategory("Reading", order = 1, id = 1),
                BackupCategory("Plan to read", order = 2, id = 2, flags = 64),
                BackupCategory("Secret", order = 3, id = 3),
            ),
        backupSources =
            listOf(
                BackupSource("MangaDex", BIG_SOURCE),
                BackupSource("Kavita", KAVITA_SOURCE),
                // The name Oneechan stores for its merged source (Persistence `MergedSource.name`).
                BackupSource("Merged", MERGED_SOURCE),
            ),
        backupPreferences = richPreferences,
        backupExtensionStores = listOf(richExtensionStore),
    )

