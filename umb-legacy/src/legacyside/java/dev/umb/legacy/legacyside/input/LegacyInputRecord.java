package dev.umb.legacy.legacyside.input;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * Host -> legacy input snapshot.  The host owns the clock; the legacy side only
 * consumes this immutable record.  Names are stable ids assigned when a legacy
 * KeyBinding is registered, never a mod name or a guessed key code.
 */
public final class LegacyInputRecord {
    private final long tick;
    private final EntityPlayerMP player;
    private final boolean useHeld;
    private final boolean attackHeld;
    private final boolean sneakHeld;
    private final boolean usePressed;
    private final boolean attackPressed;
    private final int heldSlot;
    private final float yaw;
    private final float pitch;
    private final double lookX;
    private final double lookY;
    private final double lookZ;
    private final ItemStack heldStack;
    private final Set<String> pressedKeys;

    public LegacyInputRecord(long tick, EntityPlayerMP player, boolean useHeld, boolean attackHeld,
            boolean sneakHeld, boolean usePressed, boolean attackPressed, int heldSlot,
            float yaw, float pitch, double lookX, double lookY, double lookZ,
            ItemStack heldStack, Set<String> pressedKeys) {
        this.tick = tick;
        this.player = player;
        this.useHeld = useHeld;
        this.attackHeld = attackHeld;
        this.sneakHeld = sneakHeld;
        this.usePressed = usePressed;
        this.attackPressed = attackPressed;
        this.heldSlot = heldSlot;
        this.yaw = yaw;
        this.pitch = pitch;
        this.lookX = lookX;
        this.lookY = lookY;
        this.lookZ = lookZ;
        this.heldStack = heldStack == null ? null : heldStack.func_77946_l();
        this.pressedKeys = Collections.unmodifiableSet(new LinkedHashSet<String>(
                pressedKeys == null ? Collections.<String>emptySet() : pressedKeys));
    }

    public long tick() { return tick; }
    public EntityPlayerMP player() { return player; }
    public boolean useHeld() { return useHeld; }
    public boolean attackHeld() { return attackHeld; }
    public boolean sneakHeld() { return sneakHeld; }
    public boolean usePressed() { return usePressed; }
    public boolean attackPressed() { return attackPressed; }
    public int heldSlot() { return heldSlot; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public double lookX() { return lookX; }
    public double lookY() { return lookY; }
    public double lookZ() { return lookZ; }
    public ItemStack heldStack() { return heldStack == null ? null : heldStack.func_77946_l(); }
    public Set<String> pressedKeys() { return pressedKeys; }
}
