package dev.umb.hostagent.effects;

/** Host-native replay adapter. Implementations must map only known names and count missing mappings. */
public interface LegacyEffectSink {
    void sound(LegacyEffect.Sound effect);
    void particle(LegacyEffect.Particle effect);
    void recoil(LegacyEffect.Recoil effect);
    void animation(LegacyEffect.Animation effect);
    void heldNbt(LegacyEffect.HeldNbt effect);
    default void loopStart(LegacyEffect.LoopStart effect) {}
    default void loopUpdate(LegacyEffect.LoopUpdate effect) {}
    default void loopStop(LegacyEffect.LoopStop effect) {}
    void unknown(LegacyEffect.Unknown effect);
}
