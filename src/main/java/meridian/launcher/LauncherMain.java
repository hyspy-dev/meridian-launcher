package meridian.launcher;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import meridian.launcher.auth.Account;
import meridian.launcher.auth.GameSession;
import meridian.launcher.auth.SessionProvider;
import meridian.launcher.capture.CaptureProxy;
import meridian.launcher.capture.HytaleBackends;
import meridian.launcher.discovery.ListingsParamCapture;
import meridian.launcher.discovery.ServerDiscoveryClient;
import meridian.launcher.discovery.ServerListing;
import meridian.launcher.discovery.ServerParams;
import meridian.launcher.discovery.ServerParamsStore;
import meridian.launcher.mitm.CertificateAuthority;
import meridian.launcher.mitm.ExchangeDumper;
import meridian.launcher.mitm.ExchangeHandler;
import meridian.launcher.mitm.MitmProxy;
import meridian.launcher.mitm.CaTrust;
import meridian.launcher.launch.GameLauncher;
import meridian.launcher.launch.HytaleInstall;
import meridian.launcher.launch.HytaleRoot;
import meridian.launcher.ui.LauncherWindow;

/**
 * Entry point for the launcher.
 *
 * <p>With no command (a double-clicked jar) it opens the GUI. The reference-style CLI
 * remains for scripting and for exercising the account flow on its own:
 *
 * <ul>
 *   <li>{@code gui}     — open the window (the default)</li>
 *   <li>{@code login}   — force interactive sign-in, replacing any stored session</li>
 *   <li>{@code session} — return a usable session (refresh or login), print a summary</li>
 *   <li>{@code launch}  — acquire a session and start the client ({@code --client PATH})</li>
 *   <li>{@code capture} — launch through a recon proxy and print the hosts the client reached</li>
 *   <li>{@code play}    — launch through the proxy, optionally blocking telemetry
 *       ({@code --block-telemetry} / {@code --block host,host})</li>
 *   <li>{@code probe}   — MITM the server-discovery / mod-browser hosts to test whether
 *       they are interceptable (pinning test)</li>
 *   <li>{@code logout}  — forget the stored session</li>
 * </ul>
 */
public final class LauncherMain {

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "gui";
        try {
            switch (command) {
                case "gui" -> LauncherWindow.launch(args);
                case "login" -> doLogin();
                case "session" -> doSession(args);
                case "launch" -> doLaunch(args);
                case "capture" -> doCapture(args, Set.of());
                case "play" -> doPlay(args);
                case "probe" -> doProbe(args);
                case "observe" -> doObserve(args);
                case "capture-params" -> doCaptureParams(args);
                case "servers" -> doServers(args);
                case "accounts" -> doAccounts();
                case "logout" -> doLogout(args);
                default -> {
                    System.err.println("Unknown command: " + command);
                    System.err.println("Usage: gui | login | session | accounts"
                            + "  (launch selectors: [--account NAME] [--hytale ROOT] [--version NAME]"
                            + " or [--client EXE])\n"
                            + "  | launch | capture | play [--block-telemetry] [--block h1,h2]"
                            + " | probe [--host h1,h2] [--keep-ca] | observe [--host h1,h2]"
                            + " | capture-params [--keep-ca]"
                            + " | servers [--sort featured|random|favorite] [--version V]"
                            + " | logout [--account NAME]");
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void doLogin() throws Exception {
        GameSession session = SessionProvider.withDefaults().addAccount(browserOpener());
        printSummary("Signed in", session);
    }

    /** Lists the stored accounts (active first), each with its in-game profiles. */
    private static void doAccounts() {
        List<Account> accounts = SessionProvider.withDefaults().accounts();
        if (accounts.isEmpty()) {
            System.out.println("No accounts. Run 'login' to add one.");
            return;
        }
        System.out.println("Accounts (most recently used first):");
        for (Account a : accounts) {
            String state = a.hasValidSession() ? "session valid" : "needs refresh";
            System.out.printf("  %-20s %s  [%s]%n", a.displayName(), a.id, state);
            var profiles = a.profileList();
            if (profiles.size() > 1) {
                for (var p : profiles) {
                    System.out.printf("      · %-16s %s%n", p.username(), p.uuid());
                }
            }
        }
    }

    private static void doSession(String[] args) throws Exception {
        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        printSummary("Session ready", session);
    }

    private static void doLaunch(String[] args) throws Exception {
        HytaleInstall install = resolveInstall(args);
        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        printSummary("Session ready", session);

        Process process = new GameLauncher(install)
                .launch(session, Map.of(), List.of());
        System.out.println("Launched client (pid " + process.pid() + "). Waiting for it to exit…");
        int exit = process.waitFor();
        System.out.println("Client exited with code " + exit);
    }

    /**
     * Recon for the telemetry/server-list work: launch the game with its HTTP(S) traffic
     * pointed at a local capture proxy, then print every backend host it reached. This
     * shows whether the client honours a proxy at all and which endpoints it uses —
     * without decrypting anything, so the game runs normally.
     */
    private static void doCapture(String[] args, Set<String> blocked) throws Exception {
        launchThroughProxy(args, blocked, "Capture proxy");
    }

    /**
     * Plays through the local proxy, optionally blocking telemetry. {@code --block-telemetry}
     * refuses the known telemetry/crash hosts; {@code --block h1,h2} refuses an explicit
     * list. Blocking a host makes the client's request to it fail — telemetry is
     * fire-and-forget, so the game is unaffected — and needs no decryption, so it works
     * regardless of certificate pinning.
     */
    private static void doPlay(String[] args) throws Exception {
        Set<String> blocked = new java.util.HashSet<>();
        if (hasFlag(args, "--block-telemetry")) {
            blocked.addAll(HytaleBackends.TELEMETRY);
        }
        String explicit = argValue(args, "--block");
        if (explicit != null) {
            for (String h : explicit.split(",")) {
                if (!h.isBlank()) blocked.add(h.trim());
            }
        }
        launchThroughProxy(args, blocked, "Filter proxy");
    }

    /** Shared: launch the game through the local proxy with an optional blocklist. */
    private static void launchThroughProxy(String[] args, Set<String> blocked, String label)
            throws Exception {
        HytaleInstall install = resolveInstall(args);
        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        printSummary("Session ready", session);

        try (CaptureProxy proxy = new CaptureProxy(0, blocked)) {
            proxy.start();
            String proxyUrl = "http://127.0.0.1:" + proxy.port();
            // .NET's SocketsHttpHandler and most stacks read these; we own the child env.
            Map<String, String> env = Map.of(
                    "HTTP_PROXY", proxyUrl, "HTTPS_PROXY", proxyUrl, "ALL_PROXY", proxyUrl,
                    "http_proxy", proxyUrl, "https_proxy", proxyUrl, "all_proxy", proxyUrl);

            System.out.println(label + " on " + proxyUrl + " — launching the game.");
            if (!blocked.isEmpty()) {
                System.out.println("Blocking: " + blocked);
            }
            Process process = new GameLauncher(install).launch(session, env, List.of());
            int exit = process.waitFor();
            System.out.println("Client exited with code " + exit + ".");
            proxy.printSummary();
            if (!proxy.sawAnything()) {
                System.out.println("\nThe client sent nothing through the proxy — its HTTP stack likely"
                        + " ignores the proxy env. Next: try the Windows system proxy instead.");
            }
        }
    }

    /**
     * Tests whether the discovery / mod-browser hosts can be man-in-the-middled. Generates
     * a local CA (trusted in the current user's store on Windows), MITMs the target hosts,
     * launches the game, and reports per host whether the client accepted our certificate
     * (interceptable) or rejected it (pinned / CA not trusted). The CA is removed again at
     * the end unless {@code --keep-ca} is given.
     */
    private static void doProbe(String[] args) throws Exception {
        HytaleInstall install = resolveInstall(args);

        Set<String> hosts = new java.util.LinkedHashSet<>();
        String hostArg = argValue(args, "--host");
        if (hostArg != null) {
            for (String h : hostArg.split(",")) {
                if (!h.isBlank()) hosts.add(h.trim());
            }
        } else {
            hosts.add(HytaleBackends.SERVER_DISCOVERY);
            hosts.add(HytaleBackends.MOD_BROWSER);
        }

        Path caDir = AppPaths.resolve("ca");
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(caDir);
        CaTrust caTrust = CaTrust.forThisOs();
        boolean caInstalled = false;
        if (caTrust.installs() && !caTrust.isInstalled(ca.caCertificate())) {
            caTrust.install(caDir.resolve("meridian-ca.crt"));
            caInstalled = true;
            System.out.println("Trusted the probe CA — " + caTrust.describe() + ".");
        } else {
            System.out.println("CA trust: " + caTrust.describe() + ".");
        }

        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        printSummary("Session ready", session);

        try (MitmProxy proxy = new MitmProxy(0, ca, hosts)) {
            proxy.start();
            String proxyUrl = "http://127.0.0.1:" + proxy.port();
            Map<String, String> env = new java.util.HashMap<>(Map.of(
                    "HTTP_PROXY", proxyUrl, "HTTPS_PROXY", proxyUrl, "ALL_PROXY", proxyUrl,
                    "http_proxy", proxyUrl, "https_proxy", proxyUrl, "all_proxy", proxyUrl));
            env.putAll(caTrust.launchEnv(caDir.resolve("meridian-ca.crt"), caDir));

            System.out.println("MITM proxy on " + proxyUrl + " — intercepting " + hosts);
            System.out.println("Open the server browser (and the mod browser) in-game, then quit.");
            Process process = new GameLauncher(install).launch(session, env, List.of());
            process.waitFor();

            System.out.println("\n=== Pinning probe results ===");
            for (String host : hosts) {
                MitmProxy.Verdict v = proxy.verdicts().get(host);
                String label = v == null ? "not contacted through the proxy"
                        : v == MitmProxy.Verdict.INTERCEPTED ? "INTERCEPTABLE (client trusted our cert)"
                        : "PINNED / rejected (client refused our cert)";
                System.out.printf("  %-32s %s%n", host, label);
            }
            System.out.println("=============================");
        } finally {
            if (caInstalled && !hasFlag(args, "--keep-ca")) {
                try {
                    caTrust.uninstall(ca.caCertificate());
                    System.out.println("Removed the probe CA from the trust store.");
                } catch (Exception e) {
                    System.out.println("Could not remove the probe CA automatically: " + e.getMessage()
                            + "\nRemove it via certmgr.msc (Trusted Root, \"Meridian Launcher Local CA\").");
                }
            }
        }
    }

    /**
     * Dumps the game's decrypted traffic to a backend so we can see how it works — by
     * default sessions.hytale.com, to reveal what the game sends when it needs a token
     * (the request that, when a second window runs it, kills the first window's session).
     * Saves each request/response to {@code ~/.meridian/dumps/<run>/} and relays unchanged.
     */
    private static void doObserve(String[] args) throws Exception {
        HytaleInstall install = resolveInstall(args);

        Set<String> hosts = new java.util.LinkedHashSet<>();
        String hostArg = argValue(args, "--host");
        if (hostArg != null) {
            for (String h : hostArg.split(",")) if (!h.isBlank()) hosts.add(h.trim());
        } else {
            hosts.add("sessions.hytale.com");
        }

        Path caDir = AppPaths.resolve("ca");
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(caDir);
        CaTrust caTrust = CaTrust.forThisOs();
        boolean caInstalled = false;
        if (caTrust.installs() && !caTrust.isInstalled(ca.caCertificate())) {
            caTrust.install(caDir.resolve("meridian-ca.crt"));
            caInstalled = true;
            System.out.println("Trusted the observe CA — " + caTrust.describe() + ".");
        }

        Path dumpDir = AppPaths.resolve("dumps").resolve("run-" + System.currentTimeMillis());
        ExchangeDumper dumper = new ExchangeDumper(dumpDir);
        Map<String, ExchangeHandler> handlers = new java.util.HashMap<>();
        for (String h : hosts) handlers.put(h, dumper);

        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        printSummary("Session ready", session);

        try (MitmProxy proxy = new MitmProxy(0, ca, Set.of(), handlers)) {
            proxy.start();
            String proxyUrl = "http://127.0.0.1:" + proxy.port();
            Map<String, String> env = new java.util.HashMap<>(Map.of(
                    "HTTP_PROXY", proxyUrl, "HTTPS_PROXY", proxyUrl, "ALL_PROXY", proxyUrl,
                    "http_proxy", proxyUrl, "https_proxy", proxyUrl, "all_proxy", proxyUrl));
            env.putAll(caTrust.launchEnv(caDir.resolve("meridian-ca.crt"), caDir));

            System.out.println("Observing " + hosts + " — dumps → " + dumpDir);
            System.out.println("Reproduce the issue (e.g. open a second window), then quit the game.");
            Process process = new GameLauncher(install).launch(session, env, List.of());
            process.waitFor();
            System.out.println("Done. Decrypted exchanges saved under " + dumpDir);
        } finally {
            if (caInstalled && !hasFlag(args, "--keep-ca")) {
                try {
                    caTrust.uninstall(ca.caCertificate());
                    System.out.println("Removed the observe CA from the trust store.");
                } catch (Exception e) {
                    System.out.println("Could not remove the CA automatically: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Captures the build-bound server-browser parameters ({@code protocolVersion},
     * {@code clientSeed}) for the selected version by MITM-ing server-discovery while the game
     * runs, then caching them per version. This is the one-time "first launch" — afterwards
     * {@code servers} reads the list without launching the game. Open the in-game server
     * browser (including its random list) so the client actually issues the query.
     */
    private static void doCaptureParams(String[] args) throws Exception {
        HytaleInstall install = resolveInstall(args);
        String gameVersion = gameVersionOf(install);

        Path caDir = AppPaths.resolve("ca");
        CertificateAuthority ca = CertificateAuthority.loadOrCreate(caDir);
        CaTrust caTrust = CaTrust.forThisOs();
        boolean caInstalled = false;
        if (caTrust.installs() && !caTrust.isInstalled(ca.caCertificate())) {
            caTrust.install(caDir.resolve("meridian-ca.crt"));
            caInstalled = true;
            System.out.println("Trusted the capture CA — " + caTrust.describe() + ".");
        }

        ServerParamsStore store = ServerParamsStore.defaultStore();
        Map<String, ExchangeHandler> handlers = new java.util.HashMap<>();
        handlers.put("server-discovery.hytale.com", new ListingsParamCapture(store));

        GameSession session = acquireSession(SessionProvider.withDefaults(), args);

        try (MitmProxy proxy = new MitmProxy(0, ca, Set.of(), handlers)) {
            proxy.start();
            String proxyUrl = "http://127.0.0.1:" + proxy.port();
            Map<String, String> env = new java.util.HashMap<>(Map.of(
                    "HTTP_PROXY", proxyUrl, "HTTPS_PROXY", proxyUrl, "ALL_PROXY", proxyUrl,
                    "http_proxy", proxyUrl, "https_proxy", proxyUrl, "all_proxy", proxyUrl));
            env.putAll(caTrust.launchEnv(caDir.resolve("meridian-ca.crt"), caDir));
            System.out.println("Capturing server params for " + gameVersion
                    + " — open the in-game Servers browser (and its random list), then quit.");
            Process process = new GameLauncher(install).launch(session, env, List.of());
            process.waitFor();
        } finally {
            if (caInstalled && !hasFlag(args, "--keep-ca")) {
                try {
                    caTrust.uninstall(ca.caCertificate());
                    System.out.println("Removed the capture CA from the trust store.");
                } catch (Exception e) {
                    System.out.println("Could not remove the CA automatically: " + e.getMessage());
                }
            }
        }

        ServerParams params = store.get(gameVersion);
        if (params != null && params.isComplete()) {
            System.out.println("Cached params for " + gameVersion + ": protocolVersion="
                    + params.protocolVersion() + ", clientSeed=" + params.clientSeed());
            System.out.println("You can now run 'servers' without launching the game.");
        } else if (params != null) {
            System.out.println("Only partial params for " + gameVersion + " (protocolVersion="
                    + params.protocolVersion() + ", clientSeed=" + params.clientSeed()
                    + "). Open the RANDOM server list in-game to capture clientSeed too.");
        } else {
            System.out.println("No /servers/listings request seen — did the server browser open?"
                    + " (If server-discovery is certificate-pinned, capture can't work.)");
        }
    }

    /**
     * Reads the server browser directly with a minted token — no game launch. {@code --sort
     * featured|random|favorite} (default featured), {@code --offset N}. featured/random need
     * the version's captured {@link ServerParams} (run {@code capture-params} once first).
     */
    private static void doServers(String[] args) throws Exception {
        ServerDiscoveryClient.Sort sort = parseSort(argValue(args, "--sort"));

        ServerParamsStore store = ServerParamsStore.defaultStore();
        ServerParams params = null;
        if (sort != ServerDiscoveryClient.Sort.FAVORITE) {
            // --version selects ANY captured version (not just the installed one), so old
            // versions stay browsable. Without it, default to the current install's version.
            String version = argValue(args, "--version");
            if (version == null) {
                version = gameVersionOf(resolveInstall(args));
            }
            params = store.get(version);
            String missing = missingParam(sort, params);
            if (missing != null) {
                System.out.println("No cached " + missing + " for version " + version + ".");
                System.out.println("Cached versions: " + store.versions());
                System.out.println("Run 'capture-params' once (launches the game, records them).");
                return;
            }
        }

        GameSession session = acquireSession(SessionProvider.withDefaults(), args);
        List<ServerListing> listings = new ServerDiscoveryClient()
                .listAll(session.sessionToken, sort, params);

        System.out.println(sort + " servers (" + listings.size()
                + (params != null ? ", version " + params.version() : "") + "):");
        for (ServerListing s : listings) {
            System.out.printf("  %-28s %-22s fav:%-4d likes:%-4d %s%n",
                    truncate(s.name, 28), s.endpoint(), s.favorites, s.likes,
                    s.regions == null ? "" : String.join("/", s.regions));
        }
        if (listings.isEmpty()) {
            System.out.println("(empty — if this version's cached params are stale, re-run capture-params)");
        }
    }

    private static ServerDiscoveryClient.Sort parseSort(String s) {
        if (s == null) return ServerDiscoveryClient.Sort.FEATURED;
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "random" -> ServerDiscoveryClient.Sort.RANDOM;
            case "favorite", "favourite", "favorites", "favourites" -> ServerDiscoveryClient.Sort.FAVORITE;
            default -> ServerDiscoveryClient.Sort.FEATURED;
        };
    }

    /** The required param missing for this sort, or null when all needed ones are present. */
    private static String missingParam(ServerDiscoveryClient.Sort sort, ServerParams p) {
        if (p == null || p.protocolVersion() == null || p.protocolVersion().isBlank()) {
            return "protocolVersion";
        }
        if (sort == ServerDiscoveryClient.Sort.RANDOM && (p.clientSeed() == null || p.clientSeed().isBlank())) {
            return "clientSeed";
        }
        return null;
    }

    private static String gameVersionOf(HytaleInstall install) {
        String v = HytaleRoot.gameVersion(install.root, install.version);
        return v != null ? v : install.version;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static void doLogout(String[] args) {
        SessionProvider provider = SessionProvider.withDefaults();
        String id = argValue(args, "--account");
        List<Account> accounts = provider.accounts();
        if (accounts.isEmpty()) {
            System.out.println("No accounts to remove.");
            return;
        }
        Account target = id != null
                ? accounts.stream().filter(a -> id.equals(a.id) || id.equals(a.username))
                        .findFirst().orElse(null)
                : accounts.get(0);   // active / most recent
        if (target == null) {
            System.out.println("No matching account: " + id);
            return;
        }
        provider.removeAccount(target.id);
        System.out.println("Removed account " + target.displayName() + ".");
    }

    /** Opens the sign-in URL in a browser when possible, always printing it as a fallback. */
    private static Consumer<String> browserOpener() {
        return url -> {
            System.out.println("\n=== Hytale sign-in ===");
            System.out.println("If a browser does not open, visit this URL:\n" + url + "\n");
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ignored) {
                // The printed URL is the fallback.
            }
        };
    }

    private static void printSummary(String label, GameSession s) {
        System.out.println(label + ": " + s.profileUsername + " (" + s.profileUuid + ")");
        System.out.println("  session token:  " + redact(s.sessionToken));
        System.out.println("  identity token: " + redact(s.identityToken));
        System.out.println("  refresh token:  " + (s.refreshToken != null ? "stored" : "none"));
    }

    private static String redact(String token) {
        if (token == null || token.length() < 12) return "(short/none)";
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4)
                + " (len " + token.length() + ")";
    }

    private static String argValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) return true;
        }
        return false;
    }

    /**
     * Resolves which Hytale install to launch: {@code --hytale ROOT} + {@code --version NAME}
     * (default root = auto-detected, default version = the active one, else the first
     * installed), or a direct {@code --client PATH} to the executable.
     */
    private static HytaleInstall resolveInstall(String[] args) {
        String clientPath = argValue(args, "--client");
        if (clientPath != null) {
            HytaleInstall inst = HytaleInstall.fromExecutable(Path.of(clientPath));
            if (inst != null && inst.isRunnable()) return inst;
            throw new IllegalStateException("--client is not a runnable Hytale client: " + clientPath);
        }
        Optional<Path> root = HytaleRoot.locate(argValue(args, "--hytale"));
        if (root.isEmpty()) {
            throw new IllegalStateException("Could not find the Hytale folder. "
                    + "Pass --hytale <path> or set -Dmeridian.hytale=<path>.");
        }
        List<String> versions = HytaleRoot.versions(root.get());
        String version = argValue(args, "--version");
        if (version == null) {
            String active = HytaleRoot.activeVersion(root.get());
            version = (active != null && versions.contains(active)) ? active
                    : versions.isEmpty() ? null : versions.get(0);
        }
        if (version == null) {
            throw new IllegalStateException("No installed Hytale versions under " + root.get() + "/install");
        }
        HytaleInstall inst = HytaleInstall.of(root.get(), version);
        if (!inst.isRunnable()) {
            throw new IllegalStateException("Hytale version '" + version + "' has no runnable client. "
                    + "Installed: " + versions);
        }
        return inst;
    }

    /**
     * Acquires a session, honouring {@code --account <name|uuid>} when given. Without it,
     * the active (most recently used) account is used, and login runs only if there are
     * none. A stored valid token is reused rather than re-minted — see {@link SessionProvider}.
     */
    private static GameSession acquireSession(SessionProvider provider, String[] args)
            throws Exception {
        String account = argValue(args, "--account");
        if (account == null) {
            return provider.acquire(browserOpener());
        }
        String id = provider.accounts().stream()
                .filter(a -> account.equalsIgnoreCase(a.username) || account.equals(a.id))
                .map(a -> a.id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No stored account matches '"
                        + account + "'. Run 'accounts' to list them, or 'login' to add one."));
        return provider.acquire(id, browserOpener());
    }

    private LauncherMain() {
    }
}
