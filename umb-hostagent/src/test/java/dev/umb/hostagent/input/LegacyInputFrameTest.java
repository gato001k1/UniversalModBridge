package dev.umb.hostagent.input;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LegacyInputFrameTest {
    @Test void copiesNbtAndRejectsFabricatedLook() {
        byte[] nbt = {7};
        LegacyInputFrame f = new LegacyInputFrame(1, "p", "", 0, 1, nbt,
                false, false, false, false, true, 0, 0, 1, 0, 0, 0, Map.of());
        nbt[0] = 8; assertEquals(7, f.heldNbt()[0]);
        assertThrows(IllegalArgumentException.class, () -> new LegacyInputFrame(1, "p", "", 0, 1,
                null, false, false, false, false, false, Double.NaN, 0, 1, 0, 0, 0, Map.of()));
    }
}
