package meridian.launcher.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * The launcher's source of everything installable: a single static {@code catalog.json}, generated
 * by CI in {@code hyspy-dev/meridian} and deployed to GitHub Pages (never committed to git).
 * Because it is a plain CDN file (not the GitHub API) there is <b>no rate limit</b> — one fetch
 * yields the modules (with all versions), the proxy builds (each tagged with its protocol CRC),
 * the launcher builds, and the game→CRC map. Fetched live and cached for the session; installing
 * anything needs the network anyway.
 *
 * <p>Dev override: {@code -Dmeridian.catalog.url=…} or env {@code MERIDIAN_CATALOG_URL}, either an
 * http(s) URL or a {@code file:} URL pointing at a locally generated catalog.
 */
public final class ModuleCatalog {

    private static final String DEFAULT_CATALOG_URL =
            "https://hyspy-dev.github.io/meridian/catalog.json";

    private static final String CATALOG_URL = resolveCatalogUrl();

    private static String resolveCatalogUrl() {
        String url = System.getProperty("meridian.catalog.url");
        if (url == null || url.isBlank()) url = System.getenv("MERIDIAN_CATALOG_URL");
        return url == null || url.isBlank() ? DEFAULT_CATALOG_URL : url.trim();
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Catalog cache;

    /** The whole catalog from one fetch. */
    public record Catalog(List<CatalogModule> modules, List<EndAppVersion> proxy,
                          List<EndAppVersion> launcher, Map<String, Long> games) {}

    /** A module and all its published versions. */
    public record CatalogModule(String repo, String name, String description, String htmlUrl,
                                int layer, List<CatalogVersion> versions) {}

    /**
     * One installable version of a module. {@code builtFor} / {@code requiresProtocol} mirror the
     * build stamp inside the jar (null / false when the jar predates it); the jar itself stays
     * authoritative — these are only used to describe and pick a build before downloading it.
     */
    public record CatalogVersion(String version, boolean prerelease, String jarName, String url,
                                 long size, String sha256, String minProxyVersion,
                                 String maxProxyVersion, Map<String, String> dependsOn,
                                 boolean layer1, List<String> games, Long builtFor,
                                 boolean requiresProtocol) {}

    /** A proxy or launcher build. {@code proto} is the protocol CRC (proxy only; null for launcher). */
    public record EndAppVersion(String version, String jarName, String url, long size,
                                String sha256, Long proto) {}

    /** The whole catalog, fetched live and cached for the session. */
    public Catalog load(boolean forceRefresh) throws IOException, InterruptedException {
        Catalog cached = cache;
        if (cached != null && !forceRefresh) return cached;
        Catalog c = parse(fetch());
        cache = c;
        return c;
    }

    /** Convenience: just the modules (used by the Modules tab). */
    public List<CatalogModule> list(boolean forceRefresh) throws IOException, InterruptedException {
        return load(forceRefresh).modules();
    }

    private String fetch() throws IOException, InterruptedException {
        if (CATALOG_URL.startsWith("file:")) {   // dev mode: a locally generated catalog
            return Files.readString(Path.of(URI.create(CATALOG_URL)));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(CATALOG_URL))
                .header("User-Agent", "meridian-launcher")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("catalog HTTP " + res.statusCode());
        return res.body();
    }

    private static Catalog parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        List<CatalogModule> modules = new ArrayList<>();
        JsonArray mods = root.has("modules") && root.get("modules").isJsonArray()
                ? root.getAsJsonArray("modules") : new JsonArray();
        for (JsonElement me : mods) {
            JsonObject m = me.getAsJsonObject();
            List<CatalogVersion> versions = new ArrayList<>();
            if (m.has("versions") && m.get("versions").isJsonArray()) {
                for (JsonElement ve : m.getAsJsonArray("versions")) {
                    JsonObject v = ve.getAsJsonObject();
                    Long builtFor = v.has("builtFor") && v.get("builtFor").isJsonPrimitive()
                            ? v.get("builtFor").getAsLong() : null;
                    versions.add(new CatalogVersion(
                            str(v, "version"), bool(v, "prerelease"), str(v, "jarName"), str(v, "url"),
                            longv(v, "size"), str(v, "sha256"), str(v, "minProxyVersion"),
                            str(v, "maxProxyVersion"), strMap(v, "dependsOn"), bool(v, "layer1"),
                            strList(v, "games"), builtFor, bool(v, "requiresProtocol")));
                }
            }
            modules.add(new CatalogModule(str(m, "repo"), str(m, "name"), str(m, "description"),
                    str(m, "htmlUrl"),
                    m.has("layer") && m.get("layer").isJsonPrimitive() ? m.get("layer").getAsInt() : 2,
                    versions));
        }
        modules.sort((a, b) -> a.repo().compareToIgnoreCase(b.repo()));

        Map<String, Long> games = new LinkedHashMap<>();
        if (root.has("games") && root.get("games").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("games").entrySet()) {
                if (e.getValue().isJsonPrimitive()) games.put(e.getKey(), e.getValue().getAsLong());
            }
        }
        return new Catalog(modules, parseEndApp(root, "proxy"), parseEndApp(root, "launcher"), games);
    }

    private static List<EndAppVersion> parseEndApp(JsonObject root, String key) {
        List<EndAppVersion> out = new ArrayList<>();
        if (root.has(key) && root.get(key).isJsonObject()) {
            JsonObject sec = root.getAsJsonObject(key);
            if (sec.has("versions") && sec.get("versions").isJsonArray()) {
                for (JsonElement ve : sec.getAsJsonArray("versions")) {
                    JsonObject v = ve.getAsJsonObject();
                    Long proto = v.has("proto") && v.get("proto").isJsonPrimitive()
                            ? v.get("proto").getAsLong() : null;
                    out.add(new EndAppVersion(str(v, "version"), str(v, "jarName"), str(v, "url"),
                            longv(v, "size"), str(v, "sha256"), proto));
                }
            }
        }
        return out;
    }

    /** Downloads a module version's jar to {@code dest}, verifying its SHA-256 from the catalog. */
    public void download(CatalogVersion v, Path dest, LongConsumer progress)
            throws IOException, InterruptedException {
        downloadTo(v.url(), v.sha256(), v.jarName(), dest, progress);
    }

    /** Downloads any catalog jar (by URL) to {@code dest}, verifying its SHA-256 when known. */
    public void downloadTo(String url, String sha256, String name, Path dest, LongConsumer progress)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "meridian-launcher")
                .header("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + res.statusCode() + " for " + name);
        }
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        if (part.getParent() != null) Files.createDirectories(part.getParent());
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                total += n;
                if (progress != null) progress.accept(total);
            }
        }
        if (sha256 != null && !sha256.isBlank() && !hex(sha.digest()).equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException("Checksum mismatch for " + name + " — download rejected.");
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    // --- json helpers -------------------------------------------------------------

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static long longv(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : -1;
    }

    private static Map<String, String> strMap(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return out;
    }

    private static List<String> strList(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out.isEmpty() ? null : out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
