package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
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

import dev.umb.legacy1122.boot.Legacy1122Loader;

/**
 * Same discipline as the 1.7.10 module's {@code LegacyLoaderTest}: the delegation policy is tested
 * against SYNTHETIC jars, not the real fetched 1.12.2 ones, so this suite runs identically whether
 * or not {@code research/out/legacy-1122} has been populated by the fetch step, and fails loudly if
 * the policy ever regresses (e.g. net.minecraft.* becoming parent-first, which would leak the
 * host's 26.2 classes into the legacy universe).
 */
class Legacy1122LoaderTest {

    @Test
    void netMinecraftIsChildFirst() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child.jar"), "net/minecraft/block/Block", 1);
        Path parentJar = jarWith(tmp.resolve("parent.jar"), "net/minecraft/block/Block", 2);

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             Legacy1122Loader child = new Legacy1122Loader(new URL[]{childJar.toUri().toURL()}, parent)) {

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
        Path childJar = jarWith(tmp.resolve("evil.jar"), "java/lang/Boxed1122", 7);
        try (Legacy1122Loader child = new Legacy1122Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1122LoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("java.lang.String"));
            assertTrue(child.isParentDelegated("java.util.Map"));
            assertSame(String.class, child.loadClass("java.lang.String"));
            assertTrue(child.owns("java.lang.Boxed1122"));
            assertTrue(child.isParentDelegated("java.lang.Boxed1122"));
        }
    }

    /** The shared cross-era boundary contract must resolve to the SAME Class objects both sides. */
    @Test
    void bridgeApiAndLaunchWrapperAreShared() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child2.jar"), "dev/umb/legacy1122/api/Sneak", 3);
        try (Legacy1122Loader child = new Legacy1122Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1122LoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("dev.umb.legacy1122.api.StageResult"));
            assertTrue(child.isParentDelegated("net.minecraft.launchwrapper.LaunchClassLoader"));
            assertTrue(child.isParentDelegated("net.minecraft.launchwrapper.Launch"));
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.LegacyBridge"));
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.StackData"));
            assertFalse(child.isParentDelegated("dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl"));

            assertSame(dev.umb.legacy1122.api.StageResult.class,
                    child.loadClass("dev.umb.legacy1122.api.StageResult"));
            assertSame(net.minecraft.launchwrapper.LaunchClassLoader.class,
                    child.loadClass("net.minecraft.launchwrapper.LaunchClassLoader"));
            assertSame(dev.umb.bridge.api.LegacyBridge.class,
                    child.loadClass("dev.umb.bridge.api.LegacyBridge"));
        }
    }

    @Test
    void unknownClassesFallThroughToTheParent() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child3.jar"), "some/pkg/Owned1122", 5);
        try (Legacy1122Loader child = new Legacy1122Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1122LoaderTest.class.getClassLoader())) {

            assertFalse(child.owns("org.junit.jupiter.api.Test"));
            assertSame(Test.class.getClassLoader(),
                    child.loadClass("org.junit.jupiter.api.Test").getClassLoader());
            assertTrue(child.owns("some.pkg.Owned1122"));
            assertSame(child, child.loadClass("some.pkg.Owned1122").getClassLoader());
        }
    }

    @Test
    void launchWrapperDefaultExclusionsAreNarrowed() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child4.jar"), "org/lwjgl/opengl/Fake1122", 9);
        try (Legacy1122Loader child = new Legacy1122Loader(new URL[]{childJar.toUri().toURL()},
                Legacy1122LoaderTest.class.getClassLoader())) {

            assertFalse(child.isParentDelegated("org.lwjgl.opengl.Fake1122"));
            assertSame(child, child.loadClass("org.lwjgl.opengl.Fake1122").getClassLoader());
            assertTrue(child.isParentDelegated("org.apache.logging.log4j.Level"));
            assertTrue(child.isParentDelegated("sun.misc.Unsafe"));
            assertTrue(child.isParentDelegated("com.sun.management.OperatingSystemMXBean"));
        }
    }

    private static Path jarDir() throws Exception {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "umb-legacy1122-loader-test");
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
