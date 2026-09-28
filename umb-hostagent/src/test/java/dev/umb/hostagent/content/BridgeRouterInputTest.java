package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.InputData;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.TileHandle;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The interface defaults for acceptInput (false) and drainClientEffects (empty) exist
 * for fakes/eras — but the router is the LIVE path and must forward both to the
 * default universe. The input lane discovered this live: everything forwarded except
 * these two, with total silence.
 */
class BridgeRouterInputTest {
    static final class RecordingBridge implements LegacyBridge {
        final AtomicInteger accepts = new AtomicInteger();
        final EffectData marker =
                new EffectData("test", "route", "type", 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, new byte[0]);

        @Override public void boot(HostWorld world) { }
        @Override public boolean isBooted() { return true; }
        @Override public TileHandle createTile(String id, int x, int y, int z) { return null; }
        @Override public ActivationResult activate(String id, int x, int y, int z, HostPlayer p,
                int side, float hx, float hy, float hz) { return ActivationResult.DECLINED; }
        @Override public void clicked(String id, int x, int y, int z, HostPlayer p) { }
        @Override public void tickTile(TileHandle t) { }
        @Override public void shutdown() { }
        @Override public dev.umb.bridge.api.EntityHandle restoreEntity(byte[] nbt) { return null; }
        @Override public void placedBy(String id, int x, int y, int z, HostPlayer p) { }
        @Override public void added(String id, int x, int y, int z) { }
        @Override public void neighborChanged(String id, int x, int y, int z, String n) { }
        @Override public void broken(String id, int x, int y, int z, int meta, HostPlayer p) { }
        @Override public boolean canPlaceAt(String id, int x, int y, int z) { return true; }
        @Override public dev.umb.bridge.api.StackData useItemRightClick(String id, HostPlayer p) {
            return null;
        }
        @Override public dev.umb.bridge.api.ItemUseResult useItemOnBlock(String id, HostPlayer p,
                int x, int y, int z, int side, float hx, float hy, float hz) {
            return dev.umb.bridge.api.ItemUseResult.DECLINED;
        }
        @Override public boolean acceptInput(HostPlayer player, InputData input) {
            accepts.incrementAndGet();
            return true;
        }
        @Override public List<EffectData> drainClientEffects() {
            return List.of(marker);
        }
    }

    private static InputData frame() {
        return new InputData(1L, false, false, false, false, false, 0, 0f, 0f,
                0, 0, 1, "", 0, 0, null, Map.of("legacy:key:test:1:cat", true));
    }

    @Test void acceptInputForwardsToDefault() {
        RecordingBridge inner = new RecordingBridge();
        BridgeRouter router = new BridgeRouter(inner);
        assertTrue(router.acceptInput(null, frame()));
        assertEquals(1, inner.accepts.get());
    }

    @Test void drainClientEffectsForwardsToDefault() {
        RecordingBridge inner = new RecordingBridge();
        BridgeRouter router = new BridgeRouter(inner);
        List<EffectData> out = router.drainClientEffects();
        assertEquals(1, out.size());
        assertEquals("test", out.get(0).kind);
    }
}
