package oneechan.host.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.github.oshai.kotlinlogging.KotlinLogging
import oneechan.host.rpc.RelatedMangasDto
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Related titles from the source website (Komikku's "related mangas").
 *
 * keiyoushi's extension-lib 1.6 declares Komikku's API on `CatalogueSource`: `supportsRelatedMangas`,
 * `disableRelatedMangas`, `disableRelatedMangasBySearch` and `suspend fetchRelatedMangaList(SManga)`,
 * and its themes (Madara, MangaThemesia, ...) and many extensions implement them. The host API
 * (Suwayomi's) does not declare them, so they are found on the extension's own classes and called
 * reflectively; their names survive R8 because they override library members.
 */
object RelatedCalls {
    private const val SUPPORTS = "getSupportsRelatedMangas"
    private const val DISABLED = "getDisableRelatedMangas"
    private const val SEARCH_DISABLED = "getDisableRelatedMangasBySearch"
    private const val FETCH = "fetchRelatedMangaList"

    private val logger = KotlinLogging.logger {}
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val absent = ConcurrentHashMap.newKeySet<Pair<Class<*>, String>>()

    /** The source's own related list for [manga] when it has one and it loads; the flags either way. */
    suspend fun related(
        source: Source,
        manga: SManga,
    ): RelatedMangasDto {
        val disabled = flag(source, DISABLED)
        val searchDisabled = flag(source, SEARCH_DISABLED)
        val fetch = if (!disabled && flag(source, SUPPORTS)) method(source, FETCH, SManga::class.java, Continuation::class.java) else null
        if (fetch == null) return RelatedMangasDto(supported = false, disabled = disabled, searchDisabled = searchDisabled)
        val list =
            try {
                (callSuspend(source, fetch, manga) as? List<*>).orEmpty().filterIsInstance<SManga>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The flags still matter to the app, which searches for related titles instead.
                logger.warn(e) { "Related titles of ${source.name} failed" }
                return RelatedMangasDto(supported = false, disabled = false, searchDisabled = searchDisabled)
            }
        val mangas = list.map { it.toDto() }.filter { it.url.isNotEmpty() && it.url != manga.url }.distinctBy { it.url }
        return RelatedMangasDto(supported = true, mangas = mangas, disabled = false, searchDisabled = searchDisabled)
    }

    /** A Boolean property getter of the extension; false when absent or failing (as Komikku's defaults). */
    private fun flag(
        source: Source,
        getter: String,
    ): Boolean {
        val method = method(source, getter) ?: return false
        return runCatching { method.invoke(source) as? Boolean }.getOrNull() ?: false
    }

    private fun method(
        source: Source,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        val key = source.javaClass to name
        methods[key]?.let { return it }
        if (key in absent) return null
        val method = findMethod(source.javaClass, name, parameterTypes)
        if (method == null || java.lang.reflect.Modifier.isStatic(method.modifiers)) {
            absent += key
            return null
        }
        runCatching { method.isAccessible = true }
        methods[key] = method
        return method
    }

    /** Public members first (KeiSource's), then any declared up the class chain (private test fakes). */
    private fun findMethod(
        type: Class<*>,
        name: String,
        parameterTypes: Array<out Class<*>>,
    ): Method? {
        runCatching { return type.getMethod(name, *parameterTypes) }
        var cls: Class<*>? = type
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(parameterTypes) }?.let { return it }
            cls = cls.superclass
        }
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
