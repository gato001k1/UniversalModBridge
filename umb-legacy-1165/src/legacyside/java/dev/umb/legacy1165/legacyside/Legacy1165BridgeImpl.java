package dev.umb.legacy1165.legacyside;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.TileHandle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The 1.16.5 era's REAL implementation of the shared {@code dev.umb.bridge.api} contract -
 * the minimum vertical: live-universe boot, tile create/tick, block activation with a real
 * container and slots. Everything else stays an honest stub (never throws across the
 * boundary).
 *
 * <p>Metadata-free keying (lead direction, round 4): blocks are keyed by registry id; the
 * block's variant is the per-block state-list index defined by {@link UmbWorld1165}
 * ({@code meta} = index into the block's own valid-state list, Forge order). The additive
 * contract proposal stays text-only; every method here speaks the EXISTING contract.</p>
 *
 * <p>Activation runs the genuine {@code Block.use} path ({@code func_225533_a_}, number read
 * off the mod's own bytecode): the mod resolves its tile to a container provider and calls
 * the player's openMenu, which {@link UmbPlayer1165} implements locally (base is a no-op;
 * only the server player wires packets). No networking, no window.</p>
 */
public final class Legacy1165BridgeImpl implements LegacyBridge {

    private volatile boolean booted = false;
    private volatile String bootFailure = null;
    private UmbWorld1165 world;
    private UmbPlayer1165 player;
    private HostWorld host;

    @Override
    public void boot(HostWorld world) throws Exception {
        boot(world, java.util.Collections.<File>emptyList());
    }

    public void boot(HostWorld world, List<File> dataPacks) throws Exception {
        if (booted) {
            return;
        }
        this.host = world;
        try {
            // The universe is the defining loader of this object: the bridge must be
            // constructed INSIDE the isolated Legacy1165Loader (probes and tests do exactly
            // that), never on an application classloader - every vanilla/Forge reference
            // below resolves through it. Fail loudly otherwise instead of half-booting.
            ClassLoader me = Legacy1165BridgeImpl.class.getClassLoader();
            if (!me.getClass().getName()
                    .equals("dev.umb.legacy1165.boot.Legacy1165Loader")) {
                throw new IllegalStateException("Legacy1165BridgeImpl must run inside a "
                        + "Legacy1165Loader (was: " + me + ") - construct it in-universe");
            }
            String modJarsProp = System.getProperty("umb.1165.modjars");
            if (modJarsProp == null || modJarsProp.trim().isEmpty()) {
                throw new IllegalStateException(
                        "missing -Dumb.1165.modjars (semicolon-separated mod jar paths)");
            }
            List<File> modJars = new ArrayList<File>();
            for (String part : modJarsProp.split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    modJars.add(new File(t).getAbsoluteFile());
                }
            }
            File gameDir = new File(System.getProperty("umb.1165.gamedir",
                    System.getProperty("java.io.tmpdir") + "/umb-legacy1165-gamedir"));
            Legacy1165Lifecycle.Result result = Legacy1165Lifecycle.run(me, modJars, dataPacks, gameDir,
                    new java.util.function.Consumer<String>() {
                        @Override
                        public void accept(String msg) {
                            host.log(msg);
                        }
                    });
            if (!result.allOk()) {
                throw new IllegalStateException("lifecycle failed, see stages");
            }
            this.world = new UmbWorld1165(host, result.resourceServer);
            this.player = new UmbPlayer1165(this.world, new BlockPos(0, 64, 0), "umb-probe");
            this.world.bindBridgePlayer(this.player);
        } catch (Exception e) {
            bootFailure = "1.16.5 universe boot failed: " + fullChain(e);
            throw new IllegalStateException(bootFailure, e);
        }
        booted = true;
        world.log("UMB-BRIDGE-1165 live universe booted (ModLoader lifecycle, "
                + "registries frozen, facades ready)");
    }

    @Override
    public boolean isBooted() {
        return booted;
    }

    /** Live world facade - package-private for the probe. */
    UmbWorld1165 umbWorld() {
        return world;
    }

    /** Probe player - package-private for the probe. */
    UmbPlayer1165 umbPlayer() {
        return player;
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        try {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(legacyBlockId));
            if (block == null) {
                return null;
            }
            int meta = host.getMeta(x, y, z);
            BlockState state = world.stateAt(block, meta);
            if (state == null) {
                state = block.func_176223_P();
            }
            BlockPos pos = new BlockPos(x, y, z);
            // createTileEntity is a Forge extension default (IForgeBlock), not a base-Block
            // member (proven: clean-SRG Block lacks it, the mod overrides the default) -
            // hence the interface cast, which also documents the Forge seam explicitly.
            TileEntity te = ((net.minecraftforge.common.extensions.IForgeBlock) block)
                    .createTileEntity(state, world);
            if (te == null) {
                return null;
            }
            te.func_226984_a_(world, pos);
            world.putState(pos, block, state, te);
            // putState registers the same handle in the world-owned tile tick list.  Returning
            // that handle is important: host BlockEntity callbacks and the bridge scheduler must
            // observe one live TileEntity wrapper, not two independent tick owners.
            return world.tileHandle(pos);
        } catch (Throwable t) {
            System.err.println("[UMB-BRIDGE-1165] createTile(" + legacyBlockId + ") failed: " + t);
            return null;
        }
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player,
            int side, float hitX, float hitY, float hitZ) {
        try {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(legacyBlockId));
            if (block == null) {
                return ActivationResult.DECLINED;
            }
            int meta = host.getMeta(x, y, z);
            BlockState state = world.stateAt(block, meta);
            if (state == null) {
                state = block.func_176223_P();
            }
            BlockPos pos = new BlockPos(x, y, z);
            world.putState(pos, block, state, world.func_175625_s(pos));
            Direction direction = sideToDirection(side);
            BlockRayTraceResult hit = new BlockRayTraceResult(
                    new Vector3d(x + hitX, y + hitY, z + hitZ), direction, pos, false);
            ActionResultType result = block.func_225533_a_(state, world, pos, this.player,
                    Hand.MAIN_HAND, hit);
            if (result != ActionResultType.SUCCESS) {
                return ActivationResult.DECLINED;
            }
            Container container = this.player.openedContainer();
            if (container == null) {
                return ActivationResult.DECLINED;
            }
            String title = containerTitle(container, legacyBlockId);
            return new ActivationResult(true,
                    new ContainerHandle1165(container, this.player, title));
        } catch (Throwable t) {
            System.err.println("[UMB-BRIDGE-1165] activate(" + legacyBlockId + ") failed: " + t);
            return ActivationResult.DECLINED;
        }
    }

    private static String containerTitle(Container container, String legacyBlockId) {
        try {
            ContainerType<?> type = container.func_216957_a();
            ResourceLocation key = ForgeRegistries.CONTAINERS.getKey(type);
            if (key != null) {
                return key.toString();
            }
        } catch (Throwable ignored) {
            // fall through to the legacy id
        }
        return legacyBlockId;
    }

    private static Direction sideToDirection(int side) {
        Direction[] values = Direction.values();
        if (side < 0 || side >= values.length) {
            return Direction.UP;
        }
        Direction direction = values[side];
        // 1.7.10 side convention (DOWN,UP,NORTH,SOUTH,WEST,EAST) must hold for the mapping
        // above to mean anything - fail loudly here, not silently in-world.
        String[] expected = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
        if (!direction.name().equals(expected[side])) {
            throw new IllegalStateException("Direction.values() order broke the side mapping: "
                    + direction + " at " + side);
        }
        return direction;
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        // no-op stub (minimum vertical)
    }

    @Override
    public void tickTile(TileHandle t) {
        if (t instanceof TileHandle1165) {
            ((TileHandle1165) t).tick();
        }
    }

    @Override
    public void shutdown() {
        booted = false;
    }

    @Override
    public void tickEntities() {
        if (world != null) {
            world.tickEntities();
        }
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        // no-op stub (minimum vertical)
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        // no-op stub (minimum vertical)
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z,
            String neighborLegacyBlockId) {
        // no-op stub (minimum vertical)
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        // no-op stub (minimum vertical)
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        return true;
    }

    @Override
    public dev.umb.bridge.api.StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        return null;
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z,
            int side, float hitX, float hitY, float hitZ) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(legacyItemId));
            if (item == null || player == null) {
                return ItemUseResult.DECLINED;
            }
            this.player.syncFrom(player);
            ItemStack stack = this.player.func_184586_b(Hand.MAIN_HAND);
            if (stack == null || stack.func_190926_b()) {
                return ItemUseResult.DECLINED;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockRayTraceResult hit = new BlockRayTraceResult(
                    new Vector3d(x + hitX, y + hitY, z + hitZ), sideToDirection(side), pos, false);
            ItemUseContext context = new ItemUseContext(this.player, Hand.MAIN_HAND, hit);
            // SpawnEggItem.func_195939_a is deliberately server-gated in 1.16.5: when the
            // supplied world is not a ServerWorld it returns SUCCESS without ever calling
            // EntityType.func_220331_a, which leaves a World facade's add-entity seam unused.
            // Preserve the vanilla egg/type path generically, then hand the real legacy
            // entity to UmbWorld1165.func_217376_c for host twin/render/tick registration.
            if (item instanceof SpawnEggItem
                    && spawnEggThroughFacade((SpawnEggItem) item, stack, pos, sideToDirection(side))) {
                return new ItemUseResult(true,
                        UmbItemConv1165.toStackData(this.player.func_184586_b(Hand.MAIN_HAND)));
            }
            // Forge's real block-use dispatch gives items an onItemUseFirst hook before
            // Item.useOn (func_195939_a). IE's HammerItem performs multiblock formation in
            // that hook; calling only useOn makes every such item silently return PASS. Keep
            // this reflective so the bridge remains compatible with the SRG vanilla surface,
            // while allowing each mod's own item implementation to run unchanged.
            ActionResultType result = invokeItemUseFirst(item, stack, context);
            if (result == ActionResultType.PASS) {
                result = item.func_195939_a(context);
            }
            boolean handled = result == ActionResultType.SUCCESS || result == ActionResultType.CONSUME;
            return new ItemUseResult(handled,
                    UmbItemConv1165.toStackData(this.player.func_184586_b(Hand.MAIN_HAND)));
        } catch (Throwable t) {
            Throwable detail = t.getCause() == null ? t : t.getCause();
            System.err.println("[UMB-BRIDGE-1165] useItemOnBlock(" + legacyItemId
                    + ") failed: " + fullChain(detail));
            return ItemUseResult.DECLINED;
        }
    }

    private boolean spawnEggThroughFacade(SpawnEggItem egg, ItemStack stack, BlockPos clicked,
            Direction face) {
        BlockState state = this.world.func_180495_p(clicked);
        BlockPos spawnPos = state.func_196952_d(this.world, clicked).func_197766_b()
                ? clicked : clicked.func_177972_a(face);
        EntityType<?> type = egg.func_208076_b(stack.func_77978_p());
        if (type == null) return false;
        Entity entity = type.func_200721_a(this.world);
        if (entity == null) return false;
        entity.func_70012_b(spawnPos.func_177958_n() + 0.5D, spawnPos.func_177956_o(),
                spawnPos.func_177952_p() + 0.5D,
                this.world.func_201674_k().nextFloat() * 360.0F, 0.0F);
        boolean added = this.world.func_217376_c(entity);
        if (host != null) {
            host.log("ENTITY-DIAG 1165 spawn egg type=" + type.func_220348_g()
                    + " added=" + added + " pos=" + entity.func_226277_ct_() + ","
                    + entity.func_226278_cu_() + "," + entity.func_226279_cv_());
        }
        return added;
    }

    private static ActionResultType invokeItemUseFirst(Item item, ItemStack stack, ItemUseContext context)
            throws Exception {
        try {
            Method hook = item.getClass().getMethod("onItemUseFirst", ItemStack.class, ItemUseContext.class);
            Object value = hook.invoke(item, stack, context);
            return value instanceof ActionResultType ? (ActionResultType) value : ActionResultType.PASS;
        } catch (NoSuchMethodException absent) {
            return ActionResultType.PASS;
        }
    }

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        // honest stub: entity twins are a later lane (same as before).
        return null;
    }

    public String bootFailure() {
        return bootFailure;
    }

    /**
     * Full failure chain for reports: every cause + suppressed (cycle-guarded), with stack
     * frames filtered to the packages that matter - and static-initializer frames NEVER
     * filtered, because an ExceptionInInitializerError is only diagnosable by the
     * {@code <clinit>} class that failed. (Round-5 live failure recorded exactly one level
     * and was undiagnosable; this exists so the next one is.)
     */
    static String fullChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        appendChain(sb, t, seen, "");
        return sb.toString();
    }

    private static void appendChain(StringBuilder sb, Throwable t,
            java.util.Set<Throwable> seen, String prefix) {
        if (t == null || !seen.add(t)) {
            return;
        }
        sb.append(prefix).append(t.getClass().getName()).append(": ").append(t.getMessage())
                .append('\n');
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 30); i++) {
            String line = st[i].toString();
            if (line.contains("<clinit>") || line.contains("dev.umb")
                    || line.contains("net.minecraftforge") || line.contains("net.minecraft")
                    || line.contains("com.progwml6") || line.contains("cpw.mods")) {
                sb.append(prefix).append("  at ").append(line).append('\n');
            }
        }
        for (Throwable s : t.getSuppressed()) {
            sb.append(prefix).append("  suppressed:\n");
            appendChain(sb, s, seen, prefix + "    ");
        }
        appendChain(sb, t.getCause(), seen, prefix);
    }

    // ---- legacy surface still covered by bridge-api defaults (item tooltips, block drops,
    // step/fall/animate, comparators, players sync): inherited, era-agnostic. ----
}
