package dev.umb.hostagent.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.InteractionHand;

final class AutomationProtocolTest {
    @Test void parsesAndWrapsJsonLines() {
        var r = AutomationProtocol.parse("{\"id\":7,\"token\":\"t\",\"command\":\"status\"}");
        assertEquals("status", r.get("command").getAsString());
        assertTrue(AutomationProtocol.ok(7, new com.google.gson.JsonPrimitive(true)).get("ok").getAsBoolean());
        assertFalse(AutomationProtocol.error(7, "x").get("ok").getAsBoolean());
    }

    @Test void screenshotExactPngPathIsHonoredVerbatim(@TempDir Path tmp) {
        File out = AutomationControl.resolveScreenshotTarget(tmp.resolve("shot.png").toString());
        assertEquals("shot.png", out.getName());
        assertEquals(tmp.toFile().getAbsolutePath(), out.getParentFile().getAbsolutePath());
        assertTrue(out.isAbsolute(), "callers get an absolute path, never the raw relative dir echo");
    }

    @Test void screenshotDirectoryGetsTimestampedUmbName(@TempDir Path tmp) {
        File out = AutomationControl.resolveScreenshotTarget(tmp.toString());
        assertEquals("screenshots", out.getParentFile().getName());
        assertTrue(out.getName().startsWith("umb-"), out.getName());
        assertTrue(out.getName().endsWith(".png"), out.getName());
        assertTrue(out.isAbsolute());
    }

    @Test void screenshotDirectoryCollisionDedups(@TempDir Path tmp) throws Exception {
        File first = AutomationControl.resolveScreenshotTarget(tmp.toString());
        Files.createDirectories(first.getParentFile().toPath());
        Files.createFile(first.toPath());
        File second = AutomationControl.resolveScreenshotTarget(tmp.toString());
        assertNotEquals(first.getAbsolutePath(), second.getAbsolutePath());
        assertTrue(second.getName().startsWith("umb-"), second.getName());
    }

    @Test void screenshotPngNamedDirectoryIsTreatedAsDirectory(@TempDir Path tmp) throws Exception {
        Path weird = tmp.resolve("weird.png");
        Files.createDirectory(weird);
        File out = AutomationControl.resolveScreenshotTarget(weird.toString());
        assertEquals("screenshots", out.getParentFile().getName());
    }

    @Test void entityCommandsKeepHandAndUuidOnTheProtocolBoundary() {
        var request = AutomationProtocol.parse("{\"id\":1,\"command\":\"interact_entity\",\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"hand\":\"main\"}");
        assertEquals("interact_entity", request.get("command").getAsString());
        assertEquals(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                java.util.UUID.fromString(request.get("uuid").getAsString()));
        assertSame(InteractionHand.MAIN_HAND, AutomationControl.parseHand(request));
        var off = AutomationProtocol.parse("{\"hand\":\"off\"}");
        assertSame(InteractionHand.OFF_HAND, AutomationControl.parseHand(off));
    }

    @Test void guiClickParsesGuiRelativeCoordinatesAndButton() {
        var request = AutomationProtocol.parse("{\"command\":\"gui_click\",\"x\":42.5,\"y\":17,\"button\":0}");
        var click = AutomationControl.parseGuiClick(request);
        assertEquals(42.5, click.x());
        assertEquals(17.0, click.y());
        assertEquals(0, click.button());
    }

    @Test void guiClickRejectsInvalidCoordinatesAndButtons() {
        assertThrows(IllegalArgumentException.class, () -> AutomationControl.parseGuiClick(
                AutomationProtocol.parse("{\"x\":-1,\"y\":2,\"button\":0}")));
        assertThrows(IllegalArgumentException.class, () -> AutomationControl.parseGuiClick(
                AutomationProtocol.parse("{\"x\":1,\"y\":2,\"button\":3}")));
    }
}
