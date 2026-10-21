package oneechan.host.conformance

import eu.kanade.tachiyomi.source.ConfigurableSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import oneechan.host.ExtensionHost
import oneechan.host.HostPaths
import oneechan.host.rpc.ExtInstallParams
import oneechan.host.rpc.ExtProgressEvent
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.RpcEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Installs every extension of a repo (default keiyoushi), instantiates every source, builds filter
 * lists and preference screens, and statically resolves every class/method/field the extension
 * references outside its own jar. Run: `./gradlew :host-core:test -Pbulk --tests '*BulkScan*'`
 * (optional `-PbulkRepo=<url> -PbulkLimit=N -PbulkApk=true`). Report: build/reports/bulk-scan.
 */
@Tag("bulk")
class BulkScanTest {
    @Serializable
    data class ExtensionResult(
        val pkgName: String,
        val libVersion: String,
        val installed: Boolean,
        val installMillis: Long,
        val error: String? = null,
        val sources: Int = 0,
        val sourceFailures: List<String> = emptyList(),
        val unresolved: List<String> = emptyList(),
    )

    @Serializable
    data class Report(
        val repo: String,
        val extensions: Int,
        val installed: Int,
        val sources: Int,
        val sourcesOk: Int,
        val modules: Map<String, Int>,
        val unresolvedTop: Map<String, Int>,
        val results: List<ExtensionResult>,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun scan() {
        val repo = System.getProperty("oneechan.bulkRepo") ?: "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        val limit = System.getProperty("oneechan.bulkLimit")?.toIntOrNull() ?: Int.MAX_VALUE
        val forceApk = System.getProperty("oneechan.bulkApk") == "true"
        val root = Files.createTempDirectory("oneechan-bulk")
        val events = LinkedBlockingQueue<RpcEvent>()
        val host = ExtensionHost(HostPaths(root.resolve("d"), root.resolve("c"), root.resolve("t"))) { events.add(it) }

        runBlocking { host.repoAdd(repo) }
        val offers = host.repos.available().groupBy { it.pkgName }.map { (_, v) -> v.maxBy { it.versionCode } }.sortedBy { it.pkgName }.take(limit)
        val modules = HashMap<String, Int>()
        val unresolvedCounts = HashMap<String, Int>()
        val results = mutableListOf<ExtensionResult>()

        offers.forEachIndexed { i, offer ->
            val started = System.currentTimeMillis()
            val job = host.extInstall(ExtInstallParams(offer.pkgName, offer.repoIndexUrl, forceApk = forceApk))
            val done = await(host, events, job.jobId)
            val millis = System.currentTimeMillis() - started
            if (done.phase != InstallPhase.DONE) {
                results += ExtensionResult(offer.pkgName, offer.libVersion, false, millis, done.error?.let { "${it.code}: ${it.message}" })
                println("[${i + 1}/${offers.size}] FAIL ${offer.pkgName}: ${done.error?.message?.take(200)}")
                return@forEachIndexed
            }
            val record = host.installed.get(offer.pkgName)!!
            val sourceFailures = mutableListOf<String>()
            for (src in record.sources) {
                try {
                    val source = runBlocking { host.registry.get(src.id) }
                    runCatching { source.getFilterList() }.onFailure { sourceFailures += "${src.name}: filters: ${it.describe()}" }
                    if (source is ConfigurableSource) {
                        runCatching { runBlocking { host.preferences(src.id.toString()) } }.onFailure { sourceFailures += "${src.name}: prefs: ${it.describe()}" }
                    }
                } catch (e: Throwable) {
                    sourceFailures += "${src.name}: instantiate: ${e.describe()}"
                }
            }
            val unresolved = linkCheck(host.registry.jarPath(record).absolutePathString(), modules)
            unresolved.forEach { unresolvedCounts.merge(it, 1, Int::plus) }
            results += ExtensionResult(offer.pkgName, offer.libVersion, true, millis, null, record.sources.size, sourceFailures, unresolved)
            if (sourceFailures.isNotEmpty() || unresolved.isNotEmpty()) {
                println("[${i + 1}/${offers.size}] WARN ${offer.pkgName}: ${sourceFailures.take(2)} unresolved=${unresolved.take(3)}")
            } else if ((i + 1) % 50 == 0) {
                println("[${i + 1}/${offers.size}] ok")
            }
        }

        val sources = results.sumOf { it.sources }
        val report =
            Report(
                repo = repo,
                extensions = results.size,
                installed = results.count { it.installed },
                sources = sources,
                sourcesOk = sources - results.sumOf { it.sourceFailures.size },
                modules = modules.toList().sortedByDescending { it.second }.toMap(),
                unresolvedTop = unresolvedCounts.toList().sortedByDescending { it.second }.take(100).toMap(),
                results = results,
            )
        val out = Path(System.getProperty("oneechan.reportDir") ?: "build/reports/bulk-scan").createDirectories()
        out.resolve("report.json").writeText(json.encodeToString(Report.serializer(), report))
        println(
            "BULK SUMMARY extensions=${report.extensions} installed=${report.installed} sources=${report.sources} " +
                "sourcesOk=${report.sourcesOk} withUnresolved=${results.count { it.unresolved.isNotEmpty() }}",
        )
        println("MODULES ${report.modules}")
        println("UNRESOLVED TOP ${report.unresolvedTop.entries.take(30)}")
    }

    private fun await(
        host: ExtensionHost,
        events: LinkedBlockingQueue<RpcEvent>,
        jobId: String,
    ): ExtProgressEvent {
        while (true) {
            val event = events.poll(5, TimeUnit.MINUTES) ?: error("timeout waiting for $jobId")
            if (event.event != "ext.progress") continue
            val progress = host.json.decodeFromJsonElement(ExtProgressEvent.serializer(), event.data)
            if (progress.jobId == jobId && progress.phase in setOf(InstallPhase.DONE, InstallPhase.FAILED)) return progress
        }
    }

    /** Resolves every external reference of the jar through its own class loader. */
    private fun linkCheck(
        jarPath: String,
        modules: MutableMap<String, Int>,
    ): List<String> {
        val loader = PackageTools.jarLoaderMap[jarPath] ?: return listOf("<no class loader>")
        val own = HashSet<String>()
        val nodes = mutableListOf<ClassNode>()
        ZipFile(jarPath).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                val node = ClassNode()
                ClassReader(zip.getInputStream(entry).readBytes()).accept(node, ClassReader.SKIP_FRAMES)
                own += node.name
                nodes += node
            }
        }
        val missing = sortedSetOf<String>()
        val usedModules = HashSet<String>()

        fun isStub(cls: Class<*>): Boolean =
            runCatching { cls.protectionDomain?.codeSource?.location?.path?.endsWith("android-stubs-filtered.jar") == true }.getOrDefault(false)

        fun classOf(internal: String): Class<*>? {
            val type = if (internal.startsWith("[")) Type.getType(internal).elementType else Type.getObjectType(internal)
            if (type.sort != Type.OBJECT) return null
            val name = type.internalName
            if (name in own) return null
            return try {
                Class.forName(name.replace('/', '.'), false, loader).also { cls ->
                    cls.module.name?.let(usedModules::add)
                    // Every method of a class that only exists in android.jar throws "Stub!".
                    if (isStub(cls)) missing += "stub ${cls.name}"
                }
            } catch (_: Throwable) {
                missing += "class ${name.replace('/', '.')}"
                null
            }
        }

        fun hasMethod(cls: Class<*>, name: String, desc: String): Boolean {
            val queue = ArrayDeque<Class<*>>().apply { add(cls) }
            val seen = HashSet<Class<*>>()
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (!seen.add(c)) continue
                val found =
                    runCatching {
                        if (name == "<init>") {
                            c.declaredConstructors.any { Type.getConstructorDescriptor(it) == desc }
                        } else {
                            c.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc }
                        }
                    }.getOrDefault(true)
                if (found) return true
                if (name == "<init>") return false
                c.superclass?.let(queue::add)
                queue.addAll(c.interfaces)
            }
            // Signature-polymorphic and other VM intrinsics.
            return cls.name.startsWith("java.lang.invoke.")
        }

        fun hasField(cls: Class<*>, name: String): Boolean {
            var c: Class<*>? = cls
            while (c != null) {
                if (runCatching { c.declaredFields.any { it.name == name } }.getOrDefault(true)) return true
                if (c.interfaces.any { i -> runCatching { i.fields.any { it.name == name } }.getOrDefault(false) }) return true
                c = c.superclass
            }
            return false
        }

        for (node in nodes) {
            node.superName?.let(::classOf)
            node.interfaces.forEach { classOf(it) }
            for (method in node.methods) {
                for (insn in method.instructions) {
                    when (insn) {
                        is MethodInsnNode -> classOf(insn.owner)?.let { if (!hasMethod(it, insn.name, insn.desc)) missing += "method ${it.name}.${insn.name}${insn.desc}" }
                        is FieldInsnNode -> classOf(insn.owner)?.let { if (!hasField(it, insn.name)) missing += "field ${it.name}.${insn.name}" }
                        is TypeInsnNode -> classOf(insn.desc)
                        is MultiANewArrayInsnNode -> classOf(insn.desc)
                        is LdcInsnNode -> (insn.cst as? Type)?.takeIf { it.sort == Type.OBJECT || it.sort == Type.ARRAY }?.let { classOf(it.descriptor.removePrefix("L").removeSuffix(";")) }
                        is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>().forEach { h -> classOf(h.owner)?.let { if (!hasMethod(it, h.name, h.desc)) missing += "method ${it.name}.${h.name}${h.desc}" } }
                    }
                }
            }
        }
        usedModules.forEach { modules.merge(it, 1, Int::plus) }
        return missing.toList()
    }

    private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message?.take(160)}"
}
