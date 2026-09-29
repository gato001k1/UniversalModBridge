package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl;

/**
 * Exercises the 1.16.5 era's {@code LegacyBridge} implementation against the SAME contract the
 * 1.7.10 and 1.12.2 eras speak. The bridge MUST be constructed inside the isolated loader
 * (its own requirement - it resolves vanilla/Forge types through its defining loader), so the
 * live boot test builds the universe loader from the manifest first, exactly like the M1165
 * probe. Unbooted stub behavior is covered app-side. Anything self-skips (Assumptions) when
 * the fetched jars are absent.
 */
class Legacy1165BridgeImplTest {

    private static boolean forgeReachable() {
        try {
            Class.forName("cpw.mods.modlauncher.Launcher");
            Class.forName("net.minecraftforge.fml.ModLoader");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mod jars for a live boot (the bridge reads umb.1165.modjars, set by the scripts). */
    static void setModJarsProperty() {
        File repo = TestRepo.find();
        File forgeJar = new File(repo, "research/out/legacy-1165/forge-1.16.5-36.2.34-universal.jar");
        File modJar = new File(repo, "research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(forgeJar.isFile() && modJar.isFile(),
                "forge/mod jars missing - skipping live boot");
        System.setProperty("umb.1165.modjars",
                forgeJar.getAbsolutePath() + ";" + modJar.getAbsolutePath());
    }

    @Test
    void bootSucceedsWhenRealForgeClassesAreReachable() throws Exception {
        assumeTrue(forgeReachable(), "real ModLauncher/Forge 1.16.5 classes not on the test classpath - skipping");
        setModJarsProperty();
        // The bridge must be constructed INSIDE the isolated loader (its own requirement):
        // build the universe loader from the manifest, then boot through it, exactly like
        // the M1165 probe does. A directly-constructed bridge would resolve vanilla/Forge
        // types from the test classpath instead of the universe.
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = dev.umb.legacy1165.boot.Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: "
                    + e.getMessage());
            return;
        }
        URL[] urls = dev.umb.legacy1165.boot.Legacy1165Classpath.toUrls(files);
        try (dev.umb.legacy1165.boot.Legacy1165Loader loader =
                new dev.umb.legacy1165.boot.Legacy1165Loader(urls,
                        Legacy1165BridgeImplTest.class.getClassLoader())) {
            Class<?> bridgeClass = Class.forName(
                    "dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl", true, loader);
            Object bridge = bridgeClass.getDeclaredConstructor().newInstance();
            Method isBooted = bridgeClass.getMethod("isBooted");
            assertFalse(((Boolean) isBooted.invoke(bridge)).booleanValue());
            FakeHostWorld world = new FakeHostWorld();
            bridgeClass.getMethod("boot", HostWorld.class).invoke(bridge, world);
            assertTrue(((Boolean) isBooted.invoke(bridge)).booleanValue());
            assertTrue(world.logs.stream().anyMatch(s -> s.contains("UMB-BRIDGE-1165")));
            assertNull(bridgeClass.getMethod("bootFailure").invoke(bridge));
            bridgeClass.getMethod("shutdown").invoke(bridge);
            assertFalse(((Boolean) isBooted.invoke(bridge)).booleanValue());
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("universe boot failed", e.getCause() != null
                    ? e.getCause() : e);
        }
    }

    /**
     * The regression this guards: {@code ObfuscationReflectionHelper.findField}/{@code
     * getPrivateValue}/{@code setPrivateValue} - a normal way for a mod to reach a private/renamed
     * vanilla member (Sophisticated Backpacks does this on real 1.16.5) - used to
     * {@code UnableToFindFieldException} wrapping a bare NullPointerException on
     * {@code "this.nameBindings"} for EVERY class/field pair, because
     * {@code Legacy1165Lifecycle} never called real ModLauncher's
     * {@code NameMappingServiceHandler.bindNamingServices(...)} (see
     * {@code Legacy1165Lifecycle.bindObfuscationNameMappings}'s own javadoc for the full trace).
     * Uses a field ({@code Minecraft.field_71432_P}, the singleton instance field) already relied on
     * elsewhere in this module ({@code Legacy1165Lifecycle} itself reads it reflectively in several
     * places), so this is not inventing a new fixture - it is exercising the exact same real field
     * through the PUBLIC Forge API a mod would actually call, instead of this module's own internal
     * direct reflection.
     */
    @Test
    void obfuscationReflectionHelperFindFieldWorksAfterBoot() throws Exception {
        assumeTrue(forgeReachable(), "real ModLauncher/Forge 1.16.5 classes not on the test classpath - skipping");
        setModJarsProperty();
        File repo = TestRepo.find();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        assumeTrue(manifest.isFile(), "classpath-1165.txt not present - skipping");
        List<File> files;
        try {
            files = dev.umb.legacy1165.boot.Legacy1165Classpath.readManifest(repo, manifest);
        } catch (Exception e) {
            assumeTrue(false, "a jar listed in classpath-1165.txt is missing - skipping: "
                    + e.getMessage());
            return;
        }
        URL[] urls = dev.umb.legacy1165.boot.Legacy1165Classpath.toUrls(files);
        try (dev.umb.legacy1165.boot.Legacy1165Loader loader =
                new dev.umb.legacy1165.boot.Legacy1165Loader(urls,
                        Legacy1165BridgeImplTest.class.getClassLoader())) {
            Class<?> bridgeClass = Class.forName(
                    "dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl", true, loader);
            Object bridge = bridgeClass.getDeclaredConstructor().newInstance();
            bridgeClass.getMethod("boot", HostWorld.class).invoke(bridge, new FakeHostWorld());
            assertTrue(((Boolean) bridgeClass.getMethod("isBooted").invoke(bridge)).booleanValue());

            Class<?> orh = Class.forName(
                    "net.minecraftforge.fml.common.ObfuscationReflectionHelper", true, loader);
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, loader);
            Method findField = orh.getMethod("findField", Class.class, String.class);
            Object field = findField.invoke(null, minecraftClass, "field_71432_P");
            assertNotNull(field, "ObfuscationReflectionHelper.findField returned null");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("findField after boot failed", e.getCause() != null
                    ? e.getCause() : e);
        }
    }

    @Test
    void bootThrowsClearlyWhenNotConstructedInUniverse() {
        // The bridge refuses to boot outside its isolated loader instead of half-booting
        // against the wrong classes (app-side construction is only valid for the pre-boot
        // stub behavior covered below).
        Object bridge = newBridgeAppSideForNegativeTest();
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> invokeBoot(bridge, new FakeHostWorld()));
        assertTrue(e.getMessage().contains("Legacy1165Loader"),
                "unexpected boot refusal: " + e.getMessage());
    }

    private static Object newBridgeAppSideForNegativeTest() {
        try {
            Class<?> bridgeClass = Class.forName("dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl");
            return bridgeClass.getDeclaredConstructor().newInstance();
        } catch (Exception reflective) {
            assumeTrue(false, "bridge class not loadable here - skipping: " + reflective);
            return null;
        }
    }

    private static void invokeBoot(Object bridge, FakeHostWorld world) throws Exception {
        try {
            bridge.getClass().getMethod("boot", HostWorld.class).invoke(bridge, world);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Test
    void everyOtherMethodIsAnHonestStubThatNeverThrows() {
        Legacy1165BridgeImpl bridge = new Legacy1165BridgeImpl();
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
        assertNotNull(iur);
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
