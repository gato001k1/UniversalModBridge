package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Fixes the creative screen's inclusive sprite-index clamp. Extra tabs reuse the final tab sprite
 * instead of indexing one element past the array.
 */
public final class CreativeTabSpritePatcher implements ClassFileTransformer {

    public static final String TARGET =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
    private static final String METHOD = "extractTabButton";
    private static final String MTH = "net/minecraft/util/Mth";

    public static volatile boolean applied = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            byte[] patched = patch(classfileBuffer);
            if (patched == null) {
                AgentLog.loud("PATCH-FAILED CreativeModeInventoryScreen: clamp(col,0,len) site not found");
                return null;
            }
            applied = true;
            AgentLog.loud("PATCHED CreativeModeInventoryScreen." + METHOD + " tab-sprite clamp -> length-1");
            return patched;
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED CreativeModeInventoryScreen: " + t);
            AgentLog.error("CreativeTabSpritePatcher.transform", t, 5);
            return null;
        }
    }

    /** Package-visible for the unit test. Returns null when no site matched. */
    public static byte[] patch(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);

        int fixes = 0;
        for (MethodNode m : cn.methods) {
            if (!METHOD.equals(m.name)) continue;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode call = (MethodInsnNode) in;
                if (!MTH.equals(call.owner) || !"clamp".equals(call.name) || !"(III)I".equals(call.desc)) continue;
                AbstractInsnNode after = nextReal(call);
                if (after == null || after.getOpcode() != Opcodes.AALOAD) continue;

                AbstractInsnNode len = prevOpcode(call, Opcodes.ARRAYLENGTH);
                if (len == null) continue;
                AbstractInsnNode alreadyFixed = nextReal(len);
                if (alreadyFixed != null && alreadyFixed.getOpcode() == Opcodes.ICONST_1) continue;

                InsnList fix = new InsnList();
                fix.add(new InsnNode(Opcodes.ICONST_1));
                fix.add(new InsnNode(Opcodes.ISUB));
                m.instructions.insert(len, fix);
                fixes++;
            }
        }
        if (fixes == 0) return null;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode n) {
        for (AbstractInsnNode x = n.getNext(); x != null; x = x.getNext()) {
            if (x.getOpcode() >= 0) return x;
        }
        return null;
    }

    private static AbstractInsnNode prevOpcode(AbstractInsnNode n, int opcode) {
        for (AbstractInsnNode x = n.getPrevious(); x != null; x = x.getPrevious()) {
            if (x.getOpcode() == opcode) return x;
        }
        return null;
    }
}
