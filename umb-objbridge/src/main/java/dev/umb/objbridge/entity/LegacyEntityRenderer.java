package dev.umb.objbridge.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.ObjLog;
import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.map.TexturePick;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

import java.lang.reflect.Field;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Generic 26.2 entity renderer. Geometry is submitted through the deferred collector, never GL11. */
public final class LegacyEntityRenderer extends EntityRenderer<Entity, LegacyEntityRenderer.State> {
    public static final class State extends EntityRenderState {
        String legacyClass;
        float yaw, pitch;
        Object hostEntity;
        float partial;
        /** Local player's camera mode when this entity is that player's vehicle, else -1. */
        int riderCameraMode = -1;
    }
    private final Map<String, LegacyEntityVisual> visuals;
    private final TextureAtlas atlas;

    public LegacyEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        visuals = LegacyEntityVisual.all();
        // by texture path: getAtlas(TextureAtlas.LOCATION_BLOCKS) threw "Invalid atlas id:
        // minecraft:textures/atlas/blocks.png", which aborted EVERY resource reload (entity renderers
        // are built inside it) -> all packs dropped -> black client. A renderer constructor must never
        // be able to do that: on any failure, draw nothing (counted in the log) instead.
        TextureAtlas a = null;
        try {
            a = context.getAtlas(net.minecraft.data.AtlasIds.BLOCKS);
        } catch (Throwable t) {
            System.out.println("[UMB-OBJBRIDGE] ENTITY-RENDERER atlas unavailable, legacy entities will not draw: " + t);
        }
        atlas = a;
    }

    @Override public State createRenderState() { return new State(); }

    /** Per legacy entity class: radius of the captured mesh around the entity origin, blocks. */
    private static final Map<String, Float> CULL_RADIUS = new ConcurrentHashMap<String, Float>();
    private static final Map<String, Integer> CULL_RADIUS_VERTICES = new ConcurrentHashMap<String, Integer>();

    /**
     * Frustum-test the mesh the legacy renderer actually draws, not the host hitbox: a 16-block
     * aircraft with a 3-block collision box otherwise vanishes whenever its hitbox leaves view.
     */
    @Override public boolean shouldRender(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                          double camX, double camY, double camZ) {
        String key = entity == null ? null : identity(entity);
        // A freshly spawned twin can render before its legacy id syncs; no radius yet.
        Float radius = key == null ? null : CULL_RADIUS.get(key);
        if (radius == null) return super.shouldRender(entity, frustum, camX, camY, camZ);
        double r = radius.doubleValue();
        return frustum.isVisible(new net.minecraft.world.phys.AABB(entity.getX() - r, entity.getY() - r,
                entity.getZ() - r, entity.getX() + r, entity.getY() + r, entity.getZ() + r));
    }

    private static void noteCullRadius(String entityKey, List<?> draws, int vertices) {
        if (entityKey == null || draws == null) return;
        Integer seen = CULL_RADIUS_VERTICES.get(entityKey);
        if (seen != null && seen.intValue() == vertices) return;
        float max = 0f;
        for (Object draw : draws) {
            try {
                DrawFields fields = draw == null ? null : drawFields(draw.getClass());
                if (fields == null) continue;
                float[] data = (float[]) fields.vertices.get(draw);
                float[] m = (float[]) fields.matrix.get(draw);
                int count = Math.min(fields.vertexCount.getInt(draw), data == null ? 0 : data.length / 8);
                for (int i = 0; i < count; i++) {
                    float x = data[i * 8], y = data[i * 8 + 1], z = data[i * 8 + 2];
                    if (m != null && m.length >= 16) {
                        float tx = m[0] * x + m[4] * y + m[8] * z + m[12];
                        float ty = m[1] * x + m[5] * y + m[9] * z + m[13];
                        float tz = m[2] * x + m[6] * y + m[10] * z + m[14];
                        x = tx; y = ty; z = tz;
                    }
                    float d = x * x + y * y + z * z;
                    if (Float.isFinite(d) && d > max) max = d;
                }
            } catch (Throwable ignored) { }
        }
        // Margin covers animation (rotors, gear) between re-measurements; cap absurd captures.
        float radius = Math.min((float) Math.sqrt(max) + 1.0f, 128.0f);
        CULL_RADIUS.put(entityKey, Float.valueOf(radius));
        CULL_RADIUS_VERTICES.put(entityKey, Integer.valueOf(vertices));
    }

    @Override public void extractRenderState(Entity entity, State state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.legacyClass = identity(entity);
        state.yaw = entity.getYRot();
        state.pitch = entity.getXRot();
        state.hostEntity = entity;
        state.partial = partialTick;
        state.riderCameraMode = riderCameraMode(entity);
    }

    /**
     * The legacy client renders the vehicle its own player rides under that player's camera
     * mode (renderers hide parts in first person). Returns the host camera in legacy
     * {@code GameSettings.thirdPersonView} terms (0 first person, 1 back, 2 front) when
     * {@code entity} carries the local player (directly or as its root vehicle), else -1.
     */
    private static int riderCameraMode(Entity entity) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.player == null || entity == null || entity == mc.player) return -1;
            if (mc.player.getVehicle() != entity && mc.player.getRootVehicle() != entity) return -1;
            return legacyCameraMode(mc.options.getCameraType());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int legacyCameraMode(net.minecraft.client.CameraType type) {
        if (type == null) return -1;
        if (type.isFirstPerson()) return 0;
        return type.isMirrored() ? 2 : 1;
    }

    /** Render-type slot for a legacy draw: GL_BLEND picks translucent, GL_CULL_FACE the cull variant. */
    static final int SLOT_CUTOUT = 0, SLOT_CUTOUT_CULL = 1, SLOT_TRANSLUCENT = 2, SLOT_TRANSLUCENT_CULL = 3;

    static int renderTypeSlot(boolean cull, boolean blend) {
        if (blend) return cull ? SLOT_TRANSLUCENT_CULL : SLOT_TRANSLUCENT;
        return cull ? SLOT_CUTOUT_CULL : SLOT_CUTOUT;
    }

    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector,
                                  CameraRenderState camera) {
        if (state.hostEntity != null) {
            if (submitCaptured(state, pose, collector)) return;
            // A live legacy twin must never silently fall back to a static render-map row. That
            // row is only a compatibility visual for entities without a live legacy handle; for
            // a twin it hides capture failures and freezes rotors, gear, turrets, and textures.
            renderPathLog(state.legacyClass, "capture-empty");
            return;
        }
        LegacyEntityVisual v = state.legacyClass == null ? null : visuals.get(state.legacyClass);
        if (v == null || v.texture() == null || (v.model() == null && v.boxes().isEmpty())) return;
        try {
            Identifier spriteId = Identifier.parse(sprite(v.texture()));
            if (atlas == null) return;
            TextureAtlasSprite sprite = atlas.getSprite(spriteId);
            if (sprite == null || sprite == atlas.missingSprite()) return;
            RenderType type = RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);
            pose.pushPose();
            try {
                pose.mulPose(new Quaternionf().rotateY((float) Math.toRadians(180.0f - state.yaw)));
                pose.mulPose(new Quaternionf().rotateX((float) Math.toRadians(state.pitch)));
                collector.submitCustomGeometry(pose, type, (p, out) -> {
                    if (v.model() != null) {
                        var quads = ObjBridge.quads(v.model(), sprite, new Fit(false, 1.0f), v.groups());
                        emit(quads, p, out, state.lightCoords);
                    } else {
                        for (var box : v.boxes()) emitBox(box, p, out, state.lightCoords);
                    }
                });
            } finally {
                pose.popPose();
            }
        } catch (Throwable t) {
            // Rendering must never poison the client render loop; the row remains an honest skip.
        }
    }

    /** The legacy-side cache owns the same payloads; this reference cache is independently bounded. */
    private static final java.util.Map<String, Object> CAPTURE_CACHE =
            new LinkedHashMap<String, Object>(128, 0.75f, true);
    private static final Map<Class<?>, CaptureFields> CAPTURE_FIELDS = new ConcurrentHashMap<Class<?>, CaptureFields>();
    private static final Map<Class<?>, DrawFields> DRAW_FIELDS = new ConcurrentHashMap<Class<?>, DrawFields>();
    private static final Map<String, TextureBinding> TEXTURE_TYPES = new ConcurrentHashMap<String, TextureBinding>();
    private static final Map<String, Long> TEXTURE_DIAG_NANOS = new ConcurrentHashMap<String, Long>();
    private static long captureCacheBytes;
    private static final int MAX_CAPTURE_CACHE_ENTRIES = 2048;
    private static final Map<String, Long> CAPTURE_LOG_NANOS = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> CAPTURE_BOUNDS_LOG_NANOS = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> RENDER_PATH_LOG_NANOS = new ConcurrentHashMap<String, Long>();
    private static final long CAPTURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static long profileEntities;
    private static long profileCaptureNanos;
    private static long profileRebuildNanos;
    private static long profileSubmitNanos;
    private static long profileWindowNanos;
    private static final Object PROFILE_LOCK = new Object();
    private static boolean submitCaptured(State state, PoseStack pose, SubmitNodeCollector collector) {
        String hostClass = state.hostEntity == null ? "" : state.hostEntity.getClass().getName();
        try {
            if (isRemoved(state.hostEntity)) {
                invalidateEntityCaptures(state.legacyClass);
                return false;
            }
            long captureStart = System.nanoTime();
            LegacyEntityCaptureClient.Result captureResult =
                    LegacyEntityCaptureClient.capture(state.hostEntity, state.partial, state.riderCameraMode);
            long captureNanos = System.nanoTime() - captureStart;
            Object capture = captureResult.capture;
            if (capture == null) {
                profile(captureNanos, 0L, 0L);
                captureLog(hostClass, 0, 0, captureResult.reason == null ? "unavailable" : captureResult.reason);
                return false;
            }
            long rebuildStart = System.nanoTime();
            CaptureFields capturedFields = captureFields(capture.getClass());
            if (capturedFields == null) throw new IllegalStateException("capture-fields-missing");
            java.util.List<?> draws = (java.util.List<?>) capturedFields.draws.get(capture);
            int vertices = 0;
            if (draws != null) {
                for (Object draw : draws) {
                    DrawFields fields = draw == null ? null : drawFields(draw.getClass());
                    if (fields != null) vertices += fields.vertexCount.getInt(draw);
                }
            }
            String captureEntity = String.valueOf(capturedFields.entityClass.get(capture));
            captureLog(captureEntity,
                    vertices, draws == null ? 0 : draws.size(), null);
            if (draws == null || draws.isEmpty()) {
                profile(captureNanos, System.nanoTime() - rebuildStart, 0L);
                return false;
            }
            captureBoundsLog(captureEntity, draws);
            noteCullRadius(state.legacyClass, draws, vertices);
            boolean animated = capturedFields.animated.getBoolean(capture);
            String cls = captureEntity;
            String key = cls + "|" + String.valueOf(capturedFields.stateKey.get(capture));
            if (!animated) {
                // Static captures are retained only in the byte-budgeted access-order LRU below.
                // The state key is model state, never an entity UUID or frame object.
                Object cached;
                synchronized (CAPTURE_CACHE) {
                    cached = CAPTURE_CACHE.get(key);
                    if (cached == null) {
                        putCapture(key, capture);
                        cached = capture;
                    }
                }
                if (cached != null) capture = cached;
                draws = (java.util.List<?>) capture.getClass().getField("draws").get(capture);
            }
            long rebuildNanos = System.nanoTime() - rebuildStart;
            long submitTotalNanos = 0L;
            int blendedDraws = 0, unlitDraws = 0;
            for (Object draw : draws) {
                DrawFields fields = drawFields(draw.getClass());
                if (fields == null) continue;
                String texture = (String) fields.texture.get(draw);
                float[] data = (float[]) fields.vertices.get(draw);
                float[] matrix = (float[]) fields.matrix.get(draw);
                int count = fields.vertexCount.getInt(draw);
                if (texture == null || data == null || count <= 0) continue;
                TextureBinding binding = textureBinding(texture);
                if (binding == null) continue;
                // Legacy draws recorded with GL_CULL_FACE on hide their back faces (a pilot
                // inside a hull sees the cockpit, not the hull's inner skin). Draws recorded with
                // GL_BLEND on were alpha-blended in 1.7.10 (canopy glass): a cutout type would
                // draw every partly transparent texel opaque and wall the pilot in.
                boolean cull = fields.cull != null && fields.cull.getBoolean(draw);
                boolean blend = fields.blend != null && fields.blend.getBoolean(draw)
                        && LegacyCaptureTextureResolver.needsBlending(binding.id);
                boolean lit = fields.lighting == null || fields.lighting.getBoolean(draw);
                if (blend) blendedDraws++;
                if (!lit) unlitDraws++;
                RenderType type = binding.type(renderTypeSlot(cull, blend));
                final float[] capturedVertices = data;
                final float[] capturedMatrix = matrix;
                final int vertexCount = count;
                final boolean capturedLit = lit;
                long submitStart = System.nanoTime();
                collector.submitCustomGeometry(pose, type, (p, out) -> emitCaptured(
                        capturedVertices, vertexCount, capturedMatrix, p, out, state.lightCoords, capturedLit));
                submitTotalNanos += System.nanoTime() - submitStart;
            }
            renderPathLog(captureEntity, "capture", " viewer=" + state.riderCameraMode
                    + " light=0x" + Integer.toHexString(state.lightCoords)
                    + " draws=" + draws.size() + " blend=" + blendedDraws + " unlit=" + unlitDraws);
            profile(captureNanos, rebuildNanos, submitTotalNanos);
            return true;
        } catch (Throwable failure) {
            profile(0L, 0L, 0L);
            captureLog(hostClass, 0, 0, failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void renderPathLog(String entity, String path) {
        renderPathLog(entity, path, "");
    }

    private static void renderPathLog(String entity, String path, String detail) {
        String key = entity == null ? "" : entity;
        long now = System.nanoTime();
        Long previous = RENDER_PATH_LOG_NANOS.putIfAbsent(key, now);
        if (previous != null && now - previous < CAPTURE_LOG_INTERVAL_NANOS) return;
        if (previous != null) RENDER_PATH_LOG_NANOS.put(key, now);
        ObjLog.loud("[VEHICLE-GL-EMU] entity=" + key + " path=" + path
                + " staticFallback=false" + detail);
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

    private static void putCapture(String key, Object capture) {
        Object old = CAPTURE_CACHE.remove(key);
        if (old != null) captureCacheBytes -= captureBytes(old);
        long size = captureBytes(capture);
        if (size > captureCacheBudgetBytes()) return;
        CAPTURE_CACHE.put(key, capture);
        captureCacheBytes += size;
        long budget = captureCacheBudgetBytes();
        java.util.Iterator<Map.Entry<String, Object>> it = CAPTURE_CACHE.entrySet().iterator();
        while ((captureCacheBytes > budget || CAPTURE_CACHE.size() > MAX_CAPTURE_CACHE_ENTRIES)
                && it.hasNext()) {
            Map.Entry<String, Object> eldest = it.next();
            captureCacheBytes -= captureBytes(eldest.getValue());
            it.remove();
        }
    }

    /** Explicit removal hook for integrations that remove a legacy entity outside rendering. */
    public static void invalidateEntityCaptures(String legacyClass) {
        if (legacyClass == null || legacyClass.isEmpty()) return;
        synchronized (CAPTURE_CACHE) {
            java.util.Iterator<Map.Entry<String,Object>> it = CAPTURE_CACHE.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String,Object> entry = it.next();
                if (entry.getKey().startsWith(legacyClass + "|")) {
                    captureCacheBytes -= captureBytes(entry.getValue());
                    it.remove();
                }
            }
            if (captureCacheBytes < 0L) captureCacheBytes = 0L;
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

    private static CaptureFields captureFields(Class<?> type) {
        CaptureFields cached = CAPTURE_FIELDS.get(type);
        if (cached != null) return cached == CaptureFields.MISSING ? null : cached;
        try {
            CaptureFields fields = new CaptureFields(type.getField("draws"),
                    type.getField("animated"), type.getField("entityClass"), type.getField("stateKey"));
            fields.draws.setAccessible(true);
            fields.animated.setAccessible(true);
            fields.entityClass.setAccessible(true);
            fields.stateKey.setAccessible(true);
            CaptureFields previous = CAPTURE_FIELDS.putIfAbsent(type, fields);
            return previous == null ? fields : previous;
        } catch (Throwable failure) {
            CAPTURE_FIELDS.putIfAbsent(type, CaptureFields.MISSING);
            return null;
        }
    }

    private static DrawFields drawFields(Class<?> type) {
        DrawFields cached = DRAW_FIELDS.get(type);
        if (cached != null) return cached == DrawFields.MISSING ? null : cached;
        try {
            DrawFields fields = new DrawFields(type.getField("texture"), type.getField("vertices"),
                    type.getField("matrix"), type.getField("vertexCount"));
            fields.texture.setAccessible(true);
            fields.vertices.setAccessible(true);
            fields.matrix.setAccessible(true);
            fields.vertexCount.setAccessible(true);
            fields.cull = optionalBoolean(type, "cull");
            fields.blend = optionalBoolean(type, "blend");
            fields.lighting = optionalBoolean(type, "lighting");
            DrawFields previous = DRAW_FIELDS.putIfAbsent(type, fields);
            return previous == null ? fields : previous;
        } catch (Throwable failure) {
            DRAW_FIELDS.putIfAbsent(type, DrawFields.MISSING);
            return null;
        }
    }

    /** Optional boolean Draw state; absent from older bridge copies. */
    private static Field optionalBoolean(Class<?> type, String name) {
        try {
            Field f = type.getField(name);
            if (f.getType() != boolean.class) return null;
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException olderBridge) {
            return null;
        }
    }

    private static TextureBinding textureBinding(String texture) {
        String normalized = normalizeTexture(texture);
        TextureBinding cached = TEXTURE_TYPES.get(normalized);
        if (cached != null) return cached;
        try {
            Identifier id = Identifier.parse(normalized);
            LegacyCaptureTextureResolver.ensure(id);
            // entityTranslucentCullItemTarget is 26.2's only culled translucent entity type
            // (pipeline entity_translucent_cull); plain entityTranslucent is built withCull(false).
            TextureBinding built = new TextureBinding(id, RenderTypes.entityCutout(id),
                    RenderTypes.entityCutoutCull(id), RenderTypes.entityTranslucent(id),
                    RenderTypes.entityTranslucentCullItemTarget(id));
            TextureBinding previous = TEXTURE_TYPES.putIfAbsent(normalized, built);
            Long previousDiag = TEXTURE_DIAG_NANOS.putIfAbsent(normalized, System.nanoTime());
            if (previousDiag == null) {
                ObjLog.loud("[CAPTURE-TEXTURE] raw=" + texture + " normalized=" + normalized
                        + " id=" + id + " binding=entityCutout");
            }
            return previous == null ? built : previous;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class TextureBinding {
        final Identifier id;
        final RenderType type;
        final RenderType cullType;
        final RenderType translucentType;
        final RenderType translucentCullType;
        TextureBinding(Identifier id, RenderType type, RenderType cullType,
                       RenderType translucentType, RenderType translucentCullType) {
            this.id = id; this.type = type; this.cullType = cullType;
            this.translucentType = translucentType; this.translucentCullType = translucentCullType;
        }
        RenderType type(int slot) {
            switch (slot) {
                case SLOT_CUTOUT_CULL: return cullType;
                case SLOT_TRANSLUCENT: return translucentType;
                case SLOT_TRANSLUCENT_CULL: return translucentCullType;
                default: return type;
            }
        }
    }

    private static final class CaptureFields {
        static final CaptureFields MISSING = new CaptureFields(null, null, null, null);
        final Field draws, animated, entityClass, stateKey;
        CaptureFields(Field draws, Field animated, Field entityClass, Field stateKey) {
            this.draws = draws; this.animated = animated; this.entityClass = entityClass; this.stateKey = stateKey;
        }
    }

    private static final class DrawFields {
        static final DrawFields MISSING = new DrawFields(null, null, null, null);
        final Field texture, vertices, matrix, vertexCount;
        /** Optional (absent from older bridge copies): legacy GL_CULL_FACE/GL_BLEND/GL_LIGHTING per draw. */
        Field cull, blend, lighting;
        DrawFields(Field texture, Field vertices, Field matrix, Field vertexCount) {
            this.texture = texture; this.vertices = vertices; this.matrix = matrix; this.vertexCount = vertexCount;
        }
    }

    private static long captureBytes(Object capture) {
        long bytes = 0L;
        if (capture == null) return bytes;
        try {
            List<?> draws = (List<?>) capture.getClass().getField("draws").get(capture);
            if (draws == null) return 0L;
            for (Object draw : draws) {
                if (draw == null) continue;
                float[] vertices = (float[]) draw.getClass().getField("vertices").get(draw);
                float[] matrix = (float[]) draw.getClass().getField("matrix").get(draw);
                if (vertices != null) bytes += 4L * vertices.length;
                if (matrix != null) bytes += 4L * matrix.length;
            }
        } catch (Throwable ignored) { }
        return bytes;
    }

    private static void profile(long captureNanos, long rebuildNanos, long submitNanos) {
        synchronized (PROFILE_LOCK) {
            profileEntities++;
            profileCaptureNanos += captureNanos;
            profileRebuildNanos += rebuildNanos;
            profileSubmitNanos += submitNanos;
            long now = System.nanoTime();
            if (profileWindowNanos == 0L) profileWindowNanos = now;
            if (now - profileWindowNanos >= CAPTURE_LOG_INTERVAL_NANOS) {
                double divisor = profileEntities == 0 ? 1.0 : profileEntities * 1_000_000.0;
                ObjLog.loud("[CAPTURE-PROFILE] entities=" + profileEntities
                        + " capture_ms=" + (profileCaptureNanos / divisor)
                        + " rebuild_ms=" + (profileRebuildNanos / divisor)
                        + " submit_ms=" + (profileSubmitNanos / divisor)
                        + " total_ms=" + ((profileCaptureNanos + profileRebuildNanos + profileSubmitNanos) / divisor)
                        + " cache_bytes=" + captureCacheBytes
                        + " cache_entries=" + CAPTURE_CACHE.size()
                        + " cache_budget=" + captureCacheBudgetBytes());
                profileEntities = 0L;
                profileCaptureNanos = 0L;
                profileRebuildNanos = 0L;
                profileSubmitNanos = 0L;
                profileWindowNanos = now;
            }
        }
    }

    private static void captureLog(String entity, int vertices, int draws, String status) {
        String key = entity == null ? "" : entity;
        long now = System.nanoTime();
        Long previous = CAPTURE_LOG_NANOS.putIfAbsent(key, now);
        if (previous != null && now - previous < CAPTURE_LOG_INTERVAL_NANOS) return;
        if (previous != null) CAPTURE_LOG_NANOS.put(key, now);
        String suffix = status == null ? "" : " status=" + status;
        ObjLog.loud("[CAPTURE] entity=" + key + " verts=" + vertices + " draws=" + draws + suffix);
    }

    /**
     * Reports the two coordinate spaces at the replay boundary.  Captures are stored in the
     * legacy renderer's local space and the draw matrix is applied exactly once below; this
     * diagnostic makes a missing scale/translation or a double application immediately visible
     * without retaining any geometry or depending on a particular mod's model format.
     */
    private static void captureBoundsLog(String entity, List<?> draws) {
        String key = entity == null ? "" : entity;
        long now = System.nanoTime();
        Long previous = CAPTURE_BOUNDS_LOG_NANOS.putIfAbsent(key, now);
        if (previous != null && now - previous < CAPTURE_LOG_INTERVAL_NANOS) return;
        if (previous != null) CAPTURE_BOUNDS_LOG_NANOS.put(key, now);
        float lminX = Float.POSITIVE_INFINITY, lminY = Float.POSITIVE_INFINITY,
                lminZ = Float.POSITIVE_INFINITY, lmaxX = Float.NEGATIVE_INFINITY,
                lmaxY = Float.NEGATIVE_INFINITY, lmaxZ = Float.NEGATIVE_INFINITY;
        float wminX = Float.POSITIVE_INFINITY, wminY = Float.POSITIVE_INFINITY,
                wminZ = Float.POSITIVE_INFINITY, wmaxX = Float.NEGATIVE_INFINITY,
                wmaxY = Float.NEGATIVE_INFINITY, wmaxZ = Float.NEGATIVE_INFINITY;
        int vertices = 0;
        boolean finite = true;
        int drawIndex = 0;
        if (draws != null) for (Object draw : draws) {
            if (draw == null) continue;
            try {
                DrawFields fields = drawFields(draw.getClass());
                if (fields == null) continue;
                float[] data = (float[]) fields.vertices.get(draw);
                float[] matrix = (float[]) fields.matrix.get(draw);
                int count = Math.min(fields.vertexCount.getInt(draw), data == null ? 0 : data.length / 8);
                vertices += count;
                float dminX = Float.POSITIVE_INFINITY, dminY = Float.POSITIVE_INFINITY,
                        dminZ = Float.POSITIVE_INFINITY, dmaxX = Float.NEGATIVE_INFINITY,
                        dmaxY = Float.NEGATIVE_INFINITY, dmaxZ = Float.NEGATIVE_INFINITY;
                float dwminX = Float.POSITIVE_INFINITY, dwminY = Float.POSITIVE_INFINITY,
                        dwminZ = Float.POSITIVE_INFINITY, dwmaxX = Float.NEGATIVE_INFINITY,
                        dwmaxY = Float.NEGATIVE_INFINITY, dwmaxZ = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < count; i++) {
                    int at = i * 8;
                    float x = data[at], y = data[at + 1], z = data[at + 2];
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        finite = false;
                        continue;
                    }
                    lminX = Math.min(lminX, x); lminY = Math.min(lminY, y); lminZ = Math.min(lminZ, z);
                    lmaxX = Math.max(lmaxX, x); lmaxY = Math.max(lmaxY, y); lmaxZ = Math.max(lmaxZ, z);
                    dminX = Math.min(dminX, x); dminY = Math.min(dminY, y); dminZ = Math.min(dminZ, z);
                    dmaxX = Math.max(dmaxX, x); dmaxY = Math.max(dmaxY, y); dmaxZ = Math.max(dmaxZ, z);
                    float tx = x, ty = y, tz = z;
                    if (matrix != null && matrix.length >= 16) {
                        tx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
                        ty = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
                        tz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
                    }
                    if (!Float.isFinite(tx) || !Float.isFinite(ty) || !Float.isFinite(tz)) {
                        finite = false;
                        continue;
                    }
                    wminX = Math.min(wminX, tx); wminY = Math.min(wminY, ty); wminZ = Math.min(wminZ, tz);
                    wmaxX = Math.max(wmaxX, tx); wmaxY = Math.max(wmaxY, ty); wmaxZ = Math.max(wmaxZ, tz);
                    dwminX = Math.min(dwminX, tx); dwminY = Math.min(dwminY, ty); dwminZ = Math.min(dwminZ, tz);
                    dwmaxX = Math.max(dwmaxX, tx); dwmaxY = Math.max(dwmaxY, ty); dwmaxZ = Math.max(dwmaxZ, tz);
                }
                String texture = String.valueOf(fields.texture.get(draw));
                ObjLog.loud("[CAPTURE-DRAW] entity=" + key + " draw=" + drawIndex
                        + " verts=" + count + " texture=" + texture
                        + " localMin=" + point(dminX, dminY, dminZ)
                        + " localMax=" + point(dmaxX, dmaxY, dmaxZ)
                        + " worldMin=" + point(dwminX, dwminY, dwminZ)
                        + " worldMax=" + point(dwmaxX, dwmaxY, dwmaxZ));
                drawIndex++;
            } catch (Throwable ignored) {
                finite = false;
                drawIndex++;
            }
        }
        if (vertices == 0) return;
        float localMax = Math.max(lmaxX - lminX, Math.max(lmaxY - lminY, lmaxZ - lminZ));
        float worldMax = Math.max(wmaxX - wminX, Math.max(wmaxY - wminY, wmaxZ - wminZ));
        boolean suspicious = !finite || !Float.isFinite(localMax) || !Float.isFinite(worldMax)
                || localMax > 64.0f || worldMax > 64.0f;
        ObjLog.loud("[CAPTURE-BOUNDS] entity=" + key + " verts=" + vertices
                + " localMin=" + point(lminX, lminY, lminZ)
                + " localMax=" + point(lmaxX, lmaxY, lmaxZ)
                + " worldMin=" + point(wminX, wminY, wminZ)
                + " worldMax=" + point(wmaxX, wmaxY, wmaxZ)
                + " suspicious=" + suspicious);
    }

    private static String point(float x, float y, float z) {
        return "(" + x + "," + y + "," + z + ")";
    }

    private static String normalizeTexture(String texture) {
        int colon = texture.indexOf(':');
        if (colon < 0) return texture;
        String ns = texture.substring(0, colon);
        String path = texture.substring(colon + 1);
        if (path.startsWith("textures/") == false) path = "textures/" + path;
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) path = path.substring(0, path.length() - 4);
        return ns + ":" + path;
    }

    /**
     * {@code lit=false} replays a draw made with GL_LIGHTING off. 1.7.10 then applied no
     * directional shading, only texture x color x lightmap. 26.2's entity shader always shades by
     * normal against the world-space LEVEL light directions (0.2,1,-0.7)/(-0.2,1,0.7), so a
     * world-up normal (bypassing the pose) yields the full diffuse factor (min(1, 0.4 + 0.6 *
     * 2 * 0.81) = 1): the draw keeps the lightmap, like 1.7.10, but loses the fake shading.
     */
    static final float[] UNLIT_NORMAL = {0f, 1f, 0f};

    private static void emitCaptured(float[] data, int count, float[] matrix,
                                     PoseStack.Pose pose, VertexConsumer out, int light, boolean lit) {
        int n = Math.min(count, data.length / 8);
        for (int i = 0; i < n; i++) {
            int k = i * 8;
            float x = data[k], y = data[k + 1], z = data[k + 2];
            float nx = data[k + 5], ny = data[k + 6], nz = data[k + 7];
            if (matrix != null && matrix.length >= 16) {
                float tx = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
                float ty = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
                float tz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14];
                float tnx = matrix[0] * nx + matrix[4] * ny + matrix[8] * nz;
                float tny = matrix[1] * nx + matrix[5] * ny + matrix[9] * nz;
                float tnz = matrix[2] * nx + matrix[6] * ny + matrix[10] * nz;
                float length = (float) Math.sqrt(tnx * tnx + tny * tny + tnz * tnz);
                if (length > 1.0e-6f) { tnx /= length; tny /= length; tnz /= length; }
                x = tx; y = ty; z = tz; nx = tnx; ny = tny; nz = tnz;
            }
            VertexConsumer v = out.addVertex(pose, x, y, z).setColor(0xFFFFFFFF)
                    .setUv(data[k + 3], data[k + 4])
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setLight(light);
            if (lit) v.setNormal(pose, nx, ny, nz);
            else v.setNormal(UNLIT_NORMAL[0], UNLIT_NORMAL[1], UNLIT_NORMAL[2]);
        }
    }

    private static void emitBox(LegacyEntityVisual.Box b, PoseStack.Pose pose, VertexConsumer out, int light) {
        float x=b.x()/16f, y=b.y()/16f, z=b.z()/16f, X=(b.x()+b.w())/16f, Y=(b.y()+b.h())/16f, Z=(b.z()+b.d())/16f;
        face(pose,out,light,x,y,z,X,y,z,X,Y,z,x,Y,z,0,0,-1);
        face(pose,out,light,X,y,Z,x,y,Z,x,Y,Z,X,Y,Z,0,0,1);
        face(pose,out,light,x,y,Z,x,y,z,x,Y,z,x,Y,Z,-1,0,0);
        face(pose,out,light,X,y,z,X,y,Z,X,Y,Z,X,Y,z,1,0,0);
        face(pose,out,light,x,Y,z,X,Y,z,X,Y,Z,x,Y,Z,0,1,0);
        face(pose,out,light,x,y,Z,X,y,Z,X,y,z,x,y,z,0,-1,0);
    }
    private static void face(PoseStack.Pose pose, VertexConsumer out, int light,
                             float x0,float y0,float z0,float x1,float y1,float z1,
                             float x2,float y2,float z2,float x3,float y3,float z3,
                             float nx,float ny,float nz) {
        float[][] p={{x0,y0,z0},{x1,y1,z1},{x2,y2,z2},{x3,y3,z3}};
        float[][] uv={{0,1},{1,1},{1,0},{0,0}};
        for(int i=0;i<4;i++) out.addVertex(pose,p[i][0],p[i][1],p[i][2]).setColor(0xFFFFFFFF)
                .setUv(uv[i][0],uv[i][1]).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,nx,ny,nz);
    }

    private static void emit(List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,
                             PoseStack.Pose pose, VertexConsumer out, int light) {
        for (var q : quads) {
            for (int i = 0; i < 4; i++) {
                var p = q.position(i); long uv = q.packedUV(i);
                out.addVertex(pose, p.x(), p.y(), p.z()).setColor(0xFFFFFFFF)
                        .setUv(Float.intBitsToFloat((int) (uv >>> 32)), Float.intBitsToFloat((int) uv))
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, q.direction().getStepX(), q.direction().getStepY(), q.direction().getStepZ());
            }
        }
    }

    private static String sprite(String texture) {
        String bare = texture.substring(texture.indexOf(':') + 1);
        if (bare.startsWith("textures/")) bare = bare.substring(9);
        if (bare.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) bare = bare.substring(0, bare.length() - 4);
        if (!bare.startsWith("models/")) bare = "models/" + TexturePick.RELOCATED + "/" + bare;
        return texture.substring(0, texture.indexOf(':')) + ":" + TexturePick.sanitize(bare);
    }

    private static String identity(Entity entity) {
        try {
            var id = entity.getClass().getMethod("legacyClassId").invoke(entity);
            if (id instanceof String s && !s.isEmpty()) return s;
        } catch (Throwable ignored) { }
        try {
            Field f = entity.getClass().getDeclaredField("handle"); f.setAccessible(true);
            Object handle = f.get(entity);
            if (handle != null) {
                var m = handle.getClass().getMethod("legacyEntityId");
                return m.invoke(handle).toString();
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
