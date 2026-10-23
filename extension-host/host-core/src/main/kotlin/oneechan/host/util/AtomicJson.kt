package oneechan.host.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** How a file stood when it was read: its modification time and size. */
@Serializable
data class FileStamp(
    val modifiedNanos: Long,
    val size: Long,
)

/**
 * A JSON document on disk, replaced atomically (temp file + rename) on every write.
 *
 * The parsed value is kept until the file's modification time or size changes (or this class writes
 * it), so reading is a `stat` and not a parse. The same instance comes back while nothing changed:
 * callers must not mutate it.
 */
class AtomicJsonFile<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val default: () -> T,
) {
    private class Cached<T>(
        val stamp: FileStamp,
        val value: T,
    )

    private var cached: Cached<T>? = null

    /** The file's modification time and size now; null when it doesn't exist. */
    fun stamp(): FileStamp? =
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            FileStamp(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size())
        } catch (_: IOException) {
            null
        }

    @Synchronized
    fun read(): T {
        // Stat before reading: a replace in between leaves an old stamp on newer content, which only costs a re-parse.
        val stamp = stamp()
        if (stamp == null) {
            cached = null
            return default()
        }
        cached?.takeIf { it.stamp == stamp }?.let { return it.value }
        val value = runCatching { json.decodeFromString(serializer, path.readText()) }.getOrElse { default() }
        cached = Cached(stamp, value)
        return value
    }

    @Synchronized
    fun write(value: T) {
        cached = null
        path.createParentDirectories()
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun update(transform: (T) -> T): T = transform(read()).also(::write)
}
