package meridian.launcher.mitm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows backend: the MITM CA goes into the per-user trust store.
 *
 * <p>Uses {@code certutil -user -addstore Root}, which writes to {@code CurrentUser\Root}
 * and needs no administrator rights — the trust is scoped to this user, not the machine.
 * The client validates through WinHTTP and that store, so unlike Linux there is no
 * environment-only route: the CA really has to be installed, and taken back out afterwards.
 */
public final class WindowsCaTrust implements CaTrust {

    private static final Logger log = LoggerFactory.getLogger(WindowsCaTrust.class);
    private static final String STORE = "Root";

    public WindowsCaTrust() {
    }

    @Override
    public String describe() {
        return "CurrentUser\\Root store (certutil)";
    }

    /** Whether this backend applies to the current OS. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Installs {@code caFile} (a PEM/DER cert) into the current user's Root store. */
    @Override
    public void install(Path caFile) throws Exception {
        run("Installing CA into CurrentUser\\Root",
                List.of("certutil", "-user", "-addstore", "-f", STORE, caFile.toString()));
    }

    /** Removes the CA from the current user's Root store, by its SHA-1 thumbprint. */
    @Override
    public void uninstall(X509Certificate ca) throws Exception {
        String thumbprint = sha1Thumbprint(ca);
        run("Removing CA from CurrentUser\\Root",
                List.of("certutil", "-user", "-delstore", STORE, thumbprint));
    }

    /** True if a cert with this thumbprint is already in the user's Root store. */
    @Override
    public boolean isInstalled(X509Certificate ca) {
        try {
            String thumbprint = sha1Thumbprint(ca);
            Process p = new ProcessBuilder("certutil", "-user", "-verifystore", STORE, thumbprint)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, TimeUnit.SECONDS);
            return p.exitValue() == 0 && !out.contains("Cannot find");
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(String what, List<String> command) throws Exception {
        log.info("{} via certutil", what);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("certutil timed out: " + what);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("certutil failed (" + p.exitValue() + "): "
                    + out.strip());
        }
    }

    private static String sha1Thumbprint(X509Certificate ca) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-1").digest(ca.getEncoded());
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
