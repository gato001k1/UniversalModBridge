package dev.umb.hostagent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Pins the 26.2 PlayerList join seam used to keep the client loading screen authoritative. */
class LegacyPlayerJoinPatcherTest {
    @Test
    void patchesVerifiedPlayerJoinMethod() throws Exception {
        java.io.File file = new java.io.File("research/jars/26.2/server.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(file.isFile(), "26.2 server jar not present");
        try (JarFile jar = new JarFile(file)) {
            java.util.jar.JarEntry entry = jar.getJarEntry("net/minecraft/server/players/PlayerList.class");
            org.junit.jupiter.api.Assumptions.assumeTrue(entry != null, "server jar has no PlayerList class");
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] patched = new LegacyPlayerJoinPatcher().transform(null,
                        LegacyPlayerJoinPatcher.TARGET, null, null, in.readAllBytes());
                assertNotNull(patched);
            }
        }
    }
}
