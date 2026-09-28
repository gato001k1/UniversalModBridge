package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A real, native 26.2 Block that carries its 1.7.10 provenance.
 * Lives in dev.umb.* on purpose: defining classes inside net.minecraft.* would break the
 * signed client.jar's package sealing ("signer information does not match").
 *
 * Implements {@link EntityBlock} unconditionally (Java has no per-instance interfaces), gated
 * internally by the live bridge: snapshot records with {@code hasTileEntity} still take the
 * normal fast path, while 1.16.5-only blocks are allowed to create a generic bridge entity and
 * let {@code LegacyBridge.createTile} make the authoritative yes/no decision at placement time.
 *
 * <p>Interaction (GAP 3 / INTERACTION-BRIDGE.md) is bridged for EVERY twin, not only ones with a
 * tile entity (BUG-1): 1.7.10's {@code func_149727_a} (onBlockActivated) is called regardless of
 * whether the block keeps its state in a tile entity or in metadata (levers, buttons, doors,
 * valves, ...). {@link #useItemOn} and {@link #useWithoutItem} both reach the SAME
 * {@link #doActivate} helper because PART E1 (26.2's {@code ServerPlayerGameMode.useItemOn}
 * bytecode) calls {@code useItemOn} first and only falls through to {@code useWithoutItem} when
 * the first call did not consume the action - a {@link #RECENTLY_DECLINED} thread-local records
 * "useItemOn already ran onBlockActivated for this exact pos, on this thread, this instant" so the
 * fallthrough does not run the SAME legacy call a second time for one right click (a legacy method
 * with any side effect on its decline path, e.g. a chat message, would otherwise fire twice).</p>
 */
public class UmbLegacyBlock extends Block implements EntityBlock {

    private final BlockRec record;
    private final String legacyId;
    private final VoxelShape shape;
    private final VoxelShape collisionShape;

    /**
     * Marks "useItemOn already called onBlockActivated for this BlockPos, on this thread, and it
     * declined (or could not run)" so {@link #useWithoutItem} - always invoked next in the same
     * synchronous dispatch, PART E1 - does not re-run the identical legacy call. Read-and-cleared
     * on every useWithoutItem entry so it can never leak into an unrelated later click.
     */
    private static final ThreadLocal<BlockPos> RECENTLY_DECLINED = new ThreadLocal<>();
    /** Logs each live-cell shape signature at most once per process, so a door cannot flood the server log. */
    private static final java.util.Map<String, String> COLLISION_DIAG =
            new java.util.LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
                    return size() > 512;
                }
            };

    /**
     * CRITICAL RISK (PART 1 build brief): {@link #setPlacedBy}, {@link #onPlace},
     * {@link #playerWillDestroy} and {@link #neighborChanged} can each re-enter the SAME set of
     * callbacks when the legacy mod's own handler calls {@code world.setBlock}/{@code
     * setBlockToAir} again - e.g. a multiblock controller's onBlockPlacedBy writing its own filler
     * blocks, each filler's own onBlockAdded potentially reacting further. Legacy code is
     * single-threaded on the server tick thread ({@link UmbThread}'s own contract), so a plain
     * per-thread depth counter is sufficient; it does not need to be scoped to one callback kind
     * because the four callbacks can call into each other in any order, not just into themselves.
     *
     * <p><b>Proof of termination:</b> every entry into any of the four guarded methods increments
     * this counter by exactly one and every exit (success OR exception, via try/finally) decrements
     * it by exactly one; {@link #enterReentrancyGuard} refuses to enter (and therefore never calls
     * ANY more legacy code, so no further increment can happen on this call stack) once the counter
     * has already reached {@link #MAX_REENTRANCY_DEPTH}. The recursion depth is therefore bounded by
     * a fixed integer regardless of what the mod's own code does - there is no path through this
     * guard that can recurse an unbounded number of times.</p>
     *
     * <p>64 is comfortably above any real multiblock's filler-block count while still guaranteeing
     * termination for a mod bug that
     * recurses without making progress.</p>
     */
    private static final int MAX_REENTRANCY_DEPTH = 64;
    private static final ThreadLocal<Integer> REENTRANCY_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static boolean enterReentrancyGuard(String what) {
        int d = REENTRANCY_DEPTH.get();
        if (d >= MAX_REENTRANCY_DEPTH) {
            AgentLog.errorOnce("UmbLegacyBlock.reentrancyGuard." + what,
                    new IllegalStateException("PART 1 reentrancy depth limit " + MAX_REENTRANCY_DEPTH
                            + " exceeded - a legacy callback is recursing without making progress"), 2);
            return false;
        }
        REENTRANCY_DEPTH.set(d + 1);
        return true;
    }

    private static void exitReentrancyGuard() {
        int d = REENTRANCY_DEPTH.get();
        REENTRANCY_DEPTH.set(d > 0 ? d - 1 : 0);
    }

    /** Test-only: forget any depth left over from a previous (e.g. failed) test on this thread. */
    static void resetReentrancyGuardForTests() {
        REENTRANCY_DEPTH.remove();
    }

    /** Pre-Task-A callers (and every existing test) keep getting the plain default full cube. */
    public UmbLegacyBlock(BlockBehaviour.Properties properties, BlockRec record) {
        this(properties, record, Shapes.block());
    }

    /**
     * Task A (laneConsume-progress.md): {@code shape} is the real 1.7.10 collision/outline shape,
     * pre-built by {@link BlockShapes#build} from block-shapes.json at {@link Registrar} time -
     * ONE {@link VoxelShape} per registered twin (each 1.7.10 metadata variant is already its own
     * Block instance, so no per-BlockState keying is needed here).
     */
    public UmbLegacyBlock(BlockBehaviour.Properties properties, BlockRec record, VoxelShape shape) {
        this(properties, record, shape, shape);
    }

    /**
     * {@link BlockShapes#buildCollision}'s result - identical to {@code shape} for every group
     * that declares collision, but {@code Shapes.empty()} for the 276/1900 meta-groups whose
     * 1.7.10 {@code func_149668_a} returned null (fire, gases, spikes, charges, light beams).
     * caller (and the 2/3-arg constructors) byte-for-byte at the old one-shape behaviour.
     */
    public UmbLegacyBlock(BlockBehaviour.Properties properties, BlockRec record, VoxelShape shape,
                          VoxelShape collisionShape) {
        super(properties);
        this.record = record;
        this.legacyId = record == null ? null : record.id;
        this.shape = shape != null ? shape : Shapes.block();
        this.collisionShape = collisionShape != null ? collisionShape : this.shape;
    }

    public BlockRec getLegacyRecord() {
        return record;
    }

    public String getLegacyId() {
        return legacyId;
    }

    /** 1.16.5 snapshots predate the host-side tile flag; query manifest ownership only. */
    private boolean dynamicEraTileCandidate() {
        LegacyBridge bridge = UmbBridgeHost.get();
        return bridge instanceof BridgeRouter router && router.isEraOwned(legacyId);
    }

    /**
     * 1.7.10 func_149660_a (methods.csv: onBlockPlaced). The returned metadata selects the
     * already-registered native variant, preserving machine-facing/orientation metadata instead
     * of always placing this block's default state.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (record == null || !(context.getLevel() instanceof ServerLevel level)) {
            return defaultBlockState();
        }
        try {
            UmbThread.assertServer();
            HostWorldImpl world = new HostWorldImpl(level);
            if (!UmbBridgeHost.ensureBooted(world)) return null;
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge instanceof BridgeRouter router && !router.readyForPlacement(legacyId)) return null;
            String key = Registrar.legacyVariantKeyForItem(context.getItemInHand().getItem());
            int initialMeta = 0;
            if (key != null) {
                int at = key.lastIndexOf('@');
                if (at >= 0 && legacyId.equals(key.substring(0, at))) {
                    try {
                        initialMeta = Integer.parseInt(key.substring(at + 1));
                    } catch (NumberFormatException ignored) {
                        // A malformed registry key is an honest meta-0 fallback.
                    }
                }
            }
            int side = context.getClickedFace() == null ? 0 : context.getClickedFace().get3DDataValue();
            BlockPos pos = context.getClickedPos();
            float hitX = HitCoordsUtil.relative(context.getClickLocation().x, pos.getX());
            float hitY = HitCoordsUtil.relative(context.getClickLocation().y, pos.getY());
            float hitZ = HitCoordsUtil.relative(context.getClickLocation().z, pos.getZ());
            int meta = bridge.placementMetadata(legacyId, pos.getX(), pos.getY(), pos.getZ(),
                    side, hitX, hitY, hitZ, initialMeta);
            Block variant = Registrar.LEGACY_VARIANT_BLOCKS.get(legacyId + "@" + meta);
            if (variant == null) variant = Registrar.LEGACY_BLOCKS.get(legacyId);
            return variant == null ? defaultBlockState() : variant.defaultBlockState();
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.getStateForPlacement", t, 4);
            // A placement exception must not turn into a successful vanilla BlockItem placement;
            // null is the 26.2 BlockItem contract for "do not place/do not shrink".
            return null;
        }
    }

    /** Forge Block.getDrops is authoritative for survival drops; vanilla loot is only fallback. */
    @Override
    protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (record == null) return super.getDrops(state, params);
        try {
            ServerLevel level = params.getLevel();
            net.minecraft.world.phys.Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN);
            if (origin == null) return super.getDrops(state, params);
            BlockPos pos = BlockPos.containing(origin);
            if (!UmbBridgeHost.ensureBooted(new HostWorldImpl(level))) return super.getDrops(state, params);
            int meta = UmbMetadataSavedData.get(level).getMeta(pos.getX(), pos.getY(), pos.getZ());
            Integer fortuneValue = params.getOptionalParameter(LootContextParams.ENCHANTMENT_LEVEL);
            int fortune = fortuneValue == null ? 0 : Math.max(0, fortuneValue);
            java.util.List<StackData> legacyDrops = UmbBridgeHost.get().blockDrops(
                    legacyId, pos.getX(), pos.getY(), pos.getZ(), meta, fortune);
            if (legacyDrops == null) return super.getDrops(state, params);
            java.util.List<ItemStack> out = new java.util.ArrayList<>();
            for (StackData drop : legacyDrops) {
                ItemStack nativeDrop = LegacyStackConv.toNative(drop);
                if (!nativeDrop.isEmpty()) out.add(nativeDrop);
            }
            return out;
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.getDrops", t, 4);
            return super.getDrops(state, params);
        }
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        super.stepOn(level, pos, state, entity);
        if (record == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)
                || !(entity instanceof ServerPlayer serverPlayer)) return;
        try {
            if (!UmbBridgeHost.ensureBooted(new HostWorldImpl(serverLevel))) return;
            UmbBridgeHost.get().stepOn(legacyId, pos.getX(), pos.getY(), pos.getZ(),
                    new HostPlayerImpl(serverPlayer));
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.stepOn", t, 4);
        }
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, double distance) {
        super.fallOn(level, state, pos, entity, distance);
        if (record == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)
                || !(entity instanceof ServerPlayer serverPlayer)) return;
        try {
            if (!UmbBridgeHost.ensureBooted(new HostWorldImpl(serverLevel))) return;
            UmbBridgeHost.get().fallOn(legacyId, pos.getX(), pos.getY(), pos.getZ(),
                    new HostPlayerImpl(serverPlayer), (float) distance);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.fallOn", t, 4);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        if (record == null) return false;
        try {
            LegacyBridge bridge = UmbBridgeHost.get();
            return bridge != null && bridge.isBooted() && bridge.hasComparatorInputOverride(legacyId);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.hasAnalogOutputSignal", t, 4);
            return false;
        }
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos,
                                        net.minecraft.core.Direction direction) {
        if (record == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return 0;
        try {
            if (!UmbBridgeHost.ensureBooted(new HostWorldImpl(serverLevel))) return 0;
            return UmbBridgeHost.get().comparatorInputOverride(legacyId, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.getAnalogOutputSignal", t, 4);
            return 0;
        }
    }

    /** 1.7.10 randomDisplayTick (func_149734_b) has no safe client HostWorld yet. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random);
        // The current boundary HostWorldImpl is server-backed; do not invoke legacy code from a
        // client thread against that server object. This remains an explicit counted gap until a
        // client HostWorld adapter exists, while server-safe particle spawning stays bridged.
    }

    /**
     * The outline/selection shape comes from the legacy block's own
     * {@code setBlockBoundsBasedOnState}/{@code getSelectedBoundingBoxFromPool} callbacks. The
     * static extracted shape is only a safe fallback when the live universe is unavailable; it is
     * never the primary hitbox source. This same shape feeds outline picking and occlusion.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape live = liveShape(level, pos, true);
        return live != null ? live : shape;
    }

    /**
     * Face culling/sky occlusion must follow the legacy render contract, not the extracted
     * fallback cube.  In 1.7.10 a block is an occluding normal cube only when all three probes
     * agree: isOpaqueCube, renderAsNormalBlock, and getRenderType()==0.  TESR/ISBRH and other
     * special-render blocks therefore expose an empty occlusion shape even when their collision
     * or selection geometry is a full cube.
     */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        if (record == null || !record.opaqueCube || !record.renderAsNormalBlock || record.renderType != 0) {
            return Shapes.empty();
        }
        return shape;
    }

    /**
     * Collision is queried independently from the outline because 1.7.10 legitimately has
     * clickable, non-solid blocks. A successful empty legacy result is authoritative and must not
     * fall back to the static shape.
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape live = liveShape(level, pos, false);
        if (live != null) return live;
        return hasCollision ? collisionShape : Shapes.empty();
    }

    /**
     * Vanilla fire placement asks the clicked block for its support shape before it ever reaches
     * the legacy item.  The default 26.2 support-shape cache is derived from native block
     * properties and can therefore treat an extracted legacy block with a full collision box as
     * non-supporting (notably when its visual profile required {@code noOcclusion()}).  Keep the
     * extracted 1.7.10 collision geometry authoritative for generic block support too: this lets
     * any native item whose normal path places a neighbour (flint-and-steel, fire charge, etc.)
     * work on every solid legacy twin without naming a mod or bypassing the normal item path.
     */
    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        VoxelShape live = liveShape(level, pos, false);
        if (live != null) return live;
        // Legacy ItemFlintAndSteel only required the neighbour cell to be air; it did not apply
        // 26.2's SupportType.FULL face test. Preserve that contract for every extracted solid
        // legacy block, including inset barrels and machines whose collision box is valid but
        // narrower than a full modern face. Walk-through legacy blocks remain non-supporting.
        return hasCollision && !collisionShape.isEmpty() ? Shapes.block() : Shapes.empty();
    }

    /**
     * Universal live legacy shape query. The bridge calls the mod's own callbacks for every twin.
     *
     * <p>Only the server thread computes live shapes. The legacy side aliases multiblock tiles
     * in its one shared World and the legacy block's own core lookup keeps state on the block
     * instance, so a concurrent render-thread query corrupted server clicks and collision (doors
     * refusing to open, wrong multiblock hitboxes). Every other caller reads the server's cached
     * answer and keeps the static shape on a miss.
     */
    private VoxelShape liveShape(BlockGetter level, BlockPos pos, boolean selection) {
        try {
            String id = getLegacyId();
            if (id == null || level == null) {
                return null;
            }
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) {
                return null;
            }
            boolean serverLevel = level instanceof Level runtimeLevel && !runtimeLevel.isClientSide();
            java.util.List<double[]> boxes;
            if (!serverLevel) {
                // Client, render and meshing threads only read what the server computed.
                boxes = bridge.cachedShape(id, pos.getX(), pos.getY(), pos.getZ(), selection);
            } else if (selection) {
                boxes = bridge.selectionBoxes(id, pos.getX(), pos.getY(), pos.getZ());
            } else {
                boxes = bridge.collisionBoxes(id, pos.getX(), pos.getY(), pos.getZ());
            }
            if (boxes == null) {
                return null;
            }
            VoxelShape result = Shapes.empty();
            for (double[] b : boxes) {
                if (b == null || b.length != 6) continue;
                result = Shapes.or(result, Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]));
            }
            logLiveShape(id, pos, boxes, result, level.getClass().getName(), selection);
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logLiveShape(String id, BlockPos pos, java.util.List<double[]> boxes,
                                     VoxelShape shape, String levelClass, boolean selection) {
        StringBuilder text = new StringBuilder("[UMB-SHAPE] host channel=")
                .append(selection ? "selection" : "collision").append(" id=").append(id)
                .append(" cell=").append(pos.getX()).append(',').append(pos.getY()).append(',')
                .append(pos.getZ()).append(" level=").append(levelClass).append(" boxes=");
        text.append('[');
        for (int i = 0; i < boxes.size(); i++) {
            if (i != 0) text.append(';');
            text.append(java.util.Arrays.toString(boxes.get(i)));
        }
        text.append("] shape=").append(shape.isEmpty() ? "empty" : shape.bounds());
        String line = text.toString();
        String key = (selection ? "sel@" : "col@") + id + '@' + pos.getX() + ',' + pos.getY()
                + ',' + pos.getZ();
        synchronized (COLLISION_DIAG) {
            if (line.equals(COLLISION_DIAG.get(key))) return;
            COLLISION_DIAG.put(key, line);
        }
        AgentLog.line(line);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (record == null || (!record.hasTileEntity && !dynamicEraTileCandidate())) return null;
        String legacyId = getLegacyId();
        int colon = legacyId == null ? -1 : legacyId.indexOf(':');
        String namespace = colon > 0 ? legacyId.substring(0, colon) : null;
        BlockEntityType<UmbLegacyBlockEntity> type = Registrar.tileTypeFor(namespace);
        if (type == null) return null;
        // The legacy metadata side table is written after HostWorldImpl.setBlock returns, but
        // vanilla calls this factory DURING Level.setBlock. Recover the meta from this registered
        // variant identity so createTile sees meta 6/12/etc instead of the stale side-table 0.
        return new UmbLegacyBlockEntity(type, pos, state, registeredMeta(this, pos, state));
    }

    /** Registered variant identity is the only authoritative meta available during BE creation. */
    static int registeredMeta(UmbLegacyBlock block, BlockPos pos, BlockState state) {
        String id = block == null ? null : block.getLegacyId();
        if (id != null) {
            for (java.util.Map.Entry<String, Block> e : Registrar.LEGACY_VARIANT_BLOCKS.entrySet()) {
                if (e.getValue() != block || !e.getKey().startsWith(id + "@")) continue;
                try {
                    return Integer.parseInt(e.getKey().substring(id.length() + 1));
                } catch (NumberFormatException ignored) {
                    // fall through to the persisted metadata below
                }
            }
        }
        return -1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (record == null || (!record.hasTileEntity && !dynamicEraTileCandidate())) return null;
        if (level.isClientSide()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<UmbLegacyBlockEntity>) UmbLegacyBlockEntity::serverTick;
    }


    /** Random ticks dropped because they arrived before the universe booted ({@link #randomTick}'s
     *  peek-only policy) - an honest counter instead of a silent loss; first drop logs once. */
    public static final java.util.concurrent.atomic.AtomicLong PREBOOT_RANDOM_TICKS_DROPPED =
            new java.util.concurrent.atomic.AtomicLong();

    /** Non-player entity contacts skipped ({@link #entityInside}'s player-first scope) - counted,
     *  first skip logs once. */
    public static final java.util.concurrent.atomic.AtomicLong NONPLAYER_CONTACTS_SKIPPED =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Scheduled tick -&gt; legacy updateTick (func_149674_a, methods.csv). The forward leg already
     * existed (UmbWorld.func_147464_a -&gt; HostWorld.scheduleTick -&gt; the real level.scheduleTick);
     * this is the missing RETURN leg - without it 26.2 delivered every scheduled tick into
     * BlockBehaviour's no-op default and every self-scheduling legacy block silently lost it.
     * ensureBooted (not a bare peek) is deliberate here: 26.2 PERSISTS pending scheduled ticks in
     * the level save, so after a world reload one can arrive before any player interaction - and a
     * pending legacy tick existing at all means legacy machinery was in use, so booting is what
     * that world wants (the ~7s synchronous boot lands during world load, the least surprising
     * moment for it).
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        dispatchTick(level, pos, false, true);
    }

    /**
     * Random tick -&gt; legacy updateTick (func_149674_a) - only ever delivered when {@link Registrar}
     * opted this twin in via Properties.randomTicks() (snapshot tickRandomly / func_149653_t):
     * fluids, fire, gases. PEEK-only, never boots: random ticks fire constantly for every opted-in
     * block in loaded chunks, and letting one trigger the ~7s synchronous universe boot would
     * freeze the game at a moment nobody caused. Pre-boot random ticks are dropped and COUNTED
     * ({@link #PREBOOT_RANDOM_TICKS_DROPPED}); fluids/fire self-heal after boot because random
     * ticks keep coming.
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        dispatchTick(level, pos, true, false);
    }

    private void dispatchTick(ServerLevel level, BlockPos pos, boolean isRandom, boolean mayBoot) {
        if (record == null) {
            return;
        }
        if (!enterReentrancyGuard(isRandom ? "randomTick" : "tick")) {
            return;
        }
        try {
            UmbThread.assertServer();
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) {
                return;
            }
            if (mayBoot) {
                if (!UmbBridgeHost.ensureBooted(new HostWorldImpl(level))) {
                    return;
                }
            } else if (!bridge.isBooted()) {
                if (PREBOOT_RANDOM_TICKS_DROPPED.getAndIncrement() == 0L) {
                    AgentLog.line("UmbLegacyBlock.randomTick: dropping random ticks until the legacy"
                            + " universe boots (peek-only policy) - counting from here on");
                }
                return;
            }
            bridge.tickBlock(legacyId, pos.getX(), pos.getY(), pos.getZ(), isRandom);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyBlock.dispatchTick", t, 4);
        } finally {
            exitReentrancyGuard();
        }
    }

    /**
     * entityInside -&gt; legacy onEntityCollidedWithBlock (func_149670_a, methods.csv: "Triggered
     * whenever an entity collides with this block (enters into the block)") - conveyors pushing,
     * gas/fire/spike blocks hurting. Fires every tick the entity's AABB overlaps this block, which
     * gives walk-through twins an empty collision shape. PLAYER-FIRST scope (the bridge has no
     * legacy facade for an arbitrary native entity): non-player contacts are counted and skipped
     * ({@link #NONPLAYER_CONTACTS_SKIPPED}). PEEK-only like {@link #randomTick}: standing inside a
     * block nobody interacted with must not trigger the ~7s boot.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                 InsideBlockEffectApplier applier, boolean flag) {
        if (record == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(entity instanceof ServerPlayer serverPlayer)) {
            if (NONPLAYER_CONTACTS_SKIPPED.getAndIncrement() == 0L) {
                AgentLog.line("UmbLegacyBlock.entityInside: skipping non-player contacts (no legacy"
                        + " facade for arbitrary native entities yet) - counting from here on");
            }
            return;
        }
        if (!enterReentrancyGuard("entityInside")) {
            return;
        }
        try {
            UmbThread.assertServer();
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null || !bridge.isBooted()) {
                return;
            }
            bridge.entityInside(legacyId, pos.getX(), pos.getY(), pos.getZ(),
                    new HostPlayerImpl(serverPlayer));
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyBlock.entityInside", t, 4);
        } finally {
            exitReentrancyGuard();
        }
    }

    /**
     * E3: bridges 1.7.10's onBlockActivated with the held item/hand visible (26.2's dispatch
     * always tries this BEFORE {@link #useWithoutItem}, PART E1). The held stack itself does not
     * need to be threaded through the bridge call: {@code HostPlayerImpl.getHeldItem()} already
     * reports it to legacy code exactly as {@code func_149727_a} would see it via
     * {@code player.getHeldItem()} - including FM-6 (a native item with no legacy twin reports as
     * absent, never as a fabricated empty stack that gets written back). This override exists so
     * that outcome is reached deterministically by OUR code, not by relying on
     * {@code BlockBehaviour}'s default {@code useItemOn} body happening to be non-consuming.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {
        if (record == null) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        InteractionResult result;
        try {
            result = doActivate(level, pos, player, hit);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.useItemOn", t, 4);
            result = InteractionResult.PASS;
        }
        if (result.consumesAction()) {
            return result;
        }
        // legacy declined (or the bridge could not run): let vanilla fall through to
        // useWithoutItem (PART E2's sanctioned use of TRY_WITH_EMPTY_HAND) but remember that THIS
        // click already ran the real activation, so useWithoutItem does not run it again.
        RECENTLY_DECLINED.set(pos);
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (record == null) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }
        BlockPos marker = RECENTLY_DECLINED.get();
        RECENTLY_DECLINED.remove();
        if (pos.equals(marker)) {
            return InteractionResult.PASS;
        }
        try {
            return doActivate(level, pos, player, hit);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.useWithoutItem", t, 4);
            return InteractionResult.PASS;
        }
    }

    /**
     * Left click (INTERACTION-BRIDGE.md build order item 6): bridges 1.7.10's onBlockClicked
     * (func_149699_a), which every mod's redstone-ish "tap to check" or warning blocks use. Void
     * both sides - no InteractionResult, no GUI, so there is no boolean/consuming-result mapping
     * to worry about (BUG-2 does not apply here).
     */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (record == null || level.isClientSide()) {
            return;
        }
        try {
            UmbThread.assertServer();
            if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (record.hasTileEntity) {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof UmbLegacyBlockEntity ulbe) || ulbe.isPoisoned()) {
                    return;
                }
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
            bridge.clicked(legacyId, pos.getX(), pos.getY(), pos.getZ(), hostPlayer);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.attack", t, 4);
        }
    }

    /**
     * PART 1 (INTERACTION-BRIDGE.md / GENERALIZATION-PLAN.md GAP 3): bridges 1.7.10's
     * onBlockPlacedBy (func_149689_a). THIS is the fix for "a multiblock places one tiny crushed
     * block" - a multiblock controller writes its own filler/placeholder blocks from inside its own
     * onBlockPlacedBy, using {@code World.setBlock}, which already reaches
     * {@link HostWorldImpl#setBlock} and the real 26.2 level (see that class). No mod-specific code
     * is needed here at all: every Forge mod's multiblock machinery, whatever it is called, is
     * reached the same way because it is built on this one vanilla callback.
     *
     * is declared on {@code Block} (not {@code BlockBehaviour}), its default body is a true no-op,
     * and {@code ServerPlayerGameMode}/{@code BlockItem}'s placement path calls it with the REAL
     * placing player after the block state is already written - matching 1.7.10's own
     * onBlockPlacedBy timing (called once, right after the block appears, with the placer and the
     * item used).</p>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity entity, ItemStack stack) {
        super.setPlacedBy(level, pos, state, entity, stack);
        if (record == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer serverPlayer)) {
            // 1.7.10's onBlockPlacedBy technically accepts any EntityLivingBase (e.g. a dispenser
            // "placing" a block is not modeled that way in 1.7.10 anyway - real placement is always
            // player-driven); dev.umb.bridge.api.HostPlayer only models a player, so a non-player
            // placer is an honest, documented gap rather than a crash.
            return;
        }
        if (!enterReentrancyGuard("setPlacedBy")) {
            return;
        }
        try {
            UmbThread.assertServer();
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
            // BlockItem.place calls this hook with the exact stack before it consumes one item.
            // Do not re-read ServerPlayer.mainHand: that can already be empty or changed by a
            // placement path, and legacy multiblocks legitimately inspect damage/NBT here.
            StackData placedStack = LegacyStackConv.toLegacy(stack);
            bridge.placedBy(legacyId, pos.getX(), pos.getY(), pos.getZ(), hostPlayer, placedStack);
            // ORDERING FIX (found verifying PART 1's acceptance test): 26.2 attaches this block's
            // BlockEntity (EntityBlock.newBlockEntity -> BlockEntity.setLevel -> UmbLegacyBlockEntity
            // .ensureHandle -> LegacyBridge.createTile) DURING the earlier level.setBlock call that
            // wrote the block's INITIAL state - i.e. strictly BEFORE this setPlacedBy call runs. A
            // real 1.7.10 pattern (BlockDummyable-shaped multiblocks confirmed live: meta < 6 has NO
            // tile entity, meta >= 6/12 does) is for onBlockPlacedBy to be what raises THIS position's
            // own metadata to its real, tile-entity-bearing value via setBlockMetadataWithNotify
            // (already bridged, BUG-3). Without this retry, ensureHandle already ran once at the
            // pre-onBlockPlacedBy meta, got createTile()==null, and PERMANENTLY poisoned itself
            // before the meta was ever corrected. Retrying once, right here, after legacy code has
            // had its chance to fix the meta, closes that race - safe/idempotent if a handle already
            // exists, and re-poisons with the same honest log if createTile still genuinely fails.
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof UmbLegacyBlockEntity ulbe) {
                ulbe.retryAfterPlacement();
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.setPlacedBy: legacy placement failed for " + legacyId
                    + " at " + pos, t, 4);
        } finally {
            exitReentrancyGuard();
        }
    }

    /**
     * PART 1: bridges 1.7.10's onBlockAdded (func_149726_b). Every 1.7.10 metadata variant of a
     * legacy block is registered as its OWN 26.2 {@code Block} instance (see {@link Registrar}), so
     * a meta-driven variant swap (BUG-3's {@code setMeta} -> {@link HostWorldImpl#setBlock}) also
     * looks like "a different block replaced the old one" from 26.2's own {@code onPlace} dispatch -
     * which would fire onBlockAdded on every metadata change, unlike real 1.7.10 (there,
     * onBlockAdded fires only once, at genuine placement, never on a same-block meta change). The
     * filter below restores the correct semantics generically: skip the call when the old and new
     * state are both {@link UmbLegacyBlock} twins sharing the SAME legacy id (a meta swap, not a new
     * placement); otherwise (old state was air, or a genuinely different block) this is a real new
     * block appearing, so bridge it - covering both the player-placed controller (an air -&gt; block
     * transition also reaches this, redundantly with {@link #setPlacedBy} but that mirrors 1.7.10's
     * own World.setBlock, which calls onBlockAdded unconditionally alongside onBlockPlacedBy for a
     * player placement) and every filler block a mod's own onBlockPlacedBy just wrote.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (record == null || level.isClientSide()) {
            return;
        }
        if (oldState.getBlock() instanceof UmbLegacyBlock oldTwin
                && legacyId != null && legacyId.equals(oldTwin.getLegacyId())) {
            return; // same legacy identity -> a meta-variant swap, not a genuine new placement
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!enterReentrancyGuard("onPlace")) {
            return;
        }
        try {
            UmbThread.assertServer();
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return;
            }
            UmbBridgeHost.get().added(legacyId, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.onPlace", t, 4);
        } finally {
            exitReentrancyGuard();
        }
    }

    /**
     * PART 1: bridges 1.7.10's onNeighborBlockChange (func_149695_a) - redstone-reactive machines,
     * pipes and anything that only reacts to a neighbor changing (not a direct click) stay inert
     * without this. {@code neighborChanged} is the closest 26.2 hook: same "one of my neighbors just
     * is a true no-op).
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                    Orientation orientation, boolean movedByPiston) {
        forwardNeighborChanged(level, pos, neighborBlock);
    }

    /** Shared by the host Block callback and BlockEntity.onNeighborChange. */
    void forwardNeighborChanged(Level level, BlockPos pos, Block neighborBlock) {
        if (record == null || level.isClientSide()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockEntity be = null;
        try {
            be = level.getBlockEntity(pos);
        } catch (Throwable ignored) {
            // Headless callback probes may provide a deliberately incomplete Level; the legacy
            // neighbor callback itself remains testable and authoritative without a BE dedupe.
        }
        if (be instanceof UmbLegacyBlockEntity ulbe
                && !ulbe.claimNeighborNotification(level.getGameTime(), neighborBlock)) {
            return; // one physical event can surface through both modern hooks
        }
        if (!enterReentrancyGuard("neighborChanged")) {
            return;
        }
        try {
            UmbThread.assertServer();
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return;
            }
            String neighborLegacyId = HostWorldImpl.legacyIdForBlock(neighborBlock);
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge != null) {
                bridge.invalidateShape(legacyId, pos.getX(), pos.getY(), pos.getZ());
                bridge.neighborChanged(legacyId, pos.getX(), pos.getY(), pos.getZ(), neighborLegacyId);
                bridge.invalidateShape(legacyId, pos.getX(), pos.getY(), pos.getZ());
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.neighborChanged", t, 4);
        } finally {
            exitReentrancyGuard();
        }
    }

    /**
     * PART 1: bridges 1.7.10's canPlaceBlockAt (func_149742_c). {@code canSurvive}'s default is
     * unbooted universe degrades to "always placeable" rather than silently blocking every
     * placement.
     */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (record == null || level.isClientSide()) {
            return super.canSurvive(state, level, pos);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        try {
            UmbThread.assertServer();
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return true;
            }
            return UmbBridgeHost.get().canPlaceAt(legacyId, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.canSurvive", t, 4);
            return true;
        }
    }

    /**
     * CRITICAL RISK (PART 1): "break must undo placement". {@code playerWillDestroy} is
     * state and its {@code BlockEntity} (disassembly of {@code ServerPlayerGameMode}'s destroy-block
     * method shows the block entity is fetched and {@code playerWillDestroy} is called BEFORE
     * {@code ServerLevel.removeBlock}; the SAME captured block-entity reference is reused afterwards
     * for vanilla's own {@code playerDestroy}/drop logic). Bridging 1.7.10's breakBlock
     * (func_149749_a) here, while the block and its filler positions/tile-entity inventory are still
     * fully valid, lets a multiblock controller's OWN cleanup code remove every filler block it
     * created (through the same working world facade) and lets a machine's OWN inventory-eject code
     * run - exactly the same "let the mod's own vanilla-API code do it" principle as
     * {@link #setPlacedBy}, just at the other end of the block's life. onBlockDestroyedByPlayer
     * (func_149664_b, "called right before the block is destroyed by a player") is bridged first, in
     * the same call, for the same reason it exists in 1.7.10: it fires at exactly this moment.
     *
     * <p>Honest scope limit: this only fires for PLAYER-caused destruction (an explosion, a piston,
     * or fluid replacing the block does not reach this hook) - see the report's honest-gaps section.</p>
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Vanilla's own presentation (break particles, piglin anger, the BLOCK_DESTROY game event)
        // is worth keeping - it is exactly the "26.2's own renderer, not legacy's" principle this
        // project applies everywhere else - but it is defensive, not load-bearing: it must never be
        // allowed to stop OUR bridge call below (e.g. a level whose fields are not the shape vanilla
        // expects must still let the mod's own breakBlock/drop code run).
        BlockState result = state;
        try {
            result = super.playerWillDestroy(level, pos, state, player);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.playerWillDestroy.vanillaEffects", t, 2);
        }
        if (record == null || level.isClientSide()) {
            return result;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return result;
        }
        if (!enterReentrancyGuard("playerWillDestroy")) {
            return result;
        }
        try {
            UmbThread.assertServer();
            if (record.hasTileEntity) {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof UmbLegacyBlockEntity ulbe) || ulbe.isPoisoned()) {
                    return result;
                }
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return result;
            }
            int meta = world.getMeta(pos.getX(), pos.getY(), pos.getZ());
            LegacyBridge bridge = UmbBridgeHost.get();
            HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
            bridge.broken(legacyId, pos.getX(), pos.getY(), pos.getZ(), meta, hostPlayer);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyBlock.playerWillDestroy", t, 4);
        } finally {
            exitReentrancyGuard();
        }
        return result;
    }

    /**
     * The real right-click dispatch, shared by {@link #useItemOn} and {@link #useWithoutItem}.
     * PART B side gating preserved verbatim: client returns a non-authoritative SUCCESS (the
     * server drives the real interaction and sends the open-screen packet itself), server does the
     * one real call. FM-1: hitX/hitY/hitZ are converted from 26.2's absolute {@code BlockHitResult}
     * location to 1.7.10's block-relative 0..1 floats by subtracting the hit block's own position.
     */
    private InteractionResult doActivate(Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        UmbThread.assertServer();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (record.hasTileEntity) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof UmbLegacyBlockEntity ulbe) || ulbe.isPoisoned()) {
                return InteractionResult.FAIL;
            }
        }
        HostWorldImpl world = new HostWorldImpl(serverLevel);
        if (!UmbBridgeHost.ensureBooted(world)) {
            return InteractionResult.FAIL;
        }
        LegacyBridge bridge = UmbBridgeHost.get();
        HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
        int side = hit.getDirection() != null ? hit.getDirection().get3DDataValue() : 0;
        BlockPos hitPos = hit.getBlockPos() != null ? hit.getBlockPos() : pos;
        float hitX = HitCoordsUtil.relative(hit.getLocation().x, hitPos.getX());
        float hitY = HitCoordsUtil.relative(hit.getLocation().y, hitPos.getY());
        float hitZ = HitCoordsUtil.relative(hit.getLocation().z, hitPos.getZ());
        ActivationResult result = bridge.activate(legacyId, pos.getX(), pos.getY(), pos.getZ(),
                hostPlayer, side, hitX, hitY, hitZ);
        if (result == null) {
            return InteractionResult.PASS;
        }
        if (result.container != null) {
            // HOST-SIDE title resolution (M1 visual fix): handle.title() is the legacy container's
            // own title text, which is frequently just the raw unlocalized/legacy id -- NOT a
            // human-readable name, and the bridge
            // contract does not promise otherwise. This twin's own registered name IS
            // human-readable: Registrar already gave every twin a descriptionId of
            // "block.<namespace>.<path>" (Block.getName() == Component.translatable(descriptionId),
            // row from the snapshot's displayName (falling back to a humanized id) into the
            // generated resourcepack, so this resolves to "Bricked Furnace" client-side with no
            // contract change and no umb-legacy edit.
            Component title = this.getName();
            // entity already tracks at this exact position (see UmbLegacyBlockEntity#currentHandle)
            // - no new dev.umb.bridge.api lookup needed. null for a block with no tile entity at
            // all (record.hasTileEntity==false), which UmbMenuProvider/UmbLegacyMenu already treat
            // as "nothing to snapshot", never an error.
            BlockEntity openedBe = level.getBlockEntity(pos);
            dev.umb.bridge.api.TileHandle tileHandle =
                    openedBe instanceof UmbLegacyBlockEntity openedUlbe ? openedUlbe.currentHandle() : null;
            serverPlayer.openMenu(new UmbMenuProvider(result.container, title, tileHandle,
                    pos.getX(), pos.getY(), pos.getZ()));
        }
        // BUG-2: the legacy boolean must actually gate the result. true -> a result whose
        // consumesAction() is true (PART E2: SUCCESS_SERVER is the correct choice for work that
        // happened authoritatively on the server with no client-side prediction), so vanilla never
        // reaches ItemStack.useOn and places the player's held block on top of a successful
        // interaction. false -> PASS, so a genuinely-declined interaction still lets vanilla's
        // held-item behaviour (e.g. placing a block) run.
        return result.handled ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
    }
}
