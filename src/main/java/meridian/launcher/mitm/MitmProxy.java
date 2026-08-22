package meridian.launcher.mitm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local HTTP proxy that man-in-the-middles a chosen set of hosts and blind-tunnels the
 * rest. For an intercepted host it answers the {@code CONNECT}, terminates TLS with a leaf
 * certificate minted for that host by {@link CertificateAuthority}, opens its own TLS
 * connection to the real server, and relays the now-decrypted streams between the two.
 *
 * <p>Whether the client completes the TLS handshake with our leaf is the pinning signal:
 * a success means the host is interceptable (the client trusted our CA and did not pin);
 * a handshake failure means it pins or does not trust the CA. Results are recorded per
 * host. The relay is transparent for now — enough to prove interceptability and keep the
 * game working; response rewriting (the community server list) slots into the relay later.
 */
public final class MitmProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MitmProxy.class);

    /** Per-host outcome of the client's TLS handshake against our leaf. */
    public enum Verdict { INTERCEPTED, HANDSHAKE_FAILED }

    private final ServerSocket serverSocket;
    private final CertificateAuthority ca;
    private final Set<String> interceptHosts;
    private final Set<String> blockHosts;
    private final Map<String, ExchangeHandler> httpHandlers;
    private final Map<String, Verdict> verdicts = new ConcurrentHashMap<>();
    private final SSLContext upstreamContext;
    private final java.net.http.HttpClient httpClient;
    private volatile boolean running = true;

    public MitmProxy(int port, CertificateAuthority ca, Set<String> interceptHosts) throws Exception {
        this(port, ca, interceptHosts, Map.of(), Set.of());
    }

    public MitmProxy(int port, CertificateAuthority ca, Set<String> interceptHosts,
                     Map<String, ExchangeHandler> httpHandlers) throws Exception {
        this(port, ca, interceptHosts, httpHandlers, Set.of());
    }

    /**
     * @param interceptHosts hosts to TLS-terminate and blind-relay (the pinning probe)
     * @param httpHandlers   hosts to TLS-terminate and run through a full HTTP exchange, so
     *                       the decrypted request/response can be dumped or rewritten
     * @param blockHosts     hosts to refuse at CONNECT (502), e.g. telemetry — no TLS at all
     */
    public MitmProxy(int port, CertificateAuthority ca, Set<String> interceptHosts,
                     Map<String, ExchangeHandler> httpHandlers, Set<String> blockHosts) throws Exception {
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        this.ca = ca;
        this.interceptHosts = Set.copyOf(interceptHosts).stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.blockHosts = Set.copyOf(blockHosts).stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, ExchangeHandler> lower = new java.util.HashMap<>();
        httpHandlers.forEach((h, fn) -> lower.put(h.toLowerCase(Locale.ROOT), fn));
        this.httpHandlers = Map.copyOf(lower);
        this.upstreamContext = trustAllContext();
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .sslContext(upstreamContext)
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public Map<String, Verdict> verdicts() {
        return verdicts;
    }

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
        log.info("MITM proxy on 127.0.0.1:{}, intercepting {}", port(), interceptHosts);
    }

    private void handle(Socket client) {
        try {
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine.isEmpty()) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                client.close();
                return;
            }
            drainHeaders(in);

            String hostPort = parts[1];
            String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
            int port = hostPort.contains(":")
                    ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 443;

            String key = host.toLowerCase(Locale.ROOT);
            if (blockHosts.contains(key)) {
                // Refuse the tunnel outright (telemetry) — fire-and-forget, the game copes.
                client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                log.info("blocked {}", host);
                closeQuietly(client);
                return;
            }

            client.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();

            if (httpHandlers.containsKey(key)) {
                interceptHttp(client, host, port, httpHandlers.get(key));
            } else if (interceptHosts.contains(key)) {
                intercept(client, host, port);
            } else {
                tunnel(client, host, port);
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /** TLS-terminate with our leaf, connect TLS to the real host, relay the plaintext. */
    private void intercept(Socket client, String host, int port) {
        SSLSocket clientTls = null;
        SSLSocket upstream = null;
        try {
            SSLContext serverCtx = serverContext(host);
            clientTls = (SSLSocket) serverCtx.getSocketFactory()
                    .createSocket(client, host, port, true);
            clientTls.setUseClientMode(false);
            try {
                clientTls.startHandshake();
            } catch (IOException handshakeFail) {
                // The client rejected our leaf — the host pins, or the CA is not trusted.
                verdicts.put(host, Verdict.HANDSHAKE_FAILED);
                log.info("✗ {} — client rejected our certificate (pinned or CA not trusted): {}",
                        host, handshakeFail.getMessage());
                closeQuietly(clientTls);
                return;
            }

            verdicts.putIfAbsent(host, Verdict.INTERCEPTED);

            upstream = (SSLSocket) upstreamContext.getSocketFactory().createSocket(host, port);
            upstream.startHandshake();
            log.info("✓ {} — intercepted (client trusted our certificate)", host);

            // Peek the first decrypted request line as proof we see plaintext, then relay.
            PushbackInputStream clientIn = new PushbackInputStream(clientTls.getInputStream(), 8192);
            String firstLine = peekLine(clientIn);
            if (!firstLine.isEmpty()) {
                log.info("  {} decrypted request: {}", host, firstLine);
            }

            relay(clientIn, clientTls.getOutputStream(),
                    upstream.getInputStream(), upstream.getOutputStream());
        } catch (Exception e) {
            log.warn("intercept {} failed: {}", host, e.toString());
        } finally {
            closeQuietly(upstream);
            closeQuietly(clientTls);
        }
    }

    /**
     * TLS-terminate with our leaf, then run each HTTP request through a real exchange with
     * the upstream (so we get the decrypted, decoded body), hand it to {@code handler}, and
     * write the handler's response back to the client. One request per connection
     * ({@code Connection: close}) to keep framing simple; the client reconnects as needed.
     */
    private void interceptHttp(Socket client, String host, int port, ExchangeHandler handler) {
        try {
            SSLContext serverCtx = serverContext(host);
            SSLSocket clientTls = (SSLSocket) serverCtx.getSocketFactory()
                    .createSocket(client, host, port, true);
            clientTls.setUseClientMode(false);
            try {
                clientTls.startHandshake();
            } catch (IOException handshakeFail) {
                verdicts.put(host, Verdict.HANDSHAKE_FAILED);
                log.info("✗ {} — client rejected our certificate: {}", host, handshakeFail.getMessage());
                closeQuietly(clientTls);
                return;
            }
            verdicts.putIfAbsent(host, Verdict.INTERCEPTED);

            try (clientTls) {
                InputStream in = clientTls.getInputStream();
                OutputStream out = clientTls.getOutputStream();

                String requestLine = readLine(in);
                if (requestLine.isEmpty()) return;
                String[] rl = requestLine.split(" ");
                if (rl.length < 2) return;
                String method = rl[0];
                String path = rl[1];

                Map<String, String> reqHeaders = readHeaders(in);
                byte[] reqBody = readBody(in, reqHeaders);

                HttpExchange exchange = forward(host, port, method, path, reqHeaders, reqBody);
                byte[] responseBody = handler.handle(exchange);
                writeResponse(out, exchange.status(), exchange.responseContentType(), responseBody);
            }
        } catch (Exception e) {
            log.warn("interceptHttp {} failed: {}", host, e.toString());
        }
    }

    /** Performs the real request against the upstream and returns the full exchange. */
    private HttpExchange forward(String host, int port, String method, String path,
                                 Map<String, String> reqHeaders, byte[] reqBody) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://" + host + ":" + port + path))
                .method(method, reqBody != null && reqBody.length > 0
                        ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(reqBody)
                        : java.net.http.HttpRequest.BodyPublishers.noBody());
        // Force identity so the body comes back uncompressed and readable.
        b.header("Accept-Encoding", "identity");
        reqHeaders.forEach((k, v) -> {
            if (isForwardableRequestHeader(k)) {
                try {
                    b.header(k, v);
                } catch (IllegalArgumentException ignored) {
                    // java.net.http restricts some header names; skip those.
                }
            }
        });

        java.net.http.HttpResponse<byte[]> resp = httpClient.send(
                b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        String contentType = resp.headers().firstValue("content-type").orElse(null);
        return new HttpExchange(host, method, path,
                toMultimap(reqHeaders), reqBody,
                resp.statusCode(), resp.headers().map(), contentType, resp.body());
    }

    /** Writes a minimal HTTP/1.1 response with the (possibly rewritten) body. */
    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Non-intercepted host: blind byte tunnel, no decryption. */
    private void tunnel(Socket client, String host, int port) {
        try (Socket upstream = new Socket(host, port)) {
            relay(client.getInputStream(), client.getOutputStream(),
                    upstream.getInputStream(), upstream.getOutputStream());
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /** Copies client↔upstream in both directions until either side closes. */
    private void relay(InputStream cIn, OutputStream cOut, InputStream uIn, OutputStream uOut) {
        Thread up = Thread.startVirtualThread(() -> copy(cIn, uOut));
        copy(uIn, cOut);
        try {
            up.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- TLS contexts -----------------------------------------------------------------

    /** Server-side context serving the leaf minted for {@code host}. */
    private SSLContext serverContext(String host) throws Exception {
        CertificateAuthority.LeafCertificate leaf = ca.leafFor(host);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("leaf", leaf.key(), new char[0], leaf.chain());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Client-side context that trusts any server — we are only relaying. */
    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }

    // --- io helpers -------------------------------------------------------------------

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // one side closed
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

    /** Reads the first line from a pushback stream, then pushes those bytes back. */
    private static String peekLine(PushbackInputStream in) throws IOException {
        byte[] buf = new byte[2048];
        int n = 0;
        int c;
        while (n < buf.length && (c = in.read()) != -1) {
            buf[n++] = (byte) c;
            if (c == '\n') break;
        }
        if (n > 0) in.unread(buf, 0, n);
        String line = new String(buf, 0, n, StandardCharsets.ISO_8859_1).trim();
        return line;
    }

    private static void drainHeaders(InputStream in) throws IOException {
        while (!readLine(in).isEmpty()) {
            // discard
        }
    }

    /** Reads request headers into a case-preserving map (last value wins per name). */
    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** Reads the request body when a Content-Length says there is one. */
    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        int length = 0;
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("content-length")) {
                try {
                    length = Integer.parseInt(e.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
                break;
            }
        }
        if (length <= 0) return new byte[0];
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        return body;
    }

    /** Hop-by-hop and length/encoding headers we must not replay to the upstream. */
    private static boolean isForwardableRequestHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "host", "content-length", "connection", "accept-encoding", "proxy-connection",
                 "transfer-encoding", "te", "upgrade", "keep-alive", "expect" -> false;
            default -> true;
        };
    }

    private static Map<String, List<String>> toMultimap(Map<String, String> headers) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, List.of(v)));
        return out;
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
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
