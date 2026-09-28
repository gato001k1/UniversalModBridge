package dev.umb.objbridge.map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * No net.minecraft imports, so it unit-tests off the game classpath.
 */
public final class RenderMap {

    /** One {@code models[]}/{@code textures[]} entry. */
    public record Asset(String path, String assetPath) {
        /** {@code hbm:models/weapons/minigun.obj} -> {@code minigun}. */
        public String baseName() {
            String p = path == null ? "" : path;
            int slash = p.lastIndexOf('/');
            String f = slash >= 0 ? p.substring(slash + 1) : p;
            int dot = f.lastIndexOf('.');
            return dot > 0 ? f.substring(0, dot) : f;
        }

        /** {@code hbm:models/weapons/minigun.obj} -> {@code models/weapons}; no namespace, no file. */
        public String dir() {
            String p = path == null ? "" : path;
            int colon = p.indexOf(':');
            if (colon >= 0) p = p.substring(colon + 1);
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(0, slash) : "";
        }

        public String namespace() {
            String p = path == null ? "" : path;
            int colon = p.indexOf(':');
            return colon > 0 ? p.substring(0, colon) : "minecraft";
        }

        /** Path with the namespace stripped: {@code models/weapons/minigun.obj}. */
        public String bare() {
            String p = path == null ? "" : path;
            int colon = p.indexOf(':');
            return colon >= 0 ? p.substring(colon + 1) : p;
        }
    }

    public record ItemRow(String id, String className, String rendererClass, String iconName,
                          List<Asset> models, List<Asset> textures, List<String> groups,
                          boolean dynamic, String confidence) { }

    public record BlockRow(String id, String className, int renderType, boolean hasTileEntity,
                           String isbrhClass, String tesrClass,
                           String tileEntityClass, List<Asset> models, List<Asset> textures,
                           List<String> groups, boolean dynamic, String confidence) { }

    public record TeRow(String teClass, String rendererClass, List<Asset> models,
                        List<Asset> textures, List<String> groups, List<String> blockIds) { }

    /**
     * census tool COULD identify (it knows {@code className}) but could not statically resolve
     * back to any specific field/renderer site (the field-tracing walk in {@code umb-rendermap}
     * only follows static field references; an item constructed inside a loop over a data-driven
     * config table - a common pattern for a mod with many similar item variants - has no single
     * traceable field to trace at all). This carries strictly less information than an
     * {@link ItemRow} (no {@code rendererClass}, no assets) - it is NOT a claim that the item has
     * a custom renderer, only that the static census could not determine either way.
     */
    public record UnattributedItemRow(String id, String className) { }

    private final List<ItemRow> items;
    private final List<BlockRow> blocks;
    private final List<TeRow> tileEntities;
    private final List<UnattributedItemRow> unattributedItems;
    private final Map<String, TeRow> teByBlockId;
    private final Map<String, TeRow> teByClass;

    private RenderMap(List<ItemRow> items, List<BlockRow> blocks, List<TeRow> tes,
                      List<UnattributedItemRow> unattributedItems) {
        this.items = List.copyOf(items);
        this.blocks = List.copyOf(blocks);
        this.tileEntities = List.copyOf(tes);
        this.unattributedItems = List.copyOf(unattributedItems);
        Map<String, TeRow> m = new HashMap<>();
        Map<String, TeRow> byClass = new HashMap<>();
        for (TeRow t : tes) {
            for (String b : t.blockIds()) m.putIfAbsent(b, t);
            if (t.teClass() != null) byClass.putIfAbsent(t.teClass(), t);
        }
        this.teByBlockId = Collections.unmodifiableMap(m);
        this.teByClass = Collections.unmodifiableMap(byClass);
    }

    public List<ItemRow> items() { return items; }
    public List<BlockRow> blocks() { return blocks; }
    public List<TeRow> tileEntities() { return tileEntities; }
    public List<UnattributedItemRow> unattributedItems() { return unattributedItems; }

    /** The TESR row that draws this block, if any - used to borrow a texture for blocks that list none. */
    public TeRow tileEntityFor(String blockId) { return teByBlockId.get(blockId); }

    /**
     * Resolve a TESR row through the block snapshot's exact TE class when the extractor could not
     * recover the reverse blockIds edge.  This is a linkage fallback, not a name guess: the block
     * row itself supplies {@code tileEntityClass}, and the TE row supplies the renderer/models.
     */
    public TeRow tileEntityForClass(String teClass) { return teClass == null ? null : teByClass.get(teClass); }

    public static RenderMap read(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            return of(root == null ? new JsonObject() : root);
        }
    }

    public static RenderMap of(JsonObject root) {
        List<ItemRow> items = new ArrayList<>();
        List<BlockRow> blocks = new ArrayList<>();
        List<TeRow> tes = new ArrayList<>();
        List<UnattributedItemRow> unattributedItems = new ArrayList<>();

        for (JsonElement e : arr(root, "items")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            items.add(new ItemRow(
                    str(o, "id"), str(o, "className"), str(o, "rendererClass"), str(o, "iconName"),
                    assets(o, "models"), assets(o, "textures"), strings(o, "groups"),
                    bool(o, "dynamic"), str(o, "confidence")));
        }
        for (JsonElement e : arr(root, "blocks")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            blocks.add(new BlockRow(
                    str(o, "id"), str(o, "className"), integer(o, "renderType", 0), bool(o, "hasTileEntity"),
                    str(o, "isbrhClass"), str(o, "tesrClass"),
                    str(o, "tileEntityClass"), assets(o, "models"), assets(o, "textures"),
                    strings(o, "groups"), bool(o, "dynamic"), str(o, "confidence")));
        }
        for (JsonElement e : arr(root, "tileEntities")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            tes.add(new TeRow(
                    str(o, "teClass"), str(o, "rendererClass"), assets(o, "models"),
                    assets(o, "textures"), strings(o, "groups"), strings(o, "blockIds")));
        }
        for (JsonElement e : arr(root, "unattributedItemIds")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            unattributedItems.add(new UnattributedItemRow(str(o, "id"), str(o, "className")));
        }
        return new RenderMap(items, blocks, tes, unattributedItems);
    }

    // ---------------------------------------------------------------- json helpers

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && !e.isJsonNull() && e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int integer(JsonObject o, String k, int fallback) {
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? fallback : e.getAsInt(); }
        catch (RuntimeException ex) { return fallback; }
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) {
            if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static List<Asset> assets(JsonObject o, String k) {
        List<Asset> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String p = str(a, "path");
            if (p == null || p.isEmpty()) continue;
            out.add(new Asset(p, str(a, "assetPath")));
        }
        return out;
    }
}
