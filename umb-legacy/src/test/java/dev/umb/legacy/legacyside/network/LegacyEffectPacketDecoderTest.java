package dev.umb.legacy.legacyside.network;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.EffectData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyEffectPacketDecoderTest {
    @Test
    void soundShapeProducesTypedEffectWithoutPacketIdentity() {
        SoundShape packet = new SoundShape();
        List<EffectData> effects = LegacyEffectPacketDecoder.decode(packet, "server->client", 7L);
        assertEquals(1, effects.size());
        assertEquals("sound", effects.get(0).kind);
        assertEquals("test:block.machine", effects.get(0).name);
        assertEquals(2.0F, effects.get(0).a);
        assertEquals(0.75F, effects.get(0).b);
    }

    @Test
    void explosionShapeCarriesStrengthAndBlockFlag() {
        ExplosionShape packet = new ExplosionShape();
        List<EffectData> effects = LegacyEffectPacketDecoder.decode(packet, "server->client", 9L);
        assertEquals(1, effects.size());
        assertEquals("explosion", effects.get(0).kind);
        assertEquals(6.0F, effects.get(0).a);
        assertEquals(1.0F, effects.get(0).b);
    }

    @Test
    void blockParticleShapeUsesVisibleGenericParticle() {
        BlockParticleShape packet = new BlockParticleShape();
        List<EffectData> effects = LegacyEffectPacketDecoder.decode(packet, "server->client", 11L);
        assertEquals(1, effects.size());
        assertEquals("particle", effects.get(0).kind);
        assertEquals("explode", effects.get(0).name);
        assertTrue(effects.get(0).x == 3.0D && effects.get(0).y == 4.0D && effects.get(0).z == 5.0D);
    }

    private static final class SoundShape {
        private double x = 1.0D, y = 2.0D, z = 3.0D;
        private String soundName = "test:block.machine";
        private float volume = 2.0F, pitch = 0.75F;
    }

    private static final class ExplosionShape {
        private double posX = 10.0D, posY = 20.0D, posZ = 30.0D;
        private float size = 6.0F;
        private java.util.List<Integer> affectedBlocks = java.util.Collections.emptyList();
    }

    private static final class BlockParticleShape {
        private int x = 3, y = 4, z = 5, block = 1, meta = 0;
    }
}
