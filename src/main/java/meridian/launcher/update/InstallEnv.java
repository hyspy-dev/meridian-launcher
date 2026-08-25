package meridian.launcher.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads and updates {@code install/<patchline>/env.dat} — the install descriptor the game itself
 * writes. It carries the authoritative <b>build number</b> (an integer that the patch API is keyed
 * on) at {@code dependency_versions.game.<name>.build}, the on-disk asset size, and where the
 * current build's {@code .pwr.sig} lives. After a delta update the game entry's build, asset size,
 * and signature pointer are advanced here so the next update check starts from the right build.
 *
 * <p>The layout is stable across the game/jre/sig dependency groups: each is a one-entry map keyed
 * by the version name, so we address "the game entry" without knowing the name in advance.
 */
public final class InstallEnv {

    private static final Gson GSON = new GsonBuilder().create();

    private InstallEnv() {
    }

    /** The install descriptor path for a patchline. */
    public static Path envFile(Path root, String patchline) {
        return root.resolve("install").resolve(patchline).resolve("env.dat");
    }

    /** {@code package/game/latest} — the directory a patch's container paths are relative to. */
    public static Path gameDir(Path root, String patchline) {
        return root.resolve("install").resolve(patchline)
                .resolve("package").resolve("game").resolve("latest");
    }

    /** {@code package/sig/build-<build>} — where a build's content signature is stored. */
    public static Path sigDir(Path root, String patchline, int build) {
        return root.resolve("install").resolve(patchline)
                .resolve("package").resolve("sig").resolve("build-" + build);
    }

    /** The OS/arch the install was built for (env.dat {@code platform}); Hytale uses windows/amd64. */
    public record Platform(String os, String arch) {}

    public static Platform platform(Path root, String patchline) throws IOException {
        JsonObject env = read(root, patchline);
        JsonObject p = env.getAsJsonObject("platform");
        return new Platform(p.get("os").getAsString(), p.get("arch").getAsString());
    }

    /**
     * This machine's platform in Hytale's terms — for channels that aren't installed yet.
     * The update API spells the Apple platform {@code darwin/arm64} (verified against the live
     * API: {@code macos} returns channels with no builds at all, and its patch set 404s);
     * Apple Silicon only, there is no Intel mac build.
     */
    public static Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String hytaleOs = os.contains("win") ? "windows"
                : (os.contains("mac") || os.contains("darwin")) ? "darwin" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String hytaleArch = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";
        return new Platform(hytaleOs, hytaleArch);
    }

    /** The installed build number for a patchline, from the single game dependency entry. */
    public static int currentBuild(Path root, String patchline) throws IOException {
        return gameEntry(read(root, patchline)).get("build").getAsInt();
    }

    /** The game version name of an install folder (env.dat game entry), or null if unreadable. */
    public static String gameVersion(Path root, String folder) {
        try {
            JsonObject game = firstEntry(read(root, folder), "game");
            return game != null && game.has("version") ? game.get("version").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Advances the install descriptor after an update lands: sets the game entry's {@code build},
     * {@code version}, and {@code file_sizes.assets}, repoints the signature entry, and — crucially —
     * renames the game/sig entry keys to {@code versionName} so the descriptor stays consistent
     * (otherwise the version reads stale while the build advances). {@code versionName} may be null
     * (unknown), in which case only the build/assets/sig move, as before.
     */
    public static void recordUpdate(Path root, String patchline, int newBuild, long assetsSize,
                                    String versionName) throws IOException {
        JsonObject env = read(root, patchline);
        JsonObject deps = env.getAsJsonObject("dependency_versions");
        JsonObject game = gameEntry(env);
        game.addProperty("build", newBuild);
        if (versionName != null) game.addProperty("version", versionName);
        if (game.has("file_sizes") && game.get("file_sizes").isJsonObject()) {
            game.getAsJsonObject("file_sizes").addProperty("assets", assetsSize);
        }
        JsonObject sig = firstEntry(env, "sig");
        if (sig != null) {
            sig.addProperty("build", newBuild);
            sig.addProperty("directory", sigDir(root, patchline, newBuild).toString());
            if (versionName != null) sig.addProperty("version", versionName);
        }
        if (versionName != null && deps != null) {
            rekey(deps, "game", versionName);
            rekey(deps, "sig", versionName);
        }
        Files.writeString(envFile(root, patchline), GSON.toJson(env));
    }

    /** Renames the single entry under {@code deps.<group>} to {@code newKey}, keeping its value. */
    private static void rekey(JsonObject deps, String group, String newKey) {
        if (!deps.has(group)) return;
        JsonObject g = deps.getAsJsonObject(group);
        if (g.keySet().isEmpty()) return;
        String oldKey = g.keySet().iterator().next();
        if (!oldKey.equals(newKey)) {
            JsonObject value = g.getAsJsonObject(oldKey);
            g.remove(oldKey);
            g.add(newKey, value);
        }
    }

    /**
     * Writes a minimal {@code env.dat} for a channel installed from scratch (not by the official
     * launcher), so {@link meridian.launcher.launch.HytaleInstall} and the update check can read
     * it. The JRE entry points at a runtime copied in beside the game. The entry is keyed by the
     * real semantic version name when known ({@code versionName}); otherwise it falls back to a
     * synthetic channel+build label (a from=0 patch itself carries no semantic name).
     */
    public static void writeFresh(Path root, String channel, int build, long assetsSize,
                                  String versionName) throws IOException {
        Platform pf = currentPlatform();
        boolean windows = "windows".equals(pf.os());
        Path pkg = root.resolve("install").resolve(channel).resolve("package");
        String label = versionName != null ? versionName : channel + "-" + build;

        JsonObject env = new JsonObject();
        env.addProperty("version", 2);
        JsonObject platform = new JsonObject();
        platform.addProperty("os", pf.os());
        platform.addProperty("arch", pf.arch());
        env.add("platform", platform);

        // Each platform ships the client in its own shape — macOS puts it inside an app bundle.
        String clientBinary = switch (pf.os()) {
            case "windows" -> "Client\\HytaleClient.exe";
            case "darwin" -> "Client/Hytale.app/Contents/MacOS/HytaleClient";
            default -> "Client/HytaleClient";
        };
        JsonObject deps = new JsonObject();
        deps.add("game", singleEntry(label, entry(label, build, clientBinary,
                pkg.resolve("game").resolve("latest").toString(), assetsSize)));
        deps.add("jre", singleEntry("copied", entry("copied", 0,
                windows ? "bin\\java.exe" : "bin/java",
                pkg.resolve("jre").resolve("latest").toString(), -1)));
        deps.add("sig", singleEntry(label, entry(label, build, "signature.pwr.sig",
                sigDir(root, channel, build).toString(), -1)));
        env.add("dependency_versions", deps);
        env.add("tags", new com.google.gson.JsonArray());

        Path file = envFile(root, channel);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(env));
    }

    private static JsonObject entry(String version, int build, String binary, String directory, long assets) {
        JsonObject e = new JsonObject();
        e.addProperty("version", version);
        if (build > 0) e.addProperty("build", build);
        e.addProperty("binary", binary);
        e.addProperty("directory", directory);
        if (assets >= 0) {
            JsonObject fs = new JsonObject();
            fs.addProperty("assets", assets);
            e.add("file_sizes", fs);
        }
        return e;
    }

    private static JsonObject singleEntry(String key, JsonObject value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    // --- helpers ----------------------------------------------------------------------

    private static JsonObject read(Path root, String patchline) throws IOException {
        return GSON.fromJson(Files.readString(envFile(root, patchline)), JsonObject.class);
    }

    /** The single {@code dependency_versions.game.<name>} object. */
    private static JsonObject gameEntry(JsonObject env) throws IOException {
        JsonObject e = firstEntry(env, "game");
        if (e == null) throw new IOException("env.dat has no game dependency entry");
        return e;
    }

    private static JsonObject firstEntry(JsonObject env, String group) {
        JsonObject deps = env.getAsJsonObject("dependency_versions");
        if (deps == null || !deps.has(group)) return null;
        for (Map.Entry<String, ?> e : deps.getAsJsonObject(group).entrySet()) {
            return deps.getAsJsonObject(group).getAsJsonObject(e.getKey());
        }
        return null;
    }
}
