package meridian.launcher.auth;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands back a usable {@link GameSession} for an account, doing the least work required —
 * and, crucially, <b>reusing a still-valid stored token instead of minting a new one</b>.
 *
 * <p>Minting (game-session/new) invalidates the account's other live sessions, so a naive
 * "mint on every launch" makes a second window kill the first ("session expired"). Here a
 * launch reuses the stored session while its JWT is valid ({@link TokenExpiry}) and only
 * re-mints once it has actually expired, so several windows of one account share one live
 * session. Backed by {@link AccountStore} so any of several accounts can be started.
 */
public final class SessionProvider {

    private static final Logger log = LoggerFactory.getLogger(SessionProvider.class);

    private final HytaleAuth auth;
    private final AccountStore store;

    public SessionProvider(HytaleAuth auth, AccountStore store) {
        this.auth = auth;
        this.store = store;
    }

    public static SessionProvider withDefaults() {
        return new SessionProvider(new HytaleAuth(), AccountStore.defaultStore());
    }

    /** Accounts for a picker, most-recently-used first. */
    public List<Account> accounts() {
        return store.list();
    }

    public boolean hasAccounts() {
        return !store.isEmpty();
    }

    /** One selectable row: an account paired with one of its profiles. */
    public record ProfileRow(Account account, HytaleAuth.Profile profile) {
        public String label() {
            String name = profile.username() != null ? profile.username() : profile.uuid();
            // Show the account only when it carries more than one profile.
            return account.profileList().size() > 1
                    ? account.displayName() + " · " + name : name;
        }
    }

    /**
     * Re-fetches every account's profile list from the account service, where the stored
     * refresh token still works, so profiles added or renamed since the account was added
     * show up without re-adding it. Accounts whose refresh token is dead are left untouched
     * (best-effort). Returns how many accounts' profile lists actually changed.
     *
     * <p>This does not mint game sessions — it only reads profiles — so it is safe to run on
     * launcher open without disturbing any live game window's session.
     */
    public synchronized int refreshProfiles() {
        int changed = 0;
        for (Account a : store.list()) {
            if (a.refreshToken == null) {
                continue;
            }
            try {
                HytaleAuth.ProfileList result = auth.listProfiles(a.refreshToken);
                boolean dirty = false;
                // The refresh rotated the token server-side — persist the new one or the next
                // refresh/mint fails with invalid_grant.
                if (result.refreshToken() != null && !result.refreshToken().equals(a.refreshToken)) {
                    a.refreshToken = result.refreshToken();
                    dirty = true;
                }
                List<HytaleAuth.Profile> fresh = result.profiles();
                if (fresh != null && !fresh.isEmpty() && !fresh.equals(a.profiles)) {
                    a.profiles = fresh;
                    dirty = true;
                    changed++;
                    log.info("Refreshed {} profile(s) for {}", fresh.size(), a.displayName());
                }
                if (dirty) {
                    store.update(a);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException | RuntimeException e) {
                log.info("Profile refresh skipped for {}: {}", a.displayName(), e.toString());
            }
        }
        return changed;
    }

    /**
     * An access token for the game-update/download API, refreshed from a specific account. Does
     * not mint a game session, so it never disturbs a running window. Refreshing rotates the
     * account's refresh token; the rotated one is persisted here (or the next refresh dies with
     * {@code invalid_grant}).
     */
    public synchronized String accessToken(String accountId) throws IOException, InterruptedException {
        Account account = requireAccount(accountId);
        if (account.refreshToken == null) {
            throw new IOException("Account " + account.displayName()
                    + " has no refresh token — sign in again to download updates.");
        }
        HytaleAuth.Access access = auth.access(account.refreshToken);
        if (access.refreshToken() != null && !access.refreshToken().equals(account.refreshToken)) {
            account.refreshToken = access.refreshToken();
            store.update(account);
        }
        return access.accessToken();
    }

    /** Every profile across every account, most-recently-used account first — for the picker. */
    public List<ProfileRow> profileRows() {
        List<ProfileRow> rows = new java.util.ArrayList<>();
        for (Account a : store.list()) {
            for (HytaleAuth.Profile p : a.profileList()) {
                rows.add(new ProfileRow(a, p));
            }
        }
        return rows;
    }

    /** A usable session for the active account's first profile, adding one if none. */
    public GameSession acquire(Consumer<String> browserOpener) throws IOException, InterruptedException {
        Account active = store.active();
        if (active == null) {
            return addAccount(browserOpener);
        }
        HytaleAuth.Profile first = active.profileList().get(0);
        return acquireFor(active, first.uuid(), first.username(), browserOpener);
    }

    /** A usable session for a specific account (its first profile). */
    public GameSession acquire(String accountId, Consumer<String> browserOpener)
            throws IOException, InterruptedException {
        Account account = requireAccount(accountId);
        HytaleAuth.Profile first = account.profileList().get(0);
        return acquireFor(account, first.uuid(), first.username(), browserOpener);
    }

    /** A usable session for a specific profile of a specific account. */
    public GameSession acquireProfile(String accountId, String profileUuid, String username,
                                      Consumer<String> browserOpener)
            throws IOException, InterruptedException {
        return acquireFor(requireAccount(accountId), profileUuid, username, browserOpener);
    }

    private Account requireAccount(String accountId) {
        Account account = store.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("No such account: " + accountId);
        }
        return account;
    }

    /**
     * Produces a session for launch, reusing the stored one when it is genuinely still
     * live so that several windows of one account can share it.
     *
     * <p>Hytale keeps one active session per account: minting (game-session/new) kills the
     * account's other live sessions. The game itself does not mint — it uses the token we
     * put in its environment — so the <em>only</em> thing that invalidates a running
     * window's session is another mint by us. Therefore a second window must <b>not</b>
     * mint: it reuses the first window's token, which keeps that one live for both.
     *
     * <p>A stored token is reused only when a server-side liveness check confirms it (a
     * JWT that is not-yet-expired can still be dead server-side). Any doubt → mint a fresh,
     * guaranteed-valid token, accepting that this one launch will not share.
     */
    private GameSession acquireFor(Account account, String profileUuid, String username,
                                   Consumer<String> browserOpener)
            throws IOException, InterruptedException {
        if (account.hasValidSessionFor(profileUuid)
                && auth.isSessionLive(account.session.sessionToken)) {
            log.info("Reusing live session for {} (no mint — other windows stay authenticated).",
                    account.session.profileUsername);
            store.recordUse(account);
            return account.session;
        }

        if (account.refreshToken != null) {
            log.info("Minting a fresh session for {} · {}.", account.displayName(), username);
            GameSession fresh = auth.mintForProfile(account.refreshToken, profileUuid, username);
            updateAccount(account, fresh);
            return fresh;
        }

        // No refresh token to renew from — fall back to an interactive login.
        log.info("No refresh token for {}; interactive login required.", account.displayName());
        return addAccount(browserOpener);
    }

    /** Runs interactive login and stores the result as an account (keyed by profile uuid). */
    public GameSession addAccount(Consumer<String> browserOpener)
            throws IOException, InterruptedException {
        GameSession fresh = auth.login(browserOpener);
        Account account = new Account(fresh.profileUuid, fresh.profileUsername,
                fresh.refreshToken, fresh);
        // Record every in-game profile so the picker can list them all, not just the one we minted.
        // listProfiles rotates the refresh token, so persist the rotated one it returns.
        try {
            HytaleAuth.ProfileList result = auth.listProfiles(fresh.refreshToken);
            account.profiles = result.profiles();
            if (result.refreshToken() != null) {
                account.refreshToken = result.refreshToken();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Could not list profiles for {}: {}", account.displayName(), e.toString());
        }
        store.recordUse(account);
        log.info("Added account {} ({} profile(s))",
                account.displayName(), account.profileList().size());
        return fresh;
    }

    /** Removes an account from the store. */
    public void removeAccount(String accountId) {
        store.remove(accountId);
    }

    public void setActive(String accountId) {
        store.setActive(accountId);
    }

    private void updateAccount(Account account, GameSession fresh) {
        account.session = fresh;
        if (fresh.refreshToken != null) account.refreshToken = fresh.refreshToken;
        if (fresh.profileUsername != null) account.username = fresh.profileUsername;
        if (fresh.profileUuid != null) account.id = fresh.profileUuid;
        store.recordUse(account);
    }
}
