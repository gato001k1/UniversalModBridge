package dev.umb.hostagent.effects;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LegacyEffectsReplayTest {
    @Test void replaysKnownEffectsAndCountsUnknownWithoutFakingIt() {
        LegacyEffectsQueue q = new LegacyEffectsQueue();
        q.offer(new LegacyEffect.Sound(4, "p", "LegacyFx.gun", 1, 2, 3, 1, 1));
        q.offer(new LegacyEffect.Unknown(4, "p", "private.packet", new byte[]{9}));
        AtomicInteger sounds = new AtomicInteger(), unknown = new AtomicInteger();
        LegacyEffectSink sink = new LegacyEffectSink() {
            public void sound(LegacyEffect.Sound e) { sounds.incrementAndGet(); }
            public void particle(LegacyEffect.Particle e) {}
            public void recoil(LegacyEffect.Recoil e) {}
            public void animation(LegacyEffect.Animation e) {}
            public void heldNbt(LegacyEffect.HeldNbt e) {}
            public void unknown(LegacyEffect.Unknown e) { unknown.incrementAndGet(); }
        };
        assertEquals(2, new LegacyEffectsReplay(q).replay(sink, 8));
        assertEquals(1, sounds.get()); assertEquals(1, unknown.get()); assertEquals(1, q.unknownCount());
    }

    @Test void replaysLoopLifecycleThroughAdditiveSinkMethods() {
        LegacyEffectsQueue q = new LegacyEffectsQueue();
        q.offer(new LegacyEffect.LoopStart(4, "machine", "hbm:press.loop", 1, 2, 3, 0.8F, 1.0F));
        q.offer(new LegacyEffect.LoopUpdate(5, "machine", "hbm:press.loop", 2, 3, 4, 0.5F, 1.2F));
        q.offer(new LegacyEffect.LoopStop(6, "machine", "hbm:press.loop", 2, 3, 4));
        AtomicInteger starts = new AtomicInteger(), updates = new AtomicInteger(), stops = new AtomicInteger();
        LegacyEffectSink sink = new LegacyEffectSink() {
            public void sound(LegacyEffect.Sound e) {}
            public void particle(LegacyEffect.Particle e) {}
            public void recoil(LegacyEffect.Recoil e) {}
            public void animation(LegacyEffect.Animation e) {}
            public void heldNbt(LegacyEffect.HeldNbt e) {}
            public void loopStart(LegacyEffect.LoopStart e) { starts.incrementAndGet(); }
            public void loopUpdate(LegacyEffect.LoopUpdate e) { updates.incrementAndGet(); }
            public void loopStop(LegacyEffect.LoopStop e) { stops.incrementAndGet(); }
            public void unknown(LegacyEffect.Unknown e) {}
        };
        assertEquals(3, new LegacyEffectsReplay(q).replay(sink, 8));
        assertEquals(1, starts.get());
        assertEquals(1, updates.get());
        assertEquals(1, stops.get());
    }
}
