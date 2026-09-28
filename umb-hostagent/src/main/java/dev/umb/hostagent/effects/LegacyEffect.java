package dev.umb.hostagent.effects;

/** Generic server-to-client effect vocabulary; names remain legacy names until mapped by host data. */
public sealed interface LegacyEffect permits LegacyEffect.Sound, LegacyEffect.Particle,
        LegacyEffect.Recoil, LegacyEffect.Animation, LegacyEffect.HeldNbt,
        LegacyEffect.LoopStart, LegacyEffect.LoopUpdate, LegacyEffect.LoopStop, LegacyEffect.Unknown {
    long tick();
    String playerId();
    record Sound(long tick, String playerId, String name, double x, double y, double z,
                 float volume, float pitch) implements LegacyEffect {}
    record Particle(long tick, String playerId, String name, double x, double y, double z,
                    double vx, double vy, double vz) implements LegacyEffect {}
    record Recoil(long tick, String playerId, float yaw, float pitch) implements LegacyEffect {}
    record Animation(long tick, String playerId, String name, int timer) implements LegacyEffect {}
    record HeldNbt(long tick, String playerId, byte[] patch) implements LegacyEffect {
        public HeldNbt { patch = patch == null ? new byte[0] : patch.clone(); }
        @Override public byte[] patch() { return patch.clone(); }
    }
    record LoopStart(long tick, String playerId, String name, double x, double y, double z,
                     float volume, float pitch) implements LegacyEffect {}
    record LoopUpdate(long tick, String playerId, String name, double x, double y, double z,
                      float volume, float pitch) implements LegacyEffect {}
    record LoopStop(long tick, String playerId, String name, double x, double y, double z)
            implements LegacyEffect {}
    /** Retained and counted: an unknown packet is never silently faked or discarded. */
    record Unknown(long tick, String playerId, String packetType, byte[] payload) implements LegacyEffect {
        public Unknown { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
