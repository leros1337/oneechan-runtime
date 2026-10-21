package oneechan.host.source

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import oneechan.host.rpc.ChapterDto
import oneechan.host.rpc.MangaDto
import oneechan.host.rpc.MangasPageDto
import oneechan.host.rpc.PageDto

fun SManga.toDto(): MangaDto =
    MangaDto(
        // url/title are lateinit: details responses commonly omit the url (the host keeps its own).
        url = runCatching { url }.getOrDefault(""),
        title = runCatching { title }.getOrDefault(""),
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnail_url,
        updateStrategy = update_strategy.name,
        initialized = initialized,
        memo = memo,
    )

fun MangaDto.toSManga(): SManga =
    SManga.create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnailUrl
        it.update_strategy = UpdateStrategy.entries.firstOrNull { s -> s.name == updateStrategy } ?: UpdateStrategy.ALWAYS_UPDATE
        it.initialized = initialized
        it.memo = memo
    }

fun SChapter.toDto(): ChapterDto =
    ChapterDto(
        url = runCatching { url }.getOrDefault(""),
        name = runCatching { name }.getOrDefault(""),
        dateUpload = date_upload,
        chapterNumber = chapter_number,
        scanlator = scanlator,
        memo = memo,
    )

fun ChapterDto.toSChapter(): SChapter =
    SChapter.create().also {
        it.url = url
        it.name = name
        it.date_upload = dateUpload
        it.chapter_number = chapterNumber
        it.scanlator = scanlator
        it.memo = memo
    }

fun MangasPage.toDto(): MangasPageDto = MangasPageDto(mangas.map { it.toDto() }, hasNextPage)

fun Page.toDto(): PageDto = PageDto(index, url, imageUrl)

fun PageDto.toPage(): Page = Page(index, url, imageUrl)
