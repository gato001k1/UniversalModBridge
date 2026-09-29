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
 * SYNTHETIC jars rather than the real 1.7.10 ones: the test must fail loudly if someone later makes
 * net.minecraft.* parent-first (which would let 26.2's net.minecraft.* leak into the legacy
 * universe) or makes java.* child-first (which the JVM would reject at define time).
 *
 * <p>Every loader is closed explicitly, and the synthetic jars live in a fixed scratch dir rather
 * than a JUnit temp dir - see {@link #jarDir()}.</p>
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
            // on both sides of the 26.2 embedding - see g2-design/DESIGN.md "THE BOUNDARY CONTRACT"
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
     * Proves {@code LegacyLoader.addClassLoaderExclusion}'s fix
     * calling it must keep the excluded name self-defined by this loader (not parent-delegated -
     * this loader has to stay the sole definer of its own universe) while still skipping every
     * registered transformer for that name (the actual protection a real coremod's exclusion call
     * exists to get). Proven behaviorally with a counting transformer rather than by inspecting
     * private state: a class under the excluded prefix must load without bumping the transformer's
     * own counter, while an otherwise-identical class NOT under that prefix must bump it - so this
     * fails loudly if the override ever regresses to a no-op (wall 4's mistake) or to honoring the
     * exclusion as real parent delegation (which would break identity for the excluded class).
     */
    @Test
    void addClassLoaderExclusionSkipsTransformationButKeepsSelfDefinition() throws Exception {
        Path tmp = jarDir();
        Path jar = tmp.resolve("exclusion-fixture.jar");
        writeMultiClassJar(jar,
                "test/pkg/CountingTransformer", countingTransformerBytes(),
                "test/pkg/excluded/Target", clazz("test/pkg/excluded/Target", 1),
                "test/pkg/included/Target", clazz("test/pkg/included/Target", 2));

        try (LegacyLoader child = new LegacyLoader(new URL[]{jar.toUri().toURL()},
                LegacyLoaderTest.class.getClassLoader())) {
            child.registerTransformer("test.pkg.CountingTransformer");
            child.addClassLoaderExclusion("test.pkg.excluded.");

            Class<?> excluded = child.loadClass("test.pkg.excluded.Target");
            assertSame(child, excluded.getClassLoader(),
                    "the excluded name must still be defined by this loader, not the parent");
            assertTrue(child.owns("test.pkg.excluded.Target"));
            assertFalse(child.isParentDelegated("test.pkg.excluded.Target"),
                    "addClassLoaderExclusion must not add to classLoaderExceptions");

            Class<?> transformerClass = Class.forName("test.pkg.CountingTransformer", true, child);
            int countAfterExcluded = transformerClass.getField("COUNT").getInt(null);
            assertEquals(0, countAfterExcluded,
                    "the excluded class must never reach the registered transformer");

            child.loadClass("test.pkg.included.Target");
            int countAfterIncluded = transformerClass.getField("COUNT").getInt(null);
            assertTrue(countAfterIncluded > countAfterExcluded,
                    "a non-excluded class must still reach the registered transformer "
                            + "(otherwise this test cannot tell exclusion from a dead transformer)");
        }
    }

    /** {@code public static int COUNT;  public byte[] transform(String,String,byte[]) { COUNT++; return arg3; } } */
    private static byte[] countingTransformerBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/CountingTransformer", null,
                "java/lang/Object", new String[]{"net/minecraft/launchwrapper/IClassTransformer"});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "COUNT", "I", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "transform",
                "(Ljava/lang/String;Ljava/lang/String;[B)[B", null, null);
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "test/pkg/CountingTransformer", "COUNT", "I");
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTSTATIC, "test/pkg/CountingTransformer", "COUNT", "I");
        m.visitVarInsn(Opcodes.ALOAD, 3);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(2, 4);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeMultiClassJar(Path jar, Object... nameAndBytesPairs) throws Exception {
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            for (int i = 0; i < nameAndBytesPairs.length; i += 2) {
                String internalName = (String) nameAndBytesPairs[i];
                byte[] bytes = (byte[]) nameAndBytesPairs[i + 1];
                jos.putNextEntry(new JarEntry(internalName + ".class"));
                jos.write(bytes);
                jos.closeEntry();
            }
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
