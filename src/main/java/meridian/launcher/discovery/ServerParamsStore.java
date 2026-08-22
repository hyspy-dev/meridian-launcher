package meridian.launcher.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-version cache of {@link ServerParams} in {@code server-params.json} next to the jar, so the
 * launcher can query the server browser without launching the game once a version's
 * build-bound {@code protocolVersion} has been captured (see {@link ListingsParamCapture}).
 *
 * <p>Seeded with a few known-good release entries so the format is populated and older
 * versions work out of the box; captured values add to / override these.
 */
public final class ServerParamsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Known release parameters (protocolVersion was constant across 0.5.2–0.5.4). */
    private static final Map<String, ServerParams> SEED = Map.of(
            "0.5.2", new ServerParams("0.5.2", "release", "1316766548", "1e9747cbd7206da1"),
            "0.5.3", new ServerParams("0.5.3", "release", "1316766548", "b7ef37bc1b770d64"),
            "0.5.4", new ServerParams("0.5.4", "release", "1316766548", "a52802749d53b7fc"));

    private final Path file;

    public ServerParamsStore(Path file) {
        this.file = file;
    }

    public static ServerParamsStore defaultStore() {
        return new ServerParamsStore(meridian.launcher.AppPaths.resolve("server-params.json"));
    }

    /** Parameters for a version, or {@code null} if neither captured nor seeded. */
    public synchronized ServerParams get(String version) {
        return load().get(version);
    }

    /**
     * Every known version (captured + seeded), newest first. Nothing is ever removed, so old
     * versions stay browsable via the version picker even after the install is updated.
     */
    public synchronized List<String> versions() {
        List<String> vs = new ArrayList<>(load().keySet());
        vs.sort(ServerParamsStore::compareVersionsDescending);
        return vs;
    }

    /**
     * Known versions unioned with {@code extra} (e.g. installed-but-not-yet-captured versions),
     * newest first. Lets the picker list a version you can capture next, even before its params
     * exist — selecting it then shows a "run capture-params" hint rather than hiding it.
     */
    public synchronized List<String> versionsIncluding(java.util.Collection<String> extra) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(load().keySet());
        if (extra != null) {
            for (String v : extra) {
                if (v != null && !v.isBlank()) set.add(v);
            }
        }
        List<String> vs = new ArrayList<>(set);
        vs.sort(ServerParamsStore::compareVersionsDescending);
        return vs;
    }

    /** Descending version-aware order: 0.5.10 before 0.5.9, non-numeric parts compared as text. */
    private static int compareVersionsDescending(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            boolean na = sa.matches("\\d+");
            boolean nb = sb.matches("\\d+");
            int cmp;
            if (na && nb) {
                cmp = Long.compare(Long.parseLong(sb), Long.parseLong(sa)); // descending
            } else if (na != nb) {
                cmp = na ? -1 : 1;         // a numeric part is "newer" than a lettered one (e.g. 0.5 > v0)
            } else {
                cmp = sb.compareTo(sa);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /** Stores (adds or replaces) the parameters for {@code params.version()}. */
    public synchronized void put(ServerParams params) {
        Map<String, ServerParams> all = load();
        all.put(params.version(), params);
        save(all);
    }

    private Map<String, ServerParams> load() {
        Map<String, ServerParams> all = new LinkedHashMap<>(SEED);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, ServerParams> onDisk = GSON.fromJson(r, DiskShape.class);
                if (onDisk != null) {
                    all.putAll(onDisk);   // disk overrides seeds
                }
            } catch (IOException | RuntimeException e) {
                // Corrupt/unreadable cache: fall back to the seeds rather than failing.
            }
        }
        return all;
    }

    private void save(Map<String, ServerParams> all) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(all, w);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save server params to " + file, e);
        }
    }

    /** Gson reifiable type for {@code Map<String, ServerParams>}. */
    private static final class DiskShape extends LinkedHashMap<String, ServerParams> {
    }
}
