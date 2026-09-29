package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.umb.legacy.legacyside.LegacyClientFacade;

/**
 * box instead of the vanilla grey button art): {@code LegacyClientFacade}'s one-shot vanilla GUI
 * art export must actually reach every file its own {@code VANILLA_GUI_ART} list names -
 * including {@code widgets.png}, the sheet every legacy {@code GuiButton.drawButton} binds
 * no override in any of the three MCHeli scoreboard GuiScreen classes) - onto disk under
 * {@code <umb.legacy.gameDir>/umbvanilla1710/textures/gui/}, the exact path
 * {@code LegacyGuiTextureSupply.fileFor} resolves an {@code umbvanilla1710:textures/gui/*} id to
 * on the host side.
 *
 * <p>This reproduces the real export path end to end (no mocking): {@code exportVanillaGuiArt}
 * reads straight from {@code research/jars/1.7.10/client.jar} (found via {@code umb.repo}, same
 * property {@code tools/build-legacy.ps1} passes when it runs this suite, with a {@code user.dir}
 * fallback for other invocations), so a real regression in the file list, the jar lookup, or the
 * gameDir the files land under fails this test the same way it fails live.</p>
 */
class VanillaGuiArtExportTest {

    private Boolean previousLatch;

    @AfterEach
    void resetLatch() throws Exception {
        // The export is a one-shot-per-JVM latch (VANILLA_GUI_EXPORTED); reset it so this test
        // does not depend on running before any other test that might trip the same latch, and
        // so it does not leave the latch tripped for a later test in the same JVM either.
        if (previousLatch != null) {
            Field latch = LegacyClientFacade.class.getDeclaredField("VANILLA_GUI_EXPORTED");
            latch.setAccessible(true);
            latch.setBoolean(null, previousLatch.booleanValue());
        }
    }

    @Test
    void exportsEveryVanillaGuiArtFileIncludingWidgetsPng(@TempDir Path tmp) throws Exception {
        java.io.File clientJar = new java.io.File(System.getProperty("umb.repo", "."),
                "research/jars/1.7.10/client.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(clientJar.isFile(),
                "vanilla 1.7.10 client jar not present - skipping");
        String previousGameDir = System.getProperty("umb.legacy.gameDir");
        System.setProperty("umb.legacy.gameDir", tmp.toString());
        try {
            Field latch = LegacyClientFacade.class.getDeclaredField("VANILLA_GUI_EXPORTED");
            latch.setAccessible(true);
            previousLatch = Boolean.valueOf(latch.getBoolean(null));
            latch.setBoolean(null, false);

            Method export = LegacyClientFacade.class.getDeclaredMethod("exportVanillaGuiArt");
            export.setAccessible(true);
            export.invoke(null);

            File guiDir = tmp.resolve("umbvanilla1710/textures/gui").toFile();
            File widgets = new File(guiDir, "widgets.png");
            assertTrue(widgets.isFile(),
                    "widgets.png must be exported - it is the vanilla GuiButton art every legacy "
                            + "mod's buttons (e.g. MCHeli's scoreboard) bind; a missing file is exactly "
                            + "why those buttons paint as a flat untextured box");
            assertTrue(widgets.length() > 0, "widgets.png must not be a truncated/empty file");

            Field artField = LegacyClientFacade.class.getDeclaredField("VANILLA_GUI_ART");
            artField.setAccessible(true);
            String[] art = (String[]) artField.get(null);
            assertTrue(art.length > 0, "VANILLA_GUI_ART must not be empty (test would be vacuous)");

            List<String> missing = new ArrayList<>();
            for (String name : art) {
                File f = new File(guiDir, name);
                if (!f.isFile() || f.length() == 0) missing.add(name);
            }
            assertTrue(missing.isEmpty(),
                    "every file VANILLA_GUI_ART names must actually land on disk (a real 1.7.10 "
                            + "client.jar has all of them - see research/jars/1.7.10/client.jar); "
                            + "missing: " + missing);
        } finally {
            if (previousGameDir == null) System.clearProperty("umb.legacy.gameDir");
            else System.setProperty("umb.legacy.gameDir", previousGameDir);
        }
    }

    @Test
    void widgetsPngIsPresentInTheVanillaGuiArtList() throws Exception {
        // A file can only ever export if it is named here first - pin this against a silent
        // future edit dropping the one entry every legacy GuiButton needs.
        Field artField = LegacyClientFacade.class.getDeclaredField("VANILLA_GUI_ART");
        artField.setAccessible(true);
        String[] art = (String[]) artField.get(null);
        boolean present = false;
        for (String name : art) {
            if ("widgets.png".equals(name)) { present = true; break; }
        }
        assertTrue(present, "widgets.png must stay in VANILLA_GUI_ART");
        assertFalse(art.length == 0);
    }
}
