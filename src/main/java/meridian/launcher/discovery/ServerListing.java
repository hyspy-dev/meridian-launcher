package meridian.launcher.discovery;

import java.util.List;

/**
 * One entry from the server-discovery listing response
 * ({@code GET /servers/listings}). Fields mirror the JSON the service returns; unknown or
 * absent members are simply left null by the JSON parser.
 */
public final class ServerListing {
    public String uuid;
    public String name;
    public String description;
    public String host;
    public int port;
    public String audience;
    public String serverType;
    public String ownerProfileId;
    public String createdAt;
    public int likes;
    public int favorites;
    public boolean isLiked;
    public boolean isFavorited;
    public List<String> regions;

    public String endpoint() {
        return (host == null ? "?" : host) + ":" + port;
    }
}
