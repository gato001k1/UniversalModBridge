package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One 1.7.10 Block as captured by the snapshot. Pure data - no net.minecraft types. */
public final class BlockRec {

    /** 1.7.10 side order for Block.getIcon(side, meta). */
    public static final String[] SIDE_NAMES = {"down", "up", "north", "south", "west", "east"};

    public String id;
    public String className;
    public String unlocalizedName;
    public String displayName;
    public String material;
    public int mapColor;
    public float hardness;
    public float resistance;
    public boolean unbreakable;
    public int lightValue;
    public int lightOpacity;
    public boolean opaqueCube = true;
    /** 1.7.10 func_149686_d (renderAsNormalBlock), captured by the legacy probe. */
    public boolean renderAsNormalBlock = true;
    public String creativeTab;
    public String harvestTool;
    public int harvestLevel = -1;
    public String stepSound;
    public float slipperiness = 0.6f;
    public String textureName;
    public boolean hasTileEntity;
    /**
     * 1.7.10 Block.getRenderType (func_149645_b): -1 means the block is NOT drawn by the
     * block renderer at all (TESR/ISBRH-only, e.g. light beams) - the same -1 contract
     * {@code dev.umb.objbridge.ObjBridge#spliceBlocks} already keys its runtime "tesrOnly"
     * invisibility rule off. Absent in older snapshots -> 0 (normal cube rendering).
     */
    public int renderType;
    /** func_149653_t (getTickRandomly): 1.7.10 gives this block random updateTick calls (fluids,
     *  fire, gases). Absent in snapshots older than the tick lane -> false, today's behavior. */
    public boolean tickRandomly;
    /** meta-0 icon row: six 1.7.10 icon names (may contain nulls), or null when the block has no icons. */
    public String[] sides;
    /** every icon row, keyed by its meta. In 1.7.10 these rows are per-metadata Block.getIcon results. */
    public Map<Integer, String[]> iconRows = new LinkedHashMap<>();
    /** 1.7.10 metadata sub-blocks (Block.getSubBlocks), keyed by meta. Never null. */
    public List<SubRec> subBlocks = new ArrayList<>();

    public static BlockRec from(JsonObject o) {
        BlockRec b = new BlockRec();
        b.id = LegacySnapshot.str(o, "id");
        if (b.id == null) return null;
        b.className = LegacySnapshot.str(o, "className");
        b.unlocalizedName = LegacySnapshot.str(o, "unlocalizedName");
        b.displayName = LegacySnapshot.str(o, "displayName");
        b.material = LegacySnapshot.str(o, "material");
        b.mapColor = LegacySnapshot.i(o, "mapColor", -1);
        b.hardness = LegacySnapshot.f(o, "hardness", 1.5f);
        b.resistance = LegacySnapshot.f(o, "resistance", 10.0f);
        b.unbreakable = !isFinite(b.hardness) || b.hardness < 0.0f;
        if (!isFinite(b.resistance) || b.resistance < 0.0f) b.unbreakable = true;
        b.lightValue = LegacySnapshot.i(o, "lightValue", 0);
        b.lightOpacity = LegacySnapshot.i(o, "lightOpacity", 255);
        b.opaqueCube = LegacySnapshot.bool(o, "opaqueCube", true);
        b.renderAsNormalBlock = LegacySnapshot.bool(o, "renderAsNormalBlock", true);
        b.creativeTab = LegacySnapshot.str(o, "creativeTab");
        b.harvestTool = LegacySnapshot.str(o, "harvestTool");
        b.harvestLevel = LegacySnapshot.i(o, "harvestLevel", -1);
        b.stepSound = LegacySnapshot.str(o, "stepSound");
        b.slipperiness = LegacySnapshot.f(o, "slipperiness", 0.6f);
        b.textureName = LegacySnapshot.str(o, "textureName");
        b.hasTileEntity = LegacySnapshot.bool(o, "hasTileEntity", false);
        b.renderType = LegacySnapshot.i(o, "renderType", 0);
        b.tickRandomly = LegacySnapshot.bool(o, "tickRandomly", false);
        b.iconRows = readIconRows(o);
        b.sides = readSides(o);
        b.subBlocks = SubRec.readAll(o, "subBlocks", "meta");
        return b;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Prefer the meta==0 icon row; fall back to the first row present. */
    private static Map<Integer, String[]> readIconRows(JsonObject o) {
        Map<Integer, String[]> rows = new LinkedHashMap<>();
        for (JsonElement e : LegacySnapshot.arr(o, "icons")) {
            if (!e.isJsonObject()) continue;
            JsonObject row = e.getAsJsonObject();
            String[] six = sixSides(row);
            if (six == null) continue;
            rows.putIfAbsent(LegacySnapshot.i(row, "meta", 0), six);
        }
        return rows;
    }

    private static String[] sixSides(JsonObject row) {
        JsonArray sides = LegacySnapshot.arr(row, "sides");
        if (sides.isEmpty()) return null;
        String[] out = new String[6];
        boolean any = false;
        for (int i = 0; i < 6 && i < sides.size(); i++) {
            JsonElement e = sides.get(i);
            if (e == null || e.isJsonNull()) continue;
            String v = e.getAsString();
            if (v == null || v.isEmpty()) continue;
            out[i] = v;
            any = true;
        }
        return any ? out : null;
    }

    private static String[] readSides(JsonObject o) {
        JsonArray icons = LegacySnapshot.arr(o, "icons");
        JsonObject chosen = null;
        for (JsonElement e : icons) {
            if (!e.isJsonObject()) continue;
            JsonObject row = e.getAsJsonObject();
            if (chosen == null) chosen = row;
            if (LegacySnapshot.i(row, "meta", -1) == 0) {
                chosen = row;
                break;
            }
        }
        if (chosen == null) return null;
        JsonArray sides = LegacySnapshot.arr(chosen, "sides");
        if (sides.isEmpty()) return null;
        String[] out = new String[6];
        boolean any = false;
        for (int i = 0; i < 6 && i < sides.size(); i++) {
            JsonElement e = sides.get(i);
            if (e == null || e.isJsonNull()) continue;
            String v = e.getAsString();
            if (v == null || v.isEmpty()) continue;
            out[i] = v;
            any = true;
        }
        return any ? out : null;
    }

    /**
     * The variant group as it should be registered: distinct metas, snapshot order.
     * A group of 0 or 1 entries is NOT flattened.
     *
     * NOTE: the variant source is subBlocks, NOT the icons[] rows. 75 HBM blocks carry several
     * icon rows that are pure ROTATION states (hbm:tile.block_c4 has rows for meta 0..6 that only
     * move block_c4_front between faces) while declaring a single sub-block; flattening those
     * would invent six junk blocks per rotation. Exactly the 59 blocks with more than one
     * subBlocks entry are also exactly the 59 whose ItemBlock reports more than one subItem.
     */
    public List<SubRec> variantGroup() {
        List<SubRec> d = SubRec.distinctByMeta(subBlocks);
        return d.size() > 1 ? d : List.of();
    }

    /** The sub-block that describes the base record itself (meta 0, else absent). */
    public SubRec primarySub() {
        for (SubRec s : subBlocks) {
            if (s.meta == 0) return s;
        }
        return null;
    }

    /** The icon row for one meta, falling back to the meta-0 row. */
    public String[] sidesFor(int meta) {
        String[] row = iconRows.get(meta);
        return row != null ? row : sides;
    }

    /** All distinct legacy icon names on this block's meta-0 row. */
    public List<String> distinctSideIcons() {
        List<String> out = new ArrayList<>();
        if (sides == null) return out;
        for (String s : sides) {
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public boolean allSidesEqual() {
        return allEqual(sides);
    }

    public static boolean allEqual(String[] row) {
        if (row == null) return false;
        String first = row[0];
        if (first == null) return false;
        for (String s : row) {
            if (s == null || !s.equals(first)) return false;
        }
        return true;
    }
}
