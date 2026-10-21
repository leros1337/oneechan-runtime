package oneechan.host.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A JSON document on disk, replaced atomically (temp file + rename) on every write. */
class AtomicJsonFile<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val default: () -> T,
) {
    @Synchronized
    fun read(): T =
        if (path.exists()) {
            runCatching { json.decodeFromString(serializer, path.readText()) }.getOrElse { default() }
        } else {
            default()
        }

    @Synchronized
    fun write(value: T) {
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
