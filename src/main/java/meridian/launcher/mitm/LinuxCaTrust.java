package meridian.launcher.mitm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux backend: trust without installing anything.
 *
 * <p>The client validates through .NET's OpenSSL shim, which reads {@code SSL_CERT_FILE}, so the
 * launcher builds a CA bundle of <b>the system roots plus our CA</b> and points the launched
 * client at it. The variable lives only as long as that process — nothing on the machine is
 * modified, and nothing has to be cleaned up if the launcher dies.
 *
 * <p>The system roots must be included: {@code SSL_CERT_FILE} <em>replaces</em> the default
 * bundle rather than adding to it, and the proxy tunnels (rather than intercepts) every host it
 * isn't asked to MITM — those would fail to validate against a bundle holding only our CA.
 */
public final class LinuxCaTrust implements CaTrust {

    private static final Logger log = LoggerFactory.getLogger(LinuxCaTrust.class);

    /** Where distributions keep the concatenated root bundle, most common first. */
    private static final List<Path> SYSTEM_BUNDLES = List.of(
            Path.of("/etc/ssl/certs/ca-certificates.crt"),      // Debian, Ubuntu, Arch, Alpine
            Path.of("/etc/pki/tls/certs/ca-bundle.crt"),        // Fedora, RHEL, CentOS
            Path.of("/etc/ssl/ca-bundle.pem"),                  // openSUSE
            Path.of("/etc/pki/tls/cacert.pem"),
            Path.of("/etc/ssl/cert.pem"));                      // Alpine, some others

    @Override
    public String describe() {
        return "SSL_CERT_FILE bundle (nothing is installed)";
    }

    @Override
    public boolean installs() {
        return false;
    }

    @Override
    public boolean isInstalled(X509Certificate ca) {
        return true;   // nothing to install: trust travels with the launched process
    }

    @Override
    public void install(Path caFile) {
        // no-op
    }

    @Override
    public void uninstall(X509Certificate ca) {
        // no-op
    }

    @Override
    public Map<String, String> launchEnv(Path caFile, Path workDir) {
        try {
            Path bundle = writeBundle(caFile, workDir);
            // SSL_CERT_DIR is deliberately left alone: OpenSSL keeps using the system hash dir
            // as well, so anything the bundle misses still resolves the normal way.
            return Map.of("SSL_CERT_FILE", bundle.toAbsolutePath().toString());
        } catch (IOException e) {
            log.warn("Could not build the CA bundle ({}); the client will not trust the MITM CA.",
                    e.toString());
            return Map.of();
        }
    }

    /** Writes {@code <workDir>/ca-bundle.pem} = system roots + our CA, and returns it. */
    static Path writeBundle(Path caFile, Path workDir) throws IOException {
        Path systemBundle = SYSTEM_BUNDLES.stream().filter(Files::isReadable).findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        if (systemBundle != null) {
            sb.append(Files.readString(systemBundle, StandardCharsets.UTF_8));
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        } else {
            log.warn("No system CA bundle found in {} — the client will trust ONLY the MITM CA, "
                    + "so hosts the proxy does not intercept may fail to validate.", SYSTEM_BUNDLES);
        }
        sb.append(Files.readString(caFile, StandardCharsets.UTF_8));
        if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');

        Files.createDirectories(workDir);
        Path bundle = workDir.resolve("ca-bundle.pem");
        Path tmp = workDir.resolve("ca-bundle.pem.tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, bundle, StandardCopyOption.REPLACE_EXISTING);
        log.info("CA bundle for the client: {} ({} + our CA)", bundle,
                systemBundle != null ? systemBundle : "no system roots");
        return bundle;
    }
}
