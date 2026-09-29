package dev.umb.hostagent.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.hostagent.AgentLog;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One twin {@link KeyMapping} per legacy keybinding, registered from the same plan data
 * the legacy side synthesizes from. One {@code KeyMapping.Category} per legacy mod
 * namespace ({@code Identifier(namespace, "legacy")}); the translation key is the legacy
 * description, which for real mods already is a lang key.
 *
 * <p>Plan entries carry LWJGL2 codes; {@code *-key-defaults.json} overlays fill entries
 * whose plan code is unset (mods with their own key classes). Mouse codes (negative,
 * button = code + 100) become {@code Type.MOUSE} twins; anything with neither a plan
 * code nor a default is skipped and reported, never guessed.</p>
 */
public final class LegacyInputBootstrap {
    /** One twin registration, fully derived from plan data. */
    public record Twin(String namespace, String stableId, String translationKey,
                       InputConstants.Type type, int defaultCode) { }

    private static volatile boolean done;

    private LegacyInputBootstrap() { }

    /**
     * Idempotent: collects twins from the plans directory, registers them, and appends
     * them to {@code options.keyMappings} so the Controls screen lists them (rebinds
     * flow back through the same per-tick sample path). All best-effort: sampling works
     * even if the Controls append fails.
     */
    public static void ensure(Minecraft mc, LegacyKeyMappingRegistry keys) {
        if (done) return;
        synchronized (LegacyInputBootstrap.class) {
            if (done) return;
            try {
                Path dir = plansDir();
                List<Twin> twins = collectBridgeTwins();
                if (twins.isEmpty()) twins = collectTwins(dir);
                for (Twin twin : twins) {
                    keys.register(twin.namespace(), twin.stableId(), twin.translationKey(),
                            twin.type(), twin.defaultCode());
                }
                appendToOptions(mc, keys);
                // Options.load ran before these late-discovered mappings existed. Reload once
                // after appending so existing options.txt values win over legacy defaults.
                if (!twins.isEmpty() && mc != null && mc.options != null) mc.options.load();
                Map<String, Integer> perNs = new LinkedHashMap<>();
                for (Twin twin : twins) {
                    perNs.merge(twin.namespace(), 1, Integer::sum);
                }
                LegacyInputDiag.loud("twins registered: " + twins.size()
                        + " (plans dir " + dir.toAbsolutePath() + "): " + perNs);
                LegacyInputDiag.loud("registered " + keys.mappings().size()
                        + " legacy key mappings in " + perNs.size() + " categories");
            } catch (Throwable t) {
                AgentLog.error("LegacyInputBootstrap.ensure", t, 2);
            }
            done = true;
        }
    }

    private static List<Twin> collectBridgeTwins() {
        LegacyBridge bridge = dev.umb.hostagent.content.UmbBridgeHost.get();
        if (bridge == null) return java.util.Collections.emptyList();
        try {
            List<Twin> out = new ArrayList<>();
            List<LegacyBridge.KeyBindingData> data = bridge.keyBindings();
            if (data == null) return out;
            for (LegacyBridge.KeyBindingData b : data) {
                if (b == null || b.stableId == null || b.description == null) continue;
                int code = b.defaultCode;
                if (code < 0) {
                    out.add(new Twin(b.namespace, b.stableId, b.description,
                            InputConstants.Type.MOUSE, code + 100));
                } else {
                    try {
                        out.add(new Twin(b.namespace, b.stableId, b.description,
                                InputConstants.Type.KEYSYM, Lwjgl2ToGlfw.keyboardToGlfw(code)));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return out;
        } catch (Throwable t) {
            AgentLog.error("LegacyInputBootstrap.collectBridgeTwins", t, 1);
            return java.util.Collections.emptyList();
        }
    }

    static Path plansDir() {
        String configured = System.getProperty("umb.inputPlans");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        // derive from its location instead of the process working directory.
        try {
            java.nio.file.Path snapshot = dev.umb.hostagent.HostAgent.snapshotPath();
            if (snapshot != null && snapshot.getParent() != null
                    && hasPlans(snapshot.getParent())) {
                return snapshot.getParent();
            }
        } catch (Throwable ignored) { }
        String repo = System.getProperty("umb.repo");
        if (repo != null && !repo.isBlank()) {
            Path p = Paths.get(repo, "research/out/legacy");
            if (hasPlans(p)) {
                return p;
            }
        }
        return Paths.get("research/out/legacy");
    }

    private static boolean hasPlans(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir,
                "*-input-plans.json")) {
            return ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    /** Pure data: every twin the plans directory yields, in file order. */
    static List<Twin> collectTwins(Path dir) {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        List<Path> plans = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.list(dir)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    String name = p.getFileName().toString();
                    if (name.endsWith("-key-defaults.json")) {
                        defaults.putAll(readDefaults(p));
                    } else if (name.endsWith("-input-plans.json")) {
                        plans.add(p);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot enumerate input plans in " + dir, e);
            }
        }
        plans.sort(null);
        List<Twin> out = new ArrayList<>();
        for (Path plan : plans) {
            out.addAll(readPlan(plan, defaults));
        }
        return out;
    }

    private static List<Twin> readPlan(Path file, Map<String, Integer> defaults) {
        List<Twin> out = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            String namespace = root.has("namespace")
                    && !root.get("namespace").getAsString().isBlank()
                    ? root.get("namespace").getAsString().trim()
                    : file.getFileName().toString().replace("-input-plans.json", "");
            JsonArray bindings = root.has("keybindings") ? root.getAsJsonArray("keybindings")
                    : new JsonArray();
            for (JsonElement e : bindings) {
                JsonObject b = e.getAsJsonObject();
                if (!b.has("stableId") || !b.has("description")) continue;
                String stableId = b.get("stableId").getAsString();
                String description = b.get("description").getAsString();
                Integer code = null;
                if (b.has("keyCode")) {
                    try {
                        int c = b.get("keyCode").getAsInt();
                        if (c != Integer.MIN_VALUE) code = c;
                    } catch (Exception ignored) { }
                }
                if (code == null) code = defaults.get(stableId);
                if (code == null) continue;
                if (code < 0) {
                    out.add(new Twin(namespace, stableId, description,
                            InputConstants.Type.MOUSE, code + 100));
                } else {
                    int glfw;
                    try {
                        glfw = Lwjgl2ToGlfw.keyboardToGlfw(code);
                    } catch (IllegalArgumentException unknown) {
                        continue;
                    }
                    out.add(new Twin(namespace, stableId, description,
                            InputConstants.Type.KEYSYM, glfw));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read input plans " + file, e);
        }
        return out;
    }

    private static Map<String, Integer> readDefaults(Path file) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonObject defaults = root.has("defaults") ? root.getAsJsonObject("defaults")
                    : new JsonObject();
            for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsInt());
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read key defaults " + file, e);
        }
        return out;
    }

    private static void appendToOptions(Minecraft mc, LegacyKeyMappingRegistry keys) {
        try {
            if (mc == null || mc.options == null) return;
            List<KeyMapping> twins = new ArrayList<>(keys.mappings().values());
            if (twins.isEmpty()) return;
            KeyMapping[] current = mc.options.keyMappings;
            for (KeyMapping m : current) {
                twins.remove(m);
            }
            if (twins.isEmpty()) return;
            KeyMapping[] grown = java.util.Arrays.copyOf(current, current.length + twins.size());
            for (int i = 0; i < twins.size(); i++) {
                grown[current.length + i] = twins.get(i);
            }
            Field f = mc.options.getClass().getDeclaredField("keyMappings");
            f.setAccessible(true);
            f.set(mc.options, grown);
        } catch (Throwable t) {
            AgentLog.error("LegacyInputBootstrap.appendToOptions", t, 1);
        }
    }

    /** Test-only reset for the JVM-wide once guard. */
    static void clearForTest() {
        done = false;
    }
}
