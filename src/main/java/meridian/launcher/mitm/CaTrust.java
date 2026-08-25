package meridian.launcher.mitm;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Makes the MITM CA trusted by the game client, per OS.
 *
 * <p>The client is a native host with .NET inside, and .NET validates certificates through a
 * different backend on each platform (verified by fingerprinting the shipped binaries):
 *
 * <ul>
 *   <li><b>Windows</b> — WinHTTP + the Windows certificate store, so the CA has to be
 *       <em>installed</em> into the current user's Root store and removed afterwards.</li>
 *   <li><b>Linux</b> — the OpenSSL shim ({@code System.Security.Cryptography.Native}), which
 *       honours {@code SSL_CERT_FILE}. Nothing is installed: the client is launched with an
 *       environment pointing at a bundle we build, so the machine is left untouched.</li>
 *   <li><b>macOS</b> — the Apple shim ({@code SecTrustEvaluate}), i.e. the keychain, so the CA
 *       is added to the user's login keychain and removed afterwards.</li>
 * </ul>
 *
 * <p>Two shapes of backend follow from that: {@link #install}/{@link #uninstall} for the ones
 * that must touch a system store, and {@link #launchEnv} for the ones that only need the
 * client's environment. A backend implements whichever applies; the caller does both, and the
 * no-op half costs nothing.
 */
public interface CaTrust {

    /** The backend for the current OS. */
    static CaTrust forThisOs() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return new WindowsCaTrust();
        if (os.contains("mac") || os.contains("darwin")) return new MacCaTrust();
        return new LinuxCaTrust();
    }

    /** Human-readable name of this backend, for logs and user-facing messages. */
    String describe();

    /** Whether this backend has to install anything at all (false = environment-only). */
    default boolean installs() {
        return true;
    }

    /** True when {@code ca} is already trusted, so a launch needs no install. */
    boolean isInstalled(X509Certificate ca);

    /** Trusts {@code caFile} (a PEM certificate) for the current user. */
    void install(Path caFile) throws Exception;

    /** Stops trusting {@code ca} — the counterpart of {@link #install}. */
    void uninstall(X509Certificate ca) throws Exception;

    /**
     * Environment entries the client must be launched with for the CA to be trusted, on top of
     * the proxy variables the caller already sets. Empty when trust comes from a store instead.
     *
     * @param caFile the PEM certificate of the MITM CA
     * @param workDir a writable directory the backend may use for generated files
     */
    default Map<String, String> launchEnv(Path caFile, Path workDir) {
        return Map.of();
    }
}
