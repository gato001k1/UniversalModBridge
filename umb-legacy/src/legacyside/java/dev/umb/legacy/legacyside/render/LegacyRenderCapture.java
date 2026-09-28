package dev.umb.legacy.legacyside.render;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.legacy.legacyside.LegacyClientFacade;
import dev.umb.legacy.legacyside.UmbItemConv;
import dev.umb.bridge.api.GlEmulationSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.IModelCustom;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.IItemRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic legacy client Render runner. The Render is real mod code; only its GL/Tessellator and
 * texture side effects are redirected into the thread-local capture buffer.
 */
public final class LegacyRenderCapture {
    private static final int GL_CULL_FACE = 2884;
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_BLEND = 3042;
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<Capture>();
    private static final ThreadLocal<Minecraft> ACTIVE_MINECRAFT = new ThreadLocal<Minecraft>();
    private static final Object LOCK = new Object();
    private static RenderManager manager;
    private static boolean clientRegistered;
    private static boolean clientResourcesInstalled;
    private static Object installedResourceManager;
    private static Object installedResourceRepository;
    private static Minecraft installedFacade;
    /** One shared LRU for every capture result; the old per-map ConcurrentHashMaps had no byte bound. */
    private static final LinkedHashMap<String, EntityRenderCapture> CAPTURE_CACHE =
            new LinkedHashMap<String, EntityRenderCapture>(128, 0.75f, true);
    /** Compact dynamic probes; unlike captures these retain no vertex arrays. */
    private static final LinkedHashMap<String, DynamicProbe> DYNAMIC_PROBES =
            new LinkedHashMap<String, DynamicProbe>(128, 0.75f, true);
    private static long dynamicProbeClock;
    private static final int DYNAMIC_PROBATION_FRAMES = 6;
    private static final long DYNAMIC_REPROBE_INTERVAL = 120L;
    private static final int MAX_DYNAMIC_PROBES = 2048;
    private static final Map<Class<?>, Field[]> STATE_FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();
    private static final Map<Class<?>, Method[]> INFO_GETTERS = new ConcurrentHashMap<Class<?>, Method[]>();
    private static final Map<Class<?>, Field[]> INFO_FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();
    private static long captureCacheBytes;
    private static long lastCacheDiagnosticNanos;
    /**
     * Completed static captures. The key includes only render-relevant state fields, never the
     * entity's world position or vanilla age/transform fields: host pose submission owns those.
     * Animated entities never enter this map, so a changing rotor/prop/turret/throttle state
     * remains a real per-frame capture.
     */
    /** Local-space mesh cache. Matrix-only captures reuse this geometry across animation frames. */
    /** Transform captures are keyed by the live render state; unchanged state never re-enters mod code. */
    private static final Map<String, Long> FAILURE_LOG_NANOS = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> DIAGNOSTIC_LOG_NANOS = new ConcurrentHashMap<String, Long>();
    private static final Map<String, String> LAST_DIAGNOSTIC = new ConcurrentHashMap<String, String>();
    private static final Map<String, Boolean> MINECRAFT_VIEW_DIAGNOSTICS =
            new ConcurrentHashMap<String, Boolean>();
    private static long transformCacheHits;
    private static long transformCacheMisses;
    private static final Set<Entity> WARMED_RENDER_STATE =
            Collections.newSetFromMap(new java.util.WeakHashMap<Entity, Boolean>());
    private static final long FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final Object HOOK_STDERR_LOCK = new Object();
    /** Half of the configured universal budget is reserved for this class-loader side. */
    private static final long DEFAULT_CAPTURE_CACHE_BYTES = 128L * 1024L * 1024L;
    private static final long MIN_CAPTURE_CACHE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_CAPTURE_CACHE_BYTES = 1024L * 1024L * 1024L;
    /** A zero-geometry/diagnostic result still has object/list overhead, so bytes alone are not enough. */
    private static final int MAX_CAPTURE_CACHE_ENTRIES = 2048;

    private LegacyRenderCapture() {}

    /**
     * Installs the native-free Minecraft singleton before FML constructs and pre-initializes
     * client-side mods.  This is deliberately limited to the facade and vanilla resource pack:
     * client proxy discovery remains in {@link #prepareClientUniverse(Binding)} so registrations
     * are captured once through the existing renderer/discovery boundary after mod containers
     * exist.
     */
    public static LegacyClientFacade.Binding installPreInitClientFacade() throws Exception {
        synchronized (LOCK) {
            LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
            // Loader.loadMods() has already run, so installResourcePacks mounts the vanilla pack
            // plus every mod source before a client proxy's PREINIT static code can resolve it.
            installResourcePacks(binding.minecraft);
            bindFacadeRenderServices(binding);
            System.out.println("[UMB-LEGACY] preinit client facade installed singleton="
                    + binding.minecraft.getClass().getName() + " resources="
                    + installedResourceManager.getClass().getName());
            return binding;
        }
    }

    /** The budget is shared by static, mesh, transform, and comparison entries. */
    private static long captureCacheBudgetBytes() {
        String property = System.getProperty("umb.p4.captureCacheBytes", "");
        try {
            if (!property.isEmpty()) return clampCacheBudget(Long.parseLong(property) / 2L);
        } catch (Throwable ignored) { }
        try {
            Class<?> settings = Class.forName("dev.umb.hostagent.content.UmbSettings");
            Method getter = settings.getMethod("captureCacheBytes");
            return clampCacheBudget(((Number) getter.invoke(null)).longValue() / 2L);
        } catch (Throwable ignored) { }
        return DEFAULT_CAPTURE_CACHE_BYTES;
    }

    private static long clampCacheBudget(long value) {
        return Math.max(MIN_CAPTURE_CACHE_BYTES, Math.min(MAX_CAPTURE_CACHE_BYTES, value));
    }

    private static EntityRenderCapture cacheGet(String kind, String key) {
        return CAPTURE_CACHE.get(kind + '\u0000' + key);
    }

    private static void cachePut(String kind, String key, EntityRenderCapture value) {
        if (value == null) return;
        String cacheKey = kind + '\u0000' + key;
        EntityRenderCapture old = CAPTURE_CACHE.remove(cacheKey);
        if (old != null) captureCacheBytes -= captureBytes(old);
        long size = captureBytes(value);
        if (size > captureCacheBudgetBytes()) return;
        CAPTURE_CACHE.put(cacheKey, value);
        captureCacheBytes += size;
        long budget = captureCacheBudgetBytes();
        java.util.Iterator<Map.Entry<String, EntityRenderCapture>> it = CAPTURE_CACHE.entrySet().iterator();
        while ((captureCacheBytes > budget || CAPTURE_CACHE.size() > MAX_CAPTURE_CACHE_ENTRIES)
                && it.hasNext()) {
            Map.Entry<String, EntityRenderCapture> eldest = it.next();
            captureCacheBytes -= captureBytes(eldest.getValue());
            it.remove();
        }
        logCacheBudget();
    }

    private static long captureBytes(EntityRenderCapture capture) {
        long bytes = 0L;
        if (capture == null) return bytes;
        for (EntityRenderCapture.Draw draw : capture.draws) {
            if (draw == null) continue;
            if (draw.vertices != null) bytes += 4L * draw.vertices.length;
            if (draw.matrix != null) bytes += 4L * draw.matrix.length;
        }
        return bytes;
    }

    /** Test/telemetry hook: float-array payload bytes retained by the legacy capture cache. */
    public static long captureCacheBytes() { synchronized (LOCK) { return captureCacheBytes; } }

    /** Test/telemetry hook: number of retained results, including zero-geometry results. */
    public static int captureCacheEntries() { synchronized (LOCK) { return CAPTURE_CACHE.size(); } }

    private static void logCacheBudget() {
        long now = System.nanoTime();
        if (now - lastCacheDiagnosticNanos < FAILURE_LOG_INTERVAL_NANOS) return;
        lastCacheDiagnosticNanos = now;
        System.out.println("[CAPTURE-CACHE] scope=legacy bytes=" + captureCacheBytes
                + " entries=" + CAPTURE_CACHE.size()
                + " budget=" + captureCacheBudgetBytes()
                + " entry_cap=" + MAX_CAPTURE_CACHE_ENTRIES);
    }

    /**
     * Drop all legacy-side captures derived from an entity class when its authoritative entity is
     * removed.  Captures are intentionally shared by class/model state, so removing one instance
     * invalidates the class slot rather than retaining a stale renderer state indefinitely.
     */
    public static void invalidateEntityCaptures(Entity entity) {
        if (entity != null) invalidateCapturePrefix(entity.getClass().getName() + "|");
    }

    /** Invalidate by the legacy renderer namespace used in capture/cache keys. */
    public static void invalidateEntityCaptures(String legacyClass) {
        if (legacyClass != null && !legacyClass.isEmpty()) invalidateCapturePrefix(legacyClass + "|");
    }

    /** Drop captures derived from a removed tile class. */
    public static void invalidateTileCaptures(net.minecraft.tileentity.TileEntity tile) {
        if (tile != null) invalidateCapturePrefix(tile.getClass().getName() + "|");
    }

    private static void invalidateCapturePrefix(String prefix) {
        synchronized (LOCK) {
            java.util.Iterator<Map.Entry<String, EntityRenderCapture>> it =
                    CAPTURE_CACHE.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, EntityRenderCapture> entry = it.next();
                EntityRenderCapture value = entry.getValue();
                String entityClass = value == null ? "" : value.entityClass;
                String key = entry.getKey();
                if ((entityClass != null && entityClass.startsWith(prefix))
                        || key.indexOf('\u0000' + prefix) >= 0
                        || key.endsWith("\u0000" + prefix.substring(0, prefix.length() - 1))) {
                    captureCacheBytes -= captureBytes(value);
                    it.remove();
                }
            }
            if (captureCacheBytes < 0L) captureCacheBytes = 0L;
            java.util.Iterator<Map.Entry<String, DynamicProbe>> probes = DYNAMIC_PROBES.entrySet().iterator();
            while (probes.hasNext()) {
                if (probes.next().getKey().startsWith(prefix)) probes.remove();
            }
        }
    }

    /** Test hook; live callers never need to flush the cache manually. */
    public static void clearCaptureCacheForTests() {
        synchronized (LOCK) {
            CAPTURE_CACHE.clear();
            DYNAMIC_PROBES.clear();
            dynamicProbeClock = 0L;
            captureCacheBytes = 0L;
        }
    }

    static void cacheCaptureForTests(String key, EntityRenderCapture value) {
        synchronized (LOCK) { cachePut("test", key, value); }
    }

    private static int quantizedPartialBucket(float partialTick) {
        if (!Float.isFinite(partialTick)) return 0;
        float clamped = Math.max(0.0f, Math.min(1.0f, partialTick));
        return Math.round(clamped * 4.0f);
    }

    public static EntityRenderCapture capture(Entity entity, float partialTick) {
        return capture(entity, partialTick, false);
    }

    /** Re-evaluates only the legacy transform/draw boundaries against an already cached mesh. */
    public static EntityRenderCapture captureTransform(Entity entity, float partialTick) {
        return capture(entity, partialTick, true);
    }

    /**
     * item have a real, registered {@code IItemRenderer} for this render type at all, without
     * running it. This is what {@code HeldItemModel} (objbridge) must gate on: the host cannot
     * know statically which items have a custom renderer (a static per-mod sidecar necessarily
     * only covers what a build-time census could resolve; MCHeli's own hand-coded Java model
     * renderers, for one concrete example, have zero recoverable OBJ geometry and were never in
     * any such sidecar, yet are exactly the kind of renderer this whole capture path exists to
     * replay). No cache/GL-EMU session is touched; this is a plain registry lookup.
     */
    public static boolean hasItemRenderer(String legacyId, int damage, String renderTypeName) {
        if (legacyId == null || renderTypeName == null) return false;
        try {
            IItemRenderer.ItemRenderType renderType = IItemRenderer.ItemRenderType.valueOf(renderTypeName);
            net.minecraft.item.ItemStack stack = UmbItemConv.toLegacy(
                    new dev.umb.bridge.api.StackData(legacyId, 1, damage, null));
            if (stack == null) {
                logRendererCheckOnce(legacyId, renderTypeName, "stack-null");
                return false;
            }
            IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stack, renderType);
            boolean handled = renderer != null && renderer.handleRenderType(stack, renderType);
            logRendererCheckOnce(legacyId, renderTypeName, renderer == null ? "no-renderer"
                    : renderer.getClass().getName() + " handled=" + handled);
            return handled;
        } catch (Throwable t) {
            logRendererCheckOnce(legacyId, renderTypeName, "threw " + t);
            return false;
        }
    }

    private static net.minecraft.client.renderer.RenderBlocks itemRenderBlocks;

    /** One shared RenderBlocks for item renderers, like vanilla ItemRenderer keeps its own. */
    private static net.minecraft.client.renderer.RenderBlocks itemRenderBlocks() {
        if (itemRenderBlocks == null) itemRenderBlocks = new net.minecraft.client.renderer.RenderBlocks();
        return itemRenderBlocks;
    }

    private static final java.util.Set<String> RENDERER_CHECK_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** One line per item id + render type, so a held item that stays flat shows why. */
    private static void logRendererCheckOnce(String legacyId, String renderTypeName, String outcome) {
        if (RENDERER_CHECK_LOGGED.add(legacyId + "|" + renderTypeName)) {
            System.out.println("[UMB-LEGACY] item renderer check id=" + legacyId + " type="
                    + renderTypeName + " -> " + outcome);
        }
    }

    /**
     * Runs the registered Forge IItemRenderer through this same GL/Tessellator capture boundary.
     * The cache key is exactly legacy item id + damage + ItemRenderType; transform-only callers
     * reuse the sealed mesh and re-enter the renderer for current matrices/cull state. No native
     * GL is executed. The render-type names are the verified 1.7.10 enum constants.
     */
    public static EntityRenderCapture captureItem(String legacyId, int count, int damage,
                                                   byte[] nbt, String renderTypeName,
                                                   float partialTick, boolean transformOnly) {
        if (legacyId == null || renderTypeName == null) return EntityRenderCapture.empty("", "item-null");
        synchronized (LOCK) {
            String key = legacyId + "|" + damage + "|" + renderTypeName;
            try {
                IItemRenderer.ItemRenderType renderType = IItemRenderer.ItemRenderType.valueOf(renderTypeName);
                net.minecraft.item.ItemStack stack = UmbItemConv.toLegacy(
                        new dev.umb.bridge.api.StackData(legacyId, Math.max(1, count), damage, nbt));
                if (stack == null) return EntityRenderCapture.empty("item:" + legacyId, key);
                IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stack, renderType);
                if (renderer == null || !renderer.handleRenderType(stack, renderType))
                    return EntityRenderCapture.empty("item:" + legacyId, key);
                LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
                ensureManager(binding, binding.world);
                ensureClientVisuals(binding);
                bindResourceManager(renderer.getClass().getClassLoader());
                Capture c = new Capture("item:" + legacyId, key,
                        "item|" + key, "item|" + key + "|partial=" + quantizedPartialBucket(partialTick),
                        "item|" + key, !transformOnly && cacheGet("mesh", "item|" + key) == null);
                c.enable(GL_CULL_FACE);
                ACTIVE.set(c);
                ACTIVE_MINECRAFT.set(binding.minecraft);
                // Forge 1.7.10 passes a RenderBlocks first: ENTITY (rb, EntityItem), EQUIPPED and
                // EQUIPPED_FIRST_PERSON (rb, holder), INVENTORY (rb). Renderers index into this.
                net.minecraft.client.renderer.RenderBlocks renderBlocks = itemRenderBlocks();
                Object[] args;
                if (renderType == IItemRenderer.ItemRenderType.ENTITY)
                    args = new Object[] {renderBlocks, new EntityItem(binding.world, 0.0D, 0.0D, 0.0D, stack)};
                else if (renderType == IItemRenderer.ItemRenderType.INVENTORY)
                    args = new Object[] {renderBlocks};
                else
                    args = new Object[] {renderBlocks, binding.player};
                renderer.renderItem(renderType, stack, args);
                c.finish();
                return c.result();
            } catch (Throwable failure) {
                logRateLimited(FAILURE_LOG_NANOS, "item-capture:" + key,
                        "[UMB-LEGACY] item render skipped key=" + key + " reason=" + reason(failure));
                return EntityRenderCapture.empty("item:" + legacyId, key);
            } finally {
                ACTIVE_MINECRAFT.remove();
                ACTIVE.remove();
            }
        }
    }

    /** Runs the legacy TileEntitySpecialRenderer selected by the live dispatcher through the same
     * emulation session used for entities. The tile remains the authoritative object; only its
     * temporary client-world view and the dispatcher context are rebound for the callback. */
    public static EntityRenderCapture captureTile(net.minecraft.tileentity.TileEntity tile,
                                                  float partialTick) {
        if (tile == null) return EntityRenderCapture.empty("", "tile-null");
        synchronized (LOCK) {
            net.minecraft.world.World originalWorld = null;
            net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher dispatcher = null;
            net.minecraft.world.World originalDispatcherWorld = null;
            try {
                originalWorld = (net.minecraft.world.World) field(net.minecraft.tileentity.TileEntity.class,
                        "field_145850_b").get(tile);
                EntityPlayer player = null;
                if (originalWorld != null && originalWorld.field_73010_i != null
                        && !originalWorld.field_73010_i.isEmpty()) {
                    player = (EntityPlayer) originalWorld.field_73010_i.get(0);
                }
                LegacyClientFacade.Binding binding = LegacyClientFacade.install(player, originalWorld);
                ensureManager(binding, originalWorld);
                ensureClientVisuals(binding);
                dispatcher = net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.field_147556_a;
                if (dispatcher == null) {
                    logRateLimited(DIAGNOSTIC_LOG_NANOS, "tile-dispatcher-null",
                            "[UMB-LEGACY] tile render skipped reason=dispatcher-null");
                    return EntityRenderCapture.empty(tile.getClass().getName(), "dispatcher-null");
                }
                net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer renderer =
                        dispatcher.func_147547_b(tile);
                if (renderer == null) {
                    logRateLimited(DIAGNOSTIC_LOG_NANOS, "tile-renderer-null:" + tile.getClass().getName(),
                            "[UMB-LEGACY] tile render skipped class=" + tile.getClass().getName()
                                    + " reason=renderer-null");
                    return EntityRenderCapture.empty(tile.getClass().getName(), "renderer-null");
                }
                bindResourceManager(renderer.getClass().getClassLoader());
                originalDispatcherWorld = dispatcher.field_147550_f;
                dispatcher.field_147550_f = binding.world;
                // RenderingRegistry normally injects this back-reference while the client
                // dispatcher is constructed.  The native-free client universe can receive a
                // renderer from a mod hook after that construction, so bind it explicitly before
                // any TESR calls.  Without this, TileEntitySpecialRenderer.func_147499_a reads
                // a null dispatcher/texture manager and the whole capture is discarded.
                try { renderer.func_147497_a(dispatcher); } catch (Throwable ignored) { }
                try { renderer.func_147496_a(binding.world); } catch (Throwable ignored) { }
                set(net.minecraft.tileentity.TileEntity.class, tile, "field_145850_b", binding.world);
                dispatcher.func_147542_a(binding.world, textureManager(), null, binding.player, partialTick);
                String cls = tile.getClass().getName();
                String key = cls + "@" + tile.field_145851_c + "," + tile.field_145848_d + "," + tile.field_145849_e;
                Capture c = new Capture(cls, key, cls, cls, cls, true);
                ACTIVE.set(c);
                ACTIVE_MINECRAFT.set(binding.minecraft);
                renderer.func_147500_a(tile, 0.0D, 0.0D, 0.0D, partialTick);
                c.finish();
                EntityRenderCapture result = c.result();
                if (result.vertexCount() == 0) {
                    logRateLimited(DIAGNOSTIC_LOG_NANOS, "tile-empty:" + cls,
                            "[UMB-LEGACY] tile render empty class=" + cls + " reason=renderer-returned-no-geometry");
                }
                return result;
            } catch (Throwable failure) {
                logRateLimited(FAILURE_LOG_NANOS, "tile-failure:" + tile.getClass().getName(),
                        "[UMB-LEGACY] tile render skipped class=" + tile.getClass().getName()
                                + " reason=" + reason(failure));
                return EntityRenderCapture.empty(tile.getClass().getName(), "tile-render-failed");
            } finally {
                try { if (dispatcher != null) dispatcher.field_147550_f = originalDispatcherWorld; } catch (Throwable ignored) { }
                try { field(net.minecraft.tileentity.TileEntity.class, "field_145850_b").set(tile, originalWorld); } catch (Throwable ignored) { }
                ACTIVE_MINECRAFT.remove();
                ACTIVE.remove();
            }
        }
    }

    private static EntityRenderCapture capture(Entity entity, float partialTick, boolean transformOnly) {
        if (entity == null) return EntityRenderCapture.empty("", "");
        synchronized (LOCK) {
            try {
                String entityClass = entity.getClass().getName();
                String stateKey = entityId(entity);
                boolean baselineSeeded = seedEntityRenderState(entity);
                // Cache ownership is class + renderer/model state, never the individual entity
                // id. Ten identical vehicles on screen must share one captured mesh/transform.
                String staticState = renderState(entity, false);
                String dynamicState = renderState(entity, true);
                boolean dynamic = dynamicState.length() != staticState.length();
                String modelState = entity.getClass().getName() + "|" + staticState;
                // Dynamic values are render inputs, not identities. Keep one replaceable slot per
                // renderer/model and let the live callback observe the current rotor/throttle/etc.
                // This is deliberately not a frame/pose key: a 20-minute animation cannot grow a
                // cache entry per tick. Static variants retain their model-state identity.
                String probeKey = modelState;
                long probeFrame = ++dynamicProbeClock;
                boolean movingOrRidden = entityMovingOrRidden(entity);
                boolean periodicProbe = dynamicProbeDue(probeKey, probeFrame);
                // Name tokens remain a cheap early signal, but they are no longer the correctness
                // decision. Unknown mutable fields get a bounded probation capture and periodic
                // full-geometry re-probe; motion/riding always stays on the live path.
                boolean probeSample = periodicProbe || dynamicProbeInProbation(probeKey);
                dynamic = dynamic || movingOrRidden || periodicProbe || probeSample
                        || dynamicProbeMarkedDynamic(probeKey);
                String cacheKey = dynamic
                        ? entityClass + "|dynamic|" + modelState
                        : modelState;
                // Raw partialTicks are a per-frame value. Keep only a small interpolation bucket;
                // the live entity state remains the authoritative animation key.
                String transformKey = cacheKey + (dynamic
                        ? "|partialBucket=" + quantizedPartialBucket(partialTick) : "");
                if (!transformOnly && !dynamic) {
                    EntityRenderCapture staticCapture = cacheGet("static", cacheKey);
                    if (staticCapture != null) return staticCapture;
                }
                String meshKey = modelState;
                if (transformOnly) {
                    EntityRenderCapture transform = cacheGet("transform", transformKey);
                    if (transform != null) {
                        transformCacheHits++;
                        return transform;
                    }
                    transformCacheMisses++;
                    if (!dynamic) {
                        EntityRenderCapture mesh = cacheGet("mesh", meshKey);
                        if (mesh != null) {
                            cachePut("transform", transformKey, mesh);
                            return mesh;
                        }
                    }
                }
                LegacyClientFacade.Binding binding = ensureClient(entity);
                // Expose the stable model-state key to the host renderer. The entity id belongs
                // only in diagnostics; using it here would create one host cache entry per twin.
                boolean fullGeometryProbe = probeSample && !transformOnly;
                Capture c = new Capture(entityClass, cacheKey, cacheKey, transformKey, meshKey,
                        !transformOnly && (cacheGet("mesh", meshKey) == null || fullGeometryProbe));
                // 1.7.10 EntityRenderer.renderWorld enables GL_CULL_FACE before the entity pass;
                // renderers that want double-sided faces (RendererLivingEntity, most mods'
                // flat parts) disable it themselves, which the emulation records per draw.
                c.enable(GL_CULL_FACE);
                c.probeKey = probeKey;
                c.probeSample = probeSample;
                c.probeFrame = probeFrame;
                ACTIVE.set(c);
                ACTIVE_MINECRAFT.set(binding.minecraft);
                // RenderManager.func_147938_a is the verified context setter used by the legacy
                // dispatcher; doRender itself is then invoked with zero-relative coordinates.
                net.minecraft.world.World originalWorld = entity.field_70170_p;
                Object originalManagerWorld = manager.field_78722_g;
                LegacyClientFacade.setWorld(entity, binding.world);
                try {
                    // Renderer lookup is part of the client boundary too: a few legacy
                    // RenderManager implementations consult the current world/facade while
                    // resolving their class map. The authoritative entity must never be handed
                    // to that lookup with the server-only world still attached.
                    manager.field_78722_g = binding.world;
                    Render renderer = manager.func_78713_a(entity);
                    if (renderer == null) {
                        logEmptyCapture(entity, null, "renderer-null");
                        return EntityRenderCapture.empty(entityClass, stateKey);
                    }
                    // A proxy can retain the client class loaded by its own mod loader. Rebind
                    // that view immediately before rendering as well as before registration;
                    // client-universe dispatch may have replaced the facade singleton in between.
                    bindResourceManager(renderer.getClass().getClassLoader());
                    // MCHeli's first-person branch reads Minecraft.field_71474_y directly from
                    // its own client class.  A loader-local facade can retain a null settings
                    // field even after the normal binding; restore the exact settings object
                    // passed to RenderManager before doRender so the mod's riding/pilot logic
                    // decides visibility and offsets exactly as it does in legacy Minecraft.
                    ensureGameSettings(binding);
                    RenderCameraScope cameraScope = new RenderCameraScope(binding.minecraft, manager);
                    manager.func_147938_a(binding.world, textureManager(), null, binding.player, entity,
                            binding.gameSettings, partialTick);
                    cameraScope.install(binding.player, entity, partialTick, baselineSeeded);
                    try {
                        renderer.func_76986_a(entity, 0.0D, 0.0D, 0.0D, entity.field_70177_z, partialTick);
                        c.finish();
                    } finally {
                        cameraScope.restore();
                    }
                EntityRenderCapture result = c.result();
                if (probeSample) noteDynamicProbe(probeKey, probeFrame, result.animated);
                    if (result.vertexCount() == 0) {
                        warmMissingRenderState(entity);
                        logEmptyCapture(entity, renderer, "renderer-returned-no-geometry");
                    }
                        return result;
                } finally {
                    manager.field_78722_g = (net.minecraft.world.World) originalManagerWorld;
                    LegacyClientFacade.setWorld(entity, originalWorld);
                }
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null ? t.getCause() : t;
                logFailure(entity, cause);
                return EntityRenderCapture.empty(entity.getClass().getName(), entityId(entity));
            } finally {
                ACTIVE_MINECRAFT.remove();
                ACTIVE.remove();
            }
        }
    }

    /**
     * Makes a capture look like the normal RenderManager path without borrowing the host
     * camera.  MCHeli and other legacy renderers legitimately inspect Minecraft.field_71451_h
     * and RenderManager.renderPosX/Y/Z while positioning riders and camera-relative parts.  The
     * old capture passed the facade player to cacheActiveRenderInfo, then called doRender with
     * zero-relative coordinates while leaving a stale MCH_ViewEntityDummy or player-origin in
     * those fields.  After one ride that turned the vehicle mesh into a camera-following mesh.
     *
     * This scope is deliberately type/field based: it applies to every legacy entity renderer,
     * preserves the mod's own rider checks, and restores the facade exactly after the callback.
     */
    private static final class RenderCameraScope {
        private final Minecraft minecraft;
        private final RenderManager manager;
        private final Object previousView;
        private final Object[] previousPositions = new Object[3];
        private boolean installed;

        RenderCameraScope(Minecraft minecraft, RenderManager manager) {
            this.minecraft = minecraft;
            this.manager = manager;
            this.previousView = readOptional(Minecraft.class, minecraft, "field_71451_h");
            previousPositions[0] = readOptional(RenderManager.class, manager, "field_78725_b");
            previousPositions[1] = readOptional(RenderManager.class, manager, "field_78726_c");
            previousPositions[2] = readOptional(RenderManager.class, manager, "field_78723_d");
        }

        void install(EntityPlayer player, Entity entity, float partialTick, boolean baselineSeeded) {
            if (minecraft == null || manager == null || entity == null) return;
            try {
                // A stale MCH_ViewEntityDummy is a valid legacy gameplay camera, but it is not
                // the view entity for an isolated entity capture.  The facade player preserves
                // first-person/rider decisions without importing the host camera helper.
                if (player != null) setOptional(Minecraft.class, minecraft, "field_71451_h", player);
                double x = interpolated(entity.field_70142_S, entity.field_70165_t, partialTick);
                double y = interpolated(entity.field_70137_T, entity.field_70163_u, partialTick);
                double z = interpolated(entity.field_70136_U, entity.field_70161_v, partialTick);
                setOptional(RenderManager.class, manager, "field_78725_b", Double.valueOf(x));
                setOptional(RenderManager.class, manager, "field_78726_c", Double.valueOf(y));
                setOptional(RenderManager.class, manager, "field_78723_d", Double.valueOf(z));
                installed = true;
                logCameraDiagnostic(entity, previousView, x, y, z, baselineSeeded);
            } catch (Throwable failure) {
                logRateLimited(DIAGNOSTIC_LOG_NANOS, "camera-scope:" + entity.getClass().getName(),
                        "[CAPTURE-VIEW] entity=" + entity.getClass().getName()
                                + " status=install-failed reason=" + reason(failure));
            }
        }

        void restore() {
            if (!installed) return;
            try { setOptional(Minecraft.class, minecraft, "field_71451_h", previousView); }
            catch (Throwable ignored) { }
            try { setOptional(RenderManager.class, manager, "field_78725_b", previousPositions[0]); }
            catch (Throwable ignored) { }
            try { setOptional(RenderManager.class, manager, "field_78726_c", previousPositions[1]); }
            catch (Throwable ignored) { }
            try { setOptional(RenderManager.class, manager, "field_78723_d", previousPositions[2]); }
            catch (Throwable ignored) { }
            installed = false;
        }
    }

    private static double interpolated(double previous, double current, float partialTick) {
        double p = Float.isFinite(partialTick) ? Math.max(0.0D, Math.min(1.0D, partialTick)) : 0.0D;
        return previous + (current - previous) * p;
    }

    /**
     * Entities assembled from host state can have a valid current position but zero vanilla
     * interpolation baselines because no legacy tick initialized them. Seed only that unmistakable
     * state; legitimate motion histories, including a real origin, remain untouched.
     */
    private static boolean seedEntityRenderState(Entity entity) {
        if (entity == null) return false;
        double x = entity.field_70165_t, y = entity.field_70163_u, z = entity.field_70161_v;
        boolean hasPosition = x != 0.0D || y != 0.0D || z != 0.0D;
        boolean lastTickMissing = entity.field_70142_S == 0.0D
                && entity.field_70137_T == 0.0D && entity.field_70136_U == 0.0D;
        boolean previousMissing = entity.field_70169_q == 0.0D
                && entity.field_70167_r == 0.0D && entity.field_70166_s == 0.0D;
        boolean seeded = false;
        if (hasPosition && lastTickMissing) {
            entity.field_70142_S = x;
            entity.field_70137_T = y;
            entity.field_70136_U = z;
            seeded = true;
        }
        if (hasPosition && previousMissing) {
            entity.field_70169_q = x;
            entity.field_70167_r = y;
            entity.field_70166_s = z;
            seeded = true;
        }
        if (seeded) {
            entity.field_70126_B = entity.field_70177_z;
            entity.field_70127_C = entity.field_70125_A;
        }
        return seeded;
    }

    private static Object readOptional(Class<?> owner, Object target, String name) {
        try { return field(owner, name).get(target); }
        catch (Throwable ignored) { return null; }
    }

    private static void setOptional(Class<?> owner, Object target, String name, Object value) {
        try {
            Field f = field(owner, name);
            if (value == null || !f.getType().isPrimitive()) {
                f.set(target, value);
            } else if (f.getType() == Double.TYPE) {
                f.setDouble(target, ((Number) value).doubleValue());
            } else if (f.getType() == Float.TYPE) {
                f.setFloat(target, ((Number) value).floatValue());
            }
        } catch (Throwable ignored) {
            // Mappings vary across the supported legacy client jars; absent camera fields are
            // harmless because the renderer still runs inside the isolated emulation scope.
        }
    }

    private static void logCameraDiagnostic(Entity entity, Object previousView,
                                            double x, double y, double z,
                                            boolean baselineSeeded) {
        String key = entity.getClass().getName();
        Object currentView = readOptional(Minecraft.class, ACTIVE_MINECRAFT.get(), "field_71451_h");
        Object managerX = readOptional(RenderManager.class, manager, "field_78725_b");
        Object managerY = readOptional(RenderManager.class, manager, "field_78726_c");
        Object managerZ = readOptional(RenderManager.class, manager, "field_78723_d");
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "camera-state:" + key,
                "[CAPTURE-VIEW] entity=" + key
                        + " previousView=" + objectText(previousView)
                        + " renderView=" + objectText(currentView)
                        + " renderPosTarget=" + x + "," + y + "," + z
                        + " managerRenderPos=" + String.valueOf(managerX) + ","
                        + String.valueOf(managerY) + "," + String.valueOf(managerZ)
                        + " baselineSeeded=" + baselineSeeded
                        + " rider=" + (entity.field_70154_o == null ? "none" : "present"));
    }

    private static void logFailure(Entity entity, Throwable cause) {
        String key = entity == null ? "<null>" : entity.getClass().getName();
        logRateLimited(FAILURE_LOG_NANOS, key,
                "[UMB-LEGACY] entity render skipped for " + key + ": " + reason(cause)
                        + " stack=" + stackSummary(cause));
    }

    private static void logEmptyCapture(Entity entity, Render renderer, String phase) {
        String key = entity == null ? "<null>" : entity.getClass().getName();
        StringBuilder detail = new StringBuilder(phase);
        if (renderer != null) detail.append(" renderer=").append(renderer.getClass().getName());
        String info = inspectInfoGetters(entity);
        if (info.length() > 0) detail.append(" info=").append(info);
        String models = inspectInfoModels(entity);
        if (models.length() > 0) detail.append(" models=").append(models);
        detail.append(" ").append(renderWorldSummary(entity));
        LAST_DIAGNOSTIC.put(key, detail.toString());
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "empty:" + key,
                "[UMB-LEGACY] entity render empty entity=" + key + " " + detail);
    }

    /** Last bounded diagnostic for a class, used by the headless sweep classifier. */
    public static String lastDiagnostic(String entityClass) {
        String value = LAST_DIAGNOSTIC.get(entityClass);
        return value == null ? "" : value;
    }

    public static long transformCacheHits() { return transformCacheHits; }
    public static long transformCacheMisses() { return transformCacheMisses; }

    /** Called by the generic client-proxy bytecode guard when one renderer constructor or
     * registration touches unavailable native client state. The remaining registrations continue
     * and the cause is visible without naming the originating mod. */
    public static void rendererRegistrationFailure(Throwable failure) {
        String detail = reason(failure);
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "renderer-registration:" + detail,
                "[UMB-LEGACY] client renderer registration skipped reason=" + detail);
    }

    /** Records the exact worlds visible at the renderer boundary; diagnostics never affect it. */
    private static String renderWorldSummary(Entity entity) {
        Object entityWorld = entity == null ? null : entity.field_70170_p;
        Object facadeWorld = null;
        try {
            Minecraft minecraft = ACTIVE_MINECRAFT.get();
            facadeWorld = minecraft == null ? null : minecraft.field_71441_e;
        } catch (Throwable ignored) {
            // Fail-closed diagnostics.
        }
        Object managerWorld = null;
        try {
            managerWorld = manager == null ? null : manager.field_78722_g;
        } catch (Throwable ignored) {
            // Fail-closed diagnostics.
        }
        return "worlds=entity:" + worldText(entityWorld)
                + ",facade:" + worldText(facadeWorld)
                + ",manager:" + worldText(managerWorld)
                + " sameEntityFacade=" + (entityWorld != null && entityWorld == facadeWorld)
                + " sameFacadeManager=" + (facadeWorld != null && facadeWorld == managerWorld);
    }

    private static String worldText(Object world) {
        if (world == null) return "null";
        String remote = "?";
        if (world instanceof net.minecraft.world.World) {
            try {
                remote = Boolean.toString(((net.minecraft.world.World) world).field_72995_K);
            } catch (Throwable ignored) {
                remote = "?";
            }
        }
        return world.getClass().getName() + "@" + System.identityHashCode(world) + "/remote=" + remote;
    }

    /**
     * Some legacy entities normally populate their renderer-facing info from their first update.
     * Run only the entity's explicit aircraft-state hook, once per object, when reflective info
     * getters show that state is missing. This does not invoke the general movement tick.
     */
    private static void warmMissingRenderState(Entity entity) {
        String missing = inspectInfoGetters(entity);
        if (missing.length() == 0) return;
        synchronized (WARMED_RENDER_STATE) {
            if (!WARMED_RENDER_STATE.add(entity)) return;
        }
        Method updater = noArgMethod(entity.getClass(), "onUpdateAircraft");
        if (updater == null) {
            logRateLimited(DIAGNOSTIC_LOG_NANOS, "warmup:" + entity.getClass().getName(),
                    "[UMB-LEGACY] entity render state unavailable entity="
                            + entity.getClass().getName() + " missing=" + missing
                            + " hook=onUpdateAircraft absent world=" + worldText(entity.field_70170_p));
            return;
        }
        try {
            updater.setAccessible(true);
            updater.invoke(entity);
            logRateLimited(DIAGNOSTIC_LOG_NANOS, "warmup:" + entity.getClass().getName(),
                    "[UMB-LEGACY] entity render state warmup entity="
                            + entity.getClass().getName() + " hook=onUpdateAircraft before=" + missing
                            + " after=" + inspectInfoGetters(entity)
                            + " world=" + worldText(entity.field_70170_p));
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException
                    && failure.getCause() != null ? failure.getCause() : failure;
            logRateLimited(DIAGNOSTIC_LOG_NANOS, "warmup:" + entity.getClass().getName(),
                    "[UMB-LEGACY] entity render state warmup failed entity="
                            + entity.getClass().getName() + " hook=onUpdateAircraft reason=" + reason(cause));
        }
    }

    private static String inspectInfoGetters(Entity entity) {
        if (entity == null) return "";
        StringBuilder values = new StringBuilder();
        Method[] methods;
        try { methods = entity.getClass().getMethods(); }
        catch (Throwable ignored) { return "getter-scan-failed"; }
        for (Method method : methods) {
            String name = method.getName();
            if (method.getParameterTypes().length != 0 || method.getReturnType() == Void.TYPE
                    || !name.startsWith("get") || !name.endsWith("Info")) continue;
            try {
                Object value = method.invoke(entity);
                if (values.length() > 0) values.append(',');
                values.append(name).append('=').append(value == null ? "null" : "set");
            } catch (Throwable failure) {
                if (values.length() > 0) values.append(',');
                values.append(name).append("=throws:").append(reason(failure));
            }
        }
        return values.toString();
    }

    /** Adds a generic model-slot diagnostic without naming or linking any mod type. */
    private static String inspectInfoModels(Entity entity) {
        if (entity == null) return "";
        StringBuilder values = new StringBuilder();
        try {
            for (Method getter : entity.getClass().getMethods()) {
                if (getter.getParameterTypes().length != 0
                        || getter.getReturnType() == Void.TYPE
                        || !getter.getName().startsWith("get")
                        || !getter.getName().endsWith("Info")) continue;
                Object info = getter.invoke(entity);
                if (info == null) continue;
                Field model = null;
                for (Class<?> type = info.getClass(); type != null && model == null;
                        type = type.getSuperclass()) {
                    try {
                        model = type.getDeclaredField("model");
                    } catch (NoSuchFieldException ignored) { }
                }
                if (model == null) continue;
                model.setAccessible(true);
                if (values.length() > 0) values.append(',');
                values.append(getter.getName()).append('=').append(model.get(info) == null ? "null" : "set");
            }
        } catch (Throwable ignored) {
            return "scan-failed";
        }
        return values.toString();
    }

    private static void logRateLimited(Map<String, Long> times, String key, String line) {
        long now = System.nanoTime();
        Long previous = times.get(key);
        if (previous == null || now - previous.longValue() >= FAILURE_LOG_INTERVAL_NANOS) {
            times.put(key, Long.valueOf(now));
            System.out.println(line);
        }
    }

    public static Minecraft currentMinecraft() {
        Minecraft minecraft = ACTIVE_MINECRAFT.get();
        if (minecraft != null) return minecraft;
        try { return Minecraft.func_71410_x(); } catch (Throwable ignored) { return null; }
    }

    /**
     * Runs a legacy 2-D callback against the same GL-EMU used by entity/tile rendering. The
     * sealed mesh is host-independent; the 26.2 HUD adapter owns final GUI-layer submission.
     */
    public static GlEmulationSession.Mesh captureOverlay(LegacyClientFacade.Binding binding,
                                                          Runnable callback) {
        return captureOverlay(binding, callback, false);
    }

    /**
     * Overlay capture with the vanilla GUI ambient GL state pre-applied. Real 1.7.10 clients
     * enter container drawing with texturing (and usually blending) enabled, and large parts
     * of mod GUI code (background layers, custom text) bind and emit textured quads without
     * enabling anything first. The emulation starts empty, which turned all of that into
     * color fills. Explicit disables inside the callback still record and replay as fills.
     * HUD captures keep the previous behavior (pass false) until proven otherwise.
     */
    public static GlEmulationSession.Mesh captureOverlay(LegacyClientFacade.Binding binding,
                                                          Runnable callback,
                                                          boolean guiAmbientState) {
        Capture capture = new Capture("overlay", "overlay", "overlay", "overlay", "overlay", true);
        if (guiAmbientState) {
            capture.enable(GL_TEXTURE_2D);
            capture.enable(GL_BLEND);
        }
        ACTIVE.set(capture);
        ACTIVE_MINECRAFT.set(binding == null ? null : binding.minecraft);
        try {
            if (callback != null) callback.run();
            return capture.emulation.seal();
        } finally {
            ACTIVE_MINECRAFT.remove();
            ACTIVE.remove();
        }
    }

    /**
     * Resource-manager accessor used by transformed legacy callers. The live client proved that
     * the accessor method can return null even while reflective reads of field_110451_am are
     * non-null, so capture uses the installed manager as the authoritative value and only falls
     * back to the original method reflectively outside a capture.
     */
    public static IResourceManager currentResourceManager(Minecraft minecraft) {
        Object installed = installedResourceManager;
        if (installed instanceof IResourceManager) return (IResourceManager) installed;
        if (minecraft == null) return null;
        try {
            Method getter = Minecraft.class.getDeclaredMethod("func_110442_L");
            getter.setAccessible(true);
            Object value = getter.invoke(minecraft);
            return value instanceof IResourceManager ? (IResourceManager) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void begin(int mode) {
        Capture c = ACTIVE.get();
        if (c != null) c.begin(mode);
    }
    public static void vertex(double x, double y, double z, double u, double v) {
        Capture c = ACTIVE.get();
        if (c != null) c.vertex(x, y, z, u, v);
    }
    public static void vertex(double x, double y, double z) {
        // UV-less vertices inherit the last glTexCoord2f / setTextureUV, exactly like vanilla
        // GL immediate mode and Tessellator.textureU/V do. Passing (0,0) here turned every
        // font glyph (immediate-mode glVertex3f) into a single-texel smear.
        Capture c = ACTIVE.get();
        if (c != null) c.vertex(x, y, z, c.u, c.v);
    }
    public static void uv(double u, double v) { Capture c = ACTIVE.get(); if (c != null) { c.u = (float) u; c.v = (float) v; } }
    public static void normal(float x, float y, float z) { Capture c = ACTIVE.get(); if (c != null) { c.nx=x; c.ny=y; c.nz=z; } }
    public static void color(float r, float g, float b, float a) { Capture c = ACTIVE.get(); if (c != null) c.color(r,g,b,a); }
    public static void draw() { Capture c = ACTIVE.get(); if (c != null) c.draw(); }

    /** Tessellator int colour setters (0-255, clamped like vanilla) onto the float path. */
    private static void colorInt(int r, int g, int b, int a) {
        color(clamp255(r) / 255.0F, clamp255(g) / 255.0F, clamp255(b) / 255.0F, clamp255(a) / 255.0F);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    // Tessellator call-site shims include the receiver because the legacy runtime's own
    // Tessellator class wins classpath resolution over our headless ABI shadow.
    public static int func_78381_a(net.minecraft.client.renderer.Tessellator ignored) { draw(); return 0; }
    public static void func_78382_b(net.minecraft.client.renderer.Tessellator ignored) { begin(7); }
    public static void func_78371_b(net.minecraft.client.renderer.Tessellator ignored, int mode) { begin(mode); }
    public static void func_78385_a(net.minecraft.client.renderer.Tessellator ignored, double u, double v) { uv(u, v); }
    public static void func_78380_c(net.minecraft.client.renderer.Tessellator ignored, int value) {}
    public static void func_78386_a(net.minecraft.client.renderer.Tessellator ignored, float r, float g, float b) { color(r,g,b,1); }
    public static void func_78369_a(net.minecraft.client.renderer.Tessellator ignored, float r, float g, float b, float a) { color(r,g,b,a); }
    public static void func_78376_a(net.minecraft.client.renderer.Tessellator ignored, int r, int g, int b) { colorInt(r, g, b, 255); }
    public static void func_78370_a(net.minecraft.client.renderer.Tessellator ignored, int r, int g, int b, int a) { colorInt(r, g, b, a); }
    public static void func_154352_a(net.minecraft.client.renderer.Tessellator ignored, byte r, byte g, byte b) { colorInt(r & 255, g & 255, b & 255, 255); }
    public static void func_78374_a(net.minecraft.client.renderer.Tessellator ignored, double x, double y, double z, double u, double v) { Capture c = ACTIVE.get(); if (c != null) vertex(x + c.tessX, y + c.tessY, z + c.tessZ, u, v); }
    public static void func_78377_a(net.minecraft.client.renderer.Tessellator ignored, double x, double y, double z) { Capture c = ACTIVE.get(); if (c != null) vertex(x + c.tessX, y + c.tessY, z + c.tessZ); }
    public static void func_78378_d(net.minecraft.client.renderer.Tessellator ignored, int rgb) { colorInt((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255); }
    public static void func_78384_a(net.minecraft.client.renderer.Tessellator ignored, int rgb, int alpha) { colorInt((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha); }
    public static void func_78383_c(net.minecraft.client.renderer.Tessellator ignored) {}
    public static void func_78375_b(net.minecraft.client.renderer.Tessellator ignored, float x, float y, float z) { normal(x,y,z); }
    public static void func_78373_b(net.minecraft.client.renderer.Tessellator ignored, double x, double y, double z) { Capture c = ACTIVE.get(); if (c != null) { c.tessX = x; c.tessY = y; c.tessZ = z; } }
    public static void func_78372_c(net.minecraft.client.renderer.Tessellator ignored, float x, float y, float z) { Capture c = ACTIVE.get(); if (c != null) { c.tessX += x; c.tessY += y; c.tessZ += z; } }

    /** TextureManager.bindTexture prefix: records the bind and skips loading while capturing. */
    public static boolean captureBindTexture(ResourceLocation location) {
        Capture c = ACTIVE.get();
        if (c == null) return false;
        c.bindTexture(location == null ? null : location.toString());
        return true;
    }

    public static void bindTexture(ResourceLocation location) {
        Capture c = ACTIVE.get();
        if (c != null) c.bindTexture(location == null ? null : location.toString());
    }

    public static void glPushMatrix() { Capture c=ACTIVE.get(); if(c!=null){c.push();} }
    public static void glPopMatrix() { Capture c=ACTIVE.get(); if(c!=null){c.pop();} }
    public static void glTranslatef(float x,float y,float z){translate(x,y,z);}
    public static void glTranslated(double x,double y,double z){translate(x,y,z);}
    public static void glScalef(float x,float y,float z){scale(x,y,z);}
    public static void glScaled(double x,double y,double z){scale(x,y,z);}
    public static void glRotatef(float a,float x,float y,float z){rotate(a,x,y,z);}
    public static void glRotated(double a,double x,double y,double z){rotate(a,x,y,z);}
    public static void glColor4f(float r,float g,float b,float a){color(r,g,b,a);}
    public static void glEnable(int x) { Capture c=ACTIVE.get(); if(c!=null)c.enable(x); }
    public static void glDisable(int x) { Capture c=ACTIVE.get(); if(c!=null)c.disable(x); }
    public static void glShadeModel(int x) { Capture c=ACTIVE.get(); if(c!=null)c.shadeModel(x); }
    public static void glBlendFunc(int x,int y) { Capture c=ACTIVE.get(); if(c!=null)c.blendFunc(x,y); }
    public static void glDepthMask(boolean x) { Capture c=ACTIVE.get(); if(c!=null)c.depthMask(x); }
    public static void glCullFace(int x) { Capture c=ACTIVE.get(); if(c!=null)c.cullFace(x); }
    public static void glAlphaFunc(int x,float y) { Capture c=ACTIVE.get(); if(c!=null)c.alphaFunc(x,y); }
    public static int glGetInteger(int x) { return 0; }
    public static boolean glIsEnabled(int x) { return false; }
    public static void glNormal3f(float x,float y,float z){normal(x,y,z);}
    public static void glNormal3d(double x,double y,double z){normal((float)x,(float)y,(float)z);}
    public static void glBegin(int x){beginMode(x);}
    public static void glEnd(){draw();}
    public static void glTexCoord2f(float u,float v){uv(u,v);}
    public static void glTexCoord2d(double u,double v){uv(u,v);}
    public static void glMultiTexCoord2f(int target,float u,float v){uv(u,v);}
    public static void glActiveTexture(int texture) { Capture c=ACTIVE.get(); if(c!=null)c.activeTexture(texture); }
    public static void glColor4b(byte r,byte g,byte b,byte a){color((r&255)/255.0f,(g&255)/255.0f,(b&255)/255.0f,(a&255)/255.0f);}
    public static void glColor4ub(byte r,byte g,byte b,byte a){color((r&255)/255.0f,(g&255)/255.0f,(b&255)/255.0f,(a&255)/255.0f);}
    public static void glColor4d(double r,double g,double b,double a){color((float)r,(float)g,(float)b,(float)a);}
    public static void glLineWidth(float width) {}
    public static void glPointSize(float size) {}
    public static void glLineStipple(int factor,short pattern) {}
    public static void glPolygonMode(int face,int mode) {}
    public static float glGetFloat(int pname) { return 0.0F; }
    public static void glGetFloat(int pname,java.nio.FloatBuffer params) {}
    public static void glEnableClientState(int cap) {}
    public static void glDisableClientState(int cap) {}
    public static void glDrawArrays(int mode,int first,int count) {}
    public static void glTexCoordPointer(int size,int type,java.nio.DoubleBuffer p) {}
    public static void glTexCoordPointer(int size,int type,java.nio.FloatBuffer p) {}
    public static void glTexCoordPointer(int size,int type,java.nio.IntBuffer p) {}
    public static void glTexCoordPointer(int size,int type,java.nio.ShortBuffer p) {}
    public static void glTexCoordPointer(int size,int type,int stride,java.nio.ByteBuffer p) {}
    public static void glTexCoordPointer(int size,int type,int stride,long p) {}
    public static void glVertexPointer(int size,int type,java.nio.DoubleBuffer p) {}
    public static void glVertexPointer(int size,int type,java.nio.FloatBuffer p) {}
    public static void glVertexPointer(int size,int type,java.nio.IntBuffer p) {}
    public static void glVertexPointer(int size,int type,java.nio.ShortBuffer p) {}
    public static void glVertexPointer(int size,int type,int stride,java.nio.ByteBuffer p) {}
    public static void glVertexPointer(int size,int type,int stride,long p) {}
    public static void glNormalPointer(int type,java.nio.ByteBuffer p) {}
    public static void glNormalPointer(int type,java.nio.DoubleBuffer p) {}
    public static void glNormalPointer(int type,java.nio.FloatBuffer p) {}
    public static void glNormalPointer(int type,java.nio.IntBuffer p) {}
    public static void glNormalPointer(int type,int stride,long p) {}
    public static void glColorPointer(int size,int type,java.nio.DoubleBuffer p) {}
    public static void glColorPointer(int size,int type,java.nio.FloatBuffer p) {}
    public static void glColorPointer(int size,boolean unsigned,int stride,java.nio.ByteBuffer p) {}
    public static void glColorPointer(int size,int type,int stride,java.nio.ByteBuffer p) {}
    public static void glColorPointer(int size,int type,int stride,long p) {}
    public static void glVertex2f(float x,float y){vertex(x,y,0.0D);}
    public static void glVertex3f(float x,float y,float z){vertex(x,y,z);}
    public static void glVertex3d(double x,double y,double z){vertex(x,y,z);}
    public static void glBindTexture(int x,int y) {}

    public static int glGenLists(int count) { Capture c=ACTIVE.get(); return c == null ? 0 : c.genLists(count); }
    public static void glNewList(int id,int mode) { Capture c=ACTIVE.get(); if(c!=null)c.newList(id); }
    public static void glEndList() { Capture c=ACTIVE.get(); if(c!=null)c.endList(); }
    public static void glCallList(int id) { Capture c=ACTIVE.get(); if(c!=null)c.callList(id); }
    public static void glDeleteLists(int id,int count) { }

    // Fail-closed return shims for unfamiliar render-time Sys/Display/GL methods. These keep a
    // renderer from ever reaching the host-loaded LWJGL native library.
    public static void safeVoid() {}
    public static int safeInt() { return 0; }
    public static long safeLong() { return 0L; }
    public static float safeFloat() { return 0.0F; }
    public static double safeDouble() { return 0.0D; }
    public static Object safeObject() { return null; }

    /** Native-free replacements for LWJGL2 BufferUtils used by model/VBO loaders. */
    private static ByteBuffer directBuffer(int bytes) {
        int size = Math.max(0, bytes);
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }
    public static ByteBuffer createByteBuffer(int count) { return directBuffer(count); }
    public static FloatBuffer createFloatBuffer(int count) { return directBuffer(count * 4).asFloatBuffer(); }
    public static IntBuffer createIntBuffer(int count) { return directBuffer(count * 4).asIntBuffer(); }
    public static ShortBuffer createShortBuffer(int count) { return directBuffer(count * 2).asShortBuffer(); }
    public static LongBuffer createLongBuffer(int count) { return directBuffer(count * 8).asLongBuffer(); }
    public static DoubleBuffer createDoubleBuffer(int count) { return directBuffer(count * 8).asDoubleBuffer(); }

    private static void translate(double x,double y,double z){Capture c=ACTIVE.get();if(c!=null)c.translate(x,y,z);}
    private static void scale(double x,double y,double z){Capture c=ACTIVE.get();if(c!=null)c.scale(x,y,z);}
    private static void rotate(double a,double x,double y,double z){Capture c=ACTIVE.get();if(c!=null)c.rotate(a,x,y,z);}
    private static void beginMode(int mode){Capture c=ACTIVE.get();if(c!=null)c.begin(mode);}

    /**
     * Initializes the visual half of the legacy universe while the client facade is active.
     *
     * <p>FML deliberately boots this headless universe on SERVER so common lifecycle code can
     * run without a native client.  That means the normal {@code @SidedProxy} client hook is
     * never selected during mod construction.  Waiting for the first entity capture to discover
     * it is a circular dependency: no client proxy means no model/render registration, and no
     * registered renderer means no capture.  The client tick dispatcher calls this once after
     * installing the facade, so every mod gets the same isolated client visual boundary before
     * any entity renderer is looked up.
     */
    public static void prepareClientUniverse(LegacyClientFacade.Binding binding) {
        if (binding == null) return;
        synchronized (LOCK) {
            // The client facade is deliberately short-lived: install() allocates a fresh
            // Minecraft shell for every bounded tick, while the native-free FontRenderer and
            // TextureManager are shared.  Rebind those shared services even after the visual
            // universe has been initialized; otherwise only the first facade gets field_71466_p
            // and field_71446_o and every later overlay listener sees null.
            if (clientResourcesInstalled) {
                bindFacadeRenderServices(binding);
                return;
            }
            try {
                ensureManager(binding, binding.world);
                if (!clientResourcesInstalled) {
                    installResourcePacks(binding.minecraft);
                    clientResourcesInstalled = true;
                }
                bindFacadeRenderServices(binding);
                System.out.println("[UMB-LEGACY] client resource universe ready side=client fmlSide="
                        + cpw.mods.fml.common.FMLCommonHandler.instance().getSide().name());
                // (load-phase ticks, same method, same isolation, same flag) instead of
                // on first capture, where its one-shot classloading/model parsing froze
                // the render thread ~4s on the user's first placement. Gated on staged
                // mods: an empty mod list means boot hasn't staged anything yet, and
                // marking ready then would permanently skip real registrations.
                if (!clientRegistered && !cpw.mods.fml.common.Loader.instance().getModList().isEmpty()) {
                    ensureClientVisuals(binding);
                }
            } catch (Throwable failure) {
                // Client registration is additive. A broken client-only hook must never abort
                // the host tick; discoverClientProxies already isolates individual hooks.
                logRateLimited(FAILURE_LOG_NANOS, "client-visual-universe",
                        "[UMB-LEGACY] client visual universe unavailable: " + reason(failure));
            }
        }
    }

    private static LegacyClientFacade.Binding ensureClient(Entity entity) throws Exception {
        EntityPlayer serverPlayer = null;
        if (entity.field_70170_p != null && entity.field_70170_p.field_73010_i != null
                && !entity.field_70170_p.field_73010_i.isEmpty()) {
            serverPlayer = (EntityPlayer) entity.field_70170_p.field_73010_i.get(0);
        }
        LegacyClientFacade.Binding b = LegacyClientFacade.install(serverPlayer, entity.field_70170_p);
        ensureManager(b, entity.field_70170_p);
        ensureClientVisuals(b);
        return b;
    }

    private static void ensureGameSettings(LegacyClientFacade.Binding binding) {
        if (binding == null || binding.minecraft == null || binding.gameSettings == null) return;
        try {
            Field settings = field(binding.minecraft.getClass(), "field_71474_y");
            if (settings.get(binding.minecraft) == null) settings.set(binding.minecraft, binding.gameSettings);
        } catch (Throwable failure) {
            logRateLimited(DIAGNOSTIC_LOG_NANOS, "game-settings-bind",
                    "[UMB-LEGACY] client settings bind skipped reason=" + reason(failure));
        }
    }

    /** Run all visual proxy hooks inside the native-free capture boundary. */
    private static void ensureClientVisuals(LegacyClientFacade.Binding binding) throws Exception {
        if (clientRegistered) return;
        ensureManager(binding, binding.world);
        if (!clientResourcesInstalled) {
            installResourcePacks(binding.minecraft);
            clientResourcesInstalled = true;
        }
        bindFacadeRenderServices(binding);
        Capture registration = new Capture("client-proxy", "client-proxy", "", "", "", true);
        ACTIVE.set(registration);
        ACTIVE_MINECRAFT.set(binding.minecraft);
        try {
            discoverClientProxies();
            RenderingRegistry.instance().loadEntityRenderers(manager.field_78729_o);
            registration.finish();
            clientRegistered = true;
            System.out.println("[UMB-LEGACY] client visual universe ready side=client fmlSide="
                    + cpw.mods.fml.common.FMLCommonHandler.instance().getSide().name()
                    + " thread=" + Thread.currentThread().getName() + " scope=gl-emu");
        } finally {
            ACTIVE_MINECRAFT.remove();
            ACTIVE.remove();
        }
    }

    private static void ensureManager(LegacyClientFacade.Binding binding,
            net.minecraft.world.World world) throws Exception {
        if (manager != null) return;
        manager = allocate(RenderManager.class);
        set(RenderManager.class, manager, "field_78729_o", new HashMap<Class<?>, Render>());
        set(RenderManager.class, manager, "field_78722_g", world);
        set(RenderManager.class, manager, "field_78733_k", binding.gameSettings);
        set(RenderManager.class, manager, "field_78724_e", allocate(TextureManager.class));
        setStatic(RenderManager.class, "field_78727_a", manager);
    }

    private static void installResourcePacks(Minecraft minecraft) throws Exception {
        // The facade is allocated without Minecraft's constructor. Re-establish the same
        // singleton that Minecraft.func_71410_x() reads before any client proxy can ask for
        // Minecraft.func_110442_L(). This is deliberately repeated at the capture boundary so
        // another client-universe dispatch cannot leave the live legacy view null.
        Minecraft live = liveMinecraft(minecraft);
        SimpleReloadableResourceManager resources =
                new SimpleReloadableResourceManager(new IMetadataSerializer());
        // Resource-pack listeners run synchronously from func_110545_a.  HBM and other
        // client listeners consult Minecraft.func_110438_M() during that notification,
        // so both singleton views must have a real repository before the first pack is
        // mounted.  The facade is constructor-free; installing only the resource manager
        // left the repository accessor null in the live classloader.
        Object repository = newResourcePackRepository(Minecraft.class, minecraft);
        field(Minecraft.class, "field_110448_aq").set(minecraft, repository);
        if (live != minecraft) field(Minecraft.class, "field_110448_aq").set(live, repository);
        installedResourceRepository = repository;
        installedResourceManager = resources;
        installedFacade = minecraft;
        field(Minecraft.class, "field_110451_am").set(minecraft, resources);
        if (live != minecraft) field(Minecraft.class, "field_110451_am").set(live, resources);
        bindResourceManager(minecraft.getClass().getClassLoader());
        initializeTextureManager(resources);
        // The vanilla client jar is not a mod source in the integrated server. Mount it through
        // the same resource bridge used by mod packs so FontRenderer can read ascii.png and
        // glyph_sizes.bin without depending on a client display or a client-only repository.
        VanillaClientPack vanillaPack = new VanillaClientPack(minecraft.getClass().getClassLoader());
        resources.func_110545_a(vanillaPack);
        System.out.println("[UMB-LEGACY] vanilla client font resources pack="
                + vanillaPack.func_130077_b() + " ascii="
                + vanillaPack.func_110589_b(new ResourceLocation("textures/font/ascii.png"))
                + " glyph_sizes="
                + vanillaPack.func_110589_b(new ResourceLocation("font/glyph_sizes.bin")));
        int mounted = 0;
        for (ModContainer container : Loader.instance().getModList()) {
            java.io.File source = container.getSource();
            if (source == null || !source.exists()) continue;
            resources.func_110545_a(new MountedPack(source));
            mounted++;
        }
        Object observed = resourceManager(live);
        if (observed == null) {
            throw new IllegalStateException("live-resource-manager-null singleton="
                    + live.getClass().getName());
        }
        System.out.println("[UMB-LEGACY] client resource manager installed singleton="
                + live.getClass().getName() + " manager=" + observed.getClass().getName()
                + " mounted=" + mounted);
        Object observedRepository = field(Minecraft.class, "field_110448_aq").get(live);
        if (observedRepository == null) {
            throw new IllegalStateException("live-resource-repository-null singleton="
                    + live.getClass().getName());
        }
        logMinecraftViewOnce("install", minecraft.getClass().getClassLoader());
        System.out.println("[UMB-LEGACY] client resource repository installed singleton="
                + live.getClass().getName() + " repository="
                + observedRepository.getClass().getName());
    }

    private static void bindFacadeRenderServices(LegacyClientFacade.Binding binding) {
        if (binding == null) return;
        IResourceManager resources = installedResourceManager instanceof IResourceManager
                ? (IResourceManager) installedResourceManager : null;
        LegacyClientFacade.bindClientRenderServices(binding.minecraft,
                binding.gameSettings, resources);
    }

    /**
     * RenderManager is allocated before the resource boundary exists so proxy discovery can
     * use one stable dispatcher.  Unsafe allocation skips TextureManager's constructor, which
     * normally creates field_110585_a (the texture map), field_110583_b (tickables), and
     * field_110584_c (dynamic-name counters).  HBM's TESRs call bindTexture during capture;
     * leaving those fields null turns an otherwise native-free render into a swallowed NPE.
     * Re-establish the constructor state after the real emulated resource manager is installed.
     */
    private static void initializeTextureManager(IResourceManager resources) throws Exception {
        if (manager == null || resources == null) return;
        Object textureManager = field(RenderManager.class, "field_78724_e").get(manager);
        if (textureManager == null) {
            textureManager = allocate(TextureManager.class);
            set(RenderManager.class, manager, "field_78724_e", textureManager);
        }
        Field resourceField = field(TextureManager.class, "field_110582_d");
        if (!resourceField.getType().isInstance(resources)) {
            throw new IllegalStateException("texture-manager-resource-loader-mismatch");
        }
        resourceField.set(textureManager, resources);
        Field textures = field(TextureManager.class, "field_110585_a");
        if (textures.get(textureManager) == null) textures.set(textureManager, new HashMap<Object,Object>());
        Field tickables = field(TextureManager.class, "field_110583_b");
        if (tickables.get(textureManager) == null) tickables.set(textureManager, new ArrayList<Object>());
        Field dynamicIds = field(TextureManager.class, "field_110584_c");
        if (dynamicIds.get(textureManager) == null) dynamicIds.set(textureManager, new HashMap<String,Integer>());
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "texture-manager-init",
                "[UMB-LEGACY] texture manager initialized resources="
                        + System.identityHashCode(resources) + " textures="
                        + (textures.get(textureManager) == null ? "null" : "ready"));
    }

    /**
     * Creates the vanilla repository without opening a display or touching LWJGL.  The
     * repository is deliberately empty: mod jars are mounted into the emulated resource
     * manager below, while client code still receives the non-null vanilla repository
     * contract it expects from Minecraft.func_110438_M().  The reflective fallback keeps
     * this valid when a child LaunchClassLoader owns a distinct Minecraft class.
     */
    private static Object newResourcePackRepository(Class<?> clientType, Object client)
            throws Exception {
        ClassLoader loader = clientType.getClassLoader();
        Class<?> repositoryType = Class.forName(
                "net.minecraft.client.resources.ResourcePackRepository", false, loader);
        Object settings = null;
        try { settings = field(clientType, "field_71474_y").get(client); }
        catch (Throwable ignored) { }
        try {
            Class<?> defaultPackType = Class.forName(
                    "net.minecraft.client.resources.DefaultResourcePack", false, loader);
            Class<?> metadataType = Class.forName(
                    "net.minecraft.client.resources.data.IMetadataSerializer", false, loader);
            Object defaultPack = defaultPackType.getDeclaredConstructor(Map.class)
                    .newInstance(new HashMap<Object, Object>());
            Object metadata = metadataType.getDeclaredConstructor().newInstance();
            for (java.lang.reflect.Constructor<?> constructor : repositoryType.getDeclaredConstructors()) {
                Class<?>[] p = constructor.getParameterTypes();
                if (p.length != 5 || p[0] != File.class || p[1] != File.class
                        || !p[2].isInstance(defaultPack) || !p[3].isInstance(metadata)
                        || settings == null || !p[4].isInstance(settings)) continue;
                constructor.setAccessible(true);
                return constructor.newInstance(new File("resourcepacks"),
                        new File("server-resourcepacks"), defaultPack, metadata, settings);
            }
        } catch (Throwable constructionFailure) {
            logRateLimited(FAILURE_LOG_NANOS, "resource-repository-constructor",
                    "[UMB-LEGACY] resource repository constructor unavailable: "
                            + reason(constructionFailure));
        }
        Object repository = allocate(repositoryType);
        // func_110613_c() returns the selected-pack list.  Constructor-free fallback is
        // still a valid empty repository for the native-free client visual universe.
        for (Class<?> c = repositoryType; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                if (f.get(repository) == null) f.set(repository, new ArrayList<Object>());
            }
        }
        return repository;
    }

    private static Minecraft liveMinecraft(Minecraft fallback) throws Exception {
        Minecraft live = null;
        try { live = Minecraft.func_71410_x(); } catch (Throwable ignored) { }
        if (live == null) {
            setStatic(Minecraft.class, "field_71432_P", fallback);
            live = Minecraft.func_71410_x();
        }
        if (live == null) throw new IllegalStateException("live-minecraft-singleton-null");
        return live;
    }

    private static Object resourceManager(Minecraft minecraft) throws Exception {
        Method getter = Minecraft.class.getDeclaredMethod("func_110442_L");
        getter.setAccessible(true);
        return getter.invoke(minecraft);
    }

    /** Rebinds the manager through the loader that owns a mod's client proxy. */
    private static void bindResourceManager(ClassLoader loader) throws Exception {
        if (installedResourceManager == null || loader == null) return;
        Class<?> clientType = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Field singletonField = field(clientType, "field_71432_P");
        Object fieldClient = singletonField.get(null);
        Object client = fieldClient;
        Object accessorClient = null;
        // Do not assume the static field is the accessor's authoritative view: the live
        // LaunchClassLoader can expose a facade singleton through func_71410_x() even while
        // another loader-local field still points at an older uninitialised instance.
        try {
            Method accessor = clientType.getDeclaredMethod("func_71410_x");
            accessor.setAccessible(true);
            accessorClient = accessor.invoke(null);
            if (accessorClient != null && clientType.isInstance(accessorClient)) client = accessorClient;
        } catch (Throwable ignored) {
            // Fall through to the field/allocate path; verification below remains fail-closed.
        }
        if (client == null || !clientType.isInstance(client)) {
            if (clientType.isInstance(installedFacade)) {
                client = installedFacade;
            } else {
                client = allocate(clientType);
            }
        }
        // The facade transformer may make func_71410_x() authoritative even when the raw
        // field still points at an earlier instance. Keep both views on the same object before
        // touching the resource-manager field; otherwise mod model loaders can observe null.
        Field managerField = field(clientType, "field_110451_am");
        if (!managerField.getType().isInstance(installedResourceManager)) {
            throw new IllegalStateException("resource-manager-loader-mismatch loader="
                    + String.valueOf(loader));
        }
        // Bind every object visible through this class's two singleton views. In the live
        // LaunchClassLoader topology func_71410_x() may return a facade/static-copy instance
        // different from field_71432_P; setting only one leaves model loaders seeing null.
        if (fieldClient != null && clientType.isInstance(fieldClient)) {
            managerField.set(fieldClient, installedResourceManager);
        }
        managerField.set(client, installedResourceManager);
        if (accessorClient != null && clientType.isInstance(accessorClient)) {
            managerField.set(accessorClient, installedResourceManager);
        }
        singletonField.set(null, client);
        // Fail closed on OUR write (null here is a real topology break), but tolerate a
        // raced singleton read: facade installs on other threads publish their own facade
        // as the static between our publish and the verification below, and aborting the
        // whole capture on that race is what produced the every-frame
        // resource-manager-bind-null skips. The capture underneath uses our bound client,
        // never the ambient singleton.
        if (managerField.get(client) == null) {
            throw new IllegalStateException("resource-manager-bind-null loader="
                    + String.valueOf(loader));
        }
        Object verifiedClient = client;
        try {
            Method accessor = clientType.getDeclaredMethod("func_71410_x");
            accessor.setAccessible(true);
            Object verifiedAccessorClient = accessor.invoke(null);
            if (verifiedAccessorClient != null && clientType.isInstance(verifiedAccessorClient)) {
                verifiedClient = verifiedAccessorClient;
            }
        } catch (Throwable ignored) { }
        if (verifiedClient != client && managerField.get(verifiedClient) == null) {
            logRateLimited(DIAGNOSTIC_LOG_NANOS, "resource-manager-singleton-drift:" + loader,
                    "[UMB-LEGACY] resource manager singleton drift loader=" + String.valueOf(loader)
                            + " bound=" + System.identityHashCode(client)
                            + " observed=" + System.identityHashCode(verifiedClient));
        }
        bindResourceRepository(clientType, fieldClient, client, accessorClient);
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "resource-manager-bind:" + clientType.getName(),
                "[UMB-LEGACY] resource manager bind client=" + clientType.getName()
                        + " singleton=" + System.identityHashCode(client)
                        + " verified=" + System.identityHashCode(verifiedClient)
                        + " manager=" + System.identityHashCode(managerField.get(verifiedClient)));
        logMinecraftViewOnce("bind", loader);
    }

    /** Bind the repository through every singleton view in the same loader topology. */
    private static void bindResourceRepository(Class<?> clientType, Object fieldClient,
            Object client, Object accessorClient) throws Exception {
        Field repositoryField = field(clientType, "field_110448_aq");
        Object repository = installedResourceRepository;
        if (repository == null || !repositoryField.getType().isInstance(repository)) {
            repository = newResourcePackRepository(clientType, client);
        }
        if (fieldClient != null && clientType.isInstance(fieldClient)) {
            repositoryField.set(fieldClient, repository);
        }
        repositoryField.set(client, repository);
        if (accessorClient != null && clientType.isInstance(accessorClient)) {
            repositoryField.set(accessorClient, repository);
        }
        Object observed = repositoryField.get(client);
        if (observed == null) throw new IllegalStateException("resource-repository-bind-null");
        logRateLimited(DIAGNOSTIC_LOG_NANOS, "resource-repository-bind:" + clientType.getName(),
                "[UMB-LEGACY] resource repository bind client=" + clientType.getName()
                        + " repository=" + System.identityHashCode(observed));
    }

    /**
     * intentionally reflection-only and generic: it reports the class identity, defining loader,
     * raw singleton, accessor result, and field_110451_am value without executing any GL/LWJGL.
     */
    private static void logMinecraftViewOnce(String phase, ClassLoader loader) {
        if (loader == null) return;
        String key = phase + "|" + System.identityHashCode(loader);
        if (MINECRAFT_VIEW_DIAGNOSTICS.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            Class<?> clientType = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Field singletonField = field(clientType, "field_71432_P");
            Field managerField = field(clientType, "field_110451_am");
            Object rawSingleton = singletonField.get(null);
            Object accessorValue = null;
            String accessorError = "";
            try {
                Method accessor = clientType.getDeclaredMethod("func_71410_x");
                accessor.setAccessible(true);
                accessorValue = accessor.invoke(null);
            } catch (Throwable failure) {
                accessorError = reason(failure);
            }
            Object rawManager = rawSingleton == null ? null : managerField.get(rawSingleton);
            Object accessorManager = accessorValue == null ? null : managerField.get(accessorValue);
            Object accessorMethodManager = null;
            String resourceAccessorError = "";
            if (accessorValue != null) {
                try {
                    Method resourceAccessor = clientType.getDeclaredMethod("func_110442_L");
                    resourceAccessor.setAccessible(true);
                    accessorMethodManager = resourceAccessor.invoke(accessorValue);
                } catch (Throwable failure) {
                    resourceAccessorError = reason(failure);
                }
            }
            System.out.println("[UMB-LEGACY] minecraft-view phase=" + phase
                    + " classId=" + System.identityHashCode(clientType)
                    + " classLoader=" + loaderText(clientType.getClassLoader())
                    + " rawSingleton=" + objectText(rawSingleton)
                    + " accessor=" + objectText(accessorValue)
                    + " resourceField(raw)=" + objectText(rawManager)
                    + " resourceField(accessor)=" + objectText(accessorManager)
                    + " resourceAccessor=" + objectText(accessorMethodManager)
                    + (accessorError.length() == 0 ? "" : " accessorError=" + accessorError)
                    + (resourceAccessorError.length() == 0 ? "" : " resourceAccessorError=" + resourceAccessorError));
        } catch (Throwable failure) {
            System.out.println("[UMB-LEGACY] minecraft-view phase=" + phase
                    + " loader=" + loaderText(loader) + " failed=" + reason(failure));
        }
    }

    private static String objectText(Object value) {
        return value == null ? "null" : value.getClass().getName() + "@" + System.identityHashCode(value);
    }

    private static String loaderText(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@" + System.identityHashCode(loader);
    }

    /**
     * Vanilla 1.7.10 client resources are not a Forge mod source in the integrated server. This
     * adapter first asks the active legacy classloader (the normal copied-jar path), then falls
     * back to the repository's client.jar. It intentionally exposes only the minecraft
     * namespace and never opens a client window or touches GL.
     */
    private static final class VanillaClientPack implements IResourcePack {
        private final ClassLoader loader;
        private final File clientJar;
        private final Set namespaces = Collections.singleton("minecraft");

        VanillaClientPack(ClassLoader loader) {
            this.loader = loader;
            this.clientJar = locateVanillaClientJar();
        }

        private String path(ResourceLocation location) {
            return "assets/" + location.func_110624_b() + "/" + location.func_110623_a();
        }

        public InputStream func_110590_a(ResourceLocation location) {
            String path = path(location);
            InputStream classpath = loader == null ? null : loader.getResourceAsStream(path);
            if (classpath != null) return classpath;
            if (clientJar != null) {
                try {
                    final ZipFile zip = new ZipFile(clientJar);
                    ZipEntry entry = zip.getEntry(path);
                    if (entry != null) {
                        return new FilterInputStream(zip.getInputStream(entry)) {
                            public void close() throws java.io.IOException {
                                try { super.close(); } finally { zip.close(); }
                            }
                        };
                    }
                    zip.close();
                } catch (java.io.IOException ignored) { }
            }
            throw new RuntimeException(new java.io.FileNotFoundException(path));
        }

        public boolean func_110589_b(ResourceLocation location) {
            try { InputStream in = func_110590_a(location); in.close(); return true; }
            catch (Throwable ignored) { return false; }
        }

        public Set func_110587_b() { return namespaces; }
        public net.minecraft.client.resources.data.IMetadataSection func_135058_a(
                IMetadataSerializer serializer, String section) { return null; }
        public java.awt.image.BufferedImage func_110586_a() { return null; }
        public String func_130077_b() {
            return clientJar == null ? "legacy-classloader" : clientJar.getAbsolutePath();
        }

        private static File locateVanillaClientJar() {
            List<File> candidates = new ArrayList<File>();
            String repo = System.getProperty("umb.repo", "");
            if (!repo.isEmpty()) candidates.add(new File(repo, "research/jars/1.7.10/client.jar"));
            String userDir = System.getProperty("user.dir", "");
            if (!userDir.isEmpty()) candidates.add(new File(userDir, "research/jars/1.7.10/client.jar"));
            candidates.add(new File("research/jars/1.7.10/client.jar"));
            for (File candidate : candidates) {
                if (candidate.isFile()) return candidate;
            }
            return null;
        }
    }

    /** Resource-pack adapter that avoids depending on the legacy pack implementation's obf helper calls. */
    private static final class MountedPack implements IResourcePack {
        private final File source;
        private final Set namespaces;
        MountedPack(File source) {
            this.source = source;
            this.namespaces = new HashSet();
            if (source.isDirectory()) {
                File assets = new File(source, "assets");
                File[] dirs = assets.listFiles();
                if (dirs != null) for (File dir : dirs) if (dir.isDirectory()) namespaces.add(dir.getName());
            } else {
                try {
                    ZipFile zip = new ZipFile(source);
                    java.util.Enumeration entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = ((ZipEntry) entries.nextElement()).getName();
                        if (name.startsWith("assets/")) {
                            int slash = name.indexOf('/', 7);
                            if (slash > 7) namespaces.add(name.substring(7, slash));
                        }
                    }
                    zip.close();
                } catch (java.io.IOException ignored) { }
            }
        }
        private String path(ResourceLocation location) {
            return "assets/" + location.func_110624_b() + "/" + location.func_110623_a();
        }
        public InputStream func_110590_a(ResourceLocation location) {
            String path = path(location);
            try {
                if (source.isDirectory()) return new FileInputStream(new File(source, path));
                final ZipFile zip = new ZipFile(source);
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) { zip.close(); throw new java.io.FileNotFoundException(path); }
                return new FilterInputStream(zip.getInputStream(entry)) {
                    public void close() throws java.io.IOException { try { super.close(); } finally { zip.close(); } }
                };
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        public boolean func_110589_b(ResourceLocation location) {
            try { InputStream in = func_110590_a(location); in.close(); return true; }
            catch (Throwable e) { return false; }
        }
        public Set func_110587_b() { return namespaces; }
        public net.minecraft.client.resources.data.IMetadataSection func_135058_a(
                IMetadataSerializer serializer, String section) { return null; }
        public java.awt.image.BufferedImage func_110586_a() { return null; }
        public String func_130077_b() { return source.getAbsolutePath(); }
    }

    private static TextureManager textureManager() {
        try { return (TextureManager) field(RenderManager.class, "field_78724_e").get(manager); }
        catch (Throwable t) { return null; }
    }

    private static final String[] CLIENT_RENDER_HOOKS = {
            // Some legacy render constructors initialize static model slots to null. Register
            // dispatchers first, then let the mod's model hook populate those slots.
            "registerRenderer", "registerRenderers", "registerEntityRenderer", "registerModels",
            "registerPreRenderInfo",
            "registerItemRenderer", "registerBlockRenderer",
            "registerTileEntitySpecialRenderer", "registerRenderInfo"
    };

    /**
     * FML booted this universe on SERVER, so @SidedProxy installed common proxies during mod
     * construction. Rendering is the one deliberate client-side exception: after the facade is
     * installed, replace only the annotated proxy object and run its high-level visual hooks. Each
     * mod and each hook is isolated so one client-only failure cannot suppress every other mod.
     */
    private static void discoverClientProxies() {
        for (ModContainer container : Loader.instance().getModList()) {
            Object mod = null;
            String modId = safeModId(container);
            // freeze is this loop's one-shot classloading/model parsing on the render
            // thread). Timings ride the existing per-mod log lines; no behaviour change.
            long modStartNanos = System.nanoTime();
            try {
                mod = container.getMod();
                if (mod == null) {
                    clientProxyLog(modId, false, "mod-null" + proxyMs(modStartNanos));
                    continue;
                }
                List<Object> proxies = new ArrayList<Object>();
                for (Class<?> c = mod.getClass(); c != null; c = c.getSuperclass()) {
                    Field[] fields = c.getDeclaredFields();
                    for (Field f : fields) {
                        cpw.mods.fml.common.SidedProxy annotation =
                                f.getAnnotation(cpw.mods.fml.common.SidedProxy.class);
                        if (annotation == null) continue;
                        f.setAccessible(true);
                        Object current = f.get(mod);
                        String clientName = annotation.clientSide();
                        if (clientName == null || clientName.length() == 0) {
                            clientProxyLog(modId, false, "clientSide-empty:" + f.getName());
                            continue;
                        }
                        ClassLoader loader = current == null
                                ? mod.getClass().getClassLoader() : current.getClass().getClassLoader();
                        Object client = current;
                        if (client == null || !clientName.equals(client.getClass().getName())) {
                            client = Class.forName(clientName, true, loader).newInstance();
                            f.set(mod, client);
                        }
                        if (!containsIdentity(proxies, client)) proxies.add(client);
                    }
                }
                if (proxies.isEmpty()) {
                    clientProxyLog(modId, true, "no-annotated-client-proxy" + proxyMs(modStartNanos));
                    continue;
                }
                int invoked = 0;
                int failures = 0;
                StringBuilder hooks = new StringBuilder();
                for (Object proxy : proxies) {
                    for (String hookName : CLIENT_RENDER_HOOKS) {
                        Method hook = noArgMethod(proxy.getClass(), hookName);
                        if (hook == null) continue;
                        try {
                            hook.setAccessible(true);
                            // The hook's declaring class is the authority for the Minecraft
                            // symbol used by its bytecode; a proxy object may have been resolved
                            // through a different parent/child loader.
                            bindResourceManager(hook.getDeclaringClass().getClassLoader());
                            logMinecraftViewOnce("hook", hook.getDeclaringClass().getClassLoader());
                            String stderr = invokeHookWithStderrCapture(hook, proxy);
                            if (stderr.length() > 0) {
                                failures++;
                                logRateLimited(DIAGNOSTIC_LOG_NANOS, "proxy-stderr:" + modId + ":" + hookName,
                                        "[UMB-LEGACY] client proxy registration: failed mod="
                                                + modId + " hook=" + hookName
                                                + " reason=stderr-trace " + stderr);
                            } else {
                                invoked++;
                                if (hooks.length() > 0) hooks.append(',');
                                hooks.append(hookName);
                            }
                        } catch (Throwable failure) {
                            failures++;
                            Throwable cause = failure instanceof InvocationTargetException
                                    && failure.getCause() != null ? failure.getCause() : failure;
                            logRateLimited(DIAGNOSTIC_LOG_NANOS, "proxy:" + modId + ":" + hookName,
                                    "[UMB-LEGACY] client proxy registration: failed mod="
                                            + modId + " hook=" + hookName + " reason=" + reason(cause)
                                            + " stack=" + stackSummary(cause));
                        }
                    }
                }
                if (failures == 0) {
                    clientProxyLog(modId, true, "hooks=" + invoked + " [" + hooks + "]"
                            + proxyMs(modStartNanos));
                } else {
                    clientProxyLog(modId, false, "hook-failures=" + failures + " invoked=" + invoked
                            + proxyMs(modStartNanos));
                }
            } catch (Throwable failure) {
                Throwable cause = failure instanceof InvocationTargetException
                        && failure.getCause() != null ? failure.getCause() : failure;
                clientProxyLog(modId, false, reason(cause) + proxyMs(modStartNanos));
            }
        }
    }

    /** Runs one legacy hook while preserving its stderr and exposing swallowed loader failures. */
    private static String invokeHookWithStderrCapture(Method hook, Object proxy) throws Throwable {
        synchronized (HOOK_STDERR_LOCK) {
            final PrintStream previous = System.err;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream tee = new PrintStream(new OutputStream() {
                public void write(int value) throws java.io.IOException {
                    bytes.write(value);
                    previous.write(value);
                }
                public void write(byte[] value, int offset, int length) throws java.io.IOException {
                    bytes.write(value, offset, length);
                    previous.write(value, offset, length);
                }
                public void flush() throws java.io.IOException { previous.flush(); }
            }, true);
            System.setErr(tee);
            try {
                hook.invoke(proxy);
            } finally {
                tee.flush();
                System.setErr(previous);
            }
            String text = bytes.toString();
            if (text.length() == 0) return "";
            text = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
            while (text.indexOf("  ") >= 0) text = text.replace("  ", " ");
            return text.length() > 320 ? text.substring(0, 320) + "..." : text;
        }
    }

    /** Milliseconds-since helper for the per-mod proxy timing (bug3-4 lane, bug #3). */
    private static String proxyMs(long startNanos) {
        return " ms=" + ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String safeModId(ModContainer container) {
        try { return String.valueOf(container.getModId()); }
        catch (Throwable ignored) { return "?"; }
    }

    private static void clientProxyLog(String modId, boolean ok, String detail) {
        System.out.println("[UMB-LEGACY] client proxy registration: "
                + (ok ? "ok" : "failed") + " mod=" + modId + " " + detail);
    }

    private static String reason(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        String result = failure.getClass().getName() + (message == null ? "" : ":" + message);
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root != failure) {
            String rootMessage = root.getMessage();
            result += " cause=" + root.getClass().getName()
                    + (rootMessage == null ? "" : ":" + rootMessage);
        }
        return result;
    }

    private static String stackSummary(Throwable failure) {
        if (failure == null) return "";
        StackTraceElement[] trace = failure.getStackTrace();
        StringBuilder out = new StringBuilder();
        int count = Math.min(trace == null ? 0 : trace.length, 4);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append('|');
            out.append(trace[i].getClassName()).append('#').append(trace[i].getMethodName())
                    .append(':').append(trace[i].getLineNumber());
        }
        return out.toString();
    }

    private static Method noArgMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static boolean containsIdentity(List<Object> values, Object value) {
        for (Object existing : values) if (existing == value) return true;
        return false;
    }
    private static String entityId(Entity e){try{String s=net.minecraft.entity.EntityList.func_75621_b(e);return s==null?"":s;}catch(Throwable t){return "";}}
    private static String modelKey(Entity entity) {
        return entity.getClass().getName() + "|"
                + renderState(entity, false);
    }

    private static String renderState(Entity entity, boolean includeDynamic) {
        return modelState(entity, includeDynamic) + "|" + infoState(entity, includeDynamic);
    }

    private static String infoState(Entity entity, boolean includeDynamic) {
        StringBuilder out = new StringBuilder();
        try {
            for (Method method : infoGetters(entity.getClass())) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                Object value = method.invoke(entity);
                if (value == null) {
                    out.append(name).append("=null;");
                    continue;
                }
                out.append(name).append('=').append(value.getClass().getName());
                for (Field field : infoFields(value.getClass())) {
                        if (!includeDynamic && dynamicStateName(field.getName())) continue;
                        try {
                            out.append(':').append(field.getName()).append('=')
                                    .append(cacheStateValue(field.getName(), field.get(value), includeDynamic));
                        }
                        catch (Throwable ignored) { }
                }
                out.append(';');
            }
        } catch (Throwable ignored) { }
        return out.toString();
    }

    private static String modelState(Entity entity, boolean includeDynamic) {
        StringBuilder out = new StringBuilder();
        for (Field f : stateFields(entity.getClass())) {
                if (!includeDynamic && dynamicStateName(f.getName())) continue;
                try {
                    out.append(f.getDeclaringClass().getName()).append('#').append(f.getName()).append('=')
                            .append(cacheStateValue(f.getName(), f.get(entity), includeDynamic)).append(';');
                } catch (Throwable ignored) {
                    // A private or transformed field is not a reason to fail a render.
                }
        }
        return out.toString();
    }

    private static Method[] infoGetters(Class<?> type) {
        Method[] cached = INFO_GETTERS.get(type);
        if (cached != null) return cached;
        List<Method> found = new ArrayList<Method>();
        try {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (method.getParameterTypes().length == 0 && method.getReturnType() != Void.TYPE
                        && name.startsWith("get")
                        && (name.endsWith("info") || name.endsWith("model") || name.endsWith("type"))) {
                    method.setAccessible(true);
                    found.add(method);
                }
            }
        } catch (Throwable ignored) { }
        Method[] result = found.toArray(new Method[found.size()]);
        Method[] previous = INFO_GETTERS.putIfAbsent(type, result);
        return previous == null ? result : previous;
    }

    private static Field[] stateFields(Class<?> type) {
        Field[] cached = STATE_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> found = new ArrayList<Field>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                for (Field field : cursor.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                            || !renderStateName(field.getName()) || !stateValueType(field.getType())) continue;
                    field.setAccessible(true);
                    found.add(field);
                }
            } catch (Throwable ignored) { }
        }
        Field[] result = found.toArray(new Field[found.size()]);
        Field[] previous = STATE_FIELDS.putIfAbsent(type, result);
        return previous == null ? result : previous;
    }

    private static Field[] infoFields(Class<?> type) {
        Field[] cached = INFO_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> found = new ArrayList<Field>();
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                for (Field field : cursor.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                            || !renderStateName(field.getName()) || !stateValueType(field.getType())) continue;
                    field.setAccessible(true);
                    found.add(field);
                }
            } catch (Throwable ignored) { }
        }
        Field[] result = found.toArray(new Field[found.size()]);
        Field[] previous = INFO_FIELDS.putIfAbsent(type, result);
        return previous == null ? result : previous;
    }

    private static boolean stateValueType(Class<?> type) {
        return type.isPrimitive() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type.isEnum();
    }

    private static boolean hasDynamicState(Entity entity) {
        return renderState(entity, true).length() != renderState(entity, false).length();
    }

    private static boolean entityMovingOrRidden(Entity entity) {
        if (entity == null) return false;
        return Math.abs(entity.field_70159_w) > 1.0e-5D
                || Math.abs(entity.field_70181_x) > 1.0e-5D
                || Math.abs(entity.field_70179_y) > 1.0e-5D
                || entity.field_70154_o != null
                || entity.field_70153_n != null;
    }

    private static boolean dynamicProbeDue(String key, long frame) {
        DynamicProbe probe = DYNAMIC_PROBES.get(key);
        return probe == null || probe.samples < DYNAMIC_PROBATION_FRAMES
                || frame - probe.lastProbe >= DYNAMIC_REPROBE_INTERVAL;
    }

    private static boolean dynamicProbeInProbation(String key) {
        DynamicProbe probe = DYNAMIC_PROBES.get(key);
        return probe == null || probe.samples < DYNAMIC_PROBATION_FRAMES;
    }

    private static boolean dynamicProbeMarkedDynamic(String key) {
        DynamicProbe probe = DYNAMIC_PROBES.get(key);
        return probe != null && probe.dynamic;
    }

    private static void noteDynamicProbe(String key, long frame, boolean changed) {
        DynamicProbe probe = DYNAMIC_PROBES.get(key);
        if (probe == null) {
            probe = new DynamicProbe();
            DYNAMIC_PROBES.put(key, probe);
        }
        if (probe.samples > 0 && changed) probe.dynamic = true;
        probe.samples = Math.min(DYNAMIC_PROBATION_FRAMES, probe.samples + 1);
        probe.lastProbe = frame;
        while (DYNAMIC_PROBES.size() > MAX_DYNAMIC_PROBES) {
            java.util.Iterator<Map.Entry<String, DynamicProbe>> it = DYNAMIC_PROBES.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    private static final class DynamicProbe {
        int samples;
        long lastProbe;
        boolean dynamic;
    }

    /**
     * Dynamic fields are animation inputs, not identities. Quantizing them avoids a new cache key
     * for every floating-point tick while retaining enough pose resolution for rotor/turret motion.
     */
    private static Object cacheStateValue(String name, Object value, boolean includeDynamic) {
        if (!includeDynamic || value == null || !dynamicStateName(name) || !(value instanceof Number)) {
            return value;
        }
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n)) return value;
        return Double.valueOf(Math.rint(n * 16.0d) / 16.0d);
    }

    private static boolean renderStateName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (genericAnimationFieldName(n)) return true;
        String[] tokens = {"rotor", "prop", "throttle", "turret", "wheel", "track", "anim",
                "spin", "gear", "flap", "wing", "door", "weapon", "damage", "model", "info",
                "seat", "uav", "fold", "canopy", "rudder", "elevator", "aileron", "brake",
                // types sharing one entity class (MCHeli mk15 vs s-75, both
                // MCH_EntityVehicle) collide on the class+animation-state key and render
                // each other's cached mesh. These name-ish info/model fields are stable
                // per type (displayName, category, kind, texture paths), so including
                // them only ever SPLITS keys, never merges: correctness strictly
                // improves, at bounded extra cache entries. Deliberately NOT in
                // dynamicStateName below, so they stay static-only and cannot flip the
                // static/dynamic classification (both sides gain them equally).
                "name", "title", "kind", "category", "directory", "texture", "skin",
                "variant"};
        for (String token : tokens) if (n.contains(token)) return true;
        return false;
    }

    private static boolean dynamicStateName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (genericAnimationFieldName(n)) return true;
        String[] tokens = {"rotor", "prop", "throttle", "turret", "wheel", "track", "anim", "spin",
                "gear", "flap", "wing", "door", "weapon", "damage", "seat", "uav", "fold",
                "canopy", "rudder", "elevator", "aileron", "brake"};
        for (String token : tokens) if (n.contains(token)) return true;
        return false;
    }

    /**
     * A few legacy renderers expose their animated joints as plain rotation/angle fields rather
     * than using a descriptive token such as rotor, wheel, or gear.  Keep those values out of the
     * static model key and in the per-frame render input without matching the host entity's
     * ordinary rotationYaw/rotationPitch fields.  This is deliberately name-based and
     * mod-neutral: it covers articulated parts, propeller angles, and roll channels discovered
     * through the same reflective boundary as the existing animation names.
     */
    static boolean genericAnimationFieldName(String n) {
        return "rotation".equals(n)
                || n.endsWith("rotation")
                || n.endsWith("rotationroll")
                || n.endsWith("angle");
    }
    private static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    private static void set(Class<?> c,Object target,String n,Object v)throws Exception{field(c,n).set(target,v);}
    private static void setStatic(Class<?> c,String n,Object v)throws Exception{field(c,n).set(null,v);}
    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> helper = Class.forName("dev.umb.legacy.legacyside.UmbUnsafe");
        Method method = helper.getDeclaredMethod("allocate", Class.class);
        method.setAccessible(true);
        return (T) method.invoke(null, type);
    }

    private static final class Capture {
        final String entityClass, stateKey, cacheKey, transformKey, meshKey;
        final boolean geometryEnabled;
        String probeKey;
        boolean probeSample;
        long probeFrame;
        final List<EntityRenderCapture.Draw> draws=new ArrayList<EntityRenderCapture.Draw>();
        final GlEmulationSession emulation;
        final ArrayDeque<double[]> stack=new ArrayDeque<double[]>(); final double[] m=identity();
        /** Tessellator.setTranslation/addTranslation offset, added to its vertices only. */
        double tessX, tessY, tessZ;
        List<Float> data; String texture; float u,v,nx,ny,nz; int mode; int ops,pushes,pops; boolean drawing;
        Capture(String c,String k,String cache,String transform,String mesh,boolean geometry){entityClass=c;stateKey=k;cacheKey=cache;transformKey=transform;meshKey=mesh;geometryEnabled=geometry;emulation=new GlEmulationSession(geometry);}
        void begin(int x){draw();mode=x;drawing=true;emulation.begin(x);}
        void vertex(double x,double y,double z,double uu,double vv){emulation.vertex(x,y,z,uu,vv);}
        void bindTexture(String id){texture=id;emulation.bindTexture(id);}
        void color(float r,float g,float b,float a){emulation.color(r,g,b,a);}
        void enable(int bit){emulation.enable(bit);}
        void disable(int bit){emulation.disable(bit);}
        void blendFunc(int source,int destination){emulation.blendFunc(source,destination);}
        void alphaFunc(int function,float reference){emulation.alphaFunc(function,reference);}
        void cullFace(int face){emulation.cullFace(face);}
        void depthMask(boolean on){emulation.depthMask(on);}
        void shadeModel(int model){emulation.shadeModel(model);}
        void activeTexture(int textureUnit){emulation.activeTexture(textureUnit);}
        int genLists(int count){return emulation.genLists(count);}
        void newList(int id){emulation.newList(id);}
        void endList(){emulation.endList();}
        void callList(int id){emulation.callList(id);}
        void draw(){if(!drawing)return;emulation.end();drawing=false;}
        float[] matrix(){float[] out=new float[16];for(int i=0;i<16;i++)out[i]=(float)m[i];return out;}
        void push(){emulation.pushMatrix();}
        void pop(){emulation.popMatrix();}
        void translate(double x,double y,double z){emulation.translate(x,y,z);}
        void scale(double x,double y,double z){emulation.scale(x,y,z);}
        void rotate(double a,double x,double y,double z){emulation.rotate(a,x,y,z);}
        void mul(double[] b){/* retained for ABI compatibility; matrix ownership is in the session */}
        double[] point(double x,double y,double z){return new double[]{m[0]*x+m[4]*y+m[8]*z+m[12],m[1]*x+m[5]*y+m[9]*z+m[13],m[2]*x+m[6]*y+m[10]*z+m[14]};}
        void finish(){draw();}
        EntityRenderCapture result(){
            GlEmulationSession.Mesh sealed = emulation.seal();
            draws.clear();
            for (GlEmulationSession.Draw d : sealed.draws) {
                // The host replays every draw into a QUADS buffer, so assemble the legacy
                // primitive (triangles, fans, strips, polygons) into quads here. Feeding raw
                // triangle vertices as quads joins unrelated vertices into torn, see-through faces.
                List<GlEmulationSession.Vertex> quads = asQuads(d.mode, d.vertices);
                // Transform-only replays intentionally carry no vertex payload. Passing null lets
                // Draw.owned use its canonical zero-length buffer instead of allocating one per
                // draw/frame; non-empty arrays remain owned by the capture until replay completes.
                float[] a = quads.isEmpty() ? null : new float[quads.size() * 8];
                int at = 0;
                double[] inverse = inverseAffine(d.matrix);
                double[] point = new double[3];
                double[] normal = new double[3];
                for (GlEmulationSession.Vertex v : quads) {
                    // GlEmulationSession records positions after applying the current
                    // legacy matrix.  Store the mesh in local space so a transform-only
                    // capture can reuse it with the current frame's matrix.  The host
                    // replay applies Draw.matrix exactly once (see LegacyEntityRenderer).
                    inversePoint(inverse, v.x, v.y, v.z, point);
                    inverseNormal(inverse, v.nx, v.ny, v.nz, normal);
                    a[at++] = (float) point[0]; a[at++] = (float) point[1]; a[at++] = (float) point[2];
                    a[at++] = v.u; a[at++] = v.v;
                    a[at++] = (float) normal[0]; a[at++] = (float) normal[1]; a[at++] = (float) normal[2];
                }
                boolean cull = d.state != null && d.state.enabledCaps.contains(Integer.valueOf(GL_CULL_FACE));
                draws.add(EntityRenderCapture.Draw.owned(d.texture, a, quads.size(), d.matrix, cull));
            }
            ops = sealed.matrixOps; pushes = sealed.pushes; pops = sealed.pops;
            EntityRenderCapture now = new EntityRenderCapture(entityClass,stateKey,true,ops,pushes,pops,draws);
            if (geometryEnabled && now.vertexCount() > 0) cachePut("mesh", meshKey, now);
            if (!geometryEnabled) now = mergeMesh(now, cacheGet("mesh", meshKey));
            EntityRenderCapture previous = cacheGet("last", cacheKey);
            cachePut("last", cacheKey, now);
            boolean same = previous != null && same(previous, now);
            EntityRenderCapture result = new EntityRenderCapture(entityClass, stateKey, !same, ops, pushes, pops, now.draws);
            cachePut("transform", transformKey, result);
            if (!result.animated) cachePut("static", cacheKey, result);
            return result;
        }
        /** GL primitive assembly to independent quads; degenerate quads carry triangles. */
        private static List<GlEmulationSession.Vertex> asQuads(int mode, List<GlEmulationSession.Vertex> v) {
            int n = v.size();
            if (n == 0 || mode == GlEmulationSession.GL_QUADS) {
                return n % 4 == 0 ? v : v.subList(0, n - n % 4);
            }
            List<GlEmulationSession.Vertex> out = new ArrayList<GlEmulationSession.Vertex>(n * 2);
            switch (mode) {
                case GlEmulationSession.GL_TRIANGLES:
                    for (int i = 0; i + 2 < n; i += 3) tri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                    break;
                case GlEmulationSession.GL_TRIANGLE_FAN:
                case 9: // GL_POLYGON: convex by specification, same assembly as a fan
                    for (int i = 1; i + 1 < n; i++) tri(out, v.get(0), v.get(i), v.get(i + 1));
                    break;
                case GlEmulationSession.GL_TRIANGLE_STRIP:
                    for (int i = 0; i + 2 < n; i++) {
                        if ((i & 1) == 0) tri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                        else tri(out, v.get(i + 1), v.get(i), v.get(i + 2));
                    }
                    break;
                case GlEmulationSession.GL_QUAD_STRIP:
                    for (int i = 0; i + 3 < n; i += 2) {
                        out.add(v.get(i)); out.add(v.get(i + 1)); out.add(v.get(i + 3)); out.add(v.get(i + 2));
                    }
                    break;
                default:
                    // Points and lines have no area in a quad buffer; drawing them as quads
                    // produces stray slivers, so they are dropped from the entity mesh.
                    break;
            }
            return out;
        }
        private static void tri(List<GlEmulationSession.Vertex> out, GlEmulationSession.Vertex a,
                                GlEmulationSession.Vertex b, GlEmulationSession.Vertex c) {
            out.add(a); out.add(b); out.add(c); out.add(c);
        }
        private EntityRenderCapture mergeMesh(EntityRenderCapture transforms, EntityRenderCapture mesh) {
            if (mesh == null || mesh.draws.size() != transforms.draws.size()) return transforms;
            List<EntityRenderCapture.Draw> merged = new ArrayList<EntityRenderCapture.Draw>();
            for (int i=0;i<transforms.draws.size();i++) {
                EntityRenderCapture.Draw t=transforms.draws.get(i), b=mesh.draws.get(i);
                merged.add(EntityRenderCapture.Draw.owned(t.texture == null ? b.texture : t.texture,
                        b.vertices, b.vertexCount, t.matrix, t.cull));
            }
            return new EntityRenderCapture(entityClass,stateKey,true,ops,pushes,pops,merged);
        }
        private static boolean same(EntityRenderCapture a, EntityRenderCapture b){
            if(a.draws.size()!=b.draws.size())return false;
            for(int i=0;i<a.draws.size();i++){EntityRenderCapture.Draw x=a.draws.get(i),y=b.draws.get(i);if(!java.util.Objects.equals(x.texture,y.texture)||x.vertexCount!=y.vertexCount||!java.util.Arrays.equals(x.matrix,y.matrix)||!java.util.Arrays.equals(x.vertices,y.vertices))return false;}
            return true;
        }
        static double[] identity(){double[] x=new double[16];x[0]=x[5]=x[10]=x[15]=1;return x;}

        private static void inversePoint(double[] inv, double x, double y, double z, double[] out) {
            out[0] = inv[0] * x + inv[4] * y + inv[8] * z + inv[12];
            out[1] = inv[1] * x + inv[5] * y + inv[9] * z + inv[13];
            out[2] = inv[2] * x + inv[6] * y + inv[10] * z + inv[14];
        }

        private static void inverseNormal(double[] inv, double x, double y, double z, double[] out) {
            double nx = inv[0] * x + inv[1] * y + inv[2] * z;
            double ny = inv[4] * x + inv[5] * y + inv[6] * z;
            double nz = inv[8] * x + inv[9] * y + inv[10] * z;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1.0e-12) { nx /= length; ny /= length; nz /= length; }
            out[0] = nx; out[1] = ny; out[2] = nz;
        }

        /** Inverse of the affine column-major matrix emitted by the GL emulator. */
        private static double[] inverseAffine(float[] m) {
            if (m == null || m.length < 16) return identity();
            double a = m[0], b = m[4], c = m[8];
            double d = m[1], e = m[5], f = m[9];
            double g = m[2], h = m[6], i = m[10];
            double det = a * (e * i - f * h) - b * (d * i - f * g)
                    + c * (d * h - e * g);
            if (Math.abs(det) < 1.0e-12) return identity();
            double id = 1.0 / det;
            double[] out = identity();
            out[0] = (e * i - f * h) * id;
            out[4] = (c * h - b * i) * id;
            out[8] = (b * f - c * e) * id;
            out[1] = (f * g - d * i) * id;
            out[5] = (a * i - c * g) * id;
            out[9] = (c * d - a * f) * id;
            out[2] = (d * h - e * g) * id;
            out[6] = (b * g - a * h) * id;
            out[10] = (a * e - b * d) * id;
            double tx = m[12], ty = m[13], tz = m[14];
            out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz);
            out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz);
            out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz);
            return out;
        }
    }
}
