package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Adds UMB payload codecs to 26.2's static custom-payload codec lists before class initialisation. */
public final class LegacyPayloadCodecPatcher implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                       ProtectionDomain domain, byte[] bytes) {
        if (!"net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket".equals(name)
                && !"net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket".equals(name)) return null;
        try {
            ClassNode cn = new ClassNode(); new ClassReader(bytes).accept(cn, 0);
            int hits = 0;
            for (MethodNode m : cn.methods) if ("<clinit>".equals(m.name)) {
                for (AbstractInsnNode n=m.instructions.getFirst(); n!=null; n=n.getNext()) {
                    if (n instanceof MethodInsnNode x && x.getOpcode()==Opcodes.INVOKESTATIC
                            && "net/minecraft/util/Util".equals(x.owner) && "make".equals(x.name)) {
                        InsnList add=new InsnList();
                        add.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "dev/umb/hostagent/input/LegacyPayloadRegistration", "add",
                                "(Ljava/util/List;)Ljava/util/List;", false));
                        m.instructions.insert(n, add); hits++;
                    }
                }
            }
            if (hits == 0) return null;
            ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(w); AgentLog.loud("PATCHED LegacyPayloadCodecPatcher");return w.toByteArray();
        } catch (Throwable t) { AgentLog.error("LegacyPayloadCodecPatcher", t, 3); return null; }
    }
}
