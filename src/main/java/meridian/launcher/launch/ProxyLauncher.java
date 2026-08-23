package meridian.launcher.launch;

import meridian.launcher.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the Meridian proxy jar(s) sitting next to the launcher and starts one in multiplex
 * mode, pointed at a routes file the launcher maintains. Keeping the proxy a separate,
 * versioned jar (rather than embedding it) means the user can drop in a newer proxy build and
 * pick it from the dropdown without rebuilding the launcher.
 */
public final class ProxyLauncher {

    private ProxyLauncher() {
    }

    /** Proxy jars in the launcher's folder ({@code *proxy*.jar}, excluding the launcher itself). */
    public static List<Path> findProxyJars() {
        Path dir = AppPaths.launcherDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(ProxyLauncher::looksLikeProxyJar)
                    // Prefer the shaded "-all" build, and newer names last-wins by reverse sort.
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean looksLikeProxyJar(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jar") && n.contains("proxy") && !n.contains("launcher");
    }

    /**
     * Starts {@code proxyJar} in multiplex mode. It binds nothing until the launcher drives it
     * over the returned process's stdin (see {@link ProxyControl}): {@code TOKEN}/{@code ROUTE}
     * commands. Returns the process; the caller owns it (control + kill on game exit). No
     * {@code --no-gui}, so the proxy shows its normal window (same UI as standalone).
     */
    public static Process startMultiplex(Path proxyJar) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExec());
        cmd.add("-jar");
        cmd.add(proxyJar.toString());
        cmd.add("--multiplex");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path parent = proxyJar.getParent();
        if (parent != null) {
            pb.directory(parent.toFile());
        }
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);       // we drive it via stdin
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);   // proxy logs to its own window
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /** The JVM running the launcher — guaranteed present and compatible with the native QUIC lib. */
    private static String javaExec() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String bin = windows ? "java.exe" : "java";
        if (home != null && !home.isBlank()) {
            Path exe = Path.of(home, "bin", bin);
            if (Files.isExecutable(exe)) {
                return exe.toString();
            }
        }
        return bin;   // fall back to PATH
    }
}
