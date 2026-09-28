package dev.umb.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §19–§21: host indexing and static linkage resolution, including inherited-member
 * lookup and the negative case. Host jars here are tiny synthetic ones — full-host runs
 * live in the M0 vertical verification, not unit tests.
 */
class LinkageCheckerTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ fixtures

    private static Path jarOf(Path dir, String name, java.util.Map<String, byte[]> entries) throws IOException {
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

    /** Emits a class with one public no-arg void method via ASM. */
    private static byte[] cls(String internalName) {
        return clsWithMethod(internalName, "probe", "()V");
    }

    private static byte[] clsWithMethod(String internalName, String mname, String mdesc) {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, mname, mdesc,
                null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a class whose single method invokes owner.name:desc via INVOKEVIRTUAL with a
     * null receiver — the checker is static so verify-validity doesn't matter.
     */
    private static byte[] bytesOfClassCalling(String internalName, String owner,
                                              String method, String desc) {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V",
                null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, owner, method, desc,
                false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void hostIndexCountsClasses() throws IOException {
        Path host = jarOf(tmp, "host.jar", java.util.Map.of(
                "net/minecraft/A.class", cls("net/minecraft/A"),
                "net/minecraft/B.class", cls("net/minecraft/B")));
        try (HostIndex idx = HostIndex.of(host)) {
            assertEquals(2, idx.classCount());
            assertTrue(idx.hasClass("net/minecraft/A"));
            assertFalse(idx.hasClass("net/minecraft/Nope"));
        }
    }

    @Test
    void directMethodResolvesAndMissingDoesNot() throws IOException {
        Path host = jarOf(tmp, "host.jar", java.util.Map.of(
                "net/minecraft/A.class",
                clsWithMethod("net/minecraft/A", "tick", "(I)V")));
        try (HostIndex idx = HostIndex.of(host)) {
            LinkageChecker checker = new LinkageChecker(idx);
            assertNotNull(idx.findMethod("net/minecraft/A", "tick", "(I)V"));
            assertEquals(null, idx.findMethod("net/minecraft/A", "tock", "(I)V"));

            Path goodMod = jarOf(tmp, "good.jar", java.util.Map.of(
                    "mod/Good.class",
                    bytesOfClassCalling("mod/Good", "net/minecraft/A", "tick", "(I)V")));
            assertTrue(checker.check(goodMod).links());

            Path badMod = jarOf(tmp, "bad.jar", java.util.Map.of(
                    "mod/Bad.class",
                    bytesOfClassCalling("mod/Bad", "net/minecraft/A", "tock", "(I)V")));
            LinkageChecker.LinkageResult r = checker.check(badMod);
            assertFalse(r.links());
            assertEquals(1, r.missing().size());
            assertEquals("method", r.missing().get(0).kind());
            assertEquals("net/minecraft/A", r.missing().get(0).owner());
            assertEquals("tock", r.missing().get(0).name());
        }
    }

    @Test
    void inheritedMethodResolvesThroughSuperclassChain() throws IOException {
        // B extends A; only A declares tick. A call on B must resolve.
        var cwB = new org.objectweb.asm.ClassWriter(0);
        cwB.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "net/minecraft/B", null,
                "net/minecraft/A", null);
        cwB.visitEnd();
        Path host = jarOf(tmp, "host.jar", java.util.Map.of(
                "net/minecraft/A.class",
                clsWithMethod("net/minecraft/A", "tick", "()V"),
                "net/minecraft/B.class",
                cwB.toByteArray()));
        try (HostIndex idx = HostIndex.of(host)) {
            assertNotNull(idx.findMethod("net/minecraft/B", "tick", "()V"),
                    "inherited member must resolve through super chain");
        }
    }

    @Test
    void refsToUnknownOwnersAreOutOfScopeNotMissing() throws IOException {
        Path host = jarOf(tmp, "host.jar", java.util.Map.of(
                "net/minecraft/A.class", cls("net/minecraft/A")));
        try (HostIndex idx = HostIndex.of(host)) {
            LinkageChecker checker = new LinkageChecker(idx);
            Path mod = jarOf(tmp, "mod.jar", java.util.Map.of(
                    "mod/M.class",
                    bytesOfClassCalling("mod/M", "com/other/Lib", "whatever", "()V")));
            assertTrue(checker.check(mod).links(),
                    "refs into non-host classes are not linkage failures at this stage");
        }
    }
}
