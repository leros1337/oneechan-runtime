package oneechan.host.platform

import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On a flat classpath (fat jar, embedded JVM) a stub class next to AndroidCompat's implementation
 * of the same class may win and throw "Stub!": filteredAndroidStubs must strip every class this
 * module compiles, including classes a source file declares under another name.
 */
class AndroidStubsOverlapTest {
    @Test
    fun `filtered stub jar contains no class AndroidCompat implements`() {
        val jar = File(System.getProperty("oneechan.filteredStubs") ?: error("run through Gradle"))
        val stubs = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toSet() }
        val implemented =
            System.getProperty("oneechan.compatClasses").split(File.pathSeparator).map(::File).filter { it.isDirectory }.flatMap { root ->
                root.walk().filter { it.isFile && it.extension == "class" }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
            }.toSet()

        assertTrue(stubs.size > 3000, "only ${stubs.size} stub classes")
        assertTrue("android/content/Context.class" in implemented && "android/app/Activity.class" in stubs)
        assertEquals(emptySet(), stubs intersect implemented)
    }
}
