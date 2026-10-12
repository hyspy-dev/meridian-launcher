package meridian.launcher.launch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * The launcher's control channel to a multiplex proxy it started: line commands written to the
 * proxy's stdin (a plain parent→child pipe — no files, no sockets). The proxy reads them and
 * binds ports / updates its token live.
 *
 * <ul>
 *   <li>{@link #token(String)} → {@code TOKEN <jwt>} — set/replace the player session token.</li>
 *   <li>{@link #route(int, String, int, String)} → {@code ROUTE <localPort> <host> <port>} — bind
 *       a port that relays to that server; with a scope, preceded by
 *       {@code SCOPE <localPort> <name>}, which tells the proxy to load that port's modules from
 *       {@code <name>/modules} instead of the server's own folder. A proxy that predates SCOPE
 *       ignores the line and falls back to the server's folder.</li>
 * </ul>
 */
public final class ProxyControl implements AutoCloseable {

    private final BufferedWriter out;

    public ProxyControl(OutputStream proxyStdin) {
        this.out = new BufferedWriter(new OutputStreamWriter(proxyStdin, StandardCharsets.UTF_8));
    }

    public synchronized void token(String jwt) {
        if (jwt != null && !jwt.isBlank()) {
            send("TOKEN " + jwt);
        }
    }

    public synchronized void route(int localPort, String host, int port, String scope) {
        if (scope != null && !scope.isBlank()) {
            send("SCOPE " + localPort + " " + scope);   // first, so the route is born with it
        }
        send("ROUTE " + localPort + " " + host + " " + port);
    }

    private void send(String line) {
        try {
            out.write(line);
            out.write("\n");
            out.flush();
        } catch (IOException e) {
            // Proxy process gone / pipe closed — nothing we can do; the game launch handles exit.
        }
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
