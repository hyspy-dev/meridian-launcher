package meridian.launcher.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import meridian.launcher.mitm.ExchangeHandler;
import meridian.launcher.mitm.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * The server-discovery MITM handler used while proxy mode is on. It does two things on every
 * intercepted response:
 * <ol>
 *   <li>captures the build-bound params ({@code protocolVersion}/{@code clientSeed}) from the
 *       request, via {@link ListingsParamCapture} — so the version becomes browsable; and</li>
 *   <li>rewrites the listing so every server's {@code host}/{@code port} points at the local
 *       proxy ({@code 127.0.0.1:<localPort>}), registering {@code localPort → realHost:realPort}
 *       in a {@link RouteRegistry}. The game then Direct-Connects to loopback and its gameplay
 *       UDP flows through the proxy, which relays to the real server.</li>
 * </ol>
 *
 * <p>The body is parsed as generic JSON (not a typed model) so unknown fields survive: only
 * {@code host}/{@code port} of each array element are touched. Non-JSON or non-array bodies are
 * relayed unchanged.
 */
public final class ServerDiscoveryRewriter implements ExchangeHandler {

    private static final Logger log = LoggerFactory.getLogger(ServerDiscoveryRewriter.class);

    private final ListingsParamCapture capture;
    private final RouteRegistry routes;

    public ServerDiscoveryRewriter(ServerParamsStore store, RouteRegistry routes) {
        this.capture = new ListingsParamCapture(store);
        this.routes = routes;
    }

    @Override
    public byte[] handle(HttpExchange ex) {
        // First capture params (side effect) and get the untouched response body back.
        byte[] body = capture.handle(ex);
        if (body == null || body.length == 0) {
            return body;
        }
        try {
            JsonElement root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                return body;   // e.g. an object/error — nothing to redirect
            }
            int rewritten = redirectArray(root.getAsJsonArray());
            if (rewritten == 0) {
                return body;
            }
            log.info("Redirected {} server(s) in {} to the local proxy", rewritten, ex.path());
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.warn("Listing rewrite failed for {}: {} — relaying unchanged", ex.path(), e.toString());
            return body;
        }
    }

    /** Rewrites each server object's host/port to the local proxy; returns how many changed. */
    private int redirectArray(JsonArray arr) {
        int count = 0;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("host") || !o.has("port")
                    || !o.get("host").isJsonPrimitive() || !o.get("port").isJsonPrimitive()) {
                continue;
            }
            String host = o.get("host").getAsString();
            if (host == null || host.isBlank() || host.equals("127.0.0.1")) {
                continue;   // already loopback / nothing to map
            }
            int port = o.get("port").getAsInt();
            int local = routes.localPortFor(host, port);
            o.addProperty("host", "127.0.0.1");
            o.addProperty("port", local);
            count++;
        }
        return count;
    }
}
