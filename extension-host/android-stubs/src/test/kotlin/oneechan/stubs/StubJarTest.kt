package oneechan.stubs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the generated android-stubs.jar itself (built from api/current.txt). */
class StubJarTest {
    private val jar = Path(System.getProperty("oneechan.stubsJar") ?: error("run through Gradle (oneechan.stubsJar)"))
    private val classes by lazy { readClasses(jar) }

    @Test
    fun `contains the android and dalvik API as Java 8 class files, with its notices`() {
        assertTrue(classes.size > 3000, "only ${classes.size} classes")
        assertTrue(classes.keys.all { it.startsWith("android/") || it.startsWith("dalvik/") }, "unexpected packages")
        assertTrue(classes.values.all { it.majorVersion == 52 })
        assertNull(classes["android/annotation/NonNull"]) // compile-only
        assertNotNull(classes["dalvik/system/DexClassLoader"])
        ZipFile(jar.toFile()).use { zip ->
            assertNotNull(zip.getEntry("META-INF/android-stubs/NOTICE"))
            assertNotNull(zip.getEntry("META-INF/android-stubs/LICENSE"))
            assertTrue(zip.entries().asSequence().none { it.name.startsWith("res/") || it.name == "resources.arsc" })
        }
    }

    @Test
    fun `compile-time constants extensions inline`() {
        assertEquals(30, classes.getValue("android/os/Build\$VERSION_CODES").field("R")!!.value)
        assertEquals("android.intent.action.VIEW", classes.getValue("android/content/Intent").field("ACTION_VIEW")!!.value)
        assertEquals(3, classes.getValue("android/util/Log").field("DEBUG")!!.value)
        assertEquals(0x1010001, classes.getValue("android/R\$attr").field("label")!!.value)
        assertEquals(9.80665f, classes.getValue("android/hardware/SensorManager").field("GRAVITY_EARTH")!!.value)
        assertEquals(86400000L, classes.getValue("android/text/format/DateUtils").field("DAY_IN_MILLIS")!!.value)
        assertEquals('s'.code, classes.getValue("android/app/SearchManager").field("MENU_KEY")!!.value)
        // Not a constant in the API: must not be inlined.
        assertNull(classes.getValue("dalvik/bytecode/OpcodeInfo").field("MAXIMUM_VALUE")!!.value)
    }

    @Test
    fun `stubs throw Stub and enums keep their declaration order`() {
        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            val size = loader.loadClass("android.util.Size")
            val error = assertThrows<InvocationTargetException> { size.getConstructor(Int::class.java, Int::class.java).newInstance(1, 2) }
            assertEquals("Stub!", error.cause?.message)
            val level = loader.loadClass("android.webkit.ConsoleMessage\$MessageLevel")
            val values = level.getMethod("values").invoke(null) as Array<*>
            assertEquals(listOf("TIP", "LOG", "WARNING", "ERROR", "DEBUG"), values.map { (it as Enum<*>).name })
        }
    }
}
