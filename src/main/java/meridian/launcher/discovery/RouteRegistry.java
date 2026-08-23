package meridian.launcher.discovery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Allocates a stable local UDP port for each real server the game is about to see, and emits a
 * {@code ROUTE localPort host port} to the proxy (via {@link RouteSink}) so it binds that port.
 * This is the launcher half of the auto-redirect: {@link ServerDiscoveryRewriter} rewrites each
 * listing to {@code 127.0.0.1:<localPort>}, and the proxy relays that port to the real server.
 *
 * <p>Ports are handed out sequentially from a base and are stable per {@code host:port} within a
 * session, so the same server always maps to the same local port (and is only announced once).
 */
public final class RouteRegistry {

    /** Receives each new {@code localPort → host:port} mapping (wired to the proxy control channel). */
    @FunctionalInterface
    public interface RouteSink {
        void route(int localPort, String host, int port);
    }

    /** First local port handed out; well above ephemeral/OS ranges, below 65535 with headroom. */
    public static final int DEFAULT_BASE_PORT = 16000;

    private final RouteSink sink;
    private final Map<String, Integer> assigned = new LinkedHashMap<>();
    private int nextPort;

    public RouteRegistry(int basePort, RouteSink sink) {
        this.nextPort = basePort;
        this.sink = sink;
    }

    public static RouteRegistry create(RouteSink sink) {
        return new RouteRegistry(DEFAULT_BASE_PORT, sink);
    }

    /**
     * The local port that maps to {@code host:port}, allocating and announcing a new one the
     * first time this server is seen. Idempotent per server within the session.
     */
    public synchronized int localPortFor(String host, int port) {
        String key = host + ":" + port;
        Integer existing = assigned.get(key);
        if (existing != null) {
            return existing;
        }
        int local = nextPort++;
        assigned.put(key, local);
        sink.route(local, host, port);
        return local;
    }
}
