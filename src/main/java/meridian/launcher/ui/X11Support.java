package meridian.launcher.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Works around AWT's blank window on non-reparenting X11 window managers (dwm, i3,
 * xmonad, awesome, …), where a Swing frame is mapped at the right size and shows nothing.
 * The cure is {@code _JAVA_AWT_WM_NONREPARENTING=1}, read by AWT as the toolkit starts —
 * too early to set from within the process — so the fix is to re-exec once with it set.
 *
 * <p>A trimmed, dependency-free copy of the proxy's {@code X11Relaunch}: the launcher
 * ships as its own jar and must not depend on the proxy, and the same Linux desktops hit
 * the same bug here.
 */
final class X11Support {

    private static final Set<String> NON_REPARENTING = Set.of(
            "dwm", "i3", "xmonad", "awesome", "bspwm", "herbstluftwm", "spectrwm",
            "qtile", "ratpoison", "cwm", "notion", "wmii", "2bwm", "monsterwm",
            "frankenwm", "katriawm", "dk", "sowm");

    private static final String FLAG = "_JAVA_AWT_WM_NONREPARENTING";

    private X11Support() {
    }

    /** Re-execs the JVM with the workaround set (and never returns) when it is needed. */
    static void relaunchIfNeeded(String[] originalArgs) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean x11 = os.contains("linux") || os.contains("bsd")
                    || os.contains("aix") || os.contains("sunos") || os.contains("unix");
            if (!x11) return;
            if (System.getenv("DISPLAY") == null || System.getenv(FLAG) != null) return;

            String wm = detectWindowManager();
            if (wm == null) {
                System.out.println("[launcher] Could not identify the window manager. If the window is"
                        + " blank, it does not reparent — relaunch with " + FLAG + "=1 set.");
                return;
            }
            if (!NON_REPARENTING.contains(wm.toLowerCase(Locale.ROOT))) return;

            List<String> command = currentCommand(originalArgs);
            if (command == null) {
                System.out.println("[launcher] " + wm + " does not reparent; start with " + FLAG
                        + "=1 to avoid a blank window.");
                return;
            }
            System.out.println("[launcher] " + wm + " does not reparent; restarting with "
                    + FLAG + "=1 so the window renders.");
            ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
            pb.environment().put(FLAG, "1");
            System.exit(pb.start().waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Never block startup on the workaround.
        }
    }

    private static String detectWindowManager() throws Exception {
        String check = xprop("-root", "_NET_SUPPORTING_WM_CHECK");
        if (check == null) return fromProc();
        int hash = check.indexOf("0x");
        if (hash < 0) return fromProc();
        String windowId = check.substring(hash).trim().split("\\s+")[0];
        String name = xprop("-id", windowId, "_NET_WM_NAME");
        if (name == null) return fromProc();
        int a = name.indexOf('"'), b = name.lastIndexOf('"');
        return (a >= 0 && b > a) ? name.substring(a + 1, b) : fromProc();
    }

    private static String fromProc() {
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) return null;
        try (var pids = Files.list(proc)) {
            return pids.map(dir -> {
                        try {
                            if (!Character.isDigit(dir.getFileName().toString().charAt(0))) return null;
                            return Files.readString(dir.resolve("comm")).trim().toLowerCase(Locale.ROOT);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(n -> n != null && NON_REPARENTING.contains(n))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String xprop(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("xprop");
        cmd.addAll(List.of(args));
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException notInstalled) {
            return null;
        }
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        return p.exitValue() == 0 ? out : null;
    }

    private static List<String> currentCommand(String[] originalArgs) {
        var info = ProcessHandle.current().info();
        var exe = info.command();
        var args = info.arguments();
        if (exe.isEmpty()) return null;
        List<String> command = new ArrayList<>();
        command.add(exe.get());
        if (args.isPresent()) {
            command.addAll(List.of(args.get()));
        } else {
            // Fall back to the app args; the JVM flags are lost but the relaunch still works.
            command.addAll(List.of(originalArgs));
        }
        return command;
    }
}
