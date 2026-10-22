/*
 * Copyright © 2015 Javier Tomás
 * Copyright © TachiyomiSY and Komikku contributors
 * Licensed under the Apache License 2.0: http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for Oneechan: the `.tachibk` models of Komikku (app/src/main/java/eu/kanade/tachiyomi/data/backup/models in
 * komikku-app/komikku), reduced to the wire format for the interop test. Chapter, tracking, history, source, preference
 * and extension-store models are Mihon's (`oneechan.host.backup`): Komikku's are the same apart from sync fields
 * (11–12, 106, 109) that Oneechan never writes. Flat search metadata (601) is left out: Oneechan has no SY search metadata.
 */
@file:OptIn(ExperimentalSerializationApi::class)

package oneechan.host.backup.komikku

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import oneechan.host.backup.BackupChapter
import oneechan.host.backup.BackupExtensionStore
import oneechan.host.backup.BackupHistory
import oneechan.host.backup.BackupPreference
import oneechan.host.backup.BackupSource
import oneechan.host.backup.BackupSourcePreferences
import oneechan.host.backup.BackupTracking

@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    @ProtoNumber(600) var backupSavedSearches: List<BackupSavedSearch> = emptyList(),
    @ProtoNumber(610) var backupFeeds: List<BackupFeed> = emptyList(),
)

@Serializable
class BackupManga(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(14) var viewer: Int = 0,
    @ProtoNumber(16) var chapters: List<BackupChapter> = emptyList(),
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(18) var tracking: List<BackupTracking> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var chapterFlags: Int = 0,
    @ProtoNumber(103) var viewer_flags: Int? = null,
    @ProtoNumber(104) var history: List<BackupHistory> = emptyList(),
    @ProtoNumber(105) var updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) var excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) var version: Long = 0,
    @ProtoNumber(110) var notes: String = "",
    @ProtoNumber(111) var initialized: Boolean = false,
    @ProtoNumber(112) var memo: ByteArray = "{}".encodeToByteArray(),
    @ProtoNumber(600) var mergedMangaReferences: List<BackupMergedMangaReference> = emptyList(),
    @ProtoNumber(602) var customStatus: Int = 0,
    @ProtoNumber(603) var customThumbnailUrl: String? = null,
    @ProtoNumber(800) var customTitle: String? = null,
    @ProtoNumber(801) var customArtist: String? = null,
    @ProtoNumber(802) var customAuthor: String? = null,
    // 803 is skipped in Komikku (a duplicate value in earlier builds).
    @ProtoNumber(804) var customDescription: String? = null,
    @ProtoNumber(805) var customGenre: List<String>? = null,
)

@Serializable
class BackupCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    @ProtoNumber(100) var flags: Long = 0,
    @ProtoNumber(900) var hidden: Boolean = false,
)

@Serializable
data class BackupMergedMangaReference(
    @ProtoNumber(1) var isInfoManga: Boolean,
    @ProtoNumber(2) var getChapterUpdates: Boolean,
    @ProtoNumber(3) var chapterSortMode: Int,
    @ProtoNumber(4) var chapterPriority: Int,
    @ProtoNumber(5) var downloadChapters: Boolean,
    @ProtoNumber(6) var mergeUrl: String,
    @ProtoNumber(7) var mangaUrl: String,
    @ProtoNumber(8) var mangaSourceId: Long,
)

@Serializable
data class BackupSavedSearch(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val query: String = "",
    @ProtoNumber(3) val filterList: String = "",
    @ProtoNumber(4) val source: Long = 0,
)

@Serializable
data class BackupFeed(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val global: Boolean = true,
    @ProtoNumber(9) val savedSearch: BackupSavedSearch? = null,
)
