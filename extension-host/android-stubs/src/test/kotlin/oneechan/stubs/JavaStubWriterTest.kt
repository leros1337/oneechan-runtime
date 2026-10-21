package oneechan.stubs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaStubWriterTest {
    @TempDir
    lateinit var dir: Path

    // A backslash for signature-file escapes inside raw strings.
    private val bs = "\\"

    private val signature =
        """
        // Signature format: 2.0
        package a {

          public abstract class Base<T> {
            ctor public Base(String, int);
            ctor public Base(java.util.List<T>) throws java.io.IOException;
            method public abstract int hashCode();
            method protected abstract void layout(T, boolean);
          }

          public interface Both extends a.First a.Second {
          }

          public interface First {
            method public default String name();
          }

          public class Generic {
            ctor public Generic(Class<T>);
          }

          public class Hidden {
          }

          @Deprecated public final class Impl extends a.Base<java.lang.String> implements java.lang.Comparable<a.Impl> {
            ctor public Impl();
            method public int compareTo(a.Impl);
            method @Deprecated @NonNull public static <E extends java.lang.Number> java.util.List<E> wrap(@Nullable E...);
            field public static final char C = 115; // 0x0073 's'
            field public static final double NAN = (0.0/0.0);
            field public static final float F = 1.5f;
            field public static final a.Impl INSTANCE;
            field public static final long L = -9223372036854775808L;
            field public static final int NO_VALUE;
            field public static final String S = "q${bs}u0022${bs}n${bs}u00e9 // not a comment";
            field public final int counter;
          }

          public enum Level {
            enum_constant public static final a.Level DEBUG;
            enum_constant @Deprecated public static final a.Level TIP;
          }

          @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Marker {
            method public abstract String value() default "x";
          }

          public class NoCtor {
          }

          public class Process {
            ctor public Process();
            method public Process start(a.Process);
          }

          public interface Second {
            method public String name();
          }

          public class Sub extends a.NoCtor {
            ctor public Sub();
          }

        }

        package b {

          public class Far extends a.Hidden {
            ctor public Far();
          }

        }

        package c {

          public class Skipped {
          }

        }
        """.trimIndent()

    private fun generate(): Map<String, ByteArray> {
        val api = SignatureParser.parse(signature, "test.txt")
        val writer = JavaStubWriter(api, listOf("a", "b"), enumOrder = mapOf("a.Level" to listOf("TIP", "DEBUG")))
        val result = writer.write(dir)
        assertEquals(emptyList(), result.warnings)
        assertTrue("c/Skipped.java" !in result.files)
        return InMemoryJavac.compile(result.files.associateWith { Files.readString(dir.resolve(it)) })
    }

    private val classes by lazy { generate() }

    private fun info(name: String) = readClassInfo(classes.getValue(name))

    @Test
    fun `constants keep their values and other static finals are not constants`() {
        val impl = info("a/Impl")
        assertEquals(115, impl.field("C")!!.value)
        assertEquals(Double.NaN, impl.field("NAN")!!.value)
        assertEquals(1.5f, impl.field("F")!!.value)
        assertEquals(Long.MIN_VALUE, impl.field("L")!!.value)
        assertEquals("q\"\n\u00e9 // not a comment", impl.field("S")!!.value)
        val noValue = impl.field("NO_VALUE")!!
        assertNull(noValue.value)
        assertEquals(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, noValue.access)
        assertNull(impl.field("INSTANCE")!!.value)
        assertEquals(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, impl.field("counter")!!.access)
    }

    @Test
    fun `descriptors, generics, deprecation and annotations match the declaration`() {
        val impl = info("a/Impl")
        assertTrue(impl.access and Opcodes.ACC_DEPRECATED != 0)
        assertEquals("La/Base<Ljava/lang/String;>;Ljava/lang/Comparable<La/Impl;>;", impl.signature)
        val wrap = impl.method("wrap", "([Ljava/lang/Number;)Ljava/util/List;")!!
        assertEquals("<E:Ljava/lang/Number;>([TE;)Ljava/util/List<TE;>;", wrap.signature)
        assertTrue(wrap.access and Opcodes.ACC_VARARGS != 0 && wrap.access and Opcodes.ACC_DEPRECATED != 0)
        assertTrue("-Landroid/annotation/NonNull;" in wrap.annotations)
        assertNotNull(impl.method("compareTo", "(La/Impl;)I"))
        assertNotNull(impl.method("compareTo", "(Ljava/lang/Object;)I")) // bridge, as javac emits for the original

        // Unqualified names in the signature are java.lang types, even next to a same-named class.
        assertNotNull(info("a/Process").method("start", "(La/Process;)Ljava/lang/Process;"))

        val marker = info("a/Marker")
        assertTrue("+Ljava/lang/annotation/Retention;" in marker.annotations)
        assertTrue(marker.method("value", "()Ljava/lang/String;")!!.hasAnnotationDefault)
    }

    @Test
    fun `elided overrides of abstract methods are restored`() {
        val impl = info("a/Impl")
        val hashCode = impl.method("hashCode", "()I")!!
        assertEquals(0, hashCode.access and Opcodes.ACC_ABSTRACT)
        assertTrue(hashCode.access and Opcodes.ACC_DEPRECATED != 0) // member of a deprecated class
        val layout = impl.method("layout", "(Ljava/lang/String;Z)V")!!
        assertTrue(layout.access and Opcodes.ACC_PROTECTED != 0)
        // An interface inheriting a default and an abstract name() must redeclare it.
        assertTrue(info("a/Both").method("name", "()Ljava/lang/String;")!!.access and Opcodes.ACC_ABSTRACT != 0)
    }

    @Test
    fun `constructors are callable from subclasses and generic constructors keep their type variable`() {
        val visibility = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE
        assertEquals(0, info("a/NoCtor").method("<init>", "()V")!!.access and visibility) // same-package subclass
        assertEquals(Opcodes.ACC_PROTECTED, info("a/Hidden").method("<init>", "()V")!!.access and visibility) // b.Far
        assertNotNull(info("b/Far").method("<init>", "()V"))
        val generic = info("a/Generic").method("<init>", "(Ljava/lang/Class;)V")!!
        assertEquals("<T:Ljava/lang/Object;>(Ljava/lang/Class<TT;>;)V", generic.signature)
    }

    @Test
    fun `enums follow the declaration order and work, other bodies throw Stub`() {
        val level = info("a/Level")
        assertEquals(listOf("TIP", "DEBUG"), level.fields.filter { it.access and Opcodes.ACC_ENUM != 0 }.map { it.name })
        assertTrue(level.field("TIP")!!.access and Opcodes.ACC_DEPRECATED != 0)

        val loader = InMemoryJavac.Loader(classes)
        val values = loader.loadClass("a.Level").getMethod("values").invoke(null) as Array<*>
        assertEquals(listOf("TIP", "DEBUG"), values.map { (it as Enum<*>).name })

        val impl = loader.loadClass("a.Impl")
        val error = assertThrows<InvocationTargetException> { impl.getMethod("wrap", Array<Number>::class.java).invoke(null, arrayOf<Number>()) }
        assertEquals("Stub!", (error.cause as RuntimeException).message)
        assertThrows<InvocationTargetException> { impl.getConstructor().newInstance() }
    }

    @Test
    fun `string literals are re-encoded without unicode escapes for quotes and line breaks`() {
        val value = "a\"b\\c\n\r\t\u0001\u00e9\ud83d\ude00"
        val literal = javaStringLiteral(value)
        assertTrue(literal.all { it.code in 0x20..0x7e })
        assertTrue("\\u0022" !in literal && "\\u000a" !in literal && "\\u005c" !in literal)
        assertEquals(value, unescapeJava(literal.substring(1, literal.length - 1)))
        assertEquals("\u0000A\u00e9", unescapeJava("\\0\\101\\u00e9"))
    }
}
