package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Calls {@link Hooks#beforeFreeze()} while built-in registries are still writable. If the
 * dedicated freeze method is unavailable, the bootstrap call sequence is used as a fallback.
 * Both insertion points preserve existing stack-map frames.
 */
public final class BuiltInRegistriesPatcher implements ClassFileTransformer {

    public static final String TARGET = "net/minecraft/core/registries/BuiltInRegistries";
    static final String HOOKS = "dev/umb/hostagent/Hooks";

    /** Set once the transform actually ran, for reporting. */
    public static volatile String variantApplied = null;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            byte[] patched = patch(classfileBuffer);
            if (patched == null) {
                AgentLog.loud("PATCH-FAILED BuiltInRegistries: neither freeze() nor bootStrap() matched");
                return null;
            }
            AgentLog.loud("PATCHED BuiltInRegistries." + variantApplied
                    + " -> dev.umb.hostagent.Hooks.beforeFreeze()V");
            return patched;
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED BuiltInRegistries: " + t);
            AgentLog.error("BuiltInRegistriesPatcher.transform", t, 5);
            return null; // never throw out of a transformer
        }
    }

    /** Package-visible for transformer tests. */
    public static byte[] patch(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);

        MethodNode freeze = find(cn, "freeze", "()V");
        if (freeze != null && (freeze.access & Opcodes.ACC_STATIC) != 0) {
            InsnList pre = new InsnList();
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beforeFreeze", "()V", false));
            freeze.instructions.insert(pre);
            variantApplied = "freeze()V@entry";
            return write(cn);
        }

        MethodNode boot = find(cn, "bootStrap", "()V");
        if (boot != null) {
            for (AbstractInsnNode in = boot.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode m = (MethodInsnNode) in;
                if (!TARGET.equals(m.owner) || !"createContents".equals(m.name)) continue;
                boot.instructions.insert(in,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beforeFreeze", "()V", false));
                variantApplied = "bootStrap()V@after-createContents";
                return write(cn);
            }
        }
        return null;
    }

    private static MethodNode find(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
