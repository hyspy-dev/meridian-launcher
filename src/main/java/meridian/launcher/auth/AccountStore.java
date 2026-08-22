package meridian.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists several {@link Account}s so the user can start any of them in a couple of
 * clicks instead of logging in each time. Stored next to the jar (see {@code AppPaths}),
 * owner-only (holds refresh tokens). Migrates a pre-existing single-account
 * {@code launcher-session.json} on first load.
 */
public final class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Path legacySessionFile;

    /** On-disk shape. */
    private static final class Data {
        Map<String, Account> accounts = new LinkedHashMap<>();
        String activeId;
    }

    public AccountStore(Path file, Path legacySessionFile) {
        this.file = file;
        this.legacySessionFile = legacySessionFile;
    }

    public static AccountStore defaultStore() {
        return new AccountStore(meridian.launcher.AppPaths.resolve("accounts.json"),
                meridian.launcher.AppPaths.resolve("launcher-session.json"));
    }

    // --- reads ------------------------------------------------------------------------

    /** Accounts, most-recently-used first. */
    public List<Account> list() {
        List<Account> out = new ArrayList<>(load().accounts.values());
        out.sort(Comparator.comparingLong((Account a) -> a.lastUsedAt).reversed());
        return out;
    }

    public Account get(String id) {
        return load().accounts.get(id);
    }

    /** The active account (last launched), or the first, or null if none. */
    public Account active() {
        Data d = load();
        if (d.activeId != null && d.accounts.containsKey(d.activeId)) {
            return d.accounts.get(d.activeId);
        }
        return d.accounts.isEmpty() ? null : d.accounts.values().iterator().next();
    }

    public boolean isEmpty() {
        return load().accounts.isEmpty();
    }

    // --- writes -----------------------------------------------------------------------

    /** Adds or replaces an account (keyed by its id) and makes it active. */
    public void put(Account account) {
        Data d = load();
        d.accounts.put(account.id, account);
        d.activeId = account.id;
        save(d);
    }

    /** Records a successful launch: updates the session and marks the account active. */
    public void recordUse(Account account) {
        account.lastUsedAt = System.currentTimeMillis();
        put(account);
    }

    /** Persists changes to an existing account in place, without changing which is active. */
    public void update(Account account) {
        Data d = load();
        if (d.accounts.containsKey(account.id)) {
            d.accounts.put(account.id, account);
            save(d);
        }
    }

    public void setActive(String id) {
        Data d = load();
        if (d.accounts.containsKey(id)) {
            d.activeId = id;
            save(d);
        }
    }

    public void remove(String id) {
        Data d = load();
        if (d.accounts.remove(id) != null) {
            if (id.equals(d.activeId)) {
                d.activeId = d.accounts.isEmpty() ? null : d.accounts.keySet().iterator().next();
            }
            save(d);
        }
    }

    // --- persistence ------------------------------------------------------------------

    private Data load() {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d != null) {
                    if (d.accounts == null) d.accounts = new LinkedHashMap<>();
                    return d;
                }
            } catch (Exception e) {
                log.warn("Could not read {}: {}", file, e.toString());
            }
        }
        return migrateLegacy();
    }

    /** Imports a single-account launcher-session.json into the new multi-account store. */
    private Data migrateLegacy() {
        Data d = new Data();
        try {
            if (Files.exists(legacySessionFile)) {
                try (Reader r = Files.newBufferedReader(legacySessionFile)) {
                    GameSession s = GSON.fromJson(r, GameSession.class);
                    if (s != null && s.refreshToken != null && s.profileUuid != null) {
                        Account a = new Account(s.profileUuid, s.profileUsername, s.refreshToken, s);
                        a.lastUsedAt = System.currentTimeMillis();
                        d.accounts.put(a.id, a);
                        d.activeId = a.id;
                        save(d);
                        log.info("Migrated existing session for {} into accounts.json", a.displayName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Legacy session migration failed: {}", e.toString());
        }
        return d;
    }

    private void save(Data d) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(d, w);
            }
            restrictToOwner(file);
        } catch (Exception e) {
            log.warn("Could not write {}: {}", file, e.toString());
        }
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Windows / non-POSIX: inherits the user profile ACL.
        }
    }
}
