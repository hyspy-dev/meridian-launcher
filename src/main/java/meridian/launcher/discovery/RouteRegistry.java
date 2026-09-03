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

    /** First local port handed out; well above ephemeral/OS ranges. */
    public static final int DEFAULT_BASE_PORT = 16000;

    /**
     * The highest port we may hand out.
     *
     * <p>Not a socket limit - a protocol one. A server that redirects a player sends the address
     * to go to as {@code ClientReferral.hostTo}, whose port is a <b>signed</b> 16-bit field, and
     * the proxy has to put its own port there to keep the player routed through it. A port above
     * this ceiling arrives at the client as a negative number and the redirect dies with "port
     * out of range" - the game goes nowhere at all. Real servers never come near it, so nothing
     * else in the game ever noticed.
     */
    public static final int MAX_PORT = 32767;

    /** Ports one instance may hand out before colliding with the next slot's base. */
    private static final int SLOT_SIZE = 512;
    private static final int SLOTS = 32;   // 16000 + 31*512 + 511 = 32383, inside the ceiling

    /**
     * Each concurrent launch gets its own port slot: every Play starts its own proxy process,
     * and two registries starting from the same base would tell both proxies to bind the same
     * UDP ports. Seeded randomly so two separate launcher processes rarely collide either.
     */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_SLOT =
            new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(SLOTS));

    private final RouteSink sink;
    private final Map<String, Integer> assigned = new LinkedHashMap<>();
    private final int basePort;
    private int nextPort;

    public RouteRegistry(int basePort, RouteSink sink) {
        this.basePort = basePort;
        this.nextPort = basePort;
        this.sink = sink;
    }

    /** A registry on its own per-instance port slot — safe for concurrent game launches. */
    public static RouteRegistry create(RouteSink sink) {
        int slot = Math.floorMod(NEXT_SLOT.getAndIncrement(), SLOTS);
        return new RouteRegistry(DEFAULT_BASE_PORT + slot * SLOT_SIZE, sink);
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
        if (nextPort > MAX_PORT) {
            // A session with five hundred servers in it, which has never happened - but handing
            // out a port the redirect cannot carry is worse than reusing one.
            nextPort = basePort;
        }
        int local = nextPort++;
        assigned.put(key, local);
        sink.route(local, host, port);
        return local;
    }
}
