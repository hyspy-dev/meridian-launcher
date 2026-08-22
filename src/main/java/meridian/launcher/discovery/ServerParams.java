package meridian.launcher.discovery;

/**
 * The per-version parameters the server-browser query needs beyond the session token.
 *
 * <p>Both {@link #protocolVersion} and {@link #clientSeed} are build-bound and validated by
 * the discovery service — with a wrong (or generated) value the listing comes back empty:
 * <ul>
 *   <li>{@code protocolVersion} is the client's wire-protocol CRC
 *       ({@code ProtocolSettings.PROTOCOL_CRC}, zero-extended to uint32 — the same value the
 *       dedicated server registers with in its discovery heartbeat); the service filters
 *       listings by matching {@code patchline}+{@code version}+{@code protocolVersion}.</li>
 *   <li>{@code clientSeed} is the per-build client seed; the service checks it too, so it
 *       cannot be faked — an arbitrary seed yields an empty list.</li>
 * </ul>
 * Both are computed by the client at runtime and are not readable from any install file, so
 * we capture them the first time the game runs a given version and cache them here.
 *
 * <p>{@link #version} (e.g. {@code 0.5.9}) and {@link #patchline} (e.g. {@code release}) are
 * already known from the install's {@code env.dat} / patchline.
 */
public record ServerParams(String version, String patchline, String protocolVersion, String clientSeed) {

    /** True only when both build-bound values are present, i.e. the query can succeed. */
    public boolean isComplete() {
        return protocolVersion != null && !protocolVersion.isBlank()
                && clientSeed != null && !clientSeed.isBlank();
    }
}
