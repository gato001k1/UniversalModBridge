package dev.umb.hostagent.input;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Twin collection is pure data (no KeyMapping statics); registration is proven live. */
class LegacyInputBootstrapTest {
    @Test void collectTwinsCoversBothModsWithTranslatedCodes() {
        List<LegacyInputBootstrap.Twin> twins =
                LegacyInputBootstrap.collectTwins(Paths.get("research/out/legacy"));
        Map<String, List<LegacyInputBootstrap.Twin>> byNs =
                twins.stream().collect(Collectors.groupingBy(LegacyInputBootstrap.Twin::namespace));
        assertEquals(20, byNs.get("hbm").size(), "HBM twins");
        assertEquals(124, byNs.get("mcheli").size(), "MCHeli twins");
        assertEquals(144, twins.size());
        Map<String, LegacyInputBootstrap.Twin> byId = twins.stream()
                .collect(Collectors.toMap(LegacyInputBootstrap.Twin::stableId, t -> t));
        LegacyInputBootstrap.Twin calc = byId.get("legacy:key:hbm.key.calculator:49:hbm.key");
        assertNotNull(calc);
        assertEquals(InputConstants.Type.KEYSYM, calc.type());
        assertEquals(78, calc.defaultCode());
        assertEquals("hbm.key.calculator", calc.translationKey());
        LegacyInputBootstrap.Twin gui = byId.get(
                "legacy:key:mcheli:mcheli/aircraft/MCH_AircraftClientTickHandler#KeyGUI");
        assertNotNull(gui);
        assertEquals(82, gui.defaultCode());
        LegacyInputBootstrap.Twin useWeapon = byId.get(
                "legacy:key:mcheli:mcheli/helicopter/MCH_ClientHeliTickHandler#KeyUseWeapon");
        assertNotNull(useWeapon);
        assertEquals(InputConstants.Type.MOUSE, useWeapon.type());
        assertEquals(1, useWeapon.defaultCode());
        // No twin without a resolvable code: nothing to guess from.
        assertTrue(twins.stream().allMatch(t -> t.defaultCode() >= 0
                || t.type() == InputConstants.Type.MOUSE));
    }

    @Test void collectTwinsToleratesMissingDir() {
        assertTrue(LegacyInputBootstrap.collectTwins(Paths.get("nonexistent-dir-xyz")).isEmpty());
    }
}
