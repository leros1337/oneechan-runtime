package oneechan.host

import android.os.Looper
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule
import xyz.nulldev.ts.config.configManagerModule

/**
 * One-time process bootstrap of the Android compatibility layer (what Suwayomi's
 * `applicationSetup()` does, minus the server): main Looper, Koin/Injekt graph, AndroidCompat.
 */
object HostRuntime {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var bootedRoot: String? = null

    @Synchronized
    fun ensureBooted(paths: HostPaths) {
        val root = paths.compatRoot.toAbsolutePath().toString()
        bootedRoot?.let {
            check(it == root) { "Extension host already booted with compat root $it" }
            return
        }
        paths.ensure()
        verifyPatchedLibraries()
        // Must be set before AndroidCompat's config is first read (ApplicationRootDir).
        System.setProperty("suwayomi.tachidesk.config.server.rootDir", root)

        startMainLooper()

        val app = App()
        if (GlobalContext.getOrNull() == null) {
            startKoin { modules(createAppModule(app), androidCompatModule(), configManagerModule()) }
        }
        AndroidCompatInitializer().init()
        AndroidCompat().startApp(app)

        bootedRoot = root
        logger.info { "Extension host runtime booted (compat root $root)" }
    }

    /** Fails fast if a library class we patch is shadowed by its unpatched original. */
    private fun verifyPatchedLibraries() {
        val patched =
            runCatching {
                Class.forName("com.googlecode.dex2jar.ir.ts.NewTransformer").getField("ONEECHAN_PATCHED").getBoolean(null)
            }.getOrDefault(false)
        check(patched) { "Unpatched dex2jar NewTransformer on the classpath: R8-optimized extensions would load incorrectly" }
        oneechan.host.net.NativeNetInterceptor.verifyOkHttpLayout()
    }

    private fun startMainLooper() {
        if (Looper.getMainLooper() != null) return
        val ready = java.util.concurrent.CountDownLatch(1)
        Thread({
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            ready.countDown()
            Looper.loop()
        }, "android-main").apply {
            isDaemon = true
            start()
        }
        ready.await()
    }
}
