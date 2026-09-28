package dev.umb.legacy.test;

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

import dev.umb.legacy.boot.LegacyLoader;

/**
 * The delegation policy is the load-bearing part of the , so it is tested against SYNTHETIC jars rather than the real 1.7.10 ones: the test must fail loudly if someone later makes net.minecraft.* parent-first (which would let 26.2's net.minecraft.* leak into...
 */
class LegacyLoaderTest {

    /**
     * Child-first: a net.minecraft.* class present in BOTH loaders must come from the child.
     *
     * <p>The synthetic "parent" loader is parented to the PLATFORM loader, not
     * {@code LegacyLoaderTest.class.getClassLoader()}: this test suite's own JVM classpath now
     * also carries the REAL {@code net.minecraft.world.World} (the field-repaired vanilla jar, put
     * there for {@code UmbFacadeTest}), and {@code URLClassLoader}'s default {@code loadClass} is
     * parent-first - chaining to the app loader would silently resolve the real class instead of
     * this test's synthetic one long before reaching {@code parentJar}.</p>
     */
    @Test
    void netMinecraftIsChildFirst() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child.jar"), "net/minecraft/world/World", 1);
        Path parentJar = jarWith(tmp.resolve("parent.jar"), "net/minecraft/world/World", 2);

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             LegacyLoader child = new LegacyLoader(new URL[]{childJar.toUri().toURL()}, parent)) {

            Class<?> fromChild = child.loadClass("net.minecraft.world.World");
            Class<?> fromParent = parent.loadClass("net.minecraft.world.World");

            assertSame(child, fromChild.getClassLoader(),
                    "net.minecraft.* must be defined by the legacy loader");
            assertNotSame(fromChild, fromParent, "the two universes must not share a net.minecraft class");
            assertEquals(1, fromChild.getDeclaredField("MARK").getInt(null));
            assertEquals(2, fromParent.getDeclaredField("MARK").getInt(null));
            assertTrue(child.owns("net.minecraft.world.World"));
            assertFalse(child.isParentDelegated("net.minecraft.world.World"));
        }
    }

    /** java.* must always be the parent's, even when we ship a class of that name. */
    @Test
    void javaLangIsAlwaysParentDelegated() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("evil.jar"), "java/lang/Boxed", 7);
        try (LegacyLoader child = new LegacyLoader(new URL[]{childJar.toUri().toURL()},
                LegacyLoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("java.lang.String"));
            assertTrue(child.isParentDelegated("java.util.Map"));
            // javax.* is NOT blanket-delegated: javax.vecmath belongs to the 1.7.10 libraries.
            // It resolves by ownership instead, so an unowned javax.* falls through to the parent.
            assertFalse(child.owns("javax.script.ScriptEngine"));
            assertNotNull(child.loadClass("javax.script.ScriptEngine"));

            assertSame(String.class, child.loadClass("java.lang.String"));
            assertNotNull(child.loadClass("java.util.ArrayList"));
            // the java.* class we ship is visible as a resource but is never defined by us
            assertTrue(child.owns("java.lang.Boxed"));
            assertTrue(child.isParentDelegated("java.lang.Boxed"));
        }
    }

    /** The shared plain-data API and the LaunchWrapper types must be the HOST's single copy. */
    @Test
    void apiAndLaunchWrapperAreShared() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child2.jar"), "dev/umb/legacy/api/Sneak", 3);
        try (LegacyLoader child = new LegacyLoader(new URL[]{childJar.toUri().toURL()},
                LegacyLoaderTest.class.getClassLoader())) {

            assertTrue(child.isParentDelegated("dev.umb.legacy.api.LegacyUniverse"));
            assertTrue(child.isParentDelegated("net.minecraft.launchwrapper.LaunchClassLoader"));
            assertTrue(child.isParentDelegated("net.minecraft.launchwrapper.Launch"));
            // the G2 boundary contract (dev.umb.bridge.api) must resolve to the SAME Class objects
            // on both sides of the 26.2 embedding - see g2-design/DESIGN."THE BOUNDARY CONTRACT"
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.LegacyBridge"));
            assertTrue(child.isParentDelegated("dev.umb.bridge.api.StackData"));
            assertFalse(child.isParentDelegated("dev.umb.legacy.legacyside.LegacyDriver"));

            assertSame(dev.umb.legacy.api.LegacyUniverse.class,
                    child.loadClass("dev.umb.legacy.api.LegacyUniverse"));
            assertSame(net.minecraft.launchwrapper.LaunchClassLoader.class,
                    child.loadClass("net.minecraft.launchwrapper.LaunchClassLoader"));
        }
    }

    /** Anything we do not own falls through to the parent instead of dying in the bootstrap loader. */
    @Test
    void unknownClassesFallThroughToTheParent() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child3.jar"), "some/pkg/Owned", 5);
        try (LegacyLoader child = new LegacyLoader(new URL[]{childJar.toUri().toURL()},
                LegacyLoaderTest.class.getClassLoader())) {

            assertFalse(child.owns("org.junit.jupiter.api.Test"));
            // parent-loaded, not a ClassNotFoundException: LaunchClassLoader's super(sources, null)
            // would otherwise only see the bootstrap loader
            assertSame(Test.class.getClassLoader(),
                    child.loadClass("org.junit.jupiter.api.Test").getClassLoader());
            assertTrue(child.owns("some.pkg.Owned"));
            assertSame(child, child.loadClass("some.pkg.Owned").getClassLoader());
        }
    }

    /**
     * LaunchWrapper's Java-8-era classLoaderExceptions must not survive construction wholesale -
     * but log4j has to stay shared, because parent-delegated LogWrapper mentions it.
     */
    @Test
    void launchWrapperDefaultExclusionsAreNarrowed() throws Exception {
        Path tmp = jarDir();
        Path childJar = jarWith(tmp.resolve("child4.jar"), "org/lwjgl/opengl/Fake", 9);
        try (LegacyLoader child = new LegacyLoader(new URL[]{childJar.toUri().toURL()},
                LegacyLoaderTest.class.getClassLoader())) {

            // "org.lwjgl." is a LaunchClassLoader default exclusion; the legacy universe owns
            // lwjgl-2.9.1.jar, so it must be child-first - a host must never share GL bindings.
            assertFalse(child.isParentDelegated("org.lwjgl.opengl.Fake"));
            assertSame(child, child.loadClass("org.lwjgl.opengl.Fake").getClassLoader());

            // shared on purpose - see LegacyLoader.ALWAYS_PARENT
            assertTrue(child.isParentDelegated("org.apache.logging.log4j.Level"));
            assertTrue(child.isParentDelegated("sun.misc.Unsafe"));
            assertTrue(child.isParentDelegated("com.sun.management.OperatingSystemMXBean"));
        }
    }

    /**
     * A FIXED scratch dir, not @TempDir: on Windows LaunchClassLoader.findCodeSourceConnectionFor
     * pins the jar through the JDK JarFileFactory cache, which URLClassLoader.close() does not
     * release, so @TempDir cleanup would fail every test for the wrong reason.
     */
    private static Path jarDir() throws Exception {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "umb-legacy-loader-test");
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

    /** A class with one {@code public static int MARK} so we can tell the copies apart. */
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
