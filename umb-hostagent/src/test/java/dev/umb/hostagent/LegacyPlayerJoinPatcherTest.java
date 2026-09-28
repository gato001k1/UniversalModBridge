package dev.umb.hostagent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Pins the 26.2 PlayerList join seam used to keep the client loading screen authoritative. */
class LegacyPlayerJoinPatcherTest {
    @Test
    void patchesVerifiedPlayerJoinMethod() throws Exception {
        try (JarFile jar = new JarFile("research/jars/26.2/server.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(
                    "net/minecraft/server/players/PlayerList.class"))) {
                byte[] patched = new LegacyPlayerJoinPatcher().transform(null,
                        LegacyPlayerJoinPatcher.TARGET, null, null, in.readAllBytes());
                assertNotNull(patched);
            }
        }
    }
}
