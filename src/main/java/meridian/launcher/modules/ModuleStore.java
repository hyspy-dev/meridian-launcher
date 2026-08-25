package meridian.launcher.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages one proxy modules folder for the launcher. The proxy loads every {@code *.jar} in the
 * folder <b>root</b> (non-recursive), so a module is <em>enabled</em> when its jar sits in the root
 * and <em>disabled</em> when parked in the {@code disabled/} subfolder (the proxy never scans it).
 *
 * <p>Used for both scopes: the shared default set ({@code <proxy-jar-dir>/modules}) and a per-server
 * set ({@code <proxy-jar-dir>/<host_port>/modules}) — same folder shape, different path.
 */
public final class ModuleStore {

    private final Path folder;

    public ModuleStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    private Path disabledDir() {
        return folder.resolve("disabled");
    }

    /** One jar in a modules folder, its enabled state, and its manifest (null if not a module). */
    public record InstalledModule(Path jar, boolean enabled, ModuleManifest manifest) {
        /** A display name: the manifest name, else the jar file name. */
        public String displayName() {
            return manifest != null && manifest.name() != null
                    ? manifest.name() : jar.getFileName().toString();
        }
    }

    /** Every module jar in the folder (root = enabled, {@code disabled/} = disabled). */
    public List<InstalledModule> list() {
        List<InstalledModule> out = new ArrayList<>();
        collectFrom(folder, true, out);
        collectFrom(disabledDir(), false, out);
        out.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return out;
    }

    private static void collectFrom(Path dir, boolean enabled, List<InstalledModule> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> jars = Files.list(dir)) {
            jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> out.add(new InstalledModule(p, enabled, manifestOf(p))));
        } catch (IOException ignored) {
        }
    }

    private static ModuleManifest manifestOf(Path jar) {
        try {
            return ModuleManifest.fromJar(jar);
        } catch (IOException e) {
            return null;
        }
    }

    /** Moves a module between the root (enabled) and {@code disabled/} (disabled). */
    public void setEnabled(InstalledModule module, boolean enabled) throws IOException {
        if (module.enabled() == enabled) return;
        Path target = enabled
                ? folder.resolve(module.jar().getFileName())
                : disabledDir().resolve(module.jar().getFileName());
        Files.createDirectories(target.getParent());
        Files.move(module.jar(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Copies a jar into the folder as an enabled module. The jar must contain {@code module.json}.
     * If a module with the same manifest name is already present (under any file name, enabled or
     * not), it is removed first so the store never holds two copies of one module.
     */
    public InstalledModule install(Path srcJar) throws IOException {
        ModuleManifest manifest = ModuleManifest.fromJar(srcJar);
        if (manifest == null) {
            throw new IOException("Not a Meridian module — no module.json in " + srcJar.getFileName());
        }
        for (InstalledModule existing : list()) {
            if (existing.manifest() != null && manifest.name().equals(existing.manifest().name())) {
                Files.deleteIfExists(existing.jar());
            }
        }
        Files.createDirectories(folder);
        Path dst = folder.resolve(srcJar.getFileName());
        Files.copy(srcJar, dst, StandardCopyOption.REPLACE_EXISTING);
        return new InstalledModule(dst, true, manifest);
    }

    public void remove(InstalledModule module) throws IOException {
        Files.deleteIfExists(module.jar());
    }
}
