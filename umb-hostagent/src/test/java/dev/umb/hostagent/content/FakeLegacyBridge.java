package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An in-memory stand-in for Lane A's real umb-legacy LegacyBridge, used ONLY by tests. The real
 * jar is swapped in at integration time; this fake exercises exactly the same
 * dev.umb.bridge.api surface so Lane B's host-side code can be tested standalone (per DESIGN.md
 * LANE B: "Compile against the contract text above ... and a fake LegacyBridge for tests").
 */
final class FakeLegacyBridge implements LegacyBridge {

    private final AtomicBoolean booted = new AtomicBoolean(false);
    boolean bootShouldThrow = false;
    HostWorld lastBootWorld;
    List<double[]> collisionBoxesResult;
    List<double[]> selectionBoxesResult;
    int invalidateShapeCallCount;

    @Override
    public List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        return collisionBoxesResult;
    }

    @Override
    public List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        return selectionBoxesResult;
    }

    @Override
    public void invalidateShape(String legacyBlockId, int x, int y, int z) {
        invalidateShapeCallCount++;
    }

    /** What {@link #activate} returns; tests mutate this to exercise the true/false/no-GUI paths. */
    ActivationResult activateResult = new ActivationResult(true, new FakeContainerHandle("Brick Furnace"));
    /** The full argument list of the most recent {@link #activate} call, for assertions. */
    String lastActivateLegacyBlockId;
    int lastActivateSide;
    float lastActivateHitX;
    float lastActivateHitY;
    float lastActivateHitZ;
    int activateCallCount;
    /** The most recent {@link #clicked} call, for left-click assertions. */
    String lastClickedLegacyBlockId;
    int clickedCallCount;

    // ---- PART 1: placement / removal / neighbor-change call tracking ----
    int placedByCallCount;
    String lastPlacedByLegacyBlockId;
    int addedCallCount;
    String lastAddedLegacyBlockId;
    int neighborChangedCallCount;
    String lastNeighborChangedLegacyBlockId;
    String lastNeighborLegacyBlockId;
    int brokenCallCount;
    String lastBrokenLegacyBlockId;
    int lastBrokenMeta;
    boolean canPlaceAtResult = true;
    /** When non-null, {@link #added} invokes this on every call before returning - the reentrancy
     *  test points it back at {@code UmbLegacyBlock.onPlace(...)} (via a small adapter in the test
     *  itself) to simulate a legacy mod whose onBlockAdded always writes another block, proving the
     *  depth guard bounds the recursion instead of relying on this fake to bound it. */
    Runnable onAddedCallback;

    // ---- PART 2: item-use call tracking ----
    StackData useItemRightClickResult = StackData.EMPTY;
    int useItemRightClickCallCount;
    String lastUseItemRightClickLegacyItemId;
    ItemUseResult useItemOnBlockResult = ItemUseResult.DECLINED;
    int useItemOnBlockCallCount;
    String lastUseItemOnBlockLegacyItemId;
    List<String> itemTooltipResult = new ArrayList<>();
    StackData itemInventoryTickResult;
    int itemUseDurationResult;
    String itemUseActionResult = "none";
    float itemDestroySpeedResult = Float.NaN;
    boolean itemCanHarvestBlockResult;
    int itemDestroySpeedCalls;
    int itemCanHarvestBlockCalls;
    String lastMiningBlockId;
    int itemUsingTickCalls;
    int itemStoppedUsingCalls;
    int itemEatenCalls;

    @Override
    public void boot(HostWorld world) throws Exception {
        if (bootShouldThrow) throw new Exception("fake boot failure");
        lastBootWorld = world;
        booted.set(true);
    }

    @Override
    public boolean isBooted() {
        return booted.get();
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        return new FakeTileHandle(legacyBlockId, x, y, z);
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player, int side,
                                      float hitX, float hitY, float hitZ) {
        activateCallCount++;
        lastActivateLegacyBlockId = legacyBlockId;
        lastActivateSide = side;
        lastActivateHitX = hitX;
        lastActivateHitY = hitY;
        lastActivateHitZ = hitZ;
        return activateResult;
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        clickedCallCount++;
        lastClickedLegacyBlockId = legacyBlockId;
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        placedByCallCount++;
        lastPlacedByLegacyBlockId = legacyBlockId;
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        addedCallCount++;
        lastAddedLegacyBlockId = legacyBlockId;
        if (onAddedCallback != null) {
            onAddedCallback.run();
        }
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z, String neighborLegacyBlockId) {
        neighborChangedCallCount++;
        lastNeighborChangedLegacyBlockId = legacyBlockId;
        lastNeighborLegacyBlockId = neighborLegacyBlockId;
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        brokenCallCount++;
        lastBrokenLegacyBlockId = legacyBlockId;
        lastBrokenMeta = meta;
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        return canPlaceAtResult;
    }

    @Override
    public StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        useItemRightClickCallCount++;
        lastUseItemRightClickLegacyItemId = legacyItemId;
        return useItemRightClickResult;
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z, int side,
                                         float hitX, float hitY, float hitZ) {
        useItemOnBlockCallCount++;
        lastUseItemOnBlockLegacyItemId = legacyItemId;
        return useItemOnBlockResult;
    }

    @Override
    public List<String> itemTooltip(String legacyItemId, StackData stack, boolean advanced) {
        return itemTooltipResult;
    }

    @Override
    public StackData itemInventoryTick(String legacyItemId, StackData stack, HostPlayer player,
                                       int slot, boolean current) {
        return itemInventoryTickResult == null ? stack : itemInventoryTickResult;
    }

    @Override
    public int itemUseDuration(String legacyItemId, StackData stack) {
        return itemUseDurationResult;
    }

    @Override
    public String itemUseAction(String legacyItemId, StackData stack) {
        return itemUseActionResult;
    }

    @Override
    public float itemDestroySpeed(String legacyItemId, StackData stack, String legacyBlockId) {
        itemDestroySpeedCalls++;
        lastMiningBlockId = legacyBlockId;
        return itemDestroySpeedResult;
    }

    @Override
    public boolean itemCanHarvestBlock(String legacyItemId, StackData stack, String legacyBlockId) {
        itemCanHarvestBlockCalls++;
        lastMiningBlockId = legacyBlockId;
        return itemCanHarvestBlockResult;
    }

    @Override
    public void itemUsingTick(String legacyItemId, StackData stack, HostPlayer player, int remaining) {
        itemUsingTickCalls++;
    }

    @Override
    public void itemStoppedUsing(String legacyItemId, StackData stack, HostPlayer player, int remaining) {
        itemStoppedUsingCalls++;
    }

    @Override
    public StackData itemEaten(String legacyItemId, StackData stack, HostPlayer player) {
        itemEatenCalls++;
        return itemInventoryTickResult == null ? stack : itemInventoryTickResult;
    }

    @Override
    public void tickTile(TileHandle t) {
        t.tick();
    }

    @Override
    public void shutdown() {
        booted.set(false);
    }

    // ---- ENTITY-BRIDGE: call tracking ----
    EntityHandle restoreEntityResult;
    int restoreEntityCallCount;
    byte[] lastRestoreEntityNbt;

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        restoreEntityCallCount++;
        lastRestoreEntityNbt = nbt;
        return restoreEntityResult;
    }

    /** A minimal legacy entity: fixed transform, one counter that ticks up. */
    static final class FakeEntityHandle implements EntityHandle {
        final String legacyId;
        double x, y, z;
        double mx, my, mz;
        float yaw, pitch;
        float width = 0.5F, height = 0.5F;
        boolean collidable;
        double[] boundingBox;
        java.util.List<double[]> collisionBoxes;
        String entityIdentity;
        String vehicleIdentity;
        String passengerIdentity;
        String riderIdentity;
        int ticks;
        boolean valid = true;
        boolean hostRemovedCalled;
        byte[] saveNbtResult = new byte[0];

        FakeEntityHandle(String legacyId, double x, double y, double z) {
            this.legacyId = legacyId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public byte[] saveNbt() {
            return saveNbtResult;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getZ() {
            return z;
        }

        @Override
        public double getMotionX() {
            return mx;
        }

        @Override
        public double getMotionY() {
            return my;
        }

        @Override
        public double getMotionZ() {
            return mz;
        }

        @Override
        public float getYaw() {
            return yaw;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public float getWidth() {
            return width;
        }

        @Override
        public float getHeight() {
            return height;
        }

        @Override
        public String legacyEntityIdentity() {
            return entityIdentity;
        }

        @Override
        public String legacyVehicleIdentity() {
            return vehicleIdentity;
        }

        @Override
        public String legacyPassengerIdentity() {
            return passengerIdentity;
        }

        @Override
        public String riderIdentity() {
            return riderIdentity;
        }

        @Override
        public boolean canBeCollidedWith() {
            return collidable;
        }

        @Override
        public double[] getBoundingBox() {
            return boundingBox;
        }

        @Override
        public java.util.List<double[]> getCollisionBoxes() {
            return collisionBoxes;
        }

        @Override
        public String legacyEntityId() {
            return legacyId;
        }

        @Override
        public void hostRemoved() {
            hostRemovedCalled = true;
        }
    }

    /** A minimal legacy tile: one counter that ticks up, saved/loaded as a fixed-format blob. */
    static final class FakeTileHandle implements TileHandle {
        final String legacyId;
        final int x, y, z;
        int ticks;
        boolean valid = true;

        FakeTileHandle(String legacyId, int x, int y, int z) {
            this.legacyId = legacyId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public byte[] saveNbt() {
            return ("ticks=" + ticks).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void loadNbt(byte[] nbt) {
            String s = new String(nbt, StandardCharsets.UTF_8);
            if (s.startsWith("ticks=")) {
                ticks = Integer.parseInt(s.substring("ticks=".length()));
            }
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public String legacyClassName() {
            return "fake." + legacyId;
        }

        /** TILE-FIELD-SNAPSHOT lane: tests populate this by key (see FieldPath#key) to exercise a
         *  live tile-field snapshot without a real legacy TileEntity. A key absent from this map
         *  reports present=false, matching the real TileHandleImpl's "never fake a value" contract. */
        final Map<String, Double> fields = new HashMap<>();

        /** Door-live lane: tests set this to exercise server-evaluated animation channels
         *  without a real legacy evaluator; null (the default) means "no evaluator", exactly
         *  like the real TileHandleImpl's null for a missing method. */
        double[] evalTrack = null;
        int evalCalls = 0;

        @Override
        public double[] evalStatic(String owner, String name, String stringArg,
                dev.umb.bridge.api.FieldPath objectPath) {
            evalCalls++;
            return evalTrack;
        }

        /** Door-live follow-up: hook for animation-recipe channels; null = no synthesis. */
        double[] evalAnimTrack = null;
        int evalAnimCalls = 0;

        @Override
        public double[] evalAnim(String providerOwner, String providerMethod,
                dev.umb.bridge.api.FieldPath providerReceiver,
                dev.umb.bridge.api.FieldPath[] providerArgs, String clockOwner, String clockMethod,
                String clockField, String evalOwner, String evalMethod, String evalArg) {
            evalAnimCalls++;
            return evalAnimTrack;
        }

        /** Door-live follow-up: hook for the render-dispatch helper; null = union fallback. */
        String dispatchHelper = null;

        @Override
        public String dispatchRenderer(String owner, String name,
                dev.umb.bridge.api.FieldPath objectPath) {
            return dispatchHelper;
        }

        /** Turret follow-up: hook for the render-class check; null = no constraint. */
        Boolean rendersAsResult = null;

        @Override
        public boolean rendersAs(String teClass) {
            return rendersAsResult != null ? rendersAsResult.booleanValue() : true;
        }

        @Override
        public TileFieldSnapshot snapshotFields(FieldPath[] paths) {            if (paths == null || paths.length == 0) {
                return TileFieldSnapshot.EMPTY;
            }
            String[] keys = new String[paths.length];
            double[] values = new double[paths.length];
            boolean[] present = new boolean[paths.length];
            for (int i = 0; i < paths.length; i++) {
                keys[i] = paths[i].key;
                Double v = valid ? fields.get(paths[i].key) : null;
                if (v != null) {
                    values[i] = v;
                    present[i] = true;
                }
            }
            return new TileFieldSnapshot(keys, values, present);
        }
    }

    /** A minimal legacy container: two machine slots at fixed 1.7.10-style coordinates, one progress int. */
    static final class FakeContainerHandle implements ContainerHandle {
        private final String title;
        private final Map<Integer, StackData> stacks = new HashMap<>();
        private final int[] xs;
        private final int[] ys;
        int progress;
        boolean closed;
        StackData quickMoveResult;
        int quickMoveCallCount;
        int lastQuickMoveIndex = -1;
        int lastQuickMoveMachineSlotCount = -1;

        FakeContainerHandle(String title) {
            this(title, new int[]{56, 116}, new int[]{17, 35});
        }

        /** For tests that need a specific (real-container-shaped) slot layout, e.g. the live
         * HBM Brick Furnace's own machine slots at (62,35)(35,17)(116,35)(35,53). */
        FakeContainerHandle(String title, int[] xs, int[] ys) {
            this.title = title;
            this.xs = xs;
            this.ys = ys;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public int slotCount() {
            return xs.length;
        }

        @Override
        public SlotData[] slots() {
            SlotData[] out = new SlotData[xs.length];
            for (int i = 0; i < xs.length; i++) {
                StackData s = stacks.getOrDefault(i, StackData.EMPTY);
                out[i] = new SlotData(i, xs[i], ys[i], s);
            }
            return out;
        }

        @Override
        public void setSlot(int index, StackData s) {
            stacks.put(index, s == null ? StackData.EMPTY : s);
        }

        @Override
        public StackData takeSlot(int index, int amount) {
            StackData cur = stacks.getOrDefault(index, StackData.EMPTY);
            if (cur.isEmpty()) return StackData.EMPTY;
            int take = Math.min(amount, cur.count);
            StackData taken = new StackData(cur.legacyId, take, cur.damage, cur.nbt);
            int remain = cur.count - take;
            stacks.put(index, remain > 0 ? new StackData(cur.legacyId, remain, cur.damage, cur.nbt) : StackData.EMPTY);
            return taken;
        }

        @Override
        public int[] syncData() {
            return new int[]{progress};
        }

        @Override
        public StackData quickMove(int menuIndex, int machineSlotCount) {
            quickMoveCallCount++;
            lastQuickMoveIndex = menuIndex;
            lastQuickMoveMachineSlotCount = machineSlotCount;
            return quickMoveResult;
        }

        @Override
        public void close() {
            closed = true;
        }

        // ---- CONTAINER-POLICY: canPlace call tracking ----
        /** What {@link #canPlace} answers; tests mutate this to exercise the accept/reject paths. */
        boolean canPlaceResult = true;
        boolean canPlaceShouldThrow;
        int canPlaceCallCount;
        int lastCanPlaceIndex = -1;
        StackData lastCanPlaceStack;

        @Override
        public boolean canPlace(int index, StackData s) {
            canPlaceCallCount++;
            lastCanPlaceIndex = index;
            lastCanPlaceStack = s;
            if (canPlaceShouldThrow) throw new IllegalStateException("fake canPlace failure");
            return canPlaceResult;
        }
    }
}
