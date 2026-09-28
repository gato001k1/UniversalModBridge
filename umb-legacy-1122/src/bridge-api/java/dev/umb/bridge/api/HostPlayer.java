package dev.umb.bridge.api;

/** Implemented by the HOST. The player that triggered an interaction. */
public interface HostPlayer {
    String  getName();

    /** Current host ping in milliseconds, or zero when the host has no measurement. */
    default int getPing() {
        return 0;
    }

    /**
     * Stable identity for the host player during a server session.  The default keeps older
     * adapters source-compatible and is sufficient for headless hosts; a native adapter should
     * return the host's persistent player UUID rather than a display/name field.
     */
    default String getIdentityKey() {
        return getName();
    }

    /**
     * Whether the host player is in creative mode. Legacy mods gate behaviour on
     * EntityPlayer.capabilities.isCreativeMode (e.g. infinite fuel/ammo, no item consumption);
     * the bridge mirrors this into the legacy player's capabilities every tick. Headless
     * adapters default to survival.
     */
    default boolean isCreative() {
        return false;
    }

    boolean isSneaking();
    double  getX(); double getY(); double getZ();
    StackData getHeldItem();
    void      setHeldItem(StackData s);

    /**
     * Starts the host player's native item-use state after legacy Item.func_77659_a
     * (onItemRightClick) called EntityPlayer.func_71008_a (setItemInUse). The default is a
     * no-op for headless adapters; the real host maps it to ServerPlayer.startUsingItem on the
     * main hand so 26.2 drives Item.onUseTick/releaseUsing for every legacy item uniformly.
     */
    default void startUsingItem() {
    }

    void      sendMessage(String text);
    StackData getInventorySlot(int i);   // 0..35 main inventory
    void      setInventorySlot(int i, StackData s);
    int       getInventorySize();

    // ---- TICK/CONTACT lane: motion + damage, so entity-contact dispatches have a real effect ----

    /** Host-side motion (26.2 deltaMovement), read to seed the legacy facade's motionX/Y/Z
     *  (field_70159_w/field_70181_x/field_70179_y) before an entity-contact dispatch. */
    double getMotionX();
    double getMotionY();
    double getMotionZ();

    /** Written back ONLY when legacy code changed the facade's motion during a dispatch (e.g. a
     *  conveyor push). The host must make the change actually reach the client - a server-side
     *  player motion write is invisible until it is sent (26.2: hurtMarked / the motion packet). */
    void setMotion(double mx, double my, double mz);

    /** attackEntityFrom (func_70097_a) crossing the boundary: legacyDamageType is 1.7.10's
     *  DamageSource.damageType string (field_76373_n). Vanilla names ("inFire", "cactus", ...) map
     *  to native damage sources; an unknown mod-custom name must still hurt (generic damage),
     *  never silently no-op - a gas that runs its code but deals no damage is a faked value. */
    void hurt(String legacyDamageType, float amount);

    // ---- mcheli-vehicles lane: look rotation, so look-dependent legacy code (getLook/raytrace
    //      for vehicle placement, thrown items, bows, buckets) aims where the player aims ----

    /** Host look yaw in degrees. 26.2 YRot; identical scale/orientation to 1.7.10's
     *  rotationYaw (field_70177_z, fields.csv) - straight identity mapping, no conversion. */
    default float getYaw() {
        return 0.0F;
    }

    /** Host look pitch in degrees. 26.2 XRot; identical semantics to 1.7.10's rotationPitch
     *  (field_70125_A, fields.csv): +90 is straight down, -90 straight up. */
    default float getPitch() {
        return 0.0F;
    }

    // ---- automation lane: selected hotbar slot, so server/client selection divergence
    //      (input-lane round 13) can be read instead of matched by fallback ----

    /** Selected hotbar slot 0-8, or -1 when the host cannot read it (never a fabricated
     *  slot: -1 matches nothing, so callers fall back instead of acting on slot 0). */
    default int getSelectedSlot() {
        return -1;
    }

    /**
     * Opens the host's generic legacy-container screen for a container created by legacy code
     * outside a block activation (for example, a packet-driven vehicle GUI).  The default keeps
     * headless adapters source-compatible; native hosts may route the handle through their normal
     * menu bridge.
     */
    default void openLegacyContainer(ContainerHandle handle, String title, int x, int y, int z) {
    }
}
