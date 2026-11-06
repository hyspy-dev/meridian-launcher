package meridian.launcher.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import meridian.launcher.AppPaths;

/**
 * Single-player worlds through the proxy.
 *
 * <p>The client starts a world's local server with whatever {@code --java-exec} names. We name a
 * small script instead; it runs the proxy jar's single-player wrapper (no window), handing it the
 * game's real java and the client's whole command. The wrapper moves the server to a free port,
 * starts it with that java and every flag the client chose, and hands the world to the proxy the
 * launcher already runs: that one listens on the port the client connects to, so the world plays
 * in the same window as the servers - see the proxy's {@code SingleplayerWrap} and
 * {@code SingleplayerControl}. The two find each other through {@link Control}.
 *
 * <p>The install is never touched: no jar is swapped, so game updates do not care. The script
 * reads where things are from the environment the client passes down to it.
 */
public final class SingleplayerShim {

    /** The java that runs the proxy - the launcher's own, known to carry the QUIC native. */
    public static final String ENV_PROXY_JAVA = "MERIDIAN_SP_PROXY_JAVA";
    /** The proxy jar. */
    public static final String ENV_PROXY_JAR = "MERIDIAN_SP_PROXY_JAR";
    /** The game's java, which runs the server - and the script, should the proxy be missing. */
    public static final String ENV_GAME_JAVA = "MERIDIAN_SP_GAME_JAVA";

    /** Where the wrapper reaches the running proxy, {@code 127.0.0.1:<port>}. */
    public static final String ENV_CONTROL = "MERIDIAN_SP_CONTROL";
    /** What it says to be let in - passed to the proxy and the game alike. */
    public static final String ENV_SECRET = "MERIDIAN_SP_SECRET";

    /** The proxy class that marks a jar able to do it. */
    private static final String WRAP_CLASS = "meridian/internal/SingleplayerControl.class";

    /**
     * The running proxy's single-player door: a loopback TCP port it listens on and a secret the
     * wrapper must show. Chosen here, before both are started, and handed to each.
     */
    public record Control(int port, String secret) {

        public static Control create() throws IOException {
            int port;
            try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                port = s.getLocalPort();
            }
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            return new Control(port, java.util.HexFormat.of().formatHex(b));
        }

        /** What the proxy is started with, after {@code --multiplex}. */
        public java.util.List<String> proxyArgs() {
            return java.util.List.of("--sp-control", Integer.toString(port));
        }
    }

    private static final String WINDOWS = String.join("\r\n",
            "@echo off",
            "rem Meridian: single-player worlds through the proxy. Written by the Meridian launcher.",
            "if not defined " + ENV_PROXY_JAR + " goto plain",
            "\"%" + ENV_PROXY_JAVA + "%\" -jar \"%" + ENV_PROXY_JAR + "%\" --singleplayer-wrap --no-gui"
                    + " --real-java \"%" + ENV_GAME_JAVA + "%\" -- %*",
            "exit /b %ERRORLEVEL%",
            ":plain",
            "\"%" + ENV_GAME_JAVA + "%\" %*",
            "exit /b %ERRORLEVEL%",
            "");

    private static final String UNIX = String.join("\n",
            "#!/bin/sh",
            "# Meridian: single-player worlds through the proxy. Written by the Meridian launcher.",
            "if [ -z \"$" + ENV_PROXY_JAR + "\" ]; then exec \"$" + ENV_GAME_JAVA + "\" \"$@\"; fi",
            "exec \"$" + ENV_PROXY_JAVA + "\" -jar \"$" + ENV_PROXY_JAR + "\" --singleplayer-wrap --no-gui"
                    + " --real-java \"$" + ENV_GAME_JAVA + "\" -- \"$@\"",
            "");

    private SingleplayerShim() {
    }

    /** Whether {@code proxyJar} has the single-player mode - older proxies do not. */
    public static boolean supports(Path proxyJar) {
        if (proxyJar == null || !Files.isRegularFile(proxyJar)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(proxyJar.toFile())) {
            return zip.getEntry(WRAP_CLASS) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Writes the script (again - it is tiny, and this keeps it current) into the launcher's data
     * folder and returns its path, for {@code --java-exec}.
     */
    public static Path write() throws IOException {
        boolean windows = isWindows();
        Path dir = AppPaths.resolve("singleplayer");
        Files.createDirectories(dir);
        Path script = dir.resolve(windows ? "meridian-sp-java.cmd" : "meridian-sp-java.sh");
        Files.writeString(script, windows ? WINDOWS : UNIX, StandardCharsets.UTF_8);
        if (!windows) {
            try {
                Files.setPosixFilePermissions(script, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX filesystem; nothing to set
            }
        }
        return script;
    }

    /**
     * What the script and the wrapper read: the proxy jar, the java to run it, the game's java,
     * and the running proxy's door.
     */
    public static Map<String, String> env(Path proxyJar, HytaleInstall install, Control control) {
        Map<String, String> m = new HashMap<>();
        m.put(ENV_PROXY_JAVA, ProxyLauncher.javaExec());
        m.put(ENV_PROXY_JAR, proxyJar.toAbsolutePath().toString());
        m.put(ENV_GAME_JAVA, install.javaExec.toString());
        m.put(ENV_CONTROL, "127.0.0.1:" + control.port());
        m.put(ENV_SECRET, control.secret());
        return m;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
