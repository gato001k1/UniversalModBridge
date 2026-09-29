package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import org.junit.jupiter.api.Test;

/**
 * pre-flag meaning (opaque, lit), the new factory carries GL_BLEND/GL_LIGHTING, and the
 * camera-aware capture overload falls back to the neutral capture for handles that ignore it.
 */
class EntityRenderCaptureDrawStateTest {

    @Test
    void existingFactoriesMeanOpaqueAndLit() {
        float[] v = new float[8 * 4];
        EntityRenderCapture.Draw plain = EntityRenderCapture.Draw.owned("t", v, 4, null);
        EntityRenderCapture.Draw culled = EntityRenderCapture.Draw.owned("t", v, 4, null, true);
        EntityRenderCapture.Draw copied = new EntityRenderCapture.Draw("t", v, 4, null);
        for (EntityRenderCapture.Draw d : new EntityRenderCapture.Draw[] {plain, culled, copied}) {
            assertFalse(d.blend, "legacy factories must not become translucent");
            assertTrue(d.lighting, "legacy factories must stay lit");
        }
        assertTrue(culled.cull);
        assertFalse(plain.cull);
    }

    @Test
    void stateFactoryCarriesBlendAndLighting() {
        float[] v = new float[8 * 4];
        EntityRenderCapture.Draw glass = EntityRenderCapture.Draw.owned("t", v, 4, null, true, true, true);
        EntityRenderCapture.Draw beam = EntityRenderCapture.Draw.owned("t", v, 4, null, false, true, false);
        assertTrue(glass.cull);
        assertTrue(glass.blend);
        assertTrue(glass.lighting);
        assertFalse(beam.cull);
        assertTrue(beam.blend);
        assertFalse(beam.lighting);
        assertSame(v, glass.vertices, "owned draws must not copy the sealed buffer");
    }

    @Test
    void cameraAwareCaptureDefaultsToNeutralCapture() {
        EntityRenderCapture neutral = EntityRenderCapture.empty("x.Heli", "neutral");
        EntityHandle handle = new NeutralOnlyHandle(neutral);
        assertSame(neutral, handle.renderCapture(0.5F, 0));
        assertSame(neutral, handle.renderCapture(0.5F, -1));
        assertEquals("neutral", handle.renderCapture(0.5F, 2).stateKey);
    }

    /** A handle that only knows the neutral capture, like 1.12.2/1.16.5 handles today. */
    private static final class NeutralOnlyHandle implements EntityHandle {
        private final EntityRenderCapture capture;
        NeutralOnlyHandle(EntityRenderCapture capture) { this.capture = capture; }
        @Override public void tick() { }
        @Override public byte[] saveNbt() { return null; }
        @Override public boolean isValid() { return true; }
        @Override public double getX() { return 0; }
        @Override public double getY() { return 0; }
        @Override public double getZ() { return 0; }
        @Override public double getMotionX() { return 0; }
        @Override public double getMotionY() { return 0; }
        @Override public double getMotionZ() { return 0; }
        @Override public float getYaw() { return 0; }
        @Override public float getPitch() { return 0; }
        @Override public String legacyEntityId() { return "x.heli"; }
        @Override public void hostRemoved() { }
        @Override public EntityRenderCapture renderCapture(float partialTick) { return capture; }
    }
}
