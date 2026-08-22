package meridian.launcher.discovery;

import meridian.launcher.mitm.ExchangeHandler;
import meridian.launcher.mitm.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A pass-through MITM handler for {@code server-discovery.hytale.com} that watches the game's
 * own {@code GET /servers/listings} requests and records the build-bound query parameters
 * ({@code protocolVersion}, {@code clientSeed}) per version into a {@link ServerParamsStore}.
 *
 * <p>This is how "capture on first launch, then cache" works: the values are computed by the
 * client at runtime, so the only place to read them is off the wire. The response is relayed
 * unchanged — the game is unaffected.
 *
 * <p>Requests are merged: the {@code featured} query carries {@code protocolVersion} but no
 * {@code clientSeed}; the {@code random} query carries both. So whichever the game issues, we
 * keep the most complete union seen for that version.
 */
public final class ListingsParamCapture implements ExchangeHandler {

    private static final Logger log = LoggerFactory.getLogger(ListingsParamCapture.class);

    private final ServerParamsStore store;

    public ListingsParamCapture(ServerParamsStore store) {
        this.store = store;
    }

    @Override
    public byte[] handle(HttpExchange ex) {
        try {
            if (ex.path() != null && ex.path().startsWith("/servers/listings")) {
                capture(ex.path());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to capture server params from {}: {}", ex.path(), e.toString());
        }
        return ex.responseBody();   // always relay unchanged
    }

    private void capture(String pathWithQuery) {
        Map<String, String> q = parseQuery(pathWithQuery);
        String version = q.get("version");
        String protocolVersion = q.get("protocolVersion");
        String clientSeed = q.get("clientSeed");
        String patchline = q.get("patchline");
        if (version == null || version.isBlank()) {
            return;
        }

        // Merge with anything already known for this version so featured + random combine.
        ServerParams prev = store.get(version);
        String mergedProtocol = firstNonBlank(protocolVersion, prev == null ? null : prev.protocolVersion());
        String mergedSeed = firstNonBlank(clientSeed, prev == null ? null : prev.clientSeed());
        String mergedPatchline = firstNonBlank(patchline, prev == null ? null : prev.patchline());

        ServerParams merged = new ServerParams(version, mergedPatchline, mergedProtocol, mergedSeed);
        if (prev == null || !prev.equals(merged)) {
            store.put(merged);
            log.info("Captured server params for {}: protocolVersion={}, clientSeed={}{}",
                    version, mergedProtocol, mergedSeed,
                    merged.isComplete() ? "" : " (incomplete — browse the random list to get clientSeed)");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }

    private static Map<String, String> parseQuery(String pathWithQuery) {
        Map<String, String> out = new HashMap<>();
        int qm = pathWithQuery.indexOf('?');
        if (qm < 0 || qm == pathWithQuery.length() - 1) {
            return out;
        }
        for (String pair : pathWithQuery.substring(qm + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(key, val);
        }
        return out;
    }
}
