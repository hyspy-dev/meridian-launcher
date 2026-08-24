package meridian.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of the launcher's credential store with a PBKDF2-derived key — a
 * mirror of Hytale's own {@code EncryptedAuthCredentialStore}, so a refresh token is never at
 * rest in plaintext. Pure {@code javax.crypto}: no native dependency, cross-platform.
 *
 * <p>The passphrase is resolved, in priority order, from:
 * <ol>
 *   <li>{@code HYTALE_AUTH_KEY_FILE} — a path whose file contents are the passphrase;</li>
 *   <li>{@code HYTALE_AUTH_KEY} — the passphrase directly;</li>
 *   <li>the machine's stable hardware id ({@link MachineId}) — the default, so the store is
 *       machine-bound and copying it to another machine forces a re-login;</li>
 *   <li>an auto-generated {@code auth.key} beside the store — last resort when no machine id
 *       is available.</li>
 * </ol>
 * Sharing the {@code HYTALE_AUTH_KEY*} variable names with the official launcher lets one key
 * unlock both. Decryption tries every currently-available passphrase (GCM's auth tag reveals
 * which fits), so a store sealed under one source keeps opening after a higher-priority source
 * appears; the next write re-seals it under the top-priority passphrase.
 *
 * <p>On-disk blob: {@code [version:1][iv:12][ciphertext+tag]}.
 */
public final class CredentialCipher {

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] SALT = "MeridianAuthCredentialStore".getBytes(StandardCharsets.UTF_8);
    private static final byte VERSION = 1;

    private static final String ENV_KEY_FILE = "HYTALE_AUTH_KEY_FILE";
    private static final String ENV_KEY = "HYTALE_AUTH_KEY";

    private final Path autoKeyFile;
    private final SecureRandom random = new SecureRandom();
    /** Derived AES keys cached by passphrase source label, so PBKDF2 runs once per source. */
    private final Map<String, SecretKey> keyCache = new HashMap<>();

    /** A passphrase together with a stable label naming its source (the key-cache key). */
    private record Pass(String label, char[] value) {}

    public CredentialCipher(Path autoKeyFile) {
        this.autoKeyFile = autoKeyFile;
    }

    // --- crypto -----------------------------------------------------------------------

    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException, IOException {
        SecretKey key = deriveKey(writePassphrase());
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext);
        byte[] out = new byte[1 + GCM_IV_BYTES + ct.length];
        out[0] = VERSION;
        System.arraycopy(iv, 0, out, 1, GCM_IV_BYTES);
        System.arraycopy(ct, 0, out, 1 + GCM_IV_BYTES, ct.length);
        return out;
    }

    public byte[] decrypt(byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 1 + GCM_IV_BYTES + GCM_TAG_BITS / 8 || blob[0] != VERSION) {
            throw new GeneralSecurityException("not a recognised credential blob");
        }
        byte[] iv = Arrays.copyOfRange(blob, 1, 1 + GCM_IV_BYTES);
        byte[] ct = Arrays.copyOfRange(blob, 1 + GCM_IV_BYTES, blob.length);
        GeneralSecurityException last = new AEADBadTagException("no available passphrase fit");
        for (Pass pass : readPassphrases()) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, deriveKey(pass), new GCMParameterSpec(GCM_TAG_BITS, iv));
                return c.doFinal(ct);
            } catch (GeneralSecurityException e) {
                last = e; // wrong key for this blob — try the next source
            }
        }
        throw last;
    }

    // --- passphrase resolution --------------------------------------------------------

    /** The passphrase new writes are sealed with — the top of the priority list. */
    private Pass writePassphrase() throws IOException {
        Pass env = fromEnv();
        if (env != null) return env;
        String mid = MachineId.get();
        if (mid != null) return new Pass("machine", mid.toCharArray());
        return autoKey(true); // create+persist a random key as the last resort
    }

    /** Every currently-available passphrase, highest priority first, to try on decrypt. */
    private List<Pass> readPassphrases() {
        List<Pass> list = new ArrayList<>();
        try {
            Pass env = fromEnv();
            if (env != null) list.add(env);
        } catch (IOException ignored) {
            // an unreadable HYTALE_AUTH_KEY_FILE just drops that candidate on read
        }
        String mid = MachineId.get();
        if (mid != null) list.add(new Pass("machine", mid.toCharArray()));
        try {
            Pass ak = autoKey(false); // only if it already exists
            if (ak != null) list.add(ak);
        } catch (IOException ignored) {
        }
        return list;
    }

    private Pass fromEnv() throws IOException {
        String file = System.getenv(ENV_KEY_FILE);
        if (file != null && !file.isBlank()) {
            Path p = Path.of(file.trim());
            if (Files.isReadable(p)) {
                return new Pass("env-file", Files.readString(p).trim().toCharArray());
            }
            throw new IOException(ENV_KEY_FILE + " is set but unreadable: " + p);
        }
        String direct = System.getenv(ENV_KEY);
        return (direct != null && !direct.isBlank())
                ? new Pass("env", direct.toCharArray()) : null;
    }

    private Pass autoKey(boolean create) throws IOException {
        if (Files.isReadable(autoKeyFile)) {
            return new Pass("auto", Files.readString(autoKeyFile).trim().toCharArray());
        }
        if (!create) return null;
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (autoKeyFile.getParent() != null) Files.createDirectories(autoKeyFile.getParent());
        Files.writeString(autoKeyFile, key);
        restrictToOwner(autoKeyFile);
        return new Pass("auto", key.toCharArray());
    }

    private SecretKey deriveKey(Pass pass) throws GeneralSecurityException {
        SecretKey cached = keyCache.get(pass.label());
        if (cached != null) return cached;
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(pass.value(), SALT, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKey key = new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        keyCache.put(pass.label(), key);
        return key;
    }

    /** Best-effort owner-only perms (no-op on non-POSIX; Windows inherits the profile ACL). */
    static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
