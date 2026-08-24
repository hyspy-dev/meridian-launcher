package meridian.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Hytale account flow: interactive OAuth2 login and non-interactive refresh, each
 * ending in a minted {@link GameSession}. A faithful Java port of the reference Rust
 * launcher client (hytale-monitor) — same endpoints, parameters, and header quirks.
 *
 * <p>The flow, both paths sharing steps 3–4:
 * <ol>
 *   <li><b>login</b>: OAuth2 authorization-code + PKCE over a loopback redirect →
 *       access + refresh token; or</li>
 *   <li><b>refresh</b>: the stored refresh token → access + refresh token;</li>
 *   <li>{@code get-launcher-data} → the account's first profile;</li>
 *   <li>{@code game-session/new} → session + identity tokens.</li>
 * </ol>
 */
public final class HytaleAuth {

    private static final Logger log = LoggerFactory.getLogger(HytaleAuth.class);
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** How long to wait for the user to complete sign-in in the browser. */
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

    /** An OAuth token endpoint result: the access token to use now, the refresh to keep. */
    private record Tokens(String accessToken, String refreshToken) {}

    /** One playable profile on an account: a distinct in-game identity. */
    public record Profile(String uuid, String username) {}

    /** Profiles plus the (possibly rotated) refresh token that MUST be persisted after the call. */
    public record ProfileList(List<Profile> profiles, String refreshToken) {}

    /** An access token for the account-data/game-assets APIs, plus the (rotated) refresh to persist. */
    public record Access(String accessToken, String refreshToken) {}

    /**
     * Refreshes an access token for the download/update API (patch-set, game-assets) without
     * minting a game session, so it does not disturb a running one. Like {@link #listProfiles},
     * refreshing rotates the refresh token: the caller MUST persist {@link Access#refreshToken()}.
     */
    public Access access(String refreshToken) throws IOException, InterruptedException {
        Tokens t = accessTokenFrom(refreshToken);
        return new Access(t.accessToken(), t.refreshToken());
    }

    /**
     * Lists every profile on the account (each a distinct {@code {uuid, username}}), by
     * refreshing an access token and calling get-launcher-data. Does NOT mint a game
     * session, so it does not disturb a running one. This is the data a profile picker
     * needs; the launch path mints game-session/new for the chosen profile's uuid.
     *
     * <p><b>Important:</b> refreshing rotates the refresh token server-side (the old one is
     * invalidated). The caller MUST store {@link ProfileList#refreshToken()} back on the
     * account, or the next refresh/mint fails with {@code invalid_grant}.
     */
    public ProfileList listProfiles(String refreshToken) throws IOException, InterruptedException {
        Tokens tokens = accessTokenFrom(refreshToken);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.ACCOUNT_DATA_BASE
                                + "/my-account/get-launcher-data?arch=amd64&os=windows"))
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("User-Agent", HytaleEndpoints.USER_AGENT)
                .GET());
        requireSuccess(resp, "fetch launcher data");
        AuthDtos.LauncherData data = GSON.fromJson(resp.body(), AuthDtos.LauncherData.class);
        List<Profile> profiles = (data == null || data.profiles == null) ? List.of()
                : data.profiles.stream().map(p -> new Profile(p.uuid, p.username)).toList();
        return new ProfileList(profiles, tokens.refreshToken());
    }

    /**
     * Mints a game session for a specific profile of the account (by uuid), rather than
     * always the first. Used when the user picks a profile in the launcher. Refreshes an
     * access token, then game-session/new for that uuid.
     */
    public GameSession mintForProfile(String refreshToken, String uuid, String username)
            throws IOException, InterruptedException {
        Tokens tokens = accessTokenFrom(refreshToken);
        return mintGameSession(tokens.accessToken(), uuid, username, tokens.refreshToken());
    }

    /**
     * Runs the interactive login. Opens the authorize URL through {@code browserOpener}
     * (so a GUI can hand it to the desktop, or a CLI can print it) and blocks until the
     * browser redirect arrives or {@link #LOGIN_TIMEOUT} elapses.
     */
    public GameSession login(Consumer<String> browserOpener) throws IOException, InterruptedException {
        Pkce pkce = Pkce.generate();

        try (LoopbackReceiver receiver = new LoopbackReceiver()) {
            int port = receiver.port();

            // The consent page redirects back to our loopback using the port carried in
            // `state`, so the OAuth state is base64url({"state": nonce, "port": port}).
            JsonObject stateObj = new JsonObject();
            stateObj.addProperty("state", pkce.state);
            stateObj.addProperty("port", Integer.toString(port));
            String encodedState = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(stateObj.toString().getBytes(StandardCharsets.UTF_8));

            String authUrl = HytaleEndpoints.OAUTH_BASE + "/oauth2/auth"
                    + "?response_type=code"
                    + "&client_id=" + HytaleEndpoints.CLIENT_ID
                    + "&redirect_uri=" + enc(HytaleEndpoints.REDIRECT_URI)
                    + "&scope=" + enc(HytaleEndpoints.SCOPE)
                    + "&state=" + encodedState
                    + "&code_challenge=" + pkce.challenge
                    + "&code_challenge_method=S256";

            log.info("Opening Hytale sign-in; waiting for callback on 127.0.0.1:{}", port);
            browserOpener.accept(authUrl);

            Map<String, String> callback = receiver.awaitCallback(LOGIN_TIMEOUT);

            if (callback.containsKey("error")) {
                throw new IOException("Sign-in failed: " + callback.get("error")
                        + " — " + callback.getOrDefault("error_description", ""));
            }
            String code = callback.get("code");
            String returnedState = callback.get("state");
            if (code == null || code.isBlank()) {
                throw new IOException("Sign-in returned no authorization code.");
            }
            // The consent page unwraps the JSON state and echoes back only the inner nonce.
            if (!pkce.state.equals(returnedState)) {
                throw new IOException("Sign-in state mismatch — possible CSRF; aborting.");
            }

            Tokens tokens = exchangeCodeForTokens(code, pkce.verifier);
            return mintSession(tokens);
        }
    }

    /**
     * A lightweight liveness check: is this session token still accepted by the backend?
     *
     * <p>Hytale keeps one active session per account, so a token can be JWT-valid yet dead
     * server-side (superseded by a later mint). Only a confirmed {@code 200} counts as live;
     * an auth rejection ({@code 401}/{@code 403}) is dead, and any other outcome (network
     * hiccup, 5xx) returns false so the caller mints a guaranteed-fresh token rather than
     * risk handing the game a dead one. Uses an authenticated GET with no side effects and
     * no version-specific parameters (the favourites endpoint the client itself calls).
     */
    public boolean isSessionLive(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return false;
        try {
            HttpResponse<Void> resp = http.send(HttpRequest.newBuilder(
                            URI.create("https://server-discovery.hytale.com/me/interactions/favorite?offset=0"))
                    .header("Authorization", "Bearer " + sessionToken)
                    .header("User-Agent", HytaleEndpoints.USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.info("Session liveness check failed ({}); will mint fresh.", e.toString());
            return false;
        }
    }

    /**
     * Renews a session from a stored refresh token, no browser needed. Returns a fresh
     * {@link GameSession} carrying the (possibly rotated) refresh token to persist.
     */
    public GameSession refresh(String refreshToken) throws IOException, InterruptedException {
        return mintSession(accessTokenFrom(refreshToken));
    }

    // --- shared steps -----------------------------------------------------------------

    /** Exchanges a refresh token for a fresh access token (and the rotated refresh). */
    private Tokens accessTokenFrom(String refreshToken) throws IOException, InterruptedException {
        String body = "grant_type=refresh_token"
                + "&client_id=" + HytaleEndpoints.CLIENT_ID
                + "&refresh_token=" + enc(refreshToken);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.OAUTH_BASE + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        requireSuccess(resp, "refresh access token");
        AuthDtos.TokenResponse token = GSON.fromJson(resp.body(), AuthDtos.TokenResponse.class);
        // Providers may or may not rotate the refresh token; keep the old one if not.
        String effectiveRefresh = token.refreshToken != null ? token.refreshToken : refreshToken;
        return new Tokens(token.accessToken, effectiveRefresh);
    }

    private Tokens exchangeCodeForTokens(String code, String codeVerifier)
            throws IOException, InterruptedException {
        // This client authenticates to the token endpoint with HTTP Basic (id, empty secret).
        String basic = Base64.getEncoder().encodeToString(
                (HytaleEndpoints.CLIENT_ID + ":").getBytes(StandardCharsets.UTF_8));
        String body = "grant_type=authorization_code"
                + "&client_id=" + HytaleEndpoints.CLIENT_ID
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(HytaleEndpoints.REDIRECT_URI)
                + "&code_verifier=" + enc(codeVerifier);

        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.OAUTH_BASE + "/oauth2/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        requireSuccess(resp, "exchange authorization code");

        AuthDtos.TokenResponse token = GSON.fromJson(resp.body(), AuthDtos.TokenResponse.class);
        return new Tokens(token.accessToken, token.refreshToken);
    }

    /** get-launcher-data → first profile → game-session/new. */
    private GameSession mintSession(Tokens tokens) throws IOException, InterruptedException {
        AuthDtos.GameProfile profile = fetchFirstProfile(tokens.accessToken());
        return mintGameSession(tokens.accessToken(), profile.uuid, profile.username, tokens.refreshToken());
    }

    /** game-session/new for one profile uuid, into a {@link GameSession}. */
    private GameSession mintGameSession(String accessToken, String uuid, String username,
                                        String refreshToken) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.SESSIONS_BASE + "/game-session/new"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        requireSuccess(resp, "create game session");

        GameSession session = GSON.fromJson(resp.body(), GameSession.class);
        session.profileUuid = uuid;
        session.profileUsername = username;
        // game-session/new does not echo the refresh token; carry it from the OAuth step.
        session.refreshToken = refreshToken;
        if (!session.isUsable()) {
            throw new IOException("game-session/new returned no usable tokens");
        }
        // Best-effort: also mint the singleplayer offline token, for HYTALE_OFFLINE_TOKEN.
        try {
            session.offlineToken = offlineToken(accessToken, uuid);
        } catch (Exception e) {
            log.info("Offline token unavailable for {}: {}", username, e.toString());
        }
        log.info("Minted session for {} ({})", username, uuid);
        return session;
    }

    /** Mints the singleplayer offline token for a profile uuid, or null if unavailable. */
    private String offlineToken(String accessToken, String uuid) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.SESSIONS_BASE + "/game-session/offline"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (resp.statusCode() / 100 != 2) {
            return null;
        }
        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
        JsonObject tokens = json != null ? json.getAsJsonObject("offlineTokens") : null;
        return (tokens != null && tokens.has(uuid)) ? tokens.get(uuid).getAsString() : null;
    }

    private AuthDtos.GameProfile fetchFirstProfile(String accessToken)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(HytaleEndpoints.ACCOUNT_DATA_BASE
                                + "/my-account/get-launcher-data?arch=amd64&os=windows"))
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", HytaleEndpoints.USER_AGENT)
                .GET());
        requireSuccess(resp, "fetch launcher data");

        AuthDtos.LauncherData data = GSON.fromJson(resp.body(), AuthDtos.LauncherData.class);
        if (data == null || data.profiles == null || data.profiles.isEmpty()) {
            throw new IOException("No game profiles on this account.");
        }
        return data.profiles.get(0);
    }

    // --- helpers ----------------------------------------------------------------------

    private HttpResponse<String> send(HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        HttpRequest request = builder
                .header("User-Agent", HytaleEndpoints.USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void requireSuccess(HttpResponse<String> resp, String what) throws IOException {
        if (resp.statusCode() / 100 != 2) {
            String body = resp.body();
            String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
            throw new IOException("Failed to " + what + " (HTTP " + resp.statusCode() + "): " + snippet);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
