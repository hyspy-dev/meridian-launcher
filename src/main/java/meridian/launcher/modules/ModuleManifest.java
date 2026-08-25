package meridian.launcher.modules;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The {@code module.json} a Meridian proxy module carries in its jar root — the same manifest the
 * proxy's {@code ModuleManager} reads. Only the fields the launcher needs to list, describe and
 * gate a module are kept; unknown members are ignored.
 *
 * @param name       unique module id (key others use in {@code dependsOn})
 * @param version    module SemVer
 * @param main       FQCN of the {@code ProxyModule} (informational for the launcher)
 * @param priority   load priority within a dependency level (lower first); null = default 100
 * @param minProxyVersion / maxProxyVersion  proxy SemVer range (legacy aliases min/maxCoreVersion)
 * @param updateUrl  where updates live (shown to the user)
 * @param dependsOn  module-name → SemVer range
 * @param requiresPackets  protocol packet names a Layer-1 module needs
 * @param requiresServices service FQCNs a Layer-2 module expects
 * @param builtFor         wire CRC of the build that produced this jar — written by the build
 *                         (umbrella {@code build.sh} / release CI), never by hand; null for jars
 *                         from other build pipelines
 * @param requiresProtocol whether the module really depends on {@code meridian-protocol} — also
 *                         stamped by the build; being built by a game recipe is not the same as
 *                         needing that protocol
 */
public record ModuleManifest(
        String name,
        String version,
        String main,
        Integer priority,
        String minProxyVersion,
        String maxProxyVersion,
        String updateUrl,
        Map<String, String> dependsOn,
        List<String> requiresPackets,
        List<String> requiresServices,
        Long builtFor,
        boolean requiresProtocol) {

    private static final Gson GSON = new Gson();

    /** True for a Layer-1 module: it needs the protocol (build stamp) or raw packets. */
    public boolean isLayer1() {
        return requiresProtocol || (requiresPackets != null && !requiresPackets.isEmpty());
    }

    /**
     * Reads {@code module.json} from a module jar, or returns {@code null} if the jar has none
     * (i.e. it is not a Meridian module).
     */
    public static ModuleManifest fromJar(Path jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry entry = zf.getEntry("module.json");
            if (entry == null) return null;
            try (InputStream in = zf.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return fromJson(json);
            }
        }
    }

    /** Parses a {@code module.json} document; null when it has no {@code name}. */
    public static ModuleManifest fromJson(String json) {
        Dto d = GSON.fromJson(json, Dto.class);
        if (d == null || d.name == null || d.name.isBlank()) return null;
        String minProxy = d.minProxyVersion != null ? d.minProxyVersion : d.minCoreVersion;
        String maxProxy = d.maxProxyVersion != null ? d.maxProxyVersion : d.maxCoreVersion;
        List<String> packets = d.requires != null ? d.requires.packets : null;
        List<String> services = d.requires != null ? d.requires.services : null;
        return new ModuleManifest(d.name, d.version, d.main, d.priority, minProxy, maxProxy,
                d.updateUrl, d.dependsOn, packets, services,
                d.builtFor, Boolean.TRUE.equals(d.requiresProtocol));
    }

    /** Gson shape of {@code module.json} (a superset; extra fields are ignored). */
    private static final class Dto {
        String name;
        String version;
        String main;
        Integer priority;
        String minProxyVersion;
        String maxProxyVersion;
        String minCoreVersion;   // legacy alias
        String maxCoreVersion;   // legacy alias
        String updateUrl;
        Map<String, String> dependsOn;
        Requires requires;
        Long builtFor;             // build stamp
        Boolean requiresProtocol;  // build stamp

        static final class Requires {
            List<String> packets;
            List<String> services;
        }
    }
}
