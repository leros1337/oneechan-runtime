package oneechan.stubs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureParserTest {
    private fun parse(body: String) = SignatureParser.parse("// Signature format: 2.0\n$body", "test.txt")

    @Test
    fun `class header with generics, supertypes and annotations`() {
        val api =
            parse(
                """
                package android.util {

                  @Deprecated public final class ArrayMap<K, V> extends android.util.Base<K> implements java.util.Map<K,V> java.lang.Cloneable {
                    ctor public ArrayMap(int) throws java.io.IOException;
                  }

                  @java.lang.FunctionalInterface public static interface ArrayMap.Listener<T extends java.lang.Object & java.lang.Comparable<? super T>> extends java.util.EventListener android.util.Other {
                    method public void onChange(T);
                  }

                }
                """.trimIndent(),
            )
        val map = api.byName.getValue("android.util.ArrayMap")
        assertEquals(ClassKind.CLASS, map.kind)
        assertEquals(setOf("public", "final"), map.modifiers)
        assertEquals(listOf("Deprecated"), map.annotations.map { it.name })
        assertEquals(listOf("K", "V"), map.typeParams.map { it.name })
        assertEquals(ClassType("android.util.Base", listOf(ClassType("K"))), map.superclass)
        assertEquals(listOf("java.util.Map", "java.lang.Cloneable"), map.interfaces.map { it.name })
        assertEquals(listOf(ClassType("java.io.IOException")), map.ctors.single().throws)

        val listener = api.byName.getValue("android.util.ArrayMap.Listener")
        assertEquals(ClassKind.INTERFACE, listener.kind)
        assertEquals("android.util.ArrayMap", listener.outerName)
        assertEquals("android/util/ArrayMap\$Listener", listener.binaryName)
        assertEquals(listOf("java.util.EventListener", "android.util.Other"), listener.interfaces.map { it.name })
        val bound = listener.typeParams.single().bounds
        assertEquals(ClassType("java.lang.Object"), bound[0])
        assertEquals(ClassType("java.lang.Comparable", listOf(WildcardType(ClassType("T"), superBound = true))), bound[1])
    }

    @Test
    fun `methods, parameters and annotation defaults`() {
        val api =
            parse(
                """
                package a {

                  public abstract class B {
                    method @NonNull public static <T> java.util.List<T> of(@Nullable T...);
                    method @Deprecated public abstract int[][] grid(String, @IntRange(from=0, to=10) int);
                    method public default void d() throws java.io.IOException, java.lang.InterruptedException;
                  }

                  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface C {
                    method public abstract String name() default "x(y)";
                    method public abstract Class<?>[] types() default {};
                  }

                }
                """.trimIndent(),
            )
        val b = api.byName.getValue("a.B")
        val of = b.methods[0]
        assertEquals("of", of.name)
        assertEquals(listOf("T"), of.typeParams.map { it.name })
        assertEquals(ClassType("java.util.List", listOf(ClassType("T"))), of.returnType)
        assertEquals(ArrayType(ClassType("T"), varargs = true), of.params.single().type)
        assertEquals(listOf("Nullable"), of.params.single().annotations.map { it.name })
        assertEquals(listOf("NonNull"), of.annotations.map { it.name })

        val grid = b.methods[1]
        assertEquals(ArrayType(ArrayType(PrimitiveType("int"))), grid.returnType)
        assertEquals("from=0, to=10", grid.params[1].annotations.single().args)
        assertTrue("abstract" in grid.modifiers)
        assertEquals(2, b.methods[2].throws.size)
        assertTrue("default" in b.methods[2].modifiers)

        val c = api.byName.getValue("a.C")
        assertEquals(ClassKind.ANNOTATION, c.kind)
        assertEquals("java.lang.annotation.RetentionPolicy.RUNTIME", c.annotations.single().args)
        assertEquals("\"x(y)\"", c.methods[0].defaultValue)
        assertEquals("{}", c.methods[1].defaultValue)
    }

    @Test
    fun `fields keep constant values and drop trailing comments`() {
        val api =
            parse(
                """
                package a {

                  public final class K {
                    field public static final char C = 115; // 0x0073 's'
                    field public static final double NAN = (0.0/0.0);
                    field public static final long L = -1L; // 0xffffffffffffffffL
                    field public static final String S = "http://x\"y${"\\"}u00e9\\";
                    field public static final int NO_VALUE;
                    field @Deprecated public int counter;
                  }

                  public enum E {
                    enum_constant public static final a.E B;
                    enum_constant @Deprecated public static final a.E A;
                  }

                }
                """.trimIndent(),
            )
        val fields = api.byName.getValue("a.K").fields.associateBy { it.name }
        assertEquals("115", fields.getValue("C").value)
        assertEquals("(0.0/0.0)", fields.getValue("NAN").value)
        assertEquals("-1L", fields.getValue("L").value)
        assertEquals("\"http://x\\\"y\\u00e9\\\\\"", fields.getValue("S").value)
        assertNull(fields.getValue("NO_VALUE").value)
        assertEquals(listOf("Deprecated"), fields.getValue("counter").annotations.map { it.name })

        val e = api.byName.getValue("a.E")
        assertEquals(ClassKind.ENUM, e.kind)
        assertEquals(listOf("B", "A"), e.enumConstants.map { it.name })
        assertEquals(listOf("Deprecated"), e.enumConstants[1].annotations.map { it.name })
    }

    @Test
    fun `rejects other signature formats and malformed lines`() {
        assertThrows<SignatureParseException> { SignatureParser.parse("// Signature format: 3.0\n", "x.txt") }
        assertThrows<SignatureParseException> { parse("package a {\n  public class B {\n    method public void (;\n  }\n}\n") }
    }
}
