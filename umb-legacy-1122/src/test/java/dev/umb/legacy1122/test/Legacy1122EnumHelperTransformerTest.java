package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import dev.umb.legacy1122.legacyside.Legacy1122EnumHelperTransformer;

/**
 * Java 25 removed sun.reflect.ReflectionFactory.newFieldAccessor. Forge 1.12.2 uses it in EnumHelper
 * AND in ObjectHolderRef$FinalFieldHelper; the latter killed preInit live on 2026-09-26 00:04 (every
 * mod with an @ObjectHolder). Both rewrites must verify and must no longer name ReflectionFactory.
 */
class Legacy1122EnumHelperTransformerTest {
    private static final String FORGE = "research/out/legacy-1122/forge-1.12.2-14.23.5.2860-universal.jar";

    @Test
    void finalFieldHelperAndEnumHelperRewritesVerifyAndDropReflectionFactory() throws Exception {
        File forge = new File(System.getProperty("umb.repo", "."), FORGE);
        assumeTrue(forge.isFile(), "forge universal jar not fetched: " + forge);
        String[] targets = {
                "net.minecraftforge.registries.ObjectHolderRef$FinalFieldHelper",
                "net.minecraftforge.common.util.EnumHelper"};
        try (JarFile jar = new JarFile(forge)) {
            for (String target : targets) {
                byte[] original;
                try (InputStream in = jar.getInputStream(jar.getJarEntry(target.replace('.', '/') + ".class"))) {
                    original = in.readAllBytes();
                }
                byte[] patched = new Legacy1122EnumHelperTransformer().transform(target, target, original);
                assertFalse(java.util.Arrays.equals(original, patched), "transformer must rewrite " + target);
                ClassNode cn = new ClassNode();
                new ClassReader(patched).accept(cn, 0);
                boolean touched = false;
                for (MethodNode m : cn.methods) {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m);
                    boolean rewritten = m.name.equals("<clinit>") || m.name.equals("setup")
                            || m.name.equals("makeWritable") || m.name.equals("setField")
                            || m.name.equals("makeEnum") || m.name.equals("setFailsafeFieldValue");
                    if (!rewritten) continue;
                    touched = true;
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof LdcInsnNode && String.valueOf(((LdcInsnNode) insn).cst).contains("ReflectionFactory")) {
                            throw new AssertionError(target + "." + m.name + " still reflects on ReflectionFactory");
                        }
                    }
                }
                assertTrue(touched, "expected rewritten methods in " + target);
            }
        }
    }
}
