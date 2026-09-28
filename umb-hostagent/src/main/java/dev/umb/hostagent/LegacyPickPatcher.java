package dev.umb.hostagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Generous-click seam for legacy vehicle twins. Wraps every return of
 * {@code LocalPlayer.pick(Entity, double, double, float)} with
 * {@link Hooks#pickLegacyPart}, which re-tests a vanilla MISS against legacy twin boxes.
 * Vanilla hits pass through untouched, so block aim, vanilla entities, attacks and the pick
 * range are all bit-identical without legacy twins nearby.
 */
public final class LegacyPickPatcher implements ClassFileTransformer {
    static final String TARGET = "net/minecraft/client/player/LocalPlayer";
    static final String METHOD = "pick";
    static final String DESC =
            "(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean touched = false;
            for (MethodNode method : node.methods) {
                if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
                touched |= wrapReturns(method);
            }
            if (!touched) {
                return null;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            AgentLog.loud("PATCHED LegacyPickPatcher");
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.error("LegacyPickPatcher", t, 3);
        }
        return null;
    }

    /**
     * Rewrites each {@code areturn} into
     * {@code hook(shooter, blockReach, entityReach, partialTick, vanilla); areturn}.
     * Param slots for {@code (Entity, double, double, float)} are 0, 1, 3, 5; the vanilla
     * result is spilled to a fresh local past the current frame.
     */
    static boolean wrapReturns(MethodNode method) {
        boolean wrapped = false;
        for (AbstractInsnNode insn = method.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            int spill = method.maxLocals++;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ASTORE, spill));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.DLOAD, 1));
            hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
            hook.add(new VarInsnNode(Opcodes.FLOAD, 5));
            hook.add(new VarInsnNode(Opcodes.ALOAD, spill));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/umb/hostagent/Hooks", "pickLegacyPart",
                    "(Lnet/minecraft/world/entity/Entity;DDF"
                            + "Lnet/minecraft/world/phys/HitResult;)"
                            + "Lnet/minecraft/world/phys/HitResult;",
                    false));
            method.instructions.insertBefore(insn, hook);
            wrapped = true;
        }
        return wrapped;
    }
}
