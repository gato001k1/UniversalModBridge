package dev.umb.rendermap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The registry snapshot taken from the mod's native 1.7.10 run. */
public class Snapshot {

    /**
     * One {@code getIcon(side, meta)} result set, captured live by the native 1.7.10 boot (NOT
     * guessed from bytecode) — {@code metas} is every metadata value that shares this exact
     * six-sided icon set, {@code sides} is the six vanilla block-face icon names in
     * {@code getIcon(side, meta)}'s own side order (0=down..5=east). An icon name of
     * {@code "missingno"} means the live call returned no icon at all (nothing to resolve); a name
     * containing {@code '|'} or a mixed-case namespace segment is a connected-texture-mod compound
     * key (see A5/B2 in GENERALITY-MEASUREMENT.md) — genuinely not a single static texture.
     */
    public static class IconEntry {
        public int meta;
        public List<Integer> metas = new ArrayList<>();
        public List<String> sides = new ArrayList<>();
    }
    public static class Blk {
        public String id, className, tileEntityClass;
        public int renderType;
        public boolean hasTileEntity;
        public List<IconEntry> icons = new ArrayList<>();
    }
    public static class SubItm {
        public int damage;
        public String unlocalizedName, displayName, iconName;
    }
    public static class Itm {
        public String id, className, iconName, isBlockItem, unlocalizedName;
        public boolean hasSubtypes;
        public int subItemCount;
        public List<SubItm> subItems = new ArrayList<>();
    }
    public static class Named { public String name, className; }

    public final List<Blk> blocks = new ArrayList<>();
    public final List<Itm> items = new ArrayList<>();
    public final List<Named> tileEntities = new ArrayList<>();
    public final List<Named> entities = new ArrayList<>();
    public final Map<String, Blk> blockById = new LinkedHashMap<>();
    public final Map<String, Itm> itemById = new LinkedHashMap<>();
    /** block class name -> ids backed by that class. */
    public final Map<String, List<String>> blocksByClass = new LinkedHashMap<>();
    public final Map<String, List<String>> itemsByClass = new LinkedHashMap<>();
    /** tile entity class -> block ids that carry it. */
    public final Map<String, List<String>> blocksByTeClass = new LinkedHashMap<>();
    /**
     * "local name" (the id with its {@code namespace:} stripped) -> every full id that has it,
     * for BOTH blocks and items. A registry id's namespace is decided at runtime by FML from the
     * active mod's own modid, which is not discoverable from the mod jar's bytecode by name (it is
     * not necessarily the jar's file name, its main package, or any literal string in the
     * registration call) — so the resolvers in this package never fabricate a namespace. Instead
     * they compute the LOCAL fragment only (the literal string / {@code getUnlocalizedName()}
     * result actually visible in the bytecode) and look it up here against the snapshot's real,
     * already-namespaced ids. See {@link ModRegistryResolver#resolveIds}.
     */
    public final Map<String, List<String>> localNameToIds = new LinkedHashMap<>();

    /**
     * Snapshot rows the target mod itself registered, as opposed to vanilla content the
     * snapshot also carries. This used to be a hardcoded {@code id.startsWith("hbm:")} check,
     * which is exactly the kind of mod-specific literal this module is meant to be free of: on
     * any other mod it would silently match nothing. The only namespace guaranteed non-mod is
     * {@code minecraft:}, so "not vanilla" is the mod-agnostic definition of "this mod's own".
     */
    public List<Blk> modBlocks() {
        List<Blk> out = new ArrayList<>();
        for (Blk b : blocks) if (b.id != null && !b.id.startsWith("minecraft:")) out.add(b);
        return out;
    }
    public List<Itm> modItems() {
        List<Itm> out = new ArrayList<>();
        for (Itm i : items) if (i.id != null && !i.id.startsWith("minecraft:")) out.add(i);
        return out;
    }

    public static Snapshot load(Path p) throws IOException {
        Snapshot s = new Snapshot();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (JsonElement e : arr(root, "blocks")) {
                JsonObject o = e.getAsJsonObject();
                Blk b = new Blk();
                b.id = str(o, "id");
                b.className = str(o, "className");
                b.renderType = o.has("renderType") && !o.get("renderType").isJsonNull()
                        ? o.get("renderType").getAsInt() : Integer.MIN_VALUE;
                b.hasTileEntity = o.has("hasTileEntity") && !o.get("hasTileEntity").isJsonNull()
                        && o.get("hasTileEntity").getAsBoolean();
                b.tileEntityClass = str(o, "tileEntityClass");
                if (o.has("icons") && o.get("icons").isJsonArray()) {
                    for (JsonElement ie : o.getAsJsonArray("icons")) {
                        JsonObject io = ie.getAsJsonObject();
                        IconEntry icon = new IconEntry();
                        icon.meta = io.has("meta") && !io.get("meta").isJsonNull() ? io.get("meta").getAsInt() : 0;
                        if (io.has("metas") && io.get("metas").isJsonArray())
                            for (JsonElement me : io.getAsJsonArray("metas")) icon.metas.add(me.getAsInt());
                        if (io.has("sides") && io.get("sides").isJsonArray())
                            for (JsonElement se : io.getAsJsonArray("sides"))
                                icon.sides.add(se.isJsonNull() ? null : se.getAsString());
                        b.icons.add(icon);
                    }
                }
                s.blocks.add(b);
                s.blockById.put(b.id, b);
                if (b.className != null) s.blocksByClass.computeIfAbsent(b.className, k -> new ArrayList<>()).add(b.id);
                if (b.tileEntityClass != null)
                    s.blocksByTeClass.computeIfAbsent(b.tileEntityClass, k -> new ArrayList<>()).add(b.id);
            }
            for (JsonElement e : arr(root, "items")) {
                JsonObject o = e.getAsJsonObject();
                Itm i = new Itm();
                i.id = str(o, "id");
                i.className = str(o, "className");
                i.iconName = str(o, "iconName");
                i.isBlockItem = str(o, "isBlockItem");
                i.unlocalizedName = str(o, "unlocalizedName");
                i.hasSubtypes = o.has("hasSubtypes") && !o.get("hasSubtypes").isJsonNull()
                        && o.get("hasSubtypes").getAsBoolean();
                i.subItemCount = o.has("subItems") && o.get("subItems").isJsonArray()
                        ? o.getAsJsonArray("subItems").size() : 0;
                if (o.has("subItems") && o.get("subItems").isJsonArray()) {
                    for (JsonElement se : o.getAsJsonArray("subItems")) {
                        JsonObject so = se.getAsJsonObject();
                        SubItm sub = new SubItm();
                        sub.damage = so.has("damage") && !so.get("damage").isJsonNull() ? so.get("damage").getAsInt() : 0;
                        sub.unlocalizedName = str(so, "unlocalizedName");
                        sub.displayName = str(so, "displayName");
                        sub.iconName = str(so, "iconName");
                        i.subItems.add(sub);
                    }
                }
                s.items.add(i);
                s.itemById.put(i.id, i);
                if (i.className != null) s.itemsByClass.computeIfAbsent(i.className, k -> new ArrayList<>()).add(i.id);
            }
            for (JsonElement e : arr(root, "tileEntities")) {
                JsonObject o = e.getAsJsonObject();
                Named n = new Named(); n.name = str(o, "name"); n.className = str(o, "className");
                s.tileEntities.add(n);
            }
            for (JsonElement e : arr(root, "entities")) {
                JsonObject o = e.getAsJsonObject();
                Named n = new Named(); n.name = str(o, "name"); n.className = str(o, "className");
                s.entities.add(n);
            }
        }
        for (Blk b : s.blocks) indexLocalName(s, b.id);
        for (Itm i : s.items) indexLocalName(s, i.id);
        return s;
    }

    private static void indexLocalName(Snapshot s, String id) {
        if (id == null) return;
        int c = id.indexOf(':');
        String local = c < 0 ? id : id.substring(c + 1);
        s.localNameToIds.computeIfAbsent(local, k -> new ArrayList<>()).add(id);
    }

    /**
     * Every snapshot id (block or item) whose local fragment (after the {@code namespace:}) is
     * {@code localName}, preferring one that is NOT in the vanilla {@code minecraft:} namespace
     * (the mod jar being analysed never registers vanilla content, so a non-vanilla match is
     * always the more likely intent when both exist). Returns {@code null} when there is no match
     * at all — an honest "not found," never a guessed namespace.
     */
    public String resolveLocalName(String localName) {
        List<String> ids = localNameToIds.get(localName);
        if (ids == null || ids.isEmpty()) return null;
        for (String id : ids) if (!id.startsWith("minecraft:")) return id;
        return ids.get(0);
    }

    /**
     * The single most common first {@code "."}-delimited segment of this mod's own local registry
     * names — a self-derived signal for mods that prefix every local name with their own short tag
     * (e.g. Chisel registers {@code chisel:chisel.blockBookshelf}: local name
     * {@code "chisel.blockBookshelf"}, segment {@code "chisel"}). This is a REAL, if informal,
     * 1.7.10 convention distinct from vanilla's own {@code tile./item.} prefixing (many mods invent
     * their own mini-namespace this way specifically to avoid collisions in the pre-per-mod-namespace
     * global registry) — not a Chisel-specific literal, since the segment itself is read out of THIS
     * mod's own already-known ids, never hardcoded.
     *
     * <p>Prefers ids whose namespace matches {@code modIdHint} (derived from the snapshot's own
     * file name convention, {@code <modid>-snapshot.json}) to stay correct even when the snapshot
     * itself is contaminated by a leftover mod from a prior native-boot run (a known harness bug —
     * see GENERALITY-MEASUREMENT.md's "Known, disclosed contamination"); falls back to
     * {@link #modBlocks()}/{@link #modItems()} when the hint matches too few ids to be meaningful.
     * Returns {@code null} — never a guess — unless one segment is a clear majority (&gt;=50%) of at
     * least 4 sampled ids, and never returns vanilla's own {@code tile}/{@code item} (already tried
     * as an explicit, higher-priority candidate elsewhere; re-surfacing it here would be redundant,
     * not wrong).
     */
    public String dominantLocalPrefix(String modIdHint) {
        List<String> ids = new ArrayList<>();
        if (modIdHint != null && !modIdHint.isBlank()) {
            String ns = modIdHint.toLowerCase(java.util.Locale.ROOT) + ":";
            for (Blk b : blocks) if (b.id != null && b.id.toLowerCase(java.util.Locale.ROOT).startsWith(ns)) ids.add(b.id);
            for (Itm i : items) if (i.id != null && i.id.toLowerCase(java.util.Locale.ROOT).startsWith(ns)) ids.add(i.id);
        }
        if (ids.size() < 4) {
            ids = new ArrayList<>();
            for (Blk b : modBlocks()) ids.add(b.id);
            for (Itm i : modItems()) ids.add(i.id);
        }
        if (ids.size() < 4) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : ids) {
            int c = id.indexOf(':');
            String local = c < 0 ? id : id.substring(c + 1);
            int d = local.indexOf('.');
            String seg = d < 0 ? local : local.substring(0, d);
            if (!seg.isEmpty()) counts.merge(seg, 1, Integer::sum);
        }
        String best = null; int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet())
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        if (best == null || "tile".equals(best) || "item".equals(best)) return null;
        return bestCount * 2 >= ids.size() ? best : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
