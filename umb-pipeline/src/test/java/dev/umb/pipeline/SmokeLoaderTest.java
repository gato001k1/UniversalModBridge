package dev.umb.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SmokeLoader (M3): every class of a mod jar loads under the host classloader shape
 * (mod sees host + libs, host never sees the mod). Pins NONZERO outcomes -- a class
 * referencing an absent type must FAIL and name it in the missing-symbol sample,
 * counts must be exact, ordering deterministic, package-info/module-info skipped.
 */
class SmokeLoaderTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ fixtures

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    /** A quietly valid class with no methods. */
    private static byte[] clean(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose go()V invokes owner.go()V on a null receiver -- the receiver
     *  type is the constant-pool reference the smoke must force-resolve. */
    private static byte[] calling(String internalName, String owner) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "go", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A package-info shim: an interface, as javac emits for package annotations. */
    private static byte[] packageInfo(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_SYNTHETIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static SmokeReport smoke(Path mod, Path host, List<Path> libs) throws IOException {
        return SmokeLoader.smoke(mod, host, libs);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void cleanClassLoadsUnderHostShape() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "q/Clean.class", clean("q/Clean")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(1, r.totalClasses());
        assertEquals(0, r.skipped());
        assertEquals(1, r.parseableEntries());
        assertEquals(1, r.loaded());
        assertEquals(0, r.failed());
        assertTrue(r.missingSymbols().isEmpty());
        assertTrue(r.clean());
    }

    @Test
    void classReferencingAbsentSymbolFailsAndNamesIt() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "q/GhostRef.class", calling("q/GhostRef", "net/absent/Ghost")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(0, r.loaded());
        assertEquals(1, r.failed());
        assertEquals(Map.of("net.absent.Ghost", 1), r.missingSymbols());
        assertTrue(r.failures().containsKey("q.GhostRef"));
        assertTrue(r.failures().get("q.GhostRef").startsWith("ClassNotFoundException"),
                () -> r.failures().get("q.GhostRef"));
    }

    @Test
    void twoClassesCitingSameAbsentSymbolCountTwice() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of());
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "q/GhostRef.class", calling("q/GhostRef", "net/absent/Ghost"),
                "r/GhostRef2.class", calling("r/GhostRef2", "net/absent/Ghost")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(0, r.loaded());
        assertEquals(2, r.failed());
        assertEquals(2, r.missingSymbols().get("net.absent.Ghost"));
        assertEquals(2, r.failures().size());
    }

    @Test
    void modSeesHostClassAndOwnSiblings() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "q/Ref.class", calling("q/Ref", "net/minecraft/A"),
                "q/Self.class", calling("q/Self", "q/Sib"),
                "q/Sib.class", clean("q/Sib")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(3, r.totalClasses()); // host classes are NOT part of the report
        assertEquals(3, r.parseableEntries());
        assertEquals(3, r.loaded());
        assertEquals(0, r.failed());
        assertTrue(r.clean());
    }

    @Test
    void packageInfoOnlyYieldsZeroParseable() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of());
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "q/package-info.class", packageInfo("q/package-info")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(1, r.totalClasses());
        assertEquals(1, r.skipped());
        assertEquals(0, r.parseableEntries());
        assertEquals(0, r.loaded());
        assertEquals(0, r.failed());
        assertTrue(r.clean(), "skips are not failures, but nothing was loaded either");
    }

    @Test
    void moduleInfoEntriesAreSkipped() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of());
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "module-info.class", new byte[]{1, 2, 3, 4},
                "q/Clean.class", clean("q/Clean")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(2, r.totalClasses());
        assertEquals(1, r.skipped());
        assertEquals(1, r.parseableEntries());
        assertEquals(1, r.loaded());
        assertEquals(0, r.failed());
    }

    @Test
    void failuresAndMissingAreSortedDeterministically() throws IOException {
        Path host = jarOf(tmp, "host.jar", Map.of());
        Path mod = jarOf(tmp, "mod.jar", Map.of(
                "z/Zeta.class", calling("z/Zeta", "net/absent/MissingZ"),
                "a/Alpha.class", calling("a/Alpha", "net/absent/MissingA")));
        SmokeReport r = smoke(mod, host, List.of());
        assertEquals(List.of("net.absent.MissingA", "net.absent.MissingZ"),
                new ArrayList<>(r.missingSymbols().keySet()));
        assertEquals(List.of("a.Alpha", "z.Zeta"),
                new ArrayList<>(r.failures().keySet()));
        // Two identical runs must agree field for field (TreeMap ordering is structural).
        SmokeReport again = smoke(mod, host, List.of());
        assertEquals(r, again);
    }
}
