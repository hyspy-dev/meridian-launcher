package meridian.launcher.capture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local HTTP proxy that records where the client connects, without decrypting anything.
 *
 * <p>This is the first recon instrument for the telemetry/server-list work: it answers the
 * two questions that come before any interception — does the client honour a proxy at all,
 * and which backends does it reach? For HTTPS it sees the {@code CONNECT host:443} line and
 * then blindly tunnels the encrypted bytes; for plain HTTP it sees the full request line.
 * Either way it tallies the hosts and forwards traffic untouched, so the game runs normally
 * while we watch the destinations.
 *
 * <p>Decrypting those flows (to learn what pins and what does not) is the next stage and
 * needs a CA; this stage deliberately does not, so it cannot break anything.
 *
 * <p>It can also <b>refuse</b> connections to a blocklist of hosts — used for telemetry
 * opt-out: the CONNECT to a blocked host is answered with a 502 and no tunnel, so the
 * client's telemetry request simply fails (they are fire-and-forget, so the game is
 * unaffected). Blocking needs no decryption and so is immune to certificate pinning.
 */
public final class CaptureProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CaptureProxy.class);

    private final ServerSocket serverSocket;
    private final Map<String, AtomicLong> hostHits = new ConcurrentHashMap<>();
    private final Set<String> blockedHosts;
    private final Map<String, AtomicLong> blockedHits = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public CaptureProxy(int port) throws IOException {
        this(port, Set.of());
    }

    /** @param blockedHosts exact hostnames to refuse (case-insensitive); may be empty */
    public CaptureProxy(int port, Set<String> blockedHosts) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        this.blockedHosts = Set.copyOf(blockedHosts).stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Starts the accept loop on a background thread and returns immediately. */
    public void start() {
        Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    Thread.startVirtualThread(() -> handle(client));
                } catch (IOException e) {
                    if (running) log.warn("accept failed: {}", e.toString());
                }
            }
        });
        log.info("Capture proxy listening on 127.0.0.1:{}", port());
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            if ("CONNECT".equalsIgnoreCase(method)) {
                tunnelConnect(client, in, target);
            } else {
                forwardPlainHttp(client, in, method, target, requestLine);
            }
        } catch (IOException e) {
            // Client aborts and half-open tunnels are normal; not worth logging each one.
        }
    }

    /** HTTPS: record the host, open a blind byte tunnel, never look inside. */
    private void tunnelConnect(Socket client, InputStream clientIn, String hostPort) throws IOException {
        record(hostPort);
        drainHeaders(clientIn);

        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        int port = hostPort.contains(":")
                ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 443;

        if (blockedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            blockedHits.computeIfAbsent(host, k -> new AtomicLong()).incrementAndGet();
            log.info("✗ blocked {}", host);
            client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }

        Socket upstream;
        try {
            upstream = new Socket(host, port);
        } catch (IOException e) {
            client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        try (upstream) {
            client.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            pumpBothWays(client, upstream);
        }
    }

    /** Plain HTTP: record method + URL, then forward the whole exchange verbatim. */
    private void forwardPlainHttp(Socket client, InputStream clientIn, String method, String url,
                                  String requestLine) throws IOException {
        String host = hostOf(url);
        record(host + " (http)");
        log.info("HTTP {} {}", method, url);

        // A blind forward is enough for recon: connect to the host, replay the request line
        // and the rest of the stream, pump the response back.
        int port = 80;
        Socket upstream;
        try {
            upstream = new Socket(host, port);
        } catch (IOException e) {
            return;
        }
        try (upstream) {
            OutputStream up = upstream.getOutputStream();
            up.write((requestLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            pumpBothWays(client, upstream, clientIn);
        }
    }

    private void record(String host) {
        hostHits.computeIfAbsent(host, k -> new AtomicLong()).incrementAndGet();
        log.info("→ {}", host);
    }

    /** Prints the distinct destinations seen, busiest first, marking blocked ones. */
    public void printSummary() {
        System.out.println("\n=== Capture summary — hosts the client reached ===");
        if (hostHits.isEmpty()) {
            System.out.println("(nothing — the client did not route through the proxy)");
        } else {
            hostHits.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(e -> {
                        String bare = e.getKey().contains(":")
                                ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
                        boolean blocked = blockedHosts.contains(bare.toLowerCase(Locale.ROOT));
                        System.out.printf("  %6d  %s%s%n", e.getValue().get(), e.getKey(),
                                blocked ? "   [BLOCKED]" : "");
                    });
        }
        if (!blockedHits.isEmpty()) {
            long total = blockedHits.values().stream().mapToLong(AtomicLong::get).sum();
            System.out.println("Blocked " + total + " connection(s) to " + blockedHits.keySet());
        }
        System.out.println("===================================================");
    }

    public boolean sawAnything() {
        return !hostHits.isEmpty();
    }

    // --- socket plumbing --------------------------------------------------------------

    private static void pumpBothWays(Socket a, Socket b) throws IOException {
        pumpBothWays(a, b, a.getInputStream());
    }

    /** Copies a↔b until either side closes, using {@code aIn} for a's already-opened stream. */
    private static void pumpBothWays(Socket a, Socket b, InputStream aIn) throws IOException {
        Thread t = Thread.startVirtualThread(() -> copy(aIn, outputOf(b)));
        copy(b.getInputStream(), outputOf(a));
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static OutputStream outputOf(Socket s) {
        try {
            return s.getOutputStream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Connection closed by one side; the other copy direction ends too.
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return sb.toString();
    }

    /** Reads and discards the remaining request headers up to the blank line. */
    private static void drainHeaders(InputStream in) throws IOException {
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            // discard
        }
    }

    private static String hostOf(String url) {
        String s = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        String hostPort = slash >= 0 ? s.substring(0, slash) : s;
        return hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
