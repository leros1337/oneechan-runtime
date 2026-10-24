package oneechan.host.platform

import android.util.Log

/**
 * Where android.widget.Toast#show goes besides stdout: host-core sets [sink] once an app is connected
 * and sends each toast to it (the `host.toast` event), which shows it like its own toasts.
 */
object Toasts {
    private const val TAG = "Toasts"

    /** Receives the text and whether it is a LENGTH_LONG toast; null: toasts are only printed. */
    @Volatile
    var sink: ((text: String, long: Boolean) -> Unit)? = null

    /** Called by Toast#show on the extension's thread; a failing sink never fails the extension. */
    @JvmStatic
    fun show(
        text: String,
        long: Boolean,
    ) {
        if (text.isBlank()) return
        val sink = sink ?: return
        try {
            sink(text, long)
        } catch (e: Throwable) {
            Log.w(TAG, "Toast not delivered to the app", e)
        }
    }
}
