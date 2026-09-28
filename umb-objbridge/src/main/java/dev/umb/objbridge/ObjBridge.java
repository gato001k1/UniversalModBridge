package dev.umb.objbridge;

import com.mojang.serialization.MapCodec;
import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.bake.QuadBaker;
import dev.umb.objbridge.bake.QuadGeom;
import dev.umb.objbridge.block.ObjBlockStateModel;
import dev.umb.objbridge.block.InvisibleBlockStateModel;
import dev.umb.objbridge.item.ObjItemModel;
import dev.umb.objbridge.itemeffects.HeldItemModel;
import dev.umb.objbridge.itemeffects.HeldItemRuntime;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import dev.umb.objbridge.obj.ObjAssets;
import dev.umb.objbridge.obj.ObjMesh;
import dev.umb.objbridge.transform.PathClass;
import dev.umb.objbridge.transform.RenderFit;
import dev.umb.objbridge.transform.RendererTransforms;
import dev.umb.objbridge.transform.ItemDisplayTransforms;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bytecode-visible seam of the OBJ bridge, plus the shared mesh/quad caches.
 *
 * <p>Two hooks, both spliced in by {@link ObjBridgeAgent}, both {@code static void}, neither ever
 * propagates a throwable into the game:
 * <ul>
 *   <li>{@link #registerItemModelType()} at the END of {@code ItemModels.bootstrap()V} - puts the
 *       {@code umb:obj} client-item model type into the (still late-bindable)
 *       {@code ItemModels.ID_MAPPER}.</li>
 *   <li>{@link #onModelsApplied(ModelManager)} at the END of
 *       {@code ModelManager.apply(ModelManager$ReloadState)V} - splices baked OBJ block-state models
 *       into the live {@code BlockStateModelSet.modelByState} IdentityHashMap.</li>
 * </ul>
 */
public final class ObjBridge {

    /** The client-item model {@code "type"} this lane owns. */
    public static final String TYPE_NAMESPACE = "umb";
    public static final String TYPE_PATH = "obj";

    private static volatile Path assetsRoot;
    private static volatile Path renderMapPath;
    private static volatile RenderMap renderMap;
    private static volatile Path transformsPath;
    private static volatile RendererTransforms rendererTransforms;
    private static volatile ItemDisplayTransforms itemDisplayTransforms;
    private static volatile List<ObjBridgeManifest.Loaded> mods = List.of();

    private static volatile boolean itemTypeRegistered;

    /** Parsed OBJ meshes, softly held so a 400k-triangle working set can be reclaimed. */
    private static final Map<String, SoftReference<ObjMesh>> MESHES = new ConcurrentHashMap<>();
    /** Baked quads, deduped across the many items that share one (obj, sprite, fit, groups). */
    private static final Map<QuadKey, BakeOutcome> QUADS = new ConcurrentHashMap<>();

    /**
     * {@code fit.linear()} is a {@code float[]}, which has reference identity equals/hashCode - unsafe
     * to put straight into a cache key (two blocks with genuinely different renderer transforms could
     * otherwise collide on {@code (model,sprite,onGround,size,groups)} alone, silently reusing the
     * WRONG baked geometry). {@code Arrays.toString} gives a cheap, correct, value-based key for the
     * 9-float array instead.
     */
    private record QuadKey(String model, TextureAtlasSprite sprite, boolean onGround, float size,
                           boolean auto, String linearKey, float maxExtent, boolean normalize,
                           List<String> groups, List<String> anchorGroups) {
        QuadKey(String model, TextureAtlasSprite sprite, Fit fit, List<String> groups,
                List<String> anchorGroups) {
            this(model, sprite, fit.onGround(), fit.size(), fit.auto(),
                    java.util.Arrays.toString(fit.linear()), fit.maxExtent(), fit.normalize(),
                    groups, anchorGroups);
        }
    }

    private ObjBridge() { }

    // ---------------------------------------------------------------- configuration

    public static void configure(Path assets, Path map) {
        configure(assets, map, null);
    }

    public static void configure(Path assets, Path map, Path transforms) {
        assetsRoot = assets;
        renderMapPath = map;
        transformsPath = transforms;
        renderMap = null; rendererTransforms = null; itemDisplayTransforms = null;
        itemRendererIndex = null; blockWorldClassIndex = null;
        try {
            RenderMap rm = map != null && Files.isRegularFile(map) ? RenderMap.read(map) : RenderMap.of(new com.google.gson.JsonObject());
            RendererTransforms rt = transforms != null && Files.isRegularFile(transforms)
                    ? RendererTransforms.read(transforms) : RendererTransforms.of(new com.google.gson.JsonObject());
            mods = List.of(new ObjBridgeManifest.Loaded(new ObjBridgeManifest.Mod("legacy", map, assets, transforms, null, null, null), rm, rt));
        } catch (Exception e) { throw new IllegalArgumentException("cannot configure OBJ bridge", e); }
    }

    public static void configureManifest(Path manifest) throws java.io.IOException {
        List<ObjBridgeManifest.Loaded> loaded = ObjBridgeManifest.load(manifest);
        mods = loaded;
        assetsRoot = loaded.isEmpty() ? null : loaded.get(0).mod().assetsRoot();
        renderMapPath = loaded.isEmpty() ? null : loaded.get(0).mod().renderMapPath();
        transformsPath = loaded.isEmpty() ? null : loaded.get(0).mod().transformsPath();
        renderMap = null; rendererTransforms = null; itemDisplayTransforms = null;
        itemRendererIndex = null; blockWorldClassIndex = null;
        ObjLog.loud("manifest loaded: mods=" + loaded.size() + " namespaces=" + loaded.stream().map(x -> x.mod().namespace()).toList());
    }

    static List<ObjBridgeManifest.Loaded> mods() { return mods; }

    /** Read-only manifest view for the held-item sidecar runtime; preserves multi-mod ownership. */
    public static List<ObjBridgeManifest.Loaded> loadedMods() { return List.copyOf(mods); }

    /** Render-map paths exposed to the client-only entity data loader. */
    public static List<Path> entityRenderMapPaths() {
        List<Path> out = new ArrayList<>();
        for (ObjBridgeManifest.Loaded mod : mods) out.add(mod.mod().renderMapPath());
        return List.copyOf(out);
    }

    public static Path assetsRoot() { return assetsRoot; }

    /** Lazily parsed; only the block splice needs it, so a missing file is not fatal for items. */
    public static RenderMap renderMap() {
        RenderMap m = renderMap;
        if (m != null) return m;
        synchronized (ObjBridge.class) {
            if (renderMap != null) return renderMap;
            Path p = renderMapPath;
            if (p == null || !Files.isRegularFile(p)) {
                ObjLog.line("render map missing (rendermap=" + p + "); block splice will do nothing");
                renderMap = RenderMap.of(new com.google.gson.JsonObject());
                return renderMap;
            }
            try {
                long t0 = System.nanoTime();
                renderMap = RenderMap.read(p);
                ObjLog.line("render map read: items=" + renderMap.items().size()
                        + " blocks=" + renderMap.blocks().size()
                        + " tileEntities=" + renderMap.tileEntities().size()
                        + " in " + ((System.nanoTime() - t0) / 1_000_000L) + "ms");
            } catch (Throwable t) {
                ObjLog.error("render map read " + p, t);
                renderMap = RenderMap.of(new com.google.gson.JsonObject());
            }
            return renderMap;
        }
    }

    /**
     * Lazily parsed; a missing/unreadable file degrades to "no transform data" (every model falls back
     * to legacy auto-fit) rather than failing the splice. See {@code research/out/legacy/rendermap/
     * renderer-transforms.json}, produced by umb-rendermap's {@code RendererTransformExtractor}.
     */
    public static RendererTransforms rendererTransforms() {
        RendererTransforms t = rendererTransforms;
        if (t != null) return t;
        synchronized (ObjBridge.class) {
            if (rendererTransforms != null) return rendererTransforms;
            if (mods.size() > 1) {
                com.google.gson.JsonObject merged = new com.google.gson.JsonObject();
                com.google.gson.JsonObject renderers = new com.google.gson.JsonObject();
                for (ObjBridgeManifest.Loaded mod : mods) {
                    Path p = mod.mod().transformsPath();
                    if (p == null || !Files.isRegularFile(p)) continue;
                    try {
                        com.google.gson.JsonObject root = new com.google.gson.Gson().fromJson(Files.readString(p), com.google.gson.JsonObject.class);
                        com.google.gson.JsonElement r = root == null ? null : root.get("renderers");
                        if (r != null && r.isJsonObject()) for (var e : r.getAsJsonObject().entrySet()) renderers.add(e.getKey(), e.getValue());
                    } catch (Exception e) { throw new IllegalArgumentException("cannot merge transforms " + p, e); }
                }
                merged.add("renderers", renderers);
                rendererTransforms = RendererTransforms.of(merged);
                return rendererTransforms;
            }
            Path p = transformsPath;
            if (p == null || !Files.isRegularFile(p)) {
                ObjLog.line("renderer transforms missing (transforms=" + p + "); every model auto-fits");
                rendererTransforms = RendererTransforms.of(new com.google.gson.JsonObject());
                return rendererTransforms;
            }
            try {
                long t0 = System.nanoTime();
                rendererTransforms = RendererTransforms.read(p);
                ObjLog.line("renderer transforms read: classes=" + rendererTransforms.classCount()
                        + " in " + ((System.nanoTime() - t0) / 1_000_000L) + "ms");
            } catch (Throwable ex) {
                ObjLog.error("renderer transforms read " + p, ex);
                rendererTransforms = RendererTransforms.of(new com.google.gson.JsonObject());
            }
            return rendererTransforms;
        }
    }

    /** Optional sibling sidecar; absence preserves the pre-sidecar display behavior. */
    public static ItemDisplayTransforms itemDisplayTransforms() {
        ItemDisplayTransforms t = itemDisplayTransforms;
        if (t != null) return t;
        synchronized (ObjBridge.class) {
            if (itemDisplayTransforms != null) return itemDisplayTransforms;
            Path p = transformsPath == null ? null : transformsPath.resolveSibling("item-display-transforms.json");
            if (p == null || !Files.isRegularFile(p)) return itemDisplayTransforms = ItemDisplayTransforms.empty();
            try {
                itemDisplayTransforms = ItemDisplayTransforms.read(p);
                ObjLog.line("item display transforms read: classes=" + itemDisplayTransforms.classCount());
            } catch (Throwable ex) {
                ObjLog.error("item display transforms read " + p, ex);
                itemDisplayTransforms = ItemDisplayTransforms.empty();
            }
            return itemDisplayTransforms;
        }
    }

    // ---------------------------------------------------------------- hook 1: items

    /**
     * Called from the end of {@code ItemModels.bootstrap()}. Idempotent: a second call is a no-op, so
     * a retransform or a double attach cannot make {@code BiMap.put} complain.
     */
    public static void registerItemModelType() {
        try {
            if (itemTypeRegistered) return;
            synchronized (ObjBridge.class) {
                if (itemTypeRegistered) return;
                MapCodec<ObjItemModel.Unbaked> codec = ObjItemModel.Unbaked.MAP_CODEC;
                idMapper().put(Identifier.fromNamespaceAndPath(TYPE_NAMESPACE, TYPE_PATH), codec);
                idMapper().put(Identifier.fromNamespaceAndPath(TYPE_NAMESPACE, "held"),
                        HeldItemModel.Unbaked.MAP_CODEC);
                HeldItemRuntime.loadFromManifest();
                itemTypeRegistered = true;
            }
            ObjLog.loud("REGISTERED item model type " + TYPE_NAMESPACE + ":" + TYPE_PATH);
            registerEntityRenderers();
        } catch (Throwable t) {
            ObjLog.loud("REGISTER-FAILED item model type: " + t);
            ObjLog.error("registerItemModelType", t, 6);
        }
    }

    /**
     * Runs immediately before 26.2 materializes the entity renderer map.  The item-model hook still
     * calls this for older launch orderings, but this separate entry point closes the dispatcher
     * timing window without coupling the renderer lane to item model bootstrap.
     */
    public static void registerEntityRenderers() {
        try {
            dev.umb.objbridge.entity.LegacyEntityRendererRegistration.install();
            dev.umb.objbridge.entity.LegacyBlockEntityRendererRegistration.install();
        } catch (Throwable t) {
            ObjLog.error("entity renderer registration", t, 6);
        }
    }

    /** {@code ItemModels.ID_MAPPER} is a private static field; {@code put} on it is public. */
    @SuppressWarnings("unchecked")
    static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>> idMapper()
            throws ReflectiveOperationException {
        Field f = ItemModels.class.getDeclaredField("ID_MAPPER");
        f.setAccessible(true);
        return (ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>>) f.get(null);
    }

    /** True when {@code umb:obj} is present in the mapper. Used by the headless probe. */
    public static boolean itemModelTypePresent() {
        try {
            return idMapper().values().contains(ObjItemModel.Unbaked.MAP_CODEC);
        } catch (Throwable t) {
            ObjLog.error("itemModelTypePresent", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- hook 2: blocks

    /**
     * Called from the end of {@code ModelManager.apply(ReloadState)} - i.e. on the render thread,
     * after {@code blockStateModelSet} has been replaced with the freshly baked one.
     */
    public static void onModelsApplied(ModelManager manager) {
        int cachedMeshes = MESHES.size(), cachedQuads = QUADS.size();
        QUADS.clear();   // the baked models keep their own references; this just frees the index
        try {
            spliceBlocks(manager);
        } catch (Throwable t) {
            ObjLog.loud("SPLICE-FAILED " + t);
            ObjLog.error("onModelsApplied", t, 8);
        }
        ObjLog.line("bake caches after apply: meshes=" + cachedMeshes + " quadLists=" + cachedQuads);
    }

    private static void spliceBlocks(ModelManager manager) throws ReflectiveOperationException {
        AtlasManager atlases = atlasManager(manager);
        BlockStateModelSet set = manager.getBlockStateModelSet();
        Map<BlockState, BlockStateModel> byState = modelByState(set);

        RendererTransforms transforms = rendererTransforms();
        int rows = 0, spliced = 0, states = 0, noObj = 0, noTex = 0, notRegistered = 0, failed = 0;
        int missingSprite = 0, invisibleParticleFallback = 0, tesrOnlyEmpty = 0;
        // scale-fix diagnostics: see research/out/legacy/objbridge/laneScale-progress.md
        int transformResolved = 0, transformIdentity = 0, autoFitFallback = 0, clamped = 0;
        int fellBackDegenerate = 0, dynamicSkippedTotal = 0;
        List<String> firstFailures = new ArrayList<>();

        // AtlasManager.get falls back to the atlas's own missing sprite rather than returning null,
        // so a silent miss looks like a success. Resolve that sentinel once and compare by identity.
        TextureAtlasSprite missing = null;
        try {
            missing = atlases.get(new SpriteId(TextureAtlas.LOCATION_BLOCKS,
                    net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()));
        } catch (Throwable ignored) {
            // no sentinel available: fall back to only the null check below
        }

        for (ObjBridgeManifest.Loaded mod : mods) {
            RenderMap map = mod.renderMap();
            Path modAssets = mod.mod().assetsRoot();
            for (RenderMap.BlockRow row : map.blocks()) {
            // The render map is produced per-target-jar (dev.umb.rendermap.RenderMap), so every row
            // it writes already belongs to whichever mod was analyzed - there is no need to, and no
            // generic way to, name that mod's namespace as a literal here. This used to hardcode
            // `!row.id().startsWith("hbm:")`, which silently dropped 100% of every non-HBM mod's
            // block rows (the splice hook would run, log success, and quietly do nothing). The only
            // namespace guaranteed non-mod is `minecraft:` - the same mod-agnostic "not vanilla"
            // definition already used by umb-rendermap's Snapshot.modBlocks()/modItems().
            if (row.id() == null || row.id().startsWith("minecraft:")) continue;
            rows++;
            List<RenderMap.Asset> textures = new ArrayList<>(row.textures());
            if (textures.isEmpty()) {
                RenderMap.TeRow te = map.tileEntityFor(row.id());
                if (te != null) textures.addAll(te.textures());
            }
            TexturePick.Pick pick = BlockTexturePolicy.choose(row.models(), textures, modAssets);
            // A legacy renderType -1 block is not part of the ordinary block pass. Its TESR (or
            // other live renderer) owns the world image, so never retain PackGen's icon cube and
            // never invent static geometry for a dummy/filler cell. The particle is preserved
            // below solely to keep model baking safe.
            boolean tesrOnly = row.renderType() == -1;
            if (tesrOnly) {
                try {
                    Identifier id = Identifier.parse(row.id());
                    if (BuiltInRegistries.BLOCK.containsKey(id)) {
                        Block block = BuiltInRegistries.BLOCK.getValue(id);
                        for (BlockState s : block.getStateDefinition().getPossibleStates()) {
                            // keep the particle of the model we replace (the block's generated
                            // model / its own texture); fall back to the block's default state,
                            // then to the vanilla missing-texture particle - never null.
                            BlockStateModel prev = byState.get(s);
                            net.minecraft.client.resources.model.sprite.Material.Baked particle = prev == null ? null : prev.particleMaterial();
                            if (particle == null) {
                                BlockStateModel def = byState.get(block.defaultBlockState());
                                particle = def == null ? null : def.particleMaterial();
                            }
                            if (particle == null && missing != null) particle = new net.minecraft.client.resources.model.sprite.Material.Baked(missing, false);
                            if (particle == null) { BlockStateModel stone = byState.get(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()); particle = stone == null ? null : stone.particleMaterial(); }
                            if (particle == null) { failed++; continue; } // leave the vanilla model in place rather than install a crashing one
                            if (prev == null || prev.particleMaterial() == null) invisibleParticleFallback++;
                            byState.put(s, InvisibleBlockStateModel.of(particle));
                            tesrOnlyEmpty++;
                            states++;
                        }
                        spliced++;
                    }
                } catch (Throwable t) { failed++; }
                continue;
            }
            if (pick.model() == null) { noObj++; continue; }
            if (pick.texture() == null) { noTex++; continue; }

            Identifier blockId;
            try {
                blockId = Identifier.parse(row.id());
            } catch (RuntimeException e) {
                notRegistered++;
                continue;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(blockId)) { notRegistered++; continue; }
            Block block = BuiltInRegistries.BLOCK.getValue(blockId);
            if (block == null) { notRegistered++; continue; }

            try {
                Identifier spriteName = Identifier.parse(pick.spriteId());
                TextureAtlasSprite sprite = atlases.get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, spriteName));
                if (sprite == null || sprite == missing) {
                    missingSprite++;
                    if (firstFailures.size() < 5) {
                        firstFailures.add(row.id() + ": no sprite " + spriteName + " on the block atlas");
                    }
                    continue;
                }

                String rendererClass = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
                RenderFit.Outcome outcome = RenderFit.forPath(transforms, rendererClass, PathClass.WORLD, true);
                if (outcome.resolved()) {
                    if (outcome.identity()) transformIdentity++; else transformResolved++;
                    dynamicSkippedTotal += outcome.dynamicSkipped();
                } else {
                    autoFitFallback++;
                }

                BakeOutcome baked = bakeQuads(pick.model().path(), sprite, outcome.fit(), row.groups());
                if (baked.clamped()) clamped++;
                if (baked.fellBackToAutoFit()) fellBackDegenerate++;
                if (baked.quads().isEmpty()) { failed++; continue; }
                ObjBlockStateModel model = new ObjBlockStateModel(baked.quads(), sprite);
                for (BlockState s : block.getStateDefinition().getPossibleStates()) {
                    byState.put(s, model);
                    states++;
                }
                spliced++;
            } catch (Throwable t) {
                failed++;
                if (firstFailures.size() < 5) firstFailures.add(row.id() + ": " + t);
            }
            }
        }
        ObjLog.loud("SPLICED blocks: rows=" + rows + " spliced=" + spliced + " states=" + states
                + " skipNoObj=" + noObj + " skipNoTexture=" + noTex
                + " skipNotRegistered=" + notRegistered + " skipMissingSprite=" + missingSprite
                + " tesrOnlyEmpty=" + tesrOnlyEmpty
                + " invisibleParticleFallback=" + invisibleParticleFallback + " failed=" + failed);
        ObjLog.loud("SCALE-FIX blocks: transformResolved=" + transformResolved
                + " transformIdentity=" + transformIdentity + " autoFitFallback=" + autoFitFallback
                + " clamped=" + clamped + " fellBackDegenerate=" + fellBackDegenerate
                + " dynamicOpsSkipped=" + dynamicSkippedTotal);
        for (String f : firstFailures) ObjLog.line("  splice failure: " + f);
    }

    static AtlasManager atlasManager(ModelManager manager) throws ReflectiveOperationException {
        Field f = ModelManager.class.getDeclaredField("atlasManager");
        f.setAccessible(true);
        return (AtlasManager) f.get(manager);
    }

    @SuppressWarnings("unchecked")
    static Map<BlockState, BlockStateModel> modelByState(BlockStateModelSet set)
            throws ReflectiveOperationException {
        Field f = BlockStateModelSet.class.getDeclaredField("modelByState");
        f.setAccessible(true);
        return (Map<BlockState, BlockStateModel>) f.get(set);
    }

    // ---------------------------------------------------------------- baking

    /** {@code quads()} plus the sizing diagnostics {@link dev.umb.objbridge.bake.MeshBaker.Result} carries. */
    public record BakeOutcome(List<BakedQuad> quads, boolean clamped, boolean fellBackToAutoFit) { }

    /** Cached mesh -> quad bake. {@code modelPath} is {@code "hbm:models/weapons/minigun.obj"}. */
    public static List<BakedQuad> quads(String modelPath, TextureAtlasSprite sprite, Fit fit,
                                        List<String> groups) {
        return bakeQuads(modelPath, sprite, fit, groups).quads();
    }

    /**
     * Same cached bake as {@link #quads}, but also reports whether this particular bake hit the sanity
     * clamp or fell back to auto-fit (see {@code MeshBaker}) - the block splice aggregates these into
     * its {@code SCALE-FIX blocks} log line.
     */
    public static BakeOutcome bakeQuads(String modelPath, TextureAtlasSprite sprite, Fit fit,
                                        List<String> groups) {
        return bakeQuads(modelPath, sprite, fit, groups, groups);
    }

    /**
     * Dynamic-OBJ entry point: emit only {@code groups}, but use one shared
     * model-frame anchor for centering and scale.  This preserves legacy TESR
     * part origins while keeping the cached bake immutable.
     */
    public static BakeOutcome bakeQuads(String modelPath, TextureAtlasSprite sprite, Fit fit,
                                        List<String> groups, List<String> anchorGroups) {
        List<String> g = groups == null ? List.of() : List.copyOf(groups);
        List<String> a = anchorGroups == null ? List.of() : List.copyOf(anchorGroups);
        QuadKey key = new QuadKey(modelPath, sprite, fit, g, a);
        return QUADS.computeIfAbsent(key, k -> {
            ObjMesh mesh = mesh(modelPath);
            if (mesh == null) return new BakeOutcome(List.of(), false, false);
            MeshBaker.Result r = MeshBaker.bake(mesh, fit, g, a);
            List<QuadGeom> geoms = r.quads();
            if (geoms.isEmpty()) {
                ObjLog.line("BAKE-EMPTY " + modelPath + " tris=" + r.trianglesIn()
                        + " skipped=" + r.skipped());
                return new BakeOutcome(List.of(), r.clamped(), r.fellBackToAutoFit());
            }
            return new BakeOutcome(QuadBaker.bake(geoms, sprite), r.clamped(), r.fellBackToAutoFit());
        });
    }

    /** Parses (and softly caches) an OBJ from the {@code assets=} root. */
    public static ObjMesh mesh(String modelPath) {
        SoftReference<ObjMesh> ref = MESHES.get(modelPath);
        ObjMesh cached = ref == null ? null : ref.get();
        if (cached != null) return cached;
        Path p = resolve(modelPath);
        if (p == null || !Files.isRegularFile(p)) {
            ObjLog.line("OBJ-MISSING " + modelPath + " -> " + p);
            return null;
        }
        try {
            ObjMesh m = ObjMesh.parse(p);
            if (!m.errors().isEmpty()) {
                ObjLog.line("OBJ-WARN " + modelPath + " errors=" + m.errors().size()
                        + " first=" + m.errors().get(0));
            }
            MESHES.put(modelPath, new SoftReference<>(m));
            return m;
        } catch (Throwable t) {
            ObjLog.error("parse " + modelPath, t);
            return null;
        }
    }

    /** {@code hbm:models/weapons/minigun.obj} -> {@code <assets>/assets/hbm/models/weapons/minigun.obj}. */
    public static Path resolve(String modelPath) {
        String ns = namespaceOf(modelPath);
        for (ObjBridgeManifest.Loaded mod : mods) if (mod.mod().namespace().equals(ns))
            return ObjAssets.resolve(mod.mod().assetsRoot(), modelPath);
        return ObjAssets.resolve(assetsRoot, modelPath);
    }

    private static String namespaceOf(String id) {
        int c = id == null ? -1 : id.indexOf(':');
        return c > 0 ? id.substring(0, c) : "minecraft";
    }

    /** Diagnostics for the headless probe / report. */
    public static Map<String, String> stats() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("assetsRoot", String.valueOf(assetsRoot));
        m.put("renderMap", String.valueOf(renderMapPath));
        m.put("transforms", String.valueOf(transformsPath));
        m.put("itemTypeRegistered", String.valueOf(itemTypeRegistered));
        m.put("meshes", String.valueOf(MESHES.size()));
        m.put("quadLists", String.valueOf(QUADS.size()));
        m.put("itemGeometryFromWorld", String.valueOf(ITEM_GEOMETRY_FROM_WORLD.get()));
        m.put("itemGeometryRawAutoFit", String.valueOf(ITEM_GEOMETRY_RAW_AUTOFIT.get()));
        return m;
    }

    static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    // ---------------------------------------------------------------- item renderer lookup

    /**
     * {@code (obj model path, sprite id)} -> legacy renderer class, for the item-perspective scale fix
     * in {@link dev.umb.objbridge.item.ObjTransforms#forItem}. The {@code umb:obj} codec does not carry
     * a renderer class (it is a plain model+texture reference, unchanged from before this lane), so
     * this reverse-indexes the render map once, the same way {@link #spliceBlocks} resolves a row's
     * model+texture, and looks the pair back up at item-bake time.
     *
     * <p>For a block's OWN item form, the class that actually implements the held/inventory/dropped
     * {@code IItemRenderer} is very often the TESR's anonymous inner class ({@code RenderXyz$1} - see
     * {@code RenderRadarLarge$1}/{@code RenderNukeTsar$1}/{@code RenderCargoElevator$1} in this lane's
     * progress notes), never the TESR class itself (which only ever draws the PLACED block). That inner
     * class is preferred when {@code renderer-transforms.json} actually has it; otherwise the TESR/ISBRH
     * class itself is used (still meaningful: e.g. `renderInventoryBlock` ops recorded directly on an
     * ISBRH class like `RenderAnvil`).
     */
    private static volatile Map<String, String> itemRendererIndex;
    /**
     * (model,sprite) -> the block's own OUTER TESR/ISBRH class (never the {@code $1} inner item
     * renderer), i.e. exactly the class {@link #spliceBlocks} resolves {@code PathClass.WORLD} against
     * for that same block. Only populated for {@link RenderMap.BlockRow}s - plain items have no "world"
     * shape to borrow proportions from. See {@link #itemGeometryFit} / laneInv-progress.md.
     */
    private static volatile Map<String, String> blockWorldClassIndex;

    private static void buildIndicesIfAbsent() {
        if (itemRendererIndex != null && blockWorldClassIndex != null) return;
        synchronized (ObjBridge.class) {
            if (itemRendererIndex != null && blockWorldClassIndex != null) return;
            Map<String, String> perspective = new HashMap<>();
            Map<String, String> world = new HashMap<>();
            RendererTransforms transforms = rendererTransforms();
            for (ObjBridgeManifest.Loaded mod : mods) {
              RenderMap map = mod.renderMap();
              Path modAssets = mod.mod().assetsRoot();
            for (RenderMap.ItemRow row : map.items()) {
                if (row.rendererClass() == null) continue;
                TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), modAssets);
                if (!pick.resolved()) continue;
                perspective.putIfAbsent(itemKey(pick.model().path(), pick.spriteId()), row.rendererClass());
            }
            for (RenderMap.BlockRow row : map.blocks()) {
                String cls = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
                if (cls == null) continue;
                List<RenderMap.Asset> textures = new ArrayList<>(row.textures());
                if (textures.isEmpty()) {
                    RenderMap.TeRow te = map.tileEntityFor(row.id());
                    if (te != null) textures.addAll(te.textures());
                }
                TexturePick.Pick pick = TexturePick.choose(row.models(), textures, modAssets);
                if (!pick.resolved()) continue;
                String key = itemKey(pick.model().path(), pick.spriteId());
                String inner = cls + "$1";
                String preferred = transforms.hasClass(inner) ? inner : cls;
                perspective.putIfAbsent(key, preferred);
                world.putIfAbsent(key, cls);
            }
            }
            itemRendererIndex = perspective;
            blockWorldClassIndex = world;
            ObjLog.line("item renderer index built: " + perspective.size() + " (model,sprite) pairs, "
                    + world.size() + " with a block WORLD-path class");
        }
    }

    private static Map<String, String> itemRendererIndex() {
        buildIndicesIfAbsent();
        return itemRendererIndex;
    }

    private static Map<String, String> blockWorldClassIndex() {
        buildIndicesIfAbsent();
        return blockWorldClassIndex;
    }

    private static String itemKey(String modelPath, String spriteId) {
        return modelPath + "|" + spriteId;
    }

    /** Null when this (model, sprite) pair cannot be traced back to any render-map row. */
    public static String rendererClassForItem(String modelPath, String spriteId) {
        return itemRendererIndex().get(itemKey(modelPath, spriteId));
    }

    // ---------------------------------------------------------------- item geometry fit (block-in-slot)

    private static final java.util.concurrent.atomic.AtomicInteger ITEM_GEOMETRY_FROM_WORLD =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ITEM_GEOMETRY_RAW_AUTOFIT =
            new java.util.concurrent.atomic.AtomicInteger();

    /** How many item bakes used the block-item-in-slot (transformed+normalised) rule. */
    public static int itemGeometryFromWorldCount() { return ITEM_GEOMETRY_FROM_WORLD.get(); }
    /** How many item bakes used the plain raw-mesh auto-fit (items without a resolved legacy renderer,
     *  plus any block whose WORLD-path class is unknown to {@code renderer-transforms.json}). */
    public static int itemGeometryRawAutoFitCount() { return ITEM_GEOMETRY_RAW_AUTOFIT.get(); }

    private static final java.util.concurrent.atomic.AtomicInteger ITEM_GEOMETRY_FROM_LEGACY_RENDERER =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int itemGeometryFromLegacyRendererCount() {
        return ITEM_GEOMETRY_FROM_LEGACY_RENDERER.get();
    }

    /**
     * The block-item-in-slot rule (laneInv-progress.md): a {@code BlockItem}'s held/inventory geometry
     * should be a small, correctly-proportioned version of its (possibly many-block) in-world shape, not
     * the raw OBJ file's own aspect ratio, which need bear no relation to it (the world shape comes from
     * authored coordinates x the TESR/ISBRH's own transform - see {@code dev.umb.objbridge.transform} -
     * while a plain auto-fit only ever looks at the raw, untransformed mesh). So: resolve this
     * (model,sprite) pair back to the block's own WORLD-path class exactly the way {@link #spliceBlocks}
     * does, ask {@link RenderFit} for that class's {@code PathClass.WORLD} linear map, and bake with
     * {@link Fit#itemFromWorldTransform} (proportions from the transform, absolute size normalised to
     * {@code size} like ordinary auto-fit) instead of {@code new Fit(false, size)}.
     *
     * <p>For an item with a resolved legacy IItemRenderer, preserve the model's authored 1/16 block
     * units. Its captured per-perspective GL scale is applied by {@link
     * dev.umb.objbridge.item.ObjTransforms}; normalising here would erase the absolute scale supplied
     * by the 1.7.10 renderer. Blocks retain the WORLD-path rule above.
     *
     * <p>Falls back to plain raw-mesh auto-fit for items without a resolved legacy renderer, and for a
     * block whose WORLD-path class is not present in {@code renderer-transforms.json}.
     */
    public static Fit itemGeometryFit(String modelPath, String spriteId, float size) {
        String worldClass = blockWorldClassIndex().get(itemKey(modelPath, spriteId));
        if (worldClass != null) {
            RenderFit.Outcome outcome = RenderFit.forPath(rendererTransforms(), worldClass,
                    PathClass.WORLD, false, Fit.DEFAULT_MAX_EXTENT);
            if (outcome.resolved()) {
                ITEM_GEOMETRY_FROM_WORLD.incrementAndGet();
                return Fit.itemFromWorldTransform(outcome.fit().linear(), size);
            }
        }
        String rendererClass = rendererClassForItem(modelPath, spriteId);
        if (rendererClass != null && rendererTransforms().hasClass(rendererClass)) {
            ITEM_GEOMETRY_FROM_LEGACY_RENDERER.incrementAndGet();
            return Fit.itemFromLegacyModelUnits(size);
        }
        ITEM_GEOMETRY_RAW_AUTOFIT.incrementAndGet();
        return new Fit(false, size);
    }
}
