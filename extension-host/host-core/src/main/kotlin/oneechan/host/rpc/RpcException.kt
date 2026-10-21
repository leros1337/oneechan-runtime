package oneechan.host.rpc

import eu.kanade.tachiyomi.network.HttpException
import oneechan.host.net.CloudflareBlockedException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

class RpcException(
    val code: ErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
    val url: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Maps any failure to the wire error model. [inSource] marks failures raised by extension code. */
fun Throwable.toRpcError(
    inSource: Boolean = false,
    sourceId: Long? = null,
): RpcErrorDto {
    val error = unwrap()
    val sid = sourceId?.toString()
    return when (error) {
        is RpcException -> RpcErrorDto(error.code, error.message, error.retryable, error.httpStatus, error.url, sid, error.cause?.trimmedStack())
        is CancellationException -> RpcErrorDto(ErrorCode.CANCELLED, "Cancelled", sourceId = sid)
        is CloudflareBlockedException -> RpcErrorDto(ErrorCode.CLOUDFLARE, error.message ?: "Blocked by Cloudflare", retryable = true, url = error.url, sourceId = sid)
        is HttpException -> RpcErrorDto(ErrorCode.HTTP, error.message ?: "HTTP error ${error.code}", retryable = error.code >= 500 || error.code == 429, httpStatus = error.code, sourceId = sid)
        is SocketTimeoutException, is InterruptedIOException -> RpcErrorDto(ErrorCode.TIMEOUT, error.message ?: "Timed out", retryable = true, sourceId = sid)
        is IOException -> RpcErrorDto(ErrorCode.NETWORK, error.message ?: error.javaClass.simpleName, retryable = true, sourceId = sid)
        else ->
            RpcErrorDto(
                code = if (inSource) ErrorCode.SOURCE_EXCEPTION else ErrorCode.INTERNAL,
                message = error.describe(),
                sourceId = sid,
                stack = error.trimmedStack(),
            )
    }.let { dto -> if (dto.code == ErrorCode.INVALID_EXTENSION && dto.stack == null) dto.copy(stack = error.trimmedStack()) else dto }
}

/** "Type: message" of the innermost cause, which is what explains extension failures. */
fun Throwable.describe(): String {
    var root: Throwable = this
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val text = root.message?.takeIf { it.isNotBlank() }
    return if (root === this && text != null) text else listOfNotNull(root.javaClass.simpleName, text).joinToString(": ")
}

fun Throwable.trimmedStack(maxLines: Int = 40): String = stackTraceToString().lineSequence().take(maxLines).joinToString("\n")

private fun Throwable.unwrap(): Throwable {
    var current: Throwable = this
    while (true) {
        val next =
            when {
                current is InvocationTargetException -> current.targetException
                // UncaughtExceptionInterceptor wraps non-IO failures in IOException(cause).
                current is IOException && current.javaClass == IOException::class.java && current.cause != null &&
                    current.message == current.cause.toString() -> current.cause
                else -> null
            } ?: return current
        current = next
    }
}
