package meridian.launcher.auth;

/**
 * Hytale account/session backend endpoints and the OAuth client identity.
 *
 * <p>The launcher presents itself as {@code hytale-launcher}, the same OAuth client id the
 * official launcher uses, so the account backend issues launcher-scoped tokens.
 */
public final class HytaleEndpoints {

    /** OAuth2 authorization server. */
    public static final String OAUTH_BASE = "https://oauth.accounts.hytale.com";
    /** Game-session service (session/identity token minting, server-join handshake). */
    public static final String SESSIONS_BASE = "https://sessions.hytale.com";
    /** Account/profile data, incl. the launcher-data profile list. */
    public static final String ACCOUNT_DATA_BASE = "https://account-data.hytale.com";

    /** OAuth client id — the official launcher's, so scopes and endpoints line up. */
    public static final String CLIENT_ID = "hytale-launcher";
    /** OAuth scopes the launcher requests. */
    public static final String SCOPE = "openid offline auth:launcher";

    /**
     * Redirect URI the account backend expects for this client. It is a hytale.com page
     * that bounces the authorization code back to the loopback listener via the {@code
     * state} it was given, so the value is fixed rather than our own localhost URL.
     */
    public static final String REDIRECT_URI = "https://accounts.hytale.com/consent/client";

    /**
     * User-Agent the account endpoints expect — the official launcher is a Go binary, and
     * at least {@code get-launcher-data} is sensitive to it, so it is sent on every call.
     */
    public static final String USER_AGENT = "Go-http-client/1.1";

    private HytaleEndpoints() {
    }
}
