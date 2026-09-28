package dev.umb.hostagent.content;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless panel-origin regression: every real GuiContainer profile is evaluated with its own
 * imageWidth/imageHeight. The fallback player grid uses the same panel-relative origin as the
 * legacy container and must never be placed below/outside that panel. Real legacy player-slot
 * coordinates are additionally checked at runtime by slotsInsidePanel before they are accepted.
 */
class GuiProfileSlotLayoutTest {
    @Test
    void everyGuiContainerProfileKeepsThe36SlotFallbackInsideItsOwnPanel() throws Exception {
        Path profile = Paths.get("research/out/legacy/gui-profile.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(profile));
        GuiProfile p = GuiProfile.load(profile);
        int containers = 0;
        for (GuiProfile.GuiEntry e : p.entries()) {
            if (e.containerClassName == null) continue;
            containers++;
            assertTrue(e.xSize >= 176 && e.ySize >= 166, e.guiClassName + " has invalid panel "
                    + e.xSize + "x" + e.ySize);
            int y = Math.max(18, e.ySize - 82);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    assertTrue(8 + col * 18 + 16 <= e.xSize, e.guiClassName + " player x outside panel");
                    assertTrue(y + row * 18 + 16 <= e.ySize, e.guiClassName + " player row outside panel");
                }
            }
            for (int col = 0; col < 9; col++) {
                assertTrue(8 + col * 18 + 16 <= e.xSize, e.guiClassName + " hotbar x outside panel");
                assertTrue(y + 54 + 16 <= e.ySize, e.guiClassName + " hotbar outside panel");
            }
        }
        // GuiProfile intentionally deduplicates by Container class (171 live keys), so use the
        // raw JSON rows for the required 181-GuiContainer denominator as well.
        JsonObject root = JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        int rawContainers = 0;
        for (var row : root.getAsJsonArray("guis")) {
            if ("GuiContainer".equals(row.getAsJsonObject().get("kind").getAsString())) rawContainers++;
        }
        assertEquals(181, rawContainers, "all GuiContainer profile rows are the layout denominator");
        assertEquals(171, containers, "deduplicated host Container keys");
    }
}
