package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.Identifier;

/**
 * flat white box) broke: {@code LegacyClientFacade.exportVanillaGuiArt} (umb-legacy) writes each
 * vanilla GUI sheet to {@code new File(System.getProperty("umb.legacy.gameDir"),
 * "umbvanilla1710/textures/gui/" + name)}; {@link LegacyGuiTextureSupply#fileFor} (this module)
 * must resolve the identifier the render replay looks up
 * ({@code umbvanilla1710:textures/gui/<name>}, produced by {@code LegacyGuiTextureResolver}) to
 * legacy side wrote under {@code umb.legacy.gameDir} while the host read from its own
 * {@code Minecraft.gameDirectory}, two different roots on a live deploy even though both
 * properties existed).
 *
 * <p>Deliberately does not touch {@link LegacyGuiTextureSupply#ensure} itself: registering a
 * texture needs a live {@code Minecraft.getInstance()} + GPU-backed {@code DynamicTexture}, not
 * available in this headless suite (same constraint {@code UmbLegacyScreenTest} documents for
 * {@code Screen}). {@code fileFor} is the one piece of the contract that is pure path
 * computation and fully headless-testable - which is exactly the piece that broke live.</p>
 */
class LegacyGuiTextureSupplyPathTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("umb.legacy.gameDir");
    }

    @Test
    void resolvesWidgetsPngToExactlyWhereTheLegacySideExportsIt(@TempDir Path gameDir) throws Exception {
        System.setProperty("umb.legacy.gameDir", gameDir.toString());

        Identifier id = Identifier.tryParse("umbvanilla1710:textures/gui/widgets.png");
        Path resolved = fileFor(id);

        Path expected = gameDir.resolve("umbvanilla1710").resolve("textures").resolve("gui")
                .resolve("widgets.png");
        assertEquals(expected, resolved,
                "the host lookup path must be byte-identical to where "
                        + "LegacyClientFacade.exportVanillaGuiArt writes umbvanilla1710/textures/gui/*"
                        + " under umb.legacy.gameDir - any divergence means the export and the "
                        + "lookup silently never meet, and every vanilla GUI sheet (button art "
                        + "included) falls back to an untextured fill forever");
    }

    @Test
    void aFileActuallyWrittenAtThatPathIsFoundAsRegular(@TempDir Path gameDir) throws Exception {
        System.setProperty("umb.legacy.gameDir", gameDir.toString());

        // Mirrors exactly what exportVanillaGuiArt does: new File(gameDir,
        // "umbvanilla1710/textures/gui/" + name), byte for byte.
        Path written = gameDir.resolve("umbvanilla1710/textures/gui/widgets.png");
        Files.createDirectories(written.getParent());
        Files.write(written, new byte[] {1, 2, 3, 4});

        Identifier id = Identifier.tryParse("umbvanilla1710:textures/gui/widgets.png");
        Path resolved = fileFor(id);

        assertEquals(written.toAbsolutePath().normalize(), resolved.toAbsolutePath().normalize());
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(resolved));
    }

    @Test
    void failsClosedWithNoGameDirPropertyAndNoLiveClient() throws Exception {
        // fileFor's only fallback for a missing umb.legacy.gameDir property is
        // Minecraft.getInstance().gameDirectory, which is null in this headless suite (no
        // live windowed client - see UmbLegacyScreenTest's javadoc for the same constraint).
        // Confirms the miss degrades to "no path" rather than silently resolving against an
        // unrelated directory (e.g. the JVM's own working directory).
        System.clearProperty("umb.legacy.gameDir");
        Identifier id = Identifier.tryParse("umbvanilla1710:textures/gui/widgets.png");
        Path resolved = fileFor(id);
        assertNull(resolved, "with no gameDir property and no live Minecraft instance, fileFor "
                + "must fail closed (null), never guess a path from an unrelated working directory");
    }

    private static Path fileFor(Identifier id) throws Exception {
        Method m = LegacyGuiTextureSupply.class.getDeclaredMethod("fileFor", Identifier.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, id);
    }
}
