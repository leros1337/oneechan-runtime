package oneechan.stubs

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/** Compiles Java sources in memory (as the stub build does: Java 8 class files); returns bytes by internal name. */
object InMemoryJavac {
    fun compile(sources: Map<String, String>): Map<String, ByteArray> {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val outputs = linkedMapOf<String, ByteArrayOutputStream>()
        val fileManager =
            object : ForwardingJavaFileManager<StandardJavaFileManager>(compiler.getStandardFileManager(diagnostics, null, null)) {
                override fun getJavaFileForOutput(location: JavaFileManager.Location, className: String, kind: JavaFileObject.Kind, sibling: FileObject?): JavaFileObject =
                    object : SimpleJavaFileObject(URI.create("mem:///${className.replace('.', '/')}${kind.extension}"), kind) {
                        override fun openOutputStream(): OutputStream = ByteArrayOutputStream().also { outputs[className.replace('.', '/')] = it }
                    }
            }
        val units =
            sources.map { (path, text) ->
                object : SimpleJavaFileObject(URI.create("mem:///$path"), JavaFileObject.Kind.SOURCE) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
                }
            }
        val options = listOf("-source", "8", "-target", "8", "-Xlint:-options", "-nowarn")
        val ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call()
        check(ok) { "javac failed:\n" + diagnostics.diagnostics.joinToString("\n") + "\n\n" + sources.values.joinToString("\n") }
        return outputs.mapValues { it.value.toByteArray() }
    }

    /** Loads compiled classes (children first) for reflection. */
    class Loader(private val classes: Map<String, ByteArray>) : ClassLoader(InMemoryJavac::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
