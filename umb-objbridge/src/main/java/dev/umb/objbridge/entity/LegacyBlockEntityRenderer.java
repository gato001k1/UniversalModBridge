package dev.umb.objbridge.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.transform.PathClass;
import dev.umb.objbridge.transform.RenderFit;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.lang.reflect.Field;
import java.util.List;

/** Generic submit-based BER. A non-null legacy handle is the authoritative core-TE test. */
public final class LegacyBlockEntityRenderer implements BlockEntityRenderer<BlockEntity,LegacyBlockEntityRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        LegacyBlockVisual visual;
        boolean core;
        float facing;
        String id;
        float partial;
        Object hostBlockEntity;
        java.util.Map<String, Double> dyn = java.util.Map.of();
        /** Client-synced dispatch helper class, or null (union fallback). */
        String helper;
        /** Client-synced legacy metadata for prefix facing selection, or null. */
        Integer meta;
    }
    private static final java.util.Set<String> DIAG=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** One log line per (block id, reason): every silent early-return is now visible. */
    static final java.util.concurrent.atomic.AtomicLong EXTRACTS=new java.util.concurrent.atomic.AtomicLong(),CORES=new java.util.concurrent.atomic.AtomicLong(),SUBMITS=new java.util.concurrent.atomic.AtomicLong(),SUBMIT_CORE=new java.util.concurrent.atomic.AtomicLong();
    private static void tally(){long e=EXTRACTS.get();if(e%600==1)System.out.println("[UMB-OBJBRIDGE] BER-COUNTS extracts="+e+" coreTrue="+CORES.get()+" submits="+SUBMITS.get()+" submitsCoreWithVisual="+SUBMIT_CORE.get());}
    private static void diag(String id,String why){if(DIAG.add(id+'|'+why))System.out.println("[UMB-OBJBRIDGE] BER "+id+": "+why);}
    /**
     * Door-live lane: per-block last-reported dynamic-value key set, so the "channels=&lt;values&gt;"
     * line below fires once per distinct key set instead of every frame (values themselves change
     * every tick while a door slides - the static {@link #diag} dedup key can't cover that).
     */
    private static final java.util.Map<String,String> DYN_KEYS=new java.util.concurrent.ConcurrentHashMap<>();
    /** Culling pipeline for the rare draw whose prefix last enables culling (default: no-cull). */
    private static final RenderType CULL_RT = RenderTypes.entityCutoutCull(
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
    private static volatile boolean loggedSubmitError;
    /** P4 static TESR meshes; animated callbacks stay live until the renderer proves a stable key. */
    private static final java.util.Map<String,Object> EMU_STATIC =
            new java.util.LinkedHashMap<String,Object>(64, 0.75f, true);
    private static long EMU_STATIC_BYTES;
    private static final int MAX_EMU_STATIC_ENTRIES = 2048;
    private static final java.util.Map<Class<?>,CaptureFields> CAPTURE_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>,DrawFields> DRAW_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long PROFILE_WINDOW_END;
    private static volatile long PROFILE_NEXT_TOGGLE_CHECK;
    private static long PROFILE_CALLS, PROFILE_RECAPTURES, PROFILE_HITS;
    private static long PROFILE_CAPTURE_NANOS, PROFILE_TOTAL_NANOS;
    private static final Field HANDLE=handleField(); private final java.util.Map<String,LegacyBlockVisual> visuals; private final SpriteGetter sprites;
    public LegacyBlockEntityRenderer(BlockEntityRendererProvider.Context c){visuals=LegacyBlockVisual.all();sprites=c.sprites();}
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(BlockEntity be,State s,float partial,net.minecraft.world.phys.Vec3 camera,net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay){BlockEntityRenderState.extractBase(be,s,overlay);s.core=isCore(be);s.hostBlockEntity=be;String id=String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()));s.id=id;s.partial=partial;s.dyn=readDyn(be);s.helper=dispatchHelper(be);s.meta=readMeta(be);if(s.core){s.visual=LegacyBlockVisual.find(visuals,id,legacyBlockId(be.getBlockState().getBlock()));s.facing=facing(be.getBlockState());if(s.visual==null)diag(id,"core but no extracted visual row");}else diag(id,"not core on client (sync flag false)");}
    @Override public void submit(State s,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){if(submitCaptured(s,pose,collector))return;LegacyBlockVisual v=s.visual;if(!s.core||v==null||sprites==null)return;try{Identifier sid=Identifier.parse(sprite(v.texture()));TextureAtlasSprite sp=sprites.get(new SpriteId(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,sid));if(sp==null){diag(s.id,"sprite null "+sid);return;}if(sp.contents().name().getPath().contains("missingno"))diag(s.id,"sprite MISSING in block atlas: "+sid);diag(s.id,"drawing model="+v.model()+" sprite="+sid);RenderFit.Outcome o=RenderFit.forPath(ObjBridge.rendererTransforms(),v.rendererClass(),PathClass.WORLD,true);Fit fit=o.fit();RenderType rt=RenderTypes.entityCutout(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);pose.pushPose();pose.mulPose(new org.joml.Quaternionf().rotateY((float)Math.toRadians(s.facing)));if(submitDynamic(s,v,sp,rt,pose,collector)){pose.popPose();return;}collector.submitCustomGeometry(pose,rt,(p,out)->emit(ObjBridge.quads(v.model(),sp,fit,v.groups()),p,out,s.lightCoords));pose.popPose();}catch(Throwable t){if(!loggedSubmitError){loggedSubmitError=true;System.out.println("[UMB-OBJBRIDGE] BLOCK-ENTITY-RENDERER submit failed (logged once): "+t);}}}
    @Override public boolean shouldRenderOffScreen(){return true;} @Override public int getViewDistance(){return 256;}
    /** Client-safe core test: UmbLegacyBlockEntity.isLegacyCore() reads the server->client synced flag (the
     *  legacy TileHandle field only exists on the SERVER copy, so reading it here always said "not core"). */
    private static java.lang.reflect.Method IS_CORE; private static boolean isCore(BlockEntity b){try{if(IS_CORE==null)IS_CORE=b.getClass().getMethod("isLegacyCore");return (Boolean)IS_CORE.invoke(b);}catch(Throwable t){return false;}}
    private static java.lang.reflect.Method LEGACY_BLOCK_ID; private static String legacyBlockId(Object block){try{if(block==null)return null;if(LEGACY_BLOCK_ID==null){LEGACY_BLOCK_ID=block.getClass().getMethod("getLegacyId");LEGACY_BLOCK_ID.setAccessible(true);}Object id=LEGACY_BLOCK_ID.invoke(block);return id instanceof String?(String)id:null;}catch(Throwable t){return null;}}
    private static boolean hasHandle(BlockEntity b){try{return HANDLE.get(b)!=null;}catch(Throwable t){return false;}} private static Field handleField(){try{Field f=Class.forName("dev.umb.hostagent.content.UmbLegacyBlockEntity").getDeclaredField("handle");f.setAccessible(true);return f;}catch(Throwable t){return null;}}
    private static float facing(Object state){try{for(Object pv:(Iterable<?>)state.getClass().getMethod("getValues").invoke(state)){Object p=pv.getClass().getMethod("property").invoke(pv);String n=String.valueOf(p.getClass().getMethod("getName").invoke(p));if(n.toLowerCase(java.util.Locale.ROOT).contains("facing")){Object v=pv.getClass().getMethod("value").invoke(pv);String d=String.valueOf(v).toLowerCase(java.util.Locale.ROOT);return d.contains("east")?90:d.contains("south")?180:d.contains("west")?270:0;}}}catch(Throwable ignored){}return 0;}
    private static String sprite(String t){int c=t.indexOf(':');String ns=t.substring(0,c),p=t.substring(c+1);if(p.startsWith("textures/"))p=p.substring(9);if(p.endsWith(".png"))p=p.substring(0,p.length()-4);if(!p.startsWith("models/"))p="models/_umb/"+p;return ns+":"+p.toLowerCase(java.util.Locale.ROOT);}
    private static void emit(List<net.minecraft.client.resources.model.geometry.BakedQuad> qs,PoseStack.Pose p,VertexConsumer out,int l){for(var q:qs)for(int i=0;i<4;i++){var x=q.position(i);long uv=q.packedUV(i);out.addVertex(p,x.x(),x.y(),x.z()).setColor(0xFFFFFFFF).setUv(Float.intBitsToFloat((int)(uv>>>32)),Float.intBitsToFloat((int)uv)).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(l).setNormal(p,q.direction().getStepX(),q.direction().getStepY(),q.direction().getStepZ());}}

    /** Primary P4 path: run the registered legacy TESR, then submit its emulated draws. */
    static boolean shouldCaptureCore(boolean core) { return core; }

    private boolean submitCaptured(State s, PoseStack pose, SubmitNodeCollector collector) {
        // A block can have a client-side render state for a neighboring host block while the
        // legacy tile exists only at the authoritative core position.  Never ask the provider
        // to probe that visual-only state: doing so shifted y=65 cores to y=66/67 requests.
        if (!shouldCaptureCore(s.core) || s.hostBlockEntity == null) return false;
        final long totalStart = System.nanoTime();
        final boolean cacheEnabled = cacheEnabled();
        long captureNanos = 0L;
        boolean cacheHit = false;
        try {
            if (isRemoved(s.hostBlockEntity)) {
                invalidateBlockCaptures(s.id);
                return false;
            }
            String cacheKey = "p4|" + s.id + "|" + String.valueOf(s.meta);
            Object capture;
            synchronized (EMU_STATIC) { capture = cacheEnabled ? EMU_STATIC.get(cacheKey) : null; }
            cacheHit = capture != null;
            if (capture == null) {
                long captureStart = System.nanoTime();
                LegacyTileCaptureClient.Result result = LegacyTileCaptureClient.capture(s.hostBlockEntity, s.partial);
                captureNanos = System.nanoTime() - captureStart;
                if (result.capture == null) {
                    if (result.reason != null) diag(s.id, "GL-EMU unavailable " + result.reason);
                    return false;
                }
                capture = result.capture;
                CaptureFields fields = captureFields(capture.getClass());
                if (fields == null) return false;
                if (cacheEnabled && !fields.animated.getBoolean(capture)) {
                    Object existing;
                    synchronized (EMU_STATIC) {
                        existing = EMU_STATIC.get(cacheKey);
                        if (existing == null) {
                            putEmuCapture(cacheKey, capture);
                            existing = capture;
                        }
                    }
                    if (existing != null) capture = existing;
                    diag(s.id, "GL-EMU static-cache store");
                }
            } else {
                diag(s.id, "GL-EMU static-cache hit");
            }
            CaptureFields fields = captureFields(capture.getClass());
            if (fields == null) return false;
            java.util.List<?> draws = (java.util.List<?>) fields.draws.get(capture);
            int vertices = 0;
            if (draws != null) for (Object d : draws) {
                DrawFields drawFields = drawFields(d.getClass());
                if (drawFields != null) vertices += drawFields.vertexCount.getInt(d);
            }
            if (draws == null || draws.isEmpty() || vertices == 0) {
                diag(s.id, "GL-EMU empty renderer output");
                return false;
            }
            diag(s.id, "GL-EMU draws=" + draws.size() + " verts=" + vertices);
            for (Object draw : draws) {
                DrawFields drawFields = drawFields(draw.getClass());
                if (drawFields == null) continue;
                String texture = (String) drawFields.texture.get(draw);
                float[] data = (float[]) drawFields.vertices.get(draw);
                int count = drawFields.vertexCount.getInt(draw);
                if (texture == null || data == null || count <= 0) continue;
                Identifier id = Identifier.parse(normalizeCapturedTexture(texture));
                LegacyCaptureTextureResolver.ensure(id);
                RenderType type = drawFields.cull != null && drawFields.cull.getBoolean(draw)
                        ? RenderTypes.entityCutoutCull(id) : RenderTypes.entityCutout(id);
                final float[] captured = data;
                final int capturedCount = count;
                collector.submitCustomGeometry(pose, type, (p, out) -> emitCaptured(captured, capturedCount, p, out, s.lightCoords));
            }
            profile(cacheEnabled, cacheHit, captureNanos, System.nanoTime() - totalStart);
            return true;
        } catch (Throwable failure) {
            diag(s.id, "GL-EMU failed " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isRemoved(Object value) {
        if (value == null) return false;
        try {
            java.lang.reflect.Method method = value.getClass().getMethod("isRemoved");
            return Boolean.TRUE.equals(method.invoke(value));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void putEmuCapture(String key, Object capture) {
        Object old = EMU_STATIC.remove(key);
        if (old != null) EMU_STATIC_BYTES -= captureBytes(old);
        long size = captureBytes(capture);
        if (size > captureCacheBudgetBytes()) return;
        EMU_STATIC.put(key, capture);
        EMU_STATIC_BYTES += size;
        long budget = captureCacheBudgetBytes();
        java.util.Iterator<java.util.Map.Entry<String,Object>> it = EMU_STATIC.entrySet().iterator();
        while ((EMU_STATIC_BYTES > budget || EMU_STATIC.size() > MAX_EMU_STATIC_ENTRIES)
                && it.hasNext()) {
            java.util.Map.Entry<String,Object> eldest = it.next();
            EMU_STATIC_BYTES -= captureBytes(eldest.getValue());
            it.remove();
        }
    }

    /** Explicit removal hook for a block id whose client tile was unloaded or replaced. */
    public static void invalidateBlockCaptures(String blockId) {
        if (blockId == null || blockId.isEmpty()) return;
        synchronized (EMU_STATIC) {
            java.util.Iterator<java.util.Map.Entry<String,Object>> it = EMU_STATIC.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String,Object> entry = it.next();
                if (entry.getKey().contains("|" + blockId + "|")) {
                    EMU_STATIC_BYTES -= captureBytes(entry.getValue());
                    it.remove();
                }
            }
            if (EMU_STATIC_BYTES < 0L) EMU_STATIC_BYTES = 0L;
        }
    }

    private static long captureCacheBudgetBytes() {
        String property = System.getProperty("umb.p4.captureCacheBytes", "");
        try {
            if (!property.isEmpty()) return Math.max(16L * 1024L * 1024L,
                    Long.parseLong(property) / 4L);
        } catch (Throwable ignored) { }
        try {
            Class<?> settings = Class.forName("dev.umb.hostagent.content.UmbSettings");
            long configured = ((Number) settings.getMethod("captureCacheBytes").invoke(null)).longValue();
            return Math.max(16L * 1024L * 1024L, configured / 4L);
        } catch (Throwable ignored) { }
        return 64L * 1024L * 1024L;
    }

    private static long captureBytes(Object capture) {
        long bytes = 0L;
        if (capture == null) return bytes;
        try {
            CaptureFields fields = captureFields(capture.getClass());
            if (fields == null) return 0L;
            java.util.List<?> draws = (java.util.List<?>) fields.draws.get(capture);
            if (draws == null) return 0L;
            for (Object draw : draws) {
                DrawFields df = drawFields(draw.getClass());
                if (df == null) continue;
                float[] vertices = (float[]) df.vertices.get(draw);
                float[] matrix = (float[]) df.matrix.get(draw);
                if (vertices != null) bytes += 4L * vertices.length;
                if (matrix != null) bytes += 4L * matrix.length;
            }
        } catch (Throwable ignored) { }
        return bytes;
    }

    /**
     * Cache control is deliberately external to the renderer: pass -Dumb.p4.glcache=off, or
     * create P4-CACHE-OFF in the game working directory for a live A/B round.  The marker is
     * sampled only four times per second, so toggling it cannot turn the render loop into an IO
     * benchmark.  A one-second window reports capture versus total submit cost for TESRs.
     */
    private static boolean cacheEnabled() {
        String property = System.getProperty("umb.p4.glcache", "");
        if ("off".equalsIgnoreCase(property)) return false;
        if ("on".equalsIgnoreCase(property)) return true;
        long now = System.nanoTime();
        if (now >= PROFILE_NEXT_TOGGLE_CHECK) {
            PROFILE_CACHE_ENABLED = !new java.io.File(System.getProperty("user.dir", "."),
                    "P4-CACHE-OFF").isFile();
            PROFILE_NEXT_TOGGLE_CHECK = now + 250_000_000L;
        }
        return PROFILE_CACHE_ENABLED;
    }

    private static volatile boolean PROFILE_CACHE_ENABLED = true;

    private static synchronized void profile(boolean cache, boolean hit, long captureNanos,
                                             long totalNanos) {
        long now = System.nanoTime();
        if (PROFILE_WINDOW_END == 0L) PROFILE_WINDOW_END = now + 1_000_000_000L;
        PROFILE_CALLS++;
        if (hit) PROFILE_HITS++;
        else PROFILE_RECAPTURES++;
        PROFILE_CAPTURE_NANOS += captureNanos;
        PROFILE_TOTAL_NANOS += totalNanos;
        if (now < PROFILE_WINDOW_END) return;
        long cacheBytes;
        int cacheEntries;
        synchronized (EMU_STATIC) {
            cacheBytes = EMU_STATIC_BYTES;
            cacheEntries = EMU_STATIC.size();
        }
        System.out.println("[CAPTURE-PROFILE] scope=tesr cache=" + (cache ? "on" : "off")
                + " calls=" + PROFILE_CALLS + " recaptures=" + PROFILE_RECAPTURES
                + " hits=" + PROFILE_HITS + " capture_ms="
                + (PROFILE_CAPTURE_NANOS / 1_000_000.0) + " total_ms="
                + (PROFILE_TOTAL_NANOS / 1_000_000.0) + " avg_ms="
                + (PROFILE_TOTAL_NANOS / 1_000_000.0 / Math.max(1L, PROFILE_CALLS))
                + " cache_bytes=" + cacheBytes + " cache_entries=" + cacheEntries
                + " cache_budget=" + captureCacheBudgetBytes());
        PROFILE_CALLS = PROFILE_RECAPTURES = PROFILE_HITS = 0L;
        PROFILE_CAPTURE_NANOS = PROFILE_TOTAL_NANOS = 0L;
        PROFILE_WINDOW_END = now + 1_000_000_000L;
    }

    private static CaptureFields captureFields(Class<?> type) {
        CaptureFields cached = CAPTURE_FIELDS.get(type);
        if (cached != null) return cached == CaptureFields.MISSING ? null : cached;
        try {
            Field animated = type.getField("animated");
            Field draws = type.getField("draws");
            animated.setAccessible(true);
            draws.setAccessible(true);
            CaptureFields built = new CaptureFields(animated, draws);
            CaptureFields existing = CAPTURE_FIELDS.putIfAbsent(type, built);
            return existing == null ? built : existing;
        } catch (Throwable t) {
            CAPTURE_FIELDS.putIfAbsent(type, CaptureFields.MISSING);
            return null;
        }
    }

    private static DrawFields drawFields(Class<?> type) {
        DrawFields cached = DRAW_FIELDS.get(type);
        if (cached != null) return cached == DrawFields.MISSING ? null : cached;
        try {
            Field texture = type.getField("texture");
            Field vertices = type.getField("vertices");
            Field vertexCount = type.getField("vertexCount");
            Field matrix = type.getField("matrix");
            texture.setAccessible(true);
            vertices.setAccessible(true);
            vertexCount.setAccessible(true);
            matrix.setAccessible(true);
            DrawFields built = new DrawFields(texture, vertices, vertexCount, matrix);
            try {
                built.cull = type.getField("cull");
                if (built.cull.getType() != boolean.class) built.cull = null;
            } catch (NoSuchFieldException olderBridge) {
                built.cull = null;
            }
            DrawFields existing = DRAW_FIELDS.putIfAbsent(type, built);
            return existing == null ? built : existing;
        } catch (Throwable t) {
            DRAW_FIELDS.putIfAbsent(type, DrawFields.MISSING);
            return null;
        }
    }

    private static final class CaptureFields {
        static final CaptureFields MISSING = new CaptureFields(null, null);
        final Field animated;
        final Field draws;
        CaptureFields(Field animated, Field draws) {
            this.animated = animated;
            this.draws = draws;
        }
    }

    private static final class DrawFields {
        static final DrawFields MISSING = new DrawFields(null, null, null, null);
        final Field texture;
        final Field vertices;
        final Field vertexCount;
        final Field matrix;
        /** Optional (absent from older bridge copies): legacy GL_CULL_FACE state per draw. */
        Field cull;
        DrawFields(Field texture, Field vertices, Field vertexCount, Field matrix) {
            this.texture = texture;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.matrix = matrix;
        }
    }

    private static String normalizeCapturedTexture(String texture) {
        int colon = texture.indexOf(':');
        if (colon < 0) return texture;
        String ns = texture.substring(0, colon);
        String path = texture.substring(colon + 1);
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) path = path.substring(0, path.length() - 4);
        return ns + ":" + path;
    }

    private static void emitCaptured(float[] data, int count, PoseStack.Pose pose,
                                     VertexConsumer out, int light) {
        int n = Math.min(count, data.length / 8);
        for (int i = 0; i < n; i++) {
            int k = i * 8;
            out.addVertex(pose, data[k], data[k + 1], data[k + 2]).setColor(0xFFFFFFFF)
                    .setUv(data[k + 3], data[k + 4])
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setLight(light).setNormal(pose, data[k + 5], data[k + 6], data[k + 7]);
        }
    }

    // ------------------------------------------------------- moving-parts lane

    /**
     * Reads this block entity's synced dynamic fields through the host's frame channel
     * ({@code dev.umb.hostagent.content.DynFieldChannel#get}, same in-process map the GUI
     * lane reads for gauges). Reflection only, like {@link #isCore}: objbridge must not
     * compile against hostagent. Any failure (channel absent, block removed, no fields
     * synced for this block) yields an empty map, which makes every dynamic op skip and
     * the submit below fall back to the static pose - honest absence, never a guess.
     */
    private static java.lang.reflect.Method DYN_GET;
    private static java.util.Map<String, Double> readDyn(BlockEntity be) {
        try {
            if (DYN_GET == null) {
                java.lang.reflect.Method m = Class.forName("dev.umb.hostagent.content.DynFieldChannel")
                        .getDeclaredMethod("get", net.minecraft.core.BlockPos.class);
                m.setAccessible(true);
                DYN_GET = m;
            }
            Object snap = DYN_GET.invoke(null, be.getBlockPos());
            if (snap == null) return java.util.Map.of();
            Class<?> c = snap.getClass();
            String[] keys = (String[]) c.getField("keys").get(snap);
            double[] values = (double[]) c.getField("values").get(snap);
            boolean[] present = (boolean[]) c.getField("present").get(snap);
            if (keys == null || values == null || present == null) return java.util.Map.of();
            java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
            for (int i = 0; i < keys.length && i < values.length && i < present.length; i++) {
                if (present[i] && keys[i] != null) out.put(keys[i], values[i]);
            }
            return out;
        } catch (Throwable t) {
            return java.util.Map.of();
        }
    }

    /**
     * Per-group dynamic submit. Uses the sidecar's per-group op prefixes when at least one
     * group has a fully evaluable pose; groups the sidecar never saw (or whose mesh lacks
     * them) fall back to one static submit with today's representative fit. Returns false
     * when nothing dynamic applies, so the caller runs the exact pre-lane path instead.
     * The pose on entry already carries the block-facing rotation; each group pushes its
     * own scope. Never throws (constructor rule: a model/render method must never take the
     * resource reload down with it).
     */
    private static boolean submitDynamic(State s, LegacyBlockVisual v, TextureAtlasSprite sp,
                                         RenderType rt, PoseStack pose, SubmitNodeCollector collector) {
        // Tracks the round-3 prefix push across the early returns below (the per-group
        // pushes already scope themselves); the catch pops it too, so the pose stack can
        // never imbalance no matter which exit fires.
        final boolean[] prefixed = {false};
        try {
            // Door-live follow-up: when the tile resolved its render-dispatch helper, draw
            // exactly that renderer's draws (one door variant, not every same-TE helper at
            // once); otherwise the union fallback from the moving-parts lane still applies.
            String helper = s.helper;
            java.util.List<DynamicOps.Draw> draws;
            String drawsMode;
            if (helper != null) {
                draws = DynamicOps.draws(helper);
                drawsMode = "dispatch " + helper;
            } else {
                draws = DynamicOps.drawsUnion(v.rendererClass());
                drawsMode = "union fallback";
            }
            // Door-live round 3: the TESR-side base matrix (centering + meta-facing)
            // the dispatched helper assumed. Null unless a prefix row exists and (with a
            // facing map) the meta selects a case - never half-applied.
            org.joml.Matrix4f prefix = DynamicOps.prefixMatrix(v.rendererClass(), s.meta);
            String prefixNote = prefix != null ? "prefix T+F" : "prefix none";
            diagDyn(s.id, v.rendererClass(), drawsMode + " " + prefixNote, draws, s.dyn, s.partial);
            if (draws.isEmpty()) return false;
            if (prefix != null) {
                pose.pushPose();
                pose.mulPose(prefix);
                prefixed[0] = true;
            }
            dev.umb.objbridge.obj.ObjMesh mesh = ObjBridge.mesh(v.model());
            java.util.Set<String> meshGroups = mesh == null ? java.util.Set.of()
                    : mesh.groupsByName().keySet();
            long nowMillis = System.currentTimeMillis();
            if (!DynamicOps.hasDynamicPose(v.rendererClass(), s.dyn, s.partial, nowMillis)) return false;
            java.util.Set<String> drawnGroups = new java.util.LinkedHashSet<>();
            Fit dynFit = Fit.transformed(true, Fit.IDENTITY);
            int dynamicDraws = 0;
            for (DynamicOps.Draw d : draws) {
                if (!v.groups().contains(d.group())) continue;
                if (!meshGroups.contains(d.group())) {
                    diag(s.id, "dynamic group missing from mesh, skipped: " + d.group());
                    continue;
                }
                if (!DynamicOps.isFullyEvaluable(d.ops(), s.dyn, s.partial, nowMillis)) {
                    continue;
                }
                DynamicOps.FramePlan plan = DynamicOps.plan(d.ops(), s.dyn, s.partial, nowMillis);
                org.joml.Matrix4f m = plan.total();
                // Door-live follow-up (GL-state ops): a draw whose prefix last enables
                // culling submits under the culling pipeline; every other draw keeps the
                // established no-cull entityCutout (HBM disables culling for these renders,
                // and our pipeline already matches - byte-verified withCull(false)).
                net.minecraft.client.renderer.rendertype.RenderType drawRt =
                        (plan.cull() != null && plan.cull() != 0.0) ? CULL_RT : rt;
                final String g = d.group();
                pose.pushPose();
                try {
                    pose.mulPose(m);
                    java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> qs =
                            ObjBridge.bakeQuads(v.model(), sp, dynFit, List.of(g), v.groups()).quads();
                    if (!plan.clips().isEmpty()) {
                        qs = clipQuads(qs, plan.clips(), v.model(), List.of(g), dynFit, sp);
                    }
                    final java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> fq = qs;
                    collector.submitCustomGeometry(pose, drawRt,
                            (p, out) -> emit(fq, p, out, s.lightCoords));
                } finally {
                    pose.popPose();
                }
                drawnGroups.add(g);
                dynamicDraws++;
            }
            if (dynamicDraws == 0) return false;
            java.util.List<String> staticGroups = new java.util.ArrayList<>(v.groups());
            staticGroups.removeIf(drawnGroups::contains);
            if (!staticGroups.isEmpty()) {
                RenderFit.Outcome o = RenderFit.forPath(ObjBridge.rendererTransforms(),
                        v.rendererClass(), PathClass.WORLD, true);
                collector.submitCustomGeometry(pose, rt, (p, out) ->
                        emit(ObjBridge.quads(v.model(), sp, o.fit(), staticGroups), p, out, s.lightCoords));
            }
            diag(s.id, "dynamic draws=" + dynamicDraws + " static=" + staticGroups.size());
            return true;
        } catch (Throwable t) {
            diag(s.id, "dynamic submit failed, static fallback: " + t);
            return false;
        } finally {
            // the ONE place the round-3 prefix is popped: every exit (incl. the early
            // hasDynamicPose return that leaked it and crashed the client with
            // "Pose stack not empty", 2026-09-24) passes through here exactly once.
            if (prefixed[0]) pose.popPose();
        }
    }

    /**
     * Door-live follow-up (GL-state ops): replays legacy fixed-function clip planes as
     * per-quad clipping in fitted (baked-quad) units. Planes arrive in mesh-authored units
     * (see {@link DynamicOps.FramePlan}); the bake's affine map (mirrored, drift-guarded -
     * see {@link ClipPlanes}) carries them into the frame the quads live in. Unclipped
     * quads pass through by reference (zero garbage on the common path); straddlers are
     * fanned into degenerate quads (the codebase's own triangle idiom - see QuadBaker).
     * Anything malformed fails OPEN (original quads), never hides geometry on a guess.
     */
    private static java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> clipQuads(
            java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> qs,
            java.util.List<DynamicOps.ActiveClip> clips, String model, java.util.List<String> groups,
            Fit fit, TextureAtlasSprite sp) {
        try {
            dev.umb.objbridge.obj.ObjMesh mesh = ObjBridge.mesh(model);
            if (mesh == null || qs == null || qs.isEmpty() || clips == null || clips.isEmpty()) {
                return qs;
            }
            ClipPlanes.FitMap fm = ClipPlanes.fitMap(mesh, groups, fit);
            java.util.List<double[]> planes = new java.util.ArrayList<>(clips.size());
            for (DynamicOps.ActiveClip c : clips) {
                if (c == null || c.eq() == null) continue;
                double[] q = fm.mapPlane(c.eq());
                if (q != null) planes.add(q);
            }
            if (planes.isEmpty()) return qs;
            net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo material =
                    new net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo(
                            sp, net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT,
                            net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(),
                            -1, true, 0);
            java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> out =
                    new java.util.ArrayList<>(qs.size());
            for (net.minecraft.client.resources.model.geometry.BakedQuad q : qs) {
                if (q == null) continue;
                double[][] pos = new double[4][3];
                double[][] uv = new double[4][2];
                for (int i = 0; i < 4; i++) {
                    var p = q.position(i);
                    pos[i][0] = p.x(); pos[i][1] = p.y(); pos[i][2] = p.z();
                    long packed = q.packedUV(i);
                    uv[i][0] = Float.intBitsToFloat((int) (packed >>> 32));
                    uv[i][1] = Float.intBitsToFloat((int) packed);
                }
                ClipPlanes.Poly poly = ClipPlanes.clip(pos, uv, planes);
                if (poly.pos.length == 0) continue;
                if (poly.pos.length == 4 && sameRefs(poly.pos, pos)) {
                    out.add(q);
                    continue;
                }
                net.minecraft.core.Direction dir = q.direction();
                for (int t = 0; t + 2 < poly.pos.length; t++) {
                    out.add(tri(material, dir, poly, 0, t + 1, t + 2));
                }
            }
            return out;
        } catch (Throwable t) {
            return qs;
        }
    }

    /** Reference check: clipped output identical to input means "fully inside", keep original. */
    private static boolean sameRefs(double[][] a, double[][] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /** One clipped triangle as a degenerate quad (repeated 3rd vertex), atlas UVs repacked. */
    private static net.minecraft.client.resources.model.geometry.BakedQuad tri(
            net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo material,
            net.minecraft.core.Direction dir, ClipPlanes.Poly poly, int a, int b, int c) {
        int[] ix = {a, b, c, c};
        org.joml.Vector3f[] p = new org.joml.Vector3f[4];
        long[] u = new long[4];
        for (int k = 0; k < 4; k++) {
            double[] v = poly.pos[ix[k]];
            p[k] = new org.joml.Vector3f((float) v[0], (float) v[1], (float) v[2]);
            double[] t = poly.uv[ix[k]];
            u[k] = MeshBaker.packUv((float) t[0], (float) t[1]);
        }
        return new net.minecraft.client.resources.model.geometry.BakedQuad(
                p[0], p[1], p[2], p[3], u[0], u[1], u[2], u[3], dir, material);
    }

    /**
     * Door-live follow-up one-time diagnostic, distinguishing the three silent-static causes the
     * lead hit on 2026-09-24 (core synced, model drawn, but no {@code dynamic draws=} line):
     * no dynamic row at all (union draws empty), no synced values at this position
     * (channels never arrive), or the live values themselves. The values line re-fires on a
     * key-set change immediately, and on value changes throttled to one line per block id per
     * {@link #DYN_RELOG_MS} (a sliding door narrates its transit; a spinning radar stays
     * quiet after the first line), so a stuck animation shows one line while a dead channel
     * shows none.
     */
    private static final long DYN_RELOG_MS = 30000L;
    private static final java.util.Map<String, String> DYN_VALS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> DYN_RELOG = new java.util.concurrent.ConcurrentHashMap<>();
    private static void diagDyn(String id, String rendererClass, String drawsMode,
                                java.util.List<DynamicOps.Draw> draws,
                                java.util.Map<String, Double> dyn, float partial) {
        try {
            if (draws.isEmpty()) {
                diag(id, "no dynamic row for " + rendererClass
                        + " (nor any same-TE helper renderer)");
                return;
            }
            if (dyn == null || dyn.isEmpty()) {
                diag(id, "channels empty at this pos (" + drawsMode + " draws=" + draws.size()
                        + " for " + rendererClass + ", no synced values)");
                return;
            }
            java.util.List<String> keys = new java.util.ArrayList<>(dyn.keySet());
            java.util.Collections.sort(keys);
            String keySet = String.join(",", keys);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.size() && sb.length() < 300; i++) {
                if (i > 0) sb.append(',');
                sb.append(keys.get(i)).append('=').append(dyn.get(keys.get(i)));
            }
            String body = "channels " + sb + " (" + drawsMode + " draws=" + draws.size()
                    + " for " + rendererClass + ")";
            String prevKeys = DYN_KEYS.putIfAbsent(id, keySet);
            String prevBody = DYN_VALS.get(id);
            long now = System.currentTimeMillis();
            Long last = DYN_RELOG.get(id);
            boolean keysChanged = prevKeys != null && !prevKeys.equals(keySet);
            boolean valsChanged = prevBody == null || !prevBody.equals(sb.toString());
            if (prevKeys == null || keysChanged
                    || (valsChanged && (last == null || now - last > DYN_RELOG_MS))) {
                if (keysChanged) DYN_KEYS.put(id, keySet);
                DYN_VALS.put(id, sb.toString());
                DYN_RELOG.put(id, now);
                System.out.println("[UMB-OBJBRIDGE] BER " + id + ": " + body
                        + (prevKeys != null && !keysChanged && valsChanged ? " [values changed]" : "")
                        + poseNote(draws, dyn, partial));
            }
        } catch (Throwable t) {
            // diagnostics must never break a render
        }
    }

    /**
     * Door-live round 6: per-draw submitted offsets, appended to the throttled values
     * line above (never every frame). Lets a "which panel went where" question be
     * answered from the log alone: each draw's group plus its replayed translation in
     * draw-local space (the shared entry/prefix pose applies equally on top, so equal
     * draws stay comparable). Never throws, never affects rendering.
     */
    private static String poseNote(java.util.List<DynamicOps.Draw> draws,
                                   java.util.Map<String, Double> dyn, float partial) {
        try {
            StringBuilder sb = new StringBuilder(" poses{");
            boolean first = true;
            long nowMillis = System.currentTimeMillis();
            for (DynamicOps.Draw d : draws) {
                if (d == null || d.group() == null) continue;
                if (!DynamicOps.isFullyEvaluable(d.ops(), dyn, partial, nowMillis)) continue;
                org.joml.Matrix4f m = DynamicOps.poseFor(d.ops(), dyn, partial, nowMillis);
                if (!first) sb.append(' ');
                first = false;
                sb.append(d.group()).append("=(")
                        .append(round1(m.m30())).append(',')
                        .append(round1(m.m31())).append(',')
                        .append(round1(m.m32())).append(')');
            }
            sb.append('}');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String round1(float v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == 0.0) r = 0.0;
        return Double.toString(r);
    }

    /**
     * Door-live follow-up: the client-synced render-dispatch helper class for this block
     * entity ({@code UmbLegacyBlockEntity.dispatchRenderer}, resolved server-side through
     * the sidecar's dispatch descriptor). Reflection only, like {@link #isCore}. Null when
     * the block has no dispatch or the tag has not arrived - the union fallback applies.
     */
    private static java.lang.reflect.Method DISPATCH_RENDERER;
    private static String dispatchHelper(BlockEntity be) {
        try {
            if (DISPATCH_RENDERER == null) {
                java.lang.reflect.Method m = be.getClass().getMethod("dispatchRenderer");
                if (!String.class.equals(m.getReturnType())) return null;
                DISPATCH_RENDERER = m;
            }
            Object out = DISPATCH_RENDERER.invoke(be);
            if (out instanceof String) return (String) out;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Door-live round 3: the client-synced legacy metadata for prefix facing selection
     * ({@code UmbLegacyBlockEntity.legacyMetaForRender}, Integer.MIN_VALUE when never
     * synced). Reflection only, like {@link #isCore}. Null when unreadable - the prefix
     * then stays off (both-or-nothing with a facing map).
     */
    private static java.lang.reflect.Method LEGACY_META;
    private static Integer readMeta(BlockEntity be) {
        try {
            if (LEGACY_META == null) {
                java.lang.reflect.Method m = be.getClass().getMethod("legacyMetaForRender");
                if (!int.class.equals(m.getReturnType())) return null;
                LEGACY_META = m;
            }
            Object out = LEGACY_META.invoke(be);
            if (out instanceof Integer) {
                int v = ((Integer) out).intValue();
                return v == Integer.MIN_VALUE ? null : Integer.valueOf(v);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
