import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** S1 spike payload: runs inside the JVM embedded in the iOS app (plain JDK classes only). */
public final class Spike {
    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("[spike] java.version=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.info") + ")");
        System.out.println("[spike] os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " arch=" + System.getProperty("os.arch") + " java.home=" + System.getProperty("java.home"));
        System.out.println("[spike] cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));

        String url = args.length > 0 ? args[0] : "https://example.com";
        HttpsURLConnection c = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        String cipher = c.getCipherSuite();  // must be read while the connection is open
        String body;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            body = out.toString(StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(body);
        String title = m.find() ? m.group(1).trim() : "<no title>";
        System.out.println("[spike] GET " + url + " -> HTTP " + code + " " + cipher
                + ", " + body.length() + " chars, title=\"" + title + "\"");
        System.out.printf("[spike] total %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        System.out.println("[spike] OK");
    }
}
