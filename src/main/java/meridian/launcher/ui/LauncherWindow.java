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
import meridian.launcher.discovery.ListingsParamCapture;
import meridian.launcher.discovery.RouteRegistry;
import meridian.launcher.discovery.ServerDiscoveryRewriter;
import meridian.launcher.discovery.ServerParams;
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
    private JPanel versionRow;
    private javax.swing.JComboBox<ProxyItem> proxyBox;
    private javax.swing.JCheckBox useProxyCheck;
    private javax.swing.JCheckBox blockTelemetryCheck;
    private JButton launchButton;
    private ServersPanel serversPanel;

    private final meridian.launcher.Settings settings = meridian.launcher.Settings.defaultSettings();
    private static final String PREF_BLOCK_TELEMETRY = "blockTelemetry";
    private static final String PREF_USE_PROXY = "useProxy";
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

    private void build() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        frame = new JFrame("Meridian Launcher");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(900, 640);
        frame.setMinimumSize(new Dimension(760, 540));
        frame.setLocationByPlatform(true);

        JPanel launchTab = new JPanel(new BorderLayout());
        launchTab.setBackground(BG);
        launchTab.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        launchTab.add(buildHeader(), BorderLayout.NORTH);
        launchTab.add(buildLogArea(), BorderLayout.CENTER);
        launchTab.add(buildActions(), BorderLayout.SOUTH);

        serversPanel = new ServersPanel(provider, this::openBrowser, () -> {
            HytaleInstall inst = selectedInstall();
            return inst == null ? null : HytaleRoot.gameVersion(inst.root, inst.version);
        }, this::installedGameVersions);

        // The native Windows tab painter ignores our colors (renders light tabs on the dark
        // window). Feed the dark palette to the Basic tab defaults and force the Basic UI so
        // they're actually used.
        UIManager.put("TabbedPane.selected", BG);
        UIManager.put("TabbedPane.background", BAR);
        UIManager.put("TabbedPane.foreground", FG);
        UIManager.put("TabbedPane.contentAreaColor", BG);
        UIManager.put("TabbedPane.darkShadow", BG);
        UIManager.put("TabbedPane.shadow", BAR);
        UIManager.put("TabbedPane.light", BAR);
        UIManager.put("TabbedPane.highlight", BAR);
        UIManager.put("TabbedPane.focus", FG);
        UIManager.put("TabbedPane.borderHightlightColor", ACCENT);
        UIManager.put("TabbedPane.tabAreaBackground", BG);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(2, 0, 0, 0));

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI());
        tabs.setBackground(BAR);
        tabs.setForeground(FG);
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("Launch", launchTab);
        tabs.addTab("Servers", serversPanel);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == serversPanel) {
                serversPanel.ensureLoaded();
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


    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(BG);

        JLabel title = new JLabel("Meridian Launcher");
        title.setForeground(FG);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        subtitle = new JLabel("Add your Hytale account to get started.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        statusRow.setOpaque(false);
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusDot = new JLabel("●");
        statusDot.setForeground(MUTED);
        statusText = new JLabel(" ");
        statusText.setForeground(FG);
        statusText.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        statusRow.add(statusDot);
        statusRow.add(statusText);

        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(subtitle);
        header.add(statusRow);
        return header;
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
        scroll.setBorder(BorderFactory.createLineBorder(new Color(45, 45, 45)));
        scroll.setPreferredSize(new Dimension(0, 240));
        return scroll;
    }

    private JPanel buildActions() {
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBackground(BG);
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        // Hytale folder + version row.
        JPanel clientRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        clientRow.setBackground(BAR);
        clientRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        clientRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        clientRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel folderLabel = new JLabel("Hytale folder:");
        folderLabel.setForeground(FG);
        folderLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        clientField = new JTextField(26);
        clientField.setToolTipText("The Hytale folder (holds install/ and data/); default %APPDATA%/Hytale");
        HytaleRoot.locate(null).ifPresent(p -> clientField.setText(p.toString()));
        clientField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reloadVersions(); }
        });
        JButton browse = new JButton("Browse…");
        browse.setFocusPainted(false);
        browse.addActionListener(e -> chooseFolder());

        clientRow.add(folderLabel);
        clientRow.add(clientField);
        clientRow.add(browse);

        // Version row (its own line, above the folder): dropdown + Save version.
        JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        versionRow.setBackground(BAR);
        versionRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        versionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        versionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel versionLabel = new JLabel("Version:");
        versionLabel.setForeground(FG);
        versionLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        versionBox = new javax.swing.JComboBox<>();
        versionBox.setPrototypeDisplayValue("pre-release   ");
        versionBox.addActionListener(e -> updateLaunchEnabled());

        JButton saveVersionButton = new JButton("Save version");
        saveVersionButton.setFocusPainted(false);
        saveVersionButton.setToolTipText("Copy the selected version's install to a named"
                + " snapshot so it survives the next update");
        saveVersionButton.addActionListener(e -> saveVersion());

        versionRow.add(versionLabel);
        versionRow.add(versionBox);
        versionRow.add(saveVersionButton);

        JLabel proxyLabel = new JLabel("Proxy:");
        proxyLabel.setForeground(FG);
        proxyLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        proxyBox = new javax.swing.JComboBox<>();
        proxyBox.setPrototypeDisplayValue(new ProxyItem(java.nio.file.Path.of("meridian-proxy-x.y.z-all.jar")));
        proxyBox.setToolTipText("Meridian proxy jar to run (found next to the launcher)");
        versionRow.add(Box.createHorizontalStrut(12));
        versionRow.add(proxyLabel);
        versionRow.add(proxyBox);
        this.versionRow = versionRow;

        // Account row: pick which account to launch, add another, or remove one.
        JPanel accountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        accountRow.setBackground(BAR);
        accountRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        accountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        accountRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel accountLabel = new JLabel("Account:");
        accountLabel.setForeground(FG);
        accountLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        accountBox = new javax.swing.JComboBox<>();
        accountBox.setPrototypeDisplayValue(new ProfileItem(
                new meridian.launcher.auth.SessionProvider.ProfileRow(
                        new meridian.launcher.auth.Account("", "account", null, null),
                        new meridian.launcher.auth.HytaleAuth.Profile("", "a-long-profile-name"))));
        accountBox.addActionListener(e -> updateLaunchEnabled());

        addAccountButton = new JButton("Add account");
        addAccountButton.setFocusPainted(false);
        addAccountButton.addActionListener(e -> addAccount());

        removeAccountButton = new JButton("Remove");
        removeAccountButton.setFocusPainted(false);
        removeAccountButton.addActionListener(e -> removeAccount());

        accountRow.add(accountLabel);
        accountRow.add(accountBox);
        accountRow.add(addAccountButton);
        accountRow.add(removeAccountButton);

        // Launch row.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

        launchButton = new JButton("Launch game");
        launchButton.setFocusPainted(false);
        launchButton.setEnabled(false);
        launchButton.setMargin(new Insets(4, 18, 4, 18));
        launchButton.addActionListener(e -> launch());
        buttons.add(launchButton);

        blockTelemetryCheck = new javax.swing.JCheckBox("Block telemetry");
        blockTelemetryCheck.setOpaque(false);
        blockTelemetryCheck.setForeground(FG);
        blockTelemetryCheck.setFocusPainted(false);
        blockTelemetryCheck.setToolTipText("Route the game through a local proxy that refuses"
                + " its telemetry / crash-reporting hosts (" + String.join(", ", HytaleBackends.TELEMETRY) + ")");
        blockTelemetryCheck.setSelected(settings.getBool(PREF_BLOCK_TELEMETRY, false));
        blockTelemetryCheck.addActionListener(e ->
                persistFlag(PREF_BLOCK_TELEMETRY, blockTelemetryCheck.isSelected()));
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(blockTelemetryCheck);

        useProxyCheck = new javax.swing.JCheckBox("Use proxy");
        useProxyCheck.setOpaque(false);
        useProxyCheck.setForeground(FG);
        useProxyCheck.setFocusPainted(false);
        useProxyCheck.setToolTipText("Route the game's servers through the Meridian proxy: the"
                + " in-game server list is rewritten to the local proxy, which relays to the real"
                + " servers. Starts the selected proxy jar on launch.");
        useProxyCheck.setSelected(settings.getBool(PREF_USE_PROXY, false));
        useProxyCheck.addActionListener(e -> {
            persistFlag(PREF_USE_PROXY, useProxyCheck.isSelected());
            if (proxyBox != null) proxyBox.setEnabled(useProxyCheck.isSelected());
        });
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(useProxyCheck);

        south.add(accountRow);
        south.add(versionRow);
        south.add(clientRow);
        south.add(buttons);
        return south;
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
            boolean caWeInstalled = false;
            boolean capturing = false;
            Process proxyProcess = null;
            try {
                // Reuses the stored token when still valid, so launching another window
                // of the same account does not invalidate the ones already open.
                GameSession s = provider.acquireProfile(
                        account.id, item.profileUuid(), item.username(), this::openBrowser);

                ServerParamsStore store = ServerParamsStore.defaultStore();
                ServerParams params = store.get(version);
                capturing = params == null || !params.isComplete();

                Map<String, String> env = Map.of();
                if (useProxy) {
                    // Redirect gameplay UDP: MITM server-discovery to rewrite every listing to
                    // 127.0.0.1:<localPort>, record port→realServer in a routes file, and run the
                    // proxy in multiplex mode over that file. The game Direct-Connects to loopback
                    // and its QUIC flows through the proxy, which relays to the real server.
                    Path caDir = meridian.launcher.AppPaths.resolve("ca");
                    captureCa = CertificateAuthority.loadOrCreate(caDir);
                    if (WindowsCaTrust.isWindows() && !WindowsCaTrust.isInstalled(captureCa.caCertificate())) {
                        WindowsCaTrust.install(caDir.resolve("meridian-ca.crt"));
                        caWeInstalled = true;
                    }
                    RouteRegistry routes = RouteRegistry.create(meridian.launcher.AppPaths.resolve("proxy-routes.txt"));
                    proxyProcess = ProxyLauncher.startMultiplex(proxyJar, routes.routesFile(), s.sessionToken);
                    Map<String, ExchangeHandler> handlers = new java.util.HashMap<>();
                    handlers.put("server-discovery.hytale.com", new ServerDiscoveryRewriter(store, routes));
                    java.util.Set<String> block = blockTelemetry ? HytaleBackends.TELEMETRY : java.util.Set.of();
                    MitmProxy mitm = new MitmProxy(0, captureCa, java.util.Set.of(), handlers, block);
                    mitm.start();
                    proxy = mitm;
                    env = proxyEnv("http://127.0.0.1:" + mitm.port());
                    if (!WindowsCaTrust.isWindows()) {
                        env.put("SSL_CERT_FILE", caDir.resolve("meridian-ca.crt").toString());
                    }
                    appendAsync("Proxy ON — " + proxyJar.getFileName() + " (multiplex); the in-game"
                            + " server list is redirected through it"
                            + (blockTelemetry ? "; telemetry blocked." : "."));
                } else if (capturing) {
                    // First launch of this version: capture its server-discovery params
                    // (protocolVersion + clientSeed) so it becomes browsable in the Servers tab.
                    Path caDir = meridian.launcher.AppPaths.resolve("ca");
                    captureCa = CertificateAuthority.loadOrCreate(caDir);
                    if (WindowsCaTrust.isWindows() && !WindowsCaTrust.isInstalled(captureCa.caCertificate())) {
                        WindowsCaTrust.install(caDir.resolve("meridian-ca.crt"));
                        caWeInstalled = true;
                    }
                    Map<String, ExchangeHandler> handlers = new java.util.HashMap<>();
                    handlers.put("server-discovery.hytale.com", new ListingsParamCapture(store));
                    java.util.Set<String> block = blockTelemetry ? HytaleBackends.TELEMETRY : java.util.Set.of();
                    MitmProxy mitm = new MitmProxy(0, captureCa, java.util.Set.of(), handlers, block);
                    mitm.start();
                    proxy = mitm;
                    env = proxyEnv("http://127.0.0.1:" + mitm.port());
                    if (!WindowsCaTrust.isWindows()) {
                        env.put("SSL_CERT_FILE", caDir.resolve("meridian-ca.crt").toString());
                    }
                    appendAsync("First launch of " + version + " — capturing its server list params"
                            + (blockTelemetry ? " (telemetry blocked)." : ".")
                            + " Open the in-game Servers browser (random list) once.");
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
                if (capturing && !useProxy) {
                    ServerParams got = store.get(version);
                    if (got != null && got.isComplete()) {
                        appendAsync("Captured server params for " + version + " — it's now in the Servers tab.");
                    } else if (got != null && got.protocolVersion() != null) {
                        appendAsync("Got protocolVersion for " + version + " but not clientSeed — open the"
                                + " random server list in-game next time to finish capturing.");
                    } else {
                        appendAsync("No server params captured for " + version
                                + " (server browser not opened, or the host is pinned).");
                    }
                }
                setStatusAsync(BG, " ");   // back to idle; the log records the exit
            } catch (Exception e) {
                appendAsync("Launch failed: " + e.getMessage());
                setBusyAsync(false, null);
            } finally {
                if (proxyProcess != null && proxyProcess.isAlive()) {
                    proxyProcess.destroy();   // stop the multiplex proxy when the game exits
                }
                if (proxy != null) {
                    try { proxy.close(); } catch (Exception ignored) { }
                }
                if (caWeInstalled && captureCa != null) {
                    try {
                        WindowsCaTrust.uninstall(captureCa.caCertificate());
                    } catch (Exception ignored) {
                    }
                }
            }
        });
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

    private void updateLaunchEnabled() {
        boolean ready = selectedAccount() != null && !busy && selectedInstall() != null;
        if (launchButton != null) launchButton.setEnabled(ready);
        if (removeAccountButton != null) removeAccountButton.setEnabled(selectedAccount() != null && !busy);
    }

    private void setBusy(boolean b, String status) {
        this.busy = b;
        if (addAccountButton != null) addAccountButton.setEnabled(!b);
        if (accountBox != null) accountBox.setEnabled(!b);
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
