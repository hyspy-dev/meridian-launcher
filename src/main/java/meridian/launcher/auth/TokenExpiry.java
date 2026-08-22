package meridian.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the {@code exp} claim of a JWT so the launcher can tell whether a stored token is
 * still usable without minting a fresh one. Minting (game-session/new) invalidates other
 * live sessions of the same account, so reusing a still-valid token is what lets several
 * windows of one account coexist.
 */
public final class TokenExpiry {

    /** Treat a token as expired this many seconds before its real deadline. */
    private static final long SKEW_SECONDS = 120;

    private TokenExpiry() {
    }

    /** The token's expiry, or null if it has no readable {@code exp}. */
    public static Instant expiryOf(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonObject obj = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return obj.has("exp") ? Instant.ofEpochSecond(obj.get("exp").getAsLong()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the token should be treated as expired — genuinely past {@code exp}, or
     * within the skew window, or unreadable. A token with no {@code exp} is treated as
     * expired so the launcher errs toward re-minting rather than sending a dead token.
     */
    public static boolean isExpired(String jwt) {
        Instant exp = expiryOf(jwt);
        if (exp == null) return true;
        return Instant.now().plusSeconds(SKEW_SECONDS).isAfter(exp);
    }

    public static boolean isValid(String jwt) {
        return !isExpired(jwt);
    }
}
