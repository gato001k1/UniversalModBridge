package dev.umb.legacy1165.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.matrix.MatrixStack;

import dev.umb.bridge.api.EntityRenderCapture;

/** Native-free contract test for the 1.16.5 MatrixStack/IVertexBuilder adapter. */
class LegacyEntityRenderCapture1165ClientTest {
    @Test
    void matrixAndVertexFluentCallsBecomeBridgeDrawRows() {
        MatrixStack stack = new MatrixStack();
        LegacyEntityRenderCapture1165Client.RecordingBuffer buffer =
                new LegacyEntityRenderCapture1165Client.RecordingBuffer(stack,
                        "minecraft:textures/entity/test.png");
        LegacyEntityRenderCapture1165Client.RecordingVertexBuilder vertices =
                new LegacyEntityRenderCapture1165Client.RecordingVertexBuilder(stack,
                        "minecraft:textures/entity/test.png", buffer);
        stack.func_227861_a_(2.0D, 3.0D, 4.0D);
        vertices.func_225582_a_(0.0D, 0.0D, 0.0D)
                .func_225583_a_(0.25F, 0.75F)
                .func_225584_a_(0.0F, 1.0F, 0.0F);
        vertices.func_181675_d();
        List<EntityRenderCapture.Draw> draws = vertices.finish();
        assertEquals(1, draws.size());
        assertEquals(1, draws.get(0).vertexCount);
        assertEquals("minecraft:textures/entity/test.png", draws.get(0).texture);
        // A raw pos(double) is final: a real BufferBuilder never sees the MatrixStack, so the
        // vertex stays at the origin and the draw ships an identity matrix (the host must not
        // apply the stack a second time).
        assertEquals(0.0F, draws.get(0).vertices[0]);
        assertEquals(0.0F, draws.get(0).matrix[12]);
        assertEquals(0.0F, draws.get(0).matrix[13]);
        assertEquals(0.0F, draws.get(0).matrix[14]);
        assertEquals(1.0F, draws.get(0).matrix[0]);
    }

    @Test
    void matrixAwareVertexIsTransformedExactlyOnce() {
        MatrixStack stack = new MatrixStack();
        LegacyEntityRenderCapture1165Client.RecordingBuffer buffer =
                new LegacyEntityRenderCapture1165Client.RecordingBuffer(stack, "x:textures/t.png");
        LegacyEntityRenderCapture1165Client.RecordingVertexBuilder vertices =
                new LegacyEntityRenderCapture1165Client.RecordingVertexBuilder(stack,
                        "x:textures/t.png", buffer);
        stack.func_227861_a_(2.0D, 3.0D, 4.0D);
        // IVertexBuilder.pos(Matrix4f, x, y, z): the default method transforms, then pos(double).
        vertices.func_227888_a_(stack.func_227866_c_().func_227870_a_(), 1.0F, 0.0F, 0.0F)
                .func_225583_a_(0.0F, 0.0F).func_225584_a_(0.0F, 1.0F, 0.0F);
        vertices.func_181675_d();
        EntityRenderCapture.Draw d = vertices.finish().get(0);
        assertEquals(3.0F, d.vertices[0], 1e-6F);
        assertEquals(3.0F, d.vertices[1], 1e-6F);
        assertEquals(4.0F, d.vertices[2], 1e-6F);
        assertEquals(0.0F, d.matrix[12]);
    }

    @Test
    void atlasBoundQuadsAreReexpressedPerSprite() {
        String atlas = "test:textures/atlas/cockpit-test.png";
        // Two 64x32 sprites side by side on the 1024 sheet.
        HeadlessAtlas1165.recordFrame(atlas, "a:textures/model/one.png", 0, 0, 64, 32);
        HeadlessAtlas1165.recordFrame(atlas, "b:textures/model/two.png", 64, 0, 64, 32);
        float s = HeadlessAtlas1165.ATLAS_W;
        float[] data = new float[8 * 8];
        float[][] uv = {{0, 0}, {64, 0}, {64, 32}, {0, 32}, {64, 0}, {96, 0}, {96, 16}, {64, 16}};
        for (int i = 0; i < 8; i++) {
            data[i * 8] = i;
            data[i * 8 + 3] = uv[i][0] / s;
            data[i * 8 + 4] = uv[i][1] / HeadlessAtlas1165.ATLAS_H;
        }
        List<EntityRenderCapture.Draw> out = new java.util.ArrayList<EntityRenderCapture.Draw>();
        LegacyEntityRenderCapture1165Client.RecordingVertexBuilder.addDraws(out, atlas, data);
        assertEquals(2, out.size());
        assertEquals("a:textures/model/one.png", out.get(0).texture);
        assertEquals("b:textures/model/two.png", out.get(1).texture);
        assertEquals(1.0F, out.get(0).vertices[1 * 8 + 3], 1e-5F); // u=64 -> right edge of one
        assertEquals(1.0F, out.get(0).vertices[2 * 8 + 4], 1e-5F); // v=32 -> bottom of one
        assertEquals(0.5F, out.get(1).vertices[1 * 8 + 3], 1e-5F); // u=96 -> middle of two
        assertEquals(0.5F, out.get(1).vertices[2 * 8 + 4], 1e-5F); // v=16 -> middle of two
        assertEquals(4.0F, out.get(1).vertices[0], 0F); // positions untouched
        assertTrue(HeadlessAtlas1165.isHeadlessAtlas(atlas));
    }

    @Test
    void nonAtlasTextureIsUntouched() {
        List<EntityRenderCapture.Draw> out = new java.util.ArrayList<EntityRenderCapture.Draw>();
        float[] data = new float[8 * 4];
        data[3] = 0.7F;
        LegacyEntityRenderCapture1165Client.RecordingVertexBuilder.addDraws(out,
                "alexsmobs:textures/entity/grizzly_bear.png", data);
        assertEquals(1, out.size());
        assertEquals("alexsmobs:textures/entity/grizzly_bear.png", out.get(0).texture);
        assertEquals(0.7F, out.get(0).vertices[3]);
    }

    @Test
    void realAlexGrizzlyModelEmitsVerticesThroughAdapter() throws Exception {
        File alex = new File(System.getProperty("umb.alexsmobs.jar",
                "research/mods-1165/alexsmobs-1.12.1.jar"));
        File citadel = new File(System.getProperty("umb.citadel.jar",
                "research/mods-1165/citadel-1.8.1-1.16.5.jar"));
        Assumptions.assumeTrue(alex.isFile() && citadel.isFile(),
                "Alex/Citadel runtime jars are not present");
        try (JarFile archive = new JarFile(alex)) {
            assertTrue(archive.getEntry("com/github/alexthe666/alexsmobs/client/model/ModelGrizzlyBear.class") != null);
            assertTrue(archive.getEntry("assets/alexsmobs/textures/entity/grizzly_bear.png") != null);
        }
        URL[] jars = { alex.toURI().toURL(), citadel.toURI().toURL() };
        try (URLClassLoader loader = new URLClassLoader(jars,
                LegacyEntityRenderCapture1165ClientTest.class.getClassLoader())) {
            Class<?> modelType = Class.forName(
                    "com.github.alexthe666.alexsmobs.client.model.ModelGrizzlyBear", true, loader);
            Object model = modelType.getDeclaredConstructor().newInstance();
            MatrixStack stack = new MatrixStack();
            LegacyEntityRenderCapture1165Client.RecordingBuffer buffer =
                    new LegacyEntityRenderCapture1165Client.RecordingBuffer(stack,
                            "alexsmobs:textures/entity/grizzly_bear.png");
            LegacyEntityRenderCapture1165Client.RecordingVertexBuilder vertices =
                    new LegacyEntityRenderCapture1165Client.RecordingVertexBuilder(stack,
                            "alexsmobs:textures/entity/grizzly_bear.png", buffer);
            Method render = modelType.getMethod("func_225598_a_", MatrixStack.class,
                    com.mojang.blaze3d.vertex.IVertexBuilder.class,
                    int.class, int.class, float.class, float.class, float.class, float.class);
            render.invoke(model, stack, vertices, 15728880, 0, 1.0F, 1.0F, 1.0F, 1.0F);
            List<EntityRenderCapture.Draw> draws = vertices.finish();
            int vertexCount = 0;
            for (EntityRenderCapture.Draw draw : draws) vertexCount += draw.vertexCount;
            assertTrue(vertexCount > 0, "real Alex model emitted no vertices");
            assertEquals("alexsmobs:textures/entity/grizzly_bear.png", draws.get(0).texture);
        }
    }
}
