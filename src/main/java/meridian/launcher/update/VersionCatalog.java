package meridian.launcher.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import meridian.launcher.update.UpdateClient.PatchStep;

/**
 * Discovers which game versions can be downloaded right now. Hytale keys game downloads by channel
 * (patchline); each channel serves exactly its newest build as a full {@code from=0} patch, and
 * older builds within a channel are pruned from the CDN (they answer 403). So the catalog is "the
 * newest build of every channel the account can reach" — which still spans several distinct
 * versions (e.g. v0.4, v0.5, release, pre-release), including genuinely older ones.
 */
public final class VersionCatalog {

    private static final Logger log = LoggerFactory.getLogger(VersionCatalog.class);

    private final UpdateClient client = new UpdateClient();

    /**
     * One downloadable version: a channel's newest build (with its semantic version name), and what
     * (if anything) is installed locally.
     */
    public record ChannelVersion(String channel, String version, int newestBuild, long size,
                                 String pwrUrl, String sigUrl, boolean installed, int installedBuild) {
        public boolean upToDate() {
            return installed && installedBuild >= newestBuild;
        }
    }

    /**
     * The account's entitled channels and their newest downloadable build, taken from the
     * authoritative {@code get-launcher-data} patchline list (no channel-name guessing). For each,
     * the from=0 full step supplies the download URL and size, and the local install state is read
     * from its {@code env.dat}.
     */
    public List<ChannelVersion> discover(String accessToken, Path root)
            throws IOException, InterruptedException {
        InstallEnv.Platform pf = InstallEnv.currentPlatform();
        List<ChannelVersion> out = new ArrayList<>();
        for (UpdateClient.Patchline line : client.patchlines(accessToken, pf.os(), pf.arch())) {
            String channel = line.channel();
            int newest = line.newest();
            if (newest <= 0) continue;

            // The from=0 full step carries the (time-limited) download URL and lets us size it.
            String pwrUrl = null, sigUrl = null;
            long size = -1;
            try {
                List<PatchStep> steps = client.patchSet(accessToken, pf.os(), pf.arch(), channel, 0);
                PatchStep full = steps.stream()
                        .filter(s -> s.from() == 0 && s.to() == newest)
                        .findFirst().orElse(null);
                if (full != null) {
                    pwrUrl = full.pwrUrl();
                    sigUrl = full.sigUrl();
                    size = client.contentLength(pwrUrl);
                }
            } catch (IOException e) {
                // keep the channel listed even if its download URL can't be fetched right now
            }

            int installedBuild = installedBuildOf(root, line.buildVersion());
            boolean installed = installedBuild > 0;
            out.add(new ChannelVersion(channel, line.buildVersion(), newest, size, pwrUrl, sigUrl,
                    installed, installedBuild));
            log.info("Catalog: {} = {} (build {}, {} MB){}", channel, line.buildVersion(), newest,
                    size > 0 ? size / 1_000_000 : "?",
                    installed ? " [installed build " + installedBuild + "]" : "");
        }
        return out;
    }

    /**
     * The installed build of a channel's version, or -1. Matches either the channel-named folder
     * (rolling channels like release/pre-release) or a version-named folder — a pinned vX.Y line is
     * installed under a folder named by its version (e.g. {@code v0.4.2}, not {@code v0.4}).
     */
    /**
     * The installed build for a channel version, or -1 — found by reading the game <b>version</b>
     * inside each install folder's env.dat (never the folder name). Prefers an exact version match;
     * falls back to the same {@code major.minor} line, since the patch API and env.dat can name the
     * same build differently (patchlines "0.4.2" vs env.dat "0.4.0").
     */
    private static int installedBuildOf(Path root, String channelVersion) {
        if (channelVersion == null) return -1;
        Path installDir = root.resolve("install");
        if (!Files.isDirectory(installDir)) return -1;
        String line = versionLine(channelVersion);
        int lineMatch = -1;
        try (java.util.stream.Stream<Path> dirs = Files.list(installDir)) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                String v = InstallEnv.gameVersion(root, d.getFileName().toString());
                if (v == null) continue;
                Integer b = buildOf(root, d.getFileName().toString());
                if (b == null) continue;
                if (v.equals(channelVersion)) return b;          // exact version installed
                if (lineMatch < 0 && versionLine(v).equals(line)) lineMatch = b;
            }
        } catch (IOException ignored) {
        }
        return lineMatch;                                        // same X.Y line, else -1
    }

    /** The {@code major.minor} line of a version (e.g. 0.4.2 → "0.4", 0.6.0-pre.13.1 → "0.6"). */
    private static String versionLine(String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static Integer buildOf(Path root, String folder) {
        if (!Files.exists(InstallEnv.envFile(root, folder))) return null;
        try {
            return InstallEnv.currentBuild(root, folder);
        } catch (IOException e) {
            return null;
        }
    }
}
