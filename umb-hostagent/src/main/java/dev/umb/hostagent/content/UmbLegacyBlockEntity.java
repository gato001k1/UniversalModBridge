package dev.umb.hostagent.content;

import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ONE generic block entity for every legacy twin that carries {@code hasTileEntity}, not 372
 * subclasses (twin-first-mvp DESIGN §6): a {@link BlockEntityType} is only an identity + factory +
 * valid-block set, and the legacy class identity is carried entirely inside the opaque NBT blob
 * the bridge hands us -- exactly like vanilla's own {@code TileEntity.createAndLoadEntity}.
 *
 * Persistence: 26.2's {@link ValueOutput}/{@link ValueInput} has no {@code putByteArray}, so the
 * opaque {@code byte[]} from {@link TileHandle#saveNbt()} is stored Base64-encoded under a single
 * string key (see g2-laneB-progress.md for why this was chosen over int[]-packing or
 * CompoundTag.CODEC). The blob is never interpreted here -- only Lane A's legacy code understands
 * its contents.
 *
 * A block-wide {@code hasTileEntity} flag is not enough for legacy blocks: BlockDummyable commonly
 * returns null for dummy metadata and a real tile only for meta >= 6/12. Such a null is a normal
 * no-TE state, not a poisoned block entity. Creation failures that are not explained by a
 * metadata-specific null remain poisoned and are logged loudly.
 */
public final class UmbLegacyBlockEntity extends BlockEntity implements WorldlyContainer,
        BridgeRouter.EraRetryTarget {

    private static final String NBT_KEY = "umb_legacy_nbt";

    private volatile TileHandle handle;
    private volatile boolean poisoned;
    /**
     * Door-live follow-up (poison transparency): the first poison reason, surfaced as
     * {@code "poisoned:<reason>"} by {@link #legacyTileStatus}. Transient poisons also
     * record whether a later tick may retry ({@link #poisonRetryable}) and when
     * ({@link #poisonRetryAt}, game time).
     */
    private volatile String poisonReason;
    private volatile boolean poisonRetryable;
    private volatile long poisonRetryAt;
    private volatile long lastNeighborNotificationTick = Long.MIN_VALUE;
    private volatile net.minecraft.world.level.block.Block lastNeighborNotificationBlock;
    private final int initialLegacyMeta;
    private volatile int observedLegacyMeta;
    private volatile int noTileForMeta = Integer.MIN_VALUE;
    /** True only for the distinct async-era result; unlike noTileForMeta it is retryable. */
    private volatile boolean eraBootPending;
    private volatile String eraBootPendingEra;
    public static final AtomicLong NO_TILE_FOR_META = new AtomicLong();
    /** Set by loadAdditional/getUpdateTag before a handle exists yet; replayed once one is created. */
    private volatile byte[] pendingNbt;
    /** The generic BE itself is the vanilla 26.2 automation capability for legacy inventories. */
    private final LegacyWorldlyContainerAdapter inventoryAdapter;

    public UmbLegacyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        this(type, pos, state, -1);
    }

    UmbLegacyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int initialLegacyMeta) {
        super(type, pos, state);
        this.initialLegacyMeta = initialLegacyMeta;
        this.observedLegacyMeta = initialLegacyMeta;
        this.inventoryAdapter = new LegacyWorldlyContainerAdapter(this);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level.isClientSide() || poisoned) return;
        // Seed ONLY when the block identity itself encodes a non-zero meta (a variant twin). The base
        // block also maps to "<id>@0" in LEGACY_VARIANT_BLOCKS, so initialLegacyMeta==0 carries no
        // information - seeding it overwrote the real side-table meta HostWorldImpl.setBlock had just
        // written (live: every large_vehicle_door cell read back meta 0, so the core never got its TE).
        if (initialLegacyMeta > 0 && level instanceof ServerLevel serverLevel) {
            // HostWorldImpl.setBlock records the side-table meta after Level.setBlock; seed it
            // before the first legacy createTile call made from BlockEntity.setLevel.
            UmbMetadataSavedData.get(serverLevel).setMeta(getBlockPos().getX(), getBlockPos().getY(),
                    getBlockPos().getZ(), initialLegacyMeta);
        }
        ensureHandle();
    }

    /** Lazily creates the legacy TileHandle (and, via R4, boots the universe if this is the first need). */
    private void ensureHandle() {
        if (handle != null) return;
        try {
            UmbThread.assertServer();
            if (!(level instanceof ServerLevel serverLevel)) return;
            int meta = currentLegacyMeta();
            if (meta != observedLegacyMeta) {
                observedLegacyMeta = meta;
                noTileForMeta = Integer.MIN_VALUE;
                eraBootPending = false;
                eraBootPendingEra = null;
                poisoned = false;
                poisonReason = null;
                poisonRetryable = false;
                poisonRetryAt = 0L;
            }
            if (eraBootPending) {
                BridgeRouter router = currentRouter();
                if (router == null || router.isEraBooting(legacyBlockId())) return;
                // The boot-completion queue normally clears this first. This fallback covers a
                // race where the tile tick wins the same server tick as queue publication.
                eraBootPending = false;
                eraBootPendingEra = null;
            }
            if (noTileForMeta == meta) return;
            if (poisoned && !tryClearRetryablePoison(serverLevel.getGameTime())) return;
            String legacyId = legacyBlockId();
            if (legacyId == null) {
                poison("no legacy id for block at " + getBlockPos());
                return;
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                poisonRetryable("legacy bridge not booted (no bridge installed, or boot failed)",
                        serverLevel.getGameTime());
                return;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            BlockPos p = getBlockPos();
            if (bridge instanceof BridgeRouter router && router.isEraBootFailed(legacyId)) {
                poisonRetryable("legacy era boot failed for " + legacyId, serverLevel.getGameTime());
                return;
            }
            TileHandle h = bridge.createTile(legacyId, p.getX(), p.getY(), p.getZ());
            if (h instanceof EraBootingTileHandle booting) {
                if (bridge instanceof BridgeRouter router && router.deferTile(legacyId, this)) {
                    eraBootPending = true;
                    eraBootPendingEra = booting.era;
                    AgentLog.line("UMB-HOSTAGENT era-booting/retryable id=" + legacyId
                            + " era=" + booting.era + " at " + p);
                }
                return;
            }
            if (h == null) {
                noTileForMeta = meta;
                long n = NO_TILE_FOR_META.incrementAndGet();
                if (n == 1L) {
                    AgentLog.line("UMB-HOSTAGENT no-TE-for-meta id=" + legacyId + " meta=" + meta
                            + " at " + p + " (normal legacy metadata state; not poisoned)");
                }
                return;
            }
            byte[] pending = pendingNbt;
            if (pending != null) {
                h.loadNbt(pending);
                pendingNbt = null;
            }
            handle = h;
            syncToClients();
        } catch (Throwable t) {
            long now = (level instanceof ServerLevel sl) ? sl.getGameTime() : 0L;
            poisonRetryable("ensureHandle threw: " + t, now);
        }
    }

    private int currentLegacyMeta() {
        if (level instanceof ServerLevel serverLevel) {
            return UmbMetadataSavedData.get(serverLevel).getMeta(getBlockPos().getX(), getBlockPos().getY(),
                    getBlockPos().getZ());
        }
        return initialLegacyMeta;
    }

    private String legacyBlockId() {
        if (getBlockState().getBlock() instanceof UmbLegacyBlock b) return b.getLegacyId();
        return null;
    }

    private static BridgeRouter currentRouter() {
        LegacyBridge b = UmbBridgeHost.get();
        return b instanceof BridgeRouter router ? router : null;
    }

    @Override
    public void retryAfterEraBoot() {
        if (level == null || level.isClientSide() || isRemoved()) return;
        eraBootPending = false;
        eraBootPendingEra = null;
        ensureHandle();
    }

    @Override
    public boolean isEraRetryTargetAlive() {
        return level instanceof ServerLevel && !isRemoved();
    }

    private void poison(String why) {
        if (!poisoned) {
            poisoned = true;
            poisonReason = why;
            AgentLog.loud("UMB-HOSTAGENT tile poisoned at " + getBlockPos() + ": " + why);
        }
    }

    /**
     * Door-live follow-up (poison transparency): records a TRANSIENT poison that a
     * later tick may retry (bridge not booted yet, createTile throwing during the
     * placement race before onBlockPlacedBy/meta is set). Deterministic causes
     * (no legacy id, serverTick failures) keep using {@link #poison} and stay
     * permanent. Retries are rate-limited to one attempt per
     * {@link #POISON_RETRY_TICKS} game ticks so a genuinely broken tile cannot
     * hot-loop creation.
     */
    static final long POISON_RETRY_TICKS = 100L;

    private void poisonRetryable(String why, long nowGameTime) {
        if (!poisoned) {
            poisoned = true;
            poisonReason = why;
            AgentLog.loud("UMB-HOSTAGENT tile poisoned (retryable) at " + getBlockPos() + ": " + why);
        }
        poisonRetryable = true;
        poisonRetryAt = nowGameTime + POISON_RETRY_TICKS;
    }

    /**
     * The retry gate {@link #ensureHandle} consults: clears a retryable poison once
     * its backoff has elapsed so the tick attempts creation again. Deterministic
     * poisons never clear here. Package-visible for the headless gate.
     */
    boolean tryClearRetryablePoison(long nowGameTime) {
        if (!poisoned || !poisonRetryable) return false;
        if (nowGameTime < poisonRetryAt) return false;
        poisoned = false;
        poisonRetryable = false;
        poisonReason = null;
        poisonRetryAt = 0L;
        return true;
    }

    /** For tests only: inject a poison state without a level (mirrors poison/poisonRetryable). */
    void poisonForTest(String why, boolean retryable) {
        poisoned = true;
        poisonReason = why;
        poisonRetryable = retryable;
        poisonRetryAt = retryable ? POISON_RETRY_TICKS : 0L;
    }

    /** Control characters never reach logs/chat through a status string. */
    private static String sanitizeReason(String why) {
        if (why == null) return "unknown";
        String s = why.replaceAll("[\\p{Cntrl}]", "").trim();
        if (s.isEmpty()) return "unknown";
        return s.length() > 96 ? s.substring(0, 96) : s;
    }

    public boolean isPoisoned() {
        return poisoned;
    }

    /** Modern neighbor delivery may report one physical change through both host hooks. */
    boolean claimNeighborNotification(long gameTime, net.minecraft.world.level.block.Block neighbor) {
        if (gameTime == lastNeighborNotificationTick && neighbor == lastNeighborNotificationBlock) {
            return false;
        }
        lastNeighborNotificationTick = gameTime;
        lastNeighborNotificationBlock = neighbor;
        return true;
    }

    /** Forge/vanilla onNeighborChange seam: forward the real neighboring block to legacy code. */
    public void onNeighborChange(BlockPos neighborPos) {
        Level l = level;
        if (l == null || l.isClientSide()
                || !(getBlockState().getBlock() instanceof UmbLegacyBlock block)) return;
        block.forwardNeighborChanged(l, getBlockPos(), l.getBlockState(neighborPos).getBlock());
    }

    /**
     * PART 1 ordering fix: called once by {@code UmbLegacyBlock.setPlacedBy}, right after
     * onBlockPlacedBy (func_149689_a) returns - see that method's javadoc for the exact race this
     * closes. {@link #setLevel}/{@link #ensureHandle} runs DURING the initial placement's
     * {@code level.setBlock} call, strictly before onBlockPlacedBy has a chance to raise this
     * position's own metadata to its real, tile-entity-bearing value (confirmed live: a
     * BlockDummyable-shaped multiblock's controller position often starts below the meta threshold
     * its own {@code func_149915_a} needs). A retry here is safe: a no-op if a handle already
     * exists, and if {@code createTile} genuinely still fails it re-poisons with the same honest log
     * message - this never masks a real failure, it only gives legacy code a second, correctly-timed
     * chance.
     */
    void retryAfterPlacement() {
        if (handle != null) {
            return;
        }
        noTileForMeta = Integer.MIN_VALUE;
        eraBootPending = false;
        eraBootPendingEra = null;
        poisoned = false;
        poisonReason = null;
        poisonRetryable = false;
        poisonRetryAt = 0L;
        ensureHandle();
    }

    /** For tests only: package-visible access to the live handle. */
    /** Automation/diagnostics: "live", "poisoned:<reason>", "no-te-for-meta:<m>" or "pending". */
    public String legacyTileStatus() {
        if (poisoned) return "poisoned:" + sanitizeReason(poisonReason);
        if (handle != null) return "live";
        if (eraBootPending) return "era-booting/retryable";
        if (noTileForMeta != Integer.MIN_VALUE) return "no-te-for-meta:" + noTileForMeta;
        return "pending";
    }

    TileHandle handleForTest() {
        return handle;
    }

    /**
     * TILE-FIELD-SNAPSHOT lane: the live {@code TileHandle} backing this block entity, or null if
     * none exists yet (bridge not booted, or {@code ensureHandle} genuinely failed) — used by
     * {@link UmbLegacyBlock} to hand a GUI's opened {@link UmbMenuProvider} a snapshot source
     * without adding a new {@code dev.umb.bridge.api} lookup call: this block entity already IS the
     * one place that tracks "the live tile entity at this exact position" (see {@link #ensureHandle}).
     * Package-visible only — not part of {@code dev.umb.bridge.api}.
     */
    TileHandle currentHandle() {
        return handle;
    }

    /**
     * Door-live lane: public read of the live handle for server-side automation
     * ({@code AutomationControl.legacy_tile}), which lives in a different package and
     * cannot see {@link #currentHandle()}. Null when no tile exists (yet) - callers must
     * treat null as "nothing to observe", never an error.
     */
    public TileHandle legacyHandle() {
        return handle;
    }

    /** For tests only: inject a handle directly, bypassing setLevel/ensureHandle (no live ServerLevel headlessly). */
    void setHandleForTest(TileHandle h) {
        this.handle = h;
    }

    /** For tests only: inspect the not-yet-applied blob after loadAdditional but before a handle exists. */
    byte[] pendingNbtForTest() {
        return pendingNbt;
    }

    /** Bound via EntityBlock.getTicker, which already returns null on the client -- server only. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, UmbLegacyBlockEntity be) {
        if (be.poisoned) return;
        try {
            UmbThread.assertServer();
            be.ensureHandle();
            if (be.pendingClientSync) be.syncToClients();
            TileHandle h = be.handle;
            if (h == null) return;
            if (!h.isValid()) {
                be.poison("handle reported isValid()=false");
                return;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            BlockPos shapePos = be.getBlockPos();
            String shapeId = be.legacyBlockId();
            if (bridge != null) {
                bridge.invalidateShape(shapeId, shapePos.getX(), shapePos.getY(), shapePos.getZ());
            }
            h.tick();
            // Tile state is opaque to the host. Invalidate after every live tick so a callback that
            // reads an unlisted field cannot leave a cached collision/outline answer stale.
            if (bridge != null) {
                bridge.invalidateShape(shapeId, shapePos.getX(), shapePos.getY(), shapePos.getZ());
            }
            be.syncDynamicFields(h);
        } catch (Throwable t) {
            be.poison("serverTick threw: " + t);
        }
    }

    // ---- 26.2 Container/WorldlyContainer bridge for legacy IInventory automation ----

    @Override
    public int getContainerSize() { return inventoryAdapter.getContainerSize(); }

    @Override
    public boolean isEmpty() { return inventoryAdapter.isEmpty(); }

    @Override
    public ItemStack getItem(int slot) { return inventoryAdapter.getItem(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) { return inventoryAdapter.removeItem(slot, amount); }

    @Override
    public ItemStack removeItemNoUpdate(int slot) { return inventoryAdapter.removeItemNoUpdate(slot); }

    @Override
    public void setItem(int slot, ItemStack stack) { inventoryAdapter.setItem(slot, stack); }

    @Override
    public int getMaxStackSize() { return inventoryAdapter.getMaxStackSize(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) { return inventoryAdapter.canPlaceItem(slot, stack); }

    @Override
    public void setChanged() {
        super.setChanged();
        inventoryAdapter.setChanged();
    }

    @Override
    public boolean stillValid(Player player) { return inventoryAdapter.stillValid(player); }

    @Override
    public void clearContent() { inventoryAdapter.clearContent(); }

    @Override
    public int[] getSlotsForFace(Direction direction) { return inventoryAdapter.getSlotsForFace(direction); }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return inventoryAdapter.canPlaceItemThroughFace(slot, stack, direction);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return inventoryAdapter.canTakeItemThroughFace(slot, stack, direction);
    }

    /**
     * Moving-parts lane (+ door-live animation channels): publishes this block's dynamic TESR
     * fields (spinning radar dishes, turret yaw/pitch - the exact field set the extract-time
     * sidecar resolved for this block id, see {@link DynFieldChannel}) plus its server-evaluated
     * animation channels (door slide tracks - one {@code evalStatic} per distinct evaluator,
     * published per declared index under the full channel key the client's symbolic
     * {@code {k:channel}} op looks up) to the client frame channel. Only fields referenced by
     * extracted symbolic ops are ever read; only changed snapshots are published; a dead
     * handle clears the entry instead of leaving a frozen pose. Never throws.
     */
    private void syncDynamicFields(TileHandle h) {
        try {
            String legacyId = legacyBlockId();
            java.util.List<DynFieldChannel.FieldSpec> specs = DynFieldChannel.fieldsFor(legacyId);
            java.util.List<DynFieldChannel.ChannelSpec> chans =
                    DynFieldChannel.channelsFor(legacyId);
            if (specs.isEmpty() && chans.isEmpty()) return;
            if (dynRequest == null || !dynRequestId.equals(legacyId)) {
                dynRequest = DynFieldChannel.buildRequest(specs);
                dynRequestId = legacyId;
            }
            dev.umb.bridge.api.TileFieldSnapshot fieldSnap =
                    specs.isEmpty() || !h.isValid() ? dev.umb.bridge.api.TileFieldSnapshot.EMPTY
                            : h.snapshotFields(dynRequest);
            dev.umb.bridge.api.TileFieldSnapshot chanSnap =
                    chans.isEmpty() || !h.isValid() ? dev.umb.bridge.api.TileFieldSnapshot.EMPTY
                            : evalChannels(h, chans);
            dev.umb.bridge.api.TileFieldSnapshot merged = mergeSnapshots(fieldSnap, chanSnap);
            if (!DynFieldChannel.sameSnapshot(merged, lastDynSnapshot)) {
                lastDynSnapshot = merged;
                DynFieldChannel.publish(getBlockPos(), merged);
                // Dynamic collision is evaluated per position, but neighboring movement and
                // pathing code may retain a derived shape list for the current block update.
                // Send the ordinary block update on each TE-state transition so those caches
                // invalidate through the native level machinery as the legacy pose changes.
                if (level != null && !level.isClientSide()) {
                    level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
                }
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.syncDynamicFields", t, 2);
        }
    }

    /**
     * Door-live lane: evaluates each distinct channel evaluator once and maps the returned
     * track onto the sidecar's declared indices. A null track (evaluator missing, object
     * unresolvable, the mod's method threw) leaves every index of that evaluator absent -
     * never a fabricated zero - and an index past the end of a real track is absent too.
     */
    private static dev.umb.bridge.api.TileFieldSnapshot evalChannels(
            TileHandle h, java.util.List<DynFieldChannel.ChannelSpec> chans) {
        int n = chans.size();
        String[] keys = new String[n];
        double[] values = new double[n];
        boolean[] present = new boolean[n];
        java.util.Map<String, double[]> tracks = new java.util.HashMap<>();
        java.util.Map<String, double[]> animTracks = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            DynFieldChannel.ChannelSpec c = chans.get(i);
            keys[i] = c.key;
            double[] track;
            if (c.anim != null) {
                track = animTrack(h, c, animTracks);
            } else {
                track = staticTrack(h, c, tracks);
            }
            if (track != null && c.index < track.length) {
                values[i] = track[c.index];
                present[i] = true;
            }
        }
        return new dev.umb.bridge.api.TileFieldSnapshot(keys, values, present);
    }

    /** One plain-evaluator call per distinct evaluator per tick (shared by its indices). */
    private static double[] staticTrack(TileHandle h, DynFieldChannel.ChannelSpec c,
                                        java.util.Map<String, double[]> tracks) {
        String evalKey = c.owner + '\u0000' + c.method + '\u0000' + c.stringArg + '\u0000' + c.objectPath.key;
        double[] track = tracks.get(evalKey);
        if (!tracks.containsKey(evalKey)) {
            double[] fresh = null;
            try {
                fresh = h.evalStatic(c.owner, c.method, c.stringArg, c.objectPath);
            } catch (Throwable t) {
                fresh = null;
            }
            tracks.put(evalKey, fresh);
            track = fresh;
        }
        return track;
    }

    /**
     * Door-live follow-up: evaluates one animation-recipe channel (provider clip rebuilt
     * server-side, entry tracked and clocked inside the handle). Grouped per distinct
     * recipe like the plain evaluator above: one bridge call per recipe per tick.
     */
    private static double[] animTrack(TileHandle h, DynFieldChannel.ChannelSpec c,
                                      java.util.Map<String, double[]> animTracks) {
        DynFieldChannel.AnimSpec a = c.anim;
        String recipeKey = a.providerOwner + ' ' + a.providerMethod + ' '
                + pathKey(a.providerReceiver) + ' ' + argsKey(a.providerArgs) + ' '
                + a.clockOwner + ' ' + a.clockMethod + ' ' + a.clockField + ' '
                + c.owner + ' ' + c.method + ' ' + c.stringArg;
        double[] track = animTracks.get(recipeKey);
        if (!animTracks.containsKey(recipeKey)) {
            double[] fresh = null;
            try {
                fresh = h.evalAnim(a.providerOwner, a.providerMethod, a.providerReceiver,
                        a.providerArgs, a.clockOwner, a.clockMethod, a.clockField,
                        c.owner, c.method, c.stringArg);
            } catch (Throwable t) {
                fresh = null;
            }
            animTracks.put(recipeKey, fresh);
            track = fresh;
        }
        return track;
    }

    private static String pathKey(dev.umb.bridge.api.FieldPath p) {
        if (p == null || p.hopNames == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.hopNames.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(p.hopNames[i]);
        }
        return sb.append(']').toString();
    }

    private static String argsKey(dev.umb.bridge.api.FieldPath[] paths) {
        if (paths == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pathKey(paths[i]));
        }
        return sb.append(']').toString();
    }

    /** Concatenates two snapshots (fields, then channels) into one publishable snapshot. */
    private static dev.umb.bridge.api.TileFieldSnapshot mergeSnapshots(
            dev.umb.bridge.api.TileFieldSnapshot a,
            dev.umb.bridge.api.TileFieldSnapshot b) {
        if (a == null || a.keys.length == 0) return b;
        if (b == null || b.keys.length == 0) return a;
        String[] keys = new String[a.keys.length + b.keys.length];
        double[] values = new double[keys.length];
        boolean[] present = new boolean[keys.length];
        System.arraycopy(a.keys, 0, keys, 0, a.keys.length);
        System.arraycopy(a.values, 0, values, 0, a.values.length);
        System.arraycopy(a.present, 0, present, 0, a.present.length);
        System.arraycopy(b.keys, 0, keys, a.keys.length, b.keys.length);
        System.arraycopy(b.values, 0, values, a.values.length, b.values.length);
        System.arraycopy(b.present, 0, present, a.present.length, b.present.length);
        return new dev.umb.bridge.api.TileFieldSnapshot(keys, values, present);
    }

    private volatile dev.umb.bridge.api.TileFieldSnapshot lastDynSnapshot;
    private volatile dev.umb.bridge.api.FieldPath[] dynRequest;
    private volatile String dynRequestId;

    @Override
    public void setRemoved() {
        try {
            DynFieldChannel.clear(getBlockPos());
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.setRemoved", t, 3);
        }
        invalidateRenderCapture();
        super.setRemoved();
    }

    private void invalidateRenderCapture() {
        try {
            Class<?> renderer = Class.forName("dev.umb.objbridge.entity.LegacyBlockEntityRenderer");
            renderer.getMethod("invalidateBlockCaptures", String.class)
                    .invoke(null, legacyBlockId());
        } catch (Throwable ignored) {
            // The hostagent can run without objbridge; removal must remain best-effort.
        }
    }

    /** Client renderer hint: only a real legacy handle belongs to the multiblock core. */
    public boolean hasLegacyHandle() { return handle != null; }

    /** Render-thread routing hint; this prevents a 1.7.10 tile provider being queried for another era. */
    public String legacyEraForRender() {
        try {
            String id = legacyBlockId();
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            return bridge instanceof BridgeRouter
                    ? ((BridgeRouter) bridge).eraFor(id) : "1.7.10";
        } catch (Throwable ignored) {
            return "1.7.10";
        }
    }

    /** Runtime legacy class identity, used only for preserving state across an off/on block twin. */
    String legacyTileClassName() {
        TileHandle h = handle;
        return h == null || !h.isValid() ? null : h.legacyClassName();
    }

    /**
     * A legacy machine is allowed to replace its block with a visual on/off variant while its
     * 26.2 generic BlockEntity is being replaced by Level.setBlock.  Rehydrate the new handle
     * from the old handle only when both handles prove they wrap the same legacy TileEntity
     * class; this preserves inventories, power, progress, tanks, and mod-owned NBT universally
     * without carrying state into an unrelated block replacement.
     */
    void preserveLegacyStateFrom(UmbLegacyBlockEntity previous) {
        if (previous == null || previous == this) return;
        try {
            String from = previous.legacyTileClassName();
            String to = legacyTileClassName();
            if (from == null || !from.equals(to)) return;
            TileHandle oldHandle = previous.handle;
            TileHandle newHandle = handle;
            if (oldHandle == null || newHandle == null || !oldHandle.isValid()
                    || !newHandle.isValid()) return;
            byte[] blob = oldHandle.saveNbt();
            if (blob != null) {
                newHandle.loadNbt(blob);
                setChanged();
                AgentLog.line("UMB-HOSTAGENT preserved legacy TE state across block variant at "
                        + getBlockPos() + " class=" + from);
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.preserveLegacyStateFrom", t, 2);
        }
    }

    /** Client copies of server-only state, received via getUpdateTag (see SYNC below). */
    private volatile int syncedLegacyMeta = Integer.MIN_VALUE;
    private volatile boolean syncedCore;
    private volatile String syncedHelper;

    /** Metadata retained by the host bridge for legacy renderer facing/variant decisions. */
    public int legacyMetaForRender() {
        if (level != null && level.isClientSide() && syncedLegacyMeta != Integer.MIN_VALUE) return syncedLegacyMeta;
        return currentLegacyMeta();
    }

    /**
     * True for the cell that owns a live legacy tile entity (the multiblock CORE). Works on BOTH
     * sides: the legacy TileHandle exists only on the server copy, so the client relies on the
     * synced flag. The block-entity renderer used to read the server-only handle field, which is
     * always null on the client -> no TESR machine ever drew (live 2026-09-23: door/FENSU core
     * 'live' on the server, invisible on screen).
     *
     * <p>Turret follow-up: a live handle alone is not enough - multiblock filler cells can
     * hold proxy/dummy tiles whose class vanilla would never draw (its dispatch walks the
     * hierarchy for a bound TESR). The core test additionally requires the handle to render
     * as this block's sidecar TE class (no constraint when the sidecar names none), so a
     * proxy cell beside the chekhov core stops double-rendering the whole model.</p>
     */
    public boolean isLegacyCore() {
        if (level != null && level.isClientSide()) return syncedCore;
        return handle != null && isRenderedTile(handle, legacyBlockId());
    }

    /** Server-side core refinement: the handle's tile must render as the block's TE class. */
    static boolean isRenderedTile(TileHandle h, String legacyId) {
        if (h == null) return false;
        try {
            String teClass = DynFieldChannel.teClassFor(legacyId);
            if (teClass == null) return true;
            return h.rendersAs(teClass);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.isRenderedTile", t, 2);
            return true;
        }
    }

    /**
     * Door-live follow-up: the helper renderer class this tile dispatches to (resolved
     * server-side through the sidecar's dispatch descriptor, synced like {@link #isLegacyCore}).
     * Null when the block has no dispatch (the union fallback then applies) or before the
     * first tag arrives. The client block-entity renderer reads this reflectively.
     */
    public String dispatchRenderer() {
        if (level != null && level.isClientSide()) return syncedHelper;
        return dispatchHelper;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        try {
            byte[] blob = currentBlob();
            if (blob != null) {
                out.putString(NBT_KEY, Base64.getEncoder().encodeToString(blob));
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.saveAdditional", t, 3);
        }
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        // SYNC: present only in update tags sent to the client (never in saved chunk data).
        in.getInt("umbMeta").ifPresent(m -> syncedLegacyMeta = m);
        syncedCore = in.getBooleanOr("umbCore", syncedCore);
        in.getString("umbHelper").ifPresent(h -> syncedHelper = h);
        try {
            in.getString(NBT_KEY).ifPresent(this::acceptEncodedBlob);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.loadAdditional", t, 3);
        }
        LegacyBridge bridge = UmbBridgeHost.get();
        if (bridge != null) {
            BlockPos p = getBlockPos();
            bridge.invalidateShape(legacyBlockId(), p.getX(), p.getY(), p.getZ());
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("umbMeta", currentLegacyMeta());
        tag.putBoolean("umbCore", isLegacyCore());
        String helper = currentDispatchHelper();
        if (helper != null) tag.putString("umbHelper", helper);
        try {
            byte[] blob = currentBlob();
            if (blob != null) tag.putString(NBT_KEY, Base64.getEncoder().encodeToString(blob));
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.getUpdateTag", t, 2);
        }
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    /** Pushes core/meta state to clients (ClientboundBlockEntityDataPacket via sendBlockUpdated). */
    /** Set when a client sync was requested before the chunk was FULL; flushed on the next server tick. */
    private boolean pendingClientSync;

    private void syncToClients() {
        try {
            if (level != null && !level.isClientSide()) {
                // Never from inside chunk post-load: setChanged -> Level.blockEntityChanged ->
                // getChunkAt blocks on the chunk being loaded -> permanent server deadlock (live
                // 2026-09-25 11:43 via setLevel -> ensureHandle).
                // javap 26.2: ChunkSource.getChunkNow(int,int) is null until the chunk is FULL.
                if (level instanceof ServerLevel sl && sl.getChunkSource().getChunkNow(getBlockPos().getX() >> 4, getBlockPos().getZ() >> 4) == null) {
                    pendingClientSync = true;
                    return;
                }
                pendingClientSync = false;
                setChanged();
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.syncToClients", t, 2);
        }
    }

    /**
     * Door-live follow-up: resolves the render-dispatch helper class once per tile (the
     * declaration never changes for a placed tile) through the sidecar's dispatch
     * descriptor. Resolved lazily on tag build (server thread, handle present) so a tile
     * whose bridge call fails simply syncs no helper and the client keeps the union
     * fallback. Never throws.
     */
    private volatile String dispatchHelper;
    private volatile String dispatchHelperId;

    private String currentDispatchHelper() {
        try {
            TileHandle h = handle;
            if (h == null || !h.isValid()) return dispatchHelper;
            String legacyId = legacyBlockId();
            if (legacyId == null) return dispatchHelper;
            if (dispatchHelperId != null && dispatchHelperId.equals(legacyId)) return dispatchHelper;
            DynFieldChannel.DispatchSpec spec = DynFieldChannel.dispatchFor(legacyId);
            if (spec == null) {
                dispatchHelperId = legacyId;
                return dispatchHelper;
            }
            String resolved = null;
            try {
                resolved = h.dispatchRenderer(spec.owner, spec.method, spec.objectPath);
            } catch (Throwable t) {
                resolved = null;
            }
            dispatchHelper = resolved;
            dispatchHelperId = legacyId;
            return dispatchHelper;
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlockEntity.currentDispatchHelper", t, 2);
            return dispatchHelper;
        }
    }

    private byte[] currentBlob() {
        TileHandle h = handle;
        if (h != null) {
            try {
                return h.saveNbt();
            } catch (Throwable t) {
                AgentLog.error("UmbLegacyBlockEntity.currentBlob (handle.saveNbt)", t, 2);
                return null;
            }
        }
        return pendingNbt;
    }

    private void acceptEncodedBlob(String base64) {
        try {
            byte[] blob = Base64.getDecoder().decode(base64);
            TileHandle h = handle;
            if (h != null) {
                h.loadNbt(blob);
            } else {
                pendingNbt = blob;
            }
        } catch (IllegalArgumentException e) {
            AgentLog.error("UmbLegacyBlockEntity.acceptEncodedBlob: bad base64", e, 2);
        }
    }
}
