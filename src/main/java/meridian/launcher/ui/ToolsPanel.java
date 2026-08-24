package meridian.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

import meridian.launcher.auth.GameSession;
import meridian.launcher.auth.SessionProvider;
import meridian.launcher.lookup.AccountLookupClient;

/**
 * A "Tools" tab with a read-only <b>player profile lookup</b>: resolve a username or UUID to its
 * public {@code {uuid, username}} profile via Hytale's Profile Service (needs a minted session
 * token).
 */
public final class ToolsPanel extends JPanel {

    private static final Color BG = new Color(24, 24, 24);
    private static final Color BAR = new Color(32, 32, 32);
    private static final Color FG = new Color(216, 216, 216);
    private static final Color MUTED = new Color(150, 150, 150);

    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SessionProvider provider;
    private final Consumer<String> browserOpener;
    private final AccountLookupClient client = new AccountLookupClient();

    private JTextField profileField;
    private JButton lookupButton;
    private JTextArea profileResult;

    public ToolsPanel(SessionProvider provider, Consumer<String> browserOpener) {
        this.provider = provider;
        this.browserOpener = browserOpener;
        build();
    }

    private void build() {
        setLayout(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBackground(BG);
        stack.add(buildProfileSection());
        stack.add(Box.createVerticalGlue());

        add(stack, BorderLayout.CENTER);
    }

    // ── Player profile lookup ────────────────────────────────────────────────────────────────

    private JPanel buildProfileSection() {
        profileField = new JTextField();
        lookupButton = new JButton("Look up");
        lookupButton.setFocusPainted(false);
        profileResult = new JTextArea();
        profileResult.setEditable(false);
        profileResult.setLineWrap(false);          // full JSON — keep lines and scroll, don't wrap
        profileResult.setBackground(new Color(18, 18, 18));
        profileResult.setForeground(FG);
        profileResult.setCaretColor(FG);
        profileResult.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        profileResult.setMargin(new java.awt.Insets(8, 8, 8, 8));
        profileResult.setText("Enter a username or UUID and look it up. The full response is shown and selectable.");

        JScrollPane resultScroll = new JScrollPane(profileResult);
        resultScroll.setBorder(BorderFactory.createLineBorder(BAR));
        resultScroll.getViewport().setBackground(new Color(18, 18, 18));
        resultScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultScroll.setPreferredSize(new Dimension(560, 320));

        Runnable run = this::lookupProfile;
        lookupButton.addActionListener(e -> run.run());
        profileField.addActionListener(e -> run.run());

        JPanel section = section("Player profile lookup",
                "Resolve a username or UUID to its public profile (account-data.hytale.com).");
        section.add(inputRow("Username or UUID:", profileField, lookupButton));
        section.add(Box.createVerticalStrut(8));
        section.add(resultScroll);
        return section;
    }

    private void lookupProfile() {
        String input = profileField.getText() == null ? "" : profileField.getText().trim();
        if (input.isEmpty()) {
            setProfile("Enter a username or UUID.");
            return;
        }
        boolean byUuid = UUID_RE.matcher(input).matches();
        lookupButton.setEnabled(false);
        setProfile("Looking up " + (byUuid ? "UUID " : "username ") + input + "…");
        Thread.startVirtualThread(() -> {
            try {
                GameSession session = provider.acquire(browserOpener);   // cached if a session is live
                String bearer = session.sessionToken;
                AccountLookupClient.Profile p = byUuid
                        ? client.byUuid(input, bearer)
                        : client.byUsername(input, bearer);
                SwingUtilities.invokeLater(() -> {
                    if (p.found()) {
                        setProfile(prettyProfile(p.raw()));
                    } else if (p.status() == 404) {
                        setProfile("Not found — no profile for “" + input + "”.");
                    } else {
                        setProfile("HTTP " + p.status() + "\n\n" + p.raw());
                    }
                    lookupButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setProfile("Failed: " + ex.getMessage());
                    lookupButton.setEnabled(true);
                });
            }
        });
    }

    private void setProfile(String text) {
        profileResult.setText(text);
        profileResult.setCaretPosition(0);
    }

    // ── shared building blocks ───────────────────────────────────────────────────────────────

    /** A titled section panel that stacks its children vertically. */
    private JPanel section(String title, String subtitle) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BAR), title);
        tb.setTitleColor(MUTED);
        tb.setTitleFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        p.setBorder(BorderFactory.createCompoundBorder(tb,
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getMaximumSize().height));
        if (subtitle != null) {
            JLabel s = new JLabel(subtitle);
            s.setForeground(MUTED);
            s.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(s);
            p.add(Box.createVerticalStrut(8));
        }
        return p;
    }

    /** A left-aligned row: label · growing field · button. */
    private JPanel inputRow(String labelText, JTextField field, JButton button) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel l = new JLabel(labelText);
        l.setForeground(FG);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        return row;
    }

    /** Pretty-prints the whole profile JSON, expanding the nested "skin" JSON string for readability. */
    private static String prettyProfile(String raw) {
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(raw);
            if (el.isJsonObject()) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (o.has("skin") && o.get("skin").isJsonPrimitive()
                        && o.getAsJsonPrimitive("skin").isString()) {
                    try {
                        o.add("skin", com.google.gson.JsonParser.parseString(o.get("skin").getAsString()));
                    } catch (Exception ignored) {
                        // leave "skin" as its raw string if it isn't valid JSON
                    }
                }
            }
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(el);
        } catch (Exception e) {
            return raw;   // not JSON — show verbatim
        }
    }
}
