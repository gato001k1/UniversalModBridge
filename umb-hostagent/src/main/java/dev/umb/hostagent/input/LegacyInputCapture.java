package dev.umb.hostagent.input;

import net.minecraft.client.KeyMapping;

import java.util.Map;

/** Client-tick sampler. The caller supplies host-native state; no legacy or GLFW polling occurs here. */
public final class LegacyInputCapture {
    private final LegacyKeyMappingRegistry keys;
    private boolean previousUse, previousAttack;

    public LegacyInputCapture(LegacyKeyMappingRegistry keys) { this.keys = keys; }

    public LegacyInputFrame sample(long tick, String playerId, String heldItemId, int damage, int count,
                                   byte[] nbt, boolean useDown, boolean attackDown, boolean sneak,
                                   double lookX, double lookY, double lookZ, float yaw, float pitch,
                                   int selectedSlot) {
        LegacyInputFrame f = new LegacyInputFrame(tick, playerId, heldItemId, damage, count, nbt,
                useDown, useDown && !previousUse, attackDown, attackDown && !previousAttack, sneak,
                lookX, lookY, lookZ, yaw, pitch, selectedSlot, keys.sampleHeld());
        previousUse = useDown; previousAttack = attackDown;
        return f;
    }
}
