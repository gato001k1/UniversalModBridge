package dev.umb.bridge.api;

/**
 * A live legacy {@code Entity} - the entity twin of {@link TileHandle}. Unlike a tile entity
 * (which the HOST decides to create, at a HOST-chosen position), an entity is spawned by LEGACY
 * code calling {@code World.spawnEntityInWorld} (func_72838_d) - the legacy object already exists
 * by the time this handle wraps it. The host calls {@link #tick()} once per host tick per live
 * twin, on the SERVER thread only (never from the render thread), then reads the position/motion/
 * rotation getters - updated ONLY after {@link #tick()} returns, because legacy
 * {@code Entity.onUpdate} (func_70071_h_) is what actually advances them. Same
 * "legacy computes it, host displays it" split {@link TileHandle} already uses for machine state.
 */
public interface EntityHandle {
    /** Runs the legacy entity's own onUpdate (func_70071_h_). Poisons itself on the first throw -
     *  logged once, never propagated, {@link #isValid()} still reflects real state afterward. */
    void    tick();

    /**
     * Legacy {@code writeToNBTOptional} (func_70039_c), which also writes the "id" registry-name
     * tag {@code EntityList.createEntityFromNBT} needs to reconstruct the right subclass later.
     * Returns null when the legacy entity declined to be saved (dead, unnamed, or currently riding
     * another entity - vanilla's own convention, not a bridge failure) - never a fabricated blob.
     */
    byte[]  saveNbt();

    /** False once the legacy entity is dead/removed (field_70128_L) or this handle poisoned itself. */
    boolean isValid();

    double  getX();  double getY();  double getZ();
    double  getMotionX(); double getMotionY(); double getMotionZ();
    float   getYaw();   float getPitch();
    default float getWidth() { return 0.5F; }
    default float getHeight() { return 0.5F; }

    /** True when the legacy entity's {@code canBeCollidedWith} (func_70067_L) accepts a hit. */
    default boolean canBeCollidedWith() { return false; }

    /**
     * Legacy world-space bounding box ({@code minX,minY,minZ,maxX,maxY,maxZ}), or null when the
     * entity has no usable collision shape.  The host applies this exact box to the native twin.
     */
    default double[] getBoundingBox() { return null; }

    /**
     * Extra legacy world-space collision boxes ({@code minX,minY,minZ,maxX,maxY,maxZ} each) beyond
     * {@link #getBoundingBox}, or null/empty when there are none.  Covers the generic multipart
     * contract: the vanilla parts array ({@code func_70021_al}, every part with a usable
     * {@code func_70046_E}) plus any helper-owned world-space {@code AxisAlignedBB} state the
     * entity keeps outside its base box.  Found by type only (Entity/AxisAlignedBB fields, never
     * a mod class or field name); the host unions them with the base box into its single native
     * bounding box, because 26.2 entity-entity collision ({@code EntityGetter.getEntityCollisions},
     * javap-verified) contributes exactly one box per entity.  Null is an honest "none known".
     * Must never throw across the boundary.
     */
    default java.util.List<double[]> getCollisionBoxes() { return null; }

    /**
     * The legacy registry name for this entity's class, e.g. "hbm.entity_bullet" for an
     * FML-mod-registered entity or a vanilla name for a vanilla one -
     * {@code EntityList.getEntityString} (func_75621_b), a generic vanilla/Forge API. Used by the
     * host ONLY to pick a renderer/data record later; never reinterpreted as a class name.
     */
    String  legacyEntityId();

    /** The actual legacy runtime class name, for diagnostics only; never used for dispatch. */
    default String legacyEntityClassName() { return ""; }

    /** The authoritative FML owner namespace for this legacy entity, or null when unregistered. */
    default String ownerNamespace() { return null; }

    /** Opaque process-local identity used only to join legacy child/parent entity twins. */
    default String legacyEntityIdentity() { return null; }

    /** Opaque identity of the legacy entity this entity rides/claims as its parent, or null. */
    default String legacyVehicleIdentity() { return null; }

    /** Opaque identity of a direct non-player legacy passenger, or null for a direct player/none. */
    default String legacyPassengerIdentity() { return null; }

    /** The legacy player name currently riding this entity, or null when there is no rider. */
    default String riderName() { return null; }

    /** Stable identity of the legacy player currently riding this entity, or null. */
    default String riderIdentity() { return null; }

    /**
     * World-space offset {dx, dy, dz} from this entity's position to where its legacy
     * updateRiderPosition (func_70043_V) seats the current player rider, or null when there is
     * no rider. The host seats its passenger there instead of at its default attachment.
     */
    default double[] riderOffset() { return null; }

    /** Mirrors a host-side player passenger into the legacy entity before its next tick. */
    default void setHostRider(HostPlayer player) {}

    /** Forwards host damage to Entity.attackEntityFrom (func_70097_a). */
    default boolean attack(HostPlayer attacker, String damageType, float amount) { return false; }

    /** Forwards host interaction to Entity.interactFirst (func_130002_c). */
    default boolean interact(HostPlayer player) { return false; }

    /**
     * Host-initiated removal: the native twin was discarded for a reason OTHER than the legacy
     * entity already being dead (see the host's {@code UmbLegacyEntity.onRemoval} - a player or
     * explosion killed the twin, or it was explicitly discarded). Tells the legacy entity to die
     * too (calls {@code setDead}, func_70106_y) so the two halves never diverge into "one side
     * still thinks it's alive". Idempotent; must never throw across the boundary.
     */
    void    hostRemoved();

    /** Runs this entity's registered legacy client Render against UMB's capture backend. */
    default EntityRenderCapture renderCapture(float partialTick) {
        return EntityRenderCapture.empty(legacyEntityClassName(), legacyEntityId());
    }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
