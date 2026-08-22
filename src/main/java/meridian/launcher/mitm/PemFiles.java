package meridian.launcher.mitm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/** Minimal PEM read/write for the CA cert and key. No external PEM library needed. */
final class PemFiles {

    private static final Base64.Encoder MIME = Base64.getMimeEncoder(64,
            "\n".getBytes(StandardCharsets.US_ASCII));

    private PemFiles() {
    }

    static void writeCertificate(Path file, X509Certificate cert) throws Exception {
        writePem(file, "CERTIFICATE", cert.getEncoded());
    }

    static void writePrivateKey(Path file, PrivateKey key) throws Exception {
        writePem(file, "PRIVATE KEY", key.getEncoded());
    }

    static X509Certificate readCertificate(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        }
    }

    static PrivateKey readPrivateKey(Path file) throws Exception {
        String pem = Files.readString(file);
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static void writePem(Path file, String type, byte[] der) throws IOException {
        String pem = "-----BEGIN " + type + "-----\n"
                + MIME.encodeToString(der) + "\n"
                + "-----END " + type + "-----\n";
        Files.writeString(file, pem);
    }

    static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX: inherits the user profile ACL.
        }
    }
}
