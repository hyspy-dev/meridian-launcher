package meridian.launcher.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A one-shot loopback HTTP listener for the OAuth2 redirect. Binds an ephemeral port on
 * 127.0.0.1, waits for the single browser request carrying {@code ?code=…&state=…},
 * answers it with a small close-this-tab page, and returns the parsed query.
 *
 * <p>The account backend does not redirect straight here — it redirects to a hytale.com
 * consent page, which forwards to {@code http://127.0.0.1:<port>} using the port carried
 * in the OAuth {@code state}. So the port is chosen here and folded into the state before
 * the authorize URL is built.
 */
public final class LoopbackReceiver implements AutoCloseable {

    private final ServerSocket serverSocket;

    public LoopbackReceiver() throws IOException {
        // Port 0 = let the OS pick a free one; loopback only, never externally reachable.
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * Blocks until the browser hits the redirect (or the timeout elapses), then returns
     * the callback query parameters ({@code code}, {@code state}, or {@code error}).
     */
    public Map<String, String> awaitCallback(Duration timeout) throws IOException {
        serverSocket.setSoTimeout((int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));
        try (Socket socket = serverSocket.accept()) {
            Map<String, String> params = readRequestQuery(socket.getInputStream());
            writeResponse(socket.getOutputStream(), params);
            return params;
        }
    }

    /** Reads just the request line and pulls the query string out of it. */
    private static Map<String, String> readRequestQuery(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(256);
        int c;
        // The request line ends at the first CR/LF; that is all we need.
        while ((c = in.read()) != -1 && c != '\r' && c != '\n') {
            line.append((char) c);
            if (line.length() > 8192) break;   // defensive cap on a hostile client
        }
        String requestLine = line.toString();
        int q = requestLine.indexOf('?');
        int sp = requestLine.indexOf(' ', Math.max(q, 0));
        if (q < 0 || sp < 0 || sp <= q) return Map.of();
        return parseQuery(requestLine.substring(q + 1, sp));
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private static void writeResponse(OutputStream out, Map<String, String> params) throws IOException {
        boolean ok = params.containsKey("code") && !params.containsKey("error");
        String title = ok ? "Authentication successful" : "Authentication failed";
        String detail = ok
                ? "You can close this tab and return to Meridian."
                : "Meridian could not complete sign-in: "
                        + params.getOrDefault("error_description", params.getOrDefault("error", "unknown error"))
                        + ". Close this tab and try again.";
        String body = "<!doctype html><meta charset=utf-8><title>" + title + "</title>"
                + "<body style=\"font-family:sans-serif;background:#141414;color:#ddd;padding:3rem\">"
                + "<h1>" + title + "</h1><p>" + detail + "</p>"
                + (ok ? "<script>window.close()</script>" : "") + "</body>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + (ok ? "200 OK" : "400 Bad Request") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
