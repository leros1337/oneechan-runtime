#ifndef JVM_BRIDGE_H
#define JVM_BRIDGE_H
/// Starts the embedded JVM on a background pthread (8 MB stack).
/// bundlePath: the .app directory (contains jre/, spike.jar, optional extra/host.jar)
/// tmpDir:     NSTemporaryDirectory()
/// args:       the app's launch arguments joined by '\n'. Default: run Spike.main.
///             "--host\n<port>\n<data-name>" runs the Oneechan extension host instead
///             (oneechan.host.desktop.MainKt --data <tmpDir>/<data-name> --port <port>).
///             "--nojvm" does not start the JVM (memory baseline).
void spike_start_jvm(const char *bundlePath, const char *tmpDir, const char *args);
#endif
