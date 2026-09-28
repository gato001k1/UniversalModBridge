package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Gates the 26.2 player join packet until the default legacy universe is ready. */
public final class LegacyPlayerJoinPatcher implements ClassFileTransformer {
    static final String TARGET = "net/minecraft/server/players/PlayerList";
    static final String METHOD = "placeNewPlayer";
    static final String DESC = "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain,
                            byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            int hits = 0;
            for (MethodNode method : cn.methods) {
                if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 2));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/umb/hostagent/content/LegacyServerTickHook", "beforePlayerJoin",
                        "(Lnet/minecraft/server/level/ServerPlayer;)V", false));
                method.instructions.insertBefore(method.instructions.getFirst(), call);
                hits++;
            }
            if (hits != 1) return null;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(writer);
            AgentLog.loud("PATCHED PlayerList join legacy boot gate");
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.error("LegacyPlayerJoinPatcher", t, 4);
            return null;
        }
    }
}
