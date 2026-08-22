package meridian.launcher.mitm;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local certificate authority for the MITM: one long-lived root CA, plus short leaf
 * certificates minted on demand for each host being intercepted and signed by that root.
 *
 * <p>The root's private key never leaves this machine (stored owner-only), and the root
 * is the only thing that needs to be trusted by the client. Leaves carry the host as a
 * SubjectAlternativeName so the client accepts them for that host — provided it validates
 * against the trust store rather than pinning.
 */
public final class CertificateAuthority {

    private static final Logger log = LoggerFactory.getLogger(CertificateAuthority.class);
    private static final String CA_CN = "Meridian Launcher Local CA";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final X509Certificate caCert;
    private final PrivateKey caKey;
    private final ConcurrentHashMap<String, LeafCertificate> leafCache = new ConcurrentHashMap<>();
    private final AtomicLong serial = new AtomicLong(System.currentTimeMillis());

    private CertificateAuthority(X509Certificate caCert, PrivateKey caKey) {
        this.caCert = caCert;
        this.caKey = caKey;
    }

    /** A minted leaf: its certificate chain (leaf, CA) and its private key. */
    public record LeafCertificate(X509Certificate[] chain, PrivateKey key) {}

    public X509Certificate caCertificate() {
        return caCert;
    }

    /**
     * Loads the CA from {@code dir} (a PEM cert + a PKCS8 key), generating and persisting a
     * fresh one the first time. Reusing it across runs means the client trusts it once.
     */
    public static CertificateAuthority loadOrCreate(Path dir) throws Exception {
        Path certFile = dir.resolve("meridian-ca.crt");
        Path keyFile = dir.resolve("meridian-ca.key");
        if (Files.isReadable(certFile) && Files.isReadable(keyFile)) {
            try {
                X509Certificate cert = PemFiles.readCertificate(certFile);
                PrivateKey key = PemFiles.readPrivateKey(keyFile);
                return new CertificateAuthority(cert, key);
            } catch (Exception e) {
                log.warn("Existing CA unreadable ({}); regenerating.", e.toString());
            }
        }
        CertificateAuthority ca = generate();
        Files.createDirectories(dir);
        PemFiles.writeCertificate(certFile, ca.caCert);
        PemFiles.writePrivateKey(keyFile, ca.caKey);
        PemFiles.restrictToOwner(keyFile);
        log.info("Generated a new local CA at {}", certFile);
        return ca;
    }

    /** Generates a self-signed root CA (not persisted). */
    public static CertificateAuthority generate() throws Exception {
        KeyPair caPair = rsaKeyPair();
        X500Principal dn = new X500Principal("CN=" + CA_CN);
        Instant now = Instant.now();

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                dn,
                caPair.getPublic());

        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                ext.createSubjectKeyIdentifier(caPair.getPublic()));

        X509Certificate cert = sign(builder, caPair.getPrivate());
        return new CertificateAuthority(cert, caPair.getPrivate());
    }

    /** A leaf for {@code host}, minted once and cached for reuse within this run. */
    public LeafCertificate leafFor(String host) {
        return leafCache.computeIfAbsent(host, this::mintLeaf);
    }

    private LeafCertificate mintLeaf(String host) {
        try {
            KeyPair leafPair = rsaKeyPair();
            X500Principal subject = new X500Principal("CN=" + host);
            Instant now = Instant.now();

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caCert,
                    BigInteger.valueOf(serial.incrementAndGet()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(825, ChronoUnit.DAYS)),
                    subject,
                    leafPair.getPublic());

            builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, host)));

            X509Certificate leaf = sign(builder, caKey);
            return new LeafCertificate(new X509Certificate[]{leaf, caCert}, leafPair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint leaf for " + host, e);
        }
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey signer)
            throws Exception {
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(signer);
        X509CertificateHolder holder = builder.build(cs);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, RANDOM);
        return kpg.generateKeyPair();
    }
}
