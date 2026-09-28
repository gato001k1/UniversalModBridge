package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl;

/**
 * Exercises the 1.12.2 era's {@code LegacyBridge} implementation against the SAME contract the
 * 1.7.10 era speaks. {@link Legacy1122BridgeImpl#boot} genuinely needs real Forge 1.12.2 classes
 * reachable from this test's own classloader (run-tests.ps1 puts the manifest jars on the JUnit
 * console launcher's classpath for exactly this reason) - if they are not there, the boot test
 * self-skips rather than failing, so this suite still runs green from a checkout that has not run
 * the fetch step.
 */
class Legacy1122BridgeImplTest {

    private static boolean forgeReachable() {
        try {
            Class.forName("net.minecraftforge.fml.common.Loader");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void bootSucceedsWhenRealForgeClassesAreReachable() throws Exception {
        assumeTrue(forgeReachable(), "real Forge 1.12.2 classes not on the test classpath - skipping");
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        assertFalse(bridge.isBooted());
        FakeHostWorld world = new FakeHostWorld();
        bridge.boot(world);
        assertTrue(bridge.isBooted());
        assertTrue(world.logs.stream().anyMatch(s -> s.contains("UMB-BRIDGE-1122")));
        assertNull(bridge.bootFailure());
        bridge.shutdown();
        assertFalse(bridge.isBooted());
    }

    @Test
    void bootThrowsClearlyWhenForgeClassesAreNotReachable() {
        assumeTrue(!forgeReachable(), "this test only makes sense without Forge on the classpath");
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> bridge.boot(new FakeHostWorld()));
        assertTrue(e.getMessage().contains("not reachable"));
    }

    @Test
    void everyOtherMethodIsAnHonestStubThatNeverThrows() {
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        assertNull(bridge.createTile("modid:block", 0, 0, 0));

        ActivationResult ar = bridge.activate("modid:block", 0, 0, 0, new FakeHostPlayer(), 0, 0.5f, 0.5f, 0.5f);
        assertEquals(ActivationResult.DECLINED, ar);
        assertFalse(ar.handled);

        bridge.clicked("modid:block", 0, 0, 0, new FakeHostPlayer());
        bridge.tickTile(null);
        bridge.placedBy("modid:block", 0, 0, 0, new FakeHostPlayer());
        bridge.added("modid:block", 0, 0, 0);
        bridge.neighborChanged("modid:block", 0, 0, 0, "minecraft:air");
        bridge.broken("modid:block", 0, 0, 0, 0, new FakeHostPlayer());
        assertTrue(bridge.canPlaceAt("modid:block", 0, 0, 0));

        assertNull(bridge.useItemRightClick("modid:item", new FakeHostPlayer()));
        ItemUseResult iur = bridge.useItemOnBlock("modid:item", new FakeHostPlayer(), 0, 0, 0, 0, 0.5f, 0.5f, 0.5f);
        assertEquals(ItemUseResult.DECLINED, iur);
    }

    private static final class FakeHostWorld implements HostWorld {
        final List<String> logs = new ArrayList<>();
        public boolean isRemote() { return false; }
        public long getTotalTime() { return 0; }
        public String getBlockId(int x, int y, int z) { return "minecraft:air"; }
        public int getMeta(int x, int y, int z) { return 0; }
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) { }
        public void setMeta(int x, int y, int z, int meta, int flags) { }
        public void removeBlock(int x, int y, int z) { }
        public void markBlockDirty(int x, int y, int z) { }
        public void scheduleTick(int x, int y, int z, int delay) { }
        public long randomSeed() { return 0; }
        public void log(String msg) { logs.add(msg); }
    }

    private static final class FakeHostPlayer implements HostPlayer {
        public String getName() { return "tester"; }
        public boolean isSneaking() { return false; }
        public double getX() { return 0; }
        public double getY() { return 0; }
        public double getZ() { return 0; }
        public dev.umb.bridge.api.StackData getHeldItem() { return dev.umb.bridge.api.StackData.EMPTY; }
        public void setHeldItem(dev.umb.bridge.api.StackData s) { }
        public void sendMessage(String text) { }
        public dev.umb.bridge.api.StackData getInventorySlot(int i) { return dev.umb.bridge.api.StackData.EMPTY; }
        public void setInventorySlot(int i, dev.umb.bridge.api.StackData s) { }
        public int getInventorySize() { return 36; }
        // TICK/CONTACT lane additions: this fake is stationary and unhurtable.
        public double getMotionX() { return 0; }
        public double getMotionY() { return 0; }
        public double getMotionZ() { return 0; }
        public void setMotion(double mx, double my, double mz) { }
        public void hurt(String legacyDamageType, float amount) { }
    }
}
