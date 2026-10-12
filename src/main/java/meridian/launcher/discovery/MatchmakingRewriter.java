package meridian.launcher.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import meridian.launcher.mitm.ExchangeHandler;
import meridian.launcher.mitm.HttpExchange;
import meridian.launcher.mitm.WebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sends the minigame servers the game is matched to through the local proxy, the way
 * {@link ServerDiscoveryRewriter} does for the server browser.
 *
 * <p>A match reaches the game two ways, in two shapes:
 * <ul>
 *   <li>polled - {@code POST matchmaking.hytale.com/tickets}, then {@code GET /tickets/<id>}: the
 *       ticket names its {@code gameType} and {@code ticketId}, and once matched carries
 *       {@code assignment: {address, port, matchId, joinDeadline}};</li>
 *   <li>pushed - a {@code matchmaking.ticket.updated} notification down the
 *       {@code socket-gateway.hytale.com} WebSocket, the moment it is assigned:
 *       {@code {ticket_id, status, match_id, address, port, join_deadline}}, flat and with no
 *       game type. The game connects on the push and does not poll again.</li>
 * </ul>
 * Either way the match is an object holding an {@code address}, a {@code port} and a match id;
 * those two are rewritten to {@code 127.0.0.1:<localPort>} and the real address is routed
 * through a {@link RouteRegistry}. The same server always maps to the same local port, so a
 * match seen twice is routed once. The object is looked for anywhere in the message - nested
 * objects and arrays, and JSON carried inside a string; a message with none passes untouched.
 *
 * <p>A matchmade server is a new address every match, so its modules are not looked up by
 * address - that would be a new module folder per match - but by game type:
 * {@code matchmaking_<gameType>}, one folder per minigame. The push does not say the game type,
 * so it is remembered per ticket from the matchmaking responses, which come first.
 */
public final class MatchmakingRewriter implements ExchangeHandler, WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingRewriter.class);

    private final RouteRegistry routes;
    private final Consumer<String> console;
    /** Servers already announced on the console - a ticket is seen once per poll and per push. */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();
    /** Ticket id → game type, from the matchmaking responses; the push names only the ticket. */
    private final Map<String, String> gameTypes = new ConcurrentHashMap<>();

    public MatchmakingRewriter(RouteRegistry routes) {
        this(routes, null);
    }

    /** @param console where to say that a match was routed (the launcher console), or null */
    public MatchmakingRewriter(RouteRegistry routes, Consumer<String> console) {
        this.routes = routes;
        this.console = console;
    }

    /** A matchmaking HTTP response: the ticket, polled. */
    @Override
    public byte[] handle(HttpExchange ex) {
        byte[] body = ex.responseBody();
        if (body == null || body.length == 0) {
            return body;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String out = rewrite(text);
        return out == text ? body : out.getBytes(StandardCharsets.UTF_8);
    }

    /** A message pushed down the socket gateway: the ticket, the moment it is assigned. */
    @Override
    public String fromServer(String message) {
        return rewrite(message);
    }

    /** The message with every match in it pointed at the proxy - the same string if none. */
    String rewrite(String text) {
        if (text == null || !(text.contains("address") || text.contains("ameType")
                || text.contains("game_type"))) {
            // Cheap: most traffic on both hosts names neither a server nor a game type. A ticket
            // that only names its game type is still read - the push for it will not say it.
            return text;
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            return redirectAll(root, null) > 0 ? root.toString() : text;
        } catch (RuntimeException e) {
            log.warn("Matchmaking rewrite failed: {} — passed on unchanged", e.toString());
            return text;
        }
    }

    /**
     * Redirects every match under {@code e}; returns how many. {@code gameType} is the nearest
     * one known from an enclosing object - a polled ticket names it beside its assignment.
     */
    private int redirectAll(JsonElement e, String gameType) {
        int count = 0;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String type = gameTypeOf(o, gameType);
            if (redirect(o, type)) count++;
            for (Map.Entry<String, JsonElement> child : o.entrySet()) {
                JsonElement v = child.getValue();
                if (isEmbeddedJson(v)) {
                    String inner = rewrite(v.getAsString());
                    if (!inner.equals(v.getAsString())) {
                        child.setValue(new JsonPrimitive(inner));
                        count++;
                    }
                } else {
                    count += redirectAll(v, type);
                }
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement v = a.get(i);
                if (isEmbeddedJson(v)) {
                    String inner = rewrite(v.getAsString());
                    if (!inner.equals(v.getAsString())) {
                        a.set(i, new JsonPrimitive(inner));
                        count++;
                    }
                } else {
                    count += redirectAll(v, gameType);
                }
            }
        }
        return count;
    }

    /**
     * The game type an object speaks for: its own {@code gameType} (remembered against its ticket
     * id, for the push that will not say it), else the one remembered for its ticket id, else the
     * one inherited from around it.
     */
    private String gameTypeOf(JsonObject o, String inherited) {
        String ticket = string(o, "ticketId");
        if (ticket == null) ticket = string(o, "ticket_id");
        String own = string(o, "gameType");
        if (own == null) own = string(o, "game_type");
        if (own != null) {
            if (ticket != null) gameTypes.put(ticket, own);
            return own;
        }
        if (ticket != null && gameTypes.containsKey(ticket)) return gameTypes.get(ticket);
        return inherited;
    }

    /** A string value that is itself a JSON document naming a server. */
    private static boolean isEmbeddedJson(JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return false;
        String s = v.getAsString().trim();
        return s.startsWith("{") && s.contains("address");
    }

    /**
     * Points a match at the local proxy: an object with an {@code address}, a numeric
     * {@code port} and a match id - the polled ticket's {@code assignment} and the pushed update
     * alike. False when {@code o} is not one.
     */
    private boolean redirect(JsonObject o, String gameType) {
        if (string(o, "matchId") == null && string(o, "match_id") == null) {
            return false;
        }
        String host = string(o, "address");
        JsonElement p = o.get("port");
        if (host == null || host.isBlank() || host.equals("127.0.0.1")
                || p == null || !p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        int port = p.getAsInt();
        String scope = scopeFor(gameType);
        int local = routes.localPortFor(host, port, scope);
        o.addProperty("address", "127.0.0.1");
        o.addProperty("port", local);
        log.info("Matched {}:{} ({}) redirected to the local proxy on {}", host, port, scope, local);
        if (console != null && announced.add(host + ":" + port)) {
            console.accept("Minigame match " + host + ":" + port + " goes through the proxy (127.0.0.1:"
                    + local + ", modules from " + scope + "/modules).");
        }
        return true;
    }

    /** {@code matchmaking_<gameType>}, kept to what is safe as a folder name. */
    static String scopeFor(String gameType) {
        String type = gameType == null ? "" : gameType;
        String safe = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("(^_+|_+$)", "");
        return "matchmaking_" + (safe.isEmpty() ? "game" : safe);
    }

    /** A string member of {@code o}, or null when absent or not a string. */
    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }
}
