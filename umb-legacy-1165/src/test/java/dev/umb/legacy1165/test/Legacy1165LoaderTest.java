package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy1165.boot.Legacy1165Loader;

/**
 * Same discipline as the 1.7.10 module's {@code LegacyLoaderTest} and the 1.12.2 module's
 * {@code Legacy1122LoaderTest}: the delegation policy is tested against SYNTHETIC jars, not the
 * real fetched 1.16.5 ones, so this suite runs identically whether or not
 * {@code research/out/legacy-1165} has been populated by the fetch step, and fails loudly if the
 * policy ever regresses (e.g. net.minecraft.* becoming parent-first, which would leak the host's
 * 26.2 classes into the legacy universe).
 *
 * <p>Two deliberate differences from the 1.12.2 version: there is no LaunchWrapper in the
 * ModLauncher generation (no {@code LaunchClassLoader} parent to share, no
 * {@code classLoaderExceptions} set to narrow - a plain {@code URLClassLoader} has neither), so
 * those assertions are replaced by ModLauncher-era equivalents (cpw.mods.* is child-first).</p>
 */
class Legacy1165LoaderTest {

    @Test
    void netMinecraftIsChildFirst() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child.jar"), "net/minecraft/block/Block", 1);
        Path parentJar = jarWith(tmp.resolve("parent.jar"), "net/minecraft/block/Block", 2);

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             Legacy1165Loader child = new Legacy1165Loader(new URL[]{childJar.toUri().toURL()}, parent)) {

            Class<?> fromChild = child.loadClass("net.minecraft.block.Block");
            Class<?> fromParent = parent.loadClass("net.minecraft.block.Block");

            assertSame(child, fromChild.getClassLoader());
            assertNotSame(fromChild, fromParent);
            assertEquals(1, fromChild.getDeclaredField("MARK").getInt(null));
            assertEquals(2, fromParent.getDeclaredField("MARK").getInt(null));
            assertTrue(child.owns("net.minecraft.block.Block"));
            assertFalse(child.isParentDelegated("net.minecraft.block.Block"));
        }
    }

    @Test
    void javaLangIsAlwaysParentDelegated() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("evil.jar"), "java/lang/Boxed1165", 7);
        try (Legacy1165Loader child = new Legacy1165Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1165LoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("java.lang.String"));
            assertTrue(child.isParentDelegated("java.util.Map"));
            assertSame(String.class, child.loadClass("java.lang.String"));
            assertTrue(child.owns("java.lang.Boxed1165"));
            assertTrue(child.isParentDelegated("java.lang.Boxed1165"));
        }
    }

    /** The shared cross-era boundary contract must resolve to the SAME Class objects both sides. */
    @Test
    void bridgeApiIsSharedAndModLauncherIsChildFirst() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child2.jar"), "dev/umb/legacy1165/api/Sneak", 3);
        try (Legacy1165Loader child = new Legacy1165Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1165LoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("dev.umb.legacy1165.api.StageResult"));
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.LegacyBridge"));
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.StackData"));
            assertFalse(child.isParentDelegated("dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl"));
            // ModLauncher-era: cpw.mods.* lives in OUR jars (modlauncher-8.1.3.jar), never the host's.
            assertFalse(child.isParentDelegated("cpw.mods.modlauncher.Launcher"));
            assertFalse(child.isParentDelegated("net.minecraftforge.fml.ModLoader"));

            assertSame(dev.umb.legacy1165.api.StageResult.class,
                    child.loadClass("dev.umb.legacy1165.api.StageResult"));
            assertSame(dev.umb.bridge.api.LegacyBridge.class,
                    child.loadClass("dev.umb.bridge.api.LegacyBridge"));
        }
    }

    @Test
    void unknownClassesFallThroughToTheParent() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child3.jar"), "some/pkg/Owned1165", 5);
        try (Legacy1165Loader child = new Legacy1165Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1165LoaderTest.class.getClassLoader())) {

            assertFalse(child.owns("org.junit.jupiter.api.Test"));
            assertSame(Test.class.getClassLoader(),
                    child.loadClass("org.junit.jupiter.api.Test").getClassLoader());
            assertTrue(child.owns("some.pkg.Owned1165"));
            assertSame(child, child.loadClass("some.pkg.Owned1165").getClassLoader());
        }
    }

    @Test
    void loggingIsSharedAndThereAreNoLegacyExclusionsToNarrow() throws Exception {        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child4.jar"), "org/lwjgl/opengl/Fake1165", 9);
        try (Legacy1165Loader child = new Legacy1165Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1165LoaderTest.class.getClassLoader())) {

            // No LaunchWrapper in this era means no hardcoded classLoaderExceptions to reset:
            // ownership alone decides. lwjgl lives in the vanilla set (ours), log4j is shared.
            assertFalse(child.isParentDelegated("org.lwjgl.opengl.Fake1165"));
            assertSame(child, child.loadClass("org.lwjgl.opengl.Fake1165").getClassLoader());
            assertTrue(child.isParentDelegated("org.apache.logging.log4j.Level"));
            assertTrue(child.isParentDelegated("sun.misc.Unsafe"));
            assertTrue(child.isParentDelegated("com.sun.management.OperatingSystemMXBean"));
        }
    }

    /**
     * The ModLauncher-generation hook: event classes passing through the loader are run through
     * Forge's OWN eventbus transformer (the production mechanism that gives every Event subclass
     * its public no-arg constructor + listener-list plumbing). Proves it with a SYNTHETIC event
     * that deliberately declares NO no-arg constructor: after loading, the no-arg constructor
     * (and LISTENER_LIST) must exist. The in-universe worker jar (built by build.ps1) rides along
     * as a second source, exactly like the real manifest. Self-skips when eventbus/ASM are not
     * on the test classpath (fresh checkout without the fetch step) - the degrade path (plain
     * loading) is what every other test in this class exercises.
     */
    @Test
    void eventSubclassesGainANoArgConstructorThroughTheLoader() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(eventEnginePresent(),
                "eventbus/ASM not on the test classpath - skipping transform proof");
        File helperJar = new File(TestRepo.find(), "umb-legacy-1165/build/umb-legacy1165-transform.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(helperJar.isFile(),
                "transform worker jar not built - run build.ps1 first");
        Path tmp = jarDir();
        Path childJar = jarWithEvent(tmp.resolve("event.jar"), "com/test1165/MyEvent");
        URL[] sources = new URL[]{childJar.toUri().toURL(), helperJar.toURI().toURL()};
        try (Legacy1165Loader child = new Legacy1165Loader(sources,
                Legacy1165LoaderTest.class.getClassLoader())) {

            Class<?> event = child.loadClass("com.test1165.MyEvent");
            assertSame(child, event.getClassLoader());
            // Absent from the synthetic bytecode, added by the transformer:
            assertNotNull(event.getConstructor());
            assertNotNull(event.getDeclaredField("LISTENER_LIST"));
        }
    }

    private static boolean eventEnginePresent() {
        try {
            Class.forName("net.minecraftforge.eventbus.EventBusEngine");
            Class.forName("org.objectweb.asm.tree.ClassNode");
            Class.forName("net.minecraftforge.eventbus.api.Event");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Path jarDir() throws Exception {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "umb-legacy1165-loader-test");
        Files.createDirectories(d);
        return d;
    }

    private static Path jarWith(Path jar, String internalName, int mark) throws Exception {
        byte[] bytes = clazz(internalName, mark);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry(internalName + ".class"));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }

    private static Path jarWithEvent(Path jar, String internalName) throws Exception {
        // An Event subclass with NO no-arg constructor (only a String one) - the transformer
        // must add the public no-arg constructor + LISTENER_LIST at load time.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "net/minecraftforge/eventbus/api/Event", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V",
                null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraftforge/eventbus/api/Event",
                "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 2);
        init.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry(internalName + ".class"));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }

    private static byte[] clazz(String internalName, int mark) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MARK", "I", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        cl.visitLdcInsn(Integer.valueOf(mark));
        cl.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "MARK", "I");
        cl.visitInsn(Opcodes.RETURN);
        cl.visitMaxs(1, 0);
        cl.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
