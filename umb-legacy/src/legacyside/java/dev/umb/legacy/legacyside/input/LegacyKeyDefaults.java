package dev.umb.legacy.legacyside.input;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fallback LWJGL2 codes for keybinding entries whose plan carries no code.
 *
 * <p>Some mods never put their keys in vanilla {@code KeyBinding}s with a discoverable
 * default: their plan entries carry {@code keyCode = INT_MIN} (unset) because the real
 * codes live in the mod's own config objects (e.g. MCHeli's {@code MCH_ConfigPrm}
 * defaults, applied to its own key class by each tick handler's {@code updateKeybind}).
 * Those codes are recovered once, by bytecode reading, and stored as data in
 * {@code *-key-defaults.json} files next to the input plans (mod identity stays data,
 * never a runtime branch). {@link LegacyKeyBindingSynthesis} consults this table when
 * an entry has no usable code; entries with neither are skipped and counted.</p>
 */
public final class LegacyKeyDefaults {
    private static volatile boolean loaded;
    private static final Map<String, Integer> CODES =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>());

    private LegacyKeyDefaults() { }

    /** Idempotent: loads every {@code *-key-defaults.json} in the plans directory once. */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        Path dir = LegacyInputPlanLoader.defaultPlansDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    if (p.getFileName().toString().endsWith("-key-defaults.json")) {
                        loadFile(p);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot enumerate key defaults in " + dir, e);
            }
        }
        loaded = true;
    }

    /** Test-only reset. */
    public static synchronized void clearForTest() {
        loaded = false;
        CODES.clear();
    }

    /** Loads one defaults file; returns the number of entries. */
    public static int loadFile(Path file) {
        int count = 0;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // The legacy build intentionally uses the 1.7.10 Gson API, which predates
            // JsonParser.parseReader(Reader); retain the equivalent instance call for Java 8.
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            JsonObject defaults = root.has("defaults") ? root.getAsJsonObject("defaults")
                    : new JsonObject();
            for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
                CODES.put(e.getKey(), Integer.valueOf(e.getValue().getAsInt()));
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot load key defaults " + file, e);
        }
        return count;
    }

    /** Fallback LWJGL2 code for a stable id, or null when unknown. */
    public static Integer codeFor(String stableId) {
        return stableId == null ? null : CODES.get(stableId);
    }
}
