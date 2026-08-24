package meridian.launcher.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A stable, machine-bound identifier used to seal the credential store to this machine, so a
 * copied store cannot be decrypted elsewhere. Best-effort and strictly read-only: it reads the
 * OS's own hardware/install id — Windows {@code MachineGuid}, Linux {@code /etc/machine-id},
 * macOS {@code IOPlatformUUID}. Returns {@code null} when none can be read, in which case the
 * cipher falls back to an on-disk {@code auth.key}.
 *
 * <p>The value is prefixed with its source ({@code win:}/{@code linux:}/{@code mac:}) so the
 * derived key never collides across platforms, and cached for the process lifetime.
 */
public final class MachineId {

    private MachineId() {
    }

    private static volatile String cached;
    private static volatile boolean resolved;

    /** The machine id, or {@code null} if this machine exposes none we can read. */
    public static synchronized String get() {
        if (!resolved) {
            cached = resolve();
            resolved = true;
        }
        return cached;
    }

    private static String resolve() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return windows();
            if (os.contains("mac") || os.contains("darwin")) return macos();
            return linux();
        } catch (Exception e) {
            return null;
        }
    }

    /** HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid — stable per Windows install. */
    private static String windows() {
        String out = run("reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
        if (out != null) {
            for (String line : out.split("\\R")) {
                int i = line.indexOf("REG_SZ");
                if (i >= 0) {
                    String v = line.substring(i + "REG_SZ".length()).trim();
                    if (!v.isBlank()) return "win:" + v;
                }
            }
        }
        return null;
    }

    private static String linux() throws Exception {
        for (String p : List.of("/etc/machine-id", "/var/lib/dbus/machine-id")) {
            Path path = Path.of(p);
            if (Files.isReadable(path)) {
                String v = Files.readString(path).trim();
                if (!v.isBlank()) return "linux:" + v;
            }
        }
        return null;
    }

    private static String macos() {
        String out = run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
        if (out != null) {
            for (String line : out.split("\\R")) {
                if (line.contains("IOPlatformUUID")) {
                    int eq = line.indexOf('=');
                    if (eq >= 0) {
                        String v = line.substring(eq + 1).replace('"', ' ').trim();
                        if (!v.isBlank()) return "mac:" + v;
                    }
                }
            }
        }
        return null;
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
