package dev.umb.hostagent.content;

import dev.umb.bridge.api.HostWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R4: lazy, synchronous, exactly-once boot on first legacy need; a boot failure disables the bridge, never crashes. */
class UmbBridgeHostTest {

    @AfterEach
    void reset() {
        UmbBridgeHost.resetForTests();
    }

    @Test
    void noBridgeInstalledMeansNotBooted() {
        assertFalse(UmbBridgeHost.ensureBooted(null));
    }

    @Test
    void bootsExactlyOnceAndPassesTheWorldThrough() {
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);
        HostWorld world = new StubHostWorld();
        assertTrue(UmbBridgeHost.ensureBooted(world));
        assertTrue(bridge.isBooted());
        assertSame(world, bridge.lastBootWorld);

        // second call is a no-op (already booted) -- isBooted() short-circuits before boot() runs again
        bridge.lastBootWorld = null;
        assertTrue(UmbBridgeHost.ensureBooted(world));
        assertSame(null, bridge.lastBootWorld, "boot() must not run a second time once already booted");
    }

    @Test
    void aThrowingBootDisablesTheBridgeRatherThanPropagating() {
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.bootShouldThrow = true;
        UmbBridgeHost.set(bridge);
        assertFalse(UmbBridgeHost.ensureBooted(new StubHostWorld()));
        assertFalse(bridge.isBooted());
    }

    /** HostWorld has too many abstract methods to be a lambda target; this is the minimal stand-in. */
    private static final class StubHostWorld implements HostWorld {
        @Override public boolean isRemote() { return false; }
        @Override public long getTotalTime() { return 0; }
        @Override public String getBlockId(int x, int y, int z) { return "minecraft:air"; }
        @Override public int getMeta(int x, int y, int z) { return 0; }
        @Override public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) { }
        @Override public void setMeta(int x, int y, int z, int meta, int flags) { }
        @Override public void removeBlock(int x, int y, int z) { }
        @Override public void markBlockDirty(int x, int y, int z) { }
        @Override public void scheduleTick(int x, int y, int z, int delay) { }
        @Override public long randomSeed() { return 0; }
        @Override public void log(String msg) { }
    }
}
