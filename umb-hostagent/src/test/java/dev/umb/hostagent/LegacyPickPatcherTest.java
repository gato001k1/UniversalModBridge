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
 * Bug2-mount fallback gate: the pick patch must produce verifiable bytecode, route every return
 * of {@code LocalPlayer.pick} through the legacy seam, and the seam's nearest-hit math must
 * behave (every ASM patcher needs a BasicVerifier test against the real 26.2 class).
 * The wing-slab numbers below are the live A-10 part box from the deploy-#91 round
 * (2.0 x 0.3 x 2.0 at y105.43-105.73).
 */
class LegacyPickPatcherTest {
    private static final String PLAYER = "net/minecraft/client/player/LocalPlayer";

    private static byte[] patchedPlayer() throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(PLAYER + ".class"))) {
                original = in.readAllBytes();
            }
        }
        byte[] patched = new LegacyPickPatcher().transform(null, PLAYER, null, null, original);
        assertNotNull(patched, "patcher must apply to " + PLAYER);
        return patched;
    }

    private static MethodNode pickMethod(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if ("pick".equals(m.name)
                    && "(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;"
                            .equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static int hookSites(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("dev/umb/hostagent/Hooks".equals(call.owner)
                    && "pickLegacyPart".equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void pickStaysVerifiable() throws Exception {
        byte[] patched = patchedPlayer();
        ClassNode cn = new ClassNode();
        new ClassReader(patched).accept(cn, 0);
        int analyzed = 0;
        for (MethodNode m : cn.methods) {
            if (!"pick".equals(m.name)
                    || !"(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;"
                            .equals(m.desc)) {
                continue;
            }
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m);
            analyzed++;
        }
        assertEquals(1, analyzed, "exactly one pick(Entity,DDF) in LocalPlayer");
    }

    @Test
    void everyReturnRoutesThroughTheLegacySeam() throws Exception {
        MethodNode pick = pickMethod(patchedPlayer());
        assertNotNull(pick);
        assertEquals(2, hookSites(pick), "both areturn sites (early + final) route via the hook");
    }

    @Test
    void wrapFindsBothReturnSites() throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(PLAYER + ".class"))) {
                original = in.readAllBytes();
            }
        }
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        int total = 0;
        for (MethodNode m : cn.methods) {
            if (!"pick".equals(m.name)
                    || !"(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;"
                            .equals(m.desc)) {
                continue;
            }
            assertTrue(LegacyPickPatcher.wrapReturns(m));
            total += hookSites(m);
        }
        assertEquals(2, total, "both areturn sites wrapped");
    }


    private static net.minecraft.world.phys.AABB slab() {
        return new net.minecraft.world.phys.AABB(-5.53, 105.43, 4.38, -3.53, 105.73, 6.38);
    }

    private static net.minecraft.world.phys.Vec3 vec(double x, double y, double z) {
        return new net.minecraft.world.phys.Vec3(x, y, z);
    }

    @Test
    void straightDownRayHitsTheSlab() {
        java.util.List<net.minecraft.world.phys.AABB> boxes =
                java.util.Collections.singletonList(slab());
        int hit = Hooks.nearestHitIndex(vec(-4.53, 107.73, 5.38), vec(-4.53, 100.0, 5.38),
                10.0 * 10.0, boxes);
        assertEquals(0, hit);
    }

    @Test
    void marginCatchesWhatTheExactTestMisses() {
        // Level ray 0.15 above the slab top face: the exact box misses, the +0.3 fallback
        // inflation hits. This is the documented purpose of LEGACY_PICK_MARGIN.
        net.minecraft.world.phys.Vec3 eye = vec(-4.53, 105.88, 2.0);
        net.minecraft.world.phys.Vec3 end = vec(-4.53, 105.88, 8.0);
        java.util.List<net.minecraft.world.phys.AABB> exact =
                java.util.Collections.singletonList(slab());
        assertEquals(-1, Hooks.nearestHitIndex(eye, end, 10.0 * 10.0, exact));
        java.util.List<net.minecraft.world.phys.AABB> generous =
                java.util.Collections.singletonList(slab().inflate(0.3D));
        assertEquals(0, Hooks.nearestHitIndex(eye, end, 10.0 * 10.0, generous));
    }

    @Test
    void nearestBoxWins() {
        net.minecraft.world.phys.AABB near =
                new net.minecraft.world.phys.AABB(0, 0, 5, 1, 1, 6);
        net.minecraft.world.phys.AABB far =
                new net.minecraft.world.phys.AABB(0, 0, 10, 1, 1, 11);
        java.util.List<net.minecraft.world.phys.AABB> boxes =
                java.util.Arrays.asList(far, near);
        int hit = Hooks.nearestHitIndex(vec(0.5, 0.5, 0.0), vec(0.5, 0.5, 20.0),
                30.0 * 30.0, boxes);
        assertEquals(1, hit);
    }

    @Test
    void beyondRangeNeverHits() {
        java.util.List<net.minecraft.world.phys.AABB> boxes =
                java.util.Collections.singletonList(slab());
        int hit = Hooks.nearestHitIndex(vec(-4.53, 107.73, 5.38), vec(-4.53, 100.0, 5.38),
                1.0 * 1.0, boxes);
        assertEquals(-1, hit);
    }

    @Test
    void garbageInputsMiss() {
        assertEquals(-1, Hooks.nearestHitIndex(null, vec(0, 0, 0), 100.0,
                java.util.Collections.singletonList(slab())));
        java.util.List<net.minecraft.world.phys.AABB> withNull =
                java.util.Arrays.asList(null, slab());
        assertEquals(1, Hooks.nearestHitIndex(vec(-4.53, 107.73, 5.38), vec(-4.53, 100.0, 5.38),
                10.0 * 10.0, withNull));
    }
}
