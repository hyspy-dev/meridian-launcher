package meridian.launcher;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves where the launcher keeps its data (accounts, captured server params, the MITM CA,
 * dumps, settings). To stay self-contained and not litter the user's machine, everything
 * lives in a {@code meridian/} folder <b>next to the jar</b> — delete the jar's folder and
 * nothing is left behind (no home-dir dotfolder, no registry entries).
 *
 * <p>Falls back to {@code ~/.meridian} only when the jar's folder can't be written (e.g. the
 * jar sits in a read-only location), so the launcher still works there.
 */
public final class AppPaths {

    private static final Path BASE = resolveBase();

    private AppPaths() {
    }

    /** The base data directory (created on demand by callers that write into it). */
    public static Path base() {
        return BASE;
    }

    /** A path inside the base directory, e.g. {@code resolve("accounts.json")}. */
    public static Path resolve(String child) {
        return BASE.resolve(child);
    }

    private static Path resolveBase() {
        Path jarDir = jarDir();
        if (jarDir != null) {
            Path candidate = jarDir.resolve("meridian");
            if (isWritable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        // Read-only jar location (or running in an odd context): keep working via the home dir.
        return Path.of(System.getProperty("user.home", "."), ".meridian").toAbsolutePath().normalize();
    }

    /** Directory holding the running jar (or the classes dir when run from an IDE); null if unknown. */
    private static Path jarDir() {
        try {
            URI uri = AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path location = Paths.get(uri);
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (Exception e) {
            return false;
        }
    }
}
