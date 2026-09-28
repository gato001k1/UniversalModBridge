package dev.umb.bridge.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Plain host-to-legacy input snapshot; no legacy or 26.2 classes cross the boundary. */
public final class InputData {
    public final long tick; public final boolean useHeld, attackHeld, sneakHeld, usePressed, attackPressed;
    public final int heldSlot; public final float yaw, pitch; public final double lookX, lookY, lookZ;
    public final String heldItemId; public final int heldCount, heldDamage; public final byte[] heldNbt;
    public final Map<String, Boolean> legacyKeys;
    public InputData(long tick, boolean useHeld, boolean attackHeld, boolean sneakHeld,
            boolean usePressed, boolean attackPressed, int heldSlot, float yaw, float pitch,
            double lookX, double lookY, double lookZ, String heldItemId, int heldCount, int heldDamage,
            byte[] heldNbt, Map<String, Boolean> legacyKeys) {
        this.tick=tick; this.useHeld=useHeld; this.attackHeld=attackHeld; this.sneakHeld=sneakHeld;
        this.usePressed=usePressed; this.attackPressed=attackPressed; this.heldSlot=heldSlot;
        this.yaw=yaw; this.pitch=pitch; this.lookX=lookX; this.lookY=lookY; this.lookZ=lookZ;
        this.heldItemId=heldItemId; this.heldCount=heldCount; this.heldDamage=heldDamage;
        this.heldNbt=heldNbt == null ? new byte[0] : heldNbt.clone();
        this.legacyKeys=Collections.unmodifiableMap(new LinkedHashMap<String, Boolean>(
                legacyKeys == null ? Collections.<String, Boolean>emptyMap() : legacyKeys));
    }
    public byte[] heldNbt() { return heldNbt.clone(); }
}
