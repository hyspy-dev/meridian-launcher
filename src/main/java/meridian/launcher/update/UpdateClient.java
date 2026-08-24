package meridian.launcher.update;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import meridian.launcher.auth.HytaleEndpoints;

/**
 * The Hytale game-update HTTP API: fetch the delta chain for the current build and download the
 * {@code .pwr}/{@code .pwr.sig} artifacts. The server hands back ready, pre-signed CDN URLs — the
 * client never constructs them — so downloads carry no Bearer, only the fetch of the chain does.
 *
 * <p>The patch endpoint returns the ordered steps to bring {@code fromBuild} up to the newest
 * build: a real chain {@code (from→from+1, …)} when behind, or a single sentinel step whose
 * {@code from} is 0 when already current.
 */
public final class UpdateClient {

    private static final Logger log = LoggerFactory.getLogger(UpdateClient.class);
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One delta step: apply the patch at {@code pwrUrl} to build {@code from}, yielding {@code to}. */
    public record PatchStep(int from, int to, String pwrUrl, String sigUrl) {}

    /** Reports bytes received against the total (from {@code Content-Length}; -1 if unknown). */
    public interface DownloadProgress {
        void onBytes(long done, long total);
    }

    /**
     * The delta chain to update {@code fromBuild} to the newest build, in apply order. Empty (or a
     * single {@code from==0} sentinel) means already current. Requires a Bearer access token.
     */
    public List<PatchStep> patchSet(String accessToken, String os, String arch, String channel, int fromBuild)
            throws IOException, InterruptedException {
        String url = HytaleEndpoints.ACCOUNT_DATA_BASE
                + "/patches/" + os + "/" + arch + "/" + channel + "/" + fromBuild;
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", HytaleEndpoints.USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("patch-set fetch failed (HTTP " + resp.statusCode() + ") for build " + fromBuild);
        }
        return parseSteps(resp.body());
    }

    /** Parses the patch-set JSON into steps. Separated out so it is testable without a network. */
    static List<PatchStep> parseSteps(String json) {
        List<PatchStep> steps = new ArrayList<>();
        PatchSetDto dto = GSON.fromJson(json, PatchSetDto.class);
        if (dto != null && dto.steps != null) {
            for (StepDto s : dto.steps) {
                steps.add(new PatchStep(s.from, s.to, s.pwr, s.sig));
            }
        }
        return steps;
    }

    /** True if the chain has a real delta from {@code currentBuild} (not the up-to-date sentinel). */
    public static boolean hasUpdate(List<PatchStep> steps, int currentBuild) {
        return steps.stream().anyMatch(s -> s.from() == currentBuild && s.to() > currentBuild);
    }

    /** The newest build reachable from a chain, or {@code currentBuild} if already current. */
    public static int newestBuild(List<PatchStep> steps, int currentBuild) {
        return steps.stream().filter(s -> s.to() > s.from())
                .mapToInt(PatchStep::to).max().orElse(currentBuild);
    }

    /** One entitled patchline (channel) from get-launcher-data: newest build + semantic version. */
    public record Patchline(String channel, String buildVersion, int newest) {}

    /**
     * The account's entitled patchlines, from {@code get-launcher-data} — the authoritative list of
     * channels this account can reach, each with its newest build number and semantic version name.
     * The account backend gates entitlement here (unentitled channels simply don't appear), so this
     * replaces guessing channel names.
     */
    public List<Patchline> patchlines(String accessToken, String os, String arch)
            throws IOException, InterruptedException {
        String url = HytaleEndpoints.ACCOUNT_DATA_BASE
                + "/my-account/get-launcher-data?arch=" + arch + "&os=" + os;
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", HytaleEndpoints.USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("get-launcher-data failed (HTTP " + resp.statusCode() + ")");
        }
        LauncherDataDto dto = GSON.fromJson(resp.body(), LauncherDataDto.class);
        List<Patchline> out = new ArrayList<>();
        if (dto != null && dto.patchlines != null) {
            for (Map.Entry<String, PatchlineDto> e : dto.patchlines.entrySet()) {
                PatchlineDto p = e.getValue();
                out.add(new Patchline(e.getKey(),
                        p != null ? p.buildVersion : null, p != null ? p.newest : 0));
            }
        }
        return out;
    }

    /**
     * Total size of a (signed) URL, or -1 if unknown. Uses a one-byte {@code Range} GET rather than
     * HEAD: the signed CDN URLs are signed for GET, so HEAD is rejected — but a 206 range response
     * carries the full length in {@code Content-Range}.
     */
    public long contentLength(String url) {
        try {
            HttpResponse<Void> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", HytaleEndpoints.USER_AGENT)
                            .header("Range", "bytes=0-0")
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 206) {
                String cr = resp.headers().firstValue("content-range").orElse("");
                int slash = cr.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        return Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (resp.statusCode() / 100 == 2) {
                return resp.headers().firstValueAsLong("content-length").orElse(-1);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Streams a pre-signed URL to {@code dest}, writing through a {@code .part} sidecar and moving
     * it into place only once complete, so an interrupted download never leaves a half file that
     * looks whole. Reports progress from {@code Content-Length}.
     */
    public void download(String url, Path dest, DownloadProgress progress)
            throws IOException, InterruptedException {
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", HytaleEndpoints.USER_AGENT)
                        .timeout(Duration.ofMinutes(30))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("download failed (HTTP " + resp.statusCode() + ") for " + url);
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
        long done = 0;
        try (InputStream in = resp.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(part,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                done += r;
                if (progress != null) progress.onBytes(done, total);
            }
        }
        move(part, dest);
        log.info("Downloaded {} ({} bytes)", dest.getFileName(), done);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- wire DTOs --------------------------------------------------------------------

    private static final class PatchSetDto {
        List<StepDto> steps;
    }

    private static final class StepDto {
        int from;
        int to;
        String pwr;
        String sig;
        @SerializedName("pwrHead")
        String pwrHead;
    }

    private static final class LauncherDataDto {
        Map<String, PatchlineDto> patchlines;
    }

    private static final class PatchlineDto {
        String buildVersion;
        int newest;
    }
}
