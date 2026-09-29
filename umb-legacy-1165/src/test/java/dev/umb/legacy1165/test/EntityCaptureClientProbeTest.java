package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * End-to-end CLIENT entity capture of a real modded mob through the live bridge path
 * (boot -> entity in the bridge world -> EntityHandle1165.renderCapture). Live, Alex's Mobs
 * mobs captured verts=0 while the model-only unit test passed.
 */
class EntityCaptureClientProbeTest {
    private String previousDist;
    private String previousEntity;
    private String previousModJars;
    private String previousGameDir;

    @BeforeEach
    void setUp() {
        previousDist = System.setProperty("umb.1165.dist", "CLIENT");
        previousEntity = System.setProperty("umb.1165.probe.entity", "alexsmobs:grizzly_bear");
        previousModJars = System.getProperty("umb.1165.modjars");
        previousGameDir = System.getProperty("umb.1165.gamedir");
    }

    @AfterEach
    void tearDown() {
        restore("umb.1165.dist", previousDist);
        restore("umb.1165.probe.entity", previousEntity);
        restore("umb.1165.modjars", previousModJars);
        restore("umb.1165.gamedir", previousGameDir);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void realModdedMobCaptureYieldsGeometry() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: " + e.getMessage());
            return;
        }
        File forgeJar = new File(repo, "research/out/legacy-1165/forge-1.16.5-36.2.34-universal.jar");
        File citadelJar = new File(System.getProperty("umb.citadel.jar",
                "research/mods-1165/citadel-1.8.1-1.16.5.jar"));
        File alexsmobsJar = new File(System.getProperty("umb.alexsmobs.jar",
                "research/mods-1165/alexsmobs-1.12.1.jar"));
        assumeTrue(forgeJar.isFile() && citadelJar.isFile() && alexsmobsJar.isFile(),
                "forge/citadel/alexsmobs jars missing - skipping");
        System.setProperty("umb.1165.modjars", forgeJar.getAbsolutePath() + ";"
                + citadelJar.getAbsolutePath() + ";" + alexsmobsJar.getAbsolutePath());
        System.setProperty("umb.1165.gamedir",
                System.getProperty("java.io.tmpdir") + "/umb-legacy1165-gamedir-entitycapture-test");
        // Same class sources as the live CLIENT boot (Legacy1165Universe.orderForDist).
        files = Legacy1165Classpath.forDist(files, "CLIENT");
        List<File> withModJars = new ArrayList<File>(files);
        withModJars.add(citadelJar);
        withModJars.add(alexsmobsJar);
        URL[] urls = Legacy1165Classpath.toUrls(withModJars);
        try (Legacy1165Loader loader =
                new Legacy1165Loader(urls, EntityCaptureClientProbeTest.class.getClassLoader())) {
            Class<?> probe = Class.forName(
                    "dev.umb.legacy1165.legacyside.EntityCaptureProbe1165", true, loader);
            Method run = probe.getMethod("run");
            String report = String.valueOf(run.invoke(null));
            System.out.println(report);
            Matcher m = Pattern.compile("vertices=(\\d+)").matcher(report);
            assertTrue(m.find(), report);
            assertTrue(Integer.parseInt(m.group(1)) > 0, "real mob captured no geometry:\n" + report);
            assertTrue(report.contains("texture=alexsmobs:textures/entity/"),
                    "draws should carry the mob's own texture:\n" + report);
            assertTrue(report.contains("poisoned=false"),
                    "the mob must survive 600 host ticks (AI reads game time etc.):\n" + report);
            Matcher origin = Pattern.compile("nearOriginVertices=(\\d+)").matcher(report);
            assertTrue(origin.find() && Integer.parseInt(origin.group(1)) > 0,
                    "a mob near the world origin must capture too (camera/name-tag path):\n" + report);
            // Wandering needs: world light (path scoring), the per-entity checkDespawn (idle
            // timer reset near a player) and chunks that read the host's blocks (pathfinder).
            Matcher moved = Pattern.compile("maxMoved=([0-9.]+)").matcher(report);
            assertTrue(moved.find() && Double.parseDouble(moved.group(1)) > 1.0,
                    "a mob on flat ground near a player must wander:\n" + report);
            assertTrue(report.contains("onGround=true"), "the mob must stay on the host ground:\n" + report);
            assertTrue(report.contains("serverPlayers=0"),
                    "the facade server needs a real, empty player list (mod broadcasts):\n" + report);
        }
    }
}
