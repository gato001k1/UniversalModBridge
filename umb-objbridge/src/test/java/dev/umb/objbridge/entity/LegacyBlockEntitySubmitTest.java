package dev.umb.objbridge.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.umb.objbridge.ObjBridge;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door-live round 3 follow-up: every exit of the dynamic submit path must leave the
 * client's {@link PoseStack} exactly as deep as it found it. A leaked round-3 prefix
 * push crashed the live client with "Pose stack not empty" on the hasDynamicPose-false
 * exit (a freshly placed door has no synced values yet) - a single finally pops it now,
 * and this test pins all exits with a prefix row present. Runs fully headless
 * (recording collector, synthetic sprite/mesh, no GL, no quads evaluated).
 */
class LegacyBlockEntitySubmitTest {

    private static final String SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1","bounds":{},"renderers":{
              "test.Ber":{
                "teClass":"test.BerTE","partialArg":-1,
                "ops":[{"method":"func_147500_a","op":"glTranslated","group":"Antenna",
                        "args":[{"k":"channel","key":"test.Ch[0]"},
                                {"k":"const","v":0.0},{"k":"const","v":0.0}]},
                       {"method":"func_147500_a","op":"glRotated","group":"Dome",
                        "args":[{"k":"field","key":"TE.missing","hops":["missing"],"desc":"F"},
                                {"k":"const","v":0.0},{"k":"const","v":1.0},{"k":"const","v":0.0}]}],
                "fields":[],"resolvedArgs":1,"skippedArgs":{},
                "prefix":{"translate":[0.5,0.0,0.5],"metaBase":10,
                          "facing":{"2":[90.0,0.0,1.0,0.0]}}},
              "test.BerEmpty":{
                "teClass":"test.BerTE2","partialArg":-1,
                "ops":[],"fields":[],"resolvedArgs":0,"skippedArgs":{},
                "prefix":{"translate":[0.5,0.0,0.5]}}},
             "blocks":{}}""";

    private static final String OBJ =
            "o Antenna\nv 0.0 0.0 0.0\nv 1.0 0.0 0.0\nv 0.0 1.0 0.0\nf 1 2 3\n"
            + "o Dome\nv 0.0 0.0 2.0\nv 1.0 0.0 2.0\nv 0.0 1.0 2.0\nf 4 5 6\n";

    /** Counts pushes/pops so the test can assert depth invariance. */
    static final class CountingPose extends PoseStack {
        int depth;
        @Override
        public void pushPose() {
            depth++;
            super.pushPose();
        }
        @Override
        public void popPose() {
            depth--;
            super.popPose();
        }
    }

    /**
     * Records submitted matrices without invoking the geometry consumer (no quads
     * needed). With {@code failFirst}, the first submit throws (exercising the catch
     * path) and later submits record normally.
     */
    static final class RecordingCollector implements InvocationHandler {
        final List<org.joml.Matrix4f> matrices = new ArrayList<>();
        boolean failFirst;
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("submitCustomGeometry".equals(method.getName()) && args != null && args.length == 3
                    && args[0] instanceof PoseStack) {
                if (failFirst) {
                    failFirst = false;
                    throw new RuntimeException("synthetic submit failure");
                }
                matrices.add(new org.joml.Matrix4f(((PoseStack) args[0]).last().pose()));
            }
            return null;
        }
    }

    static final class TestSprite extends TextureAtlasSpriteShim {
        TestSprite() {
            super();
        }
    }

    /** TextureAtlasSprite has a protected constructor; this shim supplies a 1px sprite. */
    static class TextureAtlasSpriteShim
            extends net.minecraft.client.renderer.texture.TextureAtlasSprite {
        TextureAtlasSpriteShim() {
            super(Identifier.parse("test:atlas"),
                    new net.minecraft.client.renderer.texture.SpriteContents(
                            Identifier.parse("test:x"),
                            new net.minecraft.client.resources.metadata.animation.FrameSize(1, 1),
                            new com.mojang.blaze3d.platform.NativeImage(1, 1, false)),
                    1, 1, 0, 0, 0);
        }
    }

    private LegacyBlockEntityRenderer renderer() {
        SpriteGetter sprites = id -> new TestSprite();
        BlockEntityRendererProvider.Context ctx = new BlockEntityRendererProvider.Context(
                null, null, null, null, null, null, sprites, null);
        return new LegacyBlockEntityRenderer(ctx);
    }

    private LegacyBlockEntityRenderer.State state(String rendererClass, String helper,
                                                  List<String> groups, Map<String, Double> dyn,
                                                  Integer meta) {
        LegacyBlockEntityRenderer.State s = new LegacyBlockEntityRenderer.State();
        s.core = true;
        s.id = "test:ber";
        s.partial = 0f;
        s.visual = new LegacyBlockVisual("test:ber", rendererClass, "test:models/x.obj",
                "test:x", List.copyOf(groups));
        s.dyn = dyn;
        s.helper = helper;
        s.meta = meta;
        return s;
    }

    private void configure(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("renderer-transforms.json"), "{\"renderers\":{}}",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("renderer-dynamic-ops.json"), SIDECAR, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("map.json"), "{\"blocks\":[]}", StandardCharsets.UTF_8);
        Path obj = tmp.resolve("assets/test/models/x.obj");
        Files.createDirectories(obj.getParent());
        Files.writeString(obj, OBJ, StandardCharsets.UTF_8);
        ObjBridge.configure(tmp, tmp.resolve("map.json"), tmp.resolve("renderer-transforms.json"));
        DynamicOps.resetForTests();
    }

    /** Element-wise matrix assert: exact float equality is brittle across
     *  composition orders (+0.0 vs -0.0 dust), epsilon is honest. */
    private static void assertMatrixEquals(org.joml.Matrix4f want, org.joml.Matrix4f got) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                org.junit.jupiter.api.Assertions.assertEquals(want.get(c, r), got.get(c, r),
                        1e-6f, "cell [" + c + "," + r + "]");
            }
        }
    }

    private SubmitNodeCollector collector(RecordingCollector rec) {
        return (SubmitNodeCollector) Proxy.newProxyInstance(
                LegacyBlockEntitySubmitTest.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, rec);
    }

    /**
     * {@code BlockEntityRenderState}'s constructor touches {@code Blocks} statics, whose
     * registration asserts a booted game. Same idiom as the hostagent lane's
     * {@code TestSupport.ensureBootstrappedWithoutFreezing}: flip
     * {@code Bootstrap.isBootstrapped} directly (registries initialise normally, nothing
     * freezes, so this composes with every other headless test in the process - unlike a
     * full {@code bootStrap()}).
     */
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    private static void ensureBootstrappedWithoutFreezing() {
        if (BOOTSTRAPPED.compareAndSet(false, true)) {
            try {
                net.minecraft.SharedConstants.tryDetectVersion();
                java.lang.reflect.Field flag =
                        Class.forName("net.minecraft.server.Bootstrap").getDeclaredField("isBootstrapped");
                flag.setAccessible(true);
                flag.setBoolean(null, true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @Test
    void everyExitLeavesPoseDepthUnchanged(@TempDir Path tmp) throws Exception {
        ensureBootstrappedWithoutFreezing();
        configure(tmp);
        LegacyBlockEntityRenderer ber = renderer();
        Map<String, Double> live = Map.of("test.Ch[0]", 2.5);

        // 1. No draws at all (prefix row present, no ops): early return before any push.
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            ber.submit(state("test.BerEmpty", "test.BerEmpty", List.of(), Map.of(), 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(1, rec.matrices.size());
        }

        // 2. hasDynamicPose false with prefix pushed (the live crash: fresh door, no values).
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            ber.submit(state("test.Ber", "test.Ber", List.of("Antenna"), Map.of(), 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(1, rec.matrices.size());
        }

        // 3. Groups filtered out (live values, nothing drawable): prefix pushed then unwound.
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            ber.submit(state("test.Ber", "test.Ber", List.of(), live, 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(1, rec.matrices.size());
        }

        // 4. Success: one dynamic draw, prefix composed around it (matrix pinned).
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            ber.submit(state("test.Ber", "test.Ber", List.of("Antenna"), live, 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(1, rec.matrices.size());
            org.joml.Matrix4f want = new org.joml.Matrix4f()
                    .translate(0.5f, 0.0f, 0.5f)
                    .rotateY((float) Math.toRadians(90.0))
                    .translate(2.5f, 0.0f, 0.0f);
            assertMatrixEquals(want, rec.matrices.get(0));
        }

        // 6. Partially evaluable draw set: the missing-field Dome draw falls through
        // to the static remainder instead of replaying half-posed (the turret scatter).
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            ber.submit(state("test.Ber", "test.Ber", List.of("Antenna", "Dome"), live, 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(2, rec.matrices.size());
            org.joml.Matrix4f want = new org.joml.Matrix4f()
                    .translate(0.5f, 0.0f, 0.5f)
                    .rotateY((float) Math.toRadians(90.0))
                    .translate(2.5f, 0.0f, 0.0f);
            assertMatrixEquals(want, rec.matrices.get(0));
        }

        // 5. Throwing first submit: the catch runs, the finally still unwinds the
        // prefix, and the static fallback submits normally afterwards.
        {
            CountingPose pose = new CountingPose();
            RecordingCollector rec = new RecordingCollector();
            rec.failFirst = true;
            ber.submit(state("test.Ber", "test.Ber", List.of("Antenna"), live, 12),
                    pose, collector(rec), null);
            assertEquals(0, pose.depth);
            assertEquals(1, rec.matrices.size());
        }
    }
}
