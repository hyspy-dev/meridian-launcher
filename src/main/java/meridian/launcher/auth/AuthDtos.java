package meridian.launcher.auth;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Wire shapes for the account/session JSON responses. Field names match the backend. */
final class AuthDtos {

    private AuthDtos() {
    }

    /** OAuth2 token endpoint response. */
    static final class TokenResponse {
        @SerializedName("access_token")
        String accessToken;
        @SerializedName("refresh_token")
        String refreshToken;
        @SerializedName("expires_in")
        Integer expiresIn;
        @SerializedName("token_type")
        String tokenType;
    }

    /** One playable profile on the account. */
    static final class GameProfile {
        String uuid;
        String username;
    }

    /** {@code get-launcher-data} response — carries the profile list. */
    static final class LauncherData {
        List<GameProfile> profiles;
    }
}
