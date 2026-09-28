package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tolerant reader for research/out/legacy/hbm-snapshot.json.
 *
 * Nothing here touches net.minecraft, so it is unit-testable off the game classpath.
 * Every field is optional; a malformed record yields defaults rather than an exception.
 */
public final class LegacySnapshot {

    public final List<BlockRec> blocks = new ArrayList<>();
    public final List<ItemRec> items = new ArrayList<>();
    public final List<TabRec> tabs = new ArrayList<>();
    public final List<LegacyRecipe> recipes = new ArrayList<>();
    /**
     * Every {@code items[]} record whose id starts with {@code "minecraft:"}, collected
     * independently of the {@code namespace} filter above (populated regardless of which
     * namespace was requested). This is the authoritative 1.7.10 vanilla item registry-name list
     * {@link VanillaItemBridge} needs -- the main {@link #items} list above only ever holds the
     * mod's OWN namespace records, since vanilla ids already exist on 26.2 and are never
     * (re)registered by {@link Registrar}.
     */
    public final List<ItemRec> vanillaItems = new ArrayList<>();

    /** Labels of the twelve 1.7.10 vanilla creative tabs; never re-created on 26.2. */
    public static final List<String> VANILLA_TAB_LABELS = List.of(
            "buildingBlocks", "decorations", "redstone", "transportation", "misc", "search",
            "food", "tools", "combat", "brewing", "materials", "inventory");

    public static LegacySnapshot load(Path file, String namespace) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r), namespace);
        }
    }

    public static LegacySnapshot parseString(String json, String namespace) {
        return parse(JsonParser.parseString(json), namespace);
    }

    private static LegacySnapshot parse(JsonElement root, String namespace) {
        LegacySnapshot s = new LegacySnapshot();
        if (root == null || !root.isJsonObject()) return s;
        JsonObject o = root.getAsJsonObject();

        for (JsonElement e : arr(o, "blocks")) {
            if (!e.isJsonObject()) continue;
            BlockRec b = BlockRec.from(e.getAsJsonObject());
            if (b != null && matches(b.id, namespace)) s.blocks.add(b);
        }
        for (JsonElement e : arr(o, "items")) {
            if (!e.isJsonObject()) continue;
            ItemRec i = ItemRec.from(e.getAsJsonObject());
            if (i == null) continue;
            if (matches(i.id, namespace)) s.items.add(i);
            if (isVanilla(i.id)) s.vanillaItems.add(i);
        }
        for (JsonElement e : arr(o, "creativeTabs")) {
            if (!e.isJsonObject()) continue;
            TabRec t = TabRec.from(e.getAsJsonObject());
            if (t != null) s.tabs.add(t);
        }
        JsonElement rawRecipes = o.get("recipes");
        if (rawRecipes != null && rawRecipes.isJsonObject()) {
            JsonObject ro = rawRecipes.getAsJsonObject();
            for (JsonElement e : arr(ro, "crafting")) if (e.isJsonObject()) s.recipes.add(LegacyRecipe.crafting(e.getAsJsonObject()));
            for (JsonElement e : arr(ro, "smelting")) if (e.isJsonObject()) s.recipes.add(LegacyRecipe.smelting(e.getAsJsonObject()));
        }
        return s;
    }

    /**
     * Only records belonging to the mod namespace are materialised; vanilla ids already exist on
     * 26.2. Case-INSENSITIVE on purpose (laneCasing, Bug 1): 1.7.10 modids are free-form and the
     * caller may pass either the raw legacy modid exactly as registered (e.g. "IronChest") or an
     * already-sanitized 26.2 namespace (e.g. "ironchest") - both must match the same records,
     * since they identify the same mod. The legacy id itself is returned/stored verbatim
     * (untouched) by every {@code *Rec.from(...)} reader; only this membership test folds case.
     */
    private static boolean matches(String id, String namespace) {
        if (id == null || namespace == null) return false;
        return id.regionMatches(true, 0, namespace, 0, namespace.length())
                && id.length() > namespace.length()
                && id.charAt(namespace.length()) == ':';
    }

    private static boolean isVanilla(String id) {
        return id != null && id.startsWith("minecraft:");
    }

    // ---------------- json helpers (shared by the record classes) ----------------

    static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : new JsonArray();
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String v = e.getAsString();
        return (v == null || v.isEmpty()) ? null : v;
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsInt() != 0;
            return Boolean.parseBoolean(p.getAsString());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static int i(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsInt();
            return Integer.parseInt(p.getAsString().trim());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /**
     * Number-or-string float. The snapshot writes the literal string "Infinity" for
     * unbreakable blocks; "NaN" and "-Infinity" are handled the same way.
     */
    static float f(JsonObject o, String key, float def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsFloat();
            String v = p.getAsString().trim();
            if (v.equalsIgnoreCase("Infinity") || v.equalsIgnoreCase("+Infinity")) return Float.POSITIVE_INFINITY;
            if (v.equalsIgnoreCase("-Infinity")) return Float.NEGATIVE_INFINITY;
            if (v.equalsIgnoreCase("NaN")) return Float.NaN;
            return Float.parseFloat(v);
        } catch (RuntimeException ex) {
            return def;
        }
    }
}
