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
    private volatile boolean logRequests;
    private volatile java.util.function.Consumer<String> requestLogger;

    /** Routes every host through the full-exchange path (for logging) without altering the response. */
    private static final ExchangeHandler PASS_THROUGH = HttpExchange::responseBody;

    /** Max characters of a body written to the log; longer bodies are truncated with a size note. */
    private static final int BODY_LOG_CAP = 4000;

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

    /**
     * Turns on logging of every decrypted HTTP request line (method + absolute URL) for hosts that
     * would otherwise be blind-tunnelled — i.e. it MITM-terminates <em>all</em> hosts and logs their
     * requests. Only hosts that trust our CA are logged; a pinned host is left failing (as with any
     * interception) and noted. {@code sink}, when non-null, additionally receives each line (e.g. the
     * UI console); lines always go to the log too. Call before {@link #start()}.
     */
    public void setLogRequests(boolean on, java.util.function.Consumer<String> sink) {
        this.logRequests = on;
        this.requestLogger = sink;
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
            } else if (logRequests) {
                // Log-all mode: run every host through the full exchange so we capture the request
                // body and response too, passing the response through unchanged.
                interceptHttp(client, host, port, PASS_THROUGH);
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
     * Logs one full decrypted exchange for the "log all HTTPS requests" mode: the request line, the
     * request body (for POSTs etc.), and the response status + body. Goes to the log and, if set, the
     * extra sink (the UI console + file). Bodies are shown as text for textual content types and
     * truncated past {@link #BODY_LOG_CAP}; binary bodies are summarised by size.
     */
    private void logExchange(HttpExchange ex) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(ex.method()).append(" https://").append(ex.host()).append(ex.path());
        byte[] reqBody = ex.requestBody();
        if (reqBody != null && reqBody.length > 0) {
            sb.append("\n    request body: ").append(bodyToText(reqBody, ex.requestHeaders()));
        }
        sb.append("\n    response ").append(ex.status());
        byte[] respBody = ex.responseBody();
        if (respBody != null && respBody.length > 0) {
            sb.append(": ").append(bodyToText(respBody, ex.responseHeaders()));
        }
        String entry = sb.toString();
        log.info("HTTPS {}", entry);
        java.util.function.Consumer<String> sink = requestLogger;
        if (sink != null) {
            try {
                sink.accept(entry);
            } catch (RuntimeException ignored) {
                // a UI sink must never break the exchange
            }
        }
    }

    /**
     * Renders a body for the log: gunzips gzip bodies (e.g. Sentry envelopes) so they read as text
     * instead of mojibake, shows text as-is (truncated past {@link #BODY_LOG_CAP}), and summarises
     * genuinely binary bodies by size rather than dumping raw bytes.
     */
    private static String bodyToText(byte[] body, Map<String, List<String>> headers) {
        byte[] data = body;
        String note = "";
        if (isGzip(body)) {
            byte[] inflated = gunzip(body);
            if (inflated == null) return "<gzip, " + body.length + " bytes>";
            data = inflated;
            note = "  [gunzipped from " + body.length + " bytes]";
        }
        if (!isMostlyText(data)) {
            String ct = headerValue(headers, "content-type");
            return "<" + data.length + " bytes" + (ct != null ? ", " + ct : "") + ">";
        }
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.length() > BODY_LOG_CAP) {
            s = s.substring(0, BODY_LOG_CAP) + "…(" + data.length + " bytes total)";
        }
        return s + note;
    }

    private static boolean isGzip(byte[] b) {
        return b.length >= 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b;
    }

    /** Inflates a gzip body, capped to guard against a decompression bomb; null on failure. */
    private static byte[] gunzip(byte[] b) {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                     new java.io.ByteArrayInputStream(b));
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total > 2_000_000) break;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a body is (almost) all printable/UTF-8 text rather than binary. */
    private static boolean isMostlyText(byte[] b) {
        int limit = Math.min(b.length, 2048);
        if (limit == 0) return true;
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int c = b[i] & 0xff;
            if (c == 9 || c == 10 || c == 13 || c >= 0x20) printable++;
        }
        return printable * 100 / limit >= 90;
    }

    /** First value of a header by case-insensitive name, or null. */
    private static String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
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
                if (logRequests) logExchange(exchange);
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
