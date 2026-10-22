package oneechan.stubs

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

// The JVM-visible shape of a class file: what links against it and what reflection sees. Code is
// not read. ASM reports the Deprecated attribute as the pseudo access flag ACC_DEPRECATED.

data class FieldInfo(
    val name: String,
    val desc: String,
    val access: Int,
    val signature: String?,
    /** ConstantValue attribute (inlined by compilers), boxed. */
    val value: Any?,
    val annotations: Set<String>,
)

data class MethodInfo(
    val name: String,
    val desc: String,
    val access: Int,
    val signature: String?,
    val exceptions: List<String>,
    val annotations: Set<String>,
    val hasAnnotationDefault: Boolean,
) {
    val key: String get() = name + desc
}

data class ClassInfo(
    /** Internal name (`android/view/View$OnClickListener`). */
    val name: String,
    /** Class access flags, with the InnerClasses flags (static, protected, private) for nested classes. */
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    val signature: String?,
    val annotations: Set<String>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val majorVersion: Int,
) {
    fun field(name: String): FieldInfo? = fields.firstOrNull { it.name == name }

    fun method(name: String, desc: String): MethodInfo? = methods.firstOrNull { it.name == name && it.desc == desc }
}

/** Annotation descriptors are recorded as `+Ldesc;` (runtime-visible) or `-Ldesc;` (class retention). */
private fun annotationKey(desc: String, visible: Boolean) = (if (visible) "+" else "-") + desc

fun readClassInfo(bytes: ByteArray): ClassInfo {
    val reader = ClassReader(bytes)
    val fields = mutableListOf<FieldInfo>()
    val methods = mutableListOf<MethodInfo>()
    val annotations = linkedSetOf<String>()
    var info: ClassInfo? = null
    var innerAccess: Int? = null
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                info = ClassInfo(name, access, superName, interfaces?.toList().orEmpty(), signature, annotations, fields, methods, version and 0xffff)
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (name == info!!.name) innerAccess = access
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                annotations += annotationKey(descriptor, visible)
                return null
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
                val fieldAnnotations = linkedSetOf<String>()
                fields += FieldInfo(name, descriptor, access, signature, value, fieldAnnotations)
                return object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        fieldAnnotations += annotationKey(descriptor, visible)
                        return null
                    }
                }
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val methodAnnotations = linkedSetOf<String>()
                val index = methods.size
                methods += MethodInfo(name, descriptor, access, signature, exceptions?.toList().orEmpty(), methodAnnotations, false)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        methodAnnotations += annotationKey(descriptor, visible)
                        return null
                    }

                    override fun visitAnnotationDefault(): AnnotationVisitor? {
                        methods[index] = methods[index].copy(hasAnnotationDefault = true)
                        return null
                    }
                }
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    val result = info!!
    // Keep the class's own ACC_SUPER/ACC_DEPRECATED bits; take visibility/static from InnerClasses.
    val access = innerAccess?.let { (result.access and (Opcodes.ACC_SUPER or Opcodes.ACC_DEPRECATED)) or it } ?: result.access
    return result.copy(access = access)
}

/** Every class of a jar or class directory, by internal name. */
fun readClasses(path: Path): Map<String, ClassInfo> {
    val out = sortedMapOf<String, ClassInfo>()
    if (path.isDirectory()) {
        Files.walk(path).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "class" && it.fileName.toString() != "module-info.class" }.forEach { file ->
                val info = readClassInfo(Files.readAllBytes(file))
                check(info.name == file.relativeTo(path).toString().removeSuffix(".class").replace('\\', '/')) { "misplaced class $file" }
                out[info.name] = info
            }
        }
    } else {
        ZipFile(path.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.endsWith(".class") || entry.name.endsWith("module-info.class") || entry.name.startsWith("META-INF/")) continue
                val info = readClassInfo(zip.getInputStream(entry).readBytes())
                out[info.name] = info
            }
        }
    }
    return out
}
