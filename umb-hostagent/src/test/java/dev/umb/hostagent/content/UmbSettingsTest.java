package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UmbSettingsTest {
    @Test
    void persistsUniversalSettings(@TempDir Path dir) throws Exception {
        String old = System.getProperty("umb.legacy.gameDir");
        try {
            System.setProperty("umb.legacy.gameDir", dir.toString());
            UmbSettings.resetForTests();
            UmbSettings.setAnimationDistance(64);
            UmbSettings.setFarMachineThrottle(false);
            UmbSettings.setCaptureCacheBytes(128L * 1024L * 1024L);
            UmbSettings.setEraEnabled("1.16.5", false);
            assertTrue(Files.isRegularFile(dir.resolve("config/umb.json")));
            UmbSettings.resetForTests();
            assertEquals(64, UmbSettings.animationDistance());
            assertFalse(UmbSettings.farMachineThrottle());
            assertEquals(128L * 1024L * 1024L, UmbSettings.captureCacheBytes());
            assertFalse(UmbSettings.eraEnabled("1.16.5"));
        } finally {
            if (old == null) System.clearProperty("umb.legacy.gameDir");
            else System.setProperty("umb.legacy.gameDir", old);
            UmbSettings.resetForTests();
        }
    }
}
