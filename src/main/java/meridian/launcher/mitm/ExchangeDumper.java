package meridian.launcher.mitm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link ExchangeHandler} that records each decrypted request/response to a file and
 * passes the response through unchanged. For understanding how the game talks to a backend
 * (e.g. what it sends to sessions.hytale.com when it needs a token) without altering it.
 */
public final class ExchangeDumper implements ExchangeHandler {

    private static final Logger log = LoggerFactory.getLogger(ExchangeDumper.class);

    private final Path dir;
    private final AtomicInteger seq = new AtomicInteger();

    public ExchangeDumper(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("Could not create dump dir {}: {}", dir, e.toString());
        }
    }

    @Override
    public byte[] handle(HttpExchange ex) {
        int n = seq.incrementAndGet();
        String safePath = ex.path().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safePath.length() > 60) safePath = safePath.substring(0, 60);
        Path file = dir.resolve(String.format("%03d_%s_%s_%d.txt",
                n, ex.method(), safePath, ex.status()));

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# ").append(ex.method()).append(' ').append(ex.host()).append(ex.path()).append('\n');
        sb.append("\n--- REQUEST HEADERS ---\n");
        writeHeaders(sb, ex.requestHeaders());
        if (ex.requestBody() != null && ex.requestBody().length > 0) {
            sb.append("\n--- REQUEST BODY ---\n");
            sb.append(new String(ex.requestBody(), StandardCharsets.UTF_8));
        }
        sb.append("\n\n--- RESPONSE ").append(ex.status()).append(" ---\n");
        writeHeaders(sb, ex.responseHeaders());
        sb.append("\n--- RESPONSE BODY ---\n");
        sb.append(new String(ex.responseBody(), StandardCharsets.UTF_8));

        try {
            Files.writeString(file, sb.toString());
        } catch (Exception e) {
            log.warn("Could not write dump {}: {}", file, e.toString());
        }
        log.info("dumped {} {}{} -> {} ({} bytes) -> {}",
                ex.method(), ex.host(), ex.path(), ex.status(),
                ex.responseBody().length, file.getFileName());
        return ex.responseBody();   // pass through unchanged
    }

    private static void writeHeaders(StringBuilder sb, Map<String, List<String>> headers) {
        if (headers == null) return;
        headers.forEach((k, vs) -> {
            if (k == null) return;
            for (String v : vs) sb.append(k).append(": ").append(v).append('\n');
        });
    }
}
