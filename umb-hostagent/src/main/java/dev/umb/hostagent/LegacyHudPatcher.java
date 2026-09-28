package dev.umb.hostagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Inserts the universal legacy overlay seam at the 26.2 HUD extraction boundary. */
public final class LegacyHudPatcher implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        if (!"net/minecraft/client/gui/Hud".equals(name)) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (MethodNode method : node.methods) {
                if (!"extractRenderState".equals(method.name)
                        || !"(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
                                .equals(method.desc)) continue;
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new VarInsnNode(Opcodes.ALOAD, 2));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/umb/hostagent/Hooks", "renderHud",
                        "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                        false));
                method.instructions.insertBefore(method.instructions.getFirst(), call);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                AgentLog.loud("PATCHED LegacyHudPatcher");
                return writer.toByteArray();
            }
        } catch (Throwable t) {
            AgentLog.error("LegacyHudPatcher", t, 3);
        }
        return null;
    }
}
