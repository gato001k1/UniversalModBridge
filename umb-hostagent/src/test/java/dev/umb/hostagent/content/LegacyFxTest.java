package dev.umb.hostagent.content;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacyFxTest {
    @BeforeEach
    void bootstrap() {
        TestSupport.ensureBootstrapped();
    }

    @Test
    void knownParticleUsesGroundedNativeType() {
        assertEquals(ParticleTypes.FLAME, LegacyFx.particle("flame"));
    }

    @Test
    void vanillaSoundUsesGroundedNativeEventId() {
        assertEquals("minecraft:entity.generic.explode",
                LegacyFx.soundId("random.explode").toString());
    }

    @Test
    void unknownParticleUsesVisibleCountedFallback() {
        assertEquals(ParticleTypes.SMOKE, LegacyFx.particle("legacyOnlyParticle"));
    }

    @Test
    void customDustPreservesEncodedRgbAndScale() {
        Object value = LegacyFx.particle("umb:custom_dust:12abef:1.25");
        assertNotNull(value);
        DustParticleOptions dust = assertInstanceOf(DustParticleOptions.class, value);
        assertEquals(0x12 / 255.0F, dust.getColor().x, 0.01F);
        assertEquals(0xab / 255.0F, dust.getColor().y, 0.01F);
        assertEquals(0xef / 255.0F, dust.getColor().z, 0.01F);
    }

    @Test
    void modSoundNamesNormalizeToTheGeneratedNamespacedEvent() {
        assertEquals("hbm:item.upgradeplug", LegacyFx.soundId("hbm:item.upgradePlug").toString());
    }

    @Test
    void malformedOrUnknownSoundDoesNotInventAnEvent() {
        assertEquals(null, LegacyFx.soundId("legacyOnlySound"));
    }
}
