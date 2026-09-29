package dev.umb.legacy1165.legacyside;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.umb.bridge.api.EntityRenderCapture;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3d;

/**
 * Native-free 1.16.5 counterpart of the 1.7.10 entity capture runner.
 *
 * <p>The 1.16 renderer contract is already a data path: an EntityRenderer writes to an
 * IVertexBuilder under a MatrixStack.  We give the real renderer a recording
 * IRenderTypeBuffer, retain the matrix active at each draw group, and return only the
 * data-only bridge API object.  No RenderSystem, OpenGL, window, or BufferBuilder is
 * touched here.  The class deliberately knows nothing about Alex's Mobs (or any other
 * mod); renderer lookup is through the era's EntityRendererManager.</p>
 */
public final class LegacyEntityRenderCapture1165Client {
    private static final int FULL_BRIGHT = 15728880;
    private static final Object INSTALL_LOCK = new Object();
    private static volatile EntityRendererManager manager;
    private static volatile String unavailable;

    private LegacyEntityRenderCapture1165Client() {
    }

    /** Bind the real 1.16.5 client dispatcher when its client facade is ready. */
    public static void install(EntityRendererManager next) {
        synchronized (INSTALL_LOCK) {
            manager = next;
            unavailable = next == null ? "entity-renderer-manager-null" : null;
            LegacyEntityCapture1165.install(next == null ? null : new LegacyEntityCapture1165.Provider() {
                @Override
                public EntityRenderCapture capture(Entity entity, float partialTick) {
                    return captureWith(next, entity, partialTick);
                }
            });
        }
    }

    /**
     * Lazy client-facade hook.  It is safe to call from a render-thread capture attempt: on a
     * dedicated/server-only 1.16 universe Minecraft's singleton is absent and the diagnostic
     * remains an explicit unavailable reason instead of a provider-missing empty mesh.
     */
    public static boolean installFromMinecraft() {
        synchronized (INSTALL_LOCK) {
            // LegacyEntityCapture1165.clear() is used during world/facade rebinds.  Reinstall
            // the provider over an already-known dispatcher instead of falsely reporting that
            // the client is bound while the provider slot is still empty.
            if (manager != null) {
                install(manager);
                return true;
            }
            try {
                Minecraft minecraft = Minecraft.func_71410_x();
                if (minecraft == null) {
                    unavailable = "minecraft-singleton-null";
                    return false;
                }
                install(minecraft.func_175598_ae());
                return manager != null;
            } catch (Throwable failure) {
                unavailable = reason(failure);
                System.err.println("[UMB-ENTITY-1165] client renderer unavailable: " + unavailable);
                return false;
            }
        }
    }

    public static String unavailableReason() {
        return unavailable == null ? "" : unavailable;
    }

    public static EntityRenderCapture capture(Entity entity, float partialTick) {
        EntityRendererManager current = manager;
        if (current == null && !installFromMinecraft()) {
            String id = unavailableReason();
            return EntityRenderCapture.empty(entity == null ? "" : entity.getClass().getName(),
                    "1165-client-renderer-unavailable:" + id);
        }
        current = manager;
        if (entity == null || current == null) {
            return EntityRenderCapture.empty(entity == null ? "" : entity.getClass().getName(),
                    "1165-client-renderer-unavailable:entity-or-manager-null");
        }
        try {
            return captureWith(current, entity, partialTick);
        } catch (Throwable failure) {
            unavailable = reason(failure);
            return EntityRenderCapture.empty(entity.getClass().getName(),
                    "1165-client-renderer-threw:" + unavailable);
        }
    }

    private static EntityRenderCapture captureWith(EntityRendererManager current, Entity entity,
            float partialTick) {
        if (entity == null) {
            return EntityRenderCapture.empty("", "1165-client-entity-null");
        }
        EntityRenderer<?> renderer = current.func_78713_a(entity);
        if (renderer == null) {
            return EntityRenderCapture.empty(entity.getClass().getName(),
                    "1165-renderer-missing:" + entity.getClass().getName());
        }
        String texture = texture(renderer, entity);
        syncRenderPartialTicks(partialTick);
        // A real frame installs the camera (cacheActiveRenderInfo) before any entity renders;
        // renderers ask the manager for the camera distance (EntityRenderer.renderName ->
        // squareDistanceTo -> info.getProjectedView).
        if (current.field_217783_c == null) {
            current.field_217783_c = new net.minecraft.client.renderer.ActiveRenderInfo();
        }
        placeCaptureCamera(current.field_217783_c, entity, partialTick);
        MatrixStack stack = new MatrixStack();
        RecordingBuffer buffer = new RecordingBuffer(stack, texture);
        // Reproduce only the geometry preamble from EntityRendererManager: apply the
        // renderer's camera offset and invoke EntityRenderer.render().  The dispatcher also
        // has optional shadow/nameplate paths; bypassing those keeps this capture strictly
        // native-free.  The host replay adds the twin's world position afterwards.
        @SuppressWarnings({"rawtypes", "unchecked"})
        EntityRenderer raw = (EntityRenderer) renderer;
        Vector3d offset = raw.func_225627_b_(entity, partialTick);
        stack.func_227860_a_();
        try {
            if (offset != null) {
                stack.func_227861_a_(offset.func_82615_a(), offset.func_82617_b(),
                        offset.func_82616_c());
            }
            raw.func_225623_a_(entity, entity.field_70177_z, partialTick, stack, buffer,
                    FULL_BRIGHT);
        } finally {
            stack.func_227865_b_();
        }
        List<EntityRenderCapture.Draw> draws = buffer.finish();
        return new EntityRenderCapture(entity.getClass().getName(),
                "1165-renderer:" + renderer.getClass().getName(), true,
                buffer.matrixOps, buffer.pushes, buffer.pops, draws);
    }

    /** Vanilla's name-tag range is 64 blocks (32 sneaking); the capture camera sits twice that away. */
    static final double CAPTURE_CAMERA_DISTANCE = 128.0D;

    /**
     * Puts the capture camera {@link #CAPTURE_CAMERA_DISTANCE} straight above the captured entity.
     * A capture is camera-independent (cached, shared by every viewer) and name tags are drawn
     * by the host, so the distance-gated name-tag branch must never run here: within range,
     * LivingRenderer.canRenderName goes on to read Minecraft.player, which this headless
     * client does not have (live: an unpositioned camera sits at the world origin, so every
     * mob within 64 blocks of (0,0,0) NPE'd there and captured nothing, while mobs farther away
     * rendered). Relative to the entity, the result no longer depends on where it stands.
     * Uses the protected setPosition (func_216775_b), which also keeps the block position.
     */
    static void placeCaptureCamera(net.minecraft.client.renderer.ActiveRenderInfo info, Entity entity,
            float partialTick) {
        if (info == null || entity == null) return;
        try {
            double p = Float.isFinite(partialTick) ? Math.max(0.0D, Math.min(1.0D, partialTick)) : 1.0D;
            double x = entity.field_70169_q + (entity.func_226277_ct_() - entity.field_70169_q) * p;
            double y = entity.field_70167_r + (entity.func_226278_cu_() - entity.field_70167_r) * p;
            double z = entity.field_70166_s + (entity.func_226281_cx_() - entity.field_70166_s) * p;
            java.lang.reflect.Method setPosition = net.minecraft.client.renderer.ActiveRenderInfo.class
                    .getDeclaredMethod("func_216775_b", double.class, double.class, double.class);
            setPosition.setAccessible(true);
            setPosition.invoke(info, x, y + CAPTURE_CAMERA_DISTANCE, z);
        } catch (Throwable ignored) {
            // Mapping mismatch: the camera keeps its previous position (renderers still run).
        }
    }

    /**
     * A real frame renders with Minecraft's timer at the frame's partial tick, and models read
     * it back (Minecraft.getRenderPartialTicks) to interpolate animations. Keep the facade timer
     * equal to the capture's partial tick. Best effort: no instance or no timer leaves it alone.
     */
    static void syncRenderPartialTicks(float partialTick) {
        try {
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft == null) return;
            java.lang.reflect.Field timerField = Minecraft.class.getDeclaredField("field_71428_T");
            timerField.setAccessible(true);
            Object timer = timerField.get(minecraft);
            if (timer instanceof net.minecraft.util.Timer) {
                ((net.minecraft.util.Timer) timer).field_194147_b =
                        Float.isFinite(partialTick) ? partialTick : 0.0F;
            }
        } catch (Throwable ignored) {
            // A mapping without the timer field keeps whatever the facade holds.
        }
    }

    private static String texture(EntityRenderer<?> renderer, Entity entity) {
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            EntityRenderer raw = (EntityRenderer) renderer;
            ResourceLocation id = (ResourceLocation) raw.func_110775_a(entity);
            return id == null ? "minecraft:missingno" : id.toString();
        } catch (Throwable ignored) {
            return "minecraft:missingno";
        }
    }

    private static String reason(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String name = current.getClass().getName();
        String message = current.getMessage();
        return message == null || message.isEmpty() ? name : name + ":" + message;
    }

    /** IRenderTypeBuffer + IVertexBuilder recorder; it never calls a native render backend. */
    static final class RecordingBuffer implements IRenderTypeBuffer {
        private final MatrixStack stack;
        private final String defaultTexture;
        private final Map<RenderType, RecordingVertexBuilder> builders =
                new LinkedHashMap<RenderType, RecordingVertexBuilder>();
        int matrixOps;
        int pushes;
        int pops;

        RecordingBuffer(MatrixStack stack, String defaultTexture) {
            this.stack = stack;
            this.defaultTexture = defaultTexture;
        }

        @Override
        public IVertexBuilder getBuffer(RenderType type) {
            RecordingVertexBuilder result = builders.get(type);
            if (result == null) {
                result = new RecordingVertexBuilder(stack, texture(type, defaultTexture), this);
                builders.put(type, result);
            }
            return result;
        }

        List<EntityRenderCapture.Draw> finish() {
            List<EntityRenderCapture.Draw> result = new ArrayList<EntityRenderCapture.Draw>();
            for (RecordingVertexBuilder builder : builders.values()) result.addAll(builder.finish());
            return result;
        }

        private static String texture(RenderType type, String fallback) {
            // Most vanilla/mod entity RenderTypes retain their ResourceLocation in the render
            // state.  Keep this conservative: if a mod renderer uses a private state shape,
            // the renderer's own texture remains the correct universal fallback.
            if (type != null) {
                ResourceLocation found = findResource(type, 0);
                if (found != null) return found.toString();
            }
            return fallback == null ? "minecraft:missingno" : fallback;
        }

        private static ResourceLocation findResource(Object value, int depth) {
            if (value == null || depth > 4) return null;
            if (value instanceof ResourceLocation) return (ResourceLocation) value;
            Class<?> type = value.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        Object child = field.get(value);
                        ResourceLocation found = findResource(child, depth + 1);
                        if (found != null) return found;
                    } catch (Throwable ignored) {
                        // Render-state implementations are private; fallback is intentional.
                    }
                }
                type = type.getSuperclass();
            }
            return null;
        }
    }

    static final class RecordingVertexBuilder implements IVertexBuilder {
        private final MatrixStack stack;
        private final String texture;
        private final RecordingBuffer owner;
        private final List<Segment> segments = new ArrayList<Segment>();
        private Segment current;
        private float x, y, z, u, v, nx, ny, nz;
        private boolean hasPosition;

        RecordingVertexBuilder(MatrixStack stack, String texture, RecordingBuffer owner) {
            this.stack = stack;
            this.texture = texture;
            this.owner = owner;
        }

        @Override
        public IVertexBuilder func_225582_a_(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            this.hasPosition = true;
            float[] matrix = matrix(stack);
            if (current == null || !Arrays.equals(current.matrix, matrix)) {
                current = new Segment(matrix);
                segments.add(current);
            }
            return this;
        }

        @Override public IVertexBuilder func_225586_a_(int r, int g, int b, int a) { return this; }
        @Override public IVertexBuilder func_225583_a_(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public IVertexBuilder func_225585_a_(int u, int v) { return this; }
        @Override public IVertexBuilder func_225587_b_(int u, int v) { return this; }
        @Override public IVertexBuilder func_225584_a_(float nx, float ny, float nz) {
            this.nx = nx; this.ny = ny; this.nz = nz; return this;
        }

        @Override
        public void func_181675_d() {
            if (hasPosition && current != null) {
                current.values.add(x); current.values.add(y); current.values.add(z);
                current.values.add(u); current.values.add(v);
                current.values.add(nx); current.values.add(ny); current.values.add(nz);
            }
            hasPosition = false;
        }

        List<EntityRenderCapture.Draw> finish() {
            List<EntityRenderCapture.Draw> result = new ArrayList<EntityRenderCapture.Draw>();
            for (Segment segment : segments) {
                if (segment.values.isEmpty()) continue;
                float[] data = new float[segment.values.size()];
                for (int i = 0; i < data.length; i++) data[i] = segment.values.get(i);
                // 1.16 writers hand the builder vertices the MatrixStack has ALREADY
                // transformed (ModelRenderer transforms each vertex and normal itself;
                // IVertexBuilder.pos(Matrix4f, ...) transforms before pos(double)). The
                // capture starts from an identity stack, so the data is final entity/block
                // space: ship an identity matrix, or the host would transform posed parts twice.
                addDraws(result, texture, data);
            }
            return result;
        }

        /** Emits {@code data}, splitting atlas-bound quads into per-sprite draws. */
        static void addDraws(List<EntityRenderCapture.Draw> out, String texture, float[] data) {
            if (!HeadlessAtlas1165.isHeadlessAtlas(texture)) {
                out.add(EntityRenderCapture.Draw.owned(texture, data, data.length / 8, null));
                return;
            }
            // UVs index the headless atlas sheet, which exists only inside this universe:
            // re-express each quad against its sprite's own PNG with 0..1 UVs.
            Map<String, List<Float>> bySprite = new LinkedHashMap<String, List<Float>>();
            int vertices = data.length / 8;
            int group = vertices % 4 == 0 ? 4 : 1;
            for (int first = 0; first + group <= vertices; first += group) {
                float cu = 0f, cv = 0f;
                for (int k = 0; k < group; k++) {
                    cu += data[(first + k) * 8 + 3];
                    cv += data[(first + k) * 8 + 4];
                }
                cu /= group;
                cv /= group;
                Object[] probe = HeadlessAtlas1165.spriteUv(texture, cu, cv, cu, cv);
                String target = probe == null ? texture : (String) probe[0];
                List<Float> values = bySprite.get(target);
                if (values == null) {
                    values = new ArrayList<Float>();
                    bySprite.put(target, values);
                }
                for (int k = 0; k < group; k++) {
                    int at = (first + k) * 8;
                    float u = data[at + 3], v = data[at + 4];
                    if (probe != null) {
                        Object[] local = HeadlessAtlas1165.spriteUv(texture, cu, cv, u, v);
                        u = ((Float) local[1]).floatValue();
                        v = ((Float) local[2]).floatValue();
                    }
                    for (int j = 0; j < 8; j++) {
                        values.add(j == 3 ? u : j == 4 ? v : data[at + j]);
                    }
                }
            }
            for (Map.Entry<String, List<Float>> entry : bySprite.entrySet()) {
                List<Float> values = entry.getValue();
                float[] split = new float[values.size()];
                for (int i = 0; i < split.length; i++) split[i] = values.get(i);
                out.add(EntityRenderCapture.Draw.owned(entry.getKey(), split, split.length / 8, null));
            }
        }

        private static float[] matrix(MatrixStack stack) {
            float[] result = new float[16];
            Object matrix = stack.func_227866_c_().func_227870_a_();
            try {
                java.lang.reflect.Method write = matrix.getClass().getMethod(
                        "func_195879_b", FloatBuffer.class);
                FloatBuffer buffer = FloatBuffer.allocate(16);
                write.invoke(matrix, buffer);
                buffer.flip();
                buffer.get(result);
                return result;
            } catch (Throwable ignored) {
                // The Forge-patched client and the clean server overlay carry slightly
                // different Matrix4f surfaces.  Both retain the sixteen SRG fields; use
                // those as the classloader-stable fallback rather than dropping geometry.
                String[] fields = {
                        "field_226575_a_", "field_226576_b_", "field_226577_c_", "field_226578_d_",
                        "field_226579_e_", "field_226580_f_", "field_226581_g_", "field_226582_h_",
                        "field_226583_i_", "field_226584_j_", "field_226585_k_", "field_226586_l_",
                        "field_226587_m_", "field_226588_n_", "field_226589_o_", "field_226590_p_"
                };
                try {
                    float[] rowMajor = new float[16];
                    for (int i = 0; i < fields.length; i++) {
                        java.lang.reflect.Field field = matrix.getClass().getDeclaredField(fields[i]);
                        field.setAccessible(true);
                        rowMajor[i] = field.getFloat(matrix);
                    }
                    // The clean/server Matrix4f overlay has no FloatBuffer writer and stores
                    // the sixteen values row-major; the client-patched writer emits the
                    // column-major form consumed by the host replay.  Normalize both forms.
                    for (int row = 0; row < 4; row++) {
                        for (int column = 0; column < 4; column++) {
                            result[column * 4 + row] = rowMajor[row * 4 + column];
                        }
                    }
                } catch (Throwable fieldFailure) {
                    result[0] = result[5] = result[10] = result[15] = 1.0F;
                }
            }
            return result;
        }
    }

    static final class Segment {
        final float[] matrix;
        final List<Float> values = new ArrayList<Float>();
        Segment(float[] matrix) { this.matrix = matrix; }
    }
}
