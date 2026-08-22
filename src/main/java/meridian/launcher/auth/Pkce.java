package meridian.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE material for one authorization-code flow: a random verifier, its S256 challenge,
 * and a CSRF state nonce. All are base64url without padding, matching the reference
 * client and the OAuth2 PKCE spec.
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /** The random verifier; replayed to the token endpoint to prove we started the flow. */
    public final String verifier;
    /** {@code base64url(sha256(verifier))}; sent up front in the authorize request. */
    public final String challenge;
    /** CSRF nonce; the callback must echo it back unchanged. */
    public final String state;

    private Pkce(String verifier, String challenge, String state) {
        this.verifier = verifier;
        this.challenge = challenge;
        this.state = state;
    }

    public static Pkce generate() {
        String verifier = randomToken();
        String state = randomToken();
        String challenge = B64URL.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new Pkce(verifier, challenge, state);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
