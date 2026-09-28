package dev.umb.hostagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies the generic legacy camera contract: the renderViewEntity snapshot
 * ({@link Hooks#syncCamera}, after native camera setup in {@code update}) plus the legacy
 * third-person distance ({@link Hooks#legacyThirdPersonDistance}, at the desired-distance seed
 * in {@code alignWithEntity}). Neither names a vehicle or mod.
 */
public final class LegacyCameraPatcher implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        if (!"net/minecraft/client/Camera".equals(name)) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean touched = false;
            int distanceSites = 0;
            for (MethodNode method : node.methods) {
                if ("update".equals(method.name)
                        && "(Lnet/minecraft/client/DeltaTracker;)V".equals(method.desc)) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "dev/umb/hostagent/Hooks", "syncCamera",
                            "(Ljava/lang/Object;)V", false));
                    for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
                            insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() == Opcodes.RETURN) {
                            method.instructions.insertBefore(insn, call);
                            call = new InsnList();
                            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    "dev/umb/hostagent/Hooks", "syncCamera",
                                    "(Ljava/lang/Object;)V", false));
                        }
                    }
                    touched = true;
                } else if ("alignWithEntity".equals(method.name)
                        && "(F)V".equals(method.desc)) {
                    distanceSites += rewriteDesiredDistance(method);
                    touched = true;
                }
            }
            if (!touched) {
                return null;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            AgentLog.loud("PATCHED LegacyCameraPatcher distanceSites=" + distanceSites);
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.error("LegacyCameraPatcher", t, 3);
        }
        return null;
    }

    /**
     * Replaces the detached camera's desired distance before attribute scaling and block clipping.
     * The hook returns the vanilla value when no legacy rider controls the camera.
     */
    static int rewriteDesiredDistance(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.LDC) continue;
            if (!(((LdcInsnNode) insn).cst instanceof Float)) continue;
            if (((Float) ((LdcInsnNode) insn).cst).floatValue() != 4.0F) continue;
            AbstractInsnNode next = insn.getNext();
            if (next == null || next.getOpcode() != Opcodes.FSTORE) continue;
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/umb/hostagent/Hooks", "legacyThirdPersonDistance",
                    "(F)F", false));
            method.instructions.insert(insn, hook);
            count++;
        }
        return count;
    }
}
