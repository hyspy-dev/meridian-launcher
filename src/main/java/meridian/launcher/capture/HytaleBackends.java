package meridian.launcher.capture;

import java.util.Set;

/**
 * The Hytale client's backend hosts, as observed via the capture proxy. Grouped by what
 * they do, so features can act on the right ones.
 */
public final class HytaleBackends {

    /** Telemetry / crash reporting — safe to block for a telemetry opt-out. */
    public static final Set<String> TELEMETRY = Set.of(
            "telemetry.hytale.com",
            "sentry.hytale.com");

    /** Server browser / discovery — the host to intercept for a community server list. */
    public static final String SERVER_DISCOVERY = "server-discovery.hytale.com";

    /** Mod browser — the host to intercept for a custom mod list. */
    public static final String MOD_BROWSER = "mod-browser.hytale.com";

    private HytaleBackends() {
    }
}
