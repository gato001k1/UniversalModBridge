package dev.umb.hostagent.effects;

/** Replays exactly one queued effect at most once; no legacy GL/render code is called. */
public final class LegacyEffectsReplay {
    private final LegacyEffectsQueue queue;
    public LegacyEffectsReplay(LegacyEffectsQueue queue) { this.queue = queue; }
    public int replay(LegacyEffectSink sink, int max) {
        return queue.drainTo(effect -> {
            if (effect instanceof LegacyEffect.Sound x) sink.sound(x);
            else if (effect instanceof LegacyEffect.Particle x) sink.particle(x);
            else if (effect instanceof LegacyEffect.Recoil x) sink.recoil(x);
            else if (effect instanceof LegacyEffect.Animation x) sink.animation(x);
            else if (effect instanceof LegacyEffect.HeldNbt x) sink.heldNbt(x);
            else if (effect instanceof LegacyEffect.LoopStart x) sink.loopStart(x);
            else if (effect instanceof LegacyEffect.LoopUpdate x) sink.loopUpdate(x);
            else if (effect instanceof LegacyEffect.LoopStop x) sink.loopStop(x);
            else if (effect instanceof LegacyEffect.Unknown x) sink.unknown(x);
        }, max);
    }
}
