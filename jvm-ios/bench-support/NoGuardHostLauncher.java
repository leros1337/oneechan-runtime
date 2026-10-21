import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Benchmark-only launcher: identical to oneechan.host.desktop.MainKt.main but without the
 * HotSpotDiagnosticMXBean "BytecodeVerificationRemote" guard, which cannot pass on OpenJ9
 * (OpenJ9 has no such VM option; verification is disabled there with -Xverify:none instead).
 * Compiled against the frozen host.jar snapshot by bench.sh (constructor signatures of that build).
 */
public final class NoGuardHostLauncher {
    public static void main(String[] args) throws Exception {
        String data = null; int port = 4568;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data")) data = args[++i];
            else if (args[i].equals("--port")) port = Integer.parseInt(args[++i]);
        }
        Path root = Path.of(data);
        var paths = new oneechan.host.HostPaths(root.resolve("data"), root.resolve("cache"), root.resolve("tmp"));
        var events = new oneechan.host.desktop.EventHub();
        var host = new oneechan.host.ExtensionHost(paths, events);
        var server = new oneechan.host.desktop.RpcHttpServer(host, events, new InetSocketAddress("127.0.0.1", port), null);
        server.start();
        System.out.println("Oneechan extension host (no-guard launcher) listening on 127.0.0.1:" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
