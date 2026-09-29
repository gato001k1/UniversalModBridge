package dev.umb.hostagent.content;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostEntity;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link HostWorld} over a {@link ServerLevel}. Every method is expected to run on the server
 * thread only (the bridge's own contract); nothing here spawns a thread or defers work.
 *
 * Block-id round-tripping covers legacy twins and every vanilla block with a 1.7.10 counterpart
 * (see {@link #vanillaLegacyKey}); a vanilla block without one reports as "minecraft:air".
 *
 * <p>ENTITY-BRIDGE: also implements {@link HostLevel} - see {@code UmbWorld.create}'s
 * {@code instanceof HostLevel} check on the legacy side for why one object implements both.</p>
 */
final class HostWorldImpl implements HostWorld, HostLevel {

    static final float DEFAULT_LEGACY_EXPLOSION_MAX_RADIUS = 4.0F;
    static final float HARD_LEGACY_EXPLOSION_MAX_RADIUS = 8.0F;
    private static final String EXPLOSION_MAX_RADIUS_PROPERTY = "umb.legacy.explosion.maxRadius";
    private static final Set<String> LOGGED_NATIVE_SOUNDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_NATIVE_PARTICLES = ConcurrentHashMap.newKeySet();
    private static final AtomicLong NATIVE_SOUND_COUNT = new AtomicLong();
    private static final AtomicLong NATIVE_PARTICLE_COUNT = new AtomicLong();

    private static final java.util.Map<ServerLevel, java.util.Map<EntityHandle, UmbLegacyEntity>> LEVEL_TWINS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /**
     * Last-observed native states for non-blocking legacy reads. A chunk can briefly be absent from
     * ChunkSource.getChunkNow while another server path unloads or posts it; returning air in that
     * interval lets legacy movement tunnel through a known solid block. This cache never loads a
     * chunk and is bounded per host level.
     */
    private static final int MAX_CACHED_BLOCK_STATES = 32768;
    private static final Map<ServerLevel, Map<Long, BlockState>> LEVEL_BLOCK_STATE_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final ServerLevel level;
    private final ConcurrentLinkedQueue<EffectData> clientEffects = new ConcurrentLinkedQueue<>();
    private final java.util.Map<EntityHandle, UmbLegacyEntity> entityTwins;
    private final Map<Long, BlockState> loadedBlockStateCache;
    /**
     * A host passenger can be absent for one server observation while a legacy mount is being
     * applied.  Keep that observation separate per handle: a null read must not immediately
     * overwrite the legacy-side rider, but a sustained host-side dismount still must propagate.
     */
    private final java.util.Map<EntityHandle, RiderSyncState> riderSyncStates =
            new java.util.IdentityHashMap<>();
    /** Handles for which the native passenger graph repaired a stale legacy rider link. */
    private final java.util.Set<EntityHandle> riderRepairLogged =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** One bounded decision trace per handle; rider polling must not flood the server log. */
    private final java.util.Set<EntityHandle> riderNullDecisionLogged =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final int MAX_CLIENT_EFFECT_QUEUE = 4096;

    @Override
    public List<HostPlayer> getPlayers() {
        List<HostPlayer> players = new ArrayList<HostPlayer>();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player != null) {
                players.add(new HostPlayerImpl(player));
            }
        }
        return players;
    }

    @Override
    public boolean isChunkWatched(String playerIdentity, int chunkX, int chunkZ) {
        if (level == null || playerIdentity == null) return false;
        try {
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                if (player != null && player.getUUID().toString().equals(playerIdentity)) {
                    return level.getChunkSource().chunkMap.isChunkTracked(player, chunkX, chunkZ);
                }
            }
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.isChunkWatched", t, 2);
        }
        return false;
    }

    static final class RiderSyncState {
        private static final int MISSING_RIDER_GRACE_TICKS = 4;
        private int missingTicks;
        private boolean hostRiderObserved;
        private boolean explicitDismount;
        private HostPlayer lastHostRider;
        private net.minecraft.server.level.ServerPlayer lastNativeRider;

        void observeHostRider() {
            missingTicks = 0;
            hostRiderObserved = true;
            explicitDismount = false;
        }

        void observeHostRider(HostPlayer rider) {
            lastHostRider = rider;
            observeHostRider();
        }

        void observeHostRider(net.minecraft.server.level.ServerPlayer rider) {
            lastNativeRider = rider;
            observeHostRider(new HostPlayerImpl(rider));
        }

        void observeLegacyInteraction() {
            observeLegacyInteraction(null, true);
        }

        void observeLegacyInteraction(String desiredName) {
            observeLegacyInteraction(desiredName, desiredName == null);
        }

        void observeLegacyInteraction(String desiredName, boolean explicitDismount) {
            // A legacy-side mount/dismount starts a new synchronization attempt. Clear the
            // observation counter so diagnostics describe this mount independently.
            missingTicks = 0;
            hostRiderObserved = false;
            this.explicitDismount = explicitDismount;
            lastHostRider = null;
            lastNativeRider = null;
        }

        /**
         * A native passenger removal (host rideTick sneak-dismount or any other host-side
         * stopRiding) is an explicit dismount, not a transient empty read: drop the retained
         * rider so the next prepareEntity propagates null to legacy instead of re-asserting
         * the stale link (which the legacy-to-host rider sync would then mirror back as a
         * re-mount every tick - the live Shift-stuck loop). Same terminal state as an
         * explicit legacy-side dismount, so the existing grace/retain tests for that state
         * apply unchanged; empty-read retention (observeMissingHostRider) is untouched, so a
         * mount that is only momentarily absent from the native graph is still preserved.
         */
        void observeHostDismount() {
            missingTicks = 0;
            hostRiderObserved = false;
            explicitDismount = true;
            lastHostRider = null;
            lastNativeRider = null;
        }

        /** Returns true after an explicit or sustained rider absence, never on one empty read. */
        boolean observeMissingHostRider(boolean legacyRiderPresent) {
            return observeMissingHostRider(legacyRiderPresent, false);
        }

        /** Retain a legacy pilot until a real host-side dismount is observable. */
        boolean observeMissingHostRider(boolean legacyRiderPresent, boolean hostDismountConfirmed) {
            if (explicitDismount) {
                missingTicks = 0;
                hostRiderObserved = false;
                lastHostRider = null;
                lastNativeRider = null;
                return true;
            }
            // Once a concrete host rider has been observed, an empty native passenger read is
            // only an ordering artifact until the legacy side explicitly dismounts.  The native
            // graph is rebuilt asynchronously and can report getVehicle()==null for several
            // consecutive observations; using that transient value as a dismount reintroduces
            // the setHostRider(null) -> remove-twin loop.
            if (lastHostRider != null) {
                missingTicks = 0;
                return false;
            }
            if (lastHostRider == null) {
                if (legacyRiderPresent && !explicitDismount && !hostDismountConfirmed) {
                    missingTicks = 0;
                    return false;
                }
                missingTicks = 0;
                hostRiderObserved = false;
                lastHostRider = null;
                lastNativeRider = null;
                return true;
            }
            if (legacyRiderPresent && !hostDismountConfirmed) {
                missingTicks = 0;
                return false;
            }
            missingTicks++;
            if (missingTicks < MISSING_RIDER_GRACE_TICKS) {
                return false;
            }
            missingTicks = 0;
            hostRiderObserved = false;
            lastHostRider = null;
            lastNativeRider = null;
            return true;
        }

        boolean explicitDismount() {
            return explicitDismount;
        }

        boolean allowLegacyMirror(String desiredName) {
            // Legacy riddenByEntity/ridingEntity is authoritative for the legacy universe.  A
            // non-null desired name must always be allowed to repair the native mirror.  A
            // legacy-side null is a clear signal once the bounded native-absence grace expires.
            return true;
        }

        void reset() {
            missingTicks = 0;
            hostRiderObserved = false;
        }

        int missingTicks() {
            return missingTicks;
        }

        HostPlayer lastHostRider() {
            return lastHostRider;
        }

        net.minecraft.server.level.ServerPlayer lastNativeRider() {
            return lastNativeRider;
        }
    }

    HostWorldImpl(ServerLevel level) {
        this.level = level;
        synchronized (LEVEL_TWINS) {
            java.util.Map<EntityHandle, UmbLegacyEntity> twins = LEVEL_TWINS.get(level);
            if (twins == null) {
                twins = new java.util.IdentityHashMap<>();
                LEVEL_TWINS.put(level, twins);
            }
            this.entityTwins = twins;
        }
        synchronized (LEVEL_BLOCK_STATE_CACHE) {
            Map<Long, BlockState> states = LEVEL_BLOCK_STATE_CACHE.get(level);
            if (states == null) {
                states = new LinkedHashMap<Long, BlockState>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, BlockState> eldest) {
                        return size() > MAX_CACHED_BLOCK_STATES;
                    }
                };
                LEVEL_BLOCK_STATE_CACHE.put(level, states);
            }
            this.loadedBlockStateCache = states;
        }
    }

    void registerRestored(EntityHandle handle, UmbLegacyEntity twin) {
        if (handle != null && twin != null) {
            entityTwins.put(handle, twin);
            twin.bindHostWorld(this);
        }
    }

    ServerLevel level() {
        return level;
    }

    @Override
    public boolean isRemote() {
        return level.isClientSide();
    }

    @Override
    public long getTotalTime() {
        return level.getGameTime();
    }

    /**
     * Live block state for a FULLY loaded chunk, or null. Never loads or generates a chunk:
     * legacy reads arrive from inside chunk post-load (LevelChunk.runPostLoad -> BlockEntity.setLevel
     * -> createTile -> getMeta), and a blocking Level.getChunkAt/getBlockState there waits on the
     * ChunkSource.getChunkNow(int,int) -> LevelChunk (null unless FULL), LevelChunk.getBlockState(BlockPos).
     * A loaded chunk returns its current state, which also covers the pad-fill race.
     */
    private BlockState stateIfLoaded(BlockPos pos) {
        long key = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        net.minecraft.world.level.chunk.LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (c != null) {
            BlockState current = c.getBlockState(pos);
            if (current != null) {
                synchronized (loadedBlockStateCache) {
                    loadedBlockStateCache.put(key, current);
                }
                return current;
            }
        }
        synchronized (loadedBlockStateCache) {
            return loadedBlockStateCache.get(key);
        }
    }

    @Override
    public String getBlockId(int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState st = stateIfLoaded(pos);
            if (st == null) return "minecraft:air";
            // A waterlogged host state holds source water where 1.7.10 would have a water
            // block: legacy getBlock/getBlockMaterial/isAABBInMaterial must see water, or
            // swimming, floating, ray-trace liquid handling and bucket fills all miss it.
            if (isWaterloggedWater(st)) return "minecraft:water";
            Block b = st.getBlock();
            if (b instanceof UmbLegacyBlock ulb && ulb.getLegacyId() != null) return ulb.getLegacyId();
            String[] vanilla = vanillaLegacyKey(b);
            return vanilla != null ? vanilla[0] : "minecraft:air";
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.getBlockId", t, 2);
            return "minecraft:air";
        }
    }

    /**
     * True when the host state carries vanilla water in its fluid slot (a waterlogged
     * waterloggable block). Only vanilla water maps: a modded fluid has no 1.7.10
     * counterpart and keeps the base-block reading. Package-visible so tests can pin it
     * against real block states without a live level.
     */
    static boolean isWaterloggedWater(BlockState st) {
        try {
            // Direct fluid comparison, not the WATER tag: tags resolve through data packs
            // and are unbound in several headless/test contexts, while the fluid slot on
            // the state itself is always readable. Plain fluid blocks are excluded: they
            // map through the normal fluid entries below, this branch is only the
            // waterlogged-solid case, which has no 1.7.10 block of its own.
            return st != null && !(st.getBlock() instanceof LiquidBlock)
                    && st.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER
                    && !(st.getBlock() instanceof UmbLegacyBlock);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A vanilla 26.2 block as the 1.7.10 world would have held it: {legacyId, meta}, or null when it
     * has no 1.7.10 counterpart (reported as air, as before). Goes through the block's item and
     * {@link VanillaItemBridge}'s evidence-only id table, so flattened variants (wool colours,
     * planks, stone kinds) come back as the legacy id + damage. Fluids and fire have no item, so
     * they are named directly. Without this every vanilla block read as air to legacy code: mod
     * raytraces went straight through terrain and placement/adjacency checks all failed.
     */
    static String[] vanillaLegacyKey(Block b) {
        if (b == null || b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) return null;
        if (b == Blocks.WATER) return new String[] {"minecraft:water", "0"};
        if (b == Blocks.LAVA) return new String[] {"minecraft:lava", "0"};
        if (b == Blocks.FIRE) return new String[] {"minecraft:fire", "0"};
        net.minecraft.world.item.Item item = b.asItem();
        if (item == null || item == net.minecraft.world.item.Items.AIR) return null;
        String key = VanillaItemBridge.nativeToLegacyKey(item);
        if (key == null) return null;
        int at = key.lastIndexOf('@');
        return at < 0 ? new String[] {key, "0"} : new String[] {key.substring(0, at), key.substring(at + 1)};
    }

    /** Legacy registry identity for a host neighbor, including vanilla redstone blocks. */
    static String legacyIdForBlock(Block b) {
        if (b instanceof UmbLegacyBlock ulb && ulb.getLegacyId() != null) return ulb.getLegacyId();
        String[] key = vanillaLegacyKey(b);
        return key == null ? null : key[0];
    }

    @Override
    public int getMeta(int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState st = stateIfLoaded(pos);
            // Paired with getBlockId's waterlogged mapping: still-water convention (meta 0,
            // same as a plain water block), never the base block's facing/half meta, whose
            // values mean something else entirely in water-fill-height math.
            if (isWaterloggedWater(st)) return 0;
            Block b = st == null ? null : st.getBlock();
            if (b != null && !(b instanceof UmbLegacyBlock)) {
                String[] vanilla = vanillaLegacyKey(b);
                if (vanilla != null) return Integer.parseInt(vanilla[1]);
            }
            return UmbMetadataSavedData.get(level).getMeta(x, y, z);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.getMeta", t, 2);
            return 0;
        }
    }

    @Override
    public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity previousEntity = level.getBlockEntity(pos);
            Block b = resolveVariantOrBase(legacyId, meta);
            if (b == null) {
                AgentLog.line("HostWorldImpl.setBlock: unknown legacy block " + legacyId + "@" + meta);
                return;
            }
            // Metadata FIRST: Level.setBlock creates the UmbLegacyBlockEntity synchronously and its
            // setLevel -> legacy createTile reads the side-table meta right then. Writing the meta
            // afterwards made every multiblock core (legacy dummy-block convention: TE only for
            // a designated meta range) see meta 0 -> null TE.
            UmbMetadataSavedData.get(level).setMeta(x, y, z, meta);
            // Legacy setBlockMetadataWithNotify/setBlock callers are not required to include the
            // 26.2 client-update bit. Always retain the caller's flags while forcing the vanilla
            // neighbor+client update pair, so off/on furnace twins and their models cannot remain
            // visually stale after a legacy state transition.
            int updateFlags = flags == 0 ? 3 : flags | 3;
            level.setBlock(pos, b.defaultBlockState(), updateFlags);
            // Legacy machines commonly toggle between separate off/on block twins from inside
            // updateEntity.  26.2 may construct a fresh generic BE for that state transition;
            // hand the opaque state back only when the old and new handles report the same legacy
            // TileEntity class, so this is a universal variant handoff rather than a mod guess.
            if (previousEntity instanceof UmbLegacyBlockEntity previous
                    && level.getBlockEntity(pos) instanceof UmbLegacyBlockEntity current
                    && previous != current) {
                current.preserveLegacyStateFrom(previous);
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge != null) {
                // The old block id may differ from the new one; invalidate every cached channel
                // at this position and its callback footprint rather than only the new id.
                bridge.invalidateShape(null, x, y, z);
            }
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.setBlock", t, 2);
        }
    }

    /**
     * The one place "legacy id + meta" resolves to a native Block, shared by {@link #setBlock} and
     * {@link #setMeta} so both pick the same variant twin for the same (id, meta) pair: a
     * registered per-meta visual variant if one exists, else the plain base block for that id
     * (never a DIFFERENT legacy id - identity always comes from the caller, this only resolves the
     * meta-specific instance of it).
     */
    static Block resolveVariantOrBase(String legacyId, int meta) {
        Block b = Registrar.LEGACY_VARIANT_BLOCKS.get(legacyId + "@" + meta);
        if (b == null) b = Registrar.LEGACY_BLOCKS.get(legacyId);
        return b;
    }

    /**
     * BUG-3: func_72921_c (setBlockMetadataWithNotify) is the standard 1.7.10 rotate/toggle/
     * change-active-state call, so it must behave exactly like {@link #setBlock} - resolve the
     * variant twin for the NEW meta (preserving the block's own legacy identity) and let
     * {@link #setBlock} do the real write plus client-update notification - not merely update the
     * side table, which left the state change invisible in the world. If there is no legacy twin
     * at this position at all (e.g. air, or a native 26.2 block never bridged), there is no
     * identity to preserve; fall back to the old side-table-only write so a stray call from legacy
     * code never touches an unrelated 26.2 block.
     */
    @Override
    public void setMeta(int x, int y, int z, int meta, int flags) {
        try {
            Block current = level.getBlockState(new BlockPos(x, y, z)).getBlock();
            if (current instanceof UmbLegacyBlock ulb && ulb.getLegacyId() != null) {
                setBlock(x, y, z, ulb.getLegacyId(), meta, flags);
                return;
            }
            UmbMetadataSavedData.get(level).setMeta(x, y, z, meta);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.setMeta", t, 2);
        }
    }

    @Override
    public void removeBlock(int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            level.removeBlock(pos, false);
            UmbMetadataSavedData.get(level).clearMeta(x, y, z);
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge != null) bridge.invalidateShape(null, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.removeBlock", t, 2);
        }
    }

    /**
     * One legacy block-drop stack as a native item entity. The host owns drop rules:
     * BLOCK_DROPS off (1.7.10's doTileDrops) or an unresolvable/empty stack drops
     * nothing, never an erasing placeholder.
     */
    @Override
    public void dropItem(double x, double y, double z, dev.umb.bridge.api.StackData stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (!Boolean.TRUE.equals(level.getGameRules().get(GameRules.BLOCK_DROPS))) {
                return;
            }
            net.minecraft.world.item.ItemStack nativeStack = LegacyStackConv.toNative(stack);
            if (nativeStack.isEmpty()) {
                return;
            }
            net.minecraft.world.entity.item.ItemEntity entity =
                    new net.minecraft.world.entity.item.ItemEntity(level, x, y, z, nativeStack);
            level.addFreshEntity(entity);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.dropItem", t, 2);
        }
    }

    @Override
    public void markBlockDirty(int x, int y, int z) {        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState st = level.getBlockState(pos);
            level.sendBlockUpdated(pos, st, st, 3);
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge != null) bridge.invalidateShape(null, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.markBlockDirty", t, 2);
        }
    }

    @Override
    public void notifyNeighbors(int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge != null) bridge.invalidateShape(null, x, y, z);
            level.updateNeighborsAt(pos, block);
            level.updateNeighbourForOutputSignal(pos, block);
            if (bridge != null) bridge.invalidateShape(null, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.notifyNeighbors", t, 2);
        }
    }

    @Override
    public void scheduleTick(int x, int y, int z, int delay) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            Block b = level.getBlockState(pos).getBlock();
            if (b == Blocks.AIR) return;
            level.scheduleTick(pos, b, Math.max(1, delay));
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.scheduleTick", t, 2);
        }
    }

    @Override
    public long randomSeed() {
        try {
            return level.getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public void log(String msg) {
        AgentLog.line("[legacy] " + msg);
    }

    /**
     * ENTITY-BRIDGE: expose native entities to legacy AABB queries.  The legacy side already owns
     * facades for players and UMB's generic legacy twins, so those three native categories are
     * filtered here to prevent duplicate hits.  The returned objects are capability wrappers;
     * no native Entity crosses the bridge.
     */
    @Override
    public java.util.List<HostEntity> getEntities(double minX, double minY, double minZ,
                                                   double maxX, double maxY, double maxZ,
                                                   String excludedIdentity) {
        if (level == null) return java.util.Collections.emptyList();
        try {
            AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            java.util.List<Entity> nativeEntities = level.getEntities((Entity) null, box,
                    e -> isQueryableNativeEntity(e, excludedIdentity));
            java.util.List<HostEntity> result = new java.util.ArrayList<>(nativeEntities.size());
            for (Entity nativeEntity : nativeEntities) {
                result.add(new HostNativeEntity(nativeEntity, level));
            }
            return result;
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.getEntities", t, 2);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * per-collision-box native collider that mirrors ONE extra hitbox of a legacy entity (see its
     * own javadoc) - it carries no legacy class identity, never ticks legacy state, and every
     * interaction/damage decision delegates back to its parent {@code UmbLegacyEntity}. It was
     * missing from this filter (only {@code Player}/{@code UmbLegacyEntity} were excluded), so a
     * seat's own part-twin collider leaked into legacy AABB scans as a generic "wild" HostEntity.
     * MCHeli's real {@code MCH_EntityAircraft.mountMobToSeats} (called once, server-side, right
     * after a player boards as pilot) scans exactly such a box for stray {@code EntityLivingBase}
     * mobs to auto-crew into empty seats; it excludes real {@code EntityPlayer}s by
     * {@code instanceof} but had no way to know a returned facade was actually a collision-box
     * shadow of the SAME aircraft/seat it was scanning around, so it auto-mounted the leaked
     * facade into the empty gunner seat - the confirmed phantom
     * A part-twin has no independent identity or riding semantics at all (its own javadoc: "never
     * carry riders"), so it must never be offered to legacy code as a queryable entity, exactly
     * like the two categories already excluded above.
     */
    static boolean isQueryableNativeEntity(Entity e, String excludedIdentity) {
        return e != null
                && !e.isRemoved()
                && !(e instanceof Player)
                && !(e instanceof UmbLegacyEntity)
                && !(e instanceof UmbLegacyPartTwin)
                && (excludedIdentity == null || !excludedIdentity.equals(e.getStringUUID()));
    }


    @Override
    public void enqueueClientEffect(EffectData effect) {
        if (effect == null || clientEffects.size() >= MAX_CLIENT_EFFECT_QUEUE) {
            AgentLog.line("UMB-FX client effect dropped reason=queue-capacity");
            return;
        }
        clientEffects.offer(effect);
    }

    @Override
    public java.util.List<EffectData> drainClientEffects() {
        java.util.ArrayList<EffectData> out = new java.util.ArrayList<>();
        EffectData effect;
        while ((effect = clientEffects.poll()) != null) out.add(effect);
        return out;
    }

    @Override
    public void playSound(double x, double y, double z, String name, float volume, float pitch) {
        try {
            UmbThread.assertServer();
            Identifier id = LegacyFx.soundId(name);
            if (id == null) {
                LegacyFx.hostDropped("playSound", "missing-mapping");
                LegacyFx.countSkip("sound", name);
                return;
            }
            // A plain (unregistered) SoundEvent by id: the client resolves it against every loaded
            // resource pack's sounds.json at play time (same mechanism /playsound uses), so mod
            // sounds need no registry entry - only the sound pack. A missing resource is a
            // client-side log line, not a crash.
            level.playSound(null, x, y, z, SoundEvent.createVariableRangeEvent(id),
                    SoundSource.BLOCKS, volume, pitch);
            LegacyFx.hostDelivered("playSound", "mapped");
            long count = NATIVE_SOUND_COUNT.incrementAndGet();
            if (LOGGED_NATIVE_SOUNDS.add(id.toString())) {
                AgentLog.line("PRESENTATION sound native name=" + id + " count=" + count);
            }
        } catch (Throwable t) {
            LegacyFx.hostDropped("playSound", "host-exception");
            AgentLog.error("HostWorldImpl.playSound", t, 2);
        }
    }

    @Override
    public void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) {
        try {
            UmbThread.assertServer();
            ParticleOptions p = LegacyFx.particle(name);
            if (p == null) {
                LegacyFx.hostDropped("spawnParticle", "missing-mapping");
                return;
            }
            // count=0 is sendParticles' velocity mode: dx/dy/dz become the single particle's
            // motion - the exact 1.7.10 World.spawnParticle contract.
            level.sendParticles(p, x, y, z, 0, vx, vy, vz, 1.0);
            LegacyFx.hostDelivered("spawnParticle", "native");
            long count = NATIVE_PARTICLE_COUNT.incrementAndGet();
            if (LOGGED_NATIVE_PARTICLES.add(String.valueOf(name))) {
                AgentLog.line("PRESENTATION particle native name=" + name + " count=" + count);
            }
        } catch (Throwable t) {
            LegacyFx.hostDropped("spawnParticle", "host-exception");
            AgentLog.error("HostWorldImpl.spawnParticle", t, 2);
        }
    }

    @Override
    public void explode(double x, double y, double z, float strength, boolean flaming, boolean breakBlocks) {
        try {
            UmbThread.assertServer();
            float maxRadius = configuredExplosionMaxRadius();
            float safeStrength = clampExplosionStrength(strength, maxRadius);
            boolean mobGriefing = Boolean.TRUE.equals(level.getGameRules().get(GameRules.MOB_GRIEFING));
            boolean allowBlockDamage = allowsExplosionBlockDamage(breakBlocks, mobGriefing);
            AgentLog.line("PRESENTATION explosion native requested=" + strength
                    + " applied=" + safeStrength + " maxRadius=" + maxRadius
                    + " breakBlocks=" + breakBlocks + " mobGriefing=" + mobGriefing
                    + " nativeBlockDamage=" + allowBlockDamage);
            level.explode(null, x, y, z, safeStrength, flaming && allowBlockDamage,
                    allowBlockDamage ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.explode", t, 2);
        }
    }

    /** Configured through -Dumb.legacy.explosion.maxRadius; the hard ceiling is never bypassed. */
    static float configuredExplosionMaxRadius() {
        String raw = System.getProperty(EXPLOSION_MAX_RADIUS_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_LEGACY_EXPLOSION_MAX_RADIUS;
        try {
            float parsed = Float.parseFloat(raw.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0.0F) {
                return DEFAULT_LEGACY_EXPLOSION_MAX_RADIUS;
            }
            return Math.min(HARD_LEGACY_EXPLOSION_MAX_RADIUS, parsed);
        } catch (NumberFormatException ignored) {
            return DEFAULT_LEGACY_EXPLOSION_MAX_RADIUS;
        }
    }

    static float clampExplosionStrength(float requested, float maxRadius) {
        if (Float.isNaN(requested) || requested <= 0.0F) return 0.0F;
        if (Float.isInfinite(requested)) return Math.max(0.0F, maxRadius);
        return Math.min(requested, Math.max(0.0F, maxRadius));
    }

    static boolean allowsExplosionBlockDamage(boolean requested, boolean mobGriefing) {
        return requested && mobGriefing;
    }

    @Override
    public int getRedstonePower(int x, int y, int z) {
        try {
            return level.getBestNeighborSignal(new BlockPos(x, y, z));
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.getRedstonePower", t, 2);
            return 0;
        }
    }

    // ---- ENTITY-BRIDGE: HostLevel ----

    /**
     * Called by legacy code (through {@code UmbWorld.func_72838_d}) once, the first time a legacy
     * Entity reaches {@code World.spawnEntityInWorld} for real. Constructs the one generic native
     * twin, seeds its initial transform from the handle (single source of truth - no duplicate
     * position parameters to keep in sync), and adds it to the level exactly the way vanilla's own
     */
    @Override
    public boolean spawnEntity(EntityHandle handle) {
        try {
            UmbThread.assertServer();
            if (handle == null) {
                return false;
            }
            if (entityTwins.containsKey(handle)) {
                return true;
            }
            String ownerNamespace = handle.ownerNamespace();
            EntityType<UmbLegacyEntity> type = Registrar.entityTypeFor(ownerNamespace);
            if (type == null) {
                AgentLog.line("ENTITY-DIAG HostWorldImpl.spawnEntity type-missing class="
                        + handle.legacyEntityClassName() + " id=" + handle.legacyEntityId()
                        + " owner=" + ownerNamespace);
                return false;
            }
            AgentLog.line("ENTITY-DIAG HostWorldImpl.spawnEntity enter class="
                    + handle.legacyEntityClassName() + " id=" + handle.legacyEntityId()
                    + " owner=" + ownerNamespace
                    + " pos=" + handle.getX() + "," + handle.getY() + "," + handle.getZ());
            UmbLegacyEntity e = new UmbLegacyEntity(type, level);
            e.bindHandle(handle);
            e.bindHostWorld(this);
            e.setPos(handle.getX(), handle.getY(), handle.getZ());
            e.setYRot(handle.getYaw());
            e.setXRot(handle.getPitch());
            e.setDeltaMovement(handle.getMotionX(), handle.getMotionY(), handle.getMotionZ());
            e.refreshDimensions();
            e.applyLegacyBounds(handle);
            boolean added = level.addFreshEntity(e);
            if (added) entityTwins.put(handle, e);
            if (added) e.syncPartTwins(handle);
            AgentLog.line("ENTITY-DIAG HostWorldImpl.spawnEntity result added=" + added
                    + " twin=" + e.getUUID());
            return added;
        } catch (Throwable t) {
            AgentLog.error("HostWorldImpl.spawnEntity", t, 3);
            return false;
        }
    }

    @Override
    public void prepareEntity(EntityHandle handle) {
        UmbLegacyEntity twin = entityTwins.get(handle);
        if (twin == null || twin.isRemoved()) return;
        syncLegacyVehicleLink(handle, twin);
        // A legacy parent with a seat/hitbox passenger must not also receive the player directly;
        // the player belongs on the child twin, preserving the same passenger graph as legacy.
        if (!shouldMirrorDirectRider(handle)) {
            return;
        }
        RiderSyncState riderState = riderSyncStates.computeIfAbsent(handle, ignored -> new RiderSyncState());
        net.minecraft.server.level.ServerPlayer nativeRider = findServerPassenger(twin);
        if (nativeRider == null) {
            // ServerLevel's passenger list can be between remove/add notifications while the
            // player still has this vehicle (or a legacy seat below it) as its vehicle.  The
            // player-side chain is the stable source during that bridge ordering window.
            net.minecraft.server.level.ServerPlayer rememberedNative = riderState.lastNativeRider();
            if (rememberedNative != null
                    && vehicleChainContains(rememberedNative.getVehicle(), twin)) {
                nativeRider = rememberedNative;
            }
        }
        HostPlayer rider = nativeRider == null ? null : new HostPlayerImpl(nativeRider);
        if (rider != null) {
            riderState.observeHostRider(nativeRider);
            handle.setHostRider(rider);
            return;
        }

        // A nested legacy seat can leave the native twin with a non-empty passenger graph while
        // the recursive player lookup is between its two mirror updates.  Clearing the legacy
        // link in that window makes option-(b) client handlers miss their vehicle gate.  Retain
        // the last concrete host rider until the native graph is actually empty; this is generic
        // for every vehicle/seat implementation and still lets a real host dismount propagate.
        HostPlayer rememberedRider = riderState.lastHostRider();
        net.minecraft.server.level.ServerPlayer rememberedNative = riderState.lastNativeRider();
        boolean rememberedNativeStillMounted = rememberedNative != null
                && vehicleChainContains(rememberedNative.getVehicle(), twin);
        boolean legacyRiderPresent = handle.riderName() != null;
        boolean hostDismountConfirmed = rememberedNative != null && !rememberedNativeStillMounted;
        if (rememberedRider != null && !riderState.explicitDismount()) {
            if (rememberedNativeStillMounted || !twin.getPassengers().isEmpty()) {
                riderState.observeHostRider(rememberedRider);
                handle.setHostRider(rememberedRider);
                if (riderRepairLogged.add(handle)) {
                    AgentLog.line("ENTITY-DIAG rider-sync retain-last-host-rider handle="
                            + Integer.toHexString(System.identityHashCode(handle))
                            + " rider=" + rememberedRider.getName()
                            + " passengers=" + twin.getPassengers().size());
                }
                return;
            }
            // Both native views can be empty for a few bridge ordering ticks.  Keep the legacy
            // facade mounted during that grace so client handlers never lose ridingEntity.
            if (!riderState.observeMissingHostRider(legacyRiderPresent, hostDismountConfirmed)) {
                handle.setHostRider(rememberedRider);
                return;
            }
        }

        // The native passenger list is only a host-side mirror.  If the legacy entity still has
        // riddenByEntity, preserve that source of truth and let syncEntity repair the host
        // passenger list.  Clearing here used to call setHostRider(null), which cleared the
        // legacy link on the next syncEntity and made every mount disappear after a few seconds.
        if (riderState.observeMissingHostRider(legacyRiderPresent, hostDismountConfirmed)) {
            traceRiderNullDecision(handle, twin, riderState, legacyRiderPresent,
                    hostDismountConfirmed);
            handle.setHostRider(null);
        } else if (riderState.missingTicks() == 1
                || riderState.missingTicks() % 40 == 0) {
            AgentLog.line("ENTITY-DIAG rider-sync preserve-legacy-rider handle="
                    + Integer.toHexString(System.identityHashCode(handle))
                    + " desired=" + handle.riderName()
                    + " hostPassengers=" + twin.getPassengers().size());
        }
    }

    /** Called by UmbLegacyEntity before mirroring legacy rider state after a host read. */
    boolean allowLegacyRiderMirror(EntityHandle handle, String desiredName) {
        RiderSyncState state = riderSyncStates.get(handle);
        return state == null || state.allowLegacyMirror(desiredName);
    }

    void noteLegacyRiderInteraction(EntityHandle handle) {
        noteLegacyRiderInteraction(handle, null);
    }

    void noteLegacyRiderInteraction(EntityHandle handle, String desiredName) {
        noteLegacyRiderInteraction(handle, desiredName, desiredName == null);
    }

    void noteLegacyRiderInteraction(EntityHandle handle, String desiredName,
            boolean explicitDismount) {
        if (handle == null) {
            return;
        }
        riderSyncStates.computeIfAbsent(handle, ignored -> new RiderSyncState())
                .observeLegacyInteraction(desiredName, explicitDismount);
    }

    /**
     * Called by {@link UmbLegacyEntity} when the native passenger graph drops a host player
     * (host rideTick sneak-dismount or any host-side stopRiding). Marks the dismount explicit
     * so {@code prepareEntity} propagates null to the legacy entity - whose own
     * {@code mountEntity(null)} logic then runs via {@code setHostRider(null)} - instead of
     * retaining the stale rider for the legacy-to-host sync to re-mount on the next tick.
     */
    void noteHostDismount(EntityHandle handle) {
        if (handle == null) {
            return;
        }
        riderSyncStates.computeIfAbsent(handle, ignored -> new RiderSyncState())
                .observeHostDismount();
    }

    private void traceRiderNullDecision(EntityHandle handle, UmbLegacyEntity twin,
            RiderSyncState state, boolean legacyRiderPresent, boolean hostDismountConfirmed) {
        if (!riderNullDecisionLogged.add(handle)) {
            return;
        }
        StringBuilder line = new StringBuilder("ENTITY-DIAG rider-null decision")
                .append(" handle=").append(Integer.toHexString(System.identityHashCode(handle)))
                .append(" twin=").append(twin.getUUID())
                .append(" legacyRider=").append(handle.riderName())
                .append(" hostPassengers=").append(twin.getPassengers().size())
                .append(" explicit=").append(state.explicitDismount())
                .append(" hostDismountConfirmed=").append(hostDismountConfirmed)
                .append(" missingTicks=").append(state.missingTicks())
                .append(" stack=");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int frames = 0;
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(Throwable.class.getName())
                    || frame.getClassName().equals(HostWorldImpl.class.getName())) {
                continue;
            }
            if (frames++ > 0) {
                line.append(" <- ");
            }
            line.append(frame.getClassName()).append('#').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (frames >= 10) {
                break;
            }
        }
        AgentLog.line(line.toString());
    }

    @Override
    public void syncEntity(EntityHandle handle) {
        UmbLegacyEntity twin = entityTwins.get(handle);
        if (twin == null || twin.isRemoved()) return;
        twin.applyLegacyState(handle);
        syncLegacyVehicleLink(handle, twin);
        // Client handlers run after this legacy tick.  If a legacy callback cleared its rider
        // link while the native graph still has the player, restore the bridge-side pilot now;
        // otherwise the client facade sees no ridingEntity and vehicle controls are gated off.
        if (shouldMirrorDirectRider(handle) && handle.riderName() == null) {
            net.minecraft.server.level.ServerPlayer nativeRider = findServerPassenger(twin);
            if (nativeRider != null) {
                HostPlayer repairedRider = new HostPlayerImpl(nativeRider);
                riderSyncStates.computeIfAbsent(handle, ignored -> new RiderSyncState())
                        .observeHostRider(nativeRider);
                handle.setHostRider(repairedRider);
                if (riderRepairLogged.add(handle)) {
                    AgentLog.line("ENTITY-DIAG rider-sync repair-legacy handle="
                            + Integer.toHexString(System.identityHashCode(handle))
                            + " player=" + nativeRider.getScoreboardName()
                            + " passengers=" + twin.getPassengers().size());
                }
            }
        }
    }

    /** Mirrors legacy child-seat/part ownership into the native passenger graph. */
    private void syncLegacyVehicleLink(EntityHandle handle, UmbLegacyEntity twin) {
        String vehicleIdentity = handle.legacyVehicleIdentity();
        UmbLegacyEntity desiredVehicle = findLegacyTwin(vehicleIdentity, handle);
        Entity currentVehicle = twin.getVehicle();
        if (desiredVehicle != null && desiredVehicle != currentVehicle) {
            boolean started = twin.startRiding(desiredVehicle, true, true);
            AgentLog.line("ENTITY-DIAG legacy-part-link child=" + twin.getUUID()
                    + " parent=" + desiredVehicle.getUUID() + " started=" + started);
        } else if (vehicleIdentity == null && currentVehicle instanceof UmbLegacyEntity) {
            twin.stopRiding();
            AgentLog.line("ENTITY-DIAG legacy-part-unlink child=" + twin.getUUID());
        }
    }

    static boolean shouldMirrorDirectRider(EntityHandle handle) {
        return handle == null || handle.legacyPassengerIdentity() == null;
    }

    private UmbLegacyEntity findLegacyTwin(String identity, EntityHandle exclude) {
        if (identity == null) {
            return null;
        }
        for (Map.Entry<EntityHandle, UmbLegacyEntity> entry : entityTwins.entrySet()) {
            EntityHandle candidate = entry.getKey();
            UmbLegacyEntity twin = entry.getValue();
            if (candidate != exclude && twin != null && !twin.isRemoved()
                    && identity.equals(candidate.legacyEntityIdentity())) {
                return twin;
            }
        }
        return null;
    }

    /**
     * Returns a real host player anywhere below the generic native passenger graph.  Legacy
     * vehicle mods may insert a seat entity between the vehicle twin and the player; the bridge
     * must not encode that implementation detail into per-mod adapters.
     */
    private net.minecraft.server.level.ServerPlayer findServerPassenger(Entity root) {
        net.minecraft.server.level.ServerPlayer nested = findServerPassenger(root,
                java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()), 0);
        if (nested != null) {
            return nested;
        }
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (vehicleChainContains(player.getVehicle(), root)) {
                return player;
            }
        }
        return null;
    }

    /**
     * evidence): recursing PAST a nested {@code UmbLegacyEntity} here let an ANCESTOR twin (e.g.
     * an MCHeli aircraft) steal a DESCENDANT twin's (a gunner seat's) own native rider as if it
     * were the ancestor's own direct passenger. MCHeli's aircraft.field_70153_n (its own direct
     * riddenByEntity) is null whenever only a seat is occupied - the seat has its OWN, completely
     * separate field_70153_n - so {@code legacyPassengerIdentity()}'s "do I already have a
     * non-player direct passenger" gate saw nothing and let this recursive walk find the real
     * gunner PLAYER three hops down (gunner -> seat twin -> aircraft twin) and hand it to
     * {@code prepareEntity}, which then called {@code setHostRider(gunner)} on the AIRCRAFT's own
     * handle. That corrupted the aircraft's legacy {@code field_70153_n} with a fake rider it was
     * never meant to have, which made {@code EntityHandleImpl.riderOffset()} run the AIRCRAFT's
     * own {@code func_70043_V()}/{@code updateRiderPosition()} (hardcoded to seatsInfo[0], the
     * PILOT's offset - real MCHeli code, faithful, just invoked on data that was never real) and
     * {@code UmbLegacyEntity.syncLegacyRider} then re-mounted the real native gunner PLAYER
     * directly onto the aircraft twin - exactly the live symptom (the gunner ends up at the
     * pilot's spot, torn off the seat).
     *
     * <p>A nested legacy twin owns its own independent rider identity and runs its own
     * prepareEntity/syncEntity cycle every tick; walking past it here to reach ITS passenger
     * double-attributes that passenger to an ancestor that was never actually riding it. Every
     * non-legacy-twin passenger (the "pure passthrough helper" case this recursion exists for)
     * is still walked exactly as before - only the legacy-twin boundary is new.</p>
     */
    static net.minecraft.server.level.ServerPlayer findServerPassenger(
            Entity current, java.util.Set<Entity> seen, int depth) {
        if (current == null || depth >= 8 || !seen.add(current)) {
            return null;
        }
        for (Entity passenger : current.getPassengers()) {
            if (passenger instanceof net.minecraft.server.level.ServerPlayer player) {
                return player;
            }
            if (passenger instanceof UmbLegacyEntity) {
                continue;
            }
            net.minecraft.server.level.ServerPlayer nested = findServerPassenger(passenger, seen,
                    depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * True for direct and nested seat/helper vehicle chains, with identity-cycle protection - but
     * (see {@link #findServerPassenger(Entity, java.util.Set, int)}'s javadoc for the live bug
     * this closes) stops at the first {@code UmbLegacyEntity} boundary that is not {@code root}
     * itself: a vehicle chain that passes THROUGH a different legacy twin (e.g. a player riding a
     * gunner seat, checked against the aircraft's own root) belongs to that OTHER twin, not to
     * {@code root}, even though {@code root} is a real ancestor in the native passenger graph.
     */
    static boolean vehicleChainContains(Entity vehicle, Entity root) {
        Set<Entity> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Entity, Boolean>());
        int depth = 0;
        while (vehicle != null && depth++ < 8 && seen.add(vehicle)) {
            if (vehicle == root) {
                return true;
            }
            if (vehicle instanceof UmbLegacyEntity) {
                return false;
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    @Override
    public void removeEntity(EntityHandle handle) {
        UmbLegacyEntity twin = entityTwins.remove(handle);
        riderSyncStates.remove(handle);
        riderRepairLogged.remove(handle);
        riderNullDecisionLogged.remove(handle);
        if (twin != null) twin.removeFromLegacy();
    }

    void twinRemoved(UmbLegacyEntity twin, EntityHandle handle) {
        if (entityTwins.get(handle) == twin) {
            entityTwins.remove(handle);
            riderSyncStates.remove(handle);
            riderRepairLogged.remove(handle);
            riderNullDecisionLogged.remove(handle);
        }
    }
}
