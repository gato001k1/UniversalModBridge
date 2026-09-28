package dev.umb.hostagent.content;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 1.13-style flattening, computed ONCE and walked by everyone.
 *
 * 1.7.10 packed several logical objects into one Item/Block and told them apart by the ItemStack
 * damage value. 26.2 has no metadata at all, so each of those has to become its own registry
 * entry. v0 registered only variant 0 under the base id with the base unlocalizedName, which is
 * why 180 tooltips read {@code item.drillbit} instead of "Steel Drillbit" and 2163 item variants
 * did not exist at all.
 *
 * {@link dev.umb.hostagent.content.Registrar} (registration), {@link dev.umb.packgen.PackGen}
 * (models/lang/textures) and {@link dev.umb.hostagent.probe.BootstrapProbe} (the gate) all walk
 * the SAME plan in the SAME order, so the three can never disagree about an id. The only shared
 * mutable state is the {@link LegacyIds} instance inside the plan, and it is fed in a fixed
 * order: every block (base entry, then its variants by snapshot order), then every plain item.
 *
 * Rules
 * <ul>
 *   <li><b>Blocks</b> keep meta 0 under the base id, so every id v0 registered stays valid, and
 *       add one entry per other meta in {@code subBlocks}. Each variant gets its own BlockItem
 *       and the {@code icons[]} row for its own meta.</li>
 *   <li><b>Items</b> register one entry per distinct sub-item damage and do NOT also register the
 *       bare base when a damage-0 sub-item exists. When there is no damage-0 sub-item the base is
 *       kept as well, so its v0 id survives.</li>
 *   <li>Ids come from {@link LegacyIds#variantIds}; display names prefer the sub-record, then the
 *       {@code <unlocalizedName>.name} row of HBM's own en_US.lang, then title case of the key.</li>
 *   <li>Variants inherit the base record's {@code creativeTab}.</li>
 *   <li><b>Shape-only twins</b> (multiblock-notes/SHAPE-VARIANT-LANE): a metadata that is NOT in
 *       {@code subBlocks} but whose own extracted collision/occlusion shape (the SAME
 *       {@link BlockShapeProfile}/{@link BlockShapes} data and builder every other twin's real
 *       shape comes from) genuinely differs from meta 0's still gets its own registered
 *       {@link BlockEntry} (with {@link BlockEntry#shapeOnly} set) so a mod's own runtime
 *       placement code (e.g. a multiblock controller writing its "activated core" metadata) can
 *       be found by {@code HostWorldImpl.setBlock}'s "id@meta" lookup - but it gets NO
 *       {@code BlockItem}, no creative-tab slot and no synthetic display name, because a player
 *       never selects this metadata from a menu the way a real {@code subBlocks} entry is. See
 *       {@link #addShapeOnlyTwins}.</li>
 * </ul>
 */
public final class VariantPlan {

    /** One block to register: the base record plus which metadata variant of it this is. */
    public static final class BlockEntry {
        public final BlockRec base;
        public final int meta;
        public final boolean variant;
        public final String legacyId;
        public final String path;
        public final String displayName;
        public final String[] sides;
        /**
         * True for a shape-only twin: a Block-only registry entry for a metadata whose extracted
         * shape differs from meta 0's but which the mod never lists as a {@code subBlocks}
         * placement choice (real geometry its OWN placement/runtime code writes, e.g. a
         * multiblock controller's "activated" tier). Callers MUST NOT register a
         * {@code BlockItem}, creative-tab slot, or display name for one of these - see
         * {@link VariantPlan}'s class javadoc and {@link #addShapeOnlyTwins}.
         */
        public final boolean shapeOnly;

        BlockEntry(BlockRec base, int meta, boolean variant, String legacyId, String path,
                   String displayName, String[] sides) {
            this(base, meta, variant, legacyId, path, displayName, sides, false);
        }

        BlockEntry(BlockRec base, int meta, boolean variant, String legacyId, String path,
                   String displayName, String[] sides, boolean shapeOnly) {
            this.base = base;
            this.meta = meta;
            this.variant = variant;
            this.legacyId = legacyId;
            this.path = path;
            this.displayName = displayName;
            this.sides = sides;
            this.shapeOnly = shapeOnly;
        }

        /** "hbm:tile.block_cap@2" - the key of the legacy (id, damage) pair this entry replaces. */
        public String legacyKey() {
            return base.id + "@" + meta;
        }
    }

    /** One plain item to register (ItemBlocks come from {@link #blocks}). */
    public static final class ItemEntry {
        public final ItemRec base;
        public final int damage;
        public final boolean variant;
        public final String legacyId;
        public final String path;
        public final String displayName;
        public final String icon;

        ItemEntry(ItemRec base, int damage, boolean variant, String legacyId, String path,
                  String displayName, String icon) {
            this.base = base;
            this.damage = damage;
            this.variant = variant;
            this.legacyId = legacyId;
            this.path = path;
            this.displayName = displayName;
            this.icon = icon;
        }

        public String legacyKey() {
            return base.id + "@" + damage;
        }
    }

    public final List<BlockEntry> blocks = new ArrayList<>();
    public final List<ItemEntry> items = new ArrayList<>();
    public final LegacyIds ids;
    /** block-shapes.json data, consulted only by {@link #addShapeOnlyTwins}. Never null. */
    private final BlockShapeProfile shapes;

    /** entries whose display name still looks like a raw translation key (target: 0). */
    public final List<String> rawKeyNames = new ArrayList<>();
    /** display names that had to be synthesised by {@link LangTable#humanize}. */
    public final List<String> humanised = new ArrayList<>();
    public int blockBases, blockVariants, blockShapeOnlyVariants, itemBases, itemVariants, unusableIds;

    private VariantPlan(LegacyIds ids, BlockShapeProfile shapes) {
        this.ids = ids;
        this.shapes = shapes == null ? BlockShapeProfile.empty() : shapes;
    }

    /**
     * Back-compat overload: no block-shapes profile, so no shape-only twins are ever produced -
     * byte-identical to every caller that pre-dates {@link #addShapeOnlyTwins} (e.g.
     * {@code dev.umb.packgen.PackGen}, which has no resource-pack entry to emit for a twin with
     * no display name/icon and does not need one - see the class javadoc).
     */
    public static VariantPlan build(LegacySnapshot snap, LangTable lang) {
        return build(snap, lang, BlockShapeProfile.empty());
    }

    public static VariantPlan build(LegacySnapshot snap, LangTable lang, BlockShapeProfile shapes) {
        VariantPlan plan = new VariantPlan(new LegacyIds(), shapes);
        LangTable table = lang == null ? LangTable.empty() : lang;

        for (BlockRec b : snap.blocks) {
            plan.addBlocks(b, table);
        }
        for (ItemRec it : snap.items) {
            if (it.isBlockItem != null) continue; // the block's own BlockItem covers it
            plan.addItems(it, table);
        }
        return plan;
    }

    // ------------------------------------------------------------------ blocks

    private void addBlocks(BlockRec b, LangTable lang) {
        List<SubRec> group = b.variantGroup();

        // the base entry: meta 0. Its name comes from the meta-0 sub-block when there is one,
        // which is what turns "tile.block_cap" into "Block of Nuka Cola Bottle Caps".
        SubRec primary = b.primarySub();
        String basePath = ids.pathFor(b.id);
        if (basePath == null) {
            unusableIds++;
        } else {
            blocks.add(new BlockEntry(b, 0, false, b.id, basePath,
                    name(primary, b.displayName, b.unlocalizedName, lang, b.id),
                    b.sidesFor(0)));
            blockBases++;
        }

        // every meta already spoken for by a real, player-placeable subBlocks entry (meta 0
        // always counts, even if basePath above turned out unusable) - addShapeOnlyTwins must
        // never invent a second entry for one of these.
        Set<Integer> coveredMetas = new HashSet<>();
        coveredMetas.add(0);

        if (!group.isEmpty()) {
            List<String> vids = LegacyIds.variantIds(b.id, b.unlocalizedName, group, true);
            for (int i = 0; i < group.size(); i++) {
                SubRec s = group.get(i);
                coveredMetas.add(s.meta);
                if (s.meta == 0) continue;               // already the base entry
                String legacyId = vids.get(i);
                String path = ids.pathFor(legacyId);
                if (path == null) {
                    unusableIds++;
                    continue;
                }
                blocks.add(new BlockEntry(b, s.meta, true, legacyId, path,
                        name(s, null, b.unlocalizedName, lang, legacyId),
                        b.sidesFor(s.meta)));
                blockVariants++;
            }
        }

        addShapeOnlyTwins(b, coveredMetas);
    }

    /**
     * Multiblock-notes lane (MULTIBLOCK-LANE.md section 4) found that a real 1.7.10 multiblock
     * controller's out-of-cell shape lives exclusively on metadata a mod's OWN placement code
     * writes at runtime (e.g. BlockDummyable's "activated core" tier), never on a {@code
     * subBlocks} entry a player selects from a menu - so the block-level loop above never
     * produces a twin for it, and {@code HostWorldImpl.resolveVariantOrBase} silently falls back
     * to the meta-0 {@code Block} the moment the mod flips that metadata, permanently hiding the
     * real shape.
     *
     * <p>This registers a Block-only twin for every metadata 1..15 that is not already in
     * {@code coveredMetas} AND whose own extracted shape actually renders differently from meta
     * 0's - reusing the EXACT same builder ({@link BlockShapes#build}) and the EXACT vanilla
     * shape-equality check ({@link Shapes#equal}) this codebase's own test suite already uses to
     * assert two {@link net.minecraft.world.phys.shapes.VoxelShape}s match or differ
     * ({@code BlockShapesTest}/{@code UmbLegacyBlockTest}), so this never reinvents or
     * approximates {@link BlockShapes}' own clamping/unclamping decision - it only asks "would
     * {@link Registrar} build a visibly different shape for this meta than for meta 0", which is
     * purely a function of the extracted numbers. No block-name, class or mod-namespace check
     * anywhere, so this generalizes to ANY 1.7.10 mod whose multiblock cells vary shape by a
     * non-subBlocks metadata, not just the corpus this lane verified against.</p>
     *
     * <p>A twin produced here gets NO {@code BlockItem}, creative-tab slot or display name
     * ({@link BlockEntry#shapeOnly} is set instead) - {@link Registrar} and
     * {@code BootstrapProbe} both key off that flag to skip item registration/verification for
     * exactly this entry, per the shared-plan contract in this class's javadoc.</p>
     */
    private void addShapeOnlyTwins(BlockRec b, Set<Integer> coveredMetas) {
        BlockShapeProfile.BlockEntry profile = shapes.get(b.id);
        if (profile == null) return; // no shape data at all for this id - nothing to compare

        BlockShapeProfile.MetaGroup zeroGroup = profile.groupFor(0);
        VoxelShape zeroShape = BlockShapes.build(zeroGroup);

        List<SubRec> shapeOnly = new ArrayList<>();
        for (int meta = 1; meta <= 15; meta++) {
            if (coveredMetas.contains(meta)) continue;
            BlockShapeProfile.MetaGroup g = profile.groupFor(meta);
            if (g == zeroGroup) continue; // same dedup group from the extractor -> same shape, cheaply
            if (Shapes.equal(BlockShapes.build(g), zeroShape)) continue; // built shapes coincide anyway
            SubRec synthetic = new SubRec();
            synthetic.meta = meta;
            shapeOnly.add(synthetic);
        }
        if (shapeOnly.isEmpty()) return;

        // no unlocalizedName exists for a runtime-only metadata (1.7.10 never assigned one), so
        // readableIdsUsable is always false here and LegacyIds.variantIds always falls back to
        // its numeric "<baseId>_<meta>" suffix - stable, deterministic, and collision-free with
        // the subBlocks-variant ids above (disjoint meta sets by construction: coveredMetas).
        List<String> vids = LegacyIds.variantIds(b.id, b.unlocalizedName, shapeOnly, false);
        for (int i = 0; i < shapeOnly.size(); i++) {
            SubRec s = shapeOnly.get(i);
            String legacyId = vids.get(i);
            String path = ids.pathFor(legacyId);
            if (path == null) {
                unusableIds++;
                continue;
            }
            // no display name/lang row/creative-tab item on purpose - see BlockEntry#shapeOnly.
            blocks.add(new BlockEntry(b, s.meta, true, legacyId, path, null, b.sidesFor(s.meta), true));
            blockShapeOnlyVariants++;
        }
    }

    // ------------------------------------------------------------------- items

    private void addItems(ItemRec it, LangTable lang) {
        List<SubRec> group = it.variantGroup();

        if (group.isEmpty()) {
            // not a multi-variant item: keep the v0 id, but still take the sub-item's name/icon
            SubRec primary = it.primarySub();
            String path = ids.pathFor(it.id);
            if (path == null) {
                unusableIds++;
                return;
            }
            items.add(new ItemEntry(it, primary == null ? 0 : primary.meta, false, it.id, path,
                    name(primary, it.displayName, it.unlocalizedName, lang, it.id),
                    icon(primary, it)));
            itemBases++;
            return;
        }

        // 29 multi-variant items have no damage-0 sub-item at all (e.g. hbm:item.bolt lists
        // 7400/8200/30/33), so the bare base id would otherwise vanish. Keep it.
        boolean hasZero = false;
        for (SubRec s : group) {
            if (s.meta == 0) hasZero = true;
        }
        if (!hasZero) {
            String path = ids.pathFor(it.id);
            if (path == null) {
                unusableIds++;
            } else {
                items.add(new ItemEntry(it, 0, false, it.id, path,
                        name(null, it.displayName, it.unlocalizedName, lang, it.id),
                        icon(null, it)));
                itemBases++;
            }
        }

        List<String> vids = LegacyIds.variantIds(it.id, it.unlocalizedName, group, false);
        for (int i = 0; i < group.size(); i++) {
            SubRec s = group.get(i);
            String legacyId = vids.get(i);
            String path = ids.pathFor(legacyId);
            if (path == null) {
                unusableIds++;
                continue;
            }
            items.add(new ItemEntry(it, s.meta, true, legacyId, path,
                    name(s, null, it.unlocalizedName, lang, legacyId),
                    icon(s, it)));
            itemVariants++;
        }
    }

    // -------------------------------------------------------------------- misc

    /**
     * Resolve the English name of one entry: the sub-record's own displayName wins, then the
     * snapshot's, then HBM's en_US.lang keyed by whichever unlocalizedName applies, then title
     * case of the key. Never returns something {@link LangTable#looksLikeRawKey} accepts.
     */
    private String name(SubRec sub, String snapshotName, String baseUnlocalized, LangTable lang,
                        String reportAs) {
        String subUnloc = sub == null ? null : sub.unlocalizedName;
        String subName = sub == null ? null : sub.displayName;

        if (!LangTable.looksLikeRawKey(subName)) return subName;
        if (!LangTable.looksLikeRawKey(snapshotName)) return snapshotName;

        for (String key : new String[]{subUnloc, baseUnlocalized}) {
            if (key == null) continue;
            String v = lang.get(key + ".name");
            if (v != null && !v.isEmpty() && !LangTable.looksLikeRawKey(v)) return v;
        }
        String seed = subUnloc != null ? subUnloc : (baseUnlocalized != null ? baseUnlocalized : reportAs);
        String human = LangTable.humanize(seed);
        humanised.add(reportAs + " -> \"" + human + "\"");
        if (LangTable.looksLikeRawKey(human)) rawKeyNames.add(reportAs + " -> " + human);
        return human;
    }

    private static String icon(SubRec sub, ItemRec base) {
        if (sub != null && sub.iconName != null) return sub.iconName;
        return base.icon();
    }

    /** legacy "id@damage" -> registry path, for later phases and for the probe. */
    public Map<String, String> legacyKeyToPath() {
        Map<String, String> out = new LinkedHashMap<>();
        for (BlockEntry e : blocks) out.put(e.legacyKey(), e.path);
        for (ItemEntry e : items) out.put(e.legacyKey(), e.path);
        return out;
    }

    /**
     * The legacy "id@meta" keys of every {@link BlockEntry#shapeOnly} twin. Every other entry
     * {@link #legacyKeyToPath()} returns is expected to also show up in
     * {@code Registrar.LEGACY_VARIANT_ITEMS} (a real subBlocks variant's own BlockItem shares its
     * key); a shape-only twin deliberately has no BlockItem at all, so a caller verifying the
     * plan against the live registries (BootstrapProbe) needs this set to know which keys must
     * instead be checked against {@code Registrar.LEGACY_VARIANT_BLOCKS}.
     */
    public Set<String> shapeOnlyKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (BlockEntry e : blocks) {
            if (e.shapeOnly) out.add(e.legacyKey());
        }
        return out;
    }

    /**
     * Guard for the invariant that matters most: no two entries may claim the same registry path.
     * Blocks and items live in different registries, so they are checked separately.
     * Returns the offending paths, empty when the plan is sound.
     */
    public List<String> duplicatePaths() {
        List<String> bad = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BlockEntry e : blocks) {
            if (!seen.add(e.path)) bad.add("BLOCK " + e.path + " (" + e.legacyId + ")");
        }
        seen.clear();
        for (ItemEntry e : items) {
            if (!seen.add(e.path)) bad.add("ITEM " + e.path + " (" + e.legacyId + ")");
        }
        return bad;
    }

    public String stats() {
        return "blocks=" + blocks.size() + " (bases=" + blockBases + " variants=" + blockVariants
                + " shapeOnly=" + blockShapeOnlyVariants + ")"
                + " plainItems=" + items.size() + " (bases=" + itemBases + " variants=" + itemVariants + ")"
                + " humanisedNames=" + humanised.size()
                + " rawKeyNames=" + rawKeyNames.size()
                + " unusableIds=" + unusableIds;
    }
}
