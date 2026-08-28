package meridian.launcher.launch;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What game version a proxy jar on disk speaks.
 *
 * <p>A proxy is bound to one wire protocol, so the jar that matches the game version being
 * launched is the only one that will work. Two things identify a build, cheapest first:
 *
 * <ul>
 *   <li>the <b>game line in its file name</b> — release jars are named
 *       {@code meridian-proxy-<version>+<line>-all.jar}, and the line ({@code 0.5.9},
 *       {@code 0.6.X}) is matched against the game version the same way the catalog matches
 *       it: exact, or an {@code X.Y.X} wildcard covering a whole minor;</li>
 *   <li>the <b>wire CRC compiled into the jar</b> ({@code meridian.protocol.ProtocolSettings
 *       .PROTOCOL_CRC}), read from the jar itself. Locally built jars carry no line in their
 *       name, and this is the only thing that identifies them.</li>
 * </ul>
 *
 * <p>Both are properties of the jar, so nothing has to be remembered or configured.
 */
public final class ProxyBuild {

    /** CRCs read out of jars, keyed by path + last-modified so a rebuilt jar is re-read. */
    private static final Map<String, Long> CRC_CACHE = new ConcurrentHashMap<>();

    private ProxyBuild() {
    }

    /** The {@code +<line>} in the jar's name, or null when it carries none. */
    public static String lineOf(Path jar) {
        if (jar == null) return null;
        String name = jar.getFileName().toString();
        int plus = name.indexOf('+');
        if (plus < 0) return null;
        String rest = name.substring(plus + 1);
        for (String suffix : List.of("-all.jar", ".jar")) {
            if (rest.endsWith(suffix)) return rest.substring(0, rest.length() - suffix.length());
        }
        return null;
    }

    /**
     * The wire CRC this jar was built against, or null if it cannot be read. The class is
     * loaded in a throw-away loader — {@code PROTOCOL_CRC} is a plain constant, so nothing of
     * the proxy actually runs.
     */
    public static Long crcOf(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return null;
        String key;
        try {
            key = jar.toAbsolutePath() + "@" + Files.getLastModifiedTime(jar).toMillis();
        } catch (Exception e) {
            return null;
        }
        Long cached = CRC_CACHE.get(key);
        if (cached != null) return cached;
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> settings = Class.forName("meridian.protocol.ProtocolSettings", true, loader);
            long crc = settings.getField("PROTOCOL_CRC").getInt(null) & 0xFFFFFFFFL;
            CRC_CACHE.put(key, crc);
            return crc;
        } catch (Throwable t) {
            return null;   // not a proxy jar, or a build without the protocol on its classpath
        }
    }

    /**
     * Whether {@code jar} serves {@code gameVersion}: by its name's line when it has one, else
     * by its CRC against {@code gameCrc} (the version's wire CRC, from the catalog). Null
     * inputs mean "unknown", and an unknown build is never claimed to match.
     */
    public static boolean serves(Path jar, String gameVersion, Long gameCrc) {
        String line = lineOf(jar);
        if (line != null && gameVersion != null) {
            return coversGame(line, gameVersion);
        }
        Long crc = crcOf(jar);
        return crc != null && crc.equals(gameCrc);
    }

    /** A build line covers a game version exactly, or as the {@code X.Y.X} wildcard for its minor. */
    static boolean coversGame(String line, String gameVersion) {
        if (line.equals(gameVersion)) return true;
        String[] p = gameVersion.split("\\.");
        return p.length >= 2 && line.equals(p[0] + "." + p[1] + ".X");
    }
}
