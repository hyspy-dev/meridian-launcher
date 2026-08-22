package meridian.launcher.discovery;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Reads the Hytale server browser directly, using a minted session token — no running game
 * required. Mirrors what the client does:
 * <ul>
 *   <li>{@code favorite} — {@code GET /me/interactions/favorite} (token only)</li>
 *   <li>{@code featured} — {@code GET /servers/listings?sort=featured} (+ version/protocol)</li>
 *   <li>{@code random}   — {@code GET /servers/listings?sort=random} (+ version/protocol/clientSeed)</li>
 * </ul>
 *
 * <p>{@code featured}/{@code random} need the build-bound {@link ServerParams}; the discovery
 * service returns an empty list if {@code protocolVersion}/{@code clientSeed} don't match the
 * build, which is why those are captured per version rather than guessed.
 */
public final class ServerDiscoveryClient {

    /** How the listing is ordered/selected server-side. */
    public enum Sort { FEATURED, RANDOM, FAVORITE }

    private static final String BASE = "https://server-discovery.hytale.com";
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Hard cap on pages walked, so a server that never returns an empty page can't loop us. */
    private static final int MAX_PAGES = 200;

    /**
     * Fetches the <em>entire</em> list by walking {@code offset} until the service stops
     * returning new entries — the way the game's browser pages through it. Deduplicates by
     * uuid, so a wrap-around or a repeated tail page ends the walk instead of duplicating.
     */
    public List<ServerListing> listAll(String sessionToken, Sort sort, ServerParams params)
            throws IOException, InterruptedException {
        List<ServerListing> all = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ServerListing> chunk = list(sessionToken, sort, params, offset);
            if (chunk.isEmpty()) {
                break;
            }
            int added = 0;
            for (ServerListing s : chunk) {
                if (s.uuid == null || seen.add(s.uuid)) {
                    all.add(s);
                    added++;
                }
            }
            offset += chunk.size();
            if (added == 0) {   // nothing new on this page → exhausted (or the service wrapped)
                break;
            }
        }
        return all;
    }

    /**
     * Fetches one page of the server list.
     *
     * @param sessionToken the minted session token (scope {@code hytale:client})
     * @param sort         which list to read
     * @param params       build-bound parameters for the version (ignored for {@link Sort#FAVORITE})
     * @param offset       pagination offset
     */
    public List<ServerListing> list(String sessionToken, Sort sort, ServerParams params, int offset)
            throws IOException, InterruptedException {
        String url = switch (sort) {
            case FAVORITE -> BASE + "/me/interactions/favorite?offset=" + offset;
            case FEATURED -> BASE + "/servers/listings?offset=" + offset + "&sort=featured"
                    + "&patchline=" + enc(params.patchline())
                    + "&version=" + enc(params.version())
                    + "&protocolVersion=" + enc(params.protocolVersion());
            case RANDOM -> BASE + "/servers/listings?offset=" + offset + "&sort=random"
                    + "&clientSeed=" + enc(params.clientSeed())
                    + "&patchline=" + enc(params.patchline())
                    + "&version=" + enc(params.version())
                    + "&protocolVersion=" + enc(params.protocolVersion());
        };

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", bearer(sessionToken))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("server-discovery " + sort + " returned HTTP "
                    + response.statusCode() + ": " + trim(response.body()));
        }
        List<ServerListing> listings = GSON.fromJson(
                response.body(), new TypeToken<List<ServerListing>>() {}.getType());
        return listings != null ? listings : List.of();
    }

    private static String bearer(String token) {
        return token.startsWith("Bearer ") ? token : "Bearer " + token;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String trim(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
