package dev.umb.hostagent.input;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LegacyInputDispatcherTest {
    private static LegacyInputFrame frame(long tick, boolean edge) {
        return new LegacyInputFrame(tick, "p", "legacy:gun", 2, 1, new byte[]{1, 2},
                true, edge, true, edge, false, 0, 0, 1, 10, 20, 3, Map.of("reload", true));
    }
    @Test void latestFramePerPlayerIsDispatchedOncePerTick() {
        AtomicInteger calls = new AtomicInteger();
        LegacyInputDispatcher d = new LegacyInputDispatcher(f -> {
            calls.incrementAndGet(); assertEquals(3, f.selectedSlot());
        });
        d.receive(new LegacyInputPayload(frame(1, true)));
        d.receive(new LegacyInputPayload(frame(2, false)));
        d.dispatchTick();
        assertEquals(1, calls.get()); assertEquals(2, d.latest("p").tick());
    }
}
