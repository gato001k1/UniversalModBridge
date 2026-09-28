package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import testfixture.StatefulEntity;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.LegacyBridgeImpl;
import dev.umb.legacy.legacyside.LegacyClientFacade;
import dev.umb.legacy.legacyside.UmbInventoryPlayer;
import dev.umb.legacy.legacyside.UmbPlayer;
import dev.umb.legacy.legacyside.UmbStub;
import dev.umb.legacy.legacyside.UmbWorld;

/**
 * Facade tests for STEP 3 - {@link UmbWorld}, {@link UmbPlayer}, {@link UmbInventoryPlayer} driven directly against a {@link FakeHostWorld}/{@link FakeHostPlayer} (no LegacyLoader, no FML boot).
 * These tests cover the wiring this class file compiles at all is...
 */
class UmbFacadeTest {

    // ---------------------------------------------------------------- fakes

    static final class FakeHostWorld implements HostWorld {
        final Map<Long, String> blockIds = new HashMap<Long, String>();
        final Map<Long, Integer> metas = new HashMap<Long, Integer>();
        final java.util.List<long[]> dirty = new java.util.ArrayList<long[]>();
        int neighborNotifications;
        final java.util.List<long[]> removed = new java.util.ArrayList<long[]>();
        long totalTime = 12345L;
        long seed = 42L;

        private static long pack(int x, int y, int z) {
            return ((long) x << 32) ^ ((long) y << 16) ^ (long) z;
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public long getTotalTime() {
            return totalTime;
        }

        @Override
        public String getBlockId(int x, int y, int z) {
            String id = blockIds.get(Long.valueOf(pack(x, y, z)));
            return id == null ? "minecraft:air" : id;
        }

        @Override
        public int getMeta(int x, int y, int z) {
            Integer m = metas.get(Long.valueOf(pack(x, y, z)));
            return m == null ? 0 : m.intValue();
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
            blockIds.put(Long.valueOf(pack(x, y, z)), legacyId);
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            blockIds.remove(Long.valueOf(pack(x, y, z)));
            metas.remove(Long.valueOf(pack(x, y, z)));
            removed.add(new long[] {x, y, z});
        }

        @Override
        public void markBlockDirty(int x, int y, int z) {
            dirty.add(new long[] {x, y, z});
        }

        @Override
        public void notifyNeighbors(int x, int y, int z) {
            neighborNotifications++;
            HostWorld.super.notifyNeighbors(x, y, z);
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delay) {
            // not asserted on in these tests
        }

        @Override
        public long randomSeed() {
            return seed;
        }

        @Override
        public void log(String msg) {
            System.out.println("[FakeHostWorld] " + msg);
        }

        // record what UmbWorld forwards, overriding the contract's
        // default no-ops so the delegation itself is what gets asserted.
        final java.util.List<String> sounds = new java.util.ArrayList<String>();
        final java.util.List<String> particles = new java.util.ArrayList<String>();
        final java.util.List<float[]> explosions = new java.util.ArrayList<float[]>();
        int redstonePower = 0;

        @Override
        public void playSound(double x, double y, double z, String name, float volume, float pitch) {
            sounds.add(name + "@" + x + "," + y + "," + z);
        }

        @Override
        public void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) {
            particles.add(name);
        }

        @Override
        public void explode(double x, double y, double z, float strength, boolean flaming, boolean breakBlocks) {
            explosions.add(new float[] {(float) x, (float) y, (float) z, strength,
                    flaming ? 1F : 0F, breakBlocks ? 1F : 0F});
        }

        @Override
        public int getRedstonePower(int x, int y, int z) {
            return redstonePower;
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        String name = "Steve";
        String identity = "player-uuid";
        boolean sneaking = false;
        double x = 1.5, y = 64.0, z = -2.5;
        float yaw = 0.0F, pitch = 0.0F;
        StackData held = StackData.EMPTY;
        String lastMessage;
        final StackData[] inventory = new StackData[36];
        int startUsingItemCalls;

        FakeHostPlayer() {
            java.util.Arrays.fill(inventory, StackData.EMPTY);
        }

        @Override
        public void startUsingItem() {
            startUsingItemCalls++;
        }

        // TICK/: recording fakes so tests can assert seed/write-back/hurt
        double motionX, motionY, motionZ;
        double[] lastSetMotion;
        String lastHurtType;
        float lastHurtAmount;

        @Override
        public double getMotionX() {
            return motionX;
        }

        @Override
        public double getMotionY() {
            return motionY;
        }

        @Override
        public double getMotionZ() {
            return motionZ;
        }

        @Override
        public void setMotion(double mx, double my, double mz) {
            lastSetMotion = new double[] {mx, my, mz};
        }

        @Override
        public void hurt(String legacyDamageType, float amount) {
            lastHurtType = legacyDamageType;
            lastHurtAmount = amount;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getIdentityKey() {
            return identity;
        }

        @Override
        public boolean isSneaking() {
            return sneaking;
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
        public float getYaw() {
            return yaw;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public StackData getHeldItem() {
            return held;
        }

        @Override
        public void setHeldItem(StackData s) {
            held = s;
        }

        @Override
        public void sendMessage(String text) {
            lastMessage = text;
        }

        @Override
        public StackData getInventorySlot(int i) {
            return inventory[i];
        }

        @Override
        public void setInventorySlot(int i, StackData s) {
            inventory[i] = s;
        }

        @Override
        public int getInventorySize() {
            return inventory.length;
        }
    }

    // ---------------------------------------------------------------- UmbWorld

    @Test
    void createSeedsTheFourM1Fields() throws Exception {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        assertFalse(world.field_72995_K, "field_72995_K (isRemote) must be seeded false");
        assertEquals(host, world.host());
        // field_73012_v (rand) and field_73011_w (provider) must be non-null - NPE insurance
        assertTrue(world.field_73012_v != null);
        assertTrue(world.field_73011_w != null);
        assertTrue(world.field_72984_F != null);
        // field_72998_d (collidingBoundingBoxes, fields.csv, PRIVATE on
        // 1.7.10 World - hence reflection) must be non-null or the inherited func_72945_a NPEs on
        // its first statement (field_72998_d.clear(), )
        java.lang.reflect.Field colliding = World.class.getDeclaredField("field_72998_d");
        colliding.setAccessible(true);
        try {
            assertNotNull(colliding.get(world));
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void forgeWorldProviderHeightMethodWorksThroughFacade() throws Exception {
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);
        Object height = world.field_73011_w.getClass().getMethod("getHeight").invoke(world.field_73011_w);
        assertEquals(Integer.valueOf(256), height,
                "Forge's WorldProvider.getHeight must be available on the facade provider");
    }

    @Test
    void blockMetadataRoundTripsThroughHostWorld() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        assertEquals(0, world.func_72805_g(1, 2, 3));
        assertTrue(world.func_72921_c(1, 2, 3, 7, 3));
        assertEquals(7, world.func_72805_g(1, 2, 3));
        assertEquals(Integer.valueOf(7), host.metas.get(Long.valueOf((1L << 32) ^ (2L << 16) ^ 3L)));
    }

    @Test
    void hostBackedCoordinatesAreAvailableToModCollisionHelpers() {
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);

        assertTrue(world.func_72899_e(0, 0, 0));
        assertTrue(world.func_72899_e(0, 255, 0));
        assertFalse(world.func_72899_e(0, -1, 0));
        assertFalse(world.func_72899_e(0, 256, 0));
    }

    @Test
    void setBlockToAirRemovesBlockAndTile() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        TileEntity te = new TileEntity();
        world.putTile(1, 2, 3, te);

        assertTrue(world.func_147468_f(1, 2, 3));
        assertEquals(1, host.removed.size());
        assertArrayEquals(new long[] {1, 2, 3}, host.removed.get(0));
        assertNull(world.getTileAt(1, 2, 3));
    }

    @Test
    void tileEntityMapIsLocalToUmbWorld() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        TileEntity te = new TileEntity();

        assertNull(world.func_147438_o(5, 5, 5));
        world.func_147455_a(5, 5, 5, te);
        assertEquals(te, world.func_147438_o(5, 5, 5));
        world.func_147475_p(5, 5, 5);
        assertNull(world.func_147438_o(5, 5, 5));
    }

    @Test
    void isAirBlockReadsTheHostBlockId() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        assertTrue(world.func_147437_c(0, 0, 0));
        host.blockIds.put(Long.valueOf(0L), "hbm:tile.machine_furnace_brick_off");
        assertFalse(world.func_147437_c(0, 0, 0));
    }

    @Test
    void markBlockForUpdateAndNeighborNotifyDelegateToHost() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        world.func_147471_g(10, 10, 10);
        assertEquals(1, host.dirty.size());

        host.dirty.clear();
        world.func_147453_f(10, 10, 10, null);
        assertEquals(1, host.neighborNotifications);
        assertEquals(6, host.dirty.size(), "func_147453_f must notify all 6 neighbors");
    }

    @Test
    void getTotalWorldTimeDelegatesToHost() {
        FakeHostWorld host = new FakeHostWorld();
        host.totalTime = 999L;
        UmbWorld world = UmbWorld.create(host, 0);

        assertEquals(999L, world.func_82737_E());
    }

    @Test
    void stubbedMembersAreCountedAndLoggedOnce() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        // func_72864_z/isBlockIndirectlyGettingPowered was upgraded from a stub to real by the
        // (see UmbWorld's javadoc on that method) - this test now exercises
        // func_72976_f/getHeightValue instead, which stays a counted stub (no HostWorld
        // terrain-height primitive).
        int before = UmbStub.hitCount("UmbWorld", "func_72976_f(getHeightValue)");
        world.func_72976_f(0, 0);
        world.func_72976_f(0, 0);
        int after = UmbStub.hitCount("UmbWorld", "func_72976_f(getHeightValue)");
        assertEquals(before + 2, after);

        Map<String, String> coverage = UmbStub.coverageReport();
        assertTrue(coverage.containsKey("UmbWorld"), coverage.toString());
        // five members upgraded from stub to real (playSoundAtEntity
        // playSoundEffect, spawnParticle, isBlockIndirectlyGettingPowered, newExplosion), so the
        // real-method count is now 31, not 25: createExplosion is also routed to the host.
        assertTrue(coverage.get("UmbWorld").contains("implemented=31"), coverage.get("UmbWorld"));
    }

    @Test
    void presentationMembersDelegateToHost() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        world.func_72908_a(1.0, 2.0, 3.0, "random.explode", 4.0F, 1.0F);
        assertEquals(1, host.sounds.size());
        assertEquals("random.explode@1.0,2.0,3.0", host.sounds.get(0));
        world.func_72908_a(0, 0, 0, null, 1.0F, 1.0F);
        assertEquals(1, host.sounds.size(), "null sound name must not cross the boundary");

        world.func_72869_a("largesmoke", 1.0, 2.0, 3.0, 0.0, 0.1, 0.0);
        assertEquals(1, host.particles.size());
        assertEquals("largesmoke", host.particles.get(0));

        world.func_72885_a(null, 5.0, 6.0, 7.0, 4.0F, true, false);
        assertEquals(1, host.explosions.size());
        float[] e = host.explosions.get(0);
        assertEquals(4.0F, e[3]);
        assertEquals(1F, e[4], "isFlaming must map to flaming");
        assertEquals(0F, e[5], "isSmoking=false must mean no terrain change");

        world.func_72876_a(null, 8.0, 9.0, 10.0, 2.0F, true);
        assertEquals(2, host.explosions.size());
        float[] created = host.explosions.get(1);
        assertEquals(2.0F, created[3]);
        assertEquals(0F, created[4], "createExplosion has no flaming flag");
        assertEquals(1F, created[5], "createExplosion smoking flag must reach block damage");

        world.func_72926_e(1000, 1, 2, 3, 0);
        world.func_72926_e(2000, 1, 2, 3, 0);
        assertEquals(2, host.sounds.size());
        assertEquals("random.click@1.0,2.0,3.0", host.sounds.get(1));
        assertEquals(2, host.particles.size());
        assertEquals("smoke", host.particles.get(1));

        host.redstonePower = 0;
        assertFalse(world.func_72864_z(0, 0, 0));
        host.redstonePower = 7;
        assertTrue(world.func_72864_z(0, 0, 0));
    }

    @Test
    void clientWorldPresentationPathsDelegateToAuthoritativeHost() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);

        binding.world.func_72908_a(11.0, 12.0, 13.0, "random.click", 0.5F, 1.2F);
        binding.world.func_72869_a("customLegacyParticle", 11.0, 12.0, 13.0,
                0.1D, 0.2D, 0.3D);
        binding.world.func_72876_a(null, 11.0, 12.0, 13.0, 3.0F, false);

        assertEquals(1, host.sounds.size());
        assertEquals(1, host.particles.size());
        assertEquals(1, host.explosions.size());
        assertEquals("customLegacyParticle", host.particles.get(0));
    }

    @Test
    void clientFacadeSeedsVanillaKeyBindings() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);

        assertNotNull(binding.gameSettings.field_74313_G);
        assertEquals(-99, binding.gameSettings.field_74313_G.func_151463_i());
        assertNotNull(binding.gameSettings.field_74351_w);
        assertEquals(17, binding.gameSettings.field_74351_w.func_151463_i());
        assertNotNull(binding.gameSettings.field_74324_K);
    }

    @Test
    void clientFacadeSeedsPlayerCapabilities() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);

        assertNotNull(binding.player.field_71075_bZ);
    }

    @Test
    void clientFacadeReusesNativeFreeEntityRendererSeed() throws Exception {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding first = LegacyClientFacade.install(null, serverWorld);
        LegacyClientFacade.Binding second = LegacyClientFacade.install(null, serverWorld);

        java.lang.reflect.Field renderer = null;
        for (java.lang.reflect.Field field : net.minecraft.client.Minecraft.class.getDeclaredFields()) {
            if (field.getType().getName().equals("net.minecraft.client.renderer.EntityRenderer")) {
                field.setAccessible(true);
                renderer = field;
                break;
            }
        }
        assertNotNull(renderer, "Minecraft must expose the grounded EntityRenderer field");
        assertSame(renderer.get(first.minecraft), renderer.get(second.minecraft));
        java.lang.reflect.Field owner = net.minecraft.client.renderer.EntityRenderer.class
                .getDeclaredField("field_78531_r");
        owner.setAccessible(true);
        assertSame(second.minecraft, owner.get(renderer.get(second.minecraft)),
                "cached EntityRenderer must be rebound to the current facade Minecraft");
    }

    @Test
    void clientHandlerMinecraftReferencesRebindAcrossNestedGraphs() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding oldBinding = LegacyClientFacade.install(null, serverWorld);
        LegacyClientFacade.Binding currentBinding = LegacyClientFacade.install(null, serverWorld);

        ClientHandlerGraph graph = new ClientHandlerGraph(oldBinding.minecraft);
        LegacyClientFacade.MinecraftReferenceScope scope =
                LegacyClientFacade.rebindMinecraftReferences(
                        java.util.Collections.singletonList(graph), currentBinding.minecraft);
        try {
            assertSame(currentBinding.minecraft, graph.minecraft);
            assertSame(currentBinding.minecraft, graph.child.minecraft);
        } finally {
            scope.restore();
        }
        assertSame(oldBinding.minecraft, graph.minecraft);
        assertSame(oldBinding.minecraft, graph.child.minecraft);
    }

    static final class ClientHandlerGraph {
        net.minecraft.client.Minecraft minecraft;
        final NestedClientHandler child;

        ClientHandlerGraph(net.minecraft.client.Minecraft minecraft) {
            this.minecraft = minecraft;
            this.child = new NestedClientHandler(minecraft);
        }
    }

    static final class NestedClientHandler {
        net.minecraft.client.Minecraft minecraft;

        NestedClientHandler(net.minecraft.client.Minecraft minecraft) {
            this.minecraft = minecraft;
        }
    }

    @Test
    void clientFacadeSeedsWorldClientMinecraftBackReference() throws Exception {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);

// Legacy compatibility behavior.
        // client packet handlers such as MuzzleFlashPacket. Unsafe allocation bypasses the
        // constructor that normally assigns it.
        java.lang.reflect.Field mc = net.minecraft.client.multiplayer.WorldClient.class
                .getDeclaredField("field_73037_M");
        mc.setAccessible(true);
        assertSame(binding.minecraft, mc.get(binding.world));
    }

    @Test
    void clientWorldScopeRestoresAuthoritativeMountedGraph() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        UmbPlayer serverPlayer = UmbPlayer.create(serverWorld, new FakeHostPlayer());
        EntityBridgeTest.TestFixtureEntity vehicle = new EntityBridgeTest.TestFixtureEntity(serverWorld);
        serverPlayer.func_70078_a(vehicle);

        LegacyClientFacade.Binding binding = LegacyClientFacade.install(serverPlayer, serverWorld);
        LegacyClientFacade.EntityWorldScope scope = LegacyClientFacade.rebindEntityWorlds(
                binding.player, binding.world, serverWorld);
        assertSame(binding.world, vehicle.field_70170_p);

        scope.restore();
        assertSame(serverWorld, vehicle.field_70170_p);
        assertSame(binding.world, binding.player.field_70170_p);
    }

    @Test
    void clientWorldScopeTemporarilyRebindsPassengerIdentity() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        UmbPlayer serverPlayer = UmbPlayer.create(serverWorld, new FakeHostPlayer());
        EntityBridgeTest.TestFixtureEntity vehicle = new EntityBridgeTest.TestFixtureEntity(serverWorld);
        serverPlayer.func_70078_a(vehicle);

        LegacyClientFacade.Binding binding = LegacyClientFacade.install(serverPlayer, serverWorld);
        assertSame(serverPlayer, vehicle.field_70153_n);
        LegacyClientFacade.EntityWorldScope scope = LegacyClientFacade.rebindEntityWorlds(
                binding.player, binding.world, serverWorld, serverPlayer, binding.player);
        try {
            assertSame(binding.player, vehicle.field_70153_n);
        } finally {
            scope.restore();
        }
        assertSame(serverPlayer, vehicle.field_70153_n);
    }

    @Test
    void clientWorldScopeRestoresModHelperCountersButKeepsPresentationState() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);
        StatefulEntity entity = new StatefulEntity(serverWorld);

        LegacyClientFacade.EntityWorldScope scope = LegacyClientFacade.rebindEntityWorlds(
                entity, binding.world, serverWorld);
        entity.helper.ammo = 879;
        entity.helper.heat = 37;
        entity.helper.countWait = 4;
        entity.helper.rotation = 12.5F;
        scope.restore();

        assertEquals(900, entity.helper.ammo);
        assertEquals(0, entity.helper.heat);
        assertEquals(0, entity.helper.countWait);
        assertEquals(12.5F, entity.helper.rotation, 0.0001F);
    }

    // ---------------------------------------------------------------- UmbPlayer / UmbInventoryPlayer

    @Test
    void playerFacadeDelegatesSneakingNameAndMessages() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        assertFalse(player.func_70093_af());
        hostPlayer.sneaking = true;
        assertTrue(player.func_70093_af());

        assertEquals("Steve", player.func_70005_c_());

        player.func_146105_b(new ChatComponentText("hello"));
        assertEquals("hello", hostPlayer.lastMessage);
        player.func_145747_a(new ChatComponentText("world"));
        assertEquals("world", hostPlayer.lastMessage);
    }

    @Test
    void playerFacadeSeedsPositionFromHost() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        assertEquals(1.5, player.field_70165_t, 0.0001);
        assertEquals(64.0, player.field_70163_u, 0.0001);
        assertEquals(-2.5, player.field_70161_v, 0.0001);

        hostPlayer.x = 100.0;
        player.refreshPosition();
        assertEquals(100.0, player.field_70165_t, 0.0001);
    }

    @Test
    void playerFacadeSeedsRotationFromHost() {
        // without rotation seeding, EntityPlayer.func_70676_i (getLook)
        // always aimed along the zero-rotation vector, so every look-dependent legacy path
        // (MC Heli vehicle placement, thrown items, bows, buckets) silently missed.
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        hostPlayer.yaw = 45.0F;
        hostPlayer.pitch = -30.0F;
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        assertEquals(45.0F, player.field_70177_z, 0.0001F);
        assertEquals(-30.0F, player.field_70125_A, 0.0001F);
        assertEquals(45.0F, player.field_70126_B, 0.0001F);
        assertEquals(-30.0F, player.field_70127_C, 0.0001F);
        assertEquals(player.field_70165_t, player.field_70169_q, 0.0001);
        assertEquals(player.field_70163_u, player.field_70167_r, 0.0001);
        assertEquals(player.field_70161_v, player.field_70166_s, 0.0001);

        hostPlayer.yaw = 0.0F;
        hostPlayer.pitch = 90.0F;
        player.refreshPosition();
        assertEquals(0.0F, player.field_70177_z, 0.0001F);
        assertEquals(90.0F, player.field_70125_A, 0.0001F);

        // straight down must look straight down (vanilla getLook math on the seeded fields)
        net.minecraft.util.Vec3 look = player.func_70676_i(1.0F);
        assertEquals(0.0, look.field_72450_a, 0.001);
        assertEquals(-1.0, look.field_72448_b, 0.001);
        assertEquals(0.0, look.field_72449_c, 0.001);
    }

    @Test
    void entityTickRefreshesStablePlayerPoseAfterHostTeleport() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        hostPlayer.x = 200.5;
        hostPlayer.y = 91.0;
        hostPlayer.z = -44.5;
        hostPlayer.yaw = 180.0F;
        hostPlayer.pitch = 25.0F;

        world.tickEntities();

        assertSame(player, UmbPlayer.create(world, hostPlayer), "teleport must not replace the facade");
        assertEquals(200.5, player.field_70165_t, 0.0001);
        assertEquals(91.0, player.field_70163_u, 0.0001);
        assertEquals(-44.5, player.field_70161_v, 0.0001);
        assertEquals(180.0F, player.field_70177_z, 0.0001F);
        assertEquals(25.0F, player.field_70125_A, 0.0001F);
    }

    @Test
    void addStatIsAHarmlessNoOp() {
        // the inherited EntityPlayerMP body dereferences theStatisticsFile
        // (field_147103_bO), which this facade never initializes - MC Heli's MCH_Achievement.addStat
        // NPE'd every vehicle spawn AFTER the entity was already created. Legacy stats stay local.
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        UmbPlayer player = UmbPlayer.create(world, new FakeHostPlayer());

        // must not throw: the override drops the stat instead of dereferencing theStatisticsFile
        player.func_71064_a(null, 1);
    }

    // universal ctor replay guard

    @Test
    void allocateSeedsEveryCtorAssignedPlayerField() throws Exception {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        UmbPlayer player = UmbPlayer.create(world, new FakeHostPlayer());

        // The two reported live crashes, pinned: DataWatcher slot reads (func_111145_d
        // is the float getter) and the stats file.
        assertEquals(20.0F, player.func_70096_w().func_111145_d(6), 0.001F);
        java.lang.reflect.Field stats = net.minecraft.entity.player.EntityPlayerMP.class
                .getDeclaredField("field_147103_bO");
        stats.setAccessible(true);
        assertNotNull(stats.get(player));
        // A zero-health facade reads as dead to every vanilla/mod isAlive check.
        assertEquals(20.0F, player.func_110138_aP(), 0.001F);
        assertTrue(player.func_110143_aJ() > 0.0F);
        assertTrue(player.func_70089_S());

        // Every reference field the real ctors assign must be non-null. The allow-list
        // below is exactly the set the ctors never assign, each with its reason — a
        // future missed seed fails here instead of NPEing live with zero context.
        Class<?>[] chain = new Class<?>[] {
                net.minecraft.entity.Entity.class,
                net.minecraft.entity.EntityLivingBase.class,
                net.minecraft.entity.player.EntityPlayer.class,
                net.minecraft.entity.player.EntityPlayerMP.class };
        for (Class<?> c : chain) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                if (f.get(player) == null && !PLAYER_ALLOW.contains(c.getSimpleName() + "." + f.getName())) {
                    fail("ctor-assigned field left null: " + c.getSimpleName() + "." + f.getName());
                }
            }
        }
    }

    /**
 * Fields the real ctors never assign : null matches a real instance
 */
    private static final java.util.Set<String> PLAYER_ALLOW =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    // Entity: mount/ridden state, set on mount, never in ctor.
                    "Entity.field_70153_n", "Entity.field_70154_o",
                    // Entity: created on first Forge NBT read, never in ctor.
                    "Entity.customEntityData",
                    // Entity: set on NBT paths, never in ctor.
                    "Entity.persistentID",
                    // EntityLivingBase: combat participants, null out of combat.
                    "EntityLivingBase.field_70717_bb", "EntityLivingBase.field_70755_b",
                    "EntityLivingBase.field_110150_bn",
                    // EntityPlayer: bed/spawn positions, never assigned by ctor.
                    "EntityPlayer.field_71081_bT", "EntityPlayer.field_71077_c",
                    "EntityPlayer.field_71073_d",
                    // EntityPlayer: item in use, null until use starts.
                    "EntityPlayer.field_71074_e",
                    // EntityPlayer: inventory/open containers — ContainerPlayer is
                    // unconstructible in this SRG runtime (its own bytecode calls a
                    // missing Slot helper -> NoSuchMethodError); nothing live opens
                    // facade containers (GUIs go through the UmbGui shim + host menus).
                    "EntityPlayer.field_71069_bz", "EntityPlayer.field_71070_bA",
                    // EntityPlayer: fish hook, null until cast.
                    "EntityPlayer.field_71104_cf",
                    // EntityPlayer: lazy, set on first getDisplayName.
                    "EntityPlayer.displayname",
                    // EntityPlayerMP: no NetHandlerPlayServer in-universe (no connection).
                    "EntityPlayerMP.field_71135_a",
                    // EntityPlayerMP: dedicated server object absent; static getServer covers it.
                    "EntityPlayerMP.field_71133_b",
                    // EntityPlayerMP: matches ctor (null until login packet; real instances identical).
                    "EntityPlayerMP.field_71143_cn"));

    @Test
    void createSeedsEveryCtorAssignedWorldField() throws Exception {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        for (java.lang.reflect.Field f
                : net.minecraft.world.World.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            if (f.get(world) == null && !WORLD_ALLOW.contains(f.getName())) {
                fail("ctor-assigned field left null: World." + f.getName());
            }
        }
    }

    /**
 * World fields no ctor assigns or no universe can provide
 */
    private static final java.util.Set<String> WORLD_ALLOW =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    // No ISaveHandler in-universe by design (chunk tickets via the
                    // getChunkSaveLocation override instead).
                    "field_73019_z"));

    @Test
    void registeredPlayerIsReturnedByClosestPlayerWithinRangeAndRefreshed() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        hostPlayer.x = 10.0;
        hostPlayer.y = 64.0;
        hostPlayer.z = 10.0;

        UmbPlayer first = UmbPlayer.create(world, hostPlayer);
        hostPlayer.x = 11.0;
        assertEquals(first, world.func_72977_a(11.0, 64.0, 10.0, 1.0));
        assertEquals(first, UmbPlayer.create(world, hostPlayer), "facade must be reused");
        assertEquals(11.0, first.field_70165_t, 0.0001);
    }

    @Test
    void oneFacadeSurvivesDistinctHostAdaptersAcrossInteractionTickAndMount() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer interactionAdapter = new FakeHostPlayer();
        interactionAdapter.name = "Pilot";
        interactionAdapter.identity = "same-uuid";
        FakeHostPlayer mountAdapter = new FakeHostPlayer();
        mountAdapter.name = "Pilot";
        mountAdapter.identity = "same-uuid";

        UmbPlayer interaction = UmbPlayer.create(world, interactionAdapter);
        EntityBridgeTest.TestFixtureEntity vehicle = new EntityBridgeTest.TestFixtureEntity(world);
        interaction.func_70078_a(vehicle);

        UmbPlayer mounted = UmbPlayer.create(world, mountAdapter);
        assertSame(interaction, mounted, "host adapters for one UUID must share one legacy facade");
        assertSame(vehicle, mounted.field_70154_o, "mount link must remain on the canonical facade");

        world.tickEntities();
        assertSame(interaction, UmbPlayer.create(world, newHostAdapter("same-uuid")));
        assertSame(interaction, vehicle.field_70153_n);
        int facades = 0;
        for (Object candidate : world.field_73010_i) {
            if (candidate == interaction) facades++;
        }
        assertEquals(1, facades, "vanilla playerEntities must contain one canonical facade");

        FakeHostPlayer differentPlayer = newHostAdapter("different-uuid");
        assertNotSame(interaction, UmbPlayer.create(world, differentPlayer),
                "display-name equality must not merge distinct host UUIDs");
    }

    private static FakeHostPlayer newHostAdapter(String identity) {
        FakeHostPlayer player = new FakeHostPlayer();
        player.identity = identity;
        player.name = "Pilot";
        return player;
    }

    @Test
    void registeredPlayerIsNotReturnedBeyondRange() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        hostPlayer.x = 10.0;
        hostPlayer.y = 64.0;
        hostPlayer.z = 10.0;

        UmbPlayer.create(world, hostPlayer);
        assertNull(world.func_72977_a(0.0, 64.0, 10.0, 9.0));
        assertTrue(world.func_72872_a(net.minecraft.entity.player.EntityPlayer.class,
                AxisAlignedBB.func_72330_a(9.5, 63.5, 9.5, 10.5, 66.0, 10.5)).size() == 1);
    }

    @Test
    void excludingEntityQueryIncludesAndCanExcludeRegisteredPlayer() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);
        AxisAlignedBB box = AxisAlignedBB.func_72330_a(1.0, 63.0, -3.0, 2.0, 66.0, -2.0);

        assertEquals(1, world.func_72839_b(null, box).size());
        assertEquals(0, world.func_72839_b(player, box).size());
    }

    @Test
    void legacySetItemInUseRequestsNativeUseStart() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        player.func_71008_a(new net.minecraft.item.ItemStack(new net.minecraft.item.Item()), 20);

        assertTrue(LegacyBridgeImpl.startHostUseIfLegacyRequested(hostPlayer, player));
        assertEquals(1, hostPlayer.startUsingItemCalls);
    }

    @Test
    void rayTraceAgainstEmptyHostStateReturnsNoHit() {
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);
        assertNull(world.func_147447_a(net.minecraft.util.Vec3.func_72443_a(0.25, 64.25, 0.25),
                net.minecraft.util.Vec3.func_72443_a(4.75, 64.25, 0.25), false, false, false));
        assertNull(world.func_72933_a(net.minecraft.util.Vec3.func_72443_a(0.25, 64.25, 0.25),
                net.minecraft.util.Vec3.func_72443_a(4.75, 64.25, 0.25)));
    }

    @Test
    void getServerForPlayerNeedsNoOverride() {
        // EntityPlayerMP.func_71121_q() is just "return (WorldServer) field_70170_p;"
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        UmbPlayer player = UmbPlayer.create(world, new FakeHostPlayer());

        assertEquals(world, player.func_71121_q());
    }

    @Test
    void heldItemIsNullWhenHostHoldsNothing() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        UmbPlayer player = UmbPlayer.create(world, new FakeHostPlayer());

        assertNull(player.func_70694_bm(), "StackData.EMPTY must convert to null, never a 0-count stack");
    }

    @Test
    void iCraftingProgressWritesLandInSyncData() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        UmbPlayer player = UmbPlayer.create(world, new FakeHostPlayer());

        player.func_71112_a(null, 0, 42);
        player.func_71112_a(null, 3, 100);
        int[] data = player.syncData();
        assertEquals(42, data[0]);
        assertEquals(100, data[3]);
    }

    @Test
    void inventoryPullPushRoundTripsEmptySlots() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        // no exception, and the inherited array is fully populated (all null for empty slots)
        player.pullInventory();
        player.pushInventory();
        for (int i = 0; i < hostPlayer.inventory.length; i++) {
            assertTrue(hostPlayer.inventory[i].isEmpty(), "slot " + i + " must round-trip as empty");
        }
    }

    @Test
    void inventoryPlayerNameDelegatesToHost() {
        FakeHostWorld hostWorld = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(hostWorld, 0);
        FakeHostPlayer hostPlayer = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, hostPlayer);

        UmbInventoryPlayer inv = player.field_71071_by instanceof UmbInventoryPlayer
                ? (UmbInventoryPlayer) player.field_71071_by : null;
        assertTrue(inv != null);
        assertEquals("Steve", inv.func_145825_b());
        assertEquals(36, inv.func_70302_i_());
    }

    // ---------------------------------------------------------------- LegacyBridgeImpl.resolveTileEntity
    //
    // Live-game bug fix: Block.createTileEntity(World,int) is FORGE's own hook (default body returns
    // null on plain vanilla Block); the vanilla factory a huge fraction of real 1.7.10 machine blocks
    // actually implement is ITileEntityProvider.func_149915_a (createNewTileEntity). These fixtures
    // do NOT need FML boot or GameData registry lookup at all - resolveTileEntity takes a Block
    // instance directly, exactly like the production call site inside LegacyBridgeImpl.createTile.

    /** Simulates the exact reported bug shape: createTileEntity returns null even though the block
     *  IS a real ITileEntityProvider (its own createNewTileEntity factory works fine). */
    static final class OnlyVanillaFactoryBlock extends Block implements ITileEntityProvider {
        boolean vanillaFactoryCalled;

        OnlyVanillaFactoryBlock() {
            super(Material.field_151576_e);
        }

        @Override
        public TileEntity createTileEntity(World world, int metadata) {
            return null;
        }

        @Override
        public TileEntity func_149915_a(World world, int meta) {
            vanillaFactoryCalled = true;
            return new TileEntity();
        }
    }

    /** Neither factory applies - not an ITileEntityProvider, and createTileEntity is not overridden. */
    static final class NoTileEntityBlock extends Block {
        NoTileEntityBlock() {
            super(Material.field_151576_e);
        }
    }

    @Test
    void resolveTileEntityFallsBackToTheVanillaFactoryWhenTheForgeHookReturnsNull() {
        OnlyVanillaFactoryBlock block = new OnlyVanillaFactoryBlock();
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);

        TileEntity te = LegacyBridgeImpl.resolveTileEntity(block, world, 0);

        assertTrue(te != null, "expected the vanilla ITileEntityProvider factory to supply a tile entity");
        assertTrue(block.vanillaFactoryCalled, "func_149915_a (createNewTileEntity) must have been tried");
    }

    @Test
    void resolveTileEntityReturnsNullWhenNeitherFactoryApplies() {
        NoTileEntityBlock block = new NoTileEntityBlock();
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);

        assertNull(LegacyBridgeImpl.resolveTileEntity(block, world, 0));
    }
}
