package dev.umb.legacy.legacyside.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.settings.KeyBinding;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the client input layer the server-side universe never constructs.
 *
 * <p>Mod client proxies (which register key bindings and event subscribers) never run in
 * this universe, so the mods' own static {@code KeyBinding} fields stay null and their
 * polling code could never fire. Synthesis replays the registration from the bytecode-derived
 * {@code *-input-plans.json} data: for every resolved keybinding entry it constructs the
 * {@code KeyBinding} (whose constructor self-registers into the vanilla static lookup
 * structures without needing a {@code Minecraft} instance) and assigns the owning mod's
 * static field, exactly as the client proxy would have done. {@code ClientRegistry} itself
 * is bypassed on purpose: it dereferences {@code Minecraft.getMinecraft().gameSettings},
 * which does not exist here.</p>
 *
 * <p>Universal: mod identity is data (plan JSON), never a runtime branch. Entries whose
 * holder field is absent, non-static, or not a {@code KeyBinding} (e.g. a mod's own key
 * class, or instance fields on tick handlers that never exist headless) are counted as
 * skipped; those keys are still mirrored through {@link LegacyLwjglState}, which is what
 * raw-LWJGL polling mods observe.</p>
 */
public final class LegacyKeyBindingSynthesis {
    private static volatile boolean installed;
    private static final Map<String, KeyBinding> BY_ID =
            Collections.synchronizedMap(new LinkedHashMap<String, KeyBinding>());
    private static final Map<String, Integer> CODE_BY_ID =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>());
    private static final Map<Integer, List<KeyBinding>> BY_CODE =
            Collections.synchronizedMap(new LinkedHashMap<Integer, List<KeyBinding>>());
    private static final List<String> REPORT =
            Collections.synchronizedList(new ArrayList<String>());
    private static int skipped;

    private LegacyKeyBindingSynthesis() { }

    /** Idempotent: constructs every resolved plan binding once per universe lifetime. */
    public static synchronized void ensureSynthesized() {
        if (installed) {
            return;
        }
        LegacyKeyDefaults.ensureLoaded();
        Path dir = LegacyInputPlanLoader.defaultPlansDir();
        installPlans(dir);
        installed = true;
        LegacyInputDiag.log("synthesis from " + dir + ": " + BY_ID.size()
                + " bindings, " + skipped + " skipped without code");
    }

    /** Test-only reset: the JUnit JVM hosts many test classes, synthesis must be repeatable. */
    public static synchronized void clearForTest() {
        installed = false;
        BY_ID.clear();
        CODE_BY_ID.clear();
        BY_CODE.clear();
        REPORT.clear();
        skipped = 0;
    }

    /** Synthesizes one plan file; returns the number of constructed bindings. */
    public static int installFile(Path file) {
        int count = 0;
        LegacyKeyDefaults.ensureLoaded();
        String namespace = namespaceOf(file);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // The legacy build intentionally uses the 1.7.10 Gson API, which predates
            // JsonParser.parseReader(Reader); retain the equivalent instance call for Java 8.
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            JsonArray bindings = root.has("keybindings") ? root.getAsJsonArray("keybindings")
                    : new JsonArray();
            if (root.has("namespace") && !root.get("namespace").getAsString().trim().isEmpty()) {
                namespace = root.get("namespace").getAsString().trim();
            }
            for (JsonElement e : bindings) {
                JsonObject b = e.getAsJsonObject();
                // Unresolved entries are skipped unless the defaults table gives them a
                // code: packet plans need resolved:true, but synthesis only needs a code
                // plus a holder description (mods with their own key classes are never
                // "resolved" yet still need mirror state).
                boolean resolved = b.has("resolved") && b.get("resolved").getAsBoolean();
                if (!resolved && LegacyKeyDefaults.codeFor(text(b, "stableId")) == null) {
                    skipped++;
                    continue;
                }
                if (synthesize(namespace, b)) {
                    count++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot synthesize input bindings from " + file, e);
        }
        return count;
    }

    /** Synthesized instance for a stable id, or null when that id has no vanilla binding. */
    public static KeyBinding bindingFor(String stableId) {
        return stableId == null ? null : BY_ID.get(stableId);
    }

    /** LWJGL2 code for a stable id, or null when unknown. */
    public static Integer codeFor(String stableId) {
        return stableId == null ? null : CODE_BY_ID.get(stableId);
    }

    /** Every synthesized instance registered under an LWJGL2 code (never null, possibly empty). */
    public static List<KeyBinding> bindingsForCode(int lwjglCode) {
        List<KeyBinding> found = BY_CODE.get(Integer.valueOf(lwjglCode));
        return found == null ? Collections.<KeyBinding>emptyList()
                : Collections.unmodifiableList(new ArrayList<KeyBinding>(found));
    }

    /** Human-readable per-binding boot report (namespace, stable id, holder or skip reason). */
    public static List<String> report() {
        synchronized (REPORT) {
            return Collections.unmodifiableList(new ArrayList<String>(REPORT));
        }
    }

    /**
     * Entries skipped without producing a binding (unresolved with no default code,
     * missing fields, no code anywhere, construct failure). Holder-assignment failures
     * do NOT count here: the binding is still synthesized and mirrored; they are
     * recorded in {@link #report()} only.
     */
    public static int skipped() {
        return skipped;
    }

    private static String namespaceOf(Path file) {
        String name = file.getFileName().toString();
        int dash = name.indexOf("-input-plans.json");
        return dash > 0 ? name.substring(0, dash) : "umb";
    }

    private static boolean synthesize(String namespace, JsonObject b) {
        String field = text(b, "field");
        String description = text(b, "description");
        String category = text(b, "category");
        String stableId = text(b, "stableId");
        if (field == null || description == null || category == null || stableId == null) {
            skipped++;
            return false;
        }
        int keyCode = Integer.MIN_VALUE;
        if (b.has("keyCode")) {
            try {
                keyCode = b.get("keyCode").getAsInt();
            } catch (Exception e) {
                keyCode = Integer.MIN_VALUE;
            }
        }
        // Entries without a usable code (mods with their own key classes read the code
        // from their own config objects) fall back to the bytecode-recovered defaults
        // table; entries with neither are skipped, never guessed.
        boolean defaulted = false;
        if (keyCode == Integer.MIN_VALUE) {
            Integer fallback = LegacyKeyDefaults.codeFor(stableId);
            if (fallback == null) {
                REPORT.add(namespace + " " + stableId + " no-code: " + field);
                skipped++;
                return false;
            }
            keyCode = fallback.intValue();
            defaulted = true;
        }
        KeyBinding binding;
        try {
            binding = new KeyBinding(description, keyCode, category);
        } catch (Throwable t) {
            REPORT.add(namespace + " " + stableId + " construct-failed: " + t);
            skipped++;
            return false;
        }
        String registered = LegacyKeyBindingRegistry.register(binding);
        CODE_BY_ID.put(stableId, Integer.valueOf(keyCode));
        BY_ID.put(stableId, binding);
        synchronized (BY_CODE) {
            List<KeyBinding> list = BY_CODE.get(Integer.valueOf(keyCode));
            if (list == null) {
                list = new ArrayList<KeyBinding>();
                BY_CODE.put(Integer.valueOf(keyCode), list);
            }
            list.add(binding);
        }
        if (!registered.equals(stableId)) {
            REPORT.add(namespace + " " + stableId + " id-mismatch: registry=" + registered);
        }
        assignHolder(namespace, stableId, field, binding);
        if (defaulted) {
            REPORT.add(namespace + " " + stableId + " code-from-defaults: " + keyCode);
        }
        return true;
    }

    private static void assignHolder(String namespace, String stableId, String field, KeyBinding binding) {
        int hash = field.indexOf('#');
        if (hash <= 0 || hash == field.length() - 1) {
            REPORT.add(namespace + " " + stableId + " no-holder: " + field);
            return;
        }
        String owner = field.substring(0, hash).replace('/', '.');
        String name = field.substring(hash + 1);
        try {
            // Holder classes are mod classes: resolve through the mod-loader chain,
            // for the same reason as the plan message classes.
            Class<?> ownerClass = LegacyModClasses.forName(owner);
            Field f = ownerClass.getDeclaredField(name);
            if (!Modifier.isStatic(f.getModifiers())
                    || !KeyBinding.class.isAssignableFrom(f.getType())) {
                REPORT.add(namespace + " " + stableId + " skip-holder ("
                        + (Modifier.isStatic(f.getModifiers()) ? "not-KeyBinding" : "not-static")
                        + "): " + field);
                return;
            }
            f.setAccessible(true);
            f.set(null, binding);
            REPORT.add(namespace + " " + stableId + " -> " + field);
        } catch (Throwable t) {
            REPORT.add(namespace + " " + stableId + " holder-absent: " + field
                    + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static String text(JsonObject b, String key) {
        return b.has(key) ? b.get(key).getAsString() : null;
    }

    private static void installPlans(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            REPORT.add("no plans directory: " + directory);
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (p.getFileName().toString().endsWith("-input-plans.json")) {
                    installFile(p);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot enumerate input plans in " + directory, e);
        }
    }
}
