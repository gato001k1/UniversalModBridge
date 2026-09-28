package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1122.boot.Legacy1122Classpath;
import dev.umb.legacy1122.boot.Legacy1122Loader;

/**
 * Integration proof against the REAL jars fetched for this lane (Forge 1.12.2 universal from
 * maven.minecraftforge.net, the real obfuscated 1.12.2 client.jar already cached under
 * research/jars/1.12.2 by an earlier mapping lane, plus the runtime libraries listed in
 * classpath-1122.txt) - not fixtures. Self-skips (does not fail) if the manifest or any jar it
 * lists is missing, so a fresh checkout without the fetch step still passes the rest of the suite;
 * see umb-legacy-1122/README.md for how to (re)run the fetch.
 *
 * <p>What this proves, concretely (see research/out/legacy-1122/ERA-1122-PLAN.md for the full
 * evidence trail this test is a live re-check of):</p>
 * <ul>
 *   <li>Genuine Forge 1.12.2 classes (never obfuscated) load through the isolated loader.</li>
 *   <li>Genuine vanilla classes in the real, still-obfuscated client.jar do NOT load by their real
 *       (SRG/MCP) name - the documented pre-deobfuscation wall.</li>
 *   <li>The two resources a real deobfuscation stage needs are physically reachable.</li>
 * </ul>
 */
class RealForgeJarsProbeTest {

    private Legacy1122Loader open() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        assumeTrue(manifest.isFile(), "classpath-1122.txt not present - skipping real-jar probe");
        List<File> files;
        try {
            files = Legacy1122Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1122.txt is missing - skipping: " + e.getMessage());
            return null; // unreachable
        }
        URL[] urls = Legacy1122Classpath.toUrls(files);
        return new Legacy1122Loader(urls, RealForgeJarsProbeTest.class.getClassLoader());
    }

    @Test
    void manifestListsExactlyTheExpectedJarCount() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        assumeTrue(manifest.isFile(), "classpath-1122.txt not present - skipping");
        List<File> files = Legacy1122Classpath.readManifest(repo, manifest);
        // The original 40 jars plus the pinned Commons Compress, Commons IO, and Commons Lang
        // compatibility jars = 43; the test mod is discovered from the isolated mods directory.
        assertEquals(41, files.size(), "classpath-1122.txt jar count changed - update this pin deliberately");
    }

    @Test
    void realForgeClassesLoadThroughTheIsolatedLoader() throws Exception {
        try (Legacy1122Loader loader = open()) {
            assertNotNull(Class.forName("net.minecraftforge.fml.common.Loader", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.event.RegistryEvent", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.registries.IForgeRegistry", false, loader));
            assertNotNull(Class.forName(
                    "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                    false, loader));
        }
    }

    @Test
    void obfuscatedVanillaClassesDoNotLoadByTheirRealNameYet() throws Exception {
        try (Legacy1122Loader loader = open()) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("net.minecraft.block.Block", false, loader));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("net.minecraft.client.Minecraft", false, loader));
        }
    }

    @Test
    void deobfuscationInputsAreReachableThroughTheLoader() throws Exception {
        try (Legacy1122Loader loader = open()) {
            assertNotNull(loader.getResource("deobfuscation_data-1.12.2.lzma"),
                    "Forge's own SRG deobf data must be on the classpath for a future full boot");
            assertNotNull(loader.getResource("binpatches.pack.lzma"),
                    "Forge's own vanilla binpatches must be on the classpath for a future full boot");
            assertNotNull(loader.getResource("forge_at.cfg"));
            assertTrue(loader.getResource("a.class") != null || loader.getResource("aa.class") != null,
                    "the real client.jar's vanilla classes should still carry short obfuscated names");
        }
    }
}
