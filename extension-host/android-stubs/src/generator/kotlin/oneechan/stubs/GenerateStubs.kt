package oneechan.stubs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * `GenerateStubs --api current.txt --out dir --include android --include dalvik [--enum-order enum-order.txt] [--strict]`
 *
 * Writes Java stub sources for the API classes of the included packages (and their nested
 * packages). With --strict, any generator warning fails the run.
 */
fun main(args: Array<String>) {
    var api: Path? = null
    var out: Path? = null
    val include = mutableListOf<String>()
    var strict = false
    var enumOrder: Path? = null
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--api" -> api = Path(it.next())
            "--out" -> out = Path(it.next())
            "--include" -> include += it.next()
            "--enum-order" -> enumOrder = Path(it.next())
            "--strict" -> strict = true
            else -> usage("unknown argument $arg")
        }
    }
    if (api == null || out == null || include.isEmpty()) usage("--api, --out and --include are required")

    val parsed = SignatureParser.parse(Files.readString(api), api.fileName.toString())
    if (out.exists()) out.toFile().deleteRecursively()
    Files.createDirectories(out)
    val order = enumOrder?.let { EnumOrder.parse(Files.readString(it)) }.orEmpty()
    val result = JavaStubWriter(parsed, include, enumOrder = order).write(out)
    println("android-stubs: ${result.classes} classes in ${result.files.size} files (${parsed.classes.size} classes in ${api.fileName})")
    result.warnings.forEach { System.err.println("warning: $it") }
    if (strict && result.warnings.isNotEmpty()) exitProcess(1)
}

private fun usage(message: String): Nothing {
    System.err.println("$message\nusage: GenerateStubs --api <signature.txt> --out <dir> --include <package prefix>... [--enum-order <file>] [--strict]")
    exitProcess(2)
}
