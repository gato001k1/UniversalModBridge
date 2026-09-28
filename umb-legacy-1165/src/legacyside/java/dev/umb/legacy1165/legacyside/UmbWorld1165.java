package dev.umb.legacy1165.legacyside;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostWorld;

import net.minecraft.entity.Entity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.Property;
import net.minecraft.state.StateContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.ISpawnWorldInfo;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The 1.16.5 world facade: a REAL {@code World} subclass with map-backed block/tile storage
 * (no chunks, no server) plus the per-block state-index mapping that lets the pre-BlockState
 * {@code HostWorld.getMeta/setMeta(int)} contract keep working - the lead's round-4 direction
 * ("meta = index into a per-block state list you define", additive proposal stays text-only).
 *
 * <p>Construction ingredients, every one grounded (see ERA-1165-PLAN.md round-4 notes):</p>
 * <ul>
 *   <li>Dimension: genuine vanilla path - {@code DynamicRegistries.func_239770_b_()} +
 *       {@code DimensionType.func_236027_a_(impl)} (default registration, what server startup
 *       does) + overworld lookup by the {@code <clinit>}-proven overworld key
 *       ({@code DimensionType.field_235999_c_}).</li>
 *   <li>Dimension key: {@code World.field_234918_g_} ({@code <clinit>}-proven
 *       {@code "overworld"}).</li>
 *   <li>World info: dynamic Proxy over {@code ISpawnWorldInfo} (defaults; the ctor only
 *       stores it - proven by constructor-body analysis).</li>
 *   <li>Profiler supplier: Proxy no-op (nothing in the vertical profiles).</li>
 *   <li>{@code isRemote=false} (first boolean ctor param - proven by putfield mapping).</li>
 * </ul>
 *
 * <p>World behavior: {@code getBlockState}/{@code getTileEntity} serve explicit maps
 * (positions the bridge placed); entity queries return empty lists (headless rule - no
 * players nearby, lid/proximity logic degrades to zero, never fakes); sounds/particles are
 * no-ops; everything else honestly absent (null/0/false) and counted by callers, never
 * faked.</p>
 */
public class UmbWorld1165 extends World {

    private final HostWorld host;
    private final HostLevel hostLevel;
    private final MinecraftServer resourceServer;
    private UmbPlayer1165 bridgePlayer;
    private final Map<Entity, EntityHandle1165> entities =
            new IdentityHashMap<Entity, EntityHandle1165>();

    private final Map<BlockPos, BlockState> states = new HashMap<BlockPos, BlockState>();
    private final Map<BlockPos, Block> blockAt = new HashMap<BlockPos, Block>();
    private final Map<BlockPos, net.minecraft.tileentity.TileEntity> tiles =
            new HashMap<BlockPos, net.minecraft.tileentity.TileEntity>();
    /**
     * Bridge-owned tile handles.  The host's 26.2 block-entity ticker is not the legacy
     * universe's scheduler: a formed 1.16.5 multiblock can create its master/dummy tiles in
     * this facade without those objects ever entering a native Level tick list.  Keep the
     * handles beside the map-backed tile surface so the existing host-driven pass can invoke
     * the mod's own ITickableTileEntity implementation once per legacy tick.
     */
    private final Map<BlockPos, TileHandle1165> tileHandles =
            new HashMap<BlockPos, TileHandle1165>();
    private long tileTickPasses;
    private long tileRegistrations;

    /**
     * Compatibility chunks for legacy callbacks which ask World for its provider before
     * reading the map-backed block surface.  These are not a second world store: Chunk is
     * only the vanilla carrier required by World.func_175726_f/func_217353_a; all block and
     * tile reads still come through this facade's host-backed methods.
     */
    private final Map<Long, net.minecraft.world.chunk.Chunk> compatibilityChunks =
            new HashMap<Long, net.minecraft.world.chunk.Chunk>();
    private final net.minecraft.world.chunk.AbstractChunkProvider chunkProvider;

    /** Per-block index mapping: Block -> its full valid-state list (built on demand). */
    private final Map<Block, List<BlockState>> stateLists = new HashMap<Block, List<BlockState>>();

    public UmbWorld1165() {
        this(null);
    }

    public UmbWorld1165(HostWorld host) {
        this(host, null);
    }

    public UmbWorld1165(HostWorld host, MinecraftServer resourceServer) {
        super(fakeInfo(), World.field_234918_g_, overworldDimension(), profilerSupplier(), false,
                false, 1L);
        this.host = host;
        this.hostLevel = host instanceof HostLevel ? (HostLevel) host : null;
        this.resourceServer = resourceServer;
        this.chunkProvider = new net.minecraft.world.chunk.AbstractChunkProvider() {
            @Override
            public net.minecraft.world.chunk.IChunk func_212849_a_(int chunkX, int chunkZ,
                    net.minecraft.world.chunk.ChunkStatus status, boolean nonnull) {
                return compatibilityChunk(chunkX, chunkZ);
            }

            @Override
            public String func_73148_d() {
                return "umb-legacy-1165-map-backed";
            }

            @Override
            public net.minecraft.world.IBlockReader func_212864_k_() {
                return UmbWorld1165.this;
            }

            @Override
            public net.minecraft.world.lighting.WorldLightManager func_212863_j_() {
                // No lighting engine exists in the bridge's headless facade.  Legacy code
                // that needs actual light is outside this compatibility surface.
                return null;
            }
        };
    }

    private net.minecraft.world.chunk.Chunk compatibilityChunk(int chunkX, int chunkZ) {
        long key = net.minecraft.util.math.ChunkPos.func_77272_a(chunkX, chunkZ);
        net.minecraft.world.chunk.Chunk chunk = compatibilityChunks.get(Long.valueOf(key));
        if (chunk == null) {
            chunk = new net.minecraft.world.chunk.Chunk(this,
                    new net.minecraft.util.math.ChunkPos(chunkX, chunkZ), null);
            compatibilityChunks.put(Long.valueOf(key), chunk);
        }
        return chunk;
    }

    // ---- construction ingredients ----

    private static ISpawnWorldInfo fakeInfo() {
        return (ISpawnWorldInfo) java.lang.reflect.Proxy.newProxyInstance(
                UmbWorld1165.class.getClassLoader(),
                new Class<?>[] { ISpawnWorldInfo.class },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method,
                            Object[] args) {
                        Class<?> ret = method.getReturnType();
                        if (ret == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (ret == int.class || ret == long.class || ret == float.class
                                || ret == double.class) {
                            return 0;
                        }
                        return null;
                    }
                });
    }

    private static DimensionType overworldDimension() {
        net.minecraft.util.registry.DynamicRegistries.Impl impl =
                net.minecraft.util.registry.DynamicRegistries.func_239770_b_();
        impl = DimensionType.func_236027_a_(impl);
        java.util.Optional<net.minecraft.util.registry.MutableRegistry<DimensionType>> opt =
                impl.func_230521_a_(
                        net.minecraft.util.registry.Registry.field_239698_ad_);
        net.minecraft.util.registry.MutableRegistry<DimensionType> reg = opt.orElse(null);
        if (reg == null) {
            throw new IllegalStateException("dimension-type registry missing after defaults");
        }
        DimensionType overworld = reg.func_230516_a_(DimensionType.field_235999_c_);
        if (overworld == null) {
            throw new IllegalStateException("overworld DimensionType missing after defaults");
        }
        return overworld;
    }

    private static Supplier<net.minecraft.profiler.IProfiler> profilerSupplier() {
        return new Supplier<net.minecraft.profiler.IProfiler>() {
            @Override
            public net.minecraft.profiler.IProfiler get() {
                return (net.minecraft.profiler.IProfiler) java.lang.reflect.Proxy.newProxyInstance(
                        UmbWorld1165.class.getClassLoader(),
                        new Class<?>[] { net.minecraft.profiler.IProfiler.class },
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method,
                                    Object[] args) {
                                Class<?> ret = method.getReturnType();
                                if (ret == boolean.class) {
                                    return Boolean.FALSE;
                                }
                                if (ret == int.class || ret == long.class || ret == float.class
                                        || ret == double.class) {
                                    return 0;
                                }
                                return null;
                            }
                        });
            }
        };
    }

    // ---- map-backed world surface ----

    /** Generic child-universe server seam for mod-owned server-data resource lookups. */
    @Override
    public MinecraftServer func_73046_m() {
        return resourceServer;
    }

    /** Places a state at a position (bridge-driven; the 26.2 host owns real placement). */
    public void putState(BlockPos pos, Block block, BlockState state,
            net.minecraft.tileentity.TileEntity tile) {
        states.put(pos, state);
        blockAt.put(pos, block);
        if (tile != null) {
            tiles.put(pos, tile);
            registerTile(pos, tile);
        } else {
            tiles.remove(pos);
            tileHandles.remove(pos);
        }
    }

    /** Returns the scheduler-owned handle for a tile position, if one is registered. */
    TileHandle1165 tileHandle(BlockPos pos) {
        return tileHandles.get(pos);
    }

    public void removeAt(BlockPos pos) {
        states.remove(pos);
        blockAt.remove(pos);
        tiles.remove(pos);
        tileHandles.remove(pos);
    }

    @Override
    public BlockState func_180495_p(BlockPos pos) {
        // Legacy callbacks run against the host's live block graph.  Serve host identity/meta
        // first so a generic multiblock matcher sees native cells, not only local facade writes.
        // HostWorld reads are the existing non-blocking boundary (stateIfLoaded on the host).
        if (host != null) {
            String id = host.getBlockId(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p());
            if (id != null && !"minecraft:air".equals(id)) {
                Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
                if (block != null) {
                    BlockState state = stateAt(block, host.getMeta(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p()));
                    if (state == null) state = block.func_176223_P();
                    states.put(pos, state);
                    blockAt.put(pos, block);
                    return state;
                }
            }
            states.remove(pos);
            blockAt.remove(pos);
            return airState();
        }
        BlockState state = states.get(pos);
        if (state != null) {
            return state;
        }
        return airState();
    }

    /** Air by registry lookup, never by an assumed field (field numbers are SRG-versioned). */
    private static BlockState airState() {
        Block air = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getValue(new ResourceLocation("minecraft:air"));
        if (air == null) {
            throw new IllegalStateException("minecraft:air missing from block registry");
        }
        return air.func_176223_P();
    }

    /** Generic legacy World.setBlockState seam; native host writes remain authoritative. */
    @Override
    public boolean func_180501_a(BlockPos pos, BlockState state, int flags) {
        if (pos == null || state == null) return false;
        Block block = state.func_177230_c();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) return false;
        int meta = stateIndexFor(block, state);
        if (host != null) {
            host.setBlock(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p(),
                    key.toString(), meta, flags);
            String actual = host.getBlockId(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p());
            if (!key.toString().equals(actual)) return false;
        }
        putState(pos, block, state, tiles.get(pos));
        return true;
    }

    /** 1.16.5 World.func_175656_a: the vanilla default flag form of setBlockState. */
    @Override
    public boolean func_175656_a(BlockPos pos, BlockState state) {
        return func_180501_a(pos, state, 3);
    }

    private int stateIndexFor(Block block, BlockState state) {
        List<BlockState> all = stateList(block);
        int index = all.indexOf(state);
        return index < 0 ? 0 : index;
    }

    @Override
    public net.minecraft.tileentity.TileEntity func_175625_s(BlockPos pos) {
        return tiles.get(pos);
    }

    @Override
    public void func_175646_b(BlockPos pos, net.minecraft.tileentity.TileEntity tile) {
        // markAndNotifyBlock: no chunks to mark dirty and no clients to notify headlessly.
        // Same family as the no-op sounds/particles. Persistence goes through explicit NBT.
    }

    @Override
    public boolean func_175667_e(BlockPos pos) {
        // The host graph is the loaded area for this facade.  This lets generic callbacks see
        // real host cells before they have been copied into the local map.
        return states.containsKey(pos) || (host != null
                && !"minecraft:air".equals(host.getBlockId(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p())));
    }

    @Override
    public void func_175690_a(BlockPos pos, net.minecraft.tileentity.TileEntity tile) {
        if (tile == null) {
            tiles.remove(pos);
            tileHandles.remove(pos);
        } else {
            tiles.put(pos, tile);
            registerTile(pos, tile);
        }
    }

    /**
     * Mirror vanilla's setTileEntity lifecycle: a mod-created master/dummy must receive this
     * facade and position before its ticker runs. This is generic and applies to every TE type.
     */
    private void registerTile(BlockPos pos, net.minecraft.tileentity.TileEntity tile) {
        TileHandle1165 handle = tileHandles.get(pos);
        if (handle == null || handle.raw() != tile) {
            handle = new TileHandle1165(tile, this, pos);
            tileHandles.put(pos, handle);
            tileRegistrations++;
            try {
                handle.bindWorldAndPos();
            } catch (Throwable t) {
                if (host != null) host.log("UMB-TILE-1165 bind failed class="
                        + tile.getClass().getName() + " pos=" + pos + " error=" + t);
            }
            if (host != null) {
                host.log("UMB-TILE-1165 register #" + tileRegistrations + " "
                        + handle.diagnostics());
            }
        }
    }

    @Override
    public void func_175713_t(BlockPos pos) {
        if (host != null) host.removeBlock(pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p());
        removeAt(pos);
    }

    @Override
    public net.minecraft.world.chunk.AbstractChunkProvider func_72863_F() {
        // Legacy mod callbacks legitimately use World.func_175726_f/func_217353_a while
        // matching multiblocks or activating a block.  Returning null makes those callbacks
        // fail before they reach this facade's authoritative host-backed block methods.
        return chunkProvider;
    }

    @Override
    public net.minecraft.world.ITickList<net.minecraft.fluid.Fluid> func_205219_F_() {
        return null;
    }

    @Override
    public net.minecraft.world.ITickList<net.minecraft.block.Block> func_205220_G_() {
        return null;
    }

    // ---- remaining interface abstracts: honest headless stubs ----

    @Override
    public net.minecraft.util.registry.DynamicRegistries func_241828_r() {
        return null;
    }

    @Override
    public java.util.stream.Stream<net.minecraft.util.math.shapes.VoxelShape> func_230318_c_(
            net.minecraft.entity.Entity entity,
            net.minecraft.util.math.AxisAlignedBB box,
            java.util.function.Predicate<net.minecraft.entity.Entity> filter) {
        return java.util.stream.Stream.empty();
    }

    @Override
    public long func_241851_ab() {
        return 0L;
    }

    @Override
    public java.util.List<? extends net.minecraft.entity.player.PlayerEntity> func_217369_A() {
        UmbPlayer1165 player = bridgePlayer;
        return player == null ? java.util.Collections.emptyList()
                : java.util.Collections.<net.minecraft.entity.player.PlayerEntity>singletonList(player);
    }

    void bindBridgePlayer(UmbPlayer1165 player) {
        this.bridgePlayer = player;
    }

    @Override
    public net.minecraft.world.biome.Biome func_225604_a_(int x, int y, int z) {
        // No biomes headlessly (IBiomeReader absentee). Callers needing real biomes are
        // outside the vertical; null (not a fake default) so misuse fails loudly downstream.
        return null;
    }

    // NOTE: ISeedReader/IStructureReader are NOT in World's hierarchy (IWorld extends only
    // IBiomeReader/IDayTimeReader plus others) - no seed/structure surface exists here at all,
    // which is itself an honest scope boundary for a headless universe.

    @Override
    public <T extends net.minecraft.entity.Entity> List<T> func_217357_a(Class<? extends T> type,
            AxisAlignedBB box) {
        List<T> out = new java.util.ArrayList<T>();
        for (Entity entity : new java.util.ArrayList<Entity>(entities.keySet())) {
            if (entity == null || entity.field_70128_L || !type.isInstance(entity)) continue;
            if (entity.func_174813_aQ() != null && entity.func_174813_aQ().func_72326_a(box)) {
                out.add(type.cast(entity));
            }
        }
        return out;
    }

    /** 1.16.5 spawnEntityInWorld seam: the legacy object already exists; twin it in the host. */
    @Override
    public boolean func_217376_c(Entity entity) {
        if (entity == null) return false;
        try {
            EntityHandle1165 handle = entities.get(entity);
            if (handle == null) {
                handle = new EntityHandle1165(entity);
                entities.put(entity, handle);
            }
            boolean twinned = hostLevel == null || hostLevel.spawnEntity(handle);
            if (host != null) {
                host.log("ENTITY-DIAG 1165 spawnEntityInWorld class=" + entity.getClass().getName()
                        + " owner=" + handle.ownerNamespace() + " twinned=" + twinned
                        + " pos=" + entity.func_226277_ct_() + "," + entity.func_226278_cu_() + ","
                        + entity.func_226279_cv_());
            }
            return true;
        } catch (Throwable t) {
            if (host != null) host.log("ENTITY-DIAG 1165 spawn failed: " + t);
            return true;
        }
    }

    /** One host-driven legacy entity pass, matching the 1.7.10 bridge lifecycle. */
    public void tickEntities() {
        tickTiles();
        for (Entity entity : new java.util.ArrayList<Entity>(entities.keySet())) {
            EntityHandle1165 handle = entities.get(entity);
            if (entity == null || handle == null || !handle.isValid()) continue;
            try {
                if (hostLevel != null) hostLevel.prepareEntity(handle);
                handle.tick();
                if (hostLevel != null && handle.isValid()) hostLevel.syncEntity(handle);
            } catch (Throwable t) {
                if (host != null) host.log("UMB-ENTITY-1165 tick failed: " + t);
            }
        }
    }

    /**
     * One host-driven legacy tile pass.  This is intentionally separate from the entity loop:
     * native 1.16.5 Level ticker lists are not authoritative for tiles backed by this facade,
     * while the legacy tile itself remains authoritative for its state and processing.
     */
    private void tickTiles() {
        tileTickPasses++;
        for (TileHandle1165 handle : new java.util.ArrayList<TileHandle1165>(tileHandles.values())) {
            if (handle == null || !handle.isValid()) continue;
            try {
                handle.tick();
            } catch (Throwable t) {
                if (host != null) host.log("UMB-TILE-1165 tick failed: " + t);
            }
        }
        if (host != null && (tileTickPasses <= 3 || tileTickPasses % 100 == 0)) {
            StringBuilder summary = new StringBuilder("UMB-TILE-1165 pass=")
                    .append(tileTickPasses).append(" list=").append(tileHandles.size())
                    .append(" remote=").append(field_72995_K).append(" entries=");
            boolean first = true;
            for (TileHandle1165 handle : tileHandles.values()) {
                if (!first) summary.append(" || ");
                first = false;
                summary.append(handle.diagnostics());
            }
            host.log(summary.toString());
        }
    }

    // ---- state-index mapping: the meta-behind-contract ----

    /**
     * Index of the position's current state in its block's full valid-state list, or 0 when
     * unknown. The list order is Forge's own ({@code StateContainer} order), stable per
     * session; indices are this bridge's convention, never a vanilla id.
     */
    public int stateIndex(BlockPos pos) {
        BlockState state = states.get(pos);
        Block block = blockAt.get(pos);
        if (state == null || block == null) {
            return 0;
        }
        List<BlockState> all = stateList(block);
        int i = all.indexOf(state);
        return i < 0 ? 0 : i;
    }

    /** State at {@code index} in the block's list (clamped), or null when unknown. */
    public BlockState stateAt(Block block, int index) {
        List<BlockState> all = stateLists.get(block);
        if (all == null || all.isEmpty()) {
            return null;
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= all.size()) {
            index = all.size() - 1;
        }
        return all.get(index);
    }

    /** Full valid-state list for a block (built once, Forge order). */
    public List<BlockState> stateList(Block block) {
        List<BlockState> all = stateLists.get(block);
        if (all == null) {
            StateContainer<Block, BlockState> container = block.func_176194_O();
            all = new java.util.ArrayList<BlockState>(container.func_177619_a());
            stateLists.put(block, all);
        }
        return all;
    }

    /**
     * Property map of a state as name-&gt;value strings, e.g. {@code {facing=north}} - the
     * vocabulary the additive contract proposal (still text-only) would carry. Pure read of
     * the state's own map; no invention.
     */
    public static Map<String, String> stateProperties(BlockState state) {
        Map<String, String> out = new LinkedHashMapSafeMap<String, String>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.func_206871_b().entrySet()) {
            out.put(entry.getKey().func_177701_a(),
                    valueName(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property<?> property, Comparable<?> value) {
        return ((Property) property).func_177702_a(value);
    }

    // ---- World abstract methods: honest stubs (null/0/false/no-op) ----

    @Override
    public void func_184138_a(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void func_184148_a(net.minecraft.entity.player.PlayerEntity player, double x, double y,
            double z, net.minecraft.util.SoundEvent sound, net.minecraft.util.SoundCategory category,
            float volume, float pitch) {
    }

    @Override
    public void func_217384_a(net.minecraft.entity.player.PlayerEntity player,
            net.minecraft.entity.Entity entity, net.minecraft.util.SoundEvent sound,
            net.minecraft.util.SoundCategory category, float volume, float pitch) {
    }

    @Override
    public net.minecraft.entity.Entity func_73045_a(int id) {
        return null;
    }

    @Override
    public net.minecraft.world.storage.MapData func_217406_a(String id) {
        return null;
    }

    @Override
    public void func_217399_a(net.minecraft.world.storage.MapData data) {
    }

    @Override
    public int func_217395_y() {
        return 0;
    }

    @Override
    public void func_175715_c(int type, BlockPos pos, int data) {
    }

    @Override
    public void func_217378_a(net.minecraft.entity.player.PlayerEntity player, int id,
            BlockPos pos, int data) {
        // IWorld stub: block-event delivery has no listeners headlessly.
    }

    @Override
    public net.minecraft.scoreboard.Scoreboard func_96441_U() {
        return null;
    }

    @Override
    public net.minecraft.item.crafting.RecipeManager func_199532_z() {
        // Mod-owned processing tiles use World.getRecipeManager() from their own tick method.
        // The headless server already owns the real DataPackRegistries recipe manager; returning
        // null here silently makes every data-driven machine tick without a recipe match.
        return resourceServer == null ? null : resourceServer.func_199529_aN();
    }

    @Override
    public net.minecraft.tags.ITagCollectionSupplier func_205772_D() {
        // Keep the companion server tag view on the same generic resource facade.  This is the
        // server-side equivalent of the recipe seam above, not an IE-specific tag lookup.
        return resourceServer == null ? null : resourceServer.func_244266_aF();
    }

    /** LinkedHashMap with stable iteration, tiny local alias to keep imports honest. */
    private static final class LinkedHashMapSafeMap<K, V> extends java.util.LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
    }
}
