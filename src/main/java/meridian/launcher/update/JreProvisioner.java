package meridian.launcher.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions a Java runtime for a game install. The Hytale game bundle ships no JRE, so a from-
 * scratch channel install needs one supplied. Preferred source: copy the exact runtime another
 * installed channel already uses (confirmed against the official launcher, which ships Temurin
 * 26.0.2 from its own redist) — that always matches the game. Only when the Hytale folder holds
 * no runtime at all does it fall back to downloading a Temurin JRE from Adoptium (verified by
 * SHA-256, extracted and normalized to {@code <jreParent>/latest/bin/java}).
 */
public final class JreProvisioner {

    private static final Logger log = LoggerFactory.getLogger(JreProvisioner.class);
    private static final Gson GSON = new Gson();
    /** Hytale builds run on JRE 25 (per the reference launchers' jre.json). */
    private static final int JRE_MAJOR = 25;
    private static final String ADOPTIUM = "https://api.adoptium.net/v3";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Progress surface (mirrors {@link GameUpdater.Listener}). */
    public interface Progress {
        default void phase(String message) {}
        default void bytes(long done, long total) {}
    }

    private record Asset(String url, String sha256, String name) {}

    /**
     * Ensures {@code <jreParent>/latest/bin/java(.exe)} exists, returning the {@code latest} dir.
     * Preferred source: copy the exact runtime the game already uses, from another installed channel
     * under {@code root} — that is the JRE version the current build expects (e.g. Temurin 26.0.2,
     * which the official launcher ships), rather than a possibly-mismatched download. Only when the
     * Hytale folder holds no runtime at all does it fall back to a Temurin JRE from Adoptium.
     */
    public Path provision(Path jreParent, Path root, Progress progress)
            throws IOException, InterruptedException {
        Path latest = jreParent.resolve("latest");
        if (hasJava(latest)) {
            return latest; // already have a runtime
        }
        // Preferred: copy the exact runtime a sibling channel already uses.
        Path sibling = findSiblingJre(root, latest);
        if (sibling != null) {
            progress.phase("Copying Java runtime");
            deleteRecursively(latest);
            copyRecursively(sibling, latest);
            log.info("Copied Java runtime from {} into {}", sibling, latest);
            return latest;
        }
        // Fallback (a Hytale folder with no runtime anywhere): download Temurin from Adoptium.
        InstallEnv.Platform pf = InstallEnv.currentPlatform();
        String os = adoptiumOs(pf.os());
        String arch = adoptiumArch(pf.arch());

        progress.phase("Fetching Java runtime info");
        Asset asset = latestAsset(os, arch);

        Files.createDirectories(jreParent);
        Path archive = jreParent.resolve(asset.name());
        progress.phase("Downloading Java runtime (" + JRE_MAJOR + ")");
        String actual = downloadVerified(asset.url(), archive, progress);
        if (asset.sha256() != null && !asset.sha256().equalsIgnoreCase(actual)) {
            Files.deleteIfExists(archive);
            throw new IOException("JRE checksum mismatch (expected " + asset.sha256() + ", got " + actual + ")");
        }

        progress.phase("Unpacking Java runtime");
        Path staging = jreParent.resolve("jre-extract");
        deleteRecursively(staging);
        Files.createDirectories(staging);
        extract(archive, staging, asset.name());

        Path home = findJavaHome(staging);
        if (home == null) throw new IOException("could not locate a JRE home in " + asset.name());
        deleteRecursively(latest);
        Files.move(home, latest, StandardCopyOption.ATOMIC_MOVE);

        deleteRecursively(staging);
        Files.deleteIfExists(archive);
        log.info("Provisioned Temurin JRE {} into {}", JRE_MAJOR, latest);
        return latest;
    }

    // --- Adoptium metadata ------------------------------------------------------------

    private Asset latestAsset(String os, String arch) throws IOException, InterruptedException {
        String url = ADOPTIUM + "/assets/latest/" + JRE_MAJOR + "/hotspot"
                + "?architecture=" + arch + "&image_type=jre&os=" + os + "&vendor=eclipse";
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "application/json").GET()
                        .timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Adoptium metadata fetch failed (HTTP " + resp.statusCode() + ")");
        }
        JsonArray arr = GSON.fromJson(resp.body(), JsonArray.class);
        if (arr == null || arr.isEmpty()) {
            throw new IOException("no Temurin JRE " + JRE_MAJOR + " for " + os + "/" + arch);
        }
        JsonObject pkg = arr.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
        return new Asset(pkg.get("link").getAsString(),
                pkg.has("checksum") ? pkg.get("checksum").getAsString() : null,
                pkg.get("name").getAsString());
    }

    private String downloadVerified(String url, Path dest, Progress progress)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("JRE download failed (HTTP " + resp.statusCode() + ")");
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
        long done = 0;
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        try (InputStream in = resp.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                sha.update(buf, 0, r);
                done += r;
                progress.bytes(done, total);
            }
        }
        return toHex(sha.digest());
    }

    // --- extraction -------------------------------------------------------------------

    private void extract(Path archive, Path into, String name) throws IOException, InterruptedException {
        if (name.endsWith(".zip")) {
            unzip(archive, into);
        } else {
            // .tar.gz — shell out to the system tar (present on Windows 10+, Linux, macOS)
            Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", into.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) throw new IOException("tar extraction failed for " + name);
        }
    }

    private void unzip(Path zip, Path into) throws IOException {
        try (var zin = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(zip)))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                Path out = into.resolve(e.getName()).normalize();
                if (!out.startsWith(into)) continue; // zip-slip guard
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                        int r;
                        while ((r = zin.read(buf)) >= 0) os.write(buf, 0, r);
                    }
                }
            }
        }
    }

    /** Finds the JRE home: {@code bin/java(.exe)} at root, one level down, or macOS Contents/Home. */
    private Path findJavaHome(Path root) throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(root);
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(candidates::add);
        }
        for (Path c : new ArrayList<>(candidates)) {
            candidates.add(c.resolve("Contents").resolve("Home"));
        }
        for (Path c : candidates) {
            if (Files.exists(c.resolve("bin").resolve("java"))
                    || Files.exists(c.resolve("bin").resolve("java.exe"))) {
                return c;
            }
        }
        return null;
    }

    // --- helpers ----------------------------------------------------------------------

    private static boolean hasJava(Path jreLatest) {
        return Files.exists(jreLatest.resolve("bin").resolve("java"))
                || Files.exists(jreLatest.resolve("bin").resolve("java.exe"));
    }

    /** Another installed channel's {@code package/jre/latest} runtime under root, or null. */
    private static Path findSiblingJre(Path root, Path selfLatest) {
        Path installDir = root.resolve("install");
        if (!Files.isDirectory(installDir)) return null;
        try (Stream<Path> channels = Files.list(installDir)) {
            for (Path ch : (Iterable<Path>) channels::iterator) {
                Path jreLatest = ch.resolve("package").resolve("jre").resolve("latest");
                if (!jreLatest.equals(selfLatest) && hasJava(jreLatest)) {
                    return jreLatest;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String adoptiumOs(String hytaleOs) {
        return switch (hytaleOs) {
            case "windows" -> "windows";
            case "macos" -> "mac";
            default -> "linux";
        };
    }

    private static String adoptiumArch(String hytaleArch) {
        return "arm64".equals(hytaleArch) ? "aarch64" : "x64";
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
