package oneechan.host.platform

import android.webkit.WebViewProvider
import java.lang.reflect.Proxy

/** Fallback used until a real WebView backend is registered: every call fails loudly. */
object UnsupportedWebViewBackend : WebViewBackend {
    override fun createProvider(view: android.webkit.WebView): WebViewProvider =
        Proxy.newProxyInstance(
            WebViewProvider::class.java.classLoader,
            arrayOf(WebViewProvider::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("WebView is not available on this platform (WebViewProvider.${method.name})")
        } as WebViewProvider
}
