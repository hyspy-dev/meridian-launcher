package meridian.launcher.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A resolved Hytale install for one version (channel), derived from the Hytale root folder
 * and a version name. The install tree is:
 *
 * <pre>
 *   &lt;root&gt;/                                    e.g. %APPDATA%/Hytale
 *     install/&lt;version&gt;/package/
 *       game/latest/Client/HytaleClient.exe    ← executable
 *       game/latest                            ← --app-dir
 *       jre/latest/bin/java.exe                ← --java-exec
 *     data/&lt;version&gt;/                            ← --user-dir (per version)
 * </pre>
 *
 * <p>Versions ({@code pre-release}, {@code release}, {@code v0.4}, …) are the subfolders of
 * {@code install/}. The user picks the root once (for a custom install) and the version
 * from a dropdown; everything else is derived here.
 */
public final class HytaleInstall {

    private static final String CLIENT_EXE = "HytaleClient.exe";
    private static final String CLIENT_BIN = "HytaleClient";

    public final Path root;
    public final String version;
    public final Path executable;
    public final Path appDir;
    public final Path userDir;
    public final Path javaExec;

    private HytaleInstall(Path root, String version, Path executable, Path appDir,
                          Path userDir, Path javaExec) {
        this.root = root;
        this.version = version;
        this.executable = executable;
        this.appDir = appDir;
        this.userDir = userDir;
        this.javaExec = javaExec;
    }

    /**
     * Resolves the install for {@code version} under {@code root}.
     *
     * <p>The executable and java-exec <em>names</em> come from {@code env.dat}'s {@code
     * binary} fields ({@code Client/HytaleClient.exe}, {@code bin/java.exe} — platform-
     * specific), so no OS-specific name is hard-coded and Linux/macOS installs resolve
     * the same way. Only the relative binary is taken from env.dat; the directories are
     * derived from the on-disk layout so a saved snapshot (whose env.dat still holds the
     * original absolute paths) resolves against its own folder.
     */
    public static HytaleInstall of(Path root, String version) {
        Path pkg = root.resolve("install").resolve(version).resolve("package");
        Path appDir = pkg.resolve("game").resolve("latest");
        Path jreDir = pkg.resolve("jre").resolve("latest");
        Path userDir = root.resolve("data").resolve(version);

        JsonObject env = readEnv(root.resolve("install").resolve(version).resolve("env.dat"));
        String gameBinary = binaryFor(env, "game", "Client/" + defaultClientName());
        String jreBinary = binaryFor(env, "jre", "bin/" + defaultJavaName());

        Path executable = resolveRelative(appDir, gameBinary);
        Path javaExec = resolveRelative(jreDir, jreBinary);
        return new HytaleInstall(root, version, executable, appDir, userDir, javaExec);
    }

    /** env.dat's {@code dependency_versions.<dep>.<ver>.binary}, or {@code fallback}. */
    private static String binaryFor(JsonObject env, String dep, String fallback) {
        try {
            JsonObject deps = env.getAsJsonObject("dependency_versions").getAsJsonObject(dep);
            for (var e : deps.entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("binary")) {
                    return e.getValue().getAsJsonObject().get("binary").getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static JsonObject readEnv(Path envDat) {
        try {
            if (Files.isReadable(envDat)) {
                return JsonParser.parseString(Files.readString(envDat)).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Resolves a possibly-backslashed relative binary path under {@code base}. */
    private static Path resolveRelative(Path base, String relative) {
        Path p = base;
        for (String seg : relative.replace('\\', '/').split("/")) {
            if (!seg.isBlank()) p = p.resolve(seg);
        }
        return p;
    }

    /**
     * Infers the install from a direct client-executable path (the {@code --client}
     * fallback), recovering the root and version from the known tree shape. Null if the
     * path is not {@code …/install/<version>/package/game/latest/Client/HytaleClient.*}.
     */
    public static HytaleInstall fromExecutable(Path exe) {
        // …/install/<version>/package/game/latest/Client/<exe>
        Path client = parent(exe);           // Client
        Path latest = parent(client);        // game/latest
        Path game = parent(latest);          // game
        Path pkg = parent(game);             // package
        Path versionDir = parent(pkg);       // <version>
        Path install = parent(versionDir);   // install
        Path root = parent(install);         // <root>
        if (root == null || versionDir == null) return null;
        return of(root, versionDir.getFileName().toString());
    }

    public boolean isRunnable() {
        return Files.isExecutable(executable);
    }

    /** The authenticated-launch arguments the client expects, in the launcher's order. */
    public List<String> launchArgs(String uuid, String name) {
        return List.of(
                "--app-dir", appDir.toString(),
                "--user-dir", userDir.toString(),
                "--java-exec", javaExec.toString(),
                "--auth-mode", "authenticated",
                "--uuid", uuid,
                "--name", name);
    }

    /** Fallback client name when env.dat is unreadable (older installs / snapshots). */
    private static String defaultClientName() {
        return isWindows() ? CLIENT_EXE : CLIENT_BIN;
    }

    private static String defaultJavaName() {
        return isWindows() ? "java.exe" : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path parent(Path p) {
        return p == null ? null : p.getParent();
    }
}
