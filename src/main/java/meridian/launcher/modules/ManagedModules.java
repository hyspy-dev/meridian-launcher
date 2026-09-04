package meridian.launcher.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import meridian.launcher.AppPaths;
import meridian.launcher.modules.ModuleCatalog.Catalog;
import meridian.launcher.modules.ModuleCatalog.CatalogModule;
import meridian.launcher.modules.ModuleCatalog.CatalogVersion;

/**
 * The launcher's own module storage, kept strictly out of the user's folders.
 *
 * <p>Everything the launcher installs lives in {@code store/} next to the launcher — every build
 * of every module, one per game line where a module is protocol-bound. The proxy's
 * {@code modules/} folders stay the user's (and standalone's) territory: the launcher writes a
 * single generated {@code modules.json} there, listing the store jars the proxy should also
 * load, and nothing else. The proxy verifies each entry against the jar's own build stamp, and
 * a hand-placed jar always wins over an offered one.
 *
 * <p>Because jars are never swapped in place, several proxy instances — even for different game
 * versions — can run from the same folders at once. Each build is pinned; the launcher only
 * <b>adds</b> a build when the game version being started has none yet.
 */
public final class ManagedModules {

    private static final String OFFER_FILE = "modules.json";
    private static final String REGISTRY_FILE = "installed.json";

    private ManagedModules() {
    }

    /** One installed build of a module. {@code game}/{@code builtFor} null = protocol-neutral. */
    public record Build(String jar, String version, String game, Long builtFor,
                        boolean requiresProtocol) {}

    /** A module the launcher manages for one scope, with every build it installed. */
    public record Managed(String repo, String name, boolean enabled, List<Build> builds) {
        /**
         * True when some installed build serves {@code gameVersion}. Judged by the build's game
         * line (a build with none is game-neutral and serves everything) — not by the jar's
         * {@code requiresProtocol} stamp, which jars released before the stamp existed don't
         * carry and would otherwise look neutral.
         */
        public boolean covers(String gameVersion) {
            return builds.stream().anyMatch(b -> b.game() == null
                    || matchesGame(List.of(b.game()), gameVersion));
        }
    }

    public static Path storeDir() {
        return AppPaths.launcherDir().resolve("store");
    }

    // --- queries -------------------------------------------------------------------

    /**
     * What this scope folder actually offers the proxy. The folder's own {@code modules.json}
     * is the source of truth — a per-server folder is created by the PROXY (seeded from the
     * default set), so the launcher's registry knows nothing about it until the user changes
     * something there. The registry only adds what the file cannot carry: modules the user
     * switched off (a disabled module is not offered, so it is absent from the file).
     */
    public static List<Managed> list(Path scopeFolder) {
        Registry reg = Registry.load();
        Map<String, Managed> byRepo = new LinkedHashMap<>();
        for (Offered o : readOffer(scopeFolder, reg)) {
            Managed m = byRepo.get(o.repo());
            List<Build> builds = new ArrayList<>(m == null ? List.of() : m.builds());
            builds.add(new Build(o.jar(), o.version(), o.game(), o.builtFor(), o.requiresProtocol()));
            byRepo.put(o.repo(), new Managed(o.repo(), o.name(), true, builds));
        }
        // The registry wins where it knows a module: it carries what the file need not (the
        // disabled flag) and what older offer files lack (the build's game line).
        for (Managed m : reg.forScope(scopeFolder)) byRepo.put(m.repo(), m);
        List<Managed> out = new ArrayList<>(byRepo.values());
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /**
     * Pulls a scope's current offer into the registry before changing anything there. A
     * per-server folder is seeded by the proxy, so its modules.json may list builds the
     * registry has never seen — and {@link #writeOffer} regenerates the file <b>from</b> the
     * registry, which would otherwise drop them.
     */
    private static void adoptOffer(Registry reg, Path scopeFolder) {
        for (Offered o : readOffer(scopeFolder, reg)) {
            Entry e = reg.entry(scopeFolder, o.repo());
            if (e.name == null) e.name = o.name();
            if (e.builds.stream().noneMatch(b -> b.jar().equals(o.jar()))) {
                e.builds.add(new Build(o.jar(), o.version(), o.game(), o.builtFor(),
                        o.requiresProtocol()));
            }
        }
    }

    /** One entry of a scope's {@code modules.json}, as written by {@link #writeOffer}. */
    private record Offered(String repo, String name, String jar, String version, String game,
                           Long builtFor, boolean requiresProtocol) {}

    /**
     * Reads a scope's offer file; entries whose jar is missing (or nameless) are skipped.
     * Offer files written before entries carried {@code repo} are matched back to the registry
     * by module name, so an upgraded launcher doesn't show the same module twice.
     */
    private static List<Offered> readOffer(Path scopeFolder, Registry reg) {
        Path f = scopeFolder.resolve(OFFER_FILE);
        if (!Files.isRegularFile(f)) return List.of();
        List<Offered> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("modules") || !root.get("modules").isJsonArray()) return out;
            for (JsonElement el : root.getAsJsonArray("modules")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String path = str(o, "path");
                if (path == null) continue;
                Path jar = Path.of(path);
                if (!Files.isRegularFile(jar)) continue;   // store jar gone → not really offered
                String name = str(o, "module");
                String repo = str(o, "repo");
                if (repo == null) repo = repoByName(reg, scopeFolder, name);   // pre-`repo` file
                if (repo == null) continue;
                out.add(new Offered(repo, name != null ? name : repo,
                        jar.getFileName().toString(), str(o, "version"), str(o, "game"),
                        o.has("builtFor") && o.get("builtFor").isJsonPrimitive()
                                ? o.get("builtFor").getAsLong() : null,
                        o.has("requiresProtocol") && o.get("requiresProtocol").getAsBoolean()));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The repo id the registry knows this module's display name by, or the name itself. */
    private static String repoByName(Registry reg, Path scopeFolder, String name) {
        if (name == null) return null;
        for (Managed m : reg.forScope(scopeFolder)) {
            if (name.equals(m.name())) return m.repo();
        }
        return name;
    }

    /** Managed modules with no build for {@code gameVersion} — what a launch would fetch. */
    public static List<String> missingFor(Path scopeFolder, String gameVersion) {
        List<String> out = new ArrayList<>();
        for (Managed m : list(scopeFolder)) {
            if (m.enabled() && !m.covers(gameVersion)) out.add(m.name());
        }
        return out;
    }

    // --- mutations (Modules tab) ---------------------------------------------------

    /**
     * Downloads {@code v} into the store (verifying its sha256) and registers it for
     * {@code scopeFolder}, then rewrites that folder's {@code modules.json}. The module's other
     * builds stay — a build is only replaced by another build of the same game line.
     */
    public static Managed install(Path scopeFolder, String repo, CatalogVersion v,
                                  ModuleCatalog catalog)
            throws IOException, InterruptedException {
        Path jar = ensureInStore(catalog, v);
        ModuleManifest mf = ModuleManifest.fromJar(jar);
        if (mf == null) throw new IOException("Not a Meridian module — no module.json in " + v.jarName());
        Build build = new Build(v.jarName(), mf.version() != null ? mf.version() : v.version(),
                v.games() == null || v.games().isEmpty() ? null : v.games().get(0),
                mf.builtFor(), mf.requiresProtocol());

        Registry reg = Registry.load();
        adoptOffer(reg, scopeFolder);
        Entry e = reg.entry(scopeFolder, repo);
        e.name = mf.name() != null ? mf.name() : repo;
        e.builds.removeIf(b -> sameLine(b, build));
        e.builds.add(build);
        reg.save();
        writeOffer(scopeFolder, reg);
        return reg.forScope(scopeFolder).stream().filter(m -> m.repo().equals(repo)).findFirst()
                .orElse(new Managed(repo, e.name, e.enabled, List.copyOf(e.builds)));
    }

    /** Enables/disables a managed module for one scope (a disabled module is not offered). */
    /**
     * Takes a jar the user hands over and manages it like any other: into the store, into the
     * registry, into the offer.
     *
     * <p>It used to be copied straight into the scope folder instead, which left it outside
     * everything the launcher knows - not in the store, not in {@code modules.json}, and copied
     * again into every server folder it was wanted in. A module built locally is a normal thing
     * to install, and there is no reason it should live differently from a downloaded one.
     *
     * @param srcJar the jar as the user chose it; it is copied, never moved or kept open
     * @return the module as it now stands, with this build among its builds
     */
    public static Managed installLocal(Path scopeFolder, Path srcJar) throws IOException {
        ModuleManifest mf = ModuleManifest.fromJar(srcJar);
        if (mf == null) {
            throw new IOException("Not a Meridian module - no module.json in "
                    + srcJar.getFileName());
        }
        String repo = repoIdFor(mf, srcJar);
        Path stored = storeDir().resolve(storeNameFor(repo, mf));
        Files.createDirectories(storeDir());
        // Into place in one step, so a proxy reading the store never sees half a jar.
        Path tmp = storeDir().resolve(stored.getFileName() + ".part");
        Files.copy(srcJar, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, stored, StandardCopyOption.REPLACE_EXISTING);

        // No game line: a jar off somebody's disk does not say which line it was cut for, and
        // the stamp it does carry is what the proxy actually checks.
        Build build = new Build(stored.getFileName().toString(), mf.version(), null,
                mf.builtFor(), mf.requiresProtocol());
        Registry reg = Registry.load();
        adoptOffer(reg, scopeFolder);
        Entry e = reg.entry(scopeFolder, repo);
        e.name = mf.name() != null ? mf.name() : repo;
        e.builds.removeIf(b -> sameLine(b, build));
        e.builds.add(build);
        reg.save();
        writeOffer(scopeFolder, reg);
        return reg.forScope(scopeFolder).stream().filter(m -> m.repo().equals(repo)).findFirst()
                .orElse(new Managed(repo, e.name, e.enabled, List.copyOf(e.builds)));
    }

    /**
     * What to file a hand-installed module under.
     *
     * <p>The module's own name, as a repo id would spell it - so a locally built jar lands on top
     * of the catalog build of the same module rather than beside it, which is what somebody
     * testing their own build wants. Falls back to the file name when the jar has no name.
     */
    private static String repoIdFor(ModuleManifest mf, Path srcJar) {
        String from = mf.name() != null && !mf.name().isBlank()
                ? mf.name()
                : srcJar.getFileName().toString().replaceAll("(?i)\\.jar$", "");
        String id = from.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return id.isEmpty() ? "local-module" : id;
    }

    /** A store name that says what the jar is, so two builds of one module cannot collide. */
    private static String storeNameFor(String repo, ModuleManifest mf) {
        StringBuilder name = new StringBuilder(repo);
        if (mf.version() != null && !mf.version().isBlank()) {
            name.append('-').append(mf.version().replaceAll("[^A-Za-z0-9.+_-]", "_"));
        }
        if (mf.builtFor() != null) {
            name.append("+p").append(mf.builtFor());
        }
        return name.append(".jar").toString();
    }

    public static void setEnabled(Path scopeFolder, String repo, boolean enabled) {
        Registry reg = Registry.load();
        adoptOffer(reg, scopeFolder);
        Entry e = reg.find(scopeFolder, repo);
        if (e == null || e.enabled == enabled) return;
        e.enabled = enabled;
        reg.save();
        writeOffer(scopeFolder, reg);
    }

    /** Removes a managed module from one scope. Store jars stay — they are a shared cache. */
    public static void remove(Path scopeFolder, String repo) {
        Registry reg = Registry.load();
        adoptOffer(reg, scopeFolder);
        if (reg.remove(scopeFolder, repo)) {
            reg.save();
            writeOffer(scopeFolder, reg);
        }
    }

    // --- launch-time sync (append-only) --------------------------------------------

    /**
     * Makes sure every enabled managed module in every scope under {@code proxyDir} has a build
     * for {@code gameVersion}, fetching missing ones into the store and refreshing each
     * {@code modules.json}. Adds only — never replaces or deletes — so concurrent proxy
     * instances are unaffected. Never throws; the catalog is fetched only if something is
     * actually missing.
     */
    public static void syncAll(Path proxyDir, String gameVersion, Consumer<String> log) {
        if (proxyDir == null || gameVersion == null) return;
        ModuleCatalog catalog = new ModuleCatalog();
        Catalog[] lazy = new Catalog[1];
        Registry reg = Registry.load();
        for (Path scope : scopeFolders(proxyDir)) {
            boolean changed = false;
            // A server folder the proxy seeded is unknown to the registry until now.
            int before = reg.forScope(scope).size();
            adoptOffer(reg, scope);
            if (reg.forScope(scope).size() != before) changed = true;
            for (Managed m : reg.forScope(scope)) {
                if (!m.enabled() || m.covers(gameVersion)) continue;
                try {
                    Catalog c = lazy[0] != null ? lazy[0] : (lazy[0] = catalog.load(false));
                    CatalogVersion target = resolve(c, m.repo(), gameVersion);
                    if (target == null) {
                        log.accept("Modules: " + m.name() + " has no build for " + gameVersion + " — skipped.");
                        continue;
                    }
                    Path jar = ensureInStore(catalog, target);
                    ModuleManifest mf = ModuleManifest.fromJar(jar);
                    Entry e = reg.entry(scope, m.repo());
                    e.builds.add(new Build(target.jarName(),
                            mf != null && mf.version() != null ? mf.version() : target.version(),
                            target.games() == null || target.games().isEmpty() ? null : target.games().get(0),
                            mf != null ? mf.builtFor() : target.builtFor(),
                            mf != null ? mf.requiresProtocol() : target.requiresProtocol()));
                    changed = true;
                    log.accept("Modules: fetched " + target.jarName() + " for " + gameVersion + ".");
                } catch (Exception ex) {
                    log.accept("Modules: couldn't fetch " + m.name() + " for " + gameVersion
                            + ": " + ex.getMessage());
                }
            }
            if (changed) {
                reg.save();
            }
            // The offer is rewritten every time, changed or not. It is built by reading the jars
            // in the store, so this is what notices a jar swapped there by hand - and without it
            // the catalog would keep describing the file that used to be at that name until
            // something else happened to touch the module, which is how an offer came to claim a
            // version the jar no longer had. A few small reads at startup.
            writeOffer(scope, reg);
        }
    }

    /** The shared {@code modules} folder plus every {@code <host_port>/modules} under it. */
    private static List<Path> scopeFolders(Path proxyDir) {
        List<Path> out = new ArrayList<>();
        out.add(proxyDir.resolve("modules"));
        try (Stream<Path> dirs = Files.list(proxyDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().equals("modules"))
                    .map(d -> d.resolve("modules"))
                    .filter(Files::isDirectory)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Newest catalog version of {@code repo} built for {@code gameVersion} (stable preferred). */
    private static CatalogVersion resolve(Catalog c, String repo, String gameVersion) {
        for (CatalogModule mod : c.modules()) {
            if (!mod.repo().equals(repo)) continue;
            CatalogVersion pre = null;
            for (CatalogVersion v : mod.versions()) {   // newest first
                if (v.games() == null || !matchesGame(v.games(), gameVersion)) continue;
                if (!v.prerelease()) return v;
                if (pre == null) pre = v;
            }
            return pre;
        }
        return null;
    }

    /** True when a build's game list covers {@code gameVersion}: exact key, or the X.Y.X line. */
    public static boolean matchesGame(List<String> games, String gameVersion) {
        if (games == null || gameVersion == null) return false;
        if (games.contains(gameVersion)) return true;
        String[] p = gameVersion.split("\\.");
        return p.length >= 2 && games.contains(p[0] + "." + p[1] + ".X");
    }

    private static boolean sameLine(Build a, Build b) {
        return a.game() == null ? b.game() == null : a.game().equals(b.game());
    }

    /** The jar in the immutable store, downloading (and sha256-verifying) it if absent. */
    private static Path ensureInStore(ModuleCatalog catalog, CatalogVersion v)
            throws IOException, InterruptedException {
        Path stored = storeDir().resolve(v.jarName());
        if (Files.isRegularFile(stored)) {
            String sha = sha256Of(stored);
            if (sha != null && sha.equalsIgnoreCase(v.sha256())) return stored;
        }
        catalog.download(v, stored, null);   // verifies sha256 from the catalog
        return stored;
    }

    // --- modules.json (what the proxy reads) ---------------------------------------

    /**
     * Rewrites {@code <scope>/modules.json}: the store jars this scope offers the proxy, each
     * described by what the launcher believes it to be (the proxy re-checks against the jar).
     * Written even when empty — an empty offer is how a removal reaches the proxy.
     */
    private static void writeOffer(Path scopeFolder, Registry reg) {
        JsonArray arr = new JsonArray();
        for (Managed m : reg.forScope(scopeFolder)) {
            if (!m.enabled()) continue;
            for (Build b : m.builds()) {
                Path jar = storeDir().resolve(b.jar());
                // What the jar says about itself, read now - not what was true when it was
                // installed. A jar replaced in the store by hand is the ordinary case while a
                // module is being worked on, and an offer describing the file it used to be is
                // one the proxy refuses outright: it holds the offer and the jar to agreeing
                // exactly, on purpose, because a catalog that misdescribes a jar is worse than
                // no catalog at all.
                ModuleManifest mf;
                try {
                    mf = ModuleManifest.fromJar(jar);
                } catch (IOException e) {
                    mf = null;
                }
                if (mf == null) {
                    continue;               // gone, or not a module: offering it helps nobody
                }
                JsonObject o = new JsonObject();
                o.addProperty("path", jar.toAbsolutePath().toString());
                o.addProperty("repo", m.repo());     // lets the launcher adopt a folder it didn't write
                o.addProperty("module", mf.name() != null ? mf.name() : m.name());
                if (mf.version() != null) o.addProperty("version", mf.version());
                if (b.game() != null) o.addProperty("game", b.game());
                if (mf.builtFor() != null) o.addProperty("builtFor", mf.builtFor());
                o.addProperty("requiresProtocol", mf.requiresProtocol());
                arr.add(o);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("writtenBy", "meridian-launcher");
        root.add("modules", arr);
        try {
            Files.createDirectories(scopeFolder);
            // Write-then-move so a proxy starting concurrently never reads a torn file.
            Path tmp = scopeFolder.resolve(OFFER_FILE + ".tmp");
            Files.writeString(tmp, root + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, scopeFolder.resolve(OFFER_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best-effort: the proxy simply keeps the previous offer
        }
    }

    // --- registry (store/installed.json) -------------------------------------------

    private static final class Entry {
        String name;
        boolean enabled = true;
        final List<Build> builds = new ArrayList<>();
    }

    /** Which modules the launcher installed, per scope folder. Lives in the store, not in the
     *  user's folders — the {@code modules.json} files are generated from it. */
    private static final class Registry {
        // scope folder (absolute, normalised) → repo → entry
        final Map<String, Map<String, Entry>> scopes = new LinkedHashMap<>();

        static String key(Path scopeFolder) {
            return scopeFolder.toAbsolutePath().normalize().toString();
        }

        static Registry load() {
            Registry reg = new Registry();
            Path f = storeDir().resolve(REGISTRY_FILE);
            if (!Files.isRegularFile(f)) return reg;
            try {
                JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject scopes = root.has("scopes") && root.get("scopes").isJsonObject()
                        ? root.getAsJsonObject("scopes") : new JsonObject();
                for (Map.Entry<String, JsonElement> se : scopes.entrySet()) {
                    if (!se.getValue().isJsonObject()) continue;
                    Map<String, Entry> byRepo = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> me : se.getValue().getAsJsonObject().entrySet()) {
                        if (!me.getValue().isJsonObject()) continue;
                        JsonObject o = me.getValue().getAsJsonObject();
                        Entry e = new Entry();
                        e.name = str(o, "name");
                        e.enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
                        if (o.has("builds") && o.get("builds").isJsonArray()) {
                            for (JsonElement be : o.getAsJsonArray("builds")) {
                                if (!be.isJsonObject()) continue;
                                JsonObject b = be.getAsJsonObject();
                                String jar = str(b, "jar");
                                if (jar == null || !Files.isRegularFile(storeDir().resolve(jar))) {
                                    continue;   // store jar gone → forget this build
                                }
                                e.builds.add(new Build(jar, str(b, "version"), str(b, "game"),
                                        b.has("builtFor") && b.get("builtFor").isJsonPrimitive()
                                                ? b.get("builtFor").getAsLong() : null,
                                        b.has("requiresProtocol") && b.get("requiresProtocol").getAsBoolean()));
                            }
                        }
                        if (!e.builds.isEmpty()) byRepo.put(me.getKey(), e);
                    }
                    if (!byRepo.isEmpty()) reg.scopes.put(se.getKey(), byRepo);
                }
            } catch (Exception ignored) {
            }
            return reg;
        }

        void save() {
            JsonObject scopesJson = new JsonObject();
            for (Map.Entry<String, Map<String, Entry>> se : scopes.entrySet()) {
                JsonObject byRepo = new JsonObject();
                for (Map.Entry<String, Entry> me : se.getValue().entrySet()) {
                    Entry e = me.getValue();
                    JsonObject o = new JsonObject();
                    if (e.name != null) o.addProperty("name", e.name);
                    o.addProperty("enabled", e.enabled);
                    JsonArray builds = new JsonArray();
                    for (Build b : e.builds) {
                        JsonObject bo = new JsonObject();
                        bo.addProperty("jar", b.jar());
                        if (b.version() != null) bo.addProperty("version", b.version());
                        if (b.game() != null) bo.addProperty("game", b.game());
                        if (b.builtFor() != null) bo.addProperty("builtFor", b.builtFor());
                        bo.addProperty("requiresProtocol", b.requiresProtocol());
                        builds.add(bo);
                    }
                    o.add("builds", builds);
                    byRepo.add(me.getKey(), o);
                }
                scopesJson.add(se.getKey(), byRepo);
            }
            JsonObject root = new JsonObject();
            root.addProperty("schema", 1);
            root.add("scopes", scopesJson);
            try {
                Files.createDirectories(storeDir());
                Path tmp = storeDir().resolve(REGISTRY_FILE + ".tmp");
                Files.writeString(tmp, root + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, storeDir().resolve(REGISTRY_FILE), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }

        Entry entry(Path scopeFolder, String repo) {
            return scopes.computeIfAbsent(key(scopeFolder), k -> new LinkedHashMap<>())
                    .computeIfAbsent(repo, k -> new Entry());
        }

        Entry find(Path scopeFolder, String repo) {
            Map<String, Entry> byRepo = scopes.get(key(scopeFolder));
            return byRepo == null ? null : byRepo.get(repo);
        }

        boolean remove(Path scopeFolder, String repo) {
            Map<String, Entry> byRepo = scopes.get(key(scopeFolder));
            return byRepo != null && byRepo.remove(repo) != null;
        }

            List<Managed> forScope(Path scopeFolder) {
            Map<String, Entry> byRepo = scopes.get(key(scopeFolder));
            if (byRepo == null) return List.of();
            List<Managed> out = new ArrayList<>();
            for (Map.Entry<String, Entry> me : byRepo.entrySet()) {
                Entry e = me.getValue();
                out.add(new Managed(me.getKey(), e.name != null ? e.name : me.getKey(),
                        e.enabled, List.copyOf(e.builds)));
            }
            out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return out;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String sha256Of(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
