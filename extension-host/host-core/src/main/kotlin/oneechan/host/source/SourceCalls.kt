package oneechan.host.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Calls into extension sources across extension-lib versions.
 *
 * extension-lib 1.6 merged `getMangaDetails`/`getChapterList` into `getMangaUpdate`. Extensions
 * built against lib 1.4/1.5 still override the old suspend methods, which the 1.6 interfaces no
 * longer declare, so a plain `getMangaUpdate` call would silently fall back to the unimplemented
 * request/parse helpers. When the extension's own classes declare the legacy method it is invoked
 * reflectively instead, exactly as a lib 1.5 host would have dispatched it.
 */
object SourceCalls {
    private val legacyMethods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val absent = ConcurrentHashMap.newKeySet<Pair<Class<*>, String>>()
    private val hostClassLoader = SourceCalls::class.java.classLoader

    suspend fun mangaUpdate(
        source: Source,
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val legacyDetails = if (fetchDetails) legacyMethod(source, "getMangaDetails") else null
        val legacyChapters = if (fetchChapters) legacyMethod(source, "getChapterList") else null
        val update =
            if (legacyDetails == null && legacyChapters == null) {
                source.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
            } else {
                coroutineScope {
                    val details =
                        async {
                            when {
                                !fetchDetails -> manga
                                legacyDetails != null -> callSuspend(source, legacyDetails, manga) as SManga
                                else -> source.getMangaUpdate(manga, chapters, true, false).manga
                            }
                        }
                    val chapterList =
                        async {
                            @Suppress("UNCHECKED_CAST")
                            when {
                                !fetchChapters -> chapters
                                legacyChapters != null -> callSuspend(source, legacyChapters, manga) as List<SChapter>
                                else -> source.getMangaUpdate(manga, chapters, false, true).chapters
                            }
                        }
                    SMangaUpdate(details.await(), chapterList.await())
                }
            }
        if (fetchChapters && source is HttpSource) {
            // Mihon calls this for every fetched chapter before chapter-number recognition.
            @Suppress("DEPRECATION")
            update.chapters.forEach { source.prepareNewChapter(it, manga) }
        }
        return update
    }

    suspend fun pageList(
        source: Source,
        chapter: SChapter,
    ): List<Page> = source.getPageList(chapter)

    /** The legacy suspend method declared by the extension's own classes (not the host API). */
    private fun legacyMethod(
        source: Source,
        name: String,
    ): Method? {
        val key = source.javaClass to name
        legacyMethods[key]?.let { return it }
        if (key in absent) return null
        var cls: Class<*>? = source.javaClass
        while (cls != null && cls.classLoader !== hostClassLoader && cls.classLoader != null) {
            val method =
                cls.declaredMethods.firstOrNull {
                    it.name == name &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == SManga::class.java &&
                        it.parameterTypes[1] == Continuation::class.java &&
                        !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
            if (method != null) {
                method.isAccessible = true
                legacyMethods[key] = method
                return method
            }
            cls = cls.superclass
        }
        absent += key
        return null
    }

    private suspend fun callSuspend(
        target: Any,
        method: Method,
        vararg args: Any?,
    ): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
}
