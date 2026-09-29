package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy1122.boot.Legacy1122Loader;

/**
 * Proves the fix described in {@code dev.umb.legacy1122.legacyside.Legacy1122CoremodLoader}'s own
 * javadoc: a mod jar whose manifest declares {@code FMLCorePlugin} gets addURL'd onto the isolated
 * loader, its plugin instantiated, and every ASM transformer class name
 * {@code getASMTransformerClass()} returns gets registered - using a SYNTHETIC coremod so this runs
 * {@code Legacy1122CoremodLoader} is package-private (an internal mechanism of
 * {@code Legacy1122Lifecycle}, not a public contract), so this test drives it reflectively - the
 * same way {@code Legacy1122Lifecycle} does, from inside the legacyside package.
 */
class Legacy1122CoremodLoaderTest {

    private static final String PLUGIN = "test.pkg.FakePlugin1122";
    private static final String TRANSFORMER = "test.pkg.FakeTransformer1122";

    @Test
    void discoversManifestPluginAddsUrlAndRegistersItsTransformer() throws Exception {
        Path jar = jarDir().resolve("fake-coremod-1122.jar");
        writeCoremodJar(jar);

        try (Legacy1122Loader loader = new Legacy1122Loader(new URL[0],
                Legacy1122CoremodLoaderTest.class.getClassLoader())) {
            List<File> modJars = Collections.singletonList(jar.toFile());
            List<String> log = new ArrayList<String>();

            List<String> discovered = invokeDiscoverAndRegister(loader, modJars, log::add);

            assertEquals(1, discovered.size(), "log: " + log);
            assertEquals(PLUGIN, discovered.get(0));
            boolean sawUrl = false;
            for (URL u : loader.getURLs()) {
                if (u.toString().endsWith("fake-coremod-1122.jar")) {
                    sawUrl = true;
                }
            }
            assertTrue(sawUrl, "the coremod jar must be addURL'd so its own classes resolve; log: " + log);
            List<?> transformers = loader.getTransformers();
            assertTrue(transformers.get(transformers.size() - 1).getClass().getName().equals(TRANSFORMER),
                    "log: " + log);
        }
    }

    @Test
    void jarsWithoutTheManifestAttributeAreIgnored() throws Exception {
        Path jar = jarDir().resolve("plain-mod-1122.jar");
        writePlainJar(jar);

        try (Legacy1122Loader loader = new Legacy1122Loader(new URL[0],
                Legacy1122CoremodLoaderTest.class.getClassLoader())) {
            List<File> modJars = Collections.singletonList(jar.toFile());
            List<String> discovered = invokeDiscoverAndRegister(loader, modJars, msg -> { });
            assertFalse(discovered.contains(PLUGIN));
            assertTrue(discovered.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeDiscoverAndRegister(Legacy1122Loader loader, List<File> modJars,
            Consumer<String> log) throws Exception {
        Class<?> loaderApi = Class.forName("dev.umb.legacy1122.legacyside.Legacy1122CoremodLoader", true,
                Legacy1122CoremodLoaderTest.class.getClassLoader());
        Method discover = loaderApi.getDeclaredMethod("discoverAndRegister", ClassLoader.class, List.class,
                Consumer.class);
        discover.setAccessible(true);
        return (List<String>) discover.invoke(null, loader, modJars, log);
    }

    private static Path jarDir() throws Exception {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "umb-legacy1122-coremod-loader-test");
        Files.createDirectories(d);
        return d;
    }

    private static void writeCoremodJar(Path jar) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("FMLCorePlugin", PLUGIN);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, mf)) {
            putClass(jos, "test/pkg/FakePlugin1122", pluginClassBytes());
            putClass(jos, "test/pkg/FakeTransformer1122", transformerClassBytes());
        }
    }

    private static void writePlainJar(Path jar) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, mf)) {
            putClass(jos, "test/pkg/Harmless1122", harmlessClassBytes());
        }
    }

    private static void putClass(JarOutputStream jos, String internalName, byte[] bytes) throws Exception {
        jos.putNextEntry(new JarEntry(internalName + ".class"));
        jos.write(bytes);
        jos.closeEntry();
    }

    /** public FakePlugin1122() {}  public String[] getASMTransformerClass() { return {TRANSFORMER}; } */
    private static byte[] pluginClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/FakePlugin1122", null,
                "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "getASMTransformerClass",
                "()[Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_1);
        m.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        m.visitInsn(Opcodes.DUP);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitLdcInsn(TRANSFORMER);
        m.visitInsn(Opcodes.AASTORE);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(4, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** implements net.minecraft.launchwrapper.IClassTransformer (real LaunchClassLoader.
     *  registerTransformer casts to it internally, so a class that does not implement it would
     *  register silently as a no-op - LaunchClassLoader swallows the ClassCastException - and this
     *  test would then fail on the getTransformers() growth assertion instead of proving the fix).
     *  transform() is a pass-through. */
    private static byte[] transformerClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/FakeTransformer1122", null,
                "java/lang/Object", new String[]{"net/minecraft/launchwrapper/IClassTransformer"});
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
        m.visitVarInsn(Opcodes.ALOAD, 3);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(1, 4);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] harmlessClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/Harmless1122", null,
                "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
