package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * era-1165-client-facade gate: boots the REAL ModLoader lifecycle with the full live
 * five-mod set (Immersive Engineering, Alex's Mobs, Citadel, Torchmaster, IronChest)
 * under Dist=CLIENT and asserts the whole client-setup pass completes.
 *
 * in some class init, past the resource-manager/key-binding singletons the placeholder
 * already carries. The headless client facade
 * ({@code Legacy1165Lifecycle#installHeadlessMinecraftPlaceholder}) must grow exactly the
 * this boot is green - and stay opt-in behind {@code -Dumb.1165.dist=CLIENT} meanwhile.
 *
 * <p>Self-skips (Assumptions) when any real jar is not present, same convention as
 * {@code M1165ProbeTest}/{@code EntityRenderClientProbeTest}.
 */
class FiveModClientBootTest {
    /** The client-dist pass is opt-in; this test exercises it. */
    private String previousDist;

    @org.junit.jupiter.api.BeforeEach
    void enableClientDist() {
        previousDist = System.setProperty("umb.1165.dist", "CLIENT");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreDist() {
        if (previousDist == null) System.clearProperty("umb.1165.dist");
        else System.setProperty("umb.1165.dist", previousDist);
    }

    @Test
    void allFiveLiveModsCompleteClientSetupUnderDistClient() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: "
                    + e.getMessage());
            return;
        }
        File forgeJar = new File(repo, "research/out/legacy-1165/forge-1.16.5-36.2.34-universal.jar");
        File ironchestJar = new File(repo, "research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar");
        File citadelJar = new File(System.getProperty("umb.citadel.jar",
                "research/mods-1165/citadel-1.8.1-1.16.5.jar"));
        File alexsmobsJar = new File(System.getProperty("umb.alexsmobs.jar",
                "research/mods-1165/alexsmobs-1.12.1.jar"));
        File torchmasterJar = new File(System.getProperty("umb.torchmaster.jar",
                "research/mods-1165/torchmaster-2.3.8.jar"));
        File ieJar = new File(System.getProperty("umb.ie.jar",
                "research/mods-1165/ImmersiveEngineering-1.16.5-5.1.0-148.jar"));
        assumeTrue(forgeJar.isFile() && citadelJar.isFile() && alexsmobsJar.isFile()
                        && torchmasterJar.isFile() && ironchestJar.isFile() && ieJar.isFile(),
                "forge/citadel/alexsmobs/torchmaster/ironchest/IE jars missing - skipping");
        System.setProperty("umb.1165.modjars", forgeJar.getAbsolutePath() + ";"
                + citadelJar.getAbsolutePath() + ";" + alexsmobsJar.getAbsolutePath() + ";"
                + torchmasterJar.getAbsolutePath() + ";" + ironchestJar.getAbsolutePath() + ";"
                + ieJar.getAbsolutePath());
        System.setProperty("umb.1165.gamedir",
                System.getProperty("java.io.tmpdir") + "/umb-legacy1165-gamedir-fivemod-test");

        // Mod jars must ride the isolated loader's own URLs (child-first probing), same
        // convention as EntityRenderClientProbeTest.
        List<File> withModJars = new ArrayList<File>(files);
        withModJars.add(citadelJar);
        withModJars.add(alexsmobsJar);
        withModJars.add(torchmasterJar);
        withModJars.add(ironchestJar);
        withModJars.add(ieJar);
        URL[] urls = Legacy1165Classpath.toUrls(withModJars);
        try (Legacy1165Loader loader =
                new Legacy1165Loader(urls, FiveModClientBootTest.class.getClassLoader())) {
            Class<?> probe = Class.forName(
                    "dev.umb.legacy1165.legacyside.EntityRenderClientProbe", true, loader);
            Method run = probe.getMethod("run");
            String result = String.valueOf(run.invoke(null));
            assertNotNull(result);
            assertTrue(result.startsWith("PROBE-OK"), "five-mod client-dist boot failed:\n"
                    + result);

            Matcher m = Pattern.compile("entityRendererFactories=(-?\\d+)").matcher(result);
            assertTrue(m.find(), "no entityRendererFactories line in report:\n" + result);
            assertTrue(Integer.parseInt(m.group(1)) > 0,
                    "expected entity renderer factories from the five-mod set, got "
                            + m.group(1) + ":\n" + result);

            Matcher t = Pattern.compile("tesrRenderers=(-?\\d+)").matcher(result);
            assertTrue(t.find(), "no tesrRenderers line in report:\n" + result);
            assertTrue(Integer.parseInt(t.group(1)) > 0,
                    "expected TESR registrations (IronChest binds 8), got "
                            + t.group(1) + ":\n" + result);

            // Gap (b): vanilla renderers must pre-populate the manager so
            // loadEntityRenderers' trailing validateRendererExistence passes and the
            // capture layer actually installs (not just factories counted).
            assertTrue(result.contains("entityCaptureInstalled=true"),
                    "expected entity capture installed (vanilla + modded manager), report:\n"
                            + result);
        }
    }
}
