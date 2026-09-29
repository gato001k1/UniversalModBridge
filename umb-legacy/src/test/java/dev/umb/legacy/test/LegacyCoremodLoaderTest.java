package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy.boot.LegacyLoader;

/**
 * Proves the fix described in {@code dev.umb.legacy.legacyside.LegacyCoremodLoader}'s own javadoc:
 * a jar whose manifest declares {@code FMLCorePlugin} gets its plugin instantiated and every ASM
 * transformer class name {@code getASMTransformerClass()} returns gets registered on the loader,
 * using a SYNTHETIC coremod (no real mod jar needed, so this runs identically whether or not the
 * surprise-test corpus has been fetched). {@code LegacyCoremodLoader} itself is package-private
 * (deliberately: it is an internal mechanism of {@code LegacyDriver.boot}, not part of any public
 * contract), so this test drives it reflectively the same way {@code LegacyDriver} would, through
 * {@code Class.forName} on the SAME loader whose transformer list it mutates - the realistic path,
 * since real production code only ever calls it from inside the isolated universe too.
 */
class LegacyCoremodLoaderTest {

    private static final String PLUGIN = "test.pkg.FakePlugin";
    private static final String TRANSFORMER = "test.pkg.FakeTransformer";

    @Test
    void discoversManifestPluginAndRegistersItsTransformer() throws Exception {
        Path jar = jarDir().resolve("fake-coremod.jar");
        writeCoremodJar(jar);

        try (LegacyLoader loader = new LegacyLoader(new URL[]{jar.toUri().toURL()},
                LegacyCoremodLoaderTest.class.getClassLoader())) {
            int before = loader.getTransformers().size();

            Class<?> loaderApi = Class.forName("dev.umb.legacy.legacyside.LegacyCoremodLoader", true,
                    getClass().getClassLoader());
            // LegacyCoremodLoader is a legacyside class (release 8, package-private); loading it
            // through the PLAIN test classloader (not the isolated one) is fine here because this
            // test never boots a real universe - it only needs the class body, and its own
            // reflection calls resolve "test.pkg.*" through whichever loader is passed to it.
            java.lang.reflect.Method discover = loaderApi.getDeclaredMethod("discoverAndRegister",
                    net.minecraft.launchwrapper.LaunchClassLoader.class, java.util.function.Consumer.class);
            discover.setAccessible(true);
            java.util.List<String> log = new java.util.ArrayList<String>();
            @SuppressWarnings("unchecked")
            List<String> discovered = (List<String>) discover.invoke(null, loader,
                    (java.util.function.Consumer<String>) log::add);

            assertEquals(1, discovered.size(), "log: " + log);
            assertEquals(PLUGIN, discovered.get(0));
            assertEquals(before + 1, loader.getTransformers().size(),
                    "the coremod's declared transformer must be registered exactly once; log: " + log);
            assertTrue(loader.getTransformers().get(loader.getTransformers().size() - 1)
                    .getClass().getName().equals(TRANSFORMER));
        }
    }

    /**
     * Proves the ordering fix described in {@code LegacyCoremodLoader.discoverAndRegister}'s own
     * {@code Legacy1122CoremodLoader}): coremods must be scanned in FILENAME order, not whichever
     * order their URLs happen to be on the loader. The loader is given the alphabetically-LATER jar
     * first; if discovery ever regresses to caller/URL order, this fails by returning the plugins in
     * the wrong sequence.
     */
    @Test
    void coremodsAreDiscoveredInFilenameOrderNotUrlOrder() throws Exception {
        Path zJar = jarDir().resolve("z-second.jar");
        Path aJar = jarDir().resolve("a-first.jar");
        writeNamedCoremodJar(zJar, "test/pkg/ZPlugin", "test/pkg/ZTransformer");
        writeNamedCoremodJar(aJar, "test/pkg/APlugin", "test/pkg/ATransformer");

        // URLs added in the "wrong" (z before a) order on purpose.
        try (LegacyLoader loader = new LegacyLoader(new URL[]{zJar.toUri().toURL(), aJar.toUri().toURL()},
                LegacyCoremodLoaderTest.class.getClassLoader())) {
            Class<?> loaderApi = Class.forName("dev.umb.legacy.legacyside.LegacyCoremodLoader", true,
                    getClass().getClassLoader());
            java.lang.reflect.Method discover = loaderApi.getDeclaredMethod("discoverAndRegister",
                    net.minecraft.launchwrapper.LaunchClassLoader.class, java.util.function.Consumer.class);
            discover.setAccessible(true);
            java.util.List<String> log = new java.util.ArrayList<String>();
            @SuppressWarnings("unchecked")
            List<String> discovered = (List<String>) discover.invoke(null, loader,
                    (java.util.function.Consumer<String>) log::add);

            assertEquals(java.util.Arrays.asList("test.pkg.APlugin", "test.pkg.ZPlugin"), discovered,
                    "coremods must scan in filename order (a-first.jar before z-second.jar), "
                            + "regardless of URL order; log: " + log);
        }
    }

    @Test
    void jarsWithoutTheManifestAttributeAreIgnored() throws Exception {
        Path jar = jarDir().resolve("plain-mod.jar");
        writePlainJar(jar);

        try (LegacyLoader loader = new LegacyLoader(new URL[]{jar.toUri().toURL()},
                LegacyCoremodLoaderTest.class.getClassLoader())) {
            Class<?> loaderApi = Class.forName("dev.umb.legacy.legacyside.LegacyCoremodLoader", true,
                    getClass().getClassLoader());
            java.lang.reflect.Method discover = loaderApi.getDeclaredMethod("discoverAndRegister",
                    net.minecraft.launchwrapper.LaunchClassLoader.class, java.util.function.Consumer.class);
            discover.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> discovered = (List<String>) discover.invoke(null, loader,
                    (java.util.function.Consumer<String>) msg -> { });
            assertFalse(discovered.contains(PLUGIN));
            assertTrue(discovered.isEmpty());
        }
    }

    private static Path jarDir() throws Exception {
        Path d = Path.of(System.getProperty("java.io.tmpdir"), "umb-legacy-coremod-loader-test");
        Files.createDirectories(d);
        return d;
    }

    private static void writeCoremodJar(Path jar) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("FMLCorePlugin", PLUGIN);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, mf)) {
            putClass(jos, "test/pkg/FakePlugin", pluginClassBytes());
            putClass(jos, "test/pkg/FakeTransformer", transformerClassBytes());
        }
    }

    /** Same shape as {@link #writeCoremodJar(Path)}, but with caller-chosen internal names. */
    private static void writeNamedCoremodJar(Path jar, String pluginInternalName,
            String transformerInternalName) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("FMLCorePlugin", pluginInternalName.replace('/', '.'));
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, mf)) {
            putClass(jos, pluginInternalName, namedPluginClassBytes(pluginInternalName, transformerInternalName));
            putClass(jos, transformerInternalName, namedTransformerClassBytes(transformerInternalName));
        }
    }

    /** {@code public FakePlugin() {}  public String[] getASMTransformerClass() { return {transformerInternalName}; } } */
    private static byte[] namedPluginClassBytes(String pluginInternalName, String transformerInternalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, pluginInternalName, null,
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
        m.visitLdcInsn(transformerInternalName.replace('/', '.'));
        m.visitInsn(Opcodes.AASTORE);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(4, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** implements net.minecraft.launchwrapper.IClassTransformer, transform() is a pass-through. */
    private static byte[] namedTransformerClassBytes(String transformerInternalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, transformerInternalName, null,
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

    private static void writePlainJar(Path jar) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, mf)) {
            putClass(jos, "test/pkg/Harmless", harmlessClassBytes());
        }
    }

    private static void putClass(JarOutputStream jos, String internalName, byte[] bytes) throws Exception {
        jos.putNextEntry(new JarEntry(internalName + ".class"));
        jos.write(bytes);
        jos.closeEntry();
    }

    /** public FakePlugin() {}  public String[] getASMTransformerClass() { return {TRANSFORMER}; } */
    private static byte[] pluginClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/FakePlugin", null,
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

    /** implements net.minecraft.launchwrapper.IClassTransformer, transform() is a pass-through. */
    private static byte[] transformerClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/FakeTransformer", null,
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
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/pkg/Harmless", null,
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
