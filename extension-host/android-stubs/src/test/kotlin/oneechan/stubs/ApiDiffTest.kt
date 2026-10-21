package oneechan.stubs

import oneechan.stubs.ApiDiff.Kind
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_DEPRECATED
import org.objectweb.asm.Opcodes.ACC_ENUM
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_INTERFACE
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import kotlin.test.assertEquals

class ApiDiffTest {
    private val pub = ACC_PUBLIC
    private val constant = ACC_PUBLIC or ACC_STATIC or ACC_FINAL

    private fun cls(
        name: String,
        superName: String? = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        access: Int = pub,
        signature: String? = null,
        fields: List<FieldInfo> = emptyList(),
        methods: List<MethodInfo> = emptyList(),
    ) = name to ClassInfo(name, access, superName, interfaces, signature, emptySet(), fields, methods, 52)

    private fun field(name: String, desc: String = "I", access: Int = constant, value: Any? = null) = FieldInfo(name, desc, access, null, value, emptySet())

    private fun method(name: String, desc: String = "()V", access: Int = pub) = MethodInfo(name, desc, access, null, emptyList(), emptySet(), false)

    private fun diff(old: Map<String, ClassInfo>, new: Map<String, ClassInfo>) = ApiDiff(old, new).compare().map { it.kind to "${it.className} ${it.detail}".trim() }

    @Test
    fun `members resolving through supertypes are inherited, others missing`() {
        val old =
            mapOf(
                cls(
                    "a/C",
                    superName = "a/S",
                    interfaces = listOf("a/I"),
                    methods = listOf(method("<init>"), method("toString", "()Ljava/lang/String;"), method("run"), method("gone"), method("help", access = ACC_PRIVATE), method("twice", access = pub or ACC_STATIC)),
                ),
                cls("a/S", methods = listOf(method("twice"))),
                cls("a/I", access = pub or ACC_INTERFACE or ACC_ABSTRACT, methods = listOf(method("run", access = pub or ACC_ABSTRACT))),
            )
        val new =
            mapOf(
                cls("a/C", superName = "a/S", interfaces = listOf("a/I")),
                cls("a/S", methods = listOf(method("twice"))),
                cls("a/I", access = pub or ACC_INTERFACE or ACC_ABSTRACT, methods = listOf(method("run", access = pub or ACC_ABSTRACT))),
            )
        assertEquals(
            listOf(
                Kind.MISSING_MEMBER to "a/C method <init>()V", // constructors are not inherited
                Kind.MISSING_MEMBER to "a/C method gone()V",
                Kind.MISSING_MEMBER to "a/C method twice()V", // static in old, instance method in the superclass
                Kind.INHERITED_MEMBER to "a/C method run()V -> a/I",
                Kind.INHERITED_MEMBER to "a/C method toString()Ljava/lang/String; -> java/lang/Object", // from the JDK
                Kind.NON_API_MISSING to "a/C method help()V",
            ),
            diff(old, new),
        )
    }

    @Test
    fun `classes, constants, deprecation and enum order`() {
        val old =
            mapOf(
                cls("a/K", fields = listOf(field("A", value = 1), field("B", value = "x"))),
                cls("a/Gone"),
                cls("a/Gone\$1", access = ACC_STATIC or ACC_SYNTHETIC),
                cls("a/E", superName = "java/lang/Enum", fields = listOf(field("TIP", "La/E;", constant or ACC_ENUM), field("DEBUG", "La/E;", constant or ACC_ENUM))),
                cls("a/D", access = pub or ACC_DEPRECATED),
            )
        val new =
            mapOf(
                cls("a/K", fields = listOf(field("A", value = 2), field("B", value = "x"), field("C", value = 3))),
                cls("a/E", superName = "java/lang/Enum", fields = listOf(field("DEBUG", "La/E;", constant or ACC_ENUM), field("TIP", "La/E;", constant or ACC_ENUM))),
                cls("a/D"),
                cls("a/New"),
            )
        assertEquals(
            listOf(
                Kind.MISSING_CLASS to "a/Gone",
                Kind.CLASS_CHANGED to "a/D deprecated true -> false",
                Kind.MEMBER_CHANGED to "a/K field A:I value 1 -> 2",
                Kind.NON_API_MISSING to "a/Gone\$1 synthetic class",
                Kind.ENUM_ORDER to "a/E [TIP, DEBUG] -> [DEBUG, TIP]",
                Kind.ADDED_CLASS to "a/New",
                Kind.ADDED_MEMBER to "a/K field C:I",
            ),
            diff(old, new),
        )
    }

    @Test
    fun `superinterface order is not a signature change`() {
        val old = mapOf(cls("a/C", interfaces = listOf("a/I", "a/J"), signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;La/I<TT;>;La/J;"))
        val new = mapOf(cls("a/C", interfaces = listOf("a/J", "a/I"), signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;La/J;La/I<TT;>;"))
        assertEquals(listOf(Kind.INTERFACE_ORDER to "a/C [a/I, a/J] -> [a/J, a/I]"), diff(old, new))
        assertEquals(
            ApiDiff.normalizeClassSignature("<T:Ljava/util/List<TT;>;>Ljava/lang/Object;La/I<TT;>;La/J;"),
            ApiDiff.normalizeClassSignature("<T:Ljava/util/List<TT;>;>Ljava/lang/Object;La/J;La/I<TT;>;"),
        )
    }
}
