package dev.umb.hostagent.input;

import java.util.Arrays;

/** Pure field-by-field change test for the client input gate. The sample id never compares. */
public final class LegacyFrameDiffer {
    private LegacyFrameDiffer() { }

    /** True when any mirrored field differs (tick excluded: it is a monotonic sample id). */
    public static boolean changed(LegacyInputFrame prev, LegacyInputFrame next) {
        if (prev == null || next == null) return true;
        if (!prev.playerId().equals(next.playerId())) return true;
        if (!prev.heldItemId().equals(next.heldItemId())) return true;
        if (prev.heldDamage() != next.heldDamage()) return true;
        if (prev.heldCount() != next.heldCount()) return true;
        if (!Arrays.equals(prev.heldNbt(), next.heldNbt())) return true;
        if (prev.useDown() != next.useDown()) return true;
        if (prev.usePressed() != next.usePressed()) return true;
        if (prev.attackDown() != next.attackDown()) return true;
        if (prev.attackPressed() != next.attackPressed()) return true;
        if (prev.sneakDown() != next.sneakDown()) return true;
        if (prev.lookX() != next.lookX()) return true;
        if (prev.lookY() != next.lookY()) return true;
        if (prev.lookZ() != next.lookZ()) return true;
        if (prev.yaw() != next.yaw()) return true;
        if (prev.pitch() != next.pitch()) return true;
        if (prev.selectedSlot() != next.selectedSlot()) return true;
        return !prev.legacyKeys().equals(next.legacyKeys());
    }
}
