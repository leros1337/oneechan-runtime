package oneechan.stubs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnumOrderTest {
    @Test
    fun `reads constants in declaration order, skipping comments, annotations, arguments and bodies`() {
        val source =
            """
            package android.text;
            /** An enum Alignment { NOT_THIS } in a comment. */
            public class Layout {
                private static final String S = "enum Alignment { NOR_THIS }";
                public enum Alignment {
                    ALIGN_NORMAL,
                    ALIGN_OPPOSITE("x, y", 'c'),
                    /** @hide */
                    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
                    ALIGN_LEFT { @Override public String toString() { return "left; right"; } },
                    @Deprecated ALIGN_RIGHT;
                    Alignment() {}
                }
                public enum MessageLevel implements java.io.Serializable {
                    TIP,
                    DEBUG
                };
            }
            """.trimIndent()
        assertEquals(listOf("ALIGN_NORMAL", "ALIGN_OPPOSITE", "ALIGN_LEFT", "ALIGN_RIGHT"), EnumOrder.constantsIn(source, "Alignment"))
        assertEquals(listOf("TIP", "DEBUG"), EnumOrder.constantsIn(source, "MessageLevel"))
        assertNull(EnumOrder.constantsIn(source, "Missing"))
    }

    @Test
    fun `parses the vendored file format`() {
        val order = EnumOrder.parse("# comment\n\nandroid.a.B.C  X Y\nandroid.a.D Z\n")
        assertEquals(mapOf("android.a.B.C" to listOf("X", "Y"), "android.a.D" to listOf("Z")), order)
    }
}
