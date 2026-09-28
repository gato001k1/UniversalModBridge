package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.registry.EntityRegistry;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.command.IEntitySelector;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostEntity;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostWorld;
import dev.umb.legacy.legacyside.EntityHandleImpl;
import dev.umb.legacy.legacyside.UmbWorld;

/**
 * ENTITY-BRIDGE: headless tests for the legacy side (no LegacyLoader/FML boot needed - same
 * discipline {@link UmbFacadeTest} already established for the World/Player facades). Uses a
 * synthetic {@link TestFixtureEntity} (this test package, never a mod class name) as the "legacy
 * Entity" a mod would otherwise construct, and registers it with plain vanilla/Forge
 * {@code EntityList} calls (the SAME generic mechanism {@code cpw.mods.fml.common.registry.
 * EntityRegistry.registerModEntity} uses under the hood, per that class's own real source) rather
 * than any mod-specific registration path.
 */
class EntityBridgeTest {

    private static boolean fixtureRegistered;

    private static synchronized void ensureFixtureRegistered() {
        if (fixtureRegistered) return;
        int freeId = EntityRegistry.findGlobalUniqueEntityId();
        EntityList.func_75618_a(TestFixtureEntity.class, "umbtest.entity_bridge_fixture", freeId);
        fixtureRegistered = true;
    }

    /**
     * A synthetic legacy Entity. {@code func_70071_h_} (onUpdate) does a tiny, deterministic,
     * REAL computation (motion integration) so tests can prove {@link EntityHandle#tick()}
     * actually executed genuine legacy code, not a fake. The three overrides below are Entity's
     */
    public static final class TestFixtureEntity extends Entity {
        int tickCalls;
        boolean throwOnTick;

        // PUBLIC: EntityList.createEntityFromNBT (func_75615_a) resolves a (World) constructor
        // reflectively - the same requirement vanilla's own chunk loader places on every mod
        // entity class - see legacyEntityIdAndSaveNbtBothResolveOnceGenericallyRegistered.
        public TestFixtureEntity(World world) {
            super(world);
        }

        @Override
        protected void func_70088_a() {
            // entityInit - no synced data needed for this fixture
        }

        @Override
        protected void func_70037_a(NBTTagCompound tag) {
            tickCalls = tag.func_74762_e("tickCalls");
        }

        @Override
        protected void func_70014_b(NBTTagCompound tag) {
            tag.func_74768_a("tickCalls", tickCalls);
        }

        @Override
        public void func_70071_h_() {
            if (throwOnTick) {
                throw new RuntimeException("synthetic tick failure");
            }
            tickCalls++;
            field_70165_t += field_70159_w;
            field_70163_u += field_70181_x;
            field_70161_v += field_70179_y;
        }
    }

    static final class FakeHostLevel implements HostWorld, HostLevel {
        boolean declineSpawn;
        EntityHandle lastSpawned;
        int spawnCallCount;
        final List<HostEntity> nativeEntities = new ArrayList<HostEntity>();

        @Override
        public boolean spawnEntity(EntityHandle handle) {
            spawnCallCount++;
            lastSpawned = handle;
            return !declineSpawn;
        }

        // ---- HostWorld: not exercised by these tests, minimal honest stand-ins ----
        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public long getTotalTime() {
            return 0L;
        }

        @Override
        public String getBlockId(int x, int y, int z) {
            return "minecraft:air";
        }

        @Override
        public int getMeta(int x, int y, int z) {
            return 0;
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
        }

        @Override
        public void removeBlock(int x, int y, int z) {
        }

        @Override
        public void markBlockDirty(int x, int y, int z) {
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delay) {
        }

        @Override
        public long randomSeed() {
            return 0L;
        }

        @Override
        public void log(String msg) {
            System.out.println("[FakeHostLevel] " + msg);
        }

        @Override
        public List<HostEntity> getEntities(double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ,
                                            String excludedIdentity) {
            return new ArrayList<HostEntity>(nativeEntities);
        }
    }

    static final class FakeHostEntity implements HostEntity {
        final String identity;
        double minX = -0.4D, minY = 0.0D, minZ = -0.4D;
        double maxX = 0.4D, maxY = 1.8D, maxZ = 0.4D;
        float health = 20.0F;
        float maxHealth = 20.0F;
        boolean dead;
        String lastDamageType;

        FakeHostEntity(String identity) {
            this.identity = identity;
        }

        @Override public String getIdentityKey() { return identity; }
        @Override public double getMinX() { return minX; }
        @Override public double getMinY() { return minY; }
        @Override public double getMinZ() { return minZ; }
        @Override public double getMaxX() { return maxX; }
        @Override public double getMaxY() { return maxY; }
        @Override public double getMaxZ() { return maxZ; }
        @Override public boolean isLiving() { return true; }
        @Override public float getHealth() { return health; }
        @Override public float getMaxHealth() { return maxHealth; }

        @Override
        public boolean hurt(String legacyDamageType, float amount) {
            lastDamageType = legacyDamageType;
            if (dead || amount <= 0.0F) return false;
            health = Math.max(0.0F, health - amount);
            if (health == 0.0F) dead = true;
            return true;
        }

        @Override public void setDead() { dead = true; health = 0.0F; }
        @Override public void setHealth(float value) { health = value; }
    }

    private static NBTTagCompound fromBytes(byte[] bytes) throws Exception {
        return CompressedStreamTools.func_152457_a(bytes, NBTSizeTracker.field_152451_a);
    }

    // ---------------------------------------------------------------- EntityHandleImpl

    @Test
    void tickRunsTheRealLegacyOnUpdate() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        e.field_70159_w = 2.0; // motionX
        EntityHandleImpl handle = new EntityHandleImpl(e);

        assertEquals(0, e.tickCalls);
        handle.tick();
        assertEquals(1, e.tickCalls, "tick() must run the real legacy onUpdate, not a stub");
        assertEquals(2.0, handle.getX(), 0.0001, "position must reflect the real motion integration onUpdate performed");
    }

    @Test
    void syntheticWorldTrackerHasTheMapNeededByProjectileTicks() throws Exception {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        Object tracker = world.func_73039_n();
        assertNotNull(tracker, "the legacy world must expose its tracker facade");

        java.lang.reflect.Field trackedIds = net.minecraft.entity.EntityTracker.class
                .getDeclaredField("field_72794_c");
        trackedIds.setAccessible(true);
        assertNotNull(trackedIds.get(tracker),
                "Unsafe tracker allocation must replay EntityTracker's IntHashMap constructor state");
    }

    @Test
    void tickPoisonsOnceOnThrowAndNeverPropagates() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        e.throwOnTick = true;
        EntityHandleImpl handle = new EntityHandleImpl(e);

        handle.tick(); // must not throw across the boundary
        assertNotNull(handle.poisonReason());
        assertFalse(handle.isValid(), "a poisoned handle is never valid, even though the legacy entity is not field_70128_L-dead");

        int before = e.tickCalls;
        handle.tick(); // poisoned - must stay a no-op, not throw again
        assertEquals(before, e.tickCalls);
    }

    @Test
    void isValidReflectsLegacyDeathViaSetDead() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        EntityHandleImpl handle = new EntityHandleImpl(e);

        assertTrue(handle.isValid());
        e.func_70106_y(); // setDead
        assertFalse(handle.isValid());
    }

    @Test
    void hostRemovedCallsSetDeadAndIsIdempotent() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        EntityHandleImpl handle = new EntityHandleImpl(e);

        assertFalse(e.field_70128_L);
        handle.hostRemoved();
        assertTrue(e.field_70128_L, "hostRemoved must call the real setDead (func_70106_y)");
        handle.hostRemoved(); // idempotent, must not throw
    }

    @Test
    void legacyEntityIdFallsBackToUnknownWhenNotRegistered() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        // A class private to THIS test method, deliberately never registered with EntityList -
        // EntityList's class->name map is process-global, so reusing TestFixtureEntity here would
        // make this test's outcome depend on whether the registration test already ran first.
        class NeverRegisteredEntity extends Entity {
            NeverRegisteredEntity(World w) {
                super(w);
            }

            @Override
            protected void func_70088_a() {
            }

            @Override
            protected void func_70037_a(NBTTagCompound tag) {
            }

            @Override
            protected void func_70014_b(NBTTagCompound tag) {
            }
        }
        EntityHandleImpl handle = new EntityHandleImpl(new NeverRegisteredEntity(world));

        assertEquals("unknown", handle.legacyEntityId());
    }

    @Test
    void legacyEntityIdAndSaveNbtBothResolveOnceGenericallyRegistered() throws Exception {
        // The SAME generic vanilla/Forge mechanism cpw.mods.fml.common.registry.EntityRegistry
        // .registerModEntity uses under the hood (EntityList.classToStringMapping/
        // stringToClassMapping) - no mod-specific registration path involved.
        // findGlobalUniqueEntityId is FML's own real allocator (EntityRegistry.java) - avoids a
        // hardcoded id guess colliding with whatever vanilla/HBM already claimed.
        ensureFixtureRegistered();
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        EntityHandleImpl handle = new EntityHandleImpl(e);

        assertEquals("umbtest.entity_bridge_fixture", handle.legacyEntityId());

        byte[] blob = handle.saveNbt();
        assertNotNull(blob, "writeToNBTOptional must succeed once the entity has a registered name");
        NBTTagCompound tag = fromBytes(blob);
        assertEquals("umbtest.entity_bridge_fixture", tag.func_74779_i("id"));

        // THE mechanism LegacyBridgeImpl.restoreEntity depends on: EntityList.createEntityFromNBT
        // reconstructs a NEW instance of the RIGHT class, generically, from the blob alone.
        // Needs a PUBLIC (World) constructor - createEntityFromNBT resolves it reflectively,
        // exactly like vanilla's own chunk loader does for every entity in every chunk.
        Entity restored = EntityList.func_75615_a(tag, world);
        assertNotNull(restored, "EntityList.createEntityFromNBT must reconstruct the entity generically from its own \"id\" tag");
        assertTrue(restored instanceof TestFixtureEntity, "must reconstruct the exact same registered class");
        assertTrue(restored != e, "must be a NEW instance, not the original");
    }

    @Test
    void restoringSameUuidReusesTheExistingLoadedEntityHandle() throws Exception {
        ensureFixtureRegistered();
        UmbFacadeTest.FakeHostWorld host = new UmbFacadeTest.FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        TestFixtureEntity original = new TestFixtureEntity(world);
        original.tickCalls = 41;
        EntityHandleImpl originalHandle = new EntityHandleImpl(original);
        byte[] blob = originalHandle.saveNbt();
        assertNotNull(blob);

        FakeHostLevel hostLevel = new FakeHostLevel();
        world = UmbWorld.create(hostLevel, 0);
        NBTTagCompound tag = fromBytes(blob);
        Entity firstEntity = EntityList.func_75615_a(tag, world);
        assertNotNull(firstEntity);
        assertTrue(world.func_72838_d(firstEntity));
        EntityHandle first = hostLevel.lastSpawned;

        Entity restored = EntityList.func_75615_a(tag, world);
        assertNotNull(restored);
        EntityHandleImpl reused = world.trackEntityOrExisting(restored);

        assertSame(first, reused,
                "a chunk reload with the same persisted UUID must not add a second legacy handle");
        assertEquals(1, world.func_72872_a(TestFixtureEntity.class,
                AxisAlignedBB.func_72330_a(-1000, -1000, -1000, 1000, 1000, 1000)).size());
    }

    @Test
    void saveNbtReturnsNullForAnUnnamedEntity() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        // a distinct, never-registered class so this test does not depend on the registration
        // test above having run first
        class UnnamedFixtureEntity extends Entity {
            UnnamedFixtureEntity(World w) {
                super(w);
            }

            @Override
            protected void func_70088_a() {
            }

            @Override
            protected void func_70037_a(NBTTagCompound tag) {
            }

            @Override
            protected void func_70014_b(NBTTagCompound tag) {
            }
        }
        EntityHandleImpl handle = new EntityHandleImpl(new UnnamedFixtureEntity(world));
        assertNull(handle.saveNbt(), "vanilla's own writeToNBTOptional declines to save an unnamed entity - honest, not a bug");
    }

    // ------------------------------------------------------- EntityHandleImpl.getCollisionBoxes

    @Test
    void collisionBoxesAreNullForAPartlessEntity() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        EntityHandleImpl handle = new EntityHandleImpl(new TestFixtureEntity(world));

        assertNull(handle.getCollisionBoxes(),
                "no parts and no helper volumes is an honest none-known, never an empty guess");
    }

    @Test
    void collisionBoxesCollectVanillaParts() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity part = new TestFixtureEntity(world);
        // field_70121_D is final in the runtime jar, so mutate its bounds in place
        // (AxisAlignedBB.func_72324_b / setBounds) instead of assigning the field.
        part.field_70121_D.func_72324_b(100, 64, 100, 104, 66, 108);
        EntityHandleImpl handle = new EntityHandleImpl(
                new testfixture.MultipartFixture.PartedFixture(world, new Entity[] {part}));

        List<double[]> boxes = handle.getCollisionBoxes();

        assertNotNull(boxes);
        assertEquals(1, boxes.size());
        assertArrayEquals(new double[] {100, 64, 100, 104, 66, 108}, boxes.get(0), 0.0001);
    }

    @Test
    void collisionBoxesCollectHelperOwnedVolumes() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        testfixture.MultipartFixture.HelpedFixture e =
                new testfixture.MultipartFixture.HelpedFixture(world);
        e.helper.volume = AxisAlignedBB.func_72330_a(0, 0, 0, 16, 4, 16);
        EntityHandleImpl handle = new EntityHandleImpl(e);

        List<double[]> boxes = handle.getCollisionBoxes();

        assertNotNull(boxes);
        assertEquals(1, boxes.size());
        assertArrayEquals(new double[] {0, 0, 0, 16, 4, 16}, boxes.get(0), 0.0001);
    }

    // ---------------------------------------------------------------- UmbWorld.func_72838_d (spawnEntityInWorld)

    @Test
    void spawnEntityInWorldTracksLocallyAndReturnsTrueWithNoHostLevel() {
        UmbFacadeTest.FakeHostWorld host = new UmbFacadeTest.FakeHostWorld(); // does NOT implement HostLevel
        UmbWorld world = UmbWorld.create(host, 0);
        TestFixtureEntity e = new TestFixtureEntity(world);

        assertTrue(world.func_72838_d(e), "matches 1.7.10 semantics: the entity exists either way");

        List<?> found = world.func_72872_a(TestFixtureEntity.class, AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 1, 1));
        assertEquals(1, found.size(), "an orphaned (host-invisible) entity must still be tracked locally");
    }

    @Test
    void spawnEntityInWorldCallsHostLevelWithAWorkingHandle() {
        FakeHostLevel host = new FakeHostLevel();
        UmbWorld world = UmbWorld.create(host, 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        e.field_70165_t = 5.0;

        assertTrue(world.func_72838_d(e));
        assertEquals(1, host.spawnCallCount);
        assertNotNull(host.lastSpawned);
        assertEquals(5.0, host.lastSpawned.getX(), 0.0001, "the handle handed to HostLevel must be backed by the REAL entity");
    }

    @Test
    void spawnEntityInWorldStillTracksLocallyWhenHostDeclines() {
        FakeHostLevel host = new FakeHostLevel();
        host.declineSpawn = true;
        UmbWorld world = UmbWorld.create(host, 0);
        TestFixtureEntity e = new TestFixtureEntity(world);

        assertTrue(world.func_72838_d(e), "a host decline is not a legacy-side failure");
        assertEquals(1, host.spawnCallCount);
        List<?> found = world.func_72839_b(null, AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 1, 1));
        assertEquals(1, found.size(), "declined-but-real entity must still be queryable locally");
    }

    @Test
    void spawnEntityInWorldReturnsFalseForNull() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        assertFalse(world.func_72838_d(null));
    }

    // ------------------------------------------------- EntityJoinWorldEvent (multi-seat children)

    @Test
    void joinCancellationFollowsTheVanillaAddPathRule() {
        // the entity out of the world, UNLESS it is force-spawned (field_98038_p). The bus
        // delivery itself cannot be subscribed headless - EventBus.register needs a booted
        // Loader with a LaunchClassLoader (verified empirically) - so subscriber delivery is
        // proven by bytecode (MCH_EventHook.entitySpawn -> createSeats) plus the live check,
        // while the rule every delivery feeds through is pinned here.
        assertTrue(UmbWorld.joinCancelledByPost(true, false));
        assertFalse(UmbWorld.joinCancelledByPost(true, true));
        assertFalse(UmbWorld.joinCancelledByPost(false, false));
        assertFalse(UmbWorld.joinCancelledByPost(false, true));
    }

    @Test
    void spawnEntityInWorldSucceedsWithNoJoinSubscribers() {
        // Exercises the real post path (LegacyEventPoster -> EVENT_BUS.post with zero
        // subscribers, which returns false without touching the loader): the spawn must be
        // unaffected. Every other spawn test in this class now also runs through the post.
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);

        assertTrue(world.func_72838_d(e));
        assertEquals(1, world.func_72872_a(TestFixtureEntity.class,
                AxisAlignedBB.func_72330_a(-1000, -1000, -1000, 1000, 1000, 1000)).size());
    }

    // ------------------------------------------------- tick-start render baselines (smoothness)

    @Test
    void tickSnapshotsRenderBaselinesAtTickStart() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity e = new TestFixtureEntity(world);
        e.func_70012_b(5.0D, 6.0D, 7.0D, 30.0F, 10.0F); // setPositionAndAngles (public)
        // Poison the baselines so the test proves the tick writes them, not the setup above.
        e.field_70142_S = 0.0D;
        e.field_70137_T = 0.0D;
        e.field_70136_U = 0.0D;
        e.field_70126_B = 0.0F;
        e.field_70127_C = 0.0F;
        assertTrue(world.func_72838_d(e));

        world.tickEntities();

        assertEquals(5.0D, e.field_70142_S, 0.0001D, "lastTickPosX must hold the pre-tick position");
        assertEquals(6.0D, e.field_70137_T, 0.0001D, "lastTickPosY must hold the pre-tick position");
        assertEquals(7.0D, e.field_70136_U, 0.0001D, "lastTickPosZ must hold the pre-tick position");
        assertEquals(30.0F, e.field_70126_B, 0.0001F, "prevRotationYaw must hold the pre-tick yaw");
        assertEquals(10.0F, e.field_70127_C, 0.0001F, "prevRotationPitch must hold the pre-tick pitch");
    }

    @Test
    void tickRestoresPrevRotationOverwrittenMidTick() {
        // Models a vehicle mod that calls the vanilla super late in its own onUpdate (MCHeli
        // runs half its tick before Entity.func_70071_h_): the vanilla prev-rotation snapshot
        // then lands mid-tick instead of at tick start, freezing every
        // prev/current/partialTick render interpolation built on it.
        class MidTickClobberFixture extends Entity {
            MidTickClobberFixture(World w) {
                super(w);
            }

            @Override
            protected void func_70088_a() {
            }

            @Override
            protected void func_70037_a(NBTTagCompound tag) {
            }

            @Override
            protected void func_70014_b(NBTTagCompound tag) {
            }

            @Override
            public void func_70071_h_() {
                field_70126_B = 999.0F;
                field_70127_C = 999.0F;
            }
        }
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        MidTickClobberFixture e = new MidTickClobberFixture(world);
        e.func_70012_b(0.0D, 0.0D, 0.0D, 30.0F, 10.0F); // setPositionAndAngles (public)
        assertTrue(world.func_72838_d(e));

        world.tickEntities();

        assertEquals(30.0F, e.field_70126_B, 0.0001F,
                "the render must sweep the full tick delta, not the mid-tick clobber");
        assertEquals(10.0F, e.field_70127_C, 0.0001F,
                "the render must sweep the full tick delta, not the mid-tick clobber");
    }

    // ---------------------------------------------------------------- getEntitiesWithinAABB[Excluding]

    @Test
    void getEntitiesWithinAABBFiltersByClassAndPosition() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity near = new TestFixtureEntity(world);
        near.func_70012_b(0, 0, 0, 0, 0); // setLocationAndAngles
        TestFixtureEntity far = new TestFixtureEntity(world);
        far.func_70012_b(500, 500, 500, 0, 0);
        world.func_72838_d(near);
        world.func_72838_d(far);

        List<?> inBox = world.func_72872_a(TestFixtureEntity.class, AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 1, 1));
        assertEquals(1, inBox.size());
        assertSame(near, inBox.get(0));

        List<?> wrongClass = world.func_72872_a(String.class, AxisAlignedBB.func_72330_a(-1000, -1000, -1000, 1000, 1000, 1000));
        assertEquals(0, wrongClass.size());
    }

    @Test
    void getEntitiesWithinAABBExcludingEntitySkipsThePassedEntity() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity a = new TestFixtureEntity(world);
        TestFixtureEntity b = new TestFixtureEntity(world);
        world.func_72838_d(a);
        world.func_72838_d(b);

        AxisAlignedBB big = AxisAlignedBB.func_72330_a(-1000, -1000, -1000, 1000, 1000, 1000);
        List<?> excludingA = world.func_72839_b(a, big);
        assertEquals(1, excludingA.size());
        assertSame(b, excludingA.get(0));
    }

    @Test
    void getEntitiesWithinAABBPrunesDeadEntities() {
        UmbWorld world = UmbWorld.create(new UmbFacadeTest.FakeHostWorld(), 0);
        TestFixtureEntity alive = new TestFixtureEntity(world);
        TestFixtureEntity dead = new TestFixtureEntity(world);
        world.func_72838_d(alive);
        world.func_72838_d(dead);
        dead.func_70106_y();

        AxisAlignedBB big = AxisAlignedBB.func_72330_a(-1000, -1000, -1000, 1000, 1000, 1000);
        List<?> result = world.func_72872_a(TestFixtureEntity.class, big);
        assertEquals(1, result.size());
        assertSame(alive, result.get(0));
    }

    @Test
    void getEntitiesWithinAABBIncludesNativeFacadeAndForwardsDamageAndDeath() {
        FakeHostLevel host = new FakeHostLevel();
        FakeHostEntity zombie = new FakeHostEntity("native-zombie");
        host.nativeEntities.add(zombie);
        UmbWorld world = UmbWorld.create(host, 0);

        AxisAlignedBB box = AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 3, 1);
        List<?> found = world.func_72839_b(null, box);
        assertEquals(1, found.size());
        assertTrue(found.get(0) instanceof EntityLivingBase);
        EntityLivingBase facade = (EntityLivingBase) found.get(0);
        assertEquals(20.0F, facade.func_110143_aJ(), 0.001F);
        assertEquals(1, world.func_82733_a(EntityLivingBase.class, box,
                new IEntitySelector() {
                    @Override public boolean func_82704_a(Entity entity) { return true; }
                }).size());
        assertEquals(0, world.func_82733_a(EntityLivingBase.class, box,
                new IEntitySelector() {
                    @Override public boolean func_82704_a(Entity entity) { return false; }
                }).size());
        assertTrue(facade.func_70097_a(new DamageSource("generic"), 7.0F));
        assertEquals(13.0F, zombie.health, 0.001F);
        assertEquals("generic", zombie.lastDamageType);

        facade.func_70106_y();
        assertTrue(zombie.dead, "legacy setDead must discard the native entity");
    }

    @Test
    void nativeFacadeHealthIsLiveForCustomDamageProcessors() {
        FakeHostLevel host = new FakeHostLevel();
        FakeHostEntity zombie = new FakeHostEntity("native-zombie-live-health");
        host.nativeEntities.add(zombie);
        UmbWorld world = UmbWorld.create(host, 0);
        EntityLivingBase facade = (EntityLivingBase) world.func_72839_b(null,
                AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 3, 1)).get(0);

        facade.func_70606_j(6.0F);
        assertEquals(6.0F, zombie.health, 0.001F,
                "custom HBM damage processors must write through to native health");
        assertTrue(facade.func_70089_S());
        facade.func_70606_j(0.0F);
        assertFalse(facade.func_70089_S());
    }

    /**
     * (directPassenger in the interact diagnostic) holding a
     * dev.umb.legacy.legacyside.UmbHostEntity - the native-query facade - after the pilot boarded.
     * Root cause: MCHeli's own MCH_EntityAircraft.mountMobToSeats (real vanilla-style auto-crew
     * feature, runs once server-side right after the pilot mounts) scans a small AABB around the
     * aircraft for stray EntityLivingBase "mobs" to auto-mount into empty seats via
     * candidate.func_70078_a(seat) - vanilla mountEntity, called ON the candidate. Before this fix,
     * a UmbLegacyPartTwin collider (the seat's OWN extra-hitbox collider; see
     * HostWorldImpl.getEntities' now-fixed filter) leaked into that scan as a plain UmbHostEntity
     * and got auto-mounted into its own seat.
     *
     * This defense-in-depth layer closes the same bug from the facade's own side: whatever finds
     * a UmbHostEntity via ANY AABB path and tries to mount it (func_70078_a, vanilla's real
     * mountEntity, called on the rider) must be refused - a fresh per-query facade is never "a
     * host passenger really riding its twin" (its own class javadoc), so it must never end up
     * wired into a legacy entity's riding graph on either side.
     */
    @Test
    void nativeFacadeRefusesToBecomeARider() {
        FakeHostLevel host = new FakeHostLevel();
        FakeHostEntity strayCollider = new FakeHostEntity("stray-part-twin");
        host.nativeEntities.add(strayCollider);
        UmbWorld world = UmbWorld.create(host, 0);
        EntityLivingBase facade = (EntityLivingBase) world.func_72839_b(null,
                AxisAlignedBB.func_72330_a(-1, -1, -1, 1, 3, 1)).get(0);
        TestFixtureEntity seat = new TestFixtureEntity(world);

        facade.func_70078_a(seat);

        assertNull(seat.field_70153_n,
                "a query facade must never become a legacy entity's rider, no matter who tries to mount it");
        assertNull(facade.field_70154_o,
                "a refused mount must not wire up the facade's own riding field either");
    }
}
