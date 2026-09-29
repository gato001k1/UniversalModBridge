package dev.umb.legacy1165.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Live CLIENT mode: the era's own LWJGL must extract natives to a private directory, never the
 * host's -Dorg.lwjgl.system.SharedLibraryExtractPath (where the host's lwjgl.dll is loaded and
 * locked). Configuration.set on the era's copy must not change the global system property.
 */
class EraNativeExtractIsolationTest {

    private static File eraLwjglJar() {
        File repo = new File(System.getProperty("umb.repo", "."));
        File jar = new File(repo, "research/jars/1.16.5/libraries/org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar");
        return jar;
    }

    @Test
    void eraCopyGetsPrivateExtractPathAndHostPropertyIsUntouched() throws Exception {
        File jar = eraLwjglJar();
        Assumptions.assumeTrue(jar.isFile(), "lwjgl 3.2.2 jar not present: " + jar);
        String key = "org.lwjgl.system.SharedLibraryExtractPath";
        String previous = System.getProperty(key);
        File gameDir = Files.createTempDirectory("umb-era-natives").toFile();
        try {
            System.setProperty(key, "C:/host/natives/lwjgl");
            // Parent = bootstrap: the era loader owns LWJGL exactly as Legacy1165Loader does.
            try (URLClassLoader era = new URLClassLoader(new URL[] {jar.toURI().toURL()}, null)) {
                List<String> log = new ArrayList<String>();
                Legacy1165Lifecycle.isolateEraNativeExtraction(era, gameDir, log::add);
                Class<?> configuration = Class.forName("org.lwjgl.system.Configuration", true, era);
                Object extractPath = configuration.getField("SHARED_LIBRARY_EXTRACT_PATH").get(null);
                Object value = extractPath.getClass().getMethod("get").invoke(extractPath);
                assertTrue(String.valueOf(value).startsWith(new File(gameDir, "natives").getAbsolutePath()),
                        "era extract path " + value + " log=" + log);
                assertTrue(new File(String.valueOf(value)).isDirectory());
                assertEquals("C:/host/natives/lwjgl", System.getProperty(key),
                        "the host's global property must not change");
            }
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    void lwjglOwnedByAnotherLoaderIsLeftAlone() throws Exception {
        List<String> log = new ArrayList<String>();
        // The application loader does not own LWJGL here (or owns a different copy): the method
        // must log and return, never throw.
        Legacy1165Lifecycle.isolateEraNativeExtraction(
                EraNativeExtractIsolationTest.class.getClassLoader(),
                Files.createTempDirectory("umb-era-natives2").toFile(), log::add);
        assertEquals(1, log.size(), String.valueOf(log));
    }
}
