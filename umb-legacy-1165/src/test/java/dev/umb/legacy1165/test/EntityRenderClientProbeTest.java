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
 * era-1165-client-pass gate: boots the REAL ModLoader lifecycle with Alex's Mobs (+ its
 * mandatory Citadel library) and Torchmaster, and asserts that Dist=CLIENT (see
 * {@code Legacy1165Lifecycle}'s class javadoc) made their own client-setup registration run for
 *
 * <ul>
 *   <li>Alex's Mobs' {@code ClientProxy} vs {@code CommonProxy} choice is made by
 *       {@code DistExecutor.runForDist(...)} at class-init time - if Dist were still
 *       DEDICATED_SERVER, {@code PROXY} would stay a {@code CommonProxy} and
 *       {@code PROXY.clientInit()} (which calls {@code RenderingRegistry
 *       .registerEntityRenderingHandler} ~25 times) would never run, however many times
 *       {@code FMLClientSetupEvent} is posted afterward.</li>
 *   <li>Torchmaster registers {@code doClientStuff} (an {@code FMLClientSetupEvent} listener)
 *       UNCONDITIONALLY in its constructor - no dist gate at all - and that handler calls
 *       {@code Minecraft.func_71410_x()} directly. This is the concrete crash the headless
 *       Minecraft placeholder (see {@code Legacy1165Lifecycle
 *       #installHeadlessMinecraftPlaceholder}) exists for: without it this boot NPEs during
 *       SIDED_SETUP instead of completing.</li>
 * </ul>
 *
 * Self-skips (Assumptions) when the real jars are not present, same convention as
 * {@code M1165ProbeTest}/{@code LegacyEntityRenderCapture1165ClientTest}.
 */
class EntityRenderClientProbeTest {
    /** The client-dist pass is opt-in; these tests exercise it. */
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
    void realAlexsMobsAndTorchmasterRegisterClientSideRenderersUnderDistClient() throws Exception {
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
        File citadelJar = new File(System.getProperty("umb.citadel.jar",
                "research/mods-1165/citadel-1.8.1-1.16.5.jar"));
        File alexsmobsJar = new File(System.getProperty("umb.alexsmobs.jar",
                "research/mods-1165/alexsmobs-1.12.1.jar"));
        File torchmasterJar = new File(System.getProperty("umb.torchmaster.jar",
                "research/mods-1165/torchmaster-2.3.8.jar"));
        assumeTrue(forgeJar.isFile() && citadelJar.isFile() && alexsmobsJar.isFile()
                        && torchmasterJar.isFile(),
                "forge/citadel/alexsmobs/torchmaster jars missing - skipping");
        System.setProperty("umb.1165.modjars", forgeJar.getAbsolutePath() + ";"
                + citadelJar.getAbsolutePath() + ";" + alexsmobsJar.getAbsolutePath() + ";"
                + torchmasterJar.getAbsolutePath());
        System.setProperty("umb.1165.gamedir",
                System.getProperty("java.io.tmpdir") + "/umb-legacy1165-gamedir-entityrender-test");

        // classpath-1165.txt only lists the era + ironchest jars (M1165ProbeTest's target);
        // Legacy1165Loader resolves dev.umb.legacy1165.legacyside.* CHILD-FIRST by probing
        // findResource against exactly these URLs (see its class javadoc), so a mod's own
        // classes are unreachable unless its jar is on this same list - the mod jars
        // themselves must ride it too, same as ironchest already does.
        List<File> withModJars = new ArrayList<File>(files);
        withModJars.add(citadelJar);
        withModJars.add(alexsmobsJar);
        withModJars.add(torchmasterJar);
        URL[] urls = Legacy1165Classpath.toUrls(withModJars);
        try (Legacy1165Loader loader =
                new Legacy1165Loader(urls, EntityRenderClientProbeTest.class.getClassLoader())) {
            Class<?> probe = Class.forName(
                    "dev.umb.legacy1165.legacyside.EntityRenderClientProbe", true, loader);
            Method run = probe.getMethod("run");
            String result = String.valueOf(run.invoke(null));
            assertNotNull(result);
            assertTrue(result.startsWith("PROBE-OK"),
                    "entity-render client-dist boot failed (see Torchmaster's unconditional "
                            + "Minecraft.func_71410_x() call and the headless placeholder in "
                            + "Legacy1165Lifecycle if this NPEs):\n" + result);

            Matcher m = Pattern.compile("entityRendererFactories=(-?\\d+)").matcher(result);
            assertTrue(m.find(), "no entityRendererFactories line in report:\n" + result);
            int factories = Integer.parseInt(m.group(1));
            assertTrue(factories > 0,
                    "expected Alex's Mobs' ClientProxy.clientInit() to register entity renderer "
                            + "factories under Dist=CLIENT, got " + factories + ":\n" + result);
        }
    }
}
