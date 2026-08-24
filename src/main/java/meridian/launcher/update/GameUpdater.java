package meridian.launcher.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import meridian.launcher.update.UpdateClient.PatchStep;
import meridian.launcher.update.wharf.WharfPatcher;

/**
 * Drives a Hytale game delta update end to end: check the current install against the patch API,
 * then for each step download the {@code .pwr}, rebuild the game into a staging tree, swap it in,
 * store the new build's signature, and advance {@code env.dat}. Deltas are applied to a fresh
 * staging directory rather than in place — a file is often both a patch source and a target, so
 * overwriting mid-apply would corrupt the source other files still copy from.
 *
 * <p>The caller supplies an access token (obtained from the active account's refresh token via
 * {@code HytaleAuth.access}, persisting the rotated refresh); this class stays free of account
 * storage so it can be driven from the UI or a headless check.
 */
public final class GameUpdater {

    private static final Logger log = LoggerFactory.getLogger(GameUpdater.class);

    private final UpdateClient client = new UpdateClient();

    /**
     * What a check found: whether an update exists, the installed and newest builds, whether the
     * only path is a full (from=0) reinstall rather than an incremental delta, and the raw steps.
     */
    public record UpdateCheck(boolean updateAvailable, int currentBuild, int newestBuild,
                              boolean fullReinstall, List<PatchStep> steps) {}

    /** Progress surface for the UI; both methods have no-op defaults. */
    public interface Listener {
        default void phase(String message) {}
        default void bytes(long done, long total) {}
    }

    /** Compares the installed build against the newest available, without downloading anything. */
    public UpdateCheck check(Path root, String patchline, String accessToken)
            throws IOException, InterruptedException {
        int current = InstallEnv.currentBuild(root, patchline);
        InstallEnv.Platform pf = InstallEnv.platform(root, patchline);
        List<PatchStep> steps = client.patchSet(accessToken, pf.os(), pf.arch(), patchline, current);
        // An update exists whenever a newer build is reachable — whether by incremental deltas or
        // by a full (from=0) reinstall. Some channels (e.g. release) publish ONLY the from=0 full,
        // so keying on from==current would miss real updates.
        int newest = UpdateClient.newestBuild(steps, current);
        boolean available = newest > current;
        List<PatchStep> plan = available ? planChain(steps, current, newest) : List.of();
        boolean full = !plan.isEmpty() && plan.get(0).from() == 0;
        return new UpdateCheck(available, current, newest, full, steps);
    }

    /**
     * Applies every pending delta for {@code patchline} in order, up to the newest build. A no-op
     * (returns the current build) when already current. {@code cacheDir} holds the downloaded
     * {@code .pwr}/{@code .pwr.sig} during the run and is cleaned as each step completes.
     */
    public int update(Path root, String patchline, String accessToken, Path cacheDir, Listener listener)
            throws IOException, InterruptedException {
        UpdateCheck check = check(root, patchline, accessToken);
        if (!check.updateAvailable()) {
            listener.phase("Already up to date (build " + check.currentBuild() + ")");
            return check.currentBuild();
        }
        Files.createDirectories(cacheDir);
        int current = check.currentBuild();
        List<PatchStep> plan = planChain(check.steps(), current, check.newestBuild());
        if (plan.isEmpty()) {
            listener.phase("No applicable patch from build " + current);
            return current;
        }
        // The newest build's semantic name, so env.dat records the real version (not a stale one).
        String versionName = versionNameFor(accessToken, patchline);
        for (PatchStep step : plan) {
            current = applyStep(root, patchline, step, cacheDir, listener, versionName);
        }
        listener.phase("Updated to build " + current);
        return current;
    }

    /** The channel's newest semantic version name from the authoritative patchline list, or null. */
    private String versionNameFor(String accessToken, String channel) {
        try {
            InstallEnv.Platform pf = InstallEnv.currentPlatform();
            for (UpdateClient.Patchline pl : client.patchlines(accessToken, pf.os(), pf.arch())) {
                if (pl.channel().equals(channel)) return pl.buildVersion();
            }
        } catch (Exception e) {
            log.debug("No version name for {}: {}", channel, e.toString());
        }
        return null;
    }

    /**
     * The steps to apply, current→newest: a contiguous chain of incremental deltas when the channel
     * publishes them, otherwise the single {@code from=0} full reinstall to newest. Empty when
     * neither reaches newest.
     */
    static List<PatchStep> planChain(List<PatchStep> steps, int current, int newest) {
        Map<Integer, PatchStep> byFrom = new HashMap<>();
        for (PatchStep s : steps) {
            if (s.to() > s.from()) byFrom.putIfAbsent(s.from(), s);
        }
        // Prefer following incremental deltas from the installed build.
        List<PatchStep> deltas = new ArrayList<>();
        int cur = current;
        while (cur < newest && byFrom.containsKey(cur)) {
            PatchStep s = byFrom.get(cur);
            deltas.add(s);
            cur = s.to();
        }
        if (cur == newest && !deltas.isEmpty()) return deltas;
        // Otherwise a full reinstall (from build 0) to newest.
        PatchStep full = byFrom.get(0);
        if (full != null && full.to() == newest) return List.of(full);
        return List.of();
    }

    /**
     * Installs a channel from scratch (one not present yet): downloads its newest full (from=0)
     * build, unpacks it into the channel's {@code game/latest}, provisions a matching Temurin JRE
     * beside it ({@link JreProvisioner} — the game bundle ships no runtime for us), stores the
     * signature, and writes a minimal {@code env.dat}. Returns the installed build.
     */
    public int installFresh(Path root, String channel, String pwrUrl, String sigUrl, int build,
                            String versionName, Path cacheDir, Listener listener)
            throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path pwr = cacheDir.resolve("0-" + build + ".pwr");
        Path sig = cacheDir.resolve("0-" + build + ".pwr.sig");
        listener.phase("Downloading full build " + build);
        client.download(pwrUrl, pwr, listener::bytes);
        if (sigUrl != null) client.download(sigUrl, sig, listener::bytes);

        listener.phase("Installing build " + build);
        Path pkg = root.resolve("install").resolve(channel).resolve("package");
        Path gameDir = pkg.resolve("game").resolve("latest");
        Path staging = gameDir.resolveSibling("latest.staging");
        deleteRecursively(staging);
        WharfPatcher.apply(pwr, emptySource(cacheDir), staging, listener::bytes);
        if (gameDir.getParent() != null) Files.createDirectories(gameDir.getParent());
        deleteRecursively(gameDir);
        Files.move(staging, gameDir, StandardCopyOption.ATOMIC_MOVE);

        Path jreDir = pkg.resolve("jre");
        new JreProvisioner().provision(jreDir, root, new JreProvisioner.Progress() {
            @Override public void phase(String m) { listener.phase(m); }
            @Override public void bytes(long d, long t) { listener.bytes(d, t); }
        });
        if (Files.exists(sig)) {
            Path sigDir = InstallEnv.sigDir(root, channel, build);
            Files.createDirectories(sigDir);
            Files.copy(sig, sigDir.resolve("signature.pwr.sig"), StandardCopyOption.REPLACE_EXISTING);
        }
        long assetsSize = 0;
        Path assets = gameDir.resolve("Assets.zip");
        if (Files.exists(assets)) assetsSize = Files.size(assets);
        InstallEnv.writeFresh(root, channel, build, assetsSize, versionName);

        Files.deleteIfExists(pwr);
        Files.deleteIfExists(sig);
        listener.phase("Installed " + channel + " build " + build);
        log.info("Fresh-installed {} build {}", channel, build);
        return build;
    }

    /**
     * Installs or updates a channel to its newest build, re-fetching a fresh (signed, time-limited)
     * patch URL at call time. If the channel is already installed it updates in place (delta or full
     * reinstall); otherwise it installs from scratch, copying {@code jreSource} in as the runtime.
     * Returns the resulting build (0 if nothing was available).
     */
    public int installOrUpdate(Path root, String channel, String accessToken, Path cacheDir,
                               Listener listener) throws IOException, InterruptedException {
        if (Files.exists(InstallEnv.envFile(root, channel))) {
            return update(root, channel, accessToken, cacheDir, listener);
        }
        InstallEnv.Platform pf = InstallEnv.currentPlatform();
        List<PatchStep> steps = client.patchSet(accessToken, pf.os(), pf.arch(), channel, 0);
        int newest = UpdateClient.newestBuild(steps, 0);
        PatchStep full = steps.stream()
                .filter(s -> s.from() == 0 && s.to() == newest)
                .findFirst().orElse(null);
        if (newest <= 0 || full == null) {
            listener.phase("Nothing to install for " + channel);
            return 0;
        }
        String versionName = versionNameFor(accessToken, channel);
        return installFresh(root, channel, full.pwrUrl(), full.sigUrl(), newest, versionName, cacheDir, listener);
    }

    private int applyStep(Path root, String patchline, PatchStep step, Path cacheDir, Listener listener,
                          String versionName) throws IOException, InterruptedException {
        boolean fullInstall = step.from() == 0;
        Path gameDir = InstallEnv.gameDir(root, patchline);
        Path pwr = cacheDir.resolve(step.from() + "-" + step.to() + ".pwr");
        Path sig = cacheDir.resolve(step.from() + "-" + step.to() + ".pwr.sig");

        listener.phase((fullInstall ? "Downloading full build " : "Downloading delta to build ") + step.to());
        client.download(step.pwrUrl(), pwr, listener::bytes);
        if (step.sigUrl() != null) client.download(step.sigUrl(), sig, listener::bytes);

        listener.phase((fullInstall ? "Installing build " : "Applying build ") + step.to());
        Path staging = gameDir.resolveSibling("latest.staging");
        deleteRecursively(staging);
        // A from=0 full patch is all-DATA (no source blocks); apply it against an empty source so it
        // never reads the old, different-version install. A delta rebuilds from the current game dir.
        Path source = fullInstall ? emptySource(cacheDir) : gameDir;
        WharfPatcher.apply(pwr, source, staging, listener::bytes);

        swapIntoPlace(gameDir, staging);

        if (Files.exists(sig)) {
            Path sigDir = InstallEnv.sigDir(root, patchline, step.to());
            Files.createDirectories(sigDir);
            Files.copy(sig, sigDir.resolve("signature.pwr.sig"), StandardCopyOption.REPLACE_EXISTING);
        }
        long assetsSize = 0;
        Path assets = gameDir.resolve("Assets.zip");
        if (Files.exists(assets)) assetsSize = Files.size(assets);
        InstallEnv.recordUpdate(root, patchline, step.to(), assetsSize, versionName);

        Files.deleteIfExists(pwr);
        Files.deleteIfExists(sig);
        log.info("Advanced {} to build {}", patchline, step.to());
        return step.to();
    }

    /** Renames the freshly built staging tree over the live game dir, keeping a brief backup. */
    private static void swapIntoPlace(Path gameDir, Path staging) throws IOException {
        Path backup = gameDir.resolveSibling("latest.old");
        deleteRecursively(backup);
        if (Files.exists(gameDir)) Files.move(gameDir, backup, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staging, gameDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // restore the backup if the final rename fails, so the install is never left empty
            if (!Files.exists(gameDir) && Files.exists(backup)) Files.move(backup, gameDir);
            throw e;
        }
        deleteRecursively(backup);
    }

    /** A guaranteed-empty directory to apply a full (from=0) patch against. */
    private static Path emptySource(Path cacheDir) throws IOException {
        Path dir = cacheDir.resolve("empty-source");
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
