package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * The round-4 vertical gate: runs {@code M1165Probe} (boot -> place -> activate -> 54 slots
 * -> put/take -> tick -> NBT) inside the isolated universe and asserts {@code M1165-OK}.
 * Needs the real fetched jars (self-skips without them). Takes ~30s (a full ModLoader boot) -
 */
class M1165ProbeTest {
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
    void liveUniverseVerticalIsOk() throws Exception {
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = Legacy1165Classpath.forDist(
                    Legacy1165Classpath.readManifest(repo, manifest), "CLIENT");
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: "
                    + e.getMessage());
            return;
        }
        File forgeJar = new File(repo, "research/out/legacy-1165/forge-1.16.5-36.2.34-universal.jar");
        File modJar = new File(repo, "research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar");
        assumeTrue(forgeJar.isFile() && modJar.isFile(), "forge/mod jars missing - skipping");
        Legacy1165BridgeImplTest.setModJarsProperty();

        URL[] urls = Legacy1165Classpath.toUrls(files);
        try (Legacy1165Loader loader =
                new Legacy1165Loader(urls, M1165ProbeTest.class.getClassLoader())) {
            Class<?> probe = Class.forName("dev.umb.legacy1165.legacyside.M1165Probe", true, loader);
            Method run = probe.getMethod("run");
            String result = String.valueOf(run.invoke(null));
            assertNotNull(result);
            assertTrue(result.startsWith("M1165-OK"), "M1165 vertical failed:\n" + result);
            // era-1165-client-pass: Dist=CLIENT (see Legacy1165Lifecycle's class javadoc) lets
            // IronChest's own DistExecutor-gated client-setup listener register its TESR, so the
            // dispatcher must now resolve the REAL IronChestTileEntityRenderer for this tile -
            // "no renderer" / "dispatcher unavailable" would mean the registration itself never
            // happened. Actual vertex output is a SEPARATE, deeper claim this probe does not
            // assert on: real-jar tracing (era-1165-client-pass.md) found that renderer calls
            // Minecraft.getModelManager().getAtlasTexture(...) (RenderMaterial's texture/sprite
            // resolution) to get an IVertexBuilder at all, which needs a working texture-atlas
            // subsystem this universe does not build - a documented, separate residual gap
            // (a headless resource-manager facade), not a client-dist registration problem.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("tesrCapture: stateKey=(\\S+)")
                    .matcher(result);
            assertTrue(m.find(), "M1165 report has no tesrCapture line:\n" + result);
            String stateKey = m.group(1);
            // stateKey names the resolved renderer whether the render call succeeded
            // ("1165-tesr:<class>") or later threw ("1165-tesr-threw:<class>:<cause>") - either
            // form proves dispatch found the REAL IronChestTileEntityRenderer; only
            // "renderer-missing"/"dispatcher-unavailable" would mean registration itself failed.
            assertTrue(stateKey.contains("IronChestTileEntityRenderer"),
                    "expected the real dispatcher to resolve IronChestTileEntityRenderer, got "
                            + stateKey + ":\n" + result);
            // era-1165-client-facade, gap (a): the headless atlas (real mod-jar PNGs,
            // demand-filled) must let the real renderer EMIT geometry, not just resolve.
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("draws=(\\d+)")
                    .matcher(result);
            assertTrue(d.find(), "M1165 report has no draws count:\n" + result);
            assertTrue(Integer.parseInt(d.group(1)) > 0,
                    "expected the real IronChest renderer to emit draws (headless atlas), got "
                            + d.group(1) + ":\n" + result);
        }
    }
}
