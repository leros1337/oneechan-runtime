package oneechan.host.platform

import android.webkit.WebViewProvider
import java.lang.reflect.Proxy

/** Fallback when no app runs WebViews (desktop host without an app, plain JVM tests): every call fails loudly. */
object UnsupportedWebViewBackend : WebViewBackend {
    override fun createProvider(view: android.webkit.WebView): WebViewProvider =
        Proxy.newProxyInstance(
            WebViewProvider::class.java.classLoader,
            arrayOf(WebViewProvider::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("WebView is not available on this platform (WebViewProvider.${method.name})")
        } as WebViewProvider
}
