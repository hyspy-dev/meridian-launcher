package meridian.launcher.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Locates the Hytale root folder and lists the installed versions under it.
 *
 * <p>The root is the folder that holds {@code install/}, {@code data/} and
 * {@code patchline.json} — {@code %APPDATA%/Hytale} by default, overridable for a custom
 * install (config, {@code -Dmeridian.hytale=<path>}, or the launcher's folder field).
 */
public final class HytaleRoot {

    private HytaleRoot() {
    }

    /** The Hytale root: the override if it looks like one, else the per-OS default. */
    public static Optional<Path> locate(String override) {
        if (override != null && !override.isBlank()) {
            Path p = asPath(override);
            return p != null && looksLikeRoot(p) ? Optional.of(p) : Optional.empty();
        }
        Path sys = asPath(System.getProperty("meridian.hytale", ""));
        if (sys != null && looksLikeRoot(sys)) return Optional.of(sys);

        Path def = defaultRoot();
        return looksLikeRoot(def) ? Optional.of(def) : Optional.empty();
    }

    /** Versions (install/ subfolders) that have a runnable client — active one first. */
    public static List<String> versions(Path root) {
        List<String> out = new ArrayList<>();
        Path install = root.resolve("install");
        if (!Files.isDirectory(install)) return out;

        String active = activeVersion(root);
        try (var dirs = Files.list(install)) {
            List<String> found = dirs
                    .filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(v -> HytaleInstall.of(root, v).isRunnable())
                    .sorted()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (active != null && found.remove(active)) {
                out.add(active);   // active/default version first
            }
            out.addAll(found);
        } catch (Exception ignored) {
            // fall through to whatever was collected
        }
        return out;
    }

    /**
     * The game's own version string for an install (e.g. {@code 0.6.0-pre.13}), read from
     * {@code install/<version>/env.dat}. Used to auto-label a saved snapshot. Null if it
     * cannot be read.
     */
    public static String gameVersion(Path root, String version) {
        try {
            Path env = root.resolve("install").resolve(version).resolve("env.dat");
            if (!Files.isReadable(env)) return null;
            JsonObject game = JsonParser.parseString(Files.readString(env)).getAsJsonObject()
                    .getAsJsonObject("dependency_versions").getAsJsonObject("game");
            for (var e : game.entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("version")) {
                    return e.getValue().getAsJsonObject().get("version").getAsString();
                }
                return e.getKey();   // the key is itself the version label
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Snapshots an install: copies {@code install/<version>} to {@code install/<label>} so
     * a specific build survives the next update (which overwrites {@code latest} in place).
     * Returns the new version name (the sanitized label).
     *
     * @throws java.nio.file.FileAlreadyExistsException if a version by that label exists
     */
    public static String saveVersion(Path root, String version, String label) throws java.io.IOException {
        String name = sanitize(label);
        Path src = root.resolve("install").resolve(version);
        Path dst = root.resolve("install").resolve(name);
        if (Files.exists(dst)) {
            throw new java.nio.file.FileAlreadyExistsException(dst.toString());
        }
        try (var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return name;
    }

    private static String sanitize(String label) {
        String s = label == null ? "" : label.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
        return s.isBlank() ? "saved" : s;
    }

    /** The active version from patchline.json, or null. */
    public static String activeVersion(Path root) {
        try {
            Path json = root.resolve("patchline.json");
            if (Files.isReadable(json)) {
                JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                return obj.has("patchline") ? obj.get("patchline").getAsString() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path defaultRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            return Path.of(System.getenv().getOrDefault("APPDATA", home + "\\AppData\\Roaming"),
                    "Hytale");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", "Hytale");
        }
        return Path.of(home, ".local", "share", "Hytale");
    }

    /** A folder is a Hytale root if it has an install/ directory. */
    private static boolean looksLikeRoot(Path p) {
        return p != null && Files.isDirectory(p.resolve("install"));
    }

    private static Path asPath(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Path.of(s);
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}
