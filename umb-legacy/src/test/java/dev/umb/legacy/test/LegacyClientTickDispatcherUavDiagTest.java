package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import dev.umb.legacy.legacyside.LegacyClientTickDispatcher;
import dev.umb.legacy.legacyside.input.LegacyInputDiag;

/**
 * Focused tests for {@link LegacyClientTickDispatcher#logUavCameraDiagnostic}, the bounded
 * diagnostic added for the UAV camera/HUD investigation
 * that logs... the local rider's ridingEntity class, station.getControlAircract() (by name/type,
 * reflective) and... getAircraft_RiddenOrControl result").
 *
 * <p>mcheli is never on this test's compile/runtime classpath (same discipline {@link
 * VehicleProbe} documents), so every case here exercises the "degrades honestly" contract - no
 * rider, a non-station rider, and a station-shaped rider built from a local fixture - never the
 * "mcheli actually present" branch, which only a real staged jar can prove. What these tests DO
 * prove, headlessly: the reflective lookups target the right field/method names, a rider that
 * merely happens to expose a same-named method is read correctly regardless of its real type (the
 * production code cannot import mcheli classes), a throwing reflective call degrades to a
 * descriptive string instead of losing the whole log line, and the oncePer bound is honored.</p>
 */
class LegacyClientTickDispatcherUavDiagTest {

    private final List<String> logged = new ArrayList<String>();

    @AfterEach
    void clear() {
        LegacyInputDiag.clearForTest();
    }

    private void captureLogs() {
        LegacyInputDiag.clearForTest();
        LegacyInputDiag.setSink(new LegacyInputDiag.Sink() {
            @Override
            public void log(String message) {
                logged.add(message);
            }
        });
    }

    @Test
    void degradesHonestlyWithNoRider() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        minecraft.field_71439_g = allocate(EntityClientPlayerMP.class);
        // field_70154_o stays at its Unsafe-allocation default (null): no rider.

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        assertEquals(1, logged.size(), "exactly one bounded diagnostic line: " + logged);
        String line = logged.get(0);
        assertTrue(line.contains("riding=none"), line);
        assertTrue(line.contains("controlAircraft=n/a"), line);
        assertTrue(line.contains("aircraftRiddenOrControl=mcheli-absent"), line);
    }

    @Test
    void degradesHonestlyRidingAPlainNonStationEntity() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class);
        minecraft.field_71439_g = player;
        player.field_70154_o = allocate(EntityBridgeTest.TestFixtureEntity.class);

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        String line = logged.get(0);
        assertTrue(line.contains("riding=" + EntityBridgeTest.TestFixtureEntity.class.getName()), line);
        assertTrue(line.contains("controlAircraft=not-a-station"), line);
        assertTrue(line.contains("aircraftRiddenOrControl=mcheli-absent"), line);
    }

    @Test
    void readsAStationShapedRidersControlAircraftByReflectionRegardlessOfItsRealType() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class);
        minecraft.field_71439_g = player;
        FakeStationEntity station = allocate(FakeStationEntity.class);
        Entity drone = allocate(EntityBridgeTest.TestFixtureEntity.class);
        station.controlAircraft = drone;
        player.field_70154_o = station;

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        String line = logged.get(0);
        assertTrue(line.contains("riding=" + FakeStationEntity.class.getName()), line);
        assertTrue(line.contains("controlAircraft=" + EntityBridgeTest.TestFixtureEntity.class.getName()), line);
    }

    @Test
    void reportsANullControlAircraftDistinctlyFromNoStationAtAll() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class);
        minecraft.field_71439_g = player;
        player.field_70154_o = allocate(FakeStationEntity.class);
        // controlAircraft left null: "docked, nobody assigned yet" must read as controlAircraft=null,
        // not the same "not-a-station" string a genuinely unrelated rider produces.

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        String line = logged.get(0);
        assertTrue(line.contains("controlAircraft=null"), line);
    }

    @Test
    void neverThrowsWhenTheReflectiveCallItselfFails() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        EntityClientPlayerMP player = allocate(EntityClientPlayerMP.class);
        minecraft.field_71439_g = player;
        player.field_70154_o = allocate(ThrowingStationEntity.class);

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        assertEquals(1, logged.size(), "a throwing reflective call must still produce one line: " + logged);
        String line = logged.get(0);
        assertTrue(line.contains("controlAircraft=getControlAircract-threw:"), line);
    }

    @Test
    void isBoundedByOncePerAndDoesNotLogTwiceWithinTheWindow() throws Exception {
        captureLogs();
        Minecraft minecraft = allocate(Minecraft.class);
        minecraft.field_71439_g = allocate(EntityClientPlayerMP.class);

        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");
        LegacyClientTickDispatcher.logUavCameraDiagnostic(minecraft, "tester");

        assertEquals(1, logged.size(), "second call within the bound window must be a no-op: " + logged);
    }

    /** A synthetic MCHeli-station-shaped fixture: only the reflective method name matters. */
    public static final class FakeStationEntity extends Entity {
        public Entity controlAircraft;

        public FakeStationEntity(World world) {
            super(world);
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

        public Entity getControlAircract() {
            return controlAircraft;
        }
    }

    /** Same shape, but the reflective call itself throws - proves the diagnostic degrades, not crashes. */
    public static final class ThrowingStationEntity extends Entity {
        public ThrowingStationEntity(World world) {
            super(world);
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

        public Entity getControlAircract() {
            throw new IllegalStateException("synthetic failure");
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, type));
    }
}
