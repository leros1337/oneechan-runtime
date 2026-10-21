package oneechan.host

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.decodeFromJsonElement
import oneechan.host.rpc.ExtProgressEvent
import oneechan.host.rpc.InstallPhase
import oneechan.host.rpc.JobDto
import oneechan.host.rpc.RpcEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One extension host per test JVM (the Android compat runtime is process-global). */
object TestHost {
    val root: Path = Files.createTempDirectory("oneechan-host-test")
    val events = LinkedBlockingQueue<RpcEvent>()

    val host: ExtensionHost by lazy {
        ExtensionHost(
            HostPaths(root.resolve("data"), root.resolve("cache"), root.resolve("tmp")),
        ) { events.add(it) }
    }

    /** Waits for the terminal progress event of [job]. */
    fun awaitJob(
        job: JobDto,
        timeoutSeconds: Long = 300,
    ): ExtProgressEvent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val event = events.poll(1, TimeUnit.SECONDS) ?: continue
            if (event.event != "ext.progress") continue
            val progress = host.json.decodeFromJsonElement<ExtProgressEvent>(event.data)
            if (progress.jobId == job.jobId && progress.phase in setOf(InstallPhase.DONE, InstallPhase.FAILED)) return progress
        }
        error("Job ${job.jobId} did not finish within ${timeoutSeconds}s")
    }

    fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(600_000) { block() } }
}
