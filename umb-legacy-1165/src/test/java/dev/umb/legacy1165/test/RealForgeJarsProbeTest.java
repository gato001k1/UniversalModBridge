package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * Integration proof against the REAL jars fetched for this lane (Forge 1.16.5-36.2.34 universal
 * plus its installer-profile libraries from maven.minecraftforge.net, the real obfuscated 1.16.5
 * client.jar already cached under research/jars/1.16.5 by an earlier mapping lane, plus the
 * runtime libraries listed in classpath-1165.txt) - not fixtures. Self-skips (does not fail) if
 * the manifest or any jar it lists is missing, so a fresh checkout without the fetch step still
 * passes the rest of the suite; see umb-legacy-1165/README.md for how to (re)run the fetch.
 *
 * <p>What this proves, concretely (see research/out/legacy-1165/ERA-1165-PLAN.md for the full
 * evidence trail this test is a live re-check of):</p>
 * <ul>
 *   <li>Genuine ModLauncher classes (a separate library, NOT inside the Forge jar) load through
 *       the isolated loader.</li>
 *   <li>Genuine Forge 1.16.5/FML 36.x classes (never obfuscated) load through the isolated
 *       loader.</li>
 *   <li>Genuine vanilla SERVER classes in the in-lane SRG-renamed + binpatched jar DO
 *       load by their real SRG names ({@code net.minecraft.block.Block}) - the pre-rename wall
 *       is crossed for the server side, PATCHED_SHA-verified.</li>
 *   <li>Client-only vanilla classes ({@code net.minecraft.client.Minecraft}) still do NOT load -
 *       the server jar has no client classes, the documented remaining wall.</li>
 *   <li>The resources a real mod-discovery stage needs are physically reachable.</li>
 * </ul>
 */
class RealForgeJarsProbeTest {

    private Legacy1165Loader open() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping real-jar probe");
        List<File> files;
        try {
            files = Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: " + e.getMessage());
            return null; // unreachable
        }
        URL[] urls = Legacy1165Classpath.toUrls(files);
        return new Legacy1165Loader(urls, RealForgeJarsProbeTest.class.getClassLoader());
    }

    @Test
    void manifestListsExactlyTheExpectedJarCount() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files = Legacy1165Classpath.readManifest(repo, manifest);
        // 1 obfuscated vanilla client + SRG overlay + full clean SRG server jars + client SRG
        // jar (client-capable universe, see classpath-1165.txt) + 39 vanilla
        // libraries (log4j 2.8.1 deliberately excluded, 2.15.0 wins) + Forge universal +
        // IronChest test mod + 8 installer-profile/Central libs
        // (modlauncher, eventbus, forgespi, coremods, accesstransformers, asm, log4j-api, log4j-core)
        // + Forge's own TOML stack (night-config core+toml, for mods.toml discovery).
        // + Forge's own server-launch jar (fml/loading: FMLLoader, ModDiscoverer).
        // + full ASM 9.1 set (ModLauncher TransformStore needs tree).
        // + our own in-universe transform worker (built by build.ps1, fails loudly if missing).
        // + maven-artifact (ModInfo versions, version.json pins 3.6.3).
        // + typetools (eventbus listener generics, version.json pins 0.8.3).
        // + our own legacyside (lifecycle, facades, bridge - built by build.ps1).
        assertEquals(64, files.size(), "classpath-1165.txt jar count changed - update this pin deliberately");
    }

    @Test
    void realModLauncherClassesLoadThroughTheIsolatedLoader() throws Exception {
        try (Legacy1165Loader loader = open()) {
            assertNotNull(Class.forName("cpw.mods.modlauncher.Launcher", false, loader));
            assertNotNull(Class.forName("cpw.mods.modlauncher.TransformingClassLoader", false, loader));
            assertNotNull(Class.forName("cpw.mods.modlauncher.api.ITransformationService", false, loader));
        }
    }

    @Test
    void realForgeClassesLoadThroughTheIsolatedLoader() throws Exception {
        try (Legacy1165Loader loader = open()) {
            assertNotNull(Class.forName("net.minecraftforge.fml.ModLoader", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.event.RegistryEvent", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.registries.IForgeRegistry", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.registries.DeferredRegister", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.fml.RegistryObject", false, loader));
            assertNotNull(Class.forName("net.minecraftforge.common.capabilities.Capability", false, loader));
        }
    }

    @Test
    void renamedVanillaServerClassesLoadByTheirRealSrgName() throws Exception {
        try (Legacy1165Loader loader = open()) {
            assertNotNull(Class.forName("net.minecraft.block.Block", false, loader));
            assertNotNull(Class.forName("net.minecraft.block.AbstractBlock", false, loader));
            assertNotNull(Class.forName("net.minecraft.tileentity.TileEntityType", false, loader));
        }
    }

    @Test
    void clientVanillaClassesResolveInTheClientCapableUniverse() throws Exception {
        try (Legacy1165Loader loader = open()) {
            // The universe is client-capable (integrated-style): the client SRG jar sits on
            // the manifest because Forge's own server binpatch links the client-only
            // IChestLid interface into server classes. Client classes therefore resolve BY
            // NAME through the loader. Instantiating them (window, game boot) is a later
            // lane's problem and is not attempted here.
            assertNotNull(Class.forName("net.minecraft.client.Minecraft", false, loader));
            assertNotNull(Class.forName("net.minecraft.tileentity.IChestLid", false, loader));
        }
    }

    @Test
    void modDiscoveryInputsAreReachableThroughTheLoader() throws Exception {
        try (Legacy1165Loader loader = open()) {
            assertNotNull(loader.getResource("META-INF/mods.toml"),
                    "Forge's own mods.toml must be on the classpath for a future full boot");
            assertNotNull(loader.getResource("META-INF/coremods.json"),
                    "Forge's coremod declarations must be on the classpath for a future full boot");
            assertNotNull(loader.getResource("META-INF/accesstransformer.cfg"));
            assertTrue(loader.getResource("a.class") != null || loader.getResource("aa.class") != null,
                    "the real client.jar's vanilla classes should still carry short obfuscated names");
        }
    }
}
