package meridian.launcher.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The selected server's banner ({@code imageUrl}) in the browser's detail panel: a 16:9 box,
 * top-aligned and shrinking with the panel, with a placeholder while loading or when the server
 * has no image. A click opens the original in the browser.
 *
 * <p>Banners are full-size PNGs (1280×720 to 1920×1080, 1–2.5 MB), so each is fetched only when
 * its server is selected, and only a downscaled copy is kept — at twice the box width, so it
 * stays sharp on a scaled display.
 */
final class ServerImagePreview extends JComponent {

    // Same palette as LauncherWindow.
    private static final Color BAR = new Color(32, 32, 32);
    private static final Color PANEL = new Color(18, 18, 18);
    private static final Color MUTED = new Color(150, 150, 150);

    static final int BOX_WIDTH = 256;
    static final int BOX_HEIGHT = 144;
    private static final int STORED_WIDTH = 2 * BOX_WIDTH;
    private static final int CACHED = 32;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** url → loaded (or loading) banner; access-ordered, so the least recently viewed goes first. */
    private static final Map<String, CompletableFuture<BufferedImage>> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, CompletableFuture<BufferedImage>> eldest) {
                    return size() > CACHED;
                }
            };

    private String url;
    private BufferedImage image;
    private String message = "";

    ServerImagePreview(Consumer<String> opener) {
        setPreferredSize(new Dimension(BOX_WIDTH, BOX_HEIGHT));
        setMinimumSize(new Dimension(BOX_WIDTH, 0));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && url != null && opener != null) {
                    opener.accept(url);
                }
            }
        });
    }

    /** Shows the banner at {@code imageUrl}, or a "No image" placeholder when there is none. */
    void show(String imageUrl) {
        String wanted = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();
        url = wanted;
        image = null;
        setCursor(Cursor.getPredefinedCursor(wanted != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        setToolTipText(wanted != null ? "Click to open the full image" : null);
        if (wanted == null) {
            message = "No image";
            repaint();
            return;
        }
        CompletableFuture<BufferedImage> loading = load(wanted);
        if (loading.isDone() && !loading.isCompletedExceptionally()) {
            image = loading.join();   // already cached: no "Loading…" flash on re-select
            message = "";
            repaint();
            return;
        }
        message = "Loading…";
        repaint();
        loading.whenComplete((img, err) -> SwingUtilities.invokeLater(() -> {
            if (!wanted.equals(url)) return;   // the selection moved on meanwhile
            image = img;
            message = err != null ? "Image unavailable" : "";
            repaint();
        }));
    }

    /** Empty box, no server selected. */
    void clear() {
        url = null;
        image = null;
        message = "";
        setCursor(Cursor.getDefaultCursor());
        setToolTipText(null);
        repaint();
    }

    private static CompletableFuture<BufferedImage> load(String url) {
        synchronized (CACHE) {
            CompletableFuture<BufferedImage> cached = CACHE.get(url);
            if (cached != null) return cached;
            CompletableFuture<BufferedImage> f = new CompletableFuture<>();
            CACHE.put(url, f);
            Thread.startVirtualThread(() -> {
                try {
                    f.complete(downscale(fetch(url)));
                } catch (Exception e) {
                    synchronized (CACHE) {
                        CACHE.remove(url, f);   // let the next selection retry
                    }
                    f.completeExceptionally(e);
                }
            });
            return f;
        }
    }

    private static BufferedImage fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IOException("not a readable image");
            }
            return img;
        }
    }

    /** Halves with bilinear filtering until {@link #STORED_WIDTH} — one big bilinear step aliases. */
    private static BufferedImage downscale(BufferedImage src) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        BufferedImage cur = src;
        int w = srcW;
        while (w > STORED_WIDTH) {
            w = Math.max(STORED_WIDTH, w / 2);
            int h = Math.max(1, (int) Math.round((double) srcH * w / srcW));
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, w, h, null);
            g.dispose();
            cur = next;
        }
        return cur;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // The largest 16:9 box that fits, centred horizontally and pinned to the top.
        int boxW = Math.min(getWidth(), getHeight() * BOX_WIDTH / BOX_HEIGHT);
        int boxH = boxW * BOX_HEIGHT / BOX_WIDTH;
        if (boxW < 2 || boxH < 2) return;
        int boxX = (getWidth() - boxW) / 2;

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(PANEL);
            g.fillRect(boxX, 0, boxW, boxH);
            if (image != null) {
                // Fit inside the box keeping the image's own aspect (letterboxed if it isn't 16:9).
                double scale = Math.min((double) (boxW - 2) / image.getWidth(),
                        (double) (boxH - 2) / image.getHeight());
                int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, boxX + (boxW - w) / 2, (boxH - h) / 2, w, h, null);
            } else if (!message.isEmpty()) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(message, boxX + (boxW - fm.stringWidth(message)) / 2,
                        (boxH - fm.getHeight()) / 2 + fm.getAscent());
            }
            g.setColor(BAR);
            g.drawRect(boxX, 0, boxW - 1, boxH - 1);
        } finally {
            g.dispose();
        }
    }
}
