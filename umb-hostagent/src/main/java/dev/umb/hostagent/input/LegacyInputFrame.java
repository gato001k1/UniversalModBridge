package dev.umb.hostagent.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One bounded, immutable host-client input sample.  The legacy side must treat this as data,
 * not as permission to poll GLFW or to invent a key state.  NBT is an opaque copied blob until
 * the legacy item adapter understands it.
 */
public record LegacyInputFrame(
        long tick,
        String playerId,
        String heldItemId,
        int heldDamage,
        int heldCount,
        byte[] heldNbt,
        boolean useDown,
        boolean usePressed,
        boolean attackDown,
        boolean attackPressed,
        boolean sneakDown,
        double lookX,
        double lookY,
        double lookZ,
        float yaw,
        float pitch,
        int selectedSlot,
        Map<String, Boolean> legacyKeys) {

    public LegacyInputFrame {
        if (playerId == null || playerId.isBlank()) throw new IllegalArgumentException("playerId");
        if (heldItemId == null) heldItemId = "";
        heldNbt = heldNbt == null ? new byte[0] : heldNbt.clone();
        legacyKeys = legacyKeys == null ? Map.of() :
                Collections.unmodifiableMap(new LinkedHashMap<>(legacyKeys));
        if (!Double.isFinite(lookX) || !Double.isFinite(lookY) || !Double.isFinite(lookZ))
            throw new IllegalArgumentException("non-finite look vector");
        if (selectedSlot < 0 || selectedSlot > 8) throw new IllegalArgumentException("selectedSlot");
    }

    @Override public byte[] heldNbt() { return heldNbt.clone(); }

    /** True when the legacy item accepted attack/use and vanilla block attack must be suppressed. */
    public boolean legacyActionPresent() { return useDown || attackDown || !legacyKeys.isEmpty(); }
}
