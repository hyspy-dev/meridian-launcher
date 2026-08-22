package meridian.launcher.auth;

/**
 * One Hytale account the launcher can start: its identity (profile uuid + username), the
 * long-lived refresh token to renew sessions without an interactive login, and the most
 * recently minted {@link GameSession}. The session is reused across game windows while its
 * token is still valid ({@link TokenExpiry}); only an expired token triggers a fresh mint.
 *
 * <p>"Account" here is the login, not the in-game profile — profile selection comes later.
 */
public final class Account {

    /** Stable key for the login: the first profile's uuid (JWT {@code sub}). */
    public String id;
    public String username;
    /** Long-lived; treat as a credential. */
    public String refreshToken;
    /** Last minted session (for {@link #session}'s profile); may be expired — check before reuse. */
    public GameSession session;
    /**
     * Every profile on this account (distinct in-game identity {@code {uuid, username}}).
     * Usually one; an account with several appears as several rows in the picker. Filled
     * from get-launcher-data at add time.
     */
    public java.util.List<HytaleAuth.Profile> profiles;
    /** Epoch millis of last successful launch, for ordering the picker. */
    public long lastUsedAt;

    public Account() {
    }

    public Account(String id, String username, String refreshToken, GameSession session) {
        this.id = id;
        this.username = username;
        this.refreshToken = refreshToken;
        this.session = session;
    }

    /** True when the stored session (whatever profile) is present and not near expiry. */
    public boolean hasValidSession() {
        return session != null && session.isUsable()
                && TokenExpiry.isValid(session.sessionToken)
                && TokenExpiry.isValid(session.identityToken);
    }

    /** True when the stored session is for {@code profileUuid} and not near expiry. */
    public boolean hasValidSessionFor(String profileUuid) {
        return session != null && session.isUsable()
                && profileUuid != null && profileUuid.equals(session.profileUuid)
                && TokenExpiry.isValid(session.sessionToken)
                && TokenExpiry.isValid(session.identityToken);
    }

    /** The profiles to show for this account — the stored list, or a single fallback. */
    public java.util.List<HytaleAuth.Profile> profileList() {
        if (profiles != null && !profiles.isEmpty()) return profiles;
        // Legacy account with no stored profile list: synthesize one from id/username.
        return java.util.List.of(new HytaleAuth.Profile(id, username));
    }

    public String displayName() {
        return username != null && !username.isBlank() ? username
                : (id != null ? id : "(unknown)");
    }
}
