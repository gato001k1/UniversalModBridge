package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Verifies the exact 26.2 tickServer(BooleanSupplier) seam before an agent build. */
class LegacyServerTickPatcherTest {
    @Test
    void patchesVerifiedMinecraftServerTickMethod() throws Exception {
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(
                    "net/minecraft/server/MinecraftServer.class"))) {
                byte[] patched = LegacyServerTickPatcher.patch(in.readAllBytes());
                assertNotNull(patched);
                assertEquals(1, LegacyServerTickPatcher.lastStartCount);
                assertTrue(LegacyServerTickPatcher.lastEndCount >= 1);
                // Counting insertions is not enough: 2026-09-24 the patch applied live for the first
                // time and the JVM rejected it (operand stack underflow - a static hook call without
                // its argument). Run a real stack/type analysis over the patched method.
                ClassNode cn = new ClassNode();
                new ClassReader(patched).accept(cn, 0);
                int analyzed = 0;
                for (MethodNode m : cn.methods) {
                    if (!LegacyServerTickPatcher.METHOD.equals(m.name) || !LegacyServerTickPatcher.DESC.equals(m.desc)) continue;
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m); // throws AnalyzerException on a bad stack
                    analyzed++;
                }
                assertEquals(1, analyzed);
            }
        }
    }
}
