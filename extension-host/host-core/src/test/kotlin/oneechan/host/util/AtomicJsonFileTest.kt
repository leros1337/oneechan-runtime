package oneechan.host.util

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The parsed document is kept in memory until the file changes (by a write here or by something else). */
class AtomicJsonFileTest {
    private lateinit var dir: Path
    private lateinit var path: Path

    private fun file() = AtomicJsonFile(path, ListSerializer(String.serializer()), Json) { emptyList() }

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("atomic-json-test")
        path = dir.resolve("list.json")
    }

    @AfterEach
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `reading twice returns the same parsed value without parsing again`() {
        path.writeText("""["a","b"]""")
        val file = file()
        val first = file.read()
        assertEquals(listOf("a", "b"), first)
        assertSame(first, file.read())
    }

    @Test
    fun `a write replaces the cached value`() {
        val file = file()
        file.write(listOf("a"))
        val first = file.read()
        file.write(listOf("b"))
        val second = file.read()
        assertNotSame(first, second)
        assertEquals(listOf("b"), second)
    }

    @Test
    fun `a write of the same size in the same instant is still seen`() {
        val file = file()
        file.write(listOf("a"))
        val stamp = Files.getLastModifiedTime(path)
        file.read()
        file.write(listOf("b"))
        // As on a file system with a coarse clock: the new file has the old time and size.
        Files.setLastModifiedTime(path, stamp)
        assertEquals(listOf("b"), file.read())
    }

    @Test
    fun `a change made by something else is picked up`() {
        path.writeText("""["a"]""")
        val file = file()
        assertEquals(listOf("a"), file.read())
        path.writeText("""["a","b"]""")
        assertEquals(listOf("a", "b"), file.read())
    }

    @Test
    fun `a change of the same size is picked up through the modification time`() {
        path.writeText("""["a"]""")
        val old = Files.getLastModifiedTime(path)
        val file = file()
        file.read()
        path.writeText("""["b"]""")
        Files.setLastModifiedTime(path, FileTime.fromMillis(old.toMillis() + 5_000))
        assertEquals(listOf("b"), file.read())
    }

    @Test
    fun `a deleted file reads as the default again`() {
        path.writeText("""["a"]""")
        val file = file()
        file.read()
        Files.delete(path)
        assertEquals(emptyList(), file.read())
        assertNull(file.stamp())
    }

    @Test
    fun `a file that cannot be parsed reads as the default`() {
        path.writeText("not json")
        assertEquals(emptyList(), file().read())
    }

    @Test
    fun `the stamp changes with each write`() {
        val file = file()
        assertNull(file.stamp())
        file.write(listOf("a"))
        val first = file.stamp()
        file.write(listOf("b"))
        assertNotEquals(first, file.stamp())
    }

    @Test
    fun `update reads the cached value and writes the result`() {
        val file = file()
        file.write(listOf("a"))
        file.update { it + "b" }
        assertEquals(listOf("a", "b"), file.read())
        assertEquals("""["a","b"]""", Files.readString(path))
    }
}
