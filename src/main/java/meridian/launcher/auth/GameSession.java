package meridian.launcher.auth;

import com.google.gson.annotations.SerializedName;

/**
 * A minted game session: the tokens the game client needs, plus the refresh token used
 * to renew them without another interactive login.
 *
 * <p>{@code sessionToken} and {@code identityToken} are what the client reads from its
 * environment ({@code HYTALE_SESSION_TOKEN} / {@code HYTALE_IDENTITY_TOKEN}); the proxy
 * consumes the same {@code sessionToken} as its player session. The refresh token is
 * account-scoped and long-lived — treat it as a credential.
 */
public final class GameSession {

    @SerializedName("identityToken")
    public String identityToken;

    @SerializedName("sessionToken")
    public String sessionToken;

    @SerializedName("refreshToken")
    public String refreshToken;

    /**
     * Singleplayer/offline token for this profile ({@code HYTALE_OFFLINE_TOKEN}), minted alongside
     * the session. Best-effort — null if the offline mint was unavailable; the client only needs it
     * for the local singleplayer server.
     */
    @SerializedName("offlineToken")
    public String offlineToken;

    /** Profile the session was minted for, filled in from the launcher data. */
    public String profileUuid;
    public String profileUsername;

    public boolean isUsable() {
        return sessionToken != null && !sessionToken.isBlank()
                && identityToken != null && !identityToken.isBlank();
    }
}
