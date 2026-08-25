package meridian.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import meridian.launcher.auth.GameSession;
import meridian.launcher.auth.SessionProvider;
import meridian.launcher.capture.CaptureProxy;
import meridian.launcher.capture.HytaleBackends;
import meridian.launcher.discovery.RouteRegistry;
import meridian.launcher.discovery.ServerDiscoveryRewriter;
import meridian.launcher.discovery.ServerParamsStore;
import meridian.launcher.launch.GameLauncher;
import meridian.launcher.launch.HytaleInstall;
import meridian.launcher.launch.HytaleRoot;
import meridian.launcher.launch.ProxyLauncher;
import meridian.launcher.mitm.CertificateAuthority;
import meridian.launcher.mitm.ExchangeHandler;
import meridian.launcher.mitm.MitmProxy;
import meridian.launcher.mitm.WindowsCaTrust;

/**
 * The launcher's window: sign in, see who you are signed in as, point at the client, and
 * launch. A dark, single-panel Swing UI in the same palette as the proxy's window. Every
 * network or process action runs off the EDT and streams progress into the log area.
 */
public final class LauncherWindow {

    // Palette shared with the proxy window, so the two read as one product.
    private static final Color BG = new Color(24, 24, 24);
    private static final Color BAR = new Color(32, 32, 32);
    private static final Color FG = new Color(216, 216, 216);
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color GREEN = new Color(111, 207, 151);
    private static final Color RED = new Color(195, 90, 90);
    private static final Color ACCENT = new Color(120, 160, 220);

    private final SessionProvider provider = SessionProvider.withDefaults();

    private JFrame frame;
    private JLabel subtitle;
    private JLabel statusDot;
    private JLabel statusText;
    private javax.swing.JComboBox<ProfileItem> accountBox;
    private JButton addAccountButton;
    private JButton removeAccountButton;
    private JTextField clientField;              // the Hytale root folder
    private javax.swing.JComboBox<String> versionBox;
    private javax.swing.JComboBox<ProxyItem> proxyBox;
    private JButton getProxyButton;
    private javax.swing.JCheckBox useProxyCheck;
    private javax.swing.JCheckBox blockTelemetryCheck;
    private javax.swing.JCheckBox logRequestsCheck;

    // Shared across concurrent game windows: the Meridian CA is trusted for as long as any window
    // is using it, and uninstalled only when the LAST such window exits (not the first to close).
    private final java.util.concurrent.atomic.AtomicInteger caUsers = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean caInstalledByUs;
    // Dedicated append-only file for "Log all HTTPS requests" — the launcher's own slf4j-simple
    // output goes to stderr, which is invisible for a GUI (javaw) process.
    private java.io.PrintWriter httpRequestLog;
    private final Object httpRequestLogLock = new Object();
    private JButton launchButton;
    private JButton updateButton;
    private JLabel updateToLabel;
    private JPanel installListCell;           // grid cell #3: inline install list (blank when idle)
    private final java.util.Map<String, meridian.launcher.update.UpdateClient.Patchline> patchlineCache
            = new java.util.HashMap<>();
    private volatile boolean patchlinesFetching;
    private String patchlineAccount;          // account id the patchline cache was fetched for
    private ServersPanel serversPanel;
    private ModulesPanel modulesPanel;

    private final meridian.launcher.Settings settings = meridian.launcher.Settings.defaultSettings();
    private static final String PREF_BLOCK_TELEMETRY = "blockTelemetry";
    private static final String PREF_USE_PROXY = "useProxy";
    private static final String PREF_LOG_REQUESTS = "logHttpsRequests";
    private JTextArea log;

    private volatile boolean busy;

    /**
     * Combo entry wrapping one account-plus-profile row. Each in-game profile of each
     * account is its own selectable line; launching mints for {@code row.profile()}.
     */
    private record ProfileItem(meridian.launcher.auth.SessionProvider.ProfileRow row) {
        meridian.launcher.auth.Account account() { return row.account(); }
        String profileUuid() { return row.profile().uuid(); }
        String username() { return row.profile().username(); }

        @Override
        public String toString() {
            // Launch-ready when there is a valid session OR a refresh token to mint from (the
            // session is refreshed on launch, and the profile list is refreshed on open). Only a
            // missing refresh token truly needs an interactive re-login.
            boolean launchReady = account().refreshToken != null
                    || account().hasValidSessionFor(profileUuid());
            return row.label() + (launchReady ? "" : "  (needs login)");
        }
    }

    /** Combo entry for a proxy jar found next to the launcher; label is the file name. */
    private record ProxyItem(java.nio.file.Path jar) {
        @Override
        public String toString() {
            return jar == null ? "(no proxy jar found)" : jar.getFileName().toString();
        }
    }

    /**
     * Opens the launcher window. Applies the non-reparenting-WM workaround first (which
     * may re-exec the JVM and not return), so the window never comes up blank on a tiling
     * Linux desktop.
     */
    public static void launch(String[] args) {
        X11Support.relaunchIfNeeded(args);
        SwingUtilities.invokeLater(() -> new LauncherWindow().build());
    }

    /** Installs the FlatLaf dark look-and-feel (flat, cohesive) with a couple of rounding tweaks. */
    private void applyTheme() {
        try {
            com.formdev.flatlaf.FlatDarkLaf.setup();
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("TitledBorder.titleColor", MUTED);
        } catch (Throwable t) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
    }

    private void build() {
        applyTheme();

        frame = new JFrame("Meridian Launcher");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1000, 680);
        frame.setMinimumSize(new Dimension(840, 560));
        frame.setLocationByPlatform(true);

        JPanel launchTab = new JPanel(new BorderLayout());
        launchTab.setBackground(BG);
        launchTab.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
        launchTab.add(buildGrid(), BorderLayout.CENTER);
        launchTab.add(buildBottom(), BorderLayout.SOUTH);   // console + a slim status footer

        serversPanel = new ServersPanel(provider, this::openBrowser, () -> {
            HytaleInstall inst = selectedInstall();
            return inst == null ? null : HytaleRoot.gameVersion(inst.root, inst.version);
        }, this::installedGameVersions);

        modulesPanel = new ModulesPanel(() -> {
            Path jar = selectedProxyJar();
            return jar != null && jar.getParent() != null
                    ? jar.getParent() : meridian.launcher.AppPaths.launcherDir();
        }, () -> {
            HytaleInstall inst = selectedInstall();
            return inst == null ? null : HytaleRoot.gameVersion(inst.root, inst.version);
        });

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab("Launch", launchTab);
        tabs.addTab("Modules", modulesPanel);
        tabs.addTab("Servers", serversPanel);
        tabs.addTab("Tools", new ToolsPanel(provider, this::openBrowser));
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == serversPanel) {
                serversPanel.ensureLoaded();
            } else if (tabs.getSelectedComponent() == modulesPanel) {
                modulesPanel.refresh();
            }
        });

        frame.setContentPane(tabs);
        frame.setVisible(true);
        frame.revalidate();
        frame.repaint();

        // Show stored accounts and installed versions without forcing any login or mint.
        reloadVersions();
        reloadProxies();
        reloadAccounts();
        refreshProfilesOnOpen();
        checkLauncherUpdate();
    }

    /** Fills the proxy dropdown from jars next to the launcher; enabled with "Use proxy". */
    private void reloadProxies() {
        if (proxyBox == null) return;
        var model = new javax.swing.DefaultComboBoxModel<ProxyItem>();
        java.util.List<java.nio.file.Path> jars = meridian.launcher.launch.ProxyLauncher.findProxyJars();
        if (jars.isEmpty()) {
            model.addElement(new ProxyItem(null));
        } else {
            for (java.nio.file.Path j : jars) model.addElement(new ProxyItem(j));
        }
        proxyBox.setModel(model);
        proxyBox.setEnabled(useProxyCheck != null && useProxyCheck.isSelected());
    }

    private java.nio.file.Path selectedProxyJar() {
        ProxyItem item = proxyBox == null ? null : (ProxyItem) proxyBox.getSelectedItem();
        return item == null ? null : item.jar();
    }

    /** Selects the proxy dropdown entry for {@code jar} (by file name), if present. */
    private void selectProxy(java.nio.file.Path jar) {
        if (proxyBox == null || jar == null) return;
        for (int i = 0; i < proxyBox.getItemCount(); i++) {
            ProxyItem it = proxyBox.getItemAt(i);
            if (it != null && it.jar() != null
                    && it.jar().getFileName().equals(jar.getFileName())) {
                proxyBox.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Downloads the proxy build matching the selected version's game, next to the launcher. */
    private void getProxy() {
        HytaleInstall install = selectedInstall();
        if (install == null) {
            append("Select a version first — the proxy is matched to its game version.");
            return;
        }
        String g = HytaleRoot.gameVersion(install.root, install.version);
        final String gv = g != null ? g : install.version;
        setBusy(true, "Finding proxy for " + gv + "…");
        Thread.startVirtualThread(() -> {
            try {
                meridian.launcher.modules.ProxyProvisioner prov =
                        new meridian.launcher.modules.ProxyProvisioner(new meridian.launcher.modules.ModuleCatalog());
                meridian.launcher.modules.ModuleCatalog.EndAppVersion proxy = prov.resolve(gv);
                if (proxy == null) {
                    appendAsync("No proxy build available for " + gv + " yet.");
                    setBusyAsync(false, null);
                    return;
                }
                appendAsync("Downloading proxy " + proxy.version() + " (" + proxy.jarName() + ")…");
                java.nio.file.Path jar = prov.download(proxy, null);
                appendAsync("Proxy installed: " + jar.getFileName());
                SwingUtilities.invokeLater(() -> {
                    if (useProxyCheck != null) useProxyCheck.setSelected(true);
                    reloadProxies();
                    selectProxy(jar);
                    if (proxyBox != null) proxyBox.setEnabled(true);
                });
                setBusyAsync(false, null);
            } catch (Exception e) {
                appendAsync("Get proxy failed: " + e.getMessage());
                setBusyAsync(false, null);
            }
        });
    }

    /**
     * When the selected version changes: tells the user which managed modules have no build for
     * that game version yet (they are fetched automatically at launch). Off the EDT; quiet when
     * everything is already present.
     */
    private void noteMissingModuleBuilds() {
        Path proxyJar = selectedProxyJar();
        Path proxyDir = proxyJar != null && proxyJar.getParent() != null ? proxyJar.getParent() : null;
        HytaleInstall install = selectedInstall();
        if (proxyDir == null || install == null) return;
        Thread.startVirtualThread(() -> {
            String gv = HytaleRoot.gameVersion(install.root, install.version);
            if (gv == null) return;
            java.util.Set<String> missing = new java.util.TreeSet<>(
                    meridian.launcher.modules.ManagedModules.missingFor(proxyDir.resolve("modules"), gv));
            try (java.util.stream.Stream<Path> dirs = java.nio.file.Files.list(proxyDir)) {
                dirs.filter(java.nio.file.Files::isDirectory)
                        .map(d -> d.resolve("modules"))
                        .filter(java.nio.file.Files::isDirectory)
                        .forEach(m -> missing.addAll(
                                meridian.launcher.modules.ManagedModules.missingFor(m, gv)));
            } catch (Exception ignored) {
            }
            if (!missing.isEmpty()) {
                appendAsync("Modules: no " + gv + " build installed yet for "
                        + String.join(", ", missing) + " — they will be fetched at launch.");
            }
        });
    }

    /** On open, checks the catalog for a newer launcher and offers to download it beside this one. */
    private void checkLauncherUpdate() {
        Thread.startVirtualThread(() -> {
            try {
                String self = launcherVersion();
                if (self == null || self.isBlank() || self.contains("SNAPSHOT")) return;  // dev build
                var versions = new meridian.launcher.modules.ModuleCatalog().load(false).launcher();
                if (versions.isEmpty()) return;
                meridian.launcher.modules.ModuleCatalog.EndAppVersion latest = versions.get(0);  // newest first
                if (latest.version() != null && compareVersions(latest.version(), self) > 0) {
                    SwingUtilities.invokeLater(() -> offerLauncherUpdate(latest, self));
                }
            } catch (Exception ignored) {
                // best-effort; no network / no catalog just means no update prompt
            }
        });
    }

    private void offerLauncherUpdate(meridian.launcher.modules.ModuleCatalog.EndAppVersion latest, String self) {
        int ok = javax.swing.JOptionPane.showConfirmDialog(frame,
                "A newer launcher is available: " + latest.version() + " (you have " + self + ").\n"
                        + "Download it next to the current launcher now? Restart afterwards to use it.",
                "Launcher update available", javax.swing.JOptionPane.YES_NO_OPTION);
        if (ok != javax.swing.JOptionPane.YES_OPTION) return;
        appendAsync("Downloading launcher " + latest.version() + "…");
        Thread.startVirtualThread(() -> {
            try {
                Path dest = meridian.launcher.AppPaths.launcherDir().resolve(latest.jarName());
                new meridian.launcher.modules.ModuleCatalog()
                        .downloadTo(latest.url(), latest.sha256(), latest.jarName(), dest, null);
                appendAsync("Downloaded " + dest.getFileName()
                        + " — restart with it to use launcher " + latest.version() + ".");
            } catch (Exception e) {
                appendAsync("Launcher update download failed: " + e.getMessage());
            }
        });
    }

    /** This launcher's build version, from the filtered {@code version.properties}; null in dev. */
    private static String launcherVersion() {
        try (java.io.InputStream in = LauncherWindow.class.getResourceAsStream("/version.properties")) {
            if (in == null) return null;
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty("version");
            return v == null || v.contains("${") ? null : v.trim();   // unfiltered placeholder = dev
        } catch (Exception e) {
            return null;
        }
    }

    /** Numeric-component version compare: {@code >0} when {@code a} is newer than {@code b}. */
    static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", " ").trim().split("[.\\s]+");
        String[] pb = b.replaceAll("[^0-9.]", " ").trim().split("[.\\s]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * On open, re-pull each account's profile list (best-effort, off the EDT) so profiles
     * added or renamed since the account was stored appear in the picker — no re-add needed.
     * Only reloads the dropdown if something actually changed. Skips silently when a refresh
     * token is dead or there is no network.
     */
    private void refreshProfilesOnOpen() {
        if (!provider.hasAccounts()) return;
        Thread.startVirtualThread(() -> {
            try {
                int changed = provider.refreshProfiles();
                if (changed > 0) {
                    appendAsync("Updated profiles for " + changed
                            + (changed == 1 ? " account." : " accounts."));
                    SwingUtilities.invokeLater(this::reloadAccounts);
                }
                // Serialized after the profile refresh (so the two token refreshes don't race):
                // pull patchlines and reveal the Update row if the selected version can update.
                SwingUtilities.invokeLater(this::refreshUpdateRow);
            } catch (Exception ignored) {
                // best-effort; the stored profiles remain shown
            }
        });
    }

    /** Game versions of every installed patchline, so the Servers picker can offer them too. */
    private List<String> installedGameVersions() {
        List<String> out = new java.util.ArrayList<>();
        try {
            String override = clientField == null || clientField.getText().isBlank()
                    ? null : clientField.getText().trim();
            Optional<Path> root = HytaleRoot.locate(override);
            if (root.isPresent()) {
                for (String folder : HytaleRoot.versions(root.get())) {
                    String gv = HytaleRoot.gameVersion(root.get(), folder);
                    out.add(gv != null ? gv : folder);
                }
            }
        } catch (Exception ignored) {
            // best-effort — the picker still has captured/seeded versions
        }
        return out;
    }


    /** The bottom area: the console, with a slim status footer beneath it (no top bar). */
    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setBackground(BG);
        bottom.add(buildLogArea(), BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        footer.setOpaque(false);
        statusDot = new JLabel("●");
        statusDot.setForeground(MUTED);
        statusText = new JLabel(" ");
        statusText.setForeground(FG);
        statusText.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        subtitle = new JLabel("Add your Hytale account to get started.");
        subtitle.setForeground(MUTED);
        footer.add(statusDot);
        footer.add(statusText);
        footer.add(subtitle);
        bottom.add(footer, BorderLayout.SOUTH);
        return bottom;
    }

    private JScrollPane buildLogArea() {
        log = new JTextArea();
        log.setEditable(false);
        // Wrap long lines (the sign-in URL is long) instead of a horizontal scrollbar.
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(new Color(16, 16, 16));
        log.setForeground(new Color(200, 200, 200));
        log.setMargin(new Insets(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(log);
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(45, 45, 45)), "Console");
        tb.setTitleColor(MUTED);
        tb.setTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        scroll.setBorder(tb);
        scroll.setPreferredSize(new Dimension(0, 96));   // compact console, at the bottom
        return scroll;
    }

    private JPanel buildGrid() {
        // --- Account controls ---
        accountBox = new javax.swing.JComboBox<>();
        accountBox.setPrototypeDisplayValue(new ProfileItem(
                new meridian.launcher.auth.SessionProvider.ProfileRow(
                        new meridian.launcher.auth.Account("", "account", null, null),
                        new meridian.launcher.auth.HytaleAuth.Profile("", "a-long-profile-name"))));
        accountBox.addActionListener(e -> { updateLaunchEnabled(); refreshUpdateRow(); });
        addAccountButton = textButton("Add account", e -> addAccount());
        removeAccountButton = textButton("Remove", e -> removeAccount());

        // --- Game-version controls ---
        clientField = new JTextField(24);
        clientField.setToolTipText("The Hytale folder (holds install/ and data/); default %APPDATA%/Hytale");
        HytaleRoot.locate(null).ifPresent(p -> clientField.setText(p.toString()));
        clientField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
        });
        JButton browse = textButton("Browse…", e -> chooseFolder());

        versionBox = new javax.swing.JComboBox<>();
        versionBox.setPrototypeDisplayValue("pre-release   ");
        versionBox.addActionListener(e -> { updateLaunchEnabled(); refreshUpdateRow(); noteMissingModuleBuilds(); });
        updateButton = textButton("Update", e -> updateGame());
        updateButton.setToolTipText("Update the selected version to the newest build of its channel");
        updateToLabel = new JLabel(" ");
        updateToLabel.setForeground(MUTED);
        updateToLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JButton installButton = textButton("Install version…", e -> toggleInstallList());
        installButton.setToolTipText("List versions available to your account and install one");
        JButton saveVersionButton = textButton("Save version", e -> saveVersion());
        saveVersionButton.setToolTipText("Copy the selected version's install to a named snapshot"
                + " so it survives the next update");

        // --- Launch controls ---
        launchButton = new JButton("Launch game");
        launchButton.setFocusPainted(false);
        launchButton.setEnabled(false);
        launchButton.setMargin(new Insets(4, 18, 4, 18));
        launchButton.addActionListener(e -> launch());

        blockTelemetryCheck = checkBox("Block telemetry", PREF_BLOCK_TELEMETRY, null);
        blockTelemetryCheck.setToolTipText("Route the game through a local proxy that refuses its"
                + " telemetry / crash-reporting hosts (" + String.join(", ", HytaleBackends.TELEMETRY) + ")");
        useProxyCheck = checkBox("Use proxy", PREF_USE_PROXY,
                () -> { if (proxyBox != null) proxyBox.setEnabled(useProxyCheck.isSelected()); });
        useProxyCheck.setToolTipText("Route the game's servers through the Meridian proxy: the in-game"
                + " server list is rewritten to the local proxy, which relays to the real servers.");
        logRequestsCheck = checkBox("Log all HTTPS requests", PREF_LOG_REQUESTS, null);
        logRequestsCheck.setToolTipText("<html>MITM-decrypt the game's HTTPS and log every request"
                + " — method, URL, POST body and the response — to the console and to"
                + " logs/launcher.log.<br>Installs the Meridian CA for the game (like the proxy)."
                + " The log may contain tokens / personal data. A rarely-seen pinned host would fail"
                + " while this is on.</html>");
        proxyBox = new javax.swing.JComboBox<>();
        proxyBox.setPrototypeDisplayValue(new ProxyItem(java.nio.file.Path.of("meridian-proxy-x.y.z-all.jar")));
        proxyBox.setToolTipText("Meridian proxy jar to run (found next to the launcher)");
        getProxyButton = new JButton("Download proxy for this version");
        getProxyButton.setFocusPainted(false);
        getProxyButton.setToolTipText("Fetch the Meridian proxy matching the selected game version from hyspy-dev");
        getProxyButton.addActionListener(e -> getProxy());

        // --- cell 1: Game version (responsive; Update row appears only when an update exists) ---
        JPanel gameVersion = migCell("Game version", "[]6[grow,fill]6[]");
        gameVersion.add(boldLabel("Folder:"));
        gameVersion.add(clientField, "growx");
        gameVersion.add(browse, "wrap");
        gameVersion.add(boldLabel("Version:"));
        gameVersion.add(versionBox, "growx");
        gameVersion.add(saveVersionButton, "wrap");
        updateButton.setVisible(false);
        updateToLabel.setVisible(false);
        gameVersion.add(updateButton);
        gameVersion.add(updateToLabel, "span 2, growx, wrap");
        gameVersion.add(installButton, "span 3, growx, wrap");

        // --- cell 2: Account (dropdown fills; each button full-width on its own row) ---
        JPanel account = migCell("Account", "[]6[grow,fill]");
        account.add(boldLabel("Account:"));
        account.add(accountBox, "growx, wrap");
        account.add(addAccountButton, "span 2, growx, wrap");
        account.add(removeAccountButton, "span 2, growx, wrap");

        // --- cell 3: inline install list (blank until "Install version…") ---
        installListCell = new JPanel(new BorderLayout());
        installListCell.setBackground(BG);

        // --- cell 4: Launch (proxy first, then toggles; Launch full-width, pinned to the bottom) ---
        JPanel launchControls = new JPanel(new net.miginfocom.swing.MigLayout(
                "fillx, insets 0", "[]6[grow,fill]", ""));
        launchControls.setBackground(BG);
        launchControls.add(boldLabel("Proxy:"));
        launchControls.add(proxyBox, "growx, wrap");
        launchControls.add(getProxyButton, "span 2, growx, wrap");
        launchControls.add(blockTelemetryCheck, "span 2, wrap");
        launchControls.add(useProxyCheck, "span 2, wrap");
        launchControls.add(logRequestsCheck, "span 2");
        JPanel launchCell = new JPanel(new BorderLayout(0, 10));
        launchCell.setBackground(BG);
        launchCell.setBorder(BorderFactory.createCompoundBorder(
                titledCellBorder("Launch"), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        launchCell.add(launchControls, BorderLayout.NORTH);
        launchCell.add(launchButton, BorderLayout.SOUTH);   // BorderLayout SOUTH = pinned to bottom

        // 2×2 grid:  Game version | Account
        //            Install list | Launch
        JPanel grid = new JPanel(new java.awt.GridLayout(2, 2, 12, 12));
        grid.setBackground(BG);
        grid.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        grid.add(gameVersion);
        grid.add(account);
        grid.add(installListCell);
        grid.add(launchCell);
        return grid;
    }

    // --- actions ----------------------------------------------------------------------

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the Hytale folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            clientField.setText(chooser.getSelectedFile().getAbsolutePath());
            reloadVersions();
        }
    }

    /** Fills the version dropdown from the folder's install/ subfolders; active first. */
    private void reloadVersions() {
        if (versionBox == null) return;
        String prev = (String) versionBox.getSelectedItem();
        var model = new javax.swing.DefaultComboBoxModel<String>();
        HytaleRoot.locate(clientField.getText().trim()).ifPresent(root ->
                HytaleRoot.versions(root).forEach(model::addElement));
        versionBox.setModel(model);
        if (prev != null && model.getIndexOf(prev) >= 0) {
            versionBox.setSelectedItem(prev);
        } else if (model.getSize() > 0) {
            versionBox.setSelectedIndex(0);   // active/default version
        }
        updateLaunchEnabled();
    }

    /**
     * Snapshots the selected version's install to a named folder under {@code install/} so
     * it survives the next update. Auto-names from the game version in {@code env.dat}, or
     * asks; copies off the EDT (the tree is large) and refreshes the dropdown.
     */
    private void saveVersion() {
        if (busy) return;
        String version = versionBox == null ? null : (String) versionBox.getSelectedItem();
        Optional<Path> root = HytaleRoot.locate(clientField.getText().trim());
        if (version == null || root.isEmpty()) {
            append("Pick a Hytale folder and version first.");
            return;
        }
        String detected = HytaleRoot.gameVersion(root.get(), version);
        String suggestion = detected != null ? detected : version + "-copy";
        String label = javax.swing.JOptionPane.showInputDialog(frame,
                "Save this version's install as (a new folder name under install/):",
                suggestion);
        if (label == null || label.isBlank()) return;   // cancelled

        setBusy(true, "Saving version " + label + "…");
        append("Copying " + version + " → install/" + label + " (this can take a while)…");
        Thread.startVirtualThread(() -> {
            try {
                String name = HytaleRoot.saveVersion(root.get(), version, label);
                appendAsync("Saved version as \"" + name + "\".");
                SwingUtilities.invokeLater(() -> {
                    reloadVersions();
                    versionBox.setSelectedItem(name);
                    setBusy(false, null);
                });
            } catch (java.nio.file.FileAlreadyExistsException dup) {
                appendAsync("A version named \"" + label + "\" already exists — pick another name.");
                setBusyAsync(false, null);
            } catch (Exception e) {
                appendAsync("Save version failed: " + e.getMessage());
                setBusyAsync(false, null);
            }
        });
    }

    /**
     * Checks the selected version's channel for a newer build and applies the delta(s). Runs off
     * the EDT (download + apply are long); progress goes to the status line and log. Downloads are
     * authorised by an access token refreshed from the selected account.
     */
    private void updateGame() {
        if (busy) return;
        HytaleInstall install = selectedInstall();
        if (install == null) {
            append("Pick a Hytale folder and version first.");
            return;
        }
        meridian.launcher.auth.Account account = selectedAccount();
        if (account == null) {
            append("Select an account — updating downloads through your Hytale login.");
            return;
        }
        Path root = install.root;
        String patchline = install.version;
        setBusy(true, "Checking for updates…");
        append("Checking " + patchline + " for updates…");
        Thread.startVirtualThread(() -> {
            try {
                String access = provider.accessToken(account.id);
                meridian.launcher.update.GameUpdater updater = new meridian.launcher.update.GameUpdater();
                meridian.launcher.update.GameUpdater.UpdateCheck chk = updater.check(root, patchline, access);
                SwingUtilities.invokeLater(() -> confirmAndUpdate(updater, root, patchline, access, chk));
            } catch (Exception e) {
                appendAsync("Update check failed: " + e.getMessage());
                setBusyAsync(false, "Update check failed");
            }
        });
    }

    /** On the EDT: report the verdict and, when an update exists, confirm before applying. */
    private void confirmAndUpdate(meridian.launcher.update.GameUpdater updater, Path root,
                                  String patchline, String access,
                                  meridian.launcher.update.GameUpdater.UpdateCheck chk) {
        if (!chk.updateAvailable()) {
            append("Already up to date (build " + chk.currentBuild() + ").");
            setBusy(false, "Up to date (build " + chk.currentBuild() + ")");
            return;
        }
        String message = "Update " + patchline + " from build " + chk.currentBuild()
                + " to build " + chk.newestBuild() + "?";
        if (chk.fullReinstall()) {
            message += "\n\nThis channel has no incremental patch from your build, so this downloads"
                    + " the FULL build (can be ~1 GB+) and replaces the current install of " + patchline + ".";
        }
        int choice = javax.swing.JOptionPane.showConfirmDialog(frame, message, "Update available",
                javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (choice != javax.swing.JOptionPane.OK_OPTION) {
            append("Update cancelled.");
            setBusy(false, null);
            return;
        }
        append("Updating " + patchline + ": build " + chk.currentBuild() + " → " + chk.newestBuild() + "…");
        Thread.startVirtualThread(() -> runUpdate(updater, root, patchline, access));
    }

    /** Off the EDT: download + apply the update, streaming throttled progress to the status line. */
    private void runUpdate(meridian.launcher.update.GameUpdater updater, Path root,
                           String patchline, String access) {
        try {
            Path cache = meridian.launcher.AppPaths.resolve("update-cache");
            String[] phase = {"Working"};
            int[] lastPct = {-1};
            int result = updater.update(root, patchline, access, cache,
                    new meridian.launcher.update.GameUpdater.Listener() {
                        @Override public void phase(String m) {
                            phase[0] = m; lastPct[0] = -1;
                            appendAsync(m); setStatusAsync(ACCENT, m);
                        }
                        @Override public void bytes(long done, long total) {
                            if (total <= 0) return;
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct[0]) {
                                lastPct[0] = pct;
                                setStatusAsync(ACCENT, phase[0] + "  " + pct + "%");
                            }
                        }
                    });
            appendAsync("Updated " + patchline + " to build " + result + ".");
            SwingUtilities.invokeLater(() -> {
                reloadVersions();
                refreshUpdateRow();
                setBusy(false, "Updated to build " + result);
            });
        } catch (Exception e) {
            appendAsync("Update failed: " + e.getMessage());
            setBusyAsync(false, "Update failed");
        }
    }

    /**
     * Toggles the inline install list in grid cell #3: fetches the versions the account can install
     * and lists them there (not a popup), each with its own Install/Update action. Clicking the
     * button again — or finishing an install — clears the cell back to blank.
     */
    private void toggleInstallList() {
        if (installListCell.getComponentCount() > 0) {   // already open → close
            clearInstallList();
            return;
        }
        if (busy) return;
        Path root = HytaleRoot.locate(clientField.getText().trim()).orElse(null);
        meridian.launcher.auth.Account account = selectedAccount();
        if (root == null) {
            append("Pick a Hytale folder first.");
            return;
        }
        if (account == null) {
            append("Select an account first — installing downloads through your Hytale login.");
            return;
        }
        setBusy(true, "Fetching versions…");
        append("Fetching available versions…");
        Thread.startVirtualThread(() -> {
            try {
                String access = provider.accessToken(account.id);
                java.util.List<meridian.launcher.update.VersionCatalog.ChannelVersion> catalog =
                        new meridian.launcher.update.VersionCatalog().discover(access, root);
                appendAsync(catalog.size() + " version(s) available — done.");
                SwingUtilities.invokeLater(() -> {
                    fillInstallList(root, access, catalog);
                    setBusy(false, null);
                });
            } catch (Exception e) {
                appendAsync("Could not fetch versions: " + e.getMessage());
                setBusyAsync(false, null);
            }
        });
    }

    /** Renders the available versions into cell #3, each row an Install/Update action. */
    private void fillInstallList(Path root, String access,
            java.util.List<meridian.launcher.update.VersionCatalog.ChannelVersion> catalog) {
        installListCell.removeAll();
        installListCell.setBorder(titledCellBorder("Install list"));
        JPanel inner = new JPanel(new net.miginfocom.swing.MigLayout(
                "fillx, insets 4", "[][grow,fill]", ""));
        inner.setBackground(BG);
        if (catalog.isEmpty()) {
            JLabel none = new JLabel("No versions available to your account.");
            none.setForeground(MUTED);
            inner.add(none, "span 2, wrap");
        }
        for (var cv : catalog) {
            String state = !cv.installed() ? "not installed"
                    : cv.upToDate() ? "up to date"
                    : "build " + cv.installedBuild() + " → " + cv.newestBuild();
            String size = cv.size() > 0 ? "  ·  " + (cv.size() / 1_000_000) + " MB" : "";
            // Keep the rolling channel label (release / pre-release); drop the junky pinned "vX.Y".
            String prefix = cv.channel().matches("v\\d.*") ? "" : cv.channel() + "  —  ";
            JLabel label = new JLabel(prefix + cv.version() + "  (" + state + ")" + size);
            label.setForeground(FG);
            JButton act = textButton(cv.installed() ? "Update" : "Install", ev -> {
                clearInstallList();
                startInstall(root, access, cv.channel(), cv.version());
            });
            act.setEnabled(!cv.upToDate());
            inner.add(act);
            inner.add(label, "growx, wrap");
        }

        JScrollPane sc = new JScrollPane(inner);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.getViewport().setBackground(BG);
        installListCell.add(sc, BorderLayout.CENTER);
        installListCell.revalidate();
        installListCell.repaint();
    }

    private void clearInstallList() {
        installListCell.removeAll();
        installListCell.setBorder(BorderFactory.createEmptyBorder());
        installListCell.revalidate();
        installListCell.repaint();
    }

    private void startInstall(Path root, String access, String channel, String version) {
        if (busy) return;
        setBusy(true, "Installing " + channel + "…");
        append("Installing " + channel + " " + version + "…");
        Thread.startVirtualThread(() -> {
            try {
                Path cache = meridian.launcher.AppPaths.resolve("update-cache");
                String[] phase = {"Working"};
                int[] lastPct = {-1};
                int result = new meridian.launcher.update.GameUpdater().installOrUpdate(root, channel, access, cache,
                        new meridian.launcher.update.GameUpdater.Listener() {
                            @Override public void phase(String m) {
                                phase[0] = m; lastPct[0] = -1;
                                appendAsync(m); setStatusAsync(ACCENT, m);
                            }
                            @Override public void bytes(long done, long total) {
                                if (total <= 0) return;
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct[0]) {
                                    lastPct[0] = pct;
                                    setStatusAsync(ACCENT, phase[0] + "  " + pct + "%");
                                }
                            }
                        });
                appendAsync(channel + " is now at build " + result + ".");
                SwingUtilities.invokeLater(() -> {
                    reloadVersions();
                    refreshUpdateRow();
                    setBusy(false, channel + " → build " + result);
                });
            } catch (Exception e) {
                appendAsync("Install failed: " + e.getMessage());
                setBusyAsync(false, "Install failed");
            }
        });
    }

    private javax.swing.border.Border titledCellBorder(String title) {
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BAR), title);
        tb.setTitleColor(MUTED);
        tb.setTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        return BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0), tb);
    }

    // --- Update availability (the conditional "Update  to <version>" row) --------------

    /** Fetches the account's patchlines (once per account, off the EDT) and refreshes the row. */
    private void refreshPatchlinesAsync() {
        meridian.launcher.auth.Account account = selectedAccount();
        Path root = HytaleRoot.locate(clientField.getText().trim()).orElse(null);
        if (account == null || root == null || patchlinesFetching) return;
        if (account.id.equals(patchlineAccount) && !patchlineCache.isEmpty()) {
            refreshUpdateRow();
            return;
        }
        patchlinesFetching = true;
        Thread.startVirtualThread(() -> {
            try {
                String access = provider.accessToken(account.id);
                meridian.launcher.update.InstallEnv.Platform pf =
                        meridian.launcher.update.InstallEnv.currentPlatform();
                java.util.List<meridian.launcher.update.UpdateClient.Patchline> lines =
                        new meridian.launcher.update.UpdateClient().patchlines(access, pf.os(), pf.arch());
                SwingUtilities.invokeLater(() -> {
                    patchlineCache.clear();
                    for (var pl : lines) patchlineCache.put(pl.channel(), pl);
                    patchlineAccount = account.id;
                    refreshUpdateRow();
                });
            } catch (Exception ignored) {
                // best-effort — the Update row just stays hidden if we can't reach the API
            } finally {
                patchlinesFetching = false;
            }
        });
    }

    /** Shows the Update row (with "to &lt;version&gt;") only when the selected version can update. */
    private void refreshUpdateRow() {
        if (updateButton == null) return;
        meridian.launcher.auth.Account account = selectedAccount();
        // Lazily pull this account's patchlines (once) so we know each channel's newest build.
        if (account != null && (patchlineCache.isEmpty() || !account.id.equals(patchlineAccount))) {
            refreshPatchlinesAsync();
        }
        boolean show = false;
        String to = " ";
        String version = versionBox == null ? null : (String) versionBox.getSelectedItem();
        Path root = HytaleRoot.locate(clientField.getText().trim()).orElse(null);
        if (version != null && root != null) {
            meridian.launcher.update.UpdateClient.Patchline pl = patchlineCache.get(version);
            if (pl != null) {
                try {
                    int installed = meridian.launcher.update.InstallEnv.currentBuild(root, version);
                    if (pl.newest() > installed) {
                        show = true;
                        to = "to " + pl.buildVersion() + "  (build " + pl.newest() + ")";
                    }
                } catch (Exception ignored) {
                }
            }
        }
        updateToLabel.setText(to);
        updateButton.setVisible(show);
        updateToLabel.setVisible(show);
        java.awt.Container parent = updateButton.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    /** The Hytale install for the current folder + version selection, or null. */
    private HytaleInstall selectedInstall() {
        String version = versionBox == null ? null : (String) versionBox.getSelectedItem();
        if (version == null) return null;
        return HytaleRoot.locate(clientField.getText().trim())
                .map(root -> HytaleInstall.of(root, version))
                .filter(HytaleInstall::isRunnable)
                .orElse(null);
    }

    /** Fills the dropdown with one row per profile across all accounts; selects the first. */
    private void reloadAccounts() {
        var model = new javax.swing.DefaultComboBoxModel<ProfileItem>();
        for (meridian.launcher.auth.SessionProvider.ProfileRow r : provider.profileRows()) {
            model.addElement(new ProfileItem(r));
        }
        accountBox.setModel(model);
        boolean hasAccounts = model.getSize() > 0;
        // The prompt only makes sense before any account exists.
        subtitle.setVisible(!hasAccounts);
        if (hasAccounts) {
            accountBox.setSelectedIndex(0);   // most recently used account, first profile
            setStatus(BG, " ");               // idle: no noisy "Ready" line
        } else {
            setStatus(RED, "No accounts yet — click Add account");
        }
        updateLaunchEnabled();
    }

    private ProfileItem selectedItem() {
        return (ProfileItem) accountBox.getSelectedItem();
    }

    private meridian.launcher.auth.Account selectedAccount() {
        ProfileItem item = selectedItem();
        return item == null ? null : item.account();
    }

    /** Interactive login → stores a new account → reselects it. */
    private void addAccount() {
        if (busy) return;
        setBusy(true, "Opening sign-in…");
        append("Adding an account. A browser window will open for sign-in.");
        Thread.startVirtualThread(() -> {
            try {
                GameSession s = provider.addAccount(this::openBrowser);
                appendAsync("Added account " + s.profileUsername + ".");
                SwingUtilities.invokeLater(() -> {
                    reloadAccounts();
                    selectByName(s.profileUsername);
                    setBusy(false, null);
                });
            } catch (Exception e) {
                appendAsync("Sign-in failed: " + e.getMessage());
                setBusyAsync(false, null);
            }
        });
    }

    private void removeAccount() {
        meridian.launcher.auth.Account a = selectedAccount();
        if (a == null) return;
        provider.removeAccount(a.id);
        append("Removed account " + a.displayName() + ".");
        reloadAccounts();
    }

    private void selectByName(String username) {
        for (int i = 0; i < accountBox.getItemCount(); i++) {
            if (username != null && username.equals(accountBox.getItemAt(i).username())) {
                accountBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private void launch() {
        ProfileItem item = selectedItem();
        HytaleInstall install = selectedInstall();
        if (busy || item == null) return;
        meridian.launcher.auth.Account account = item.account();
        if (install == null) {
            append("No runnable Hytale version selected — check the Hytale folder and version.");
            return;
        }
        boolean blockTelemetry = blockTelemetryCheck.isSelected();
        boolean useProxy = useProxyCheck.isSelected();
        boolean logHttps = logRequestsCheck.isSelected();
        Path proxyJar = selectedProxyJar();
        String gv = HytaleRoot.gameVersion(install.root, install.version);
        final String version = gv != null ? gv : install.version;
        if (useProxy && proxyJar == null) {
            append("Use proxy is on but no proxy jar was found next to the launcher — "
                    + "drop a meridian-proxy-*-all.jar there, or turn off Use proxy.");
            return;
        }
        setBusy(true, "Preparing " + item.username() + "…");
        Thread.startVirtualThread(() -> {
            // A per-launch proxy lives as long as the game window and is closed on exit.
            // Modes: proxy (rewrite the server list to a local multiplex proxy), auto-capture
            // (first run of a version), or a plain CONNECT-block proxy (telemetry). Null if none.
            AutoCloseable proxy = null;
            CertificateAuthority captureCa = null;
            boolean usedCa = false;
            Process proxyProcess = null;
            meridian.launcher.launch.ProxyControl proxyControl = null;
            try {
                // Reuses the stored token when still valid, so launching another window
                // of the same account does not invalidate the ones already open.
                GameSession s = provider.acquireProfile(
                        account.id, item.profileUuid(), item.username(), this::openBrowser);

                ServerParamsStore store = ServerParamsStore.defaultStore();

                Map<String, String> env = Map.of();
                if (useProxy || logHttps) {
                    // Both modes MITM the game's HTTPS, so both need our CA trusted by the game.
                    // useProxy also redirects gameplay UDP: MITM server-discovery to rewrite every
                    // listing to 127.0.0.1:<localPort>, record port→realServer, and run the proxy in
                    // multiplex mode. logHttps just logs every decrypted request line.
                    usedCa = true;
                    caUsers.incrementAndGet();   // hold the CA installed until the last window exits
                    Path caDir = meridian.launcher.AppPaths.resolve("ca");
                    captureCa = CertificateAuthority.loadOrCreate(caDir);
                    if (WindowsCaTrust.isWindows() && !WindowsCaTrust.isInstalled(captureCa.caCertificate())) {
                        WindowsCaTrust.install(caDir.resolve("meridian-ca.crt"));
                        caInstalledByUs = true;
                    }
                    Map<String, ExchangeHandler> handlers = new java.util.HashMap<>();
                    if (useProxy) {
                        // Launcher-managed module jars follow the selected game version; jars the
                        // user placed by hand are never touched. Never throws, logs what it swaps.
                        meridian.launcher.modules.ManagedModules.syncAll(
                                proxyJar.getParent(), version, this::appendAsync);
                        proxyProcess = ProxyLauncher.startMultiplex(proxyJar);
                        // Drive the proxy over its stdin (a parent→child pipe, not files): hand it
                        // the player token, and announce a route for each server as the listing is
                        // rewritten.
                        proxyControl = new meridian.launcher.launch.ProxyControl(proxyProcess.getOutputStream());
                        proxyControl.token(s.sessionToken);
                        RouteRegistry routes = RouteRegistry.create(proxyControl::route);
                        handlers.put("server-discovery.hytale.com", new ServerDiscoveryRewriter(store, routes));
                    }
                    java.util.Set<String> block = blockTelemetry ? HytaleBackends.TELEMETRY : java.util.Set.of();
                    MitmProxy mitm = new MitmProxy(0, captureCa, java.util.Set.of(), handlers, block);
                    mitm.setLogRequests(logHttps, this::logHttpRequest);
                    mitm.start();
                    proxy = mitm;
                    env = proxyEnv("http://127.0.0.1:" + mitm.port());
                    if (!WindowsCaTrust.isWindows()) {
                        env.put("SSL_CERT_FILE", caDir.resolve("meridian-ca.crt").toString());
                    }
                    String on = useProxy
                            ? "Proxy ON — " + proxyJar.getFileName() + " (multiplex); server list redirected through it"
                            : "HTTPS request logging ON — MITM-decrypting the game's HTTPS";
                    appendAsync(on
                            + (logHttps && useProxy ? "; logging all HTTPS requests" : "")
                            + (blockTelemetry ? "; telemetry blocked." : "."));
                    if (logHttps) {
                        appendAsync("Writing HTTPS requests (with bodies + responses — may contain "
                                + "tokens) to " + httpRequestLogPath());
                    }
                } else if (blockTelemetry) {
                    CaptureProxy cp = new CaptureProxy(0, HytaleBackends.TELEMETRY);
                    cp.start();
                    proxy = cp;
                    env = proxyEnv("http://127.0.0.1:" + cp.port());
                    appendAsync("Telemetry blocking on — refusing " + HytaleBackends.TELEMETRY);
                }

                appendAsync("Launching " + install.version + " as " + s.profileUsername);
                Process p = new GameLauncher(install).launch(s, env, List.of());
                appendAsync("Client started (pid " + p.pid() + ").");
                setStatusAsync(GREEN, "Playing as " + s.profileUsername);
                SwingUtilities.invokeLater(this::reloadAccounts);
                setBusyAsync(false, null);
                int code = p.waitFor();
                appendAsync("Client (" + s.profileUsername + ") exited with code " + code + ".");
                setStatusAsync(BG, " ");   // back to idle; the log records the exit
            } catch (Exception e) {
                appendAsync("Launch failed: " + e.getMessage());
                setBusyAsync(false, null);
            } finally {
                if (proxyControl != null) {
                    proxyControl.close();     // close the stdin control pipe
                }
                if (proxyProcess != null && proxyProcess.isAlive()) {
                    proxyProcess.destroy();   // stop the multiplex proxy when the game exits
                }
                if (proxy != null) {
                    try { proxy.close(); } catch (Exception ignored) { }
                }
                // Uninstall the CA only when the LAST window that used it exits — otherwise closing
                // one of several open game windows would strip the cert the others still need.
                if (usedCa && caUsers.decrementAndGet() == 0 && caInstalledByUs && captureCa != null) {
                    try {
                        WindowsCaTrust.uninstall(captureCa.caCertificate());
                        caInstalledByUs = false;
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** Sends one HTTPS request line to the console and appends it to the dedicated log file. */
    private void logHttpRequest(String line) {
        appendAsync(line);
        synchronized (httpRequestLogLock) {
            try {
                if (httpRequestLog == null) {
                    java.nio.file.Path file = httpRequestLogPath();
                    java.nio.file.Files.createDirectories(file.getParent());
                    httpRequestLog = new java.io.PrintWriter(
                            new java.io.FileWriter(file.toFile(), true), true);
                }
                String time = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                httpRequestLog.println(time + "  " + line);
            } catch (Exception ignored) {
                // logging must never break a launch
            }
        }
    }

    /** The launcher's own log file (kept separate from the proxy's {@code logs/meridian.log}). */
    private static java.nio.file.Path httpRequestLogPath() {
        return meridian.launcher.AppPaths.launcherDir().resolve("logs").resolve("launcher.log");
    }

    /** Proxy env vars (both cases + ALL_PROXY) pointing the client's HTTP stack at {@code url}. */
    private static Map<String, String> proxyEnv(String url) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String k : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
                "http_proxy", "https_proxy", "all_proxy"}) {
            m.put(k, url);
        }
        return m;
    }

    private void openBrowser(String url) {
        appendAsync("If no browser opens, visit:\n" + url);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ignored) {
        }
    }

    // --- state plumbing ---------------------------------------------------------------

    /** Persists a checkbox setting to the launcher's own settings.json (no registry). */
    private void persistFlag(String key, boolean value) {
        settings.setBool(key, value);
    }

    // --- small UI builders ------------------------------------------------------------

    /** A titled grid cell backed by MigLayout, so its rows stretch with the window. */
    private JPanel migCell(String title, String columns) {
        JPanel p = new JPanel(new net.miginfocom.swing.MigLayout(
                "fillx, hidemode 3, insets 8 10 8 10", columns, ""));
        p.setBackground(BG);
        p.setBorder(titledCellBorder(title));
        return p;
    }

    private JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        return l;
    }

    private JButton textButton(String text, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.addActionListener(action);
        return b;
    }

    private javax.swing.JCheckBox checkBox(String text, String prefKey, Runnable onToggle) {
        javax.swing.JCheckBox cb = new javax.swing.JCheckBox(text);
        cb.setOpaque(false);
        cb.setForeground(FG);
        cb.setFocusPainted(false);
        cb.setSelected(settings.getBool(prefKey, false));
        cb.addActionListener(e -> {
            persistFlag(prefKey, cb.isSelected());
            if (onToggle != null) onToggle.run();
        });
        return cb;
    }

    private void updateLaunchEnabled() {
        boolean ready = selectedAccount() != null && !busy && selectedInstall() != null;
        if (launchButton != null) launchButton.setEnabled(ready);
        if (removeAccountButton != null) removeAccountButton.setEnabled(selectedAccount() != null && !busy);
    }

    private void setBusy(boolean b, String status) {
        this.busy = b;
        if (addAccountButton != null) addAccountButton.setEnabled(!b);
        if (accountBox != null) accountBox.setEnabled(!b);
        if (updateButton != null) updateButton.setEnabled(!b);
        if (status != null) setStatus(b ? ACCENT : MUTED, status);
        updateLaunchEnabled();
    }

    private void setStatus(Color dot, String text) {
        statusDot.setForeground(dot);
        statusText.setText(text);
    }

    private void append(String line) {
        log.append(line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void setBusyAsync(boolean b, String s) { SwingUtilities.invokeLater(() -> setBusy(b, s)); }
    private void setStatusAsync(Color c, String s) { SwingUtilities.invokeLater(() -> setStatus(c, s)); }
    private void appendAsync(String line) { SwingUtilities.invokeLater(() -> append(line)); }

    private LauncherWindow() {
    }
}
