package dev.umb.hostagent.content;

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

/**
 * Tolerant reader for research/out/legacy/block-shapes.json (umb-legacy's GAP-1 block-shape
 * extraction: 1142 blocks, 338 non-cube, 57 multi-box, 0 errors).
 *
 * Pure data - no net.minecraft types, so this is unit-testable off the game classpath, exactly
 * like {@link LegacySnapshot}. {@link BlockShapes} is the net.minecraft-dependent VoxelShape
 * builder that consumes what this class parses.
 *
 * Every field is optional; a malformed record yields an empty/absent group rather than an
 * exception - a single bad entry must never take down Registrar.run's whole block pass.
 */
public final class BlockShapeProfile {

    /** One 1.7.10 shape shared by a deduped set of metadata values (block-shapes.json's own dedup). */
    public static final class MetaGroup {
        public final List<Integer> metas;
        public final double[] rawBounds;      // [minX,minY,minZ,maxX,maxY,maxZ] or null
        public final double[] collisionAabb;  // same shape, or null
        public final List<double[]> collisionBoxes; // possibly empty, never null
        public final boolean isFullCube;

        /**
         * True when this group RECORDED "no collision at all": {@code "collisionAabb"} is an
         * EXPLICIT JSON {@code null} (the extractor's faithful record of 1.7.10's
         * {@code getCollisionBoundingBoxFromPool}/{@code func_149668_a} returning null - the
         * legacy engine's own "walk-through block" convention, e.g. torches, fire, HBM's spikes
         * and gases) AND {@code collisionBoxes} is empty AND the group is not a full cube.
         * A merely ABSENT {@code collisionAabb} key stays false - that is "no data", not
         * "no collision", and must conservatively keep solid collision. {@link BlockShapes
         * #buildCollision} turns this flag into {@code Shapes.empty()}.
         */
        public final boolean hasNoCollision;

        MetaGroup(List<Integer> metas, double[] rawBounds, double[] collisionAabb,
                  List<double[]> collisionBoxes, boolean isFullCube, boolean hasNoCollision) {
            this.metas = metas;
            this.rawBounds = rawBounds;
            this.collisionAabb = collisionAabb;
            this.collisionBoxes = collisionBoxes;
            this.isFullCube = isFullCube;
            this.hasNoCollision = hasNoCollision;
        }

        public boolean hasMeta(int meta) {
            return metas.contains(meta);
        }
    }

    /** One 1.7.10 block, as block-shapes.json recorded it - its metaGroups cover every meta 0..15. */
    public static final class BlockEntry {
        public final String id;
        public final String className;
        public final List<MetaGroup> metaGroups = new ArrayList<>();

        BlockEntry(String id, String className) {
            this.id = id;
            this.className = className;
        }

        /**
         * The group covering this exact meta; falls back to the group covering meta 0 (matching
         * {@link VariantPlan}'s own "meta 0 is the base" convention), then the first group at all,
         * then null when this block has no groups (should not happen for ok=1142/1142, but a
         * caller must still treat null as "use the default full cube").
         */
        public MetaGroup groupFor(int meta) {
            MetaGroup zero = null;
            for (MetaGroup g : metaGroups) {
                if (g.hasMeta(meta)) return g;
                if (zero == null && g.hasMeta(0)) zero = g;
            }
            if (zero != null) return zero;
            return metaGroups.isEmpty() ? null : metaGroups.get(0);
        }
    }

    private final Map<String, BlockEntry> byId = new LinkedHashMap<>();
    public int errors;

    private BlockShapeProfile() {
    }

    public static BlockShapeProfile empty() {
        return new BlockShapeProfile();
    }

    /** Never throws: a missing/unreadable file behaves exactly like {@link #empty()}. */
    public static BlockShapeProfile load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    public static BlockShapeProfile parseString(String json) {
        return parse(JsonParser.parseString(json));
    }

    private static BlockShapeProfile parse(JsonElement root) {
        BlockShapeProfile p = new BlockShapeProfile();
        if (root == null || !root.isJsonObject()) return p;
        JsonObject o = root.getAsJsonObject();
        for (JsonElement e : arr(o, "blocks")) {
            if (!e.isJsonObject()) continue;
            JsonObject bo = e.getAsJsonObject();
            String id = str(bo, "id");
            if (id == null) continue;
            if (str(bo, "error") != null) p.errors++;
            BlockEntry be = new BlockEntry(id, str(bo, "className"));
            for (JsonElement ge : arr(bo, "metaGroups")) {
                if (!ge.isJsonObject()) continue;
                JsonObject go = ge.getAsJsonObject();
                List<Integer> metas = new ArrayList<>();
                for (JsonElement me : arr(go, "metas")) {
                    if (me.isJsonPrimitive()) {
                        try {
                            metas.add(me.getAsInt());
                        } catch (RuntimeException ignored) {
                            // skip a malformed entry, keep the rest of the group
                        }
                    }
                }
                double[] rawBounds = doubles6(go, "rawBounds");
                double[] collisionAabb = doubles6(go, "collisionAabb");
                List<double[]> boxes = new ArrayList<>();
                for (JsonElement bxe : arr(go, "collisionBoxes")) {
                    if (!bxe.isJsonArray()) continue;
                    double[] box = doubles6(bxe.getAsJsonArray());
                    if (box != null) boxes.add(box);
                }
                boolean isFullCube = bool(go, "isFullCube", false);
                // EXPLICIT JSON null only - doubles6 returns null for both "key: null" and a
                // missing/malformed key, but only the explicit null is the extractor recording
                // 1.7.10's func_149668_a returning null (see MetaGroup#hasNoCollision).
                JsonElement collisionAabbRaw = go.get("collisionAabb");
                boolean hasNoCollision = collisionAabbRaw != null && collisionAabbRaw.isJsonNull()
                        && boxes.isEmpty() && !isFullCube;
                be.metaGroups.add(new MetaGroup(metas, rawBounds, collisionAabb, boxes, isFullCube,
                        hasNoCollision));
            }
            p.byId.put(id, be);
        }
        return p;
    }

    public BlockEntry get(String id) {
        return byId.get(id);
    }

    /** Every parsed block, in file order - read-only (added for the no-collision corpus gate). */
    public java.util.Collection<BlockEntry> entries() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    // ---------------- json helpers (mirrors LegacySnapshot's tolerant style) ----------------

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String v = e.getAsString();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double[] doubles6(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return null;
        return doubles6(e.getAsJsonArray());
    }

    private static double[] doubles6(JsonArray a) {
        if (a.size() != 6) return null;
        double[] out = new double[6];
        for (int i = 0; i < 6; i++) {
            JsonElement e = a.get(i);
            if (e == null || !e.isJsonPrimitive()) return null;
            try {
                out[i] = e.getAsDouble();
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return out;
    }
}
