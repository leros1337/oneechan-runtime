import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Exercises the interpreter, invokedynamic/lambdas, threads, the GC and some JDK natives. */
public final class Smoke {
    public static String run() throws Exception {
        StringBuilder out = new StringBuilder();
        // invokedynamic: lambdas, string concatenation, streams.
        String joined = IntStream.rangeClosed(1, 5).mapToObj(i -> "n" + i).collect(Collectors.joining(","));
        out.append("streams=").append(joined);
        out.append(" regex=").append(Pattern.compile("(\\d+)-(\\d+)").matcher("12-34").replaceAll("$2-$1"));
        out.append(" format=").append(String.format("%05.1f", Math.PI));
        // Threads and monitors.
        AtomicLong sum = new AtomicLong();
        CountDownLatch done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            int base = t;
            new Thread(() -> { for (int i = 0; i < 1000; i++) sum.addAndGet(base); done.countDown(); }, "smoke-" + t).start();
        }
        done.await();
        out.append(" threads=").append(sum.get());
        // GC: allocate well over the young generation.
        long kept = 0;
        for (int round = 0; round < 50; round++) {
            List<byte[]> garbage = new ArrayList<>();
            for (int i = 0; i < 100; i++) garbage.add(new byte[16 * 1024]);
            kept += garbage.size();
        }
        out.append(" gc=").append(kept);
        // libzip.
        byte[] data = "hello hello hello hello".getBytes();
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] packed = new byte[100];
        int n = deflater.deflate(packed);
        Inflater inflater = new Inflater();
        inflater.setInput(packed, 0, n);
        byte[] unpacked = new byte[data.length];
        inflater.inflate(unpacked);
        out.append(" zip=").append(new String(unpacked).equals(new String(data)));
        // libnet, libnio.
        out.append(" loopback=").append(java.net.InetAddress.getLoopbackAddress().getHostAddress());
        out.append(" tmp=").append(java.nio.file.Files.isDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))));
        return out.toString();
    }
}
