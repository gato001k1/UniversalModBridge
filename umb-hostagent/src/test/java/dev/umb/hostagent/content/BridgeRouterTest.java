package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Era routing with fake bridges (no real universes boot here): namespace-keyed dispatch,
 * lazy era admission, peek policy, tile ownership, broadcast, and failure isolation.
 */
class BridgeRouterTest {

    static class Fake implements LegacyBridge {
        final String name;
        final AtomicInteger boots = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        volatile boolean booted;
        RuntimeException failOnBoot;

        Fake(String name) {
            this.name = name;
        }

        @Override
        public void boot(HostWorld world) {
            boots.incrementAndGet();
            if (failOnBoot != null) throw failOnBoot;
            booted = true;
        }

        @Override
        public boolean isBooted() {
            return booted;
        }

        @Override
        public TileHandle createTile(String id, int x, int y, int z) {
            creates.incrementAndGet();
            return null;
        }

        @Override
        public ActivationResult activate(String id, int x, int y, int z, HostPlayer p, int side,
                float hx, float hy, float hz) {
            return ActivationResult.DECLINED;
        }

        @Override
        public void clicked(String id, int x, int y, int z, HostPlayer p) {
        }

        @Override
        public void tickTile(TileHandle t) {
            ticks.incrementAndGet();
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
            booted = false;
        }

        @Override
        public void placedBy(String id, int x, int y, int z, HostPlayer p) {
        }

        @Override
        public void added(String id, int x, int y, int z) {
        }

        @Override
        public void neighborChanged(String id, int x, int y, int z, String n) {
        }

        @Override
        public void broken(String id, int x, int y, int z, int meta, HostPlayer p) {
        }

        @Override
        public boolean canPlaceAt(String id, int x, int y, int z) {
            return true;
        }

        @Override
        public StackData useItemRightClick(String id, HostPlayer p) {
            return null;
        }

        @Override
        public ItemUseResult useItemOnBlock(String id, HostPlayer p, int x, int y, int z, int side,
                float hx, float hy, float hz) {
            return ItemUseResult.DECLINED;
        }

        @Override
        public EntityHandle restoreEntity(byte[] nbt) {
            return null;
        }
    }

    static final class SlowFake extends Fake {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        SlowFake(String name) {
            super(name);
        }

        @Override
        public void boot(HostWorld world) {
            boots.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("slow boot timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            booted = true;
        }
    }

    static final class RetryTarget implements BridgeRouter.EraRetryTarget {
        final AtomicInteger retries = new AtomicInteger();
        boolean alive = true;

        @Override
        public void retryAfterEraBoot() {
            retries.incrementAndGet();
        }

        @Override
        public boolean isEraRetryTargetAlive() {
            return alive;
        }
    }

    static final class FakeWorld implements HostWorld {
        public boolean isRemote() { return false; }
        public long getTotalTime() { return 0; }
        public String getBlockId(int x, int y, int z) { return "minecraft:air"; }
        public int getMeta(int x, int y, int z) { return 0; }
        public void setBlock(int x, int y, int z, String id, int meta, int flags) { }
        public void setMeta(int x, int y, int z, int meta, int flags) { }
        public void removeBlock(int x, int y, int z) { }
        public void markBlockDirty(int x, int y, int z) { }
        public void scheduleTick(int x, int y, int z, int delay) { }
        public long randomSeed() { return 0; }
        public void log(String msg) { }
    }

    private BridgeRouter routerWithEra(Fake def, Fake era) {
        BridgeRouter router = new BridgeRouter(def);
        Set<String> ns = new HashSet<>();
        ns.add("testns");
        final Fake eraFinal = era;
        router.registerEra("9.9.9", ns, () -> eraFinal);
        return router;
    }

    private static void awaitBoot(Fake era) throws Exception {
        awaitAttempt(era);
        assertTrue(era.booted);
    }

    private static void awaitAttempt(Fake era) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (era.boots.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(1, era.boots.get());
    }

    @Test void routesByNamespaceAfterLazyEraUse() throws Exception {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        BridgeRouter router = routerWithEra(def, era);
        FakeWorld world = new FakeWorld();
        router.boot(world);
        assertEquals(1, def.boots.get());
        assertEquals(0, era.boots.get(), "registration must not touch the era loader");
        assertEquals("lazy 0ms 0MB", router.statusLine("9.9.9"));
        router.createTile("testns:block", 0, 0, 0);
        awaitBoot(era);
        assertTrue(router.statusLine("9.9.9").startsWith("ready "));
        assertTrue(router.statusLine("9.9.9").contains("ms"));

        router.createTile("hbm:something", 0, 0, 0);
        assertEquals(1, def.creates.get());
        assertEquals(0, era.creates.get());

        router.createTile("testns:block", 0, 0, 0);
        assertEquals(1, era.creates.get());
        assertEquals(1, def.creates.get());

        router.createTile("TESTNS:upper", 0, 0, 0);
        assertEquals(2, era.creates.get());

        router.createTile("nosuchcolon", 0, 0, 0);
        assertEquals(2, def.creates.get());
    }

    @Test void placementGateStartsEraButRefusesFallbackUntilReady() throws Exception {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        BridgeRouter router = routerWithEra(def, era);
        router.boot(new FakeWorld());

        assertFalse(router.readyForPlacement("testns:block"));
        assertEquals(0, era.creates.get(), "a pending era must not route placement through the default bridge");
        awaitBoot(era);
        assertTrue(router.readyForPlacement("testns:block"));
    }

    @Test void slowEraReturnsRetryableTileAndDrainsQueuedRetryAfterBoot() throws Exception {
        Fake def = new Fake("default");
        SlowFake era = new SlowFake("slow-era");
        BridgeRouter router = routerWithEra(def, era);
        router.boot(new FakeWorld());

        TileHandle first = router.createTile("testns:block", 0, 0, 0);
        assertInstanceOf(EraBootingTileHandle.class, first);
        assertEquals(0, def.creates.get(), "booting era must not become normal no-TE/default fallback");
        assertTrue(era.entered.await(1, TimeUnit.SECONDS));
        RetryTarget target = new RetryTarget();
        assertTrue(router.deferTile("testns:block", target));
        assertEquals(0, target.retries.get());

        era.release.countDown();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!era.booted && System.nanoTime() < deadline) Thread.sleep(5L);
        assertTrue(era.booted);
        assertEquals(1, router.drainReadyTileRetries());
        assertEquals(1, target.retries.get());
        assertEquals("9.9.9", router.routeForTest("testns:block"));
    }

    @Test void peekPathsNeverBootAnEra() {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        BridgeRouter router = routerWithEra(def, era);
        router.tickBlock("testns:block", 0, 0, 0, true);
        router.entityInside("testns:block", 0, 0, 0, null);
        assertEquals(0, era.boots.get());
        assertEquals("default-unbooted-9.9.9", router.routeForTest("testns:block"));
        assertEquals("default", router.routeForTest("hbm:x"));
    }

    @Test void brokenEraDisablesLoudlyAndFallsBack() throws Exception {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        era.failOnBoot = new RuntimeException("boom");
        BridgeRouter router = routerWithEra(def, era);
        router.boot(new FakeWorld());
        router.createTile("testns:block", 0, 0, 0);
        awaitAttempt(era);
        assertFalse(era.booted);
        assertEquals(0, def.creates.get(), "the initial booting result must not become no-TE/default");
        assertEquals(0, era.creates.get());
        // second call does not retry the boot
        router.createTile("testns:block", 0, 0, 0);
        assertEquals(1, era.boots.get());
        assertEquals(1, def.creates.get());
    }

    @Test void shutdownStopsAllAndDuplicateEraOrNamespaceFailsLoudly() throws Exception {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        BridgeRouter router = routerWithEra(def, era);
        router.boot(new FakeWorld());
        router.createTile("testns:block", 0, 0, 0);
        awaitBoot(era);
        router.shutdown();
        assertEquals(1, def.shutdowns.get());
        assertEquals(1, era.shutdowns.get());

        assertThrows(IllegalStateException.class, () ->
                router.registerEra("9.9.9", Collections.singleton("other"), () -> era));
        assertThrows(IllegalStateException.class, () ->
                router.registerEra("8.8.8", Collections.singleton("testns"), () -> era));
    }

    @Test void isBootedReportsDefault() throws Exception {
        Fake def = new Fake("default");
        Fake era = new Fake("era");
        BridgeRouter router = routerWithEra(def, era);
        assertFalse(router.isBooted());
        router.boot(new FakeWorld());
        assertTrue(router.isBooted());
    }
}
