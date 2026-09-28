package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * Classloader isolation between the co-resident universes (round-5 proof for living next to
 * the 1.7.10 universe in one JVM): the 1.16.5 loader serves its own world and provably cannot
 * see the 1.7.10 era's loader machinery, while the shared boundary contract resolves to the
 * IDENTICAL Class object on both sides (the property the whole bridge design depends on).
 * Self-skips without the fetched jars.
 */
class Legacy1165IsolationTest {

    private Legacy1165Loader open() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: "
                    + e.getMessage());
            return null;
        }
        URL[] urls = Legacy1165Classpath.toUrls(files);
        return new Legacy1165Loader(urls, Legacy1165IsolationTest.class.getClassLoader());
    }

    @Test
    void ownVanillaLoadsFromOwnLoader() throws Exception {
        try (Legacy1165Loader loader = open()) {
            Class<?> block = Class.forName("net.minecraft.block.Block", false, loader);
            assertSame(loader, block.getClassLoader());
        }
    }

    @Test
    void launchWrapperEraIsInvisible() throws Exception {
        try (Legacy1165Loader loader = open()) {
            // 1.7.10/1.12.2 boot through LaunchWrapper; nothing on this manifest provides it.
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("net.minecraft.launchwrapper.Launch", false, loader));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(
                    "net.minecraft.launchwrapper.LaunchClassLoader", false, loader));
        }
    }

    @Test
    void oldFmlIsInvisible() throws Exception {
        try (Legacy1165Loader loader = open()) {
            // 1.7.10 FML (cpw.mods.fml) and 1.12.2 FML (net.minecraftforge.fml.common.Loader)
            // must not leak in: our FML is net.minecraftforge.fml.ModLoader (FML 36.x).
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("cpw.mods.fml.common.Loader", false, loader));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(
                    "net.minecraftforge.fml.common.Loader", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.fml.ModLoader", false, loader));
        }
    }

    @Test
    void sharedContractIsIdenticalAcrossTheBoundary() throws Exception {
        try (Legacy1165Loader loader = open()) {
            // Parent-delegated by design (see the loader javadoc): the SAME Class object both
            // sides, so plain casts across the boundary are valid.
            assertSame(dev.umb.bridge.api.LegacyBridge.class,
                    Class.forName("dev.umb.bridge.api.LegacyBridge", false, loader));
            assertTrue(loader.isParentDelegated("dev.umb.bridge.api.LegacyBridge"));
            assertTrue(loader.isParentDelegated("org.apache.logging.log4j.Level"));
        }
    }
}
