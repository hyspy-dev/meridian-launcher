package meridian.launcher.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry from the server-discovery listing response
 * ({@code GET /servers/listings}). Fields mirror the JSON the service returns; unknown or
 * absent members are simply left null by the JSON parser.
 *
 * <p>{@code serverType} and {@code regions} arrive as numeric codes (the service sends integers,
 * which Gson coerces into these String fields); {@link #typeName()} / {@link #regionNames()} map
 * them to display names, falling back to the raw value if a code is ever out of range.
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

    /** Single server-type code → name (index = code). */
    private static final String[] TYPES = {
            "Survival", "Adventure/RPG", "Creative", "PvP", "Minigames",
            "Roleplay", "Social", "Sandbox", "Other"};

    /** Region code → name (index = code). */
    private static final String[] REGIONS = {
            "NA East", "NA West", "South America", "EU West", "EU Central",
            "EU East", "Middle East", "Asia East", "Asia Southeast", "Oceania"};

    public String endpoint() {
        return (host == null ? "?" : host) + ":" + port;
    }

    /** The server type as a display name (e.g. {@code "3"} → "PvP"), or the raw value if unknown. */
    public String typeName() {
        Integer code = code(serverType);
        if (code != null && code >= 0 && code < TYPES.length) return TYPES[code];
        return serverType == null ? "" : serverType;
    }

    /** All regions as display names (e.g. {@code [4,0]} → ["EU Central", "NA East"]). */
    public List<String> regionNameList() {
        if (regions == null || regions.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(regions.size());
        for (String r : regions) {
            Integer code = code(r);
            out.add(code != null && code >= 0 && code < REGIONS.length ? REGIONS[code] : r);
        }
        return out;
    }

    /** Every region name joined by comma — for the detail view. */
    public String regionNames() {
        return String.join(", ", regionNameList());
    }

    /** Up to the first three region names, then "…" if there are more — for the compact column. */
    public String regionNamesShort() {
        List<String> names = regionNameList();
        if (names.size() <= 3) return String.join(", ", names);
        return String.join(", ", names.subList(0, 3)) + ", …";
    }

    /** Parses a region/type wire code, tolerating an integer that arrived as "3" or "3.0". */
    private static Integer code(String value) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }
}
