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
     * The last session minted for each profile, by profile uuid. Sessions live per profile: a mint
     * for one leaves the others' running windows alone, but a second mint for the same profile
     * ends its first. Keeping each one is what lets a profile's next window reuse it.
     */
    public java.util.Map<String, GameSession> sessions;
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

    /** True when a session for {@code profileUuid} is stored and not near expiry. */
    public boolean hasValidSessionFor(String profileUuid) {
        GameSession s = sessionFor(profileUuid);
        return s != null && s.isUsable()
                && TokenExpiry.isValid(s.sessionToken)
                && TokenExpiry.isValid(s.identityToken);
    }

    /** The stored session for {@code profileUuid}, or null. */
    public GameSession sessionFor(String profileUuid) {
        if (profileUuid == null) return null;
        if (sessions != null && sessions.get(profileUuid) != null) return sessions.get(profileUuid);
        return session != null && profileUuid.equals(session.profileUuid) ? session : null;
    }

    /** Stores {@code fresh} as its profile's session, and as the last one. */
    public void storeSession(GameSession fresh) {
        session = fresh;
        if (fresh != null && fresh.profileUuid != null) {
            if (sessions == null) sessions = new java.util.LinkedHashMap<>();
            sessions.put(fresh.profileUuid, fresh);
        }
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
