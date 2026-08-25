package meridian.launcher.mitm;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS backend: the CA goes into the user's <b>login keychain</b>, marked as a trusted root.
 *
 * <p>The client validates through .NET's Apple shim ({@code SecTrustEvaluate}), i.e. the
 * keychain — {@code SSL_CERT_FILE} plays no part there. Trust is set for this user only
 * ({@code -r trustRoot} against {@code login.keychain-db}), so no {@code sudo} is involved; the
 * first install shows the usual macOS prompt for the keychain password.
 */
public final class MacCaTrust implements CaTrust {

    private static final Logger log = LoggerFactory.getLogger(MacCaTrust.class);

    @Override
    public String describe() {
        return "login keychain (security add-trusted-cert)";
    }

    @Override
    public boolean isInstalled(X509Certificate ca) {
        try {
            // find-certificate -Z prints the SHA-1 of every match; ours is in there once trusted.
            Process p = new ProcessBuilder("security", "find-certificate", "-a", "-Z",
                    loginKeychain().toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(15, TimeUnit.SECONDS);
            return out.toUpperCase(java.util.Locale.ROOT).contains(sha1(ca));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void install(Path caFile) throws Exception {
        run("Adding the CA to the login keychain",
                List.of("security", "add-trusted-cert", "-r", "trustRoot",
                        "-k", loginKeychain().toString(), caFile.toString()));
    }

    @Override
    public void uninstall(X509Certificate ca) throws Exception {
        // remove-trusted-cert drops the trust setting; delete-certificate removes the cert
        // itself. The first needs the file, so it is best-effort here and the delete (by
        // fingerprint) is what actually takes it out of the keychain.
        run("Removing the CA from the login keychain",
                List.of("security", "delete-certificate", "-Z", sha1(ca),
                        loginKeychain().toString()));
    }

    private static Path loginKeychain() {
        return Path.of(System.getProperty("user.home", "."),
                "Library", "Keychains", "login.keychain-db");
    }

    private static void run(String what, List<String> command) throws Exception {
        log.info("{} via security(1)", what);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) {   // the keychain prompt is a GUI dialog
            p.destroyForcibly();
            throw new IllegalStateException("security(1) timed out: " + what);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("security(1) failed (" + p.exitValue() + "): " + out.strip());
        }
    }

    /** Upper-case hex SHA-1, the shape {@code security -Z} prints and accepts. */
    private static String sha1(X509Certificate ca) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-1").digest(ca.getEncoded());
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
