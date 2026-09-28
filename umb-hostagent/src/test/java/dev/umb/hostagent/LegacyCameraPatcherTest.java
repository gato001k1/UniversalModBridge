package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The camera patch must produce verifiable bytecode and route the hardcoded third-person
 * distance through the legacy seam (every ASM patcher needs a BasicVerifier test against the
 * real 26.2 class, per the 2026-09-25 white-world rule). The LDC shape is grounded with javap on
 * 26.2 client.jar: inside the detached branch of {@code Camera.alignWithEntity} (not
 * {@code update}, which only handles render distance, FOV and frustum) the desired distance is
 * seeded by {@code ldc 4.0f; fstore_2}; the depth-far {@code ldc 4.0f} in {@code update} feeds
 * an {@code fmul}.
 */
class LegacyCameraPatcherTest {
    private static final String CAMERA = "net/minecraft/client/Camera";

    private static byte[] patchedCamera() throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(CAMERA + ".class"))) {
                original = in.readAllBytes();
            }
        }
        byte[] patched = new LegacyCameraPatcher().transform(null, CAMERA, null, null, original);
        assertNotNull(patched, "patcher must apply to " + CAMERA);
        return patched;
    }

    private static MethodNode cameraMethod(byte[] bytes, String name, String desc) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static int countHookCalls(MethodNode method, String hookName, String hookDesc) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("dev/umb/hostagent/Hooks".equals(call.owner)
                    && hookName.equals(call.name)
                    && hookDesc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void patchedMethodsStayVerifiable() throws Exception {
        byte[] patched = patchedCamera();
        ClassNode cn = new ClassNode();
        new ClassReader(patched).accept(cn, 0);
        int analyzed = 0;
        for (MethodNode m : cn.methods) {
            boolean patchedMethod = ("update".equals(m.name)
                    && "(Lnet/minecraft/client/DeltaTracker;)V".equals(m.desc))
                    || ("alignWithEntity".equals(m.name) && "(F)V".equals(m.desc));
            if (!patchedMethod) continue;
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m);
            analyzed++;
        }
        assertEquals(2, analyzed, "update(DeltaTracker)V and alignWithEntity(F)V in Camera");
    }

    @Test
    void desiredDistanceRoutesThroughTheLegacySeam() throws Exception {
        byte[] patched = patchedCamera();
        MethodNode align = cameraMethod(patched, "alignWithEntity", "(F)V");
        assertNotNull(align);
        assertEquals(1, countHookCalls(align, "legacyThirdPersonDistance", "(F)F"),
                "exactly one LDC-4.0F+FSTORE distance site in alignWithEntity");
        MethodNode update = cameraMethod(patched,
                "update", "(Lnet/minecraft/client/DeltaTracker;)V");
        assertNotNull(update);
        assertEquals(0, countHookCalls(update, "legacyThirdPersonDistance", "(F)F"),
                "update only holds the depth-far LDC, which feeds FMUL and must stay vanilla");
        assertTrue(countHookCalls(update, "syncCamera", "(Ljava/lang/Object;)V") >= 1,
                "the renderViewEntity snapshot seam must remain");
    }

    @Test
    void rewriteFindsTheSingleDistanceSite() throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(CAMERA + ".class"))) {
                original = in.readAllBytes();
            }
        }
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        int alignSites = 0;
        int updateSites = 0;
        for (MethodNode m : cn.methods) {
            if ("alignWithEntity".equals(m.name) && "(F)V".equals(m.desc)) {
                alignSites += LegacyCameraPatcher.rewriteDesiredDistance(m);
            }
            if ("update".equals(m.name)
                    && "(Lnet/minecraft/client/DeltaTracker;)V".equals(m.desc)) {
                updateSites += LegacyCameraPatcher.rewriteDesiredDistance(m);
            }
        }
        assertEquals(1, alignSites, "the LDC+FSTORE distance shape must be unique in alignWithEntity");
        assertEquals(0, updateSites, "update must contribute no distance site");
    }
}
