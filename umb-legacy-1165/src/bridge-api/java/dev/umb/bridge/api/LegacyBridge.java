package dev.umb.bridge.api;

/** The legacy universe, implemented INSIDE the LegacyLoader; the single entry point. */
public interface LegacyBridge {
    /** Host-independent snapshot of the legacy render camera. */
    final class CameraState {
        public final boolean overridden;
        public final double x;
        public final double y;
        public final double z;
        public final float pitch;
        public final float yaw;

        public CameraState(boolean overridden, double x, double y, double z,
                           float pitch, float yaw) {
            this.overridden = overridden;
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
        }
    }

    void    boot(HostWorld world) throws Exception;   // runs the FML lifecycle once
    boolean isBooted();
    /** create the legacy TileEntity for a placed twin; null if the block has none */
    TileHandle createTile(String legacyBlockId, int x, int y, int z);
    /**
     * right-click: bridges 1.7.10's onBlockActivated (func_149727_a) for ANY twin, not only ones
     * with a tile entity. hitX/hitY/hitZ are block-RELATIVE floats in 0..1, matching 1.7.10's own
     * convention. Never throws - a failure comes back as {@link ActivationResult#DECLINED}.
     */
    ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player, int side,
                               float hitX, float hitY, float hitZ);
    /** left click: bridges onBlockClicked (func_149699_a). No return value in 1.7.10. Must not throw. */
    void    clicked(String legacyBlockId, int x, int y, int z, HostPlayer player);
    void    tickTile(TileHandle t);
    void    shutdown();

    // ---- PART 1 (INTERACTION-BRIDGE.md / GENERALIZATION-PLAN.md GAP 3): placement / removal /
    // neighbor-change, so a mod's OWN placement code can build a multiblock out of its own filler
    // blocks through the world facade that already works, and breaking it can undo that generically.
    // Every method here is void-or-primitive-returning and must never throw across the boundary.

    /**
     * onBlockPlacedBy (func_149689_a): the block just appeared because a living entity (in practice,
     * always a player at this boundary) placed it. This is where a multiblock controller writes its
     * own filler/placeholder blocks (BlockDummyable-shaped mods, and any other mod that does the same
     * thing under a different class name) via the world facade - no mod-specific code needed here,
     * the mod's own onBlockPlacedBy does the work.
     */
    void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player);

    /** Exact item stack supplied by 26.2 BlockItem.place, including damage and NBT. */
    default void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player,
                          StackData placedStack) {
        placedBy(legacyBlockId, x, y, z, player);
    }

    /**
     * onBlockAdded (func_149726_b): fired once per genuine new block identity appearing at a
     * position - the host filters out its own meta-driven variant swaps (same legacy id, different
     * 26.2 Block instance) before calling this, so it fires exactly when 1.7.10's own World.setBlock
     * would have fired it, not on every metadata change.
     */
    void added(String legacyBlockId, int x, int y, int z);

    /**
     * onNeighborBlockChange (func_149695_a): one of this block's neighbors changed.
     * neighborLegacyBlockId is the neighbor's legacy id if it is a known twin, else null/"minecraft:air".
     */
    void neighborChanged(String legacyBlockId, int x, int y, int z, String neighborLegacyBlockId);

    /**
     * onBlockDestroyedByPlayer (func_149664_b) then breakBlock (func_149749_a), in that order -
     * called BEFORE the block is actually removed from the 26.2 level, so the mod's own cleanup code
     * (removing a multiblock's filler positions, ejecting a tile entity's inventory) still sees valid
     * world state at every position it touches. meta is this block's own metadata at removal time.
     */
    void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player);

    /** canPlaceBlockAt (func_149742_c). Returns true (never block placement) if the bridge could not run. */
    boolean canPlaceAt(String legacyBlockId, int x, int y, int z);

    // ---- PART 2 (INTERACTION-BRIDGE.md): item-side interaction (igniters, detonators, wrenches,
    // guns) that has no block-side onBlockActivated branch at all.

    /**
     * onItemRightClick (func_77659_a): fired when the player right-clicks holding this item and is
     * NOT targeting a block (or the targeted block's own onBlockActivated already declined - see
     * PART E1's dispatch order). Returns the StackData that must end up back in the player's hand
     * (FM-4: 1.7.10 items mutate the passed stack in place; FM-5: the return may be a DIFFERENT
     * stack, e.g. bucket -&gt; empty bucket). Null only when the bridge could not run at all (unknown
     * legacy item, not booted, or a throw) - the caller must then leave the held stack untouched,
     * never write an erasing empty back (the exact FM-6 shape already fixed once for setHeldItem).
     */
    StackData useItemRightClick(String legacyItemId, HostPlayer player);

    /**
     * onItemUse (func_77648_a): fired when the player right-clicks this item WHILE targeting a
     * block, after that block's own onBlockActivated declined. x/y/z is the targeted block position;
     * hitX/hitY/hitZ are block-relative floats in 0..1 (FM-1 convention, same as {@link #activate}).
     */
    ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z, int side,
                                  float hitX, float hitY, float hitZ);

    // ---- ENTITY-BRIDGE: existence, spawning and persistence for legacy Entity twins ----

    /**
     * The host loaded a saved native twin from disk (world reload) and needs the corresponding
     * live legacy {@code Entity} reconstructed from the blob {@link EntityHandle#saveNbt()} wrote.
     * Uses the generic vanilla/Forge factory {@code EntityList.createEntityFromNBT} (func_75615_a),
     * which resolves the right subclass from the blob's own "id" tag with zero mod-specific code -
     * the exact mechanism vanilla's own chunk loader uses for every entity in a chunk. Returns null
     * if the blob is corrupt, the class is no longer registered, or the bridge is not booted yet
     * (the host must then retry later, same "pendingNbt" pattern {@code UmbLegacyBlockEntity}
     * already uses for tile entities). Never throws.
     */
    EntityHandle restoreEntity(byte[] nbt);

    // ---- TICK/CONTACT lane: block ticks + entity-inside, the two block callbacks that make
    // fluids flow, fire spread, gases rise, and conveyors/gas/spikes act on the player. Both have
    // no-op defaults ONLY so eras/fakes that have not bridged them yet (umb-legacy-1122, test
    // fakes) keep compiling - the real 1.7.10 LegacyBridgeImpl overrides both.

    /**
     * updateTick (func_149674_a): ONE legacy method serves BOTH 26.2 tick kinds - a scheduled tick
     * (isRandom=false: the return leg of UmbWorld.func_147464_a -> HostWorld.scheduleTick) and a
     * random tick (isRandom=true: only ever delivered when the snapshot said
     * func_149653_t/getTickRandomly, via Registrar's randomTicks() opt-in). isRandom is
     * diagnostics-only; the dispatched legacy call is identical. Must never throw.
     */
    default void tickBlock(String legacyBlockId, int x, int y, int z, boolean isRandom) {
    }

    /**
     * onEntityCollidedWithBlock (func_149670_a), PLAYER-FIRST scope: conveyors pushing the player,
     * gas/fire/spike blocks hurting the player. A non-player entity does not cross this boundary
     * yet (there is no legacy facade for an arbitrary native entity) - an honest, documented gap
     * counted by the caller, not silently absorbed here. Must never throw.
     */
    default void entityInside(String legacyBlockId, int x, int y, int z, HostPlayer player) {
    }

    // ---- BLOCK SURFACE: placement, drops, contact, client display and comparator ------------

    /**
     * Door-live lane: the legacy block's live collision bounds
     * ({@code Block.getCollisionBoundingBoxFromPool}, 1.7.10) for one cell, for blocks
     * whose bounds genuinely depend on tile-entity state (doors, hatches - never guessed:
     * the host only calls this for block ids the extract-time sidecar flags as
     * state-following, and only on the server thread where the universe is live).
     * Returns {@code {minX, minY, minZ, maxX, maxY, maxZ}} in 1.7.10 block-local
     * coordinates, or null when the block has no live bounds right now (no tile, null
     * box, any failure) - the caller then keeps its statically extracted shape. The
     * values feed 26.2 collision directly, so they must be the box corners, never voxel
     * indices or metadata. Must never throw across the boundary.
     */
    default double[] collisionBounds(String legacyBlockId, int x, int y, int z) {
        return null;
    }

    /**
     * Current per-position collision geometry, in block-local coordinates.  Implementations must
     * evaluate the legacy block's state-dependent bounds callback before
     * {@code addCollisionBoxesToList}; an empty list is a real open/passable result, while null
     * means the live query was unavailable and callers may retain their static fallback.
     */
    default java.util.List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        return null;
    }

    /**
     * Current per-position selection/outline geometry, in block-local coordinates. The legacy
     * implementation must run {@code setBlockBoundsBasedOnState} (func_149719_a) before
     * {@code getSelectedBoundingBoxFromPool} (func_149633_g). An empty list is an authoritative
     * non-selectable result; null means the live query was unavailable.
     */
    default java.util.List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        return null;
    }

    /** Invalidates cached live shape answers affected by a tile or neighbor state change. */
    default void invalidateShape(String legacyBlockId, int x, int y, int z) {
    }

    default int placementMetadata(String legacyBlockId, int x, int y, int z, int side,
                                  float hitX, float hitY, float hitZ, int meta) {
        return meta;
    }

    default java.util.List<StackData> blockDrops(String legacyBlockId, int x, int y, int z,
                                                  int meta, int fortune) {
        return java.util.Collections.emptyList();
    }

    default void stepOn(String legacyBlockId, int x, int y, int z, HostPlayer player) {
    }

    default void fallOn(String legacyBlockId, int x, int y, int z, HostPlayer player,
                        float distance) {
    }

    default void animateBlock(String legacyBlockId, int x, int y, int z) {
    }

    default boolean hasComparatorInputOverride(String legacyBlockId) {
        return false;
    }

    default int comparatorInputOverride(String legacyBlockId, int x, int y, int z) {
        return 0;
    }

    // ---- ITEM SURFACE: tooltip, inventory tick, and use lifecycle --------------------------

    default java.util.List<String> itemTooltip(String legacyItemId, StackData stack, boolean advanced) {
        return java.util.Collections.emptyList();
    }

    default StackData itemInventoryTick(String legacyItemId, StackData stack, HostPlayer player,
                                        int slot, boolean current) {
        return stack;
    }

    default int itemUseDuration(String legacyItemId, StackData stack) {
        return 0;
    }

    default String itemUseAction(String legacyItemId, StackData stack) {
        return "none";
    }

    /**
     * Legacy Item.func_150893_a: the live destroy-speed answer for this item/block pair.
     * The 26.2 adapter calls this from Item.getDestroySpeed; a finite result is authoritative.
     */
    default float itemDestroySpeed(String legacyItemId, StackData stack, String legacyBlockId) {
        return Float.NaN;
    }

    /**
     * Legacy Item.func_150897_b (canHarvestBlock): the live correct-tool answer for this block.
     * The 26.2 adapter calls this from Item.isCorrectToolForDrops.
     */
    default boolean itemCanHarvestBlock(String legacyItemId, StackData stack, String legacyBlockId) {
        return false;
    }

    default void itemUsingTick(String legacyItemId, StackData stack, HostPlayer player,
                               int remaining) {
    }

    default void itemStoppedUsing(String legacyItemId, StackData stack, HostPlayer player,
                                  int remaining) {
    }

    default StackData itemEaten(String legacyItemId, StackData stack, HostPlayer player) {
        return stack;
    }

    // ---- FML/Forge lifecycle events: host server-tick seam -------------------------------

    /**
     * Dispatches one host server-tick phase into the legacy universe. {@code endPhase=false}
     * means START and {@code endPhase=true} means END. The host supplies the current overworld
     * facade and the players visible to the host server. Default no-op keeps older eras/fakes
     * source-compatible while the 1.7.10 bridge implements the real FML/Forge posts.
     */
    default void tickEvents(HostWorld world, HostPlayer[] players, boolean endPhase) {
    }

    /** Extracts one legacy HUD frame into the shared GL-EMU mesh format. */
    default GlEmulationSession.Mesh renderHud(String playerName, float partialTicks,
                                              int width, int height) {
        return null;
    }

    /** Extracts the current persistent legacy GuiContainer's panel layers into a GL-EMU mesh. */
    default GlEmulationSession.Mesh renderGui(String playerName, float partialTicks,
                                               int width, int height) {
        return null;
    }

    /** Returns the last bounded legacy renderViewEntity snapshot for this player. */
    default CameraState cameraState(String playerName) {
        return null;
    }

    /**
     * Last third-person camera distance (in blocks) written by legacy client code into its own
     * EntityRenderer (1.7.10 {@code field_78490_B}/{@code thirdPersonDistance}, javap-verified on
     * the 1.7.10 SRG runtime; legacy mods write it through the client facade's EntityRenderer).
     * Captured after the bounded legacy client tick, per player name, like {@link #cameraState}.
     * Returns NaN when no legacy client tick has recorded one (honest absence, never a guess) -
     * the host then keeps its own distance. Must never throw across the boundary.
     */
    default float thirdPersonDistance(String playerName) {
        return Float.NaN;
    }

    /** Ticks every live legacy entity exactly once for one host server tick. */
    default void tickEntities() {
    }

    /** Reconciles the live host player snapshot with UmbWorld's cached legacy facades. */
    default void syncPlayers(java.util.List<HostPlayer> livePlayers) {
    }

    /** Fires a player-respawn lifecycle event after the host replaces a player instance. */
    default void playerRespawn(HostPlayer player) {
    }

    /** Delivers a statically proven legacy GUI packet action; unknown recipes are rejected. */
    default void guiButtonPacket(String guiClass, int buttonId, int x, int y, int z,
                                 String[] args) {
    }

    /**
     * Delivers one generic legacy GuiContainer mouse click from the host menu.  The coordinates
     * are panel-relative ({@code guiX/guiY}) plus the already-scaled host screen coordinate used
     * by the legacy GuiContainer hit test ({@code screenX/screenY}); the bridge implementation
     * invokes the legacy mouse-click and mouse-release methods on the client GUI facade and lets
     * the mod's own packet code reach its server handler through the loopback.
     */
    default boolean guiMouseClick(String guiClass, int x, int y, int z,
                                  int guiX, int guiY, int button, int screenX, int screenY) {
        return false;
    }

    /** Host client input; default keeps older eras/fakes source-compatible. */
    default boolean acceptInput(HostPlayer player, InputData input) { return false; }

    /** Server-to-client effects drained after the legacy server tick. */
    default java.util.List<EffectData> drainClientEffects() {
        return java.util.Collections.emptyList();
    }
}
