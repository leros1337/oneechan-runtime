package oneechan.host.net

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source
import java.io.IOException
import java.lang.reflect.Field
import kotlin.io.path.deleteIfExists

/**
 * First application interceptor of every client derived from `NetworkHelper.client`. When a
 * [NativeNet] is registered it rewrites this call's interceptor list once, so that:
 *  - `ConnectInterceptor` is dropped (no JVM socket/TLS is ever opened),
 *  - `CallServerInterceptor` becomes [NativeCallInterceptor] (the platform performs the request),
 * while everything else is OkHttp's own code: the client and extension application interceptors,
 * `RetryAndFollowUpInterceptor` (redirects with per-hop cookies, auth, retries),
 * `BridgeInterceptor` (cookie jar, default headers), `CacheInterceptor` and extension network
 * interceptors. WebSocket calls are left untouched.
 */
class NativeNetInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val native = NetworkPlatform.nativeNet
        if (native != null && native.isAvailable && !chain.request().isWebSocket()) rewrite(chain)
        return chain.proceed(chain.request())
    }

    private fun rewrite(chain: Interceptor.Chain) {
        @Suppress("UNCHECKED_CAST")
        val interceptors = interceptorsField(chain).get(chain) as? MutableList<Interceptor> ?: return
        synchronized(interceptors) {
            if (interceptors.any { it is NativeCallInterceptor }) return
            interceptors.removeAll { it.javaClass.name == CONNECT_INTERCEPTOR }
            val server = interceptors.indexOfFirst { it.javaClass.name == CALL_SERVER_INTERCEPTOR }
            check(server >= 0) { "OkHttp chain layout changed: no CallServerInterceptor" }
            interceptors[server] = NativeCallInterceptor()
        }
    }

    private fun okhttp3.Request.isWebSocket() = header("Upgrade").equals("websocket", ignoreCase = true)

    companion object {
        private const val CONNECT_INTERCEPTOR = "okhttp3.internal.connection.ConnectInterceptor"
        private const val CALL_SERVER_INTERCEPTOR = "okhttp3.internal.http.CallServerInterceptor"

        @Volatile
        private var field: Field? = null

        private fun interceptorsField(chain: Interceptor.Chain): Field =
            field ?: chain.javaClass.getDeclaredField("interceptors").apply { isAccessible = true }.also { field = it }

        /** Fails fast at boot if this OkHttp build no longer matches what the rewrite expects. */
        fun verifyOkHttpLayout() {
            val chainClass = Class.forName("okhttp3.internal.http.RealInterceptorChain")
            chainClass.getDeclaredField("interceptors")
            Class.forName(CONNECT_INTERCEPTOR)
            Class.forName(CALL_SERVER_INTERCEPTOR)
        }
    }
}

/** Terminal interceptor: hands the request to the platform's HTTP stack. */
class NativeCallInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val native = NetworkPlatform.nativeNet ?: throw IOException("Native network transport is not available")
        val request = chain.request()
        val call = chain.call()
        val body = request.body?.let { Buffer().also(it::writeTo).readByteArray() }
        val headers = request.headers.map { it.first to it.second }.toMutableList()
        request.body?.contentType()?.let { type -> if (headers.none { it.first.equals("Content-Type", true) }) headers += "Content-Type" to type.toString() }
        val sentAt = System.currentTimeMillis()
        val result =
            native.execute(
                NativeRequest(request.url.toString(), request.method, headers, body, chain.readTimeoutMillis().toLong().coerceAtLeast(1000)),
            ) { call.isCanceled() }
        if (call.isCanceled()) {
            result.bodyFile?.deleteIfExists()
            throw IOException("Canceled")
        }

        val responseHeaders = Headers.Builder().apply { result.headers.forEach { (k, v) -> addUnsafeNonAscii(k, v) } }.build()
        val contentType = responseHeaders["Content-Type"]?.toMediaTypeOrNull()
        val responseBody =
            when {
                result.bodyFile != null -> {
                    val file = result.bodyFile
                    val size = file.toFile().length()
                    val source =
                        object : ForwardingSource(file.source()) {
                            override fun close() {
                                super.close()
                                file.deleteIfExists()
                            }
                        }
                    source.buffer().asResponseBody(contentType, size)
                }
                else -> (result.body ?: ByteArray(0)).toResponseBody(contentType)
            }
        return Response
            .Builder()
            .request(request)
            .protocol(result.protocol?.let { runCatching { Protocol.get(it) }.getOrNull() } ?: Protocol.HTTP_1_1)
            .code(result.code)
            .message(result.message)
            .headers(responseHeaders)
            .body(responseBody)
            .sentRequestAtMillis(sentAt)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
    }
}
