package meridian.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple on/off launcher settings (e.g. the "Block telemetry" checkbox), stored as JSON in
 * {@code <base>/settings.json} — <b>not</b> the Windows registry / Java Preferences, so the
 * launcher leaves nothing behind outside its own folder ({@link AppPaths}).
 */
public final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public Settings(Path file) {
        this.file = file;
    }

    public static Settings defaultSettings() {
        return new Settings(AppPaths.resolve("settings.json"));
    }

    public synchronized boolean getBool(String key, boolean def) {
        Boolean v = load().get(key);
        return v != null ? v : def;
    }

    public synchronized void setBool(String key, boolean value) {
        Map<String, Boolean> all = load();
        all.put(key, value);
        save(all);
    }

    private Map<String, Boolean> load() {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, Boolean> onDisk = GSON.fromJson(r, DiskShape.class);
                if (onDisk != null) {
                    return onDisk;
                }
            } catch (IOException | RuntimeException e) {
                // Corrupt/unreadable: start fresh rather than failing.
            }
        }
        return new LinkedHashMap<>();
    }

    private void save(Map<String, Boolean> all) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(all, w);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save settings to " + file, e);
        }
    }

    /** Gson reifiable type for {@code Map<String, Boolean>}. */
    private static final class DiskShape extends LinkedHashMap<String, Boolean> {
    }
}
