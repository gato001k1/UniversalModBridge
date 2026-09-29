package dev.umb.hostagent.input;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The shipped plans only use codes this table covers, with the exact GLFW values below. */
class Lwjgl2ToGlfwTest {
    @Test void planKeyboardCodesMapToExpectedGlfw() {
        // HBM (LWJGL2 -> GLFW): R C V Z B N F1 arrows enter ctrl/shift/alt + R letter.
        assertEquals(82, Lwjgl2ToGlfw.keyboardToGlfw(19));
        assertEquals(67, Lwjgl2ToGlfw.keyboardToGlfw(46));
        assertEquals(86, Lwjgl2ToGlfw.keyboardToGlfw(47));
        assertEquals(90, Lwjgl2ToGlfw.keyboardToGlfw(44));
        assertEquals(66, Lwjgl2ToGlfw.keyboardToGlfw(48));
        assertEquals(78, Lwjgl2ToGlfw.keyboardToGlfw(49));
        assertEquals(290, Lwjgl2ToGlfw.keyboardToGlfw(59));
        assertEquals(265, Lwjgl2ToGlfw.keyboardToGlfw(200));
        assertEquals(263, Lwjgl2ToGlfw.keyboardToGlfw(203));
        assertEquals(262, Lwjgl2ToGlfw.keyboardToGlfw(205));
        assertEquals(264, Lwjgl2ToGlfw.keyboardToGlfw(208));
        assertEquals(257, Lwjgl2ToGlfw.keyboardToGlfw(28));
        assertEquals(341, Lwjgl2ToGlfw.keyboardToGlfw(29));
        assertEquals(340, Lwjgl2ToGlfw.keyboardToGlfw(42));
        assertEquals(342, Lwjgl2ToGlfw.keyboardToGlfw(56));
        assertEquals(82 + 0, Lwjgl2ToGlfw.keyboardToGlfw(19));
        // MCHeli config defaults: W S D A F G H J L M U Y X SPACE J navigation.
        assertEquals(87, Lwjgl2ToGlfw.keyboardToGlfw(17));
        assertEquals(83, Lwjgl2ToGlfw.keyboardToGlfw(31));
        assertEquals(68, Lwjgl2ToGlfw.keyboardToGlfw(32));
        assertEquals(65, Lwjgl2ToGlfw.keyboardToGlfw(30));
        assertEquals(70, Lwjgl2ToGlfw.keyboardToGlfw(33));
        assertEquals(71, Lwjgl2ToGlfw.keyboardToGlfw(34));
        assertEquals(72, Lwjgl2ToGlfw.keyboardToGlfw(35));
        assertEquals(74, Lwjgl2ToGlfw.keyboardToGlfw(36));
        assertEquals(76, Lwjgl2ToGlfw.keyboardToGlfw(38));
        assertEquals(77, Lwjgl2ToGlfw.keyboardToGlfw(50));
        assertEquals(85, Lwjgl2ToGlfw.keyboardToGlfw(22));
        assertEquals(89, Lwjgl2ToGlfw.keyboardToGlfw(21));
        assertEquals(88, Lwjgl2ToGlfw.keyboardToGlfw(45));
        assertEquals(32, Lwjgl2ToGlfw.keyboardToGlfw(57));
        assertEquals(266, Lwjgl2ToGlfw.keyboardToGlfw(201));
        assertEquals(267, Lwjgl2ToGlfw.keyboardToGlfw(209));
    }

    @Test void mouseCodesStayOutOfTheKeyboardTable() {
        assertTrue(Lwjgl2ToGlfw.isMouseCode(-100));
        assertTrue(Lwjgl2ToGlfw.isMouseCode(-99));
        assertTrue(Lwjgl2ToGlfw.isMouseCode(-98));
        assertFalse(Lwjgl2ToGlfw.isMouseCode(19));
        assertThrows(IllegalArgumentException.class, () -> Lwjgl2ToGlfw.keyboardToGlfw(-100));
        assertThrows(IllegalArgumentException.class, () -> Lwjgl2ToGlfw.keyboardToGlfw(256));
    }

    @Test void reverseTableRoundTripsRepresentativeKeys() {
        assertEquals(19, Lwjgl2ToGlfw.glfwToKeyboard(82));
        assertEquals(17, Lwjgl2ToGlfw.glfwToKeyboard(87));
        assertEquals(200, Lwjgl2ToGlfw.glfwToKeyboard(265));
        assertThrows(IllegalArgumentException.class, () -> Lwjgl2ToGlfw.glfwToKeyboard(999));
    }

    @Test void everyPlanCodeResolves() throws Exception {
        java.nio.file.Path dir = java.nio.file.Paths.get("research/out/legacy");
        int checked = 0;
        int unset = 0;
        try (var paths = java.nio.file.Files.list(dir)) {
            for (var p : (Iterable<java.nio.file.Path>) paths::iterator) {
                if (!p.getFileName().toString().endsWith("-input-plans.json")) continue;
                var root = com.google.gson.JsonParser.parseReader(
                        java.nio.file.Files.newBufferedReader(p)).getAsJsonObject();
                for (var e : root.getAsJsonArray("keybindings")) {
                    var b = e.getAsJsonObject();
                    if (!b.has("keyCode")) continue;
                    int code = b.get("keyCode").getAsInt();
                    // Unset codes (mods with their own key classes) resolve through the
                    // *-key-defaults.json table, covered by LegacyInputBootstrapTest.
                    if (code == Integer.MIN_VALUE) { unset++; continue; }
                    if (code < 0) {
                        int button = code + 100;
                        assertTrue(button >= 0 && button < 8,
                                "mouse button out of range for " + b.get("stableId"));
                    } else {
                        Lwjgl2ToGlfw.keyboardToGlfw(code);
                    }
                    checked++;
                }
            }
        }
        assertEquals(20, checked, "all HBM plan codes resolve in the table");
        assertEquals(124, unset, "all MCHeli entries defer to the defaults table");
    }

    @Test void automationMergeAndLatestStore() {
        LegacyAutomationKeys.clearForTest();
        try {
            assertFalse(LegacyAutomationKeys.hasLive());
            Map<String, Boolean> merged = LegacyAutomationKeys.merged(Map.of("a", true));
            assertEquals(Boolean.TRUE, merged.get("a"));
            LegacyAutomationKeys.press("legacy:key:test:1:cat");
            assertTrue(LegacyAutomationKeys.hasLive());
            assertEquals(Boolean.TRUE, LegacyAutomationKeys.merged(Map.of()).get("legacy:key:test:1:cat"));
            LegacyAutomationKeys.press("legacy:key:gone:1:cat", 0);
            assertFalse(LegacyAutomationKeys.merged(Map.of()).containsKey("legacy:key:gone:1:cat"));
            LegacyAutomationKeys.press(null);
            LegacyAutomationKeys.press("  ");
        } finally {
            LegacyAutomationKeys.clearForTest();
        }
        assertFalse(LegacyAutomationKeys.hasLive());
        LegacyInputFrame f = new LegacyInputFrame(1, "p", "", 0, 1, null,
                false, false, false, false, false, 0, 0, 1, 0, 0, 0, Map.of());
        LegacyLatestInputs.put(f);
        assertEquals(f, LegacyLatestInputs.get("p"));
        assertNull(LegacyLatestInputs.get("nobody"));
        LegacyLatestInputs.remove("p");
        assertNull(LegacyLatestInputs.get("p"));
    }

    @Test void differIgnoresTickButCatchesEveryField() {
        LegacyInputFrame base = new LegacyInputFrame(1, "p", "item", 0, 1, null,
                false, false, false, false, false, 0, 0, 1, 0, 0, 0, Map.of("k", true));
        assertFalse(LegacyFrameDiffer.changed(base, base));
        assertTrue(LegacyFrameDiffer.changed(null, base));
        assertTrue(LegacyFrameDiffer.changed(base, null));
        // Same content, different sample id: no change.
        assertFalse(LegacyFrameDiffer.changed(base, new LegacyInputFrame(999, "p", "item", 0, 1, null,
                false, false, false, false, false, 0, 0, 1, 0, 0, 0, Map.of("k", true))));
        // Each field flips the verdict.
        assertTrue(LegacyFrameDiffer.changed(base, with(base, "item2", 0, 1)));
        assertTrue(LegacyFrameDiffer.changed(base, with(base, "item", 3, 0)));
        assertTrue(LegacyFrameDiffer.changed(base, keys(Map.of("k", false))));
        assertTrue(LegacyFrameDiffer.changed(base, keys(Map.of())));
        assertTrue(LegacyFrameDiffer.changed(base, look(0, 0, 2)));
    }

    private static LegacyInputFrame with(LegacyInputFrame b, String item, int slot, int count) {
        return new LegacyInputFrame(2, "p", item, 0, count, null,
                false, false, false, false, false, 0, 0, 1, 0, 0, slot, b.legacyKeys());
    }

    private static LegacyInputFrame keys(Map<String, Boolean> keys) {
        return new LegacyInputFrame(2, "p", "item", 0, 1, null,
                false, false, false, false, false, 0, 0, 1, 0, 0, 0, keys);
    }

    private static LegacyInputFrame look(double x, double y, double z) {
        return new LegacyInputFrame(2, "p", "item", 0, 1, null,
                false, false, false, false, false, x, y, z, 0, 0, 0, Map.of("k", true));
    }
}
