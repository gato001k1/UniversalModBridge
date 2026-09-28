package dev.umb.hostagent.content;

import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.content.fluid.FluidRegistrar;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/**
 * Turns the 1.7.10 registry snapshot into native 26.2 Blocks, Items and CreativeModeTabs.
 * Called from Hooks.beforeFreeze(), i.e. while BuiltInRegistries is still writable.
 *
 * Every record is registered inside its own try/catch: one bad record can never abort the run.
 */
public final class Registrar {

    /** legacy id -> the native object we registered for it. Kept for later phases. */
    public static final Map<String, Object> LEGACY_TO_NATIVE = new LinkedHashMap<>();
    /** legacy block id -> the native Block. */
    public static final Map<String, Block> LEGACY_BLOCKS = new LinkedHashMap<>();
    /** legacy item id -> the native Item (BlockItems included). */
    public static final Map<String, Item> LEGACY_ITEMS = new LinkedHashMap<>();
    /**
     * legacy "id@damage" -> the native Item that now carries that 1.7.10 metadata variant.
     * This is the map later phases need: a 1.7.10 ItemStack is (id, damage) and on 26.2 the
     * damage is gone, so (id, damage) is the only thing that identifies one object.
     */
    public static final Map<String, Item> LEGACY_VARIANT_ITEMS = new LinkedHashMap<>();
    /** legacy "id@meta" -> the native Block for that 1.7.10 block metadata. */
    public static final Map<String, Block> LEGACY_VARIANT_BLOCKS = new LinkedHashMap<>();

    public static volatile String summary = "(not run)";

    /** Register all manifest records after checking cross-mod registry ownership. */
    public static void run(ModContentManifest manifest) {
        Map<String, String> owners = new HashMap<>();
        for (ModContentRecord r : manifest.records()) {
            try {
                LegacySnapshot s = LegacySnapshot.load(r.snapshot(), r.namespace());
                checkIds(owners, r.namespace(), s);
            } catch (Exception e) {
                throw new IllegalStateException("cannot preflight content owner " + r.namespace(), e);
            }
        }
        for (ModContentRecord r : manifest.records()) {
            run(r.snapshot(), r.lang(), r.blockShapes(), r.guiProfile(), r.namespace());
            if (r.recipes() != null) {
                try {
                    LegacySnapshot s = LegacySnapshot.load(r.snapshot(), r.namespace());
                    RecipeGenerator.Counts c = RecipeGenerator.generate(s, r.namespace(), r.recipes());
                    AgentLog.line("UMB-HOSTAGENT recipes ns=" + r.namespace() + " total=" + c.total()
                            + " owned=" + c.owned() + " vanilla-skipped=" + c.vanillaSkipped()
                            + " unknown-class=" + c.unknownClass() + " unresolvable-item=" + c.unresolvableItem());
                } catch (Exception e) {
                    AgentLog.loud("UMB-HOSTAGENT recipe generation failed for " + r.namespace() + ": " + e);
                }
            }
        }
    }

    private static void checkIds(Map<String, String> owners, String ns, LegacySnapshot s) {
        for (BlockRec b : s.blocks) claim(owners, "block:" + LegacyIds.sanitizeNamespace(ns) + ":" + b.id, ns);
        for (ItemRec i : s.items) claim(owners, "item:" + LegacyIds.sanitizeNamespace(ns) + ":" + i.id, ns);
    }

    private static void claim(Map<String, String> owners, String id, String owner) {
        String prior = owners.putIfAbsent(id, owner);
        if (prior != null && !prior.equals(owner)) {
            throw new IllegalStateException("UMB-HOSTAGENT REGISTRY COLLISION " + id + " owners=" + prior + "," + owner);
        }
    }

    /** legacy block id -> its BlockRec, kept for later phases (e.g. hasTileEntity lookups). */
    public static final Map<String, BlockRec> LEGACY_BLOCK_RECS = new LinkedHashMap<>();

    /**
     * Task B (laneConsume-progress.md): the parsed gui-profile.json, loaded once by {@link #run}
     * and read by {@link UmbMenuProvider} at every menu-open (keyed by the REAL legacy Container
     * class, recovered via {@link LegacyContainerClassResolver} - {@code dev.umb.bridge.api} is
     * never widened for this). Empty (never null) when no gui-profile.json was configured, so a
     * lookup always degrades to the pre-existing 176x166 dispenser-borrowed panel.
     */
    public static volatile GuiProfile GUI_PROFILE = GuiProfile.empty();

    /** Lazily built reverse of LEGACY_VARIANT_ITEMS: native Item -> "legacyId@meta". First writer wins. */
    private static volatile Map<Item, String> itemToLegacyVariantKey;

    /**
     * Reverse lookup for host -> legacy item conversions (e.g. a player depositing a native
     * ItemStack into a legacy container's mirrored slot). Built once, lazily, off the map
     * Registrar itself filled during run() -- safe to call any time after run() completes since
     * everything from here on is server-thread-only.
     */
    public static String legacyVariantKeyForItem(Item item) {
        Map<Item, String> m = itemToLegacyVariantKey;
        if (m == null) {
            m = new HashMap<>();
            for (Map.Entry<String, Item> e : LEGACY_VARIANT_ITEMS.entrySet()) {
                m.putIfAbsent(e.getValue(), e.getKey());
            }
            itemToLegacyVariantKey = m;
        }
        return m.get(item);
    }

    /**
     * Test-only: forces the next {@link #legacyVariantKeyForItem} call to rebuild its cache from
     * the CURRENT contents of {@link #LEGACY_VARIANT_ITEMS}. Needed because JUnit's
     * {@code --scan-class-path} runs every test class in one JVM: without this, a test class that
     * populates {@code LEGACY_VARIANT_ITEMS} with its own fixture item AFTER some earlier test
     * class already triggered (and permanently cached) the reverse lookup would see that fixture
     * item silently missing from the reverse map forever.
     */
    static void resetLegacyVariantKeyCacheForTest() {
        itemToLegacyVariantKey = null;
    }

    // ---------------------------------------------------------- G2 step 2: tile + menu registration
    //
    // Called from Hooks.beforeFreeze AFTER run() completes (needs LEGACY_BLOCKS/LEGACY_BLOCK_RECS
    // populated). Kept separate from run()'s own summary line on purpose: the existing
    // "UMB-HOSTAGENT blocks ok=... " summary format must not change, so this logs its own line.

    /** BlockEntityType umb:legacy_tile -- ONE generic type for every hasTileEntity twin (twin-first §6). */
    public static volatile BlockEntityType<UmbLegacyBlockEntity> LEGACY_TILE_TYPE;
    /** Namespace-owned tile types; the volatile field above remains the legacy single-mod fallback. */
    public static final Map<String, BlockEntityType<UmbLegacyBlockEntity>> LEGACY_TILE_TYPES =
            new LinkedHashMap<>();
    public static volatile int hasTileEntityBlockCount = -1;

    public static void registerLegacyTileAndMenu(String namespace) {
        // GENERALITY fix (laneCasing, Bug 1): callers (Hooks.beforeFreeze -> HostAgent.namespace())
        // may hand us the RAW legacy modid (e.g. "IronChest"), same as run() receives - sanitize
        // here too so the legacy_tile BlockEntityType/MenuType land in the SAME namespace run()
        // actually registered the blocks under, instead of throwing inside the catch below (an
        // Identifier with an uppercase namespace is invalid) or silently drifting to a different
        // namespace than the blocks it is supposed to cover.
        namespace = LegacyIds.sanitizeNamespace(namespace);
        try {
            Set<Block> tileBlocks = new HashSet<>();
            for (Map.Entry<String, BlockRec> e : LEGACY_BLOCK_RECS.entrySet()) {
                boolean snapshotTile = e.getValue() != null && e.getValue().hasTileEntity;
                // 1.16.5 snapshots do not have the 1.7.10 hasTileEntity bit.  Include blocks
                // owned by a registered era so vanilla accepts the generic BE factory; the live
                // era's createTile is still authoritative and returns null for a real non-TE.
                boolean eraOwned = UmbBridgeHost.get() instanceof BridgeRouter router
                        && router.isEraOwned(e.getKey());
                if (snapshotTile || eraOwned) {
                    Block b = LEGACY_BLOCKS.get(e.getKey());
                    if (b != null) tileBlocks.add(b);
                }
            }
            hasTileEntityBlockCount = tileBlocks.size();

            // BlockEntityType's factory needs the type instance itself as the BlockEntity ctor's
            // first argument, and the type doesn't exist until its own ctor returns -- the usual
            // one-element-holder trick, safe because the lambda isn't INVOKED until a real chunk
            // asks for a block entity, long after `holder[0]` is set.
            @SuppressWarnings("unchecked")
            BlockEntityType<UmbLegacyBlockEntity>[] holder = new BlockEntityType[1];
            BlockEntityType<UmbLegacyBlockEntity> type = new BlockEntityType<>(
                    (pos, state) -> new UmbLegacyBlockEntity(holder[0], pos, state),
                    Set.copyOf(tileBlocks));
            holder[0] = type;

            Identifier tileId = Identifier.fromNamespaceAndPath(namespace, "legacy_tile");
            ResourceKey<BlockEntityType<?>> tileKey = ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, tileId);
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, tileKey, type);
            LEGACY_TILE_TYPES.put(namespace, type);
            LEGACY_TILE_TYPE = type;
            AgentLog.loud("PATCHED BlockEntityType registered: " + tileId + " validBlocks=" + tileBlocks.size());
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering BlockEntityType: " + t);
            AgentLog.error("Registrar.registerLegacyTileAndMenu (tile)", t, 6);
        }

        try {
            UmbMenuRegistration.registerMenuType(namespace);
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering MenuType: " + t);
            AgentLog.error("Registrar.registerLegacyTileAndMenu (menu)", t, 6);
        }

        try {
            // Client-side only, but harmless to attempt headlessly too (pure reflection + one
            // ASM-generated class, no GL/render context touched) -- own try/catch so a dedicated
            // server process (no MenuScreens class meaningfully usable) never loses the tile/menu
            // registration above over this.
            UmbMenuRegistration.registerScreen();
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering screen: " + t);
            AgentLog.error("Registrar.registerLegacyTileAndMenu (screen)", t, 6);
        }

        AgentLog.loud("UMB-HOSTAGENT-TILE tileBlocks=" + hasTileEntityBlockCount
                + " blockEntityTypeRegistered=" + (LEGACY_TILE_TYPE != null)
                + " menuTypeRegistered=" + (UmbMenuRegistration.menuType() != null)
                + " screenRegistered=" + UmbMenuRegistration.isScreenRegistered());
    }

    /** Returns the generic tile type belonging to one registered legacy namespace. */
    static BlockEntityType<UmbLegacyBlockEntity> tileTypeFor(String namespace) {
        if (namespace == null || namespace.isEmpty()) return LEGACY_TILE_TYPE;
        return LEGACY_TILE_TYPES.getOrDefault(LegacyIds.sanitizeNamespace(namespace),
                LEGACY_TILE_TYPE);
    }

    // ---------------------------------------------------------- ENTITY-BRIDGE: entity type registration
    //
    // Called from Hooks.beforeFreeze, same pre-freeze window as registerLegacyTileAndMenu (no new
    // agent hook needed - ENTITY-BRIDGE.md §2.2 confirmed BuiltInRegistries.ENTITY_TYPE freezes at
    // the exact same moment BLOCK/ITEM/BLOCK_ENTITY_TYPE/MENU_TYPE already do).

    /** Namespace-owned generic EntityTypes; the namespace is the only host-side ownership key. */
    public static volatile EntityType<UmbLegacyEntity> LEGACY_ENTITY_TYPE;
    public static final Map<String, EntityType<UmbLegacyEntity>> LEGACY_ENTITY_TYPES = new LinkedHashMap<>();

    /**
     * ENTITY-BRIDGE §3.1: one generic {@link EntityType}, not 149 subclasses - the legacy class
     * identity travels inside the twin's own NBT blob, exactly like {@code umb:legacy_tile}. The
     * EntityType is selected by namespace at spawn time; its builder dimensions are only a safe
     * construction default because {@link UmbLegacyEntity#getDimensions} refreshes each instance
     * from the live legacy width/height.
     */
    public static void registerLegacyEntityType(String namespace) {
        namespace = LegacyIds.sanitizeNamespace(namespace);
        try {
            if (LEGACY_ENTITY_TYPES.containsKey(namespace)) return;
            Identifier id = Identifier.fromNamespaceAndPath(namespace, "legacy_entity");
            ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
            EntityType<UmbLegacyEntity> type = EntityType.Builder
                    .<UmbLegacyEntity>of(UmbLegacyEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .build(key);
            Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
            LEGACY_ENTITY_TYPES.put(namespace, type);
            LEGACY_ENTITY_TYPE = type;
            AgentLog.loud("PATCHED EntityType registered: " + id);
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering EntityType: " + t);
            AgentLog.error("Registrar.registerLegacyEntityType", t, 6);
        }
    }

    static EntityType<UmbLegacyEntity> entityTypeFor(String namespace) {
        if (namespace == null || namespace.isEmpty()) return null;
        return LEGACY_ENTITY_TYPES.get(LegacyIds.sanitizeNamespace(namespace));
    }

    /** Highest column the creative screen can draw a tab sprite for (arrays are 7 long). */
    private static final int LAST_DRAWABLE_COLUMN = 6;

    private Registrar() {
    }

    /** Back-compat overload: no block-shapes.json / gui-profile.json (every twin keeps the
     *  default full cube shape and every GUI falls back to the 176x166 dispenser panel). */
    public static void run(Path snapshotFile, Path langFile, String ns) {
        run(snapshotFile, langFile, null, null, ns);
    }

    public static void run(Path snapshotFile, Path langFile, Path blockShapesFile, Path guiProfileFile, String ns) {
        long t0 = System.nanoTime();
        if (snapshotFile == null) {
            AgentLog.loud("UMB-HOSTAGENT no snapshot= argument, nothing to do");
            return;
        }
        LegacySnapshot snap;
        try {
            snap = LegacySnapshot.load(snapshotFile, ns);
        } catch (Exception e) {
            AgentLog.loud("UMB-HOSTAGENT FAILED reading snapshot " + snapshotFile + ": " + e);
            AgentLog.error("Registrar.load", e, 5);
            return;
        }
        LangTable lang = LangTable.load(langFile);
        BlockShapeProfile shapes = BlockShapeProfile.load(blockShapesFile);
        // Multi-mod: MERGE each mod's GUI profile (was a replace - last mod erased the rest).
        GuiProfile loadedGui = GuiProfile.load(guiProfileFile);
        GUI_PROFILE = GUI_PROFILE.size() == 0 ? loadedGui : GUI_PROFILE.mergeFrom(loadedGui);
        AgentLog.line("snapshot loaded: blocks=" + snap.blocks.size() + " items=" + snap.items.size()
                + " tabs=" + snap.tabs.size() + " lang entries=" + lang.size()
                + " blockShapes=" + shapes.size() + " (from " + blockShapesFile + ")"
                + " guiProfile=" + GUI_PROFILE.size() + " (from " + guiProfileFile + ")");

        // shapes must be loaded before the plan is built: VariantPlan's shape-only-twin step
        // (multiblock-notes/SHAPE-VARIANT-LANE) needs the SAME BlockShapeProfile the block loop
        // below already builds real shapes from, so both agree on which metas got a twin.
        VariantPlan plan = VariantPlan.build(snap, lang, shapes);
        LegacyIds ids = plan.ids;
        String namespace = LegacyIds.sanitizeNamespace(ns);
        // FLUID-LANE hook: registers namespace-owned snapshot fluids while registries are writable.
        FluidRegistrar.register(snapshotFile, namespace);
        AgentLog.line("variant plan: " + plan.stats());
        List<String> dupes = plan.duplicatePaths();
        if (!dupes.isEmpty()) {
            AgentLog.loud("UMB-HOSTAGENT DUPLICATE-IDS " + dupes.size() + " (first: " + dupes.get(0) + ")");
            for (int i = 0; i < Math.min(10, dupes.size()); i++) AgentLog.line("  DUP " + dupes.get(i));
        }

        int blocksOk = 0, blocksFailed = 0, itemsOk = 0, itemsFailed = 0, blockItemsSkipped = 0;
        int tabsOk = 0, tabsFailed = 0, blockVariants = 0, itemVariants = 0;
        int blocksNonDefaultShape = 0, blocksMultiBoxShape = 0;
        // multiblock-notes lane: how many registered variants declare a shape extending past their
        // own 1x1x1 cell (blocksOutOfCellShape) and how many of those were trusted enough by
        // BlockShapes.isUnclamped to actually BUILD unclamped (blocksUnclampedShape) - the
        // difference is the count still conservatively clamped because at least one box sat at the
        // extractor's own query-window edge (see BlockShapes' class javadoc).
        int blocksOutOfCellShape = 0, blocksUnclampedShape = 0;
        // shape-variant lane: VariantPlan.BlockEntry#shapeOnly twins actually registered here -
        // real Blocks with NO BlockItem/creative-tab entry (see the loop below).
        int blocksShapeOnly = 0;
        // no-collision lane (LIVE-GAP-ANALYSIS.md section 4): registered variants whose 1.7.10
        // data recorded an explicit-null collisionAabb with no collisionBoxes - walk-through in
        // 1.7.10, now registered with an EMPTY collision VoxelShape (corpus baseline: 276 of
        // 1900 meta-groups; this counts registered VARIANTS, whose denominator is blocksOk).
        int blocksNoCollision = 0;
        int blocksRandomTick = 0;

        // ---- blocks (+ their ItemBlocks), one per 1.7.10 metadata variant ----
        Map<String, List<ItemLike>> byTab = new LinkedHashMap<>();
        List<ItemLike> orphans = new ArrayList<>();

        for (VariantPlan.BlockEntry e : plan.blocks) {
            BlockRec rec = e.base;
            try {
                Identifier rl = Identifier.fromNamespaceAndPath(namespace, e.path);
                ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, rl);
                BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(key);

                if (rec.unbreakable) {
                    p = p.strength(-1.0F, 3600000.0F);
                } else {
                    p = p.strength(clamp(rec.hardness, 0.0F, 100.0F),
                            clamp(rec.resistance, 0.0F, 3600000.0F));
                }
                if (rec.lightValue > 0) {
                    final int lv = Math.min(15, rec.lightValue);
                    p = p.lightLevel(state -> lv);
                }
                // 1.7.10's own occlusion contract is the conjunction of isOpaqueCube,
                // renderAsNormalBlock, and getRenderType.  A custom render type is not the
                // normal full-cube pass, so it must not hide neighboring faces even when the
                // legacy block reports opaqueCube=true.  These are probe DATA, never model
                // geometry or a mod-specific rule.
                if (!rec.opaqueCube || !rec.renderAsNormalBlock || rec.renderType != 0) {
                    p = p.noOcclusion();
                }
                // TICK/CONTACT lane: 26.2 only ever delivers randomTick() to blocks whose
                // Properties said randomTicks(), so the snapshot's tickRandomly (func_149653_t,
                // getTickRandomly) is the per-block opt-in - fluids, fire, gases. Blanket-enabling
                // instead would hand every block spurious updateTick calls 1.7.10 never gave it.
                if (rec.tickRandomly) {
                    p = p.randomTicks();
                    blocksRandomTick++;
                }
                p = p.sound(soundFor(rec.stepSound));
                MapColor mc = mapColorFor(rec);
                if (mc != null) p = p.mapColor(mc);
                if (rec.harvestTool != null) p = p.requiresCorrectToolForDrops();
                if (rec.slipperiness > 0.0F && rec.slipperiness != 0.6F) p = p.friction(rec.slipperiness);
                // 26.2 caches collision shapes on BlockStateBase unless the block declares a
                // dynamic shape.  Legacy bounds may read the live tile at this position, so
                // opt those extracted blocks out of the cache before any state is registered.
                if (DynLiveBounds.isLiveBounds(e.legacyId)) {
                    p = p.dynamicShape();
                }

                // Task A: the real 1.7.10 collision/outline shape for THIS metadata variant, not
                // the default full cube - see BlockShapes' class javadoc for the out-of-cell decision.
                BlockShapeProfile.BlockEntry shapeEntry = shapes.get(rec.id);
                BlockShapeProfile.MetaGroup group = shapeEntry == null ? null : shapeEntry.groupFor(e.meta);
                VoxelShape shape = BlockShapes.build(group);
                VoxelShape collisionShape = BlockShapes.buildCollision(group);
                if (BlockShapes.isNonDefault(group)) {
                    blocksNonDefaultShape++;
                    if (BlockShapes.contributingBoxCount(group) > 1) blocksMultiBoxShape++;
                }
                if (BlockShapes.declaresOutOfCellShape(group)) {
                    blocksOutOfCellShape++;
                    if (BlockShapes.isUnclamped(group)) blocksUnclampedShape++;
                }
                if (BlockShapes.hasNoCollision(group)) blocksNoCollision++;

                Block block = new UmbLegacyBlock(p, rec, shape, collisionShape);
                Registry.register(BuiltInRegistries.BLOCK, key, block);
                LEGACY_BLOCKS.put(e.legacyId, block);
                LEGACY_VARIANT_BLOCKS.put(e.legacyKey(), block);
                LEGACY_TO_NATIVE.put(e.legacyId, block);
                LEGACY_BLOCK_RECS.put(e.legacyId, rec);
                blocksOk++;
                if (e.variant) blockVariants++;

                // shape-variant lane: a shape-only twin (VariantPlan.BlockEntry#shapeOnly) is a
                // metadata a mod's OWN placement/runtime code writes, never a subBlocks choice a
                // player picks from a menu - so it gets no BlockItem, no creative-tab slot, and
                // (per the flag's own contract) no display name. HostWorldImpl.setBlock's
                // LEGACY_VARIANT_BLOCKS lookup above already has everything it needs for this
                // twin; there is nothing left to register on the item side.
                if (!e.shapeOnly) {
                    // the ItemBlock shares the block's identifier
                    ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, rl);
                    Item.Properties ip = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
                    BlockItem bi = new BlockItem(block, ip);
                    bi.registerBlocks(Item.BY_BLOCK, bi);
                    Registry.register(BuiltInRegistries.ITEM, itemKey, bi);
                    LEGACY_ITEMS.put(e.legacyId, bi);
                    LEGACY_VARIANT_ITEMS.put(e.legacyKey(), bi);
                    itemsOk++;
                    place(byTab, orphans, rec.creativeTab, bi);
                } else {
                    blocksShapeOnly++;
                }
            } catch (Throwable t) {
                blocksFailed++;
                AgentLog.error("block " + e.legacyId, t, 3);
            }
        }

        int statesCached = primeBlockStateCaches();

        // ---- plain items, one per distinct sub-item damage ----
        for (VariantPlan.ItemEntry e : plan.items) {
            ItemRec rec = e.base;
            try {
                Identifier rl = Identifier.fromNamespaceAndPath(namespace, e.path);
                ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, rl);
                // Post-flattening snapshots (1.13+) list a block's item under the block's own id and
                // carry no isBlockItem flag: that item IS the BlockItem registered above. Link to it
                // BEFORE constructing anything - a constructed-but-unregistered Item leaves an
                // intrusive holder behind and BuiltInRegistries.freeze() crashes the client.
                Item existing = BuiltInRegistries.ITEM.getValue(key);
                if (BuiltInRegistries.ITEM.containsKey(key) && existing != null) {
                    LEGACY_ITEMS.put(e.legacyId, existing);
                    LEGACY_VARIANT_ITEMS.put(e.legacyKey(), existing);
                    LEGACY_TO_NATIVE.put(e.legacyId, existing);
                    blockItemsSkipped++;
                    continue;
                }
                Item.Properties ip = new Item.Properties().setId(key).useItemDescriptionPrefix();
                // durability() forces stack size 1 in vanilla, so the two are mutually exclusive
                if (rec.maxDamage > 0) {
                    ip = ip.durability(rec.maxDamage);
                } else {
                    ip = ip.stacksTo(rec.clampedStackSize());
                }
                Item item = new UmbLegacyItem(ip, rec);
                Registry.register(BuiltInRegistries.ITEM, key, item);
                LEGACY_ITEMS.put(e.legacyId, item);
                LEGACY_VARIANT_ITEMS.put(e.legacyKey(), item);
                LEGACY_TO_NATIVE.put(e.legacyId, item);
                itemsOk++;
                if (e.variant) itemVariants++;
                place(byTab, orphans, rec.creativeTab, item);
            } catch (Throwable t) {
                itemsFailed++;
                AgentLog.error("item " + e.legacyId, t, 3);
            }
        }

        // legacy ItemBlock records map onto the block's own BlockItem, per metadata variant
        for (ItemRec rec : snap.items) {
            if (rec.isBlockItem == null) continue;
            blockItemsSkipped++;
            Item bi = LEGACY_ITEMS.get(rec.isBlockItem);
            if (bi != null) {
                LEGACY_ITEMS.put(rec.id, bi);
                LEGACY_TO_NATIVE.put(rec.id, bi);
            }
            for (SubRec sub : SubRec.distinctByMeta(rec.subItems)) {
                Item v = LEGACY_VARIANT_ITEMS.get(rec.isBlockItem + "@" + sub.meta);
                if (v == null) v = bi;
                if (v != null) LEGACY_VARIANT_ITEMS.put(rec.id + "@" + sub.meta, v);
            }
        }

        // ---- creative tabs ----
        int nextColumn = firstFreeTopColumn();
        for (TabRec tab : snap.tabs) {
            if (tab.isVanillaLabel()) continue;
            List<ItemLike> contents = byTab.get(tab.label);
            try {
                String path = LegacyIds.sanitizePath(tab.label);
                if (path == null || path.isEmpty()) {
                    tabsFailed++;
                    continue;
                }
                String title = lang.getOr("itemGroup." + tab.label, prettyLabel(tab.label));
                if (registerTab(namespace, path, title, nextColumn,
                        contents == null ? List.of() : contents, tab.iconItemId, tab.iconMeta)) {
                    nextColumn++;
                    tabsOk++;
                } else {
                    tabsFailed++;
                }
            } catch (Throwable t) {
                tabsFailed++;
                AgentLog.error("tab " + tab.label, t, 3);
            }
        }
        // catch-all: HBM content whose 1.7.10 tab was a VANILLA tab (or null). Those vanilla tabs
        // exist on 26.2 but are not ours to rebuild, so the content would otherwise never reach
        // the creative search (search aggregates the display items of registered tabs).
        List<ItemLike> leftovers = new ArrayList<>(orphans);
        for (Map.Entry<String, List<ItemLike>> e : byTab.entrySet()) {
            if (LegacySnapshot.VANILLA_TAB_LABELS.contains(e.getKey())) leftovers.addAll(e.getValue());
        }
        if (!leftovers.isEmpty()) {
            try {
                // GENERALITY fix (hostagent-purge #15): this used to hardcode the brand string
                // "NTM Other (legacy tabs)" for every mod's catch-all tab. Interpolate the real
                // namespace instead - for the test mod (ns=hbm) this reads "HBM Other (legacy
                // tabs)" rather than "NTM Other (legacy tabs)"; the two brand strings were never
                // the same thing (NTM is HBM's own in-universe name, not its namespace), so this
                // is a disclosed, deliberate cosmetic-text change, not a byte-identical one - no
                // test asserts the old literal (grep-confirmed) and no game logic reads tab titles.
                String catchAllTitle = namespace.toUpperCase(Locale.ROOT) + " Other (legacy tabs)";
                if (registerTab(namespace, "legacy_other", catchAllTitle,
                        nextColumn, leftovers, null, 0)) {
                    nextColumn++;
                    tabsOk++;
                } else {
                    tabsFailed++;
                }
            } catch (Throwable t) {
                tabsFailed++;
                AgentLog.error("tab legacy_other", t, 3);
            }
        }

        for (Map.Entry<String, String> e : ids.renames().entrySet()) {
            AgentLog.line("RENAMED " + e.getKey() + " -> " + namespace + ":" + e.getValue());
        }

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        summary = "UMB-HOSTAGENT blocks ok=" + blocksOk + " failed=" + blocksFailed
                + " items ok=" + itemsOk + " failed=" + itemsFailed
                + " blockItemsSkipped=" + blockItemsSkipped
                + " tabs ok=" + tabsOk + " failed=" + tabsFailed
                + " blockStatesCached=" + statesCached
                + " blockVariants=" + blockVariants
                + " itemVariants=" + itemVariants
                + " renamed=" + ids.renameCount()
                + " blocksNonDefaultShape=" + blocksNonDefaultShape
                + " blocksMultiBoxShape=" + blocksMultiBoxShape
                + " blocksOutOfCellShape=" + blocksOutOfCellShape
                + " blocksUnclampedShape=" + blocksUnclampedShape
                + " blocksShapeOnly=" + blocksShapeOnly
                + " blocksNoCollision=" + blocksNoCollision
                + " blocksRandomTick=" + blocksRandomTick
                + " ms=" + ms;
        AgentLog.loud(summary);
    }

    /**
     * The step vanilla only ever performs for its OWN blocks.
     *
     * Blocks.&lt;clinit&gt; ends with
     *     for (Block b : BuiltInRegistries.BLOCK)
     *         for (BlockState s : b.getStateDefinition().getPossibleStates()) {
     *             Block.BLOCK_STATE_REGISTRY.add(s);
     *             s.initCache();
     *         }
     * and that class is already initialised by the time our hook runs, so every block we add
     * afterwards has BlockStateBase.occlusionShapesByFace == null. The world renders fine until
     * one of our blocks is next to anything, then chunk meshing dies with
     *   NullPointerException: Cannot load from object array because "this.occlusionShapesByFace" is null
     *     at BlockBehaviour$BlockStateBase.getFaceOcclusionShape -> Block.shouldRenderFace
     * (observed live: crash-2026-09-07_22.20.35-client.txt). Doing the same two calls ourselves
     * is the whole fix.
     */
    private static int primeBlockStateCaches() {
        int n = 0;
        for (Block b : LEGACY_BLOCKS.values()) {
            try {
                for (BlockState s : b.getStateDefinition().getPossibleStates()) {
                    Block.BLOCK_STATE_REGISTRY.add(s);
                    s.initCache();
                    n++;
                }
            } catch (Throwable t) {
                AgentLog.error("initCache for " + b, t, 3);
            }
        }
        AgentLog.line("primed BLOCK_STATE_REGISTRY + initCache for " + n + " block states");
        return n;
    }

    private static void place(Map<String, List<ItemLike>> byTab, List<ItemLike> orphans,
                              String tabLabel, ItemLike thing) {
        if (tabLabel == null) {
            orphans.add(thing);
        } else {
            byTab.computeIfAbsent(tabLabel, k -> new ArrayList<>()).add(thing);
        }
    }

    private static boolean registerTab(String namespace, String path, String title, int column,
                                       List<ItemLike> contents, String iconLegacyId, int iconMeta) throws Exception {
        Identifier rl = Identifier.fromNamespaceAndPath(namespace, path);
        ResourceKey<CreativeModeTab> key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, rl);
        final List<ItemLike> items = List.copyOf(contents);
        final String iconId = iconLegacyId;
        final int iconM = iconMeta;
        CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, column)
                .title(Component.literal(title))
                .icon(() -> iconStack(iconId, iconM, items))
                .displayItems(DisplayItemsBridge.of(items))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key, tab);
        AgentLog.line("TAB " + rl + " column=" + column + " title=\"" + title + "\" entries=" + items.size()
                + " page=" + dev.umb.hostagent.CreativePaging.pageOfColumn(column));
        return true;
    }

    private static ItemStack iconStack(String iconLegacyId, int iconMeta, List<ItemLike> contents) {
        try {
            if (iconLegacyId != null) {
                // the 1.7.10 tab icon is an (id, damage) pair, so prefer the flattened variant
                Item it = LEGACY_VARIANT_ITEMS.get(iconLegacyId + "@" + iconMeta);
                if (it == null) it = LEGACY_ITEMS.get(iconLegacyId);
                if (it != null) return new ItemStack(it);
            }
            if (!contents.isEmpty()) return new ItemStack(contents.get(0));
        } catch (Throwable ignored) {
            // fall through
        }
        return new ItemStack(Blocks.STONE);
    }

    /**
     * Vanilla 26.2 fills TOP 0..6 and BOTTOM 0..6, so this normally returns 7 - past the
     * drawable strip. CreativeModeTabs.validate() only rejects duplicate (row,column) pairs,
     * and CreativeTabSpritePatcher keeps the renderer from indexing off the sprite array.
     */
    private static int firstFreeTopColumn() {
        Set<Integer> used = new HashSet<>();
        // NOTE: at freeze() entry the Holder.References are still UNBOUND - MappedRegistry binds
        // values inside freeze() itself - so Registry.getValue()/iterator() throw
        // "Trying to access unbound value". The already-registered instances are reachable from
        // the private MappedRegistry.byValue map, which is keyed BY the value.
        try {
            Object reg = BuiltInRegistries.CREATIVE_MODE_TAB;
            java.lang.reflect.Field f = null;
            for (Class<?> c = reg.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("byValue");
                } catch (NoSuchFieldException ignored) {
                    // keep walking up
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object m = f.get(reg);
                if (m instanceof Map<?, ?> map) {
                    for (Object v : map.keySet()) {
                        if (v instanceof CreativeModeTab t && t.row() == CreativeModeTab.Row.TOP) {
                            used.add(t.column());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AgentLog.error("firstFreeTopColumn", t, 2);
        }
        if (used.isEmpty()) {
            // could not read the registry: vanilla 26.2 is known to fill TOP 0..6, so start past it
            AgentLog.line("could not read vanilla tab columns; assuming TOP 0.." + LAST_DRAWABLE_COLUMN + " are taken");
            for (int i = 0; i <= LAST_DRAWABLE_COLUMN; i++) used.add(i);
        }
        int c = 0;
        while (used.contains(c)) c++;
        AgentLog.line("vanilla TOP columns in use=" + new java.util.TreeSet<>(used) + " first free=" + c);
        return c;
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    static SoundType soundFor(String stepSound) {
        if (stepSound == null) return SoundType.STONE;
        switch (stepSound.toLowerCase(Locale.ROOT)) {
            case "wood": return SoundType.WOOD;
            case "gravel": return SoundType.GRAVEL;
            case "grass": return SoundType.GRASS;
            case "metal": return SoundType.METAL;
            case "glass": return SoundType.GLASS;
            case "cloth": return SoundType.WOOL;
            case "sand": return SoundType.SAND;
            case "snow": return SoundType.SNOW;
            case "ladder": return SoundType.LADDER;
            case "anvil": return SoundType.ANVIL;
            case "stone":
            default: return SoundType.STONE;
        }
    }

    /**
     * 1.7.10 MapColor ids are the same numbering modern MapColor.byId uses, so prefer the int;
     * fall back to the Material class name only when byId gives nothing usable.
     */
    static MapColor mapColorFor(BlockRec rec) {
        try {
            if (rec.mapColor >= 0) {
                MapColor c = MapColor.byId(rec.mapColor);
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
            // fall through to the material heuristic
        }
        String m = rec.material == null ? "" : rec.material.toLowerCase(Locale.ROOT);
        if (m.contains("iron") || m.contains("anvil")) return MapColor.METAL;
        if (m.contains("wood")) return MapColor.WOOD;
        if (m.contains("grass")) return MapColor.GRASS;
        if (m.contains("sand")) return MapColor.SAND;
        if (m.contains("cloth")) return MapColor.WOOL;
        return MapColor.STONE;
    }

    /** "tabParts" -> "NTM Parts"-ish fallback when the lang file has no itemGroup entry. */
    static String prettyLabel(String label) {
        String s = label;
        if (s.startsWith("tab") && s.length() > 3) s = s.substring(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) sb.append(' ');
            sb.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    /** Snapshot of what got registered, for the headless probe. */
    public static Map<String, Object> registered() {
        return new HashMap<>(LEGACY_TO_NATIVE);
    }
}
