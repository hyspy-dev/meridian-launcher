package meridian.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import meridian.launcher.auth.GameSession;
import meridian.launcher.auth.SessionProvider;
import meridian.launcher.discovery.ServerDiscoveryClient;
import meridian.launcher.discovery.ServerListing;
import meridian.launcher.discovery.ServerParams;
import meridian.launcher.discovery.ServerParamsStore;

/**
 * The server browser, embedded as a tab in the launcher window (not a separate window).
 *
 * <p>Featured / Random / Favorites are three <em>separate lists</em> the discovery service
 * exposes — not a sort order — so they are picked with three labelled buttons. A version
 * selector chooses which captured build's servers to read (Favorites is per-account, so it
 * ignores the version). Each list is fetched in full (all pages) with a minted token, without
 * launching the game.
 */
public final class ServersPanel extends JPanel {

    // Same palette as LauncherWindow.
    private static final Color BG = new Color(24, 24, 24);
    private static final Color BAR = new Color(32, 32, 32);
    private static final Color FG = new Color(216, 216, 216);
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color RED = new Color(195, 90, 90);
    private static final Color GREEN = new Color(111, 207, 151);

    private static final String[] COLUMNS = {"Name", "Address", "Likes", "Favorites", "Regions"};

    private final SessionProvider provider;
    private final Consumer<String> browserOpener;
    private final Supplier<String> defaultVersion;
    private final Supplier<List<String>> installedVersions;
    private final ServerParamsStore store = ServerParamsStore.defaultStore();

    private JComboBox<String> versionBox;
    private JToggleButton featuredButton;
    private JToggleButton randomButton;
    private JToggleButton favoritesButton;
    private JButton refreshButton;
    private JLabel status;
    private DefaultTableModel model;

    private ServerDiscoveryClient.Sort current = ServerDiscoveryClient.Sort.RANDOM;
    private volatile boolean busy;
    private boolean loadedOnce;

    public ServersPanel(SessionProvider provider, Consumer<String> browserOpener,
                        Supplier<String> defaultVersion, Supplier<List<String>> installedVersions) {
        this.provider = provider;
        this.browserOpener = browserOpener;
        this.defaultVersion = defaultVersion;
        this.installedVersions = installedVersions;
        build();
    }

    private void build() {
        setLayout(new BorderLayout(0, 8));
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        add(buildControls(), BorderLayout.NORTH);

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setBackground(BG);
        table.setForeground(FG);
        table.setGridColor(BAR);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setBackground(BAR);
        table.getTableHeader().setForeground(MUTED);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);   // Name
        table.getColumnModel().getColumn(1).setPreferredWidth(230);   // Address
        table.getColumnModel().getColumn(2).setPreferredWidth(60);    // Likes
        table.getColumnModel().getColumn(3).setPreferredWidth(70);    // Favorites
        table.getColumnModel().getColumn(4).setPreferredWidth(120);   // Regions
        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(BorderFactory.createLineBorder(BAR));
        add(scroll, BorderLayout.CENTER);

        status = new JLabel(" ");
        status.setForeground(MUTED);
        add(status, BorderLayout.SOUTH);

        onListChanged();   // set version enablement for the default list
    }

    private JPanel buildControls() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(BG);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        bar.add(label("List:"));
        ButtonGroup group = new ButtonGroup();
        featuredButton = listButton("Featured", ServerDiscoveryClient.Sort.FEATURED, group, bar);
        randomButton = listButton("Random", ServerDiscoveryClient.Sort.RANDOM, group, bar);
        favoritesButton = listButton("Favorites", ServerDiscoveryClient.Sort.FAVORITE, group, bar);
        randomButton.setSelected(true);   // Random is the default list

        bar.add(strut(16));
        bar.add(label("Version:"));
        versionBox = new JComboBox<>();
        versionBox.setPreferredSize(new Dimension(170, 26));
        versionBox.addActionListener(e -> { if (!busy) fetch(); });
        bar.add(versionBox);

        refreshButton = new JButton("Refresh");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> { reloadVersions(); fetch(); });
        bar.add(strut(8));
        bar.add(refreshButton);
        return bar;
    }

    private JToggleButton listButton(String text, ServerDiscoveryClient.Sort sort,
                                     ButtonGroup group, JPanel bar) {
        JToggleButton b = new JToggleButton(text);
        b.setFocusPainted(false);
        b.addActionListener(e -> {
            current = sort;
            onListChanged();
            fetch();
        });
        group.add(b);
        bar.add(b);
        return b;
    }

    private static Component strut(int w) {
        return javax.swing.Box.createHorizontalStrut(w);
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        return l;
    }

    private void onListChanged() {
        // Favorites is per-account and version-independent.
        versionBox.setEnabled(current != ServerDiscoveryClient.Sort.FAVORITE);
    }

    /** Fills the version selector from the store, preserving the current pick when possible. */
    private void reloadVersions() {
        String want = (String) versionBox.getSelectedItem();
        versionBox.removeAllItems();
        List<String> extra = installedVersions != null ? installedVersions.get() : null;
        for (String v : store.versionsIncluding(extra)) {
            versionBox.addItem(v);
        }
        String def = want != null ? want : (defaultVersion != null ? defaultVersion.get() : null);
        if (def != null && indexOf(def) >= 0) {
            versionBox.setSelectedItem(def);
        }
    }

    private int indexOf(String value) {
        for (int i = 0; i < versionBox.getItemCount(); i++) {
            if (value.equals(versionBox.getItemAt(i))) return i;
        }
        return -1;
    }

    /** Loads the list the first time the tab is shown (no token minted until then). */
    public void ensureLoaded() {
        if (loadedOnce) return;
        loadedOnce = true;
        reloadVersions();
        fetch();
    }

    private void fetch() {
        if (busy) return;
        ServerDiscoveryClient.Sort sort = current;
        String version = (String) versionBox.getSelectedItem();

        ServerParams params = null;
        if (sort != ServerDiscoveryClient.Sort.FAVORITE) {
            if (version == null) {
                setStatus(RED, "No captured versions yet — launch a version once (capture-params) to browse it.");
                model.setRowCount(0);
                return;
            }
            params = store.get(version);
            String missing = missingParam(sort, params);
            if (missing != null) {
                setStatus(RED, "No " + missing + " cached for " + version + " — run capture-params for it.");
                model.setRowCount(0);
                return;
            }
        }
        final ServerParams p = params;

        setBusy(true);
        setStatus(MUTED, "Loading " + labelOf(sort) + (version != null ? " · " + version : "") + "…");
        Thread.startVirtualThread(() -> {
            try {
                GameSession session = provider.acquire(browserOpener);
                List<ServerListing> listings = new ServerDiscoveryClient()
                        .listAll(session.sessionToken, sort, p);
                SwingUtilities.invokeLater(() -> {
                    populate(listings);
                    setStatus(listings.isEmpty() ? MUTED : GREEN,
                            listings.size() + (listings.size() == 1 ? " server" : " servers")
                                    + (listings.isEmpty()
                                        ? " — nothing here (params stale? re-run capture-params)" : ""));
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus(RED, "Failed: " + ex.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void populate(List<ServerListing> listings) {
        model.setRowCount(0);
        for (ServerListing s : listings) {
            model.addRow(new Object[]{
                    s.name == null ? "" : s.name,
                    s.endpoint(),
                    s.likes,
                    s.favorites,
                    s.regions == null ? "" : String.join("/", s.regions)});
        }
    }

    private static String labelOf(ServerDiscoveryClient.Sort sort) {
        return switch (sort) {
            case FEATURED -> "Featured";
            case RANDOM -> "Random";
            case FAVORITE -> "Favorites";
        };
    }

    private static String missingParam(ServerDiscoveryClient.Sort sort, ServerParams p) {
        if (p == null || p.protocolVersion() == null || p.protocolVersion().isBlank()) {
            return "protocolVersion";
        }
        if (sort == ServerDiscoveryClient.Sort.RANDOM && (p.clientSeed() == null || p.clientSeed().isBlank())) {
            return "clientSeed";
        }
        return null;
    }

    private void setBusy(boolean b) {
        busy = b;
        refreshButton.setEnabled(!b);
        featuredButton.setEnabled(!b);
        randomButton.setEnabled(!b);
        favoritesButton.setEnabled(!b);
        versionBox.setEnabled(!b && current != ServerDiscoveryClient.Sort.FAVORITE);
    }

    private void setStatus(Color color, String text) {
        status.setForeground(color);
        status.setText(text);
    }
}
