package dev.umb.bridge.api;

/** A live legacy tile entity. */
public interface TileHandle {
    void    tick();
    byte[]  saveNbt();
    void    loadNbt(byte[] nbt);
    boolean isValid();

    /** Runs the registered legacy TESR through the native-free GL emulation boundary. */
    default EntityRenderCapture renderCapture(float partialTick) {
        return EntityRenderCapture.empty(legacyClassName(), "tile-render-unavailable");
    }

    /**
     * Optional legacy IInventory/ISidedInventory surface for the host block-entity Container.
     * Side is the 1.7.10 Forge ordinal (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), kept as
     * an int so the bridge API carries no 26.2 Direction class. Defaults are an honest empty
     * capability for tiles that do not implement IInventory.
     */
    default int inventorySize() { return 0; }
    default int inventoryMaxStackSize() { return 64; }
    default StackData inventoryItem(int slot) { return StackData.EMPTY; }
    default StackData inventoryRemove(int slot, int amount) { return StackData.EMPTY; }
    default void inventorySet(int slot, StackData stack) { }
    default boolean inventoryCanPlace(int slot, StackData stack) { return false; }
    default boolean inventoryCanTake(int slot, StackData stack) { return false; }
    default int[] inventorySlotsForSide(int side) { return new int[0]; }
    default boolean inventoryCanPlaceThroughFace(int slot, StackData stack, int side) { return false; }
    default boolean inventoryCanTakeThroughFace(int slot, StackData stack, int side) { return false; }
    default void inventoryChanged() { }

    /**
     * TILE-FIELD-SNAPSHOT: reads ONLY the named fields, never a whole object graph - called on the
     * SERVER thread, at most once per server tick per open GUI (see {@code UmbLegacyMenu}'s own
     * per-tick refresh). A path that can't be resolved right now (the tile just became invalid, an
     * intermediate reference is null, the field/accessor no longer exists) comes back absent in the
     * result at that same index, never a fabricated value - see {@link TileFieldSnapshot}. Must
     * never throw across the boundary.
     */
    TileFieldSnapshot snapshotFields(FieldPath[] paths);

    /**
     * Automation/observation: every declared instance field of the live object this handle
     * wraps (superclass fields included), with simple type names and display values - see
     * {@link TileFieldDatum}. Called on the SERVER thread; bounded by the implementation
     * (at most a few hundred fields, values truncated); never throws across the boundary.
     * Defaults to empty for handles that do not wrap a reflectable object (fakes, tests).
     */
    default java.util.List<TileFieldDatum> describeFields() {
        return java.util.Collections.emptyList();
    }

    /**
     * Door-live lane: invokes one static {@code (String, Object) -> double[]} evaluator -
     * typically an animation-track function - against the object at the end of
     * {@code objectPath} (resolved exactly like {@link #snapshotFields} hops, TE-rooted),
     * with {@code stringArg} naming the track. Called on the SERVER thread; the method is
     * looked up by name with a {@code (String, <anim runtime type>)} signature and must
     * return {@code double[]} (anything else, any throw, or a missing method comes back
     * null - never a fabricated array). A hop chain resolving to null still reaches the
     * method as a null argument - the mod's own evaluator decides what null means. Exists so per-frame
     * animation values computed by the mod's own pure evaluator (same inputs the legacy
     * renderer would pass it, including its own clock reads) can be synced without
     * reimplementing that evaluator natively. Defaults to null (no evaluator).
     */
    default double[] evalStatic(String owner, String name, String stringArg, FieldPath objectPath) {
        return null;
    }

    /**
     * Door-live follow-up: resolves a render dispatch through a helper object - invokes the
     * zero-arg {@code owner.name()} on the object at the end of {@code objectPath} (resolved
     * exactly like {@link #snapshotFields} hops, TE-rooted, accessors allowed) and returns
     * the result's runtime class name. Called on the SERVER thread; a null receiver, a
     * missing method, any throw, or a null result comes back null, never a fabricated name.
     * Exists so the client draws exactly the helper renderer the tile-entity-server method
     * would have dispatched to, instead of every same-TE helper at once. Defaults to null
     * (no dispatch).
     */
    default String dispatchRenderer(String owner, String name, FieldPath objectPath) {
        return null;
    }

    /**
     * Turret follow-up: would vanilla's tile-entity renderer dispatch draw this tile as
     * {@code teClass}? Vanilla ({@code TileEntityRendererDispatcher}, bytecode-verified)
     * looks the tile's class up in the bound-TESR map and otherwise walks to superclasses
     * (stopping before {@code TileEntity} itself) - a multiblock filler holding a proxy or
     * dummy tile is therefore never drawn, while the core with the bound class is. Without
     * this, every live handle counts as a render core and filler dummies double-render the
     * whole model (live 2026-09-24: a proxy cell beside the chekhov core drew a second
     * interleaved turret). Called on the SERVER thread; any failure reads as no constraint
     * (render, today's behaviour). Defaults to true (no constraint) for handles that do
     * not wrap a classed object (fakes, tests).
     */
    default boolean rendersAs(String teClass) {
        return true;
    }

    /**
     * Stable runtime identity used when a legacy block changes its visual variant (for example
     * an off/on machine block) and the 26.2 side replaces the generic BlockEntity object.  The
     * host uses this only to decide whether the old opaque NBT belongs to the newly-created tile;
     * null means that the implementation cannot make that determination.
     */
    default String legacyClassName() { return null; }

    /**
     * Door-live follow-up: evaluates an animation track against a server-rebuilt animation
     * object (see the sidecar's per-channel {@code anim} recipe). The provider
     * ({@code providerOwner.providerMethod}, invoked on the receiver at the end of
     * {@code providerReceiver} with {@code providerArgs} resolved off the live tile)
     * rebuilds the clip the network packet would have installed; the implementation keeps
     * one entry per distinct recipe: the wall-clock entry time of the current argument
     * fingerprint (a provider returning null preserves the previous entry, so a rest state
     * holds its transit clip's end exactly like the vanilla client). The clip's
     * {@code clockField} is backdated to ({@code clockOwner.clockMethod()} minus elapsed),
     * so the track sees the true transit age with zero clock skew, and the static evaluator
     * ({@code evalOwner.evalMethod(evalArg, clip)}) runs the mod's own function. Called on
     * the SERVER thread; every failure mode comes back null, never a fabricated array.
     * Defaults to null (no animation synthesis).
     */
    default double[] evalAnim(String providerOwner, String providerMethod, FieldPath providerReceiver,
                              FieldPath[] providerArgs, String clockOwner, String clockMethod,
                              String clockField, String evalOwner, String evalMethod, String evalArg) {
        return null;
    }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
