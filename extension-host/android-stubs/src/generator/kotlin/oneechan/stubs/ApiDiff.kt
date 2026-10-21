package oneechan.stubs

import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_ANNOTATION
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_DEPRECATED
import org.objectweb.asm.Opcodes.ACC_ENUM
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_INTERFACE
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PROTECTED
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ACC_TRANSIENT
import org.objectweb.asm.Opcodes.ACC_VARARGS
import org.objectweb.asm.Opcodes.ACC_VOLATILE
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * Compares two stub jars at the class-file level. Every class and API (public/protected) member of
 * the old jar must still link against the new one: a member the new class no longer declares is
 * fine when JVM resolution finds it in a supertype (signature files omit overrides that change
 * nothing), and is reported as [Kind.MISSING_MEMBER] otherwise.
 */
class ApiDiff(
    private val old: Map<String, ClassInfo>,
    private val new: Map<String, ClassInfo>,
    /** Resolves supertypes of new classes: the new jar, the code that replaces filtered stubs, the JDK. */
    private val resolver: ClassResolver = ClassResolver(listOf(new)),
) {
    enum class Kind(val breaking: Boolean, val description: String) {
        MISSING_CLASS(true, "class missing (not synthetic)"),
        MISSING_MEMBER(true, "API member missing (does not resolve through supertypes either)"),
        CLASS_CHANGED(false, "class flags, supertypes, generic signature or deprecation differ"),
        MEMBER_CHANGED(false, "member flags, constant value, generic signature, exceptions or deprecation differ"),
        INHERITED_MEMBER(false, "member no longer declared; resolves to the same member of a supertype"),
        NON_API_MISSING(false, "synthetic class, private or package-private member missing"),
        INTERFACE_ORDER(false, "same superinterfaces, declared in a different order"),
        ENUM_ORDER(false, "enum constants declared in a different order (ordinals differ)"),
        ANNOTATIONS_CHANGED(false, "annotations differ"),
        ADDED_CLASS(false, "class added"),
        ADDED_MEMBER(false, "API member added"),
    }

    data class Entry(val kind: Kind, val className: String, val detail: String) {
        override fun toString() = "${kind.name} ${className.replace('/', '.')}${if (detail.isEmpty()) "" else " $detail"}"
    }

    fun compare(): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((name, o) in old) {
            val n = new[name]
            when {
                n != null -> compareClass(o, n, out)
                // javac's access-constructor tags (Outer$1) and other synthetic classes are not API.
                o.access and ACC_SYNTHETIC != 0 || ANONYMOUS.containsMatchIn(name) -> out += Entry(Kind.NON_API_MISSING, name, "synthetic class")
                else -> out += Entry(Kind.MISSING_CLASS, name, "")
            }
        }
        for (name in new.keys - old.keys) out += Entry(Kind.ADDED_CLASS, name, "")
        return out.sortedWith(compareBy({ it.kind.ordinal }, { it.className }, { it.detail }))
    }

    private fun compareClass(o: ClassInfo, n: ClassInfo, out: MutableList<Entry>) {
        val classMask = ACC_PUBLIC or ACC_PROTECTED or ACC_PRIVATE or ACC_STATIC or ACC_FINAL or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION or ACC_ENUM
        val changes = mutableListOf<String>()
        if (o.access and classMask != n.access and classMask) changes += "flags ${flags(o.access and classMask)} -> ${flags(n.access and classMask)}"
        if (o.superName != n.superName) changes += "superclass ${o.superName} -> ${n.superName}"
        if (o.interfaces.toSet() != n.interfaces.toSet()) changes += "interfaces ${o.interfaces.sorted()} -> ${n.interfaces.sorted()}"
        if (normalizeClassSignature(o.signature) != normalizeClassSignature(n.signature)) changes += "signature ${o.signature} -> ${n.signature}"
        if (changes.isEmpty() && (o.interfaces != n.interfaces || o.signature != n.signature)) {
            out += Entry(Kind.INTERFACE_ORDER, o.name, "${o.interfaces} -> ${n.interfaces}")
        }
        if (o.access and ACC_DEPRECATED != n.access and ACC_DEPRECATED) changes += "deprecated ${o.access and ACC_DEPRECATED != 0} -> ${n.access and ACC_DEPRECATED != 0}"
        changes.forEach { out += Entry(Kind.CLASS_CHANGED, o.name, it) }
        if (o.annotations - DEPRECATED != n.annotations - DEPRECATED) {
            out += Entry(Kind.ANNOTATIONS_CHANGED, o.name, "class ${o.annotations - DEPRECATED} -> ${n.annotations - DEPRECATED}")
        }

        for (f in o.fields) {
            val match = n.fields.firstOrNull { it.name == f.name && it.desc == f.desc }
            val id = "field ${f.name}:${f.desc}"
            when {
                match != null -> compareField(o.name, id, f, match, out)
                !isApi(f.access) -> out += Entry(Kind.NON_API_MISSING, o.name, id)
                else -> out += resolveField(n, f)?.let { Entry(Kind.INHERITED_MEMBER, o.name, "$id -> $it") } ?: Entry(Kind.MISSING_MEMBER, o.name, id)
            }
        }
        for (m in o.methods) {
            val match = n.method(m.name, m.desc)
            val id = "method ${m.name}${m.desc}" + if (m.access and ACC_BRIDGE != 0) " (bridge)" else ""
            when {
                match != null -> compareMethod(o.name, id, m, match, out)
                !isApi(m.access) -> out += Entry(Kind.NON_API_MISSING, o.name, id)
                else -> out += resolveMethod(n, m)?.let { Entry(Kind.INHERITED_MEMBER, o.name, "$id -> $it") } ?: Entry(Kind.MISSING_MEMBER, o.name, id)
            }
        }
        for (f in n.fields) if (isApi(f.access) && o.fields.none { it.name == f.name && it.desc == f.desc }) out += Entry(Kind.ADDED_MEMBER, o.name, "field ${f.name}:${f.desc}")
        for (m in n.methods) if (isApi(m.access) && m.access and ACC_SYNTHETIC == 0 && o.method(m.name, m.desc) == null) out += Entry(Kind.ADDED_MEMBER, o.name, "method ${m.name}${m.desc}")

        val oldOrder = o.fields.filter { it.access and ACC_ENUM != 0 }.map { it.name }
        val newOrder = n.fields.filter { it.access and ACC_ENUM != 0 }.map { it.name }
        if (oldOrder != newOrder && oldOrder.toSet() == newOrder.toSet()) out += Entry(Kind.ENUM_ORDER, o.name, "$oldOrder -> $newOrder")
    }

    private fun compareField(owner: String, id: String, o: FieldInfo, n: FieldInfo, out: MutableList<Entry>) {
        val mask = ACC_PUBLIC or ACC_PROTECTED or ACC_PRIVATE or ACC_STATIC or ACC_FINAL or ACC_VOLATILE or ACC_TRANSIENT or ACC_ENUM
        val changes = mutableListOf<String>()
        if (o.access and mask != n.access and mask) changes += "flags ${flags(o.access and mask)} -> ${flags(n.access and mask)}"
        if (o.value != n.value) changes += "value ${literal(o.value)} -> ${literal(n.value)}"
        if (o.signature != n.signature) changes += "signature ${o.signature} -> ${n.signature}"
        if (o.access and ACC_DEPRECATED != n.access and ACC_DEPRECATED) changes += "deprecated ${o.access and ACC_DEPRECATED != 0} -> ${n.access and ACC_DEPRECATED != 0}"
        if (!isApi(o.access)) return
        changes.forEach { out += Entry(Kind.MEMBER_CHANGED, owner, "$id $it") }
        if (o.annotations - DEPRECATED != n.annotations - DEPRECATED) out += Entry(Kind.ANNOTATIONS_CHANGED, owner, "$id ${o.annotations - DEPRECATED} -> ${n.annotations - DEPRECATED}")
    }

    private fun compareMethod(owner: String, id: String, o: MethodInfo, n: MethodInfo, out: MutableList<Entry>) {
        if (!isApi(o.access)) return
        val mask = ACC_PUBLIC or ACC_PROTECTED or ACC_PRIVATE or ACC_STATIC or ACC_FINAL or ACC_ABSTRACT or ACC_VARARGS or ACC_BRIDGE
        val changes = mutableListOf<String>()
        if (o.access and mask != n.access and mask) changes += "flags ${flags(o.access and mask)} -> ${flags(n.access and mask)}"
        if (o.signature != n.signature) changes += "signature ${o.signature} -> ${n.signature}"
        if (o.exceptions.toSet() != n.exceptions.toSet()) changes += "throws ${o.exceptions.sorted()} -> ${n.exceptions.sorted()}"
        if (o.hasAnnotationDefault != n.hasAnnotationDefault) changes += "annotation default ${o.hasAnnotationDefault} -> ${n.hasAnnotationDefault}"
        if (o.access and ACC_DEPRECATED != n.access and ACC_DEPRECATED) changes += "deprecated ${o.access and ACC_DEPRECATED != 0} -> ${n.access and ACC_DEPRECATED != 0}"
        changes.forEach { out += Entry(Kind.MEMBER_CHANGED, owner, "$id $it") }
        if (o.annotations - DEPRECATED != n.annotations - DEPRECATED) out += Entry(Kind.ANNOTATIONS_CHANGED, owner, "$id ${o.annotations - DEPRECATED} -> ${n.annotations - DEPRECATED}")
    }

    /**
     * JVMS 5.4.3.2: where a field no longer declared by [start] resolves (its superinterfaces, then
     * its superclasses), as the declaring class; null when it does not, or not as the same kind of
     * (static or instance) field.
     */
    private fun resolveField(start: ClassInfo, field: FieldInfo): String? =
        supertypes(start).firstOrNull { c -> c.fields.any { it.name == field.name && it.desc == field.desc && it.access and ACC_STATIC == field.access and ACC_STATIC } }?.name

    /** JVMS 5.4.3.3/5.4.3.4: superclasses first, then non-static, non-private superinterface methods. */
    private fun resolveMethod(start: ClassInfo, method: MethodInfo): String? {
        if (method.name == "<init>" || method.name == "<clinit>") return null // not inherited
        val static = method.access and ACC_STATIC
        var c: ClassInfo? = start.superName?.let(resolver::find)
        while (c != null) {
            val found = c.method(method.name, method.desc)
            if (found != null && found.access and ACC_PRIVATE == 0) return c.name.takeIf { found.access and ACC_STATIC == static }
            c = c.superName?.let(resolver::find)
        }
        if (static != 0) return null
        return supertypes(start).firstOrNull { i ->
            i.access and ACC_INTERFACE != 0 && i.method(method.name, method.desc)?.let { it.access and (ACC_STATIC or ACC_PRIVATE) == 0 } == true
        }?.name
    }

    /** Every resolvable proper supertype of [start] (superclasses and all superinterfaces). */
    private fun supertypes(start: ClassInfo): List<ClassInfo> {
        val seen = LinkedHashMap<String, ClassInfo>()
        val queue = ArrayDeque<String>().apply { start.superName?.let(::add); addAll(start.interfaces) }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in seen) continue
            val info = resolver.find(name) ?: continue
            seen[name] = info
            info.superName?.let(queue::add)
            queue.addAll(info.interfaces)
        }
        return seen.values.toList()
    }

    companion object {
        private val DEPRECATED = setOf("+Ljava/lang/Deprecated;")
        private val ANONYMOUS = Regex("\\$[0-9]+($|\\$)")

        /** A class Signature attribute with its superinterfaces sorted (their order carries no meaning). */
        fun normalizeClassSignature(signature: String?): String? {
            if (signature == null) return null
            var i = 0
            if (signature.startsWith("<")) {
                var depth = 0
                do {
                    when (signature[i]) {
                        '<' -> depth++
                        '>' -> depth--
                    }
                    i++
                } while (depth > 0)
            }
            val prefix = signature.substring(0, i)
            val types = mutableListOf<String>()
            while (i < signature.length) {
                val start = i
                var depth = 0
                while (true) {
                    val c = signature[i++]
                    if (c == '<') depth++
                    if (c == '>') depth--
                    if (c == ';' && depth == 0) break
                }
                types += signature.substring(start, i)
            }
            return prefix + types.first() + types.drop(1).sorted().joinToString("")
        }

        fun isApi(access: Int) = access and (ACC_PUBLIC or ACC_PROTECTED) != 0

        private fun literal(value: Any?) = if (value is String) javaStringLiteral(value) else value.toString()

        private fun flags(access: Int): String =
            listOfNotNull(
                "public".takeIf { access and ACC_PUBLIC != 0 },
                "protected".takeIf { access and ACC_PROTECTED != 0 },
                "private".takeIf { access and ACC_PRIVATE != 0 },
                "static".takeIf { access and ACC_STATIC != 0 },
                "final".takeIf { access and ACC_FINAL != 0 },
                "abstract".takeIf { access and ACC_ABSTRACT != 0 },
                "interface".takeIf { access and ACC_INTERFACE != 0 },
                "annotation".takeIf { access and ACC_ANNOTATION != 0 },
                "enum".takeIf { access and ACC_ENUM != 0 },
                "volatile/bridge".takeIf { access and ACC_VOLATILE != 0 },
                "transient/varargs".takeIf { access and ACC_TRANSIENT != 0 },
            ).joinToString(" ", "[", "]")

        fun summary(entries: List<Entry>, oldClasses: Int, newClasses: Int): String =
            buildString {
                append("old: $oldClasses classes, new: $newClasses classes\n")
                for (kind in Kind.entries) {
                    val count = entries.count { it.kind == kind }
                    val classes = entries.filter { it.kind == kind }.map { it.className }.distinct().size
                    append("  %-20s %6d in %4d classes  %s%s\n".format(kind.name, count, classes, kind.description, if (kind.breaking) "  [breaking]" else ""))
                }
            }
    }
}

/**
 * `ApiDiff --old old.jar --new new.jar [--classpath a.jar:classes/] [--report report.txt]
 *  [--allow accepted.txt] [--fail-on-breaking]`
 *
 * --classpath adds the classes that replace filtered-out stubs (AndroidCompat), used only to
 * resolve inherited members. --allow lists accepted breaking entries, one report line each.
 */
fun main(args: Array<String>) {
    var oldJar: Path? = null
    var newJar: Path? = null
    var report: Path? = null
    var allow: Path? = null
    var fail = false
    val classpath = mutableListOf<Path>()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--old" -> oldJar = Path(it.next())
            "--new" -> newJar = Path(it.next())
            "--classpath" -> it.next().split(File.pathSeparator).filter(String::isNotBlank).mapTo(classpath) { p -> Path(p) }
            "--report" -> report = Path(it.next())
            "--allow" -> allow = Path(it.next())
            "--fail-on-breaking" -> fail = true
            else -> {
                System.err.println("unknown argument $arg")
                exitProcess(2)
            }
        }
    }
    requireNotNull(oldJar) { "--old is required" }
    requireNotNull(newJar) { "--new is required" }
    val old = readClasses(oldJar)
    val new = readClasses(newJar)
    val resolver = ClassResolver(listOf(new) + classpath.filter { Files.exists(it) }.map(::readClasses))
    val entries = ApiDiff(old, new, resolver).compare()
    val summary = ApiDiff.summary(entries, old.size, new.size)
    println(summary)
    report?.let { path ->
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, summary + "\n" + entries.joinToString("\n", postfix = "\n"))
        println("report: $path")
    }
    val accepted = allow?.let { path -> Files.readAllLines(path).map(String::trim).filter { line -> line.isNotEmpty() && !line.startsWith("#") }.toSet() }.orEmpty()
    val breaking = entries.filter { it.kind.breaking && it.toString() !in accepted }
    breaking.take(200).forEach { println("BREAKING $it") }
    if (fail && breaking.isNotEmpty()) {
        System.err.println("${breaking.size} breaking differences (not in the allow list)")
        exitProcess(1)
    }
}
