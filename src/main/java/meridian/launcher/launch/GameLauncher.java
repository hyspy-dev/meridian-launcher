package meridian.launcher.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import meridian.launcher.auth.GameSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the Hytale game client with a minted session in its environment.
 *
 * <p>The client reads its credentials from environment variables — the same ones the
 * proxy's snooper looks for on a running game ({@code HYTALE_SESSION_TOKEN} /
 * {@code HYTALE_IDENTITY_TOKEN}, plus the profile id). Setting them here is what makes
 * Meridian the launcher: the game comes up already signed in, and the proxy is handed the
 * matching session directly instead of reading it back out of the process.
 */
public final class GameLauncher {

    private static final Logger log = LoggerFactory.getLogger(GameLauncher.class);

    /** Environment variable the client reads its player session token from. */
    public static final String ENV_SESSION_TOKEN = "HYTALE_SESSION_TOKEN";
    /** Environment variable the client reads its identity token from. */
    public static final String ENV_IDENTITY_TOKEN = "HYTALE_IDENTITY_TOKEN";
    /** Environment variable the client reads the singleplayer offline token from (best-effort). */
    public static final String ENV_OFFLINE_TOKEN = "HYTALE_OFFLINE_TOKEN";

    private final HytaleInstall install;

    public GameLauncher(HytaleInstall install) {
        this.install = install;
    }

    /**
     * Launches the client with {@code session} in its environment and returns the process.
     * The caller owns the returned {@link Process} (wait on it, or let it outlive us).
     *
     * <p>Both halves of the launcher's contract are supplied: the session tokens in the
     * environment (which the client also passes down to the singleplayer server it spawns,
     * where they are read "from environment"), and the authenticated-launch arguments
     * ({@code --auth-mode authenticated --uuid --name} plus the install paths). Without
     * the arguments the client rejects the launch even with the tokens present.
     *
     * @param extraEnv  additional environment entries to set (e.g. telemetry or proxy
     *                  overrides once those are wired); may be empty
     * @param extraArgs additional command-line arguments appended after the standard ones
     */
    public Process launch(GameSession session, Map<String, String> extraEnv, java.util.List<String> extraArgs)
            throws IOException {
        if (!session.isUsable()) {
            throw new IllegalArgumentException("Refusing to launch: session has no usable tokens");
        }
        if (session.profileUuid == null || session.profileUsername == null) {
            throw new IllegalArgumentException("Refusing to launch: session has no profile uuid/name");
        }
        if (!install.isRunnable()) {
            throw new IOException("Client executable not found or not runnable: " + install.executable);
        }
        ensureExecutable(install.executable);

        // Note: --name MUST equal the identity token's username, or the client refuses to start
        // with "Identity token username mismatch" — the display name can't be substituted.
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(install.executable.toString());
        command.addAll(install.launchArgs(session.profileUuid, session.profileUsername));
        command.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        Path clientDir = install.executable.getParent();
        // (see ensureExecutable below — the client may arrive without its permission bits)
        pb.directory(clientDir != null ? clientDir.toFile() : null);
        pb.inheritIO();

        // The official launcher sets exactly these two; the profile identity travels as
        // the --uuid / --name arguments, not the environment.
        Map<String, String> env = pb.environment();
        env.put(ENV_SESSION_TOKEN, session.sessionToken);
        env.put(ENV_IDENTITY_TOKEN, session.identityToken);
        if (session.offlineToken != null && !session.offlineToken.isBlank()) {
            env.put(ENV_OFFLINE_TOKEN, session.offlineToken);
        }
        env.putAll(extraEnv);

        log.info("Launching {} as {} ({}), version {}",
                install.executable.getFileName(), session.profileUsername,
                session.profileUuid, install.version);
        return pb.start();
    }

    /**
     * Makes sure the client carries its execute bit on Unix. A tree can arrive without one —
     * unpacked by a tool that drops permissions, copied across filesystems, or installed by an
     * older build of this launcher, which restored file contents but not their modes. The
     * failure it causes ({@code Exec failed … Permission denied}) says nothing about the cause,
     * so the launcher repairs it instead of forwarding the riddle to the user.
     */
    private static void ensureExecutable(Path exe) {
        if (Files.isExecutable(exe)) return;
        var view = Files.getFileAttributeView(exe, java.nio.file.attribute.PosixFileAttributeView.class);
        if (view == null) return;   // Windows has no execute bit to set
        try {
            var perms = java.util.EnumSet.copyOf(Files.getPosixFilePermissions(exe));
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) {
                perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            }
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) {
                perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            }
            view.setPermissions(perms);
            log.info("Set the execute bit on {} (it was missing).", exe);
        } catch (Exception e) {
            log.warn("Could not set the execute bit on {}: {}", exe, e.toString());
        }
    }
}
