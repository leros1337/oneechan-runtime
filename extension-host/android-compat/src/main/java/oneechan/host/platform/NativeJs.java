package oneechan.host.platform;

/**
 * JNI entry points of the app's JavaScriptCore backend. The app's native shim (OneechanJVM,
 * oneechan_js.c) registers them with RegisterNatives before the host starts; in any other JVM they
 * are unbound and {@link JavaScriptCoreJsEngineFactory} reports itself unavailable.
 *
 * A context handle is a native pointer owned by {@link JavaScriptCoreJsEngine}. Scripts run on a
 * native worker thread with a large stack while the calling thread waits (and serves calls to bound
 * host objects), so the depth scripts can reach does not depend on the JVM's thread stack size.
 */
final class NativeJs {
    private NativeJs() {}

    /** The native API version (also the availability probe). */
    static native int version();

    /** A new context in its own context group, or 0. */
    static native long create();

    static native void release(long context);

    /**
     * Evaluates a script; returns the completion value converted by {@link JsValues} rules. Throws
     * QuickJsException for a JS error, or the exception of a bound host method that escaped.
     * {@code engine} receives the host object calls ({@link #invokeHost}).
     */
    static native Object evaluate(long context, String script, String fileName, Object engine);

    /** Parses a script without running it; QuickJsException on a syntax error. */
    static native void compile(long context, String script, String fileName);

    /** Defines the global {@code name} with {@code methods} calling host object {@code id}; false if the global exists. */
    static native boolean bind(long context, String name, int id, String[] methods);

    /** Contexts created and not yet released (diagnostics). */
    static native int liveContexts();

    /** Native stack sizes in bytes: the calling thread's and the script worker's (diagnostics). */
    static native long[] stackSizes();

    /** Called by the native shim on the evaluating thread when a script calls a bound host method. */
    static Object invokeHost(Object engine, int id, int method, Object[] args) throws Throwable {
        return ((JavaScriptCoreJsEngine) engine).invokeHost(id, method, args);
    }
}
