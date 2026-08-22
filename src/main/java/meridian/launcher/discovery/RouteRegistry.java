package meridian.launcher.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Allocates a stable local UDP port for each real server the game is about to see, and writes
 * the {@code port=host:port} routes file that the proxy's multiplex mode watches. This is the
 * launcher half of the auto-redirect: {@link ServerDiscoveryRewriter} rewrites each listing to
 * {@code 127.0.0.1:<localPort>}, and the proxy binds that port and relays to the real server.
 *
 * <p>Ports are handed out sequentially from a base and are stable per {@code host:port} within
 * a session, so the same server always maps to the same local port. The file is truncated when
 * the registry is created, so each launch starts the proxy with only this session's routes.
 */
public final class RouteRegistry {

    /** First local port handed out; well above ephemeral/OS ranges, below 65535 with headroom. */
    public static final int DEFAULT_BASE_PORT = 16000;

    private final Path routesFile;
    private final Map<String, Integer> assigned = new LinkedHashMap<>();
    private int nextPort;

    public RouteRegistry(Path routesFile, int basePort) {
        this.routesFile = routesFile;
        this.nextPort = basePort;
        truncate();
    }

    public static RouteRegistry create(Path routesFile) {
        return new RouteRegistry(routesFile, DEFAULT_BASE_PORT);
    }

    /** The routes file the proxy should be pointed at ({@code --routes <this>}). */
    public Path routesFile() {
        return routesFile;
    }

    /**
     * The local port that maps to {@code host:port}, allocating and persisting a new one the
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
        appendRoute(local, host, port);
        return local;
    }

    private void truncate() {
        try {
            Files.createDirectories(routesFile.getParent());
            Files.writeString(routesFile, "# meridian proxy routes: localPort=host:port\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise routes file " + routesFile, e);
        }
    }

    private void appendRoute(int localPort, String host, int port) {
        try {
            Files.writeString(routesFile, localPort + "=" + host + ":" + port + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append route " + localPort + "=" + host + ":" + port, e);
        }
    }
}
