package meridian.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
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

    private static final String[] COLUMNS = {"Name", "Type", "Address", "Regions", "Likes", "Favorites"};

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
    private JTable table;
    private JTextField searchField;
    private List<ServerListing> allListings = List.of();   // everything fetched
    private List<ServerListing> listings = List.of();      // currently shown (after search filter)

    private JLabel detailName;
    private JTextField detailOwner;
    private JLabel detailRegions;
    private JTextArea detailDesc;

    private ServerDiscoveryClient.Sort current = ServerDiscoveryClient.Sort.RANDOM;
    private volatile boolean busy;
    private boolean loadedOnce;
    private boolean populating;   // true while reloadVersions() fills the combo, to mute its listener

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

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBackground(BG);
        top.add(buildControls(), BorderLayout.NORTH);
        top.add(buildSearchBar(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setBackground(BG);
        table.setForeground(FG);
        table.setGridColor(BAR);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setBackground(BAR);
        table.getTableHeader().setForeground(MUTED);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);   // Name
        table.getColumnModel().getColumn(1).setPreferredWidth(110);   // Type
        table.getColumnModel().getColumn(2).setPreferredWidth(190);   // Address
        table.getColumnModel().getColumn(3).setPreferredWidth(190);   // Regions
        table.getColumnModel().getColumn(4).setPreferredWidth(55);    // Likes
        table.getColumnModel().getColumn(5).setPreferredWidth(70);    // Favorites

        // Center every column (cells and header).
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(center);
        }
        if (table.getTableHeader().getDefaultRenderer() instanceof DefaultTableCellRenderer h) {
            h.setHorizontalAlignment(SwingConstants.CENTER);
        }

        // Left-click a row → show its details below.
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0) { clearDetail(); return; }
            int m = table.convertRowIndexToModel(row);
            if (m >= 0 && m < listings.size()) showDetail(listings.get(m)); else clearDetail();
        });
        // Right-click a row → copy its IP:PORT to the clipboard.
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { rightClickCopy(e); }
            @Override public void mouseReleased(MouseEvent e) { rightClickCopy(e); }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(BorderFactory.createLineBorder(BAR));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, buildDetail());
        split.setBorder(null);
        split.setDividerSize(6);
        split.setResizeWeight(0.72);
        split.setBackground(BG);
        add(split, BorderLayout.CENTER);

        status = new JLabel(" ");
        status.setForeground(MUTED);
        add(status, BorderLayout.SOUTH);

        clearDetail();
        onListChanged();   // set version enablement for the default list
    }

    /** The panel below the table showing the selected server's description, regions and owner. */
    private JPanel buildDetail() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(BG);
        detailName = new JLabel();
        detailName.setForeground(FG);
        detailName.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        detailName.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Owner uuid on its own row: a "Owner:" label + a read-only-but-selectable field so the
        // uuid can be highlighted and copied with the mouse.
        JPanel ownerRow = new JPanel();
        ownerRow.setLayout(new BoxLayout(ownerRow, BoxLayout.X_AXIS));
        ownerRow.setBackground(BG);
        ownerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel ownerLabel = infoLabel();
        ownerLabel.setText("Owner: ");
        detailOwner = selectableField();
        ownerRow.add(ownerLabel);
        ownerRow.add(detailOwner);

        detailRegions = infoLabel();
        header.add(detailName);
        header.add(Box.createVerticalStrut(3));
        header.add(ownerRow);
        header.add(detailRegions);

        detailDesc = new JTextArea();
        detailDesc.setEditable(false);
        detailDesc.setLineWrap(true);
        detailDesc.setWrapStyleWord(true);
        detailDesc.setBackground(new Color(18, 18, 18));
        detailDesc.setForeground(FG);
        detailDesc.setMargin(new java.awt.Insets(8, 8, 8, 8));
        detailDesc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JScrollPane descScroll = new JScrollPane(detailDesc);
        descScroll.setBorder(BorderFactory.createLineBorder(BAR));
        descScroll.getViewport().setBackground(new Color(18, 18, 18));

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
        panel.add(header, BorderLayout.NORTH);
        panel.add(descScroll, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(0, 96));
        panel.setPreferredSize(new Dimension(0, 150));
        return panel;
    }

    private JLabel infoLabel() {
        JLabel l = new JLabel();
        l.setForeground(MUTED);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** A borderless read-only text field that still allows mouse selection + copy of its value. */
    private JTextField selectableField() {
        JTextField f = new JTextField();
        f.setEditable(false);
        f.setBorder(null);
        f.setOpaque(false);
        f.setForeground(MUTED);
        f.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        f.setCaretColor(FG);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        return f;
    }

    private void showDetail(ServerListing s) {
        detailName.setText(s.name == null || s.name.isBlank() ? "(unnamed server)" : s.name);
        detailOwner.setText(s.ownerProfileId == null || s.ownerProfileId.isBlank() ? "—" : s.ownerProfileId);
        String regions = s.regionNames();
        detailRegions.setText("Regions: " + (regions.isBlank() ? "—" : regions));
        detailDesc.setText(s.description == null || s.description.isBlank()
                ? "(no description)" : s.description);
        detailDesc.setCaretPosition(0);
    }

    private void clearDetail() {
        detailName.setText("No server selected");
        detailOwner.setText(" ");
        detailRegions.setText(" ");
        detailDesc.setText("Select a server to view its description. Right-click a row to copy its IP:PORT.");
    }

    private void rightClickCopy(MouseEvent e) {
        if (!SwingUtilities.isRightMouseButton(e)) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        int m = table.convertRowIndexToModel(row);
        if (m < 0 || m >= listings.size()) return;
        String address = listings.get(m).endpoint();
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(address), null);
            setStatus(GREEN, "Copied " + address + " to clipboard");
        } catch (Exception ex) {
            setStatus(RED, "Couldn't copy to clipboard: " + ex.getMessage());
        }
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
        versionBox.addActionListener(e -> { if (!busy && !populating) fetch(); });
        bar.add(versionBox);

        refreshButton = new JButton("Refresh");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> { reloadVersions(); fetch(); });
        bar.add(strut(8));
        bar.add(refreshButton);
        return bar;
    }

    private JPanel buildSearchBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(BG);
        bar.add(label("Search:"), BorderLayout.WEST);

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText",
                "name, type, address, description, region — e.g. Survival, EU");
        searchField.addActionListener(e -> applyFilter());   // Enter triggers the search
        bar.add(searchField, BorderLayout.CENTER);

        JButton searchButton = new JButton("Search");
        searchButton.setFocusPainted(false);
        searchButton.addActionListener(e -> applyFilter());
        bar.add(searchButton, BorderLayout.EAST);
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
        // Mute the combo's action listener: adding items auto-selects index 0 and would fire a
        // fetch() for the wrong version, whose busy-flag then blocks the correct one — leaving the
        // box showing one version while the list is from another.
        populating = true;
        try {
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
        } finally {
            populating = false;
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
                setStatus(RED, "No captured versions yet — launch a version with proxy once to browse it.");
                model.setRowCount(0);
                return;
            }
            params = store.get(version);
            String missing = missingParam(sort, params);
            if (missing != null) {
                setStatus(RED, "No " + missing + " cached for " + version + " — launch game with proxy once.");
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
                    populate(listings);   // sets allListings + applies search filter + count status
                    if (listings.isEmpty()) {
                        setStatus(MUTED, "0 servers — nothing here (params stale? launch game with proxy once)");
                    }
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

    private void populate(List<ServerListing> fetched) {
        this.allListings = fetched;
        applyFilter();   // fills the table honouring the current search box, and clears the detail
    }

    /** Re-fills the table from {@link #allListings}, keeping only rows matching the search box. */
    private void applyFilter() {
        String[] terms = searchTerms();
        List<ServerListing> shown;
        if (terms.length == 0) {
            shown = allListings;
        } else {
            shown = new java.util.ArrayList<>();
            for (ServerListing s : allListings) {
                if (matches(s, terms)) shown.add(s);
            }
        }
        this.listings = shown;
        model.setRowCount(0);
        for (ServerListing s : shown) {
            model.addRow(new Object[]{
                    s.name == null ? "" : s.name,
                    s.typeName(),
                    s.endpoint(),
                    s.regionNamesShort(),
                    s.likes,
                    s.favorites});
        }
        clearDetail();
        updateCountStatus();
    }

    /** The comma-separated search terms, lower-cased (a term may contain spaces, e.g. "EU West"). */
    private String[] searchTerms() {
        String q = searchField == null ? null : searchField.getText();
        if (q == null || q.isBlank()) return new String[0];
        List<String> terms = new java.util.ArrayList<>();
        for (String t : q.toLowerCase().split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) terms.add(trimmed);
        }
        return terms.toArray(new String[0]);
    }

    /** A server matches when <em>every</em> term is a substring of any of its searchable fields. */
    private static boolean matches(ServerListing s, String[] terms) {
        StringBuilder hay = new StringBuilder();
        if (s.name != null) hay.append(s.name).append(' ');
        hay.append(s.typeName()).append(' ');
        hay.append(s.endpoint()).append(' ');
        if (s.description != null) hay.append(s.description).append(' ');
        hay.append(s.regionNames());
        String h = hay.toString().toLowerCase();
        for (String t : terms) {
            if (!h.contains(t)) return false;
        }
        return true;
    }

    private void updateCountStatus() {
        int shown = listings.size(), total = allListings.size();
        if (total == 0) {
            setStatus(MUTED, "0 servers");
        } else if (shown == total) {
            setStatus(GREEN, total + (total == 1 ? " server" : " servers"));
        } else {
            setStatus(GREEN, "showing " + shown + " of " + total + " servers");
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
