package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;
import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * failure from live game B was {@code PotionBrewingAccess.getConversions()} - a real, STATIC
 * {@code @Accessor("POTION_MIXES")} in Immersive Engineering's corpus jar - throwing
 * {@code UnsupportedOperationException("Replaced by Mixin")} because Mixin never actually ran.
 *
 * <p>This test does not boot the full ModLoader lifecycle (that is
 * {@code EntityRenderClientProbeTest}'s job for a different concern): it loads ONLY
 * {@code PotionBrewingAccess} through the REAL {@code Legacy1165Loader} (which runs every
 * child-loaded class through {@code EventTransform}, the accessor/invoker applier this session
 * extended) and calls the accessor directly, proving end to end that the stub body was replaced
 * and the call now reads the real vanilla field instead of throwing.</p>
 */
class MixinAccessorTest {

    @Test
    void realIEPotionBrewingAccessorNoLongerThrowsReplacedByMixin() throws Exception {
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
        File ieJar = new File(System.getProperty("umb.ie.jar",
                "research/mods-1165/ImmersiveEngineering-1.16.5-5.1.0-148.jar"));
        assumeTrue(ieJar.isFile(), "Immersive Engineering jar missing - skipping");

        List<File> withMod = new ArrayList<File>(files);
        withMod.add(ieJar);
        URL[] urls = Legacy1165Classpath.toUrls(withMod);
        try (Legacy1165Loader loader =
                new Legacy1165Loader(urls, MixinAccessorTest.class.getClassLoader())) {
            // GETSTATIC on PotionBrewing (the accessor's real target) triggers ITS <clinit>,
            // which reads vanilla's own Potion/Effect registries - populate them first the same
            // way Legacy1165Lifecycle does (vanilla Bootstrap, no mod loading needed for this
            Class.forName("net.minecraft.util.registry.Bootstrap", true, loader)
                    .getMethod("func_151354_b").invoke(null);
            Class<?> access = Class.forName(
                    "blusunrize.immersiveengineering.mixin.accessors.PotionBrewingAccess", true, loader);
            Method getConversions = access.getMethod("getConversions");
            Object result;
            try {
                result = getConversions.invoke(null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof UnsupportedOperationException
                        && "Replaced by Mixin".equals(cause.getMessage())) {
                    fail("PotionBrewingAccess.getConversions() still throws the un-transformed "
                            + "Mixin stub - the accessor/invoker applier did not resolve the "
                            + "static POTION_MIXES field. See mixin-support.md.", cause);
                }
                throw e;
            }
            assertNotNull(result, "getConversions() must return the real (possibly empty) list, not null");
        }
    }
}
