package meridian.launcher.lookup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Read-only profile lookups against Hytale's Profile Service, for the Tools tab. Mirrors the game's
 * own {@code ProfileServiceClient}: {@code GET account-data.hytale.com/profile/{uuid|username}/…}
 * with a Bearer session/identity token (scope {@code hytale:client}); a hit returns
 * {@code {uuid, username, skin}} (we read uuid + username), a miss is HTTP 404.
 *
 * <p>This doubles as a "username taken?" check: a name that resolves to a profile is in use, a 404
 * means no player profile uses it. (The website's {@code username-reservations/availability}
 * endpoint would give exact reservation status, but it is gated behind the accounts.hytale.com
 * <em>web session cookie</em> — a Bearer token just gets a 302 to /login — so it isn't reachable
 * from the launcher.)
 *
 * <p>Cloudflare-fronted and shared with the official launcher, so heavy use can rate-limit
 * (429/1015) — fine for the occasional manual check the Tools tab does.
 */
public final class AccountLookupClient {

    private static final String ACCOUNT_DATA = "https://account-data.hytale.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** A public game profile ({@code uuid}/{@code username}), plus the raw body for diagnostics. */
    public record Profile(int status, String uuid, String username, String raw) {
        public boolean found() {
            return status == 200 && (uuid != null || username != null);
        }
    }

    public Profile byUsername(String username, String bearer)
            throws IOException, InterruptedException {
        return get(ACCOUNT_DATA + "/profile/username/"
                + URLEncoder.encode(username, StandardCharsets.UTF_8), bearer);
    }

    public Profile byUuid(String uuid, String bearer)
            throws IOException, InterruptedException {
        return get(ACCOUNT_DATA + "/profile/uuid/"
                + URLEncoder.encode(uuid, StandardCharsets.UTF_8), bearer);
    }

    private Profile get(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", bearer.startsWith("Bearer ") ? bearer : "Bearer " + bearer)
                .header("User-Agent", "Go-http-client/1.1")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        String uuid = null, username = null;
        try {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            if (o.has("uuid") && !o.get("uuid").isJsonNull()) uuid = o.get("uuid").getAsString();
            if (o.has("username") && !o.get("username").isJsonNull()) username = o.get("username").getAsString();
        } catch (Exception ignored) {
            // non-JSON (e.g. 404 "game profile not found") — leave null; caller shows status/raw
        }
        return new Profile(res.statusCode(), uuid, username, res.body());
    }
}
