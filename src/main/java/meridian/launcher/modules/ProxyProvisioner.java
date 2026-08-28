package meridian.launcher.modules;

import meridian.launcher.AppPaths;
import meridian.launcher.modules.ModuleCatalog.Catalog;
import meridian.launcher.modules.ModuleCatalog.EndAppVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Installs the Meridian proxy from the catalog, matched to a game version. The proxy is protocol-
 * bound (its {@code proto} is the wire CRC), so the right build is chosen by mapping the game
 * version to its CRC (the catalog's {@code games} map) and finding the proxy release with that
 * {@code proto}. The jar lands next to the launcher, where {@code ProxyLauncher} looks for it.
 */
public final class ProxyProvisioner {

    private final ModuleCatalog catalog;

    public ProxyProvisioner(ModuleCatalog catalog) {
        this.catalog = catalog;
    }

    /** The newest proxy build whose protocol matches {@code gameVersion}, or null if none is known. */
    public EndAppVersion resolve(String gameVersion) throws IOException, InterruptedException {
        Catalog c = catalog.load(false);
        Long crc = crcFor(c.games(), gameVersion);
        if (crc == null) return null;
        for (EndAppVersion p : c.proxy()) {   // catalog lists releases newest-first
            if (crc.equals(p.proto())) return p;
        }
        return null;
    }

    /** Downloads a proxy build next to the launcher (verifying sha256); returns the jar path. */
    public Path download(EndAppVersion proxy, LongConsumer progress)
            throws IOException, InterruptedException {
        Path dest = AppPaths.launcherDir().resolve(proxy.jarName());
        catalog.downloadTo(proxy.url(), proxy.sha256(), proxy.jarName(), dest, progress);
        return dest;
    }

    /** Maps a game version to its protocol CRC: exact key first, then the {@code X.Y.X} line. */
    public static Long crcFor(Map<String, Long> games, String gameVersion) {
        if (gameVersion == null || games == null) return null;
        Long exact = games.get(gameVersion);
        if (exact != null) return exact;
        String[] p = gameVersion.split("\\.");
        return p.length >= 2 ? games.get(p[0] + "." + p[1] + ".X") : null;
    }
}
