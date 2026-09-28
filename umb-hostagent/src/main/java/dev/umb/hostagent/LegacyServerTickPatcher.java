package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Injects lifecycle seams into {@code MinecraftServer.tickServer(BooleanSupplier)}. */
public final class LegacyServerTickPatcher implements ClassFileTransformer {
    public static final String TARGET = "net/minecraft/server/MinecraftServer";
    static final String METHOD = "tickServer";
    static final String DESC = "(Ljava/util/function/BooleanSupplier;)V";
    static final String HOOK = "dev/umb/hostagent/content/LegacyServerTickHook";

    public static volatile int lastStartCount;
    public static volatile int lastEndCount;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            byte[] patched = patch(classfileBuffer);
            if (patched == null) {
                // The legacy universes define their own net.minecraft.server.MinecraftServer
                // (1.7.10 / 1.16.5) with no 26.2 tickServer(BooleanSupplier): not our target.
                if (lastStartCount == 0) {
                    AgentLog.line("tick patch: skipped non-26.2 MinecraftServer from loader " + loader);
                } else {
                    AgentLog.loud("PATCH-FAILED MinecraftServer lifecycle tick (start=" + lastStartCount
                            + " end=" + lastEndCount + ") loader=" + loader);
                }
                return null;
            }
            AgentLog.loud("PATCHED MinecraftServer lifecycle tick loader=" + loader + " start=" + lastStartCount
                    + " end=" + lastEndCount);
            return patched;
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED MinecraftServer lifecycle tick: " + t);
            AgentLog.error("LegacyServerTickPatcher.transform", t, 5);
            return null;
        }
    }

    public static byte[] patch(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        lastStartCount = 0;
        lastEndCount = 0;
        for (MethodNode method : cn.methods) {
            if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
            InsnList start = new InsnList();
            start.add(new VarInsnNode(Opcodes.ALOAD, 0));
            start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "serverTickStart",
                    "(Lnet/minecraft/server/MinecraftServer;)V", false));
            method.instructions.insertBefore(method.instructions.getFirst(), start);
            lastStartCount++;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.RETURN) continue;
                InsnList end = new InsnList();
                end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "serverTickEnd",
                        "(Lnet/minecraft/server/MinecraftServer;)V", false));
                // each static call consumes its own receiver argument: reload `this` (a missing
                // ALOAD is required to keep the operand stack valid.)
                end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/umb/hostagent/Hooks", "serverEffectsEnd",
                        "(Lnet/minecraft/server/MinecraftServer;)V", false));
                method.instructions.insertBefore(in, end);
                lastEndCount++;
            }
        }
        if (lastStartCount != 1 || lastEndCount == 0) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }
}
