package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.legacy1122.boot.Legacy1122Classpath;
import dev.umb.legacy1122.boot.Legacy1122Loader;
import dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl;

/**
 * Exercises the 1.12.2 era's {@code LegacyBridge} implementation against the SAME contract the
 * 1.7.10 era speaks. {@link Legacy1122BridgeImpl#boot} genuinely needs real Forge 1.12.2 classes
 * reachable from this test's own classloader (run-tests.ps1 puts the manifest jars on the JUnit
 * console launcher's classpath for exactly this reason) - if they are not there, the boot test
 * self-skips rather than failing, so this suite still runs green from a checkout that has not run
 * the fetch step.
 */
class Legacy1122BridgeImplTest {

    private static boolean forgeReachable() {
        try {
            Class.forName("net.minecraftforge.fml.common.Loader");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void bootSucceedsWhenRealForgeClassesAreReachable() throws Exception {
        assumeTrue(forgeReachable(), "real Forge 1.12.2 classes not on the test classpath - skipping");
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        assertFalse(bridge.isBooted());
        FakeHostWorld world = new FakeHostWorld();
        bridge.boot(world);
        assertTrue(bridge.isBooted());
        assertTrue(world.logs.stream().anyMatch(s -> s.contains("UMB-BRIDGE-1122")));
        assertNull(bridge.bootFailure());
        bridge.shutdown();
        assertFalse(bridge.isBooted());
    }

    /**
     * The REAL per-mod check: {@link Legacy1122BridgeImpl#boot} only drives the full FML lifecycle
     * (Legacy1122Lifecycle - construct/preInit/registry/init/postInit, including
     * {@link dev.umb.legacy1122.legacyside.Legacy1122CoremodLoader}'s coremod discovery) when its
     * OWN classloader is a real isolated {@link Legacy1122Loader}; run through a flat classpath (as
     * {@link #bootSucceedsWhenRealForgeClassesAreReachable} above does) it takes the "Forge classes
     * reachable" shortcut and never touches umb.1122.modjars at all. The surprise-test harness
     * for every non-skipped 1.12.2 pick, so EVERY "loaded" verdict there was a false positive: the
     * picked mod's jar was never even opened. This test drives the isolated path for real, gated on
     * the same {@code umb.1122.modjars} system property the harness already sets - point
     * run-headless.ps1 at this method instead to get a verdict that actually depends on which mod
     * was picked.
     */
    @Test
    void bootSucceedsInIsolatedUniverseWithModJars() throws Exception {
        String modjars = System.getProperty("umb.1122.modjars", "");
        assumeTrue(!modjars.trim().isEmpty(), "no umb.1122.modjars set - skipping isolated boot");
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        assumeTrue(manifest.isFile(), "classpath-1122.txt not present - skipping");
        File legacyside = new File(repo, "umb-legacy-1122/build/umb-legacy1122-legacyside.jar");
        assumeTrue(legacyside.isFile(),
                "umb-legacy1122-legacyside.jar not built - run umb-legacy-1122/build.ps1 first");
        List<File> files;
        try {
            files = new ArrayList<File>(Legacy1122Classpath.readManifest(repo, manifest));
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1122.txt is missing - skipping: " + e.getMessage());
            return;
        }
        // The isolated loader must OWN the legacyside classes (child-first) for
        // Legacy1122BridgeImpl.boot()'s isolated-mode check to trigger - classpath-1122.txt
        // deliberately excludes them (RealForgeJarsProbeTest above only probes raw Forge/vanilla
        // reachability, never loads our own bridge classes through the isolated loader).
        files.add(legacyside);
        URL[] urls = Legacy1122Classpath.toUrls(files);
        try (Legacy1122Loader loader = new Legacy1122Loader(urls, getClass().getClassLoader())) {
            Class<?> bridgeClass = Class.forName("dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl", true, loader);
            assertSame(loader, bridgeClass.getClassLoader(),
                    "bridge leaked to another loader - the isolated-mode branch in boot() would never run");
            Object bridge = bridgeClass.getDeclaredConstructor().newInstance();
            FakeHostWorld world = new FakeHostWorld();
            Method boot = bridgeClass.getMethod("boot", HostWorld.class);
            try {
                boot.invoke(bridge, world);
            } catch (java.lang.reflect.InvocationTargetException bootFailure) {
                Throwable real = bootFailure.getCause() != null ? bootFailure.getCause() : bootFailure;
                throw new AssertionError("isolated boot failed with modjars=" + modjars + ": " + real, real);
            }
            Method isBooted = bridgeClass.getMethod("isBooted");
            assertEquals(Boolean.TRUE, isBooted.invoke(bridge));
            Method bootFailureMethod = bridgeClass.getMethod("bootFailure");
            assertNull(bootFailureMethod.invoke(bridge), "bootFailure(): " + world.logs);
            assertTrue(world.logs.stream().anyMatch(s -> s.contains("UMB-BRIDGE-1122 live universe booted")),
                    "expected a real isolated boot, got: " + world.logs);
            if (modjars.contains("mixinbooter")) {
                assertMixinApplied(loader);
            }
        }
    }

    /**
     * constraint fix in Legacy1122Lifecycle.installForgeTransformers makes the boot SUCCEED even if
     * MixinBooter's own coremod never got as far as actually applying a mixin (a config could fail
     * to register, MixinBootstrap could silently no-op, etc). MixinBooter's own bundled
     * {@code mixin.mixinbooter.init.json} (verified: unzip -p on the fetched
     * !mixinbooter-11.17.jar) declares {@code ASMModParserMixin} against
     * {@code net.minecraftforge.fml.common.discovery.asm.ASMModParser}, applied at
     * {@code @env(PREINIT)} - a phase this era's lifecycle drives (see its own javadoc: preinit is
     * part of the sequence). Real Sponge Mixin marks every method it merges from a mixin with
     * {@code org.spongepowered.asm.mixin.transformer.meta.MixinMerged} (verified: that class is
     * bundled inside !mixinbooter-11.17.jar itself, under its shaded
     * org.spongepowered.asm.mixin.transformer.meta package) - checking for that annotation BY NAME
     * (not by loading the annotation class ourselves, which would reopen the exact cross-loader
     * identity question wall 3 was about) is direct bytecode-level evidence a mixin transformation
     * actually landed on the target class, not just that the boot sequence did not crash.
     *
     * <p><b>Passes as of this commit</b> (see REPORT.md "MixinBooter walls 3-5" for the full trace).
     * Wall 3 (this file's own regression, a JDK loader-constraint LinkageError inside
     * ForgeModContainer's event-bus setup), wall 4 (MixinBooter's own addClassLoaderExclusion calls
     * routing its packages to a parent that does not have them, so MixinBootstrap.init() never ran),
     * and wall 5 (fixing wall 4 with a plain no-op let Mixin's own MixinTransformer start running
     * ITS OWN couldTransformClass check against Mixin's own internal classes, causing a
     * ClassCircularityError on MixinEnvironment's own CompatibilityLevel enum the first time it
     * self-initialized, which then surfaced as an unrelated IncompatibleClassChangeError against
     * Forge's binary-patch library the next time something else hit the same transformer's blast
     * radius) are all fixed - see Legacy1122Loader.addClassLoaderExclusion's own javadoc for the
     * final shape of that fix (redirect into addTransformerExclusion, not a no-op). Proven live:
     * this assertion finds {@code ASMModParser.wrapOperation$zza000$mixinbooter
     * $suppressClassReaderWhenReadingNewClasses(...)} annotated with
     * {@code @MixinMerged(mixin="zone.rong.mixinbooter.mixin.ASMModParserMixin", priority=1000)} -
     * a real mixin, from a real mod, actually merged into a real target class.</p>
     */
    private static void assertMixinApplied(Legacy1122Loader loader) throws Exception {
        Class<?> target = Class.forName(
                "net.minecraftforge.fml.common.discovery.asm.ASMModParser", true, loader);
        StringBuilder seen = new StringBuilder();
        for (Method m : target.getDeclaredMethods()) {
            for (java.lang.annotation.Annotation a : m.getDeclaredAnnotations()) {
                String annName = a.annotationType().getName();
                seen.append(m.getName()).append(':').append(annName).append(' ');
                if ("org.spongepowered.asm.mixin.transformer.meta.MixinMerged".equals(annName)) {
                    return;
                }
            }
        }
        throw new AssertionError("expected at least one MixinMerged-annotated method on "
                + target.getName() + " after MixinBooter's ASMModParserMixin should have applied "
                + "(@env(PREINIT)) - got annotations: " + seen);
    }

    /**
     * Mirrors {@code dev.umb.hostagent.content.Legacy1122Universe.boot()}'s EXACT parent-classloader
     * composition - the one thing {@link #bootSucceedsInIsolatedUniverseWithModJars} above does NOT
     * reproduce (its parent is simply this test's own classloader, which - unlike the real host -
     * has {@code umb-legacy1122-boot.jar} AND {@code umb-legacy1122-legacyside.jar} both directly on
     * it). That gap is exactly how a real regression slipped past every other test in this module:
     * {@code FMLServerTweaker} was built in the {@code boot} package, worked in every headless test
     * here, and threw {@code ClassNotFoundException} live - {@code Legacy1122Universe}'s real parent
     * is the HOST APPLICATION's own classloader, which has neither {@code boot.jar} nor
     * {@code legacyside.jar} merged in - only {@code dev.umb.bridge.api} (a direct
     * {@code umb-hostagent} compile dependency, confirmed by {@code Legacy1122Universe}'s own
     * imports) and, conservatively, NOT {@code dev.umb.legacy1122.api} either (nothing in
     * {@code Legacy1122Universe} references it, so its real availability on the host classpath is
     * unconfirmed - if some future change genuinely needs it, this test will say so precisely,
     * instead of that change discovering it live).
     *
     * <p>Any {@code Class.forName(<literal>, true, loader)} call anywhere in this era's legacyside
     * code that resolves - via {@code Legacy1122Loader.ALWAYS_PARENT} - to the parent for a class
     * the real host does not have fails here exactly like it failed live, and nowhere else in this
     * test module. No third-party mod jar is needed: the coremod-discovery/transformer setup this
     * regression lived in runs for every boot, mod or no mod, so a forge-only boot already exercises
     * it.</p>
     */
    @Test
    void bootSucceedsWithTheRealHostClasspathSplit() throws Exception {
        assumeTrue(forgeReachable(), "real Forge 1.12.2 classes not on the test classpath - skipping");
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        assumeTrue(manifest.isFile(), "classpath-1122.txt not present - skipping");
        File legacyside = new File(repo, "umb-legacy-1122/build/umb-legacy1122-legacyside.jar");
        File bootJar = new File(repo, "umb-legacy-1122/build/umb-legacy1122-boot.jar");
        assumeTrue(legacyside.isFile() && bootJar.isFile(),
                "umb-legacy1122-{legacyside,boot}.jar not built - run umb-legacy-1122/build.ps1 first");
        File forgeJar = new File(repo, "research/out/legacy-1122/forge-1.12.2-14.23.5.2860-universal.jar");
        assumeTrue(forgeJar.isFile(), "forge universal jar not fetched - skipping");
        List<File> files;
        try {
            files = new ArrayList<File>(Legacy1122Classpath.readManifest(repo, manifest));
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1122.txt is missing - skipping: " + e.getMessage());
            return;
        }
        // Matches Legacy1122Universe.isolatedUrls(): the isolated loader's OWN sources carry both
        // legacyside AND boot in production too - harmlessly inert here for ALWAYS_PARENT-prefixed
        // names, since Legacy1122Loader checks isParentDelegated() before owns(). Proving that is
        // exactly the point: if this test passed only because boot.jar/legacyside.jar happen to
        // ALSO be on the isolated loader's own sources, it would prove nothing; they are included
        // here for realism, not as an escape hatch.
        files.add(legacyside);
        files.add(bootJar);
        URL[] urls = Legacy1122Classpath.toUrls(files);
        ClassLoader hostLike = new HostClasspathStandIn(getClass().getClassLoader(),
                "dev.umb.legacy1122.boot.", "dev.umb.legacy1122.legacyside.", "dev.umb.legacy1122.api.");
        String previousModJars = System.getProperty("umb.1122.modjars");
        System.setProperty("umb.1122.modjars", forgeJar.getAbsolutePath());
        try (Legacy1122Loader loader = new Legacy1122Loader(urls, hostLike)) {
            Class<?> bridgeClass = Class.forName("dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl", true, loader);
            assertSame(loader, bridgeClass.getClassLoader(),
                    "bridge leaked to another loader - the isolated-mode branch in boot() would never run");
            Object bridge = bridgeClass.getDeclaredConstructor().newInstance();
            FakeHostWorld world = new FakeHostWorld();
            Method boot = bridgeClass.getMethod("boot", HostWorld.class);
            boot.invoke(bridge, world);
            Method isBooted = bridgeClass.getMethod("isBooted");
            assertEquals(Boolean.TRUE, isBooted.invoke(bridge));
            Method bootFailureMethod = bridgeClass.getMethod("bootFailure");
            assertNull(bootFailureMethod.invoke(bridge), "bootFailure(): " + world.logs);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("boot failed under the REAL host classpath split - a "
                    + "class reachable in every other test in this module is NOT reachable from the "
                    + "real host (see this test's own javadoc for exactly what that means)",
                    e.getCause() != null ? e.getCause() : e);
        } finally {
            if (previousModJars == null) System.clearProperty("umb.1122.modjars");
            else System.setProperty("umb.1122.modjars", previousModJars);
        }
    }

    /**
     * Stands in for {@code Legacy1122Universe}'s real parent (the host application's own
     * classloader): delegates everything to the real test classloader EXCEPT the given prefixes,
     * which it refuses outright with {@code ClassNotFoundException} - simulating "this class
     * genuinely is not on the real host's classpath", not merely "this class happens not to be
     * requested by this test".
     */
    private static final class HostClasspathStandIn extends ClassLoader {
        private final ClassLoader delegate;
        private final String[] blockedPrefixes;

        HostClasspathStandIn(ClassLoader delegate, String... blockedPrefixes) {
            super(null);
            this.delegate = delegate;
            this.blockedPrefixes = blockedPrefixes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : blockedPrefixes) {
                if (name.startsWith(prefix)) {
                    throw new ClassNotFoundException(name + " - deliberately blocked: the real host "
                            + "classpath (Legacy1122Universe's own classloader) does not have this "
                            + "package either, only dev.umb.bridge.api (bridge-api.jar, a direct "
                            + "umb-hostagent dependency)");
                }
            }
            Class<?> c = delegate.loadClass(name);
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }

        @Override
        public URL getResource(String name) {
            String asClassName = name.replace('/', '.');
            for (String prefix : blockedPrefixes) {
                if (asClassName.startsWith(prefix)) {
                    return null;
                }
            }
            return delegate.getResource(name);
        }
    }

    @Test
    void bootThrowsClearlyWhenForgeClassesAreNotReachable() {
        assumeTrue(!forgeReachable(), "this test only makes sense without Forge on the classpath");
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> bridge.boot(new FakeHostWorld()));
        assertTrue(e.getMessage().contains("not reachable"));
    }

    @Test
    void everyOtherMethodIsAnHonestStubThatNeverThrows() {
        Legacy1122BridgeImpl bridge = new Legacy1122BridgeImpl();
        assertNull(bridge.createTile("modid:block", 0, 0, 0));

        ActivationResult ar = bridge.activate("modid:block", 0, 0, 0, new FakeHostPlayer(), 0, 0.5f, 0.5f, 0.5f);
        assertEquals(ActivationResult.DECLINED, ar);
        assertFalse(ar.handled);

        bridge.clicked("modid:block", 0, 0, 0, new FakeHostPlayer());
        bridge.tickTile(null);
        bridge.placedBy("modid:block", 0, 0, 0, new FakeHostPlayer());
        bridge.added("modid:block", 0, 0, 0);
        bridge.neighborChanged("modid:block", 0, 0, 0, "minecraft:air");
        bridge.broken("modid:block", 0, 0, 0, 0, new FakeHostPlayer());
        assertTrue(bridge.canPlaceAt("modid:block", 0, 0, 0));

        assertNull(bridge.useItemRightClick("modid:item", new FakeHostPlayer()));
        ItemUseResult iur = bridge.useItemOnBlock("modid:item", new FakeHostPlayer(), 0, 0, 0, 0, 0.5f, 0.5f, 0.5f);
        assertEquals(ItemUseResult.DECLINED, iur);
    }

    private static final class FakeHostWorld implements HostWorld {
        final List<String> logs = new ArrayList<>();
        public boolean isRemote() { return false; }
        public long getTotalTime() { return 0; }
        public String getBlockId(int x, int y, int z) { return "minecraft:air"; }
        public int getMeta(int x, int y, int z) { return 0; }
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) { }
        public void setMeta(int x, int y, int z, int meta, int flags) { }
        public void removeBlock(int x, int y, int z) { }
        public void markBlockDirty(int x, int y, int z) { }
        public void scheduleTick(int x, int y, int z, int delay) { }
        public long randomSeed() { return 0; }
        public void log(String msg) { logs.add(msg); }
    }

    private static final class FakeHostPlayer implements HostPlayer {
        public String getName() { return "tester"; }
        public boolean isSneaking() { return false; }
        public double getX() { return 0; }
        public double getY() { return 0; }
        public double getZ() { return 0; }
        public dev.umb.bridge.api.StackData getHeldItem() { return dev.umb.bridge.api.StackData.EMPTY; }
        public void setHeldItem(dev.umb.bridge.api.StackData s) { }
        public void sendMessage(String text) { }
        public dev.umb.bridge.api.StackData getInventorySlot(int i) { return dev.umb.bridge.api.StackData.EMPTY; }
        public void setInventorySlot(int i, dev.umb.bridge.api.StackData s) { }
        public int getInventorySize() { return 36; }
        public double getMotionX() { return 0; }
        public double getMotionY() { return 0; }
        public double getMotionZ() { return 0; }
        public void setMotion(double mx, double my, double mz) { }
        public void hurt(String legacyDamageType, float amount) { }
    }
}
