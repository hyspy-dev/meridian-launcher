package meridian.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import meridian.launcher.modules.ModuleCatalog;
import meridian.launcher.modules.ModuleCatalog.CatalogModule;
import meridian.launcher.modules.ModuleCatalog.CatalogVersion;
import meridian.launcher.modules.ModuleManifest;
import meridian.launcher.modules.ModuleStore;
import meridian.launcher.modules.ModuleStore.InstalledModule;

/**
 * The Modules tab: manage the proxy's module jars per scope. Two scopes (the user's "default and
 * per-server"): the shared <b>Default</b> set ({@code <proxy-dir>/modules}) that the proxy seeds
 * into every server, and a <b>per-server</b> set ({@code <proxy-dir>/<host_port>/modules}).
 *
 * <p>Modules are added from a local jar or installed from the {@code hyspy-dev} GitHub releases.
 * Browsing lists each module repo with its latest version; clicking one shows its details and an
 * install button per available version (some modules ship a jar per game version). Enabling/
 * disabling moves a jar between the folder root and its {@code disabled/} subfolder — the exact
 * convention the proxy's loader honours.
 */
public final class ModulesPanel extends JPanel {

    private static final Color BG = new Color(24, 24, 24);
    private static final Color BAR = new Color(32, 32, 32);
    private static final Color FG = new Color(216, 216, 216);
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color RED = new Color(195, 90, 90);
    private static final Color GREEN = new Color(111, 207, 151);

    /** The selected-module card: lifted off the page background and edged with an accent. */
    private static final Color CARD_BG = new Color(34, 34, 34);
    private static final Color CARD_EDGE = new Color(58, 58, 58);

    private static final String DEFAULT_SCOPE = "Default (all servers)";

    private final Supplier<Path> proxyDir;
    private final Supplier<String> gameVersion;   // selected install's game version; null if unknown
    private final ModuleCatalog catalog = new ModuleCatalog();

    private JComboBox<String> scopeBox;
    private DefaultTableModel installedModel;
    private JTable installedTable;
    /** One row per module, from either source (see {@link Row}). */
    private List<Row> installed = List.of();
    private boolean refreshing;

    /**
     * A row of the Installed table. Modules reach a scope two ways: the user drops a jar into
     * the folder ({@code jar} set), or the launcher installs one into its store and offers it
     * through {@code modules.json} ({@code managed} set). Both are shown together; actions act
     * on whichever source the row came from.
     */
    private record Row(meridian.launcher.modules.ManagedModules.Managed managed, InstalledModule jar) {
        boolean isManaged() {
            return managed != null;
        }

        String name() {
            return isManaged() ? managed.name() : jar.displayName();
        }

        boolean enabled() {
            return isManaged() ? managed.enabled() : jar.enabled();
        }
    }

    private DefaultTableModel availableModel;
    private JTable availableTable;
    private List<CatalogModule> availableRepos = List.of();
    private JButton browseButton;

    // module detail (shown on selecting an available module)
    private JLabel detailName;
    private JTextField detailUrl;
    private JTextArea detailDesc;
    private JButton openGithubButton;
    private JPanel installButtons;
    private JLabel detailStatus;
    private JPanel detailCard;
    private CatalogModule selectedRepo;

    private JLabel status;

    public ModulesPanel(Supplier<Path> proxyDir, Supplier<String> gameVersion) {
        this.proxyDir = proxyDir;
        this.gameVersion = gameVersion;
        build();
    }

    private void build() {
        setLayout(new BorderLayout(0, 8));
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        add(buildControls(), BorderLayout.NORTH);
        add(buildSplit(), BorderLayout.CENTER);

        status = new JLabel(" ");
        status.setForeground(MUTED);
        add(status, BorderLayout.SOUTH);

        reloadScopes();
        reloadInstalled();
        clearDetail();
    }

    private JPanel buildControls() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(BG);
        bar.add(label("Scope:"));
        scopeBox = new JComboBox<>();
        scopeBox.setPreferredSize(new Dimension(210, 26));
        scopeBox.addActionListener(e -> { if (!refreshing) reloadInstalled(); });
        bar.add(scopeBox);
        bar.add(button("+ Server…", this::addServerScope));
        bar.add(button("Add from file…", this::addFromFile));
        bar.add(button("Open folder", this::openFolder));
        return bar;
    }

    private java.awt.Component buildSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildInstalled(), buildAvailable());
        split.setBorder(null);
        split.setDividerSize(6);
        split.setResizeWeight(0.45);
        split.setBackground(BG);
        return split;
    }

    // --- installed (top) ----------------------------------------------------------

    private JPanel buildInstalled() {
        installedModel = new DefaultTableModel(
                new String[]{"On", "Name", "Version", "Kind", "From", "Depends on"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        };
        installedModel.addTableModelListener(this::onInstalledEdited);
        installedTable = styledTable(installedModel);
        installedTable.getColumnModel().getColumn(0).setMaxWidth(32);
        installedTable.getColumnModel().getColumn(1).setPreferredWidth(220);   // Name
        installedTable.getColumnModel().getColumn(2).setMaxWidth(70);          // Version (narrow)
        installedTable.getColumnModel().getColumn(3).setPreferredWidth(110);   // Kind
        installedTable.getColumnModel().getColumn(4).setMaxWidth(70);          // From
        installedTable.getColumnModel().getColumn(5).setPreferredWidth(180);   // Depends on
        center(installedTable, 1, 2, 4);   // Name + Version + From centred

        JPanel titleBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        titleBar.setBackground(BG);
        titleBar.add(sectionLabel("Installed — this scope"));
        titleBar.add(button("Remove", this::removeSelected));
        titleBar.add(button("Rescan", this::reloadInstalled));

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBackground(BG);
        top.add(titleBar, BorderLayout.NORTH);
        top.add(scroll(installedTable), BorderLayout.CENTER);
        return top;
    }

    // --- available (bottom): table + detail with per-version install buttons ------

    private JPanel buildAvailable() {
        availableModel = new DefaultTableModel(new String[]{"Module", "Description"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        availableTable = styledTable(availableModel);
        availableTable.getColumnModel().getColumn(0).setPreferredWidth(220);   // Module
        availableTable.getColumnModel().getColumn(1).setPreferredWidth(420);   // Description
        center(availableTable, 0);   // Module centred (version lives in the detail, per repo)
        availableTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = availableTable.getSelectedRow();
            if (row >= 0 && row < availableRepos.size()) showDetail(availableRepos.get(row));
        });

        browseButton = button("Refresh", () -> loadAvailable(true));
        JPanel titleBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        titleBar.setBackground(BG);
        titleBar.add(sectionLabel("Available — hyspy-dev"));
        titleBar.add(browseButton);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setBackground(BG);
        bottom.add(titleBar, BorderLayout.NORTH);
        bottom.add(scroll(availableTable), BorderLayout.CENTER);
        bottom.add(buildDetail(), BorderLayout.SOUTH);
        return bottom;
    }

    private JPanel buildDetail() {
        detailName = new JLabel();
        detailName.setForeground(FG);
        detailName.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        openGithubButton = button("Open on GitHub ↗", this::openGithub);
        openGithubButton.setEnabled(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(detailName, BorderLayout.WEST);
        header.add(openGithubButton, BorderLayout.EAST);

        detailUrl = new JTextField();
        detailUrl.setEditable(false);
        detailUrl.setBorder(null);
        detailUrl.setOpaque(false);
        detailUrl.setForeground(MUTED);
        detailUrl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        detailDesc = new JTextArea(2, 20);
        detailDesc.setEditable(false);
        detailDesc.setLineWrap(true);
        detailDesc.setWrapStyleWord(true);
        detailDesc.setOpaque(false);
        detailDesc.setForeground(MUTED);
        detailDesc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        JPanel installRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        installRow.setOpaque(false);
        installRow.add(sectionLabel("Install version:"));
        installButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        installButtons.setOpaque(false);
        installRow.add(installButtons);
        detailStatus = new JLabel();
        detailStatus.setForeground(MUTED);
        detailStatus.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        installRow.add(detailStatus);

        JPanel mid = new JPanel();
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        mid.setOpaque(false);
        detailUrl.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(detailUrl);
        mid.add(Box.createVerticalStrut(2));
        mid.add(detailDesc);

        // The selected module reads as a card: its own surface, an outline, and a coloured
        // spine on the left that lights up only while something is actually selected.
        detailCard = new JPanel(new BorderLayout(0, 4));
        detailCard.setBackground(CARD_BG);
        detailCard.add(header, BorderLayout.NORTH);
        detailCard.add(mid, BorderLayout.CENTER);
        detailCard.add(installRow, BorderLayout.SOUTH);
        setCardSelected(false);

        JPanel detail = new JPanel(new BorderLayout());
        detail.setBackground(BG);
        detail.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        detail.add(detailCard, BorderLayout.CENTER);
        detail.setPreferredSize(new Dimension(0, 132));
        return detail;
    }

    /** Accent spine + brighter title while a module is selected; muted when nothing is. */
    private void setCardSelected(boolean selected) {
        detailCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? CARD_EDGE : BAR),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, selected ? GREEN : BAR),
                        BorderFactory.createEmptyBorder(8, 10, 8, 10))));
        detailName.setForeground(selected ? FG : MUTED);
    }

    // --- scope --------------------------------------------------------------------

    private Path currentScopeFolder() {
        Path base = proxyDir.get();
        String scope = (String) scopeBox.getSelectedItem();
        if (scope == null || scope.equals(DEFAULT_SCOPE)) return base.resolve("modules");
        return base.resolve(scope).resolve("modules");
    }

    private ModuleStore currentStore() {
        return new ModuleStore(currentScopeFolder());
    }

    private void reloadScopes() {
        refreshing = true;
        String want = (String) scopeBox.getSelectedItem();
        scopeBox.removeAllItems();
        scopeBox.addItem(DEFAULT_SCOPE);
        Path base = proxyDir.get();
        if (base != null && Files.isDirectory(base)) {
            try (Stream<Path> dirs = Files.list(base)) {
                dirs.filter(Files::isDirectory)
                        .filter(d -> !d.getFileName().toString().equals("modules"))
                        .filter(d -> Files.isDirectory(d.resolve("modules")))
                        .map(d -> d.getFileName().toString())
                        .sorted()
                        .forEach(scopeBox::addItem);
            } catch (Exception ignored) {
            }
        }
        if (want != null && indexOf(scopeBox, want) >= 0) scopeBox.setSelectedItem(want);
        refreshing = false;
    }

    private void addServerScope() {
        String hostPort = JOptionPane.showInputDialog(this,
                "Server as host_port (e.g. 45.12.34.56_5520):", "Add per-server scope",
                JOptionPane.PLAIN_MESSAGE);
        if (hostPort == null) return;
        hostPort = hostPort.trim().replace(':', '_');
        if (hostPort.isEmpty()) return;
        try {
            Files.createDirectories(proxyDir.get().resolve(hostPort).resolve("modules"));
            reloadScopes();
            scopeBox.setSelectedItem(hostPort);
            reloadInstalled();
        } catch (Exception ex) {
            setStatus(RED, "Couldn't create scope: " + ex.getMessage());
        }
    }

    // --- installed ----------------------------------------------------------------

    private void reloadInstalled() {
        refreshing = true;
        try {
            Path scope = currentScopeFolder();
            List<Row> rows = new java.util.ArrayList<>();
            for (var m : meridian.launcher.modules.ManagedModules.list(scope)) rows.add(new Row(m, null));
            for (InstalledModule m : currentStore().list()) rows.add(new Row(null, m));
            rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            installed = List.copyOf(rows);

            installedModel.setRowCount(0);
            for (Row r : installed) {
                String version;
                String kind;
                String depends = "";
                if (r.isManaged()) {
                    // One row per module even when several per-line builds are installed; the
                    // proxy loads whichever matches its protocol.
                    var builds = r.managed().builds();
                    version = builds.isEmpty() ? "" : builds.get(0).version();
                    boolean layer1 = builds.stream().anyMatch(b -> b.requiresProtocol());
                    kind = (layer1 ? "Layer 1" : "Layer 2")
                            + (builds.size() > 1 ? " · " + builds.size() + " builds" : "");
                } else {
                    ModuleManifest mf = r.jar().manifest();
                    version = mf != null && mf.version() != null ? mf.version() : "";
                    kind = mf == null ? "?" : (mf.isLayer1() ? "Layer 1" : "Layer 2");
                    if (mf != null && mf.dependsOn() != null) depends = String.join(", ", mf.dependsOn().keySet());
                }
                installedModel.addRow(new Object[]{r.enabled(), r.name(), version, kind,
                        r.isManaged() ? "launcher" : "folder", depends});
            }
            long on = installed.stream().filter(Row::enabled).count();
            setStatus(MUTED, installed.size() + " module(s), " + on + " enabled · " + scope);
        } finally {
            refreshing = false;
        }
    }

    private void onInstalledEdited(javax.swing.event.TableModelEvent e) {
        if (refreshing || e.getColumn() != 0) return;
        int row = e.getFirstRow();
        if (row < 0 || row >= installed.size()) return;
        boolean enabled = Boolean.TRUE.equals(installedModel.getValueAt(row, 0));
        Row r = installed.get(row);
        try {
            if (r.isManaged()) {
                meridian.launcher.modules.ManagedModules.setEnabled(
                        currentScopeFolder(), r.managed().repo(), enabled);
            } else {
                currentStore().setEnabled(r.jar(), enabled);
            }
            reloadInstalled();
        } catch (Exception ex) {
            setStatus(RED, "Toggle failed: " + ex.getMessage());
            reloadInstalled();
        }
    }

    private void removeSelected() {
        int row = installedTable.getSelectedRow();
        if (row < 0 || row >= installed.size()) { setStatus(MUTED, "Select a module to remove."); return; }
        Row r = installed.get(row);
        int ok = JOptionPane.showConfirmDialog(this, "Remove " + r.name() + "?",
                "Remove module", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            if (r.isManaged()) {
                meridian.launcher.modules.ManagedModules.remove(currentScopeFolder(), r.managed().repo());
            } else {
                currentStore().remove(r.jar());
            }
            setStatus(GREEN, "Removed " + r.name());
            reloadInstalled();
        } catch (Exception ex) {
            setStatus(RED, "Remove failed: " + ex.getMessage());
        }
    }

    private void addFromFile() {
        JFileChooser fc = new JFileChooser();
        // Where the launcher lives, which is where its jars are - rather than wherever Swing
        // decides, which is the user documents folder and never has a module in it.
        java.nio.file.Path from = meridian.launcher.AppPaths.launcherDir();
        if (java.nio.file.Files.isDirectory(from)) {
            fc.setCurrentDirectory(from.toFile());
        }
        fc.setFileFilter(new FileNameExtensionFilter("Module jar (*.jar)", "jar"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path jar = fc.getSelectedFile().toPath();
        try {
            // Into the store and the offer, the same way a downloaded module goes. Copying it
            // into this one server folder instead left it unmanaged and unlisted, and needed
            // doing again for every other server it was wanted in.
            var m = meridian.launcher.modules.ManagedModules.installLocal(currentScopeFolder(), jar);
            setStatus(GREEN, "Installed " + m.name() + " into the store");
            reloadInstalled();
        } catch (Exception ex) {
            setStatus(RED, ex.getMessage());
        }
    }

    private void openFolder() {
        try {
            Path folder = currentScopeFolder();
            Files.createDirectories(folder);
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            setStatus(RED, "Couldn't open folder: " + ex.getMessage());
        }
    }

    // --- available (static catalog) -----------------------------------------------

    private void loadAvailable(boolean force) {
        browseButton.setEnabled(false);
        setStatus(MUTED, "Loading module catalog…");
        Thread.startVirtualThread(() -> {
            try {
                List<CatalogModule> list = catalog.list(force);
                SwingUtilities.invokeLater(() -> {
                    availableRepos = list;
                    availableModel.setRowCount(0);
                    for (CatalogModule m : list) {
                        availableModel.addRow(new Object[]{
                                m.name() != null ? m.name() : m.repo(),
                                m.description() == null ? "" : m.description()});
                    }
                    setStatus(GREEN, list.size() + " module(s) — click one to pick a version.");
                    browseButton.setEnabled(true);
                    clearDetail();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus(RED, ex.getMessage());
                    browseButton.setEnabled(true);
                });
            }
        });
    }

    /** Shows a module's details and an install button per version — all from the catalog, no network. */
    private void showDetail(CatalogModule module) {
        selectedRepo = module;
        detailName.setText(module.name() != null ? module.name() : module.repo());
        detailUrl.setText(module.htmlUrl() == null ? "" : module.htmlUrl());
        detailUrl.setCaretPosition(0);
        detailDesc.setText(module.description() == null ? "" : module.description());
        openGithubButton.setEnabled(module.htmlUrl() != null);
        installButtons.removeAll();
        List<CatalogVersion> versions = module.versions();
        // A release version may ship one jar per game line. When the selected install's game
        // version is known, show ONE button per version that installs the matching build; only
        // when the game is unknown (or nothing matches) fall back to a button per line.
        String gv = gameVersion.get();
        java.util.LinkedHashMap<String, List<CatalogVersion>> byVersion = new java.util.LinkedHashMap<>();
        for (CatalogVersion v : versions) {
            byVersion.computeIfAbsent(v.version(), k -> new java.util.ArrayList<>()).add(v);
        }
        for (Map.Entry<String, List<CatalogVersion>> e : byVersion.entrySet()) {
            List<CatalogVersion> group = e.getValue();
            CatalogVersion match = group.size() == 1 ? group.get(0)
                    : gv == null ? null
                    : group.stream()
                            .filter(v -> meridian.launcher.modules.ManagedModules.matchesGame(v.games(), gv))
                            .findFirst().orElse(null);
            if (match != null) {
                String label = match.version() + (match.prerelease() ? " (pre)" : "");
                JButton b = button(label, () -> install(module.repo(), match));
                if (match.games() != null) b.setToolTipText("build for " + String.join(", ", match.games()));
                installButtons.add(b);
            } else {
                for (CatalogVersion v : group) {   // game unknown or unmatched: disambiguate per line
                    String line = v.games() == null || v.games().isEmpty() ? "?" : v.games().get(0);
                    String label = v.version() + (v.prerelease() ? " (pre)" : "") + " (" + line + ")";
                    installButtons.add(button(label, () -> install(module.repo(), v)));
                }
            }
        }
        detailStatus.setText(versions.isEmpty() ? "no downloadable versions" : "");
        setCardSelected(true);
        installButtons.revalidate();
        installButtons.repaint();
    }

    private void clearDetail() {
        selectedRepo = null;
        detailName.setText("No module selected");
        detailUrl.setText("");
        detailDesc.setText("Browse hyspy-dev, then click a module to see its versions.");
        openGithubButton.setEnabled(false);
        installButtons.removeAll();
        detailStatus.setText("");
        setCardSelected(false);
        installButtons.revalidate();
        installButtons.repaint();
    }

    private void openGithub() {
        if (selectedRepo == null || selectedRepo.htmlUrl() == null) return;
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(selectedRepo.htmlUrl()));
            }
        } catch (Exception ex) {
            setStatus(RED, "Couldn't open browser: " + ex.getMessage());
        }
    }

    /**
     * Installs a catalog build into the launcher's own store and offers it to this scope via
     * {@code modules.json} — the proxy's modules folder itself is left to the user.
     */
    private void install(String repo, CatalogVersion version) {
        Path scopeFolder = currentScopeFolder();
        setStatus(MUTED, "Downloading " + version.jarName() + "…");
        Thread.startVirtualThread(() -> {
            try {
                var m = meridian.launcher.modules.ManagedModules.install(
                        scopeFolder, repo, version, catalog);   // verifies sha256 from the catalog
                SwingUtilities.invokeLater(() -> {
                    setStatus(GREEN, "Installed " + m.name() + " (" + version.version() + ")");
                    reloadInstalled();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> setStatus(RED, "Install failed: " + ex.getMessage()));
            }
        });
    }

    // --- small ui helpers ---------------------------------------------------------

    private JTable styledTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setBackground(BG);
        t.setForeground(FG);
        t.setGridColor(BAR);
        t.setRowHeight(24);
        t.setFillsViewportHeight(true);
        t.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setBackground(BAR);
        t.getTableHeader().setForeground(MUTED);
        return t;
    }

    /** Centres the given column indices (cells + their headers). */
    private static void center(JTable table, int... columns) {
        DefaultTableCellRenderer c = new DefaultTableCellRenderer();
        c.setHorizontalAlignment(SwingConstants.CENTER);
        for (int col : columns) table.getColumnModel().getColumn(col).setCellRenderer(c);
        if (table.getTableHeader().getDefaultRenderer() instanceof DefaultTableCellRenderer h) {
            h.setHorizontalAlignment(SwingConstants.CENTER);
        }
    }

    private JScrollPane scroll(JTable t) {
        JScrollPane sc = new JScrollPane(t);
        sc.getViewport().setBackground(BG);
        sc.setBorder(BorderFactory.createLineBorder(BAR));
        return sc;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        return l;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        return l;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static int indexOf(JComboBox<String> box, String value) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (value.equals(box.getItemAt(i))) return i;
        }
        return -1;
    }

    private void setStatus(Color color, String text) {
        status.setForeground(color);
        status.setText(text);
    }

    /** Refreshes scopes + installed list — call when the tab is shown or the proxy dir changes. */
    public void refresh() {
        reloadScopes();
        reloadInstalled();
        if (availableRepos.isEmpty()) loadAvailable(false);   // populate the catalog once, on first open
    }
}
