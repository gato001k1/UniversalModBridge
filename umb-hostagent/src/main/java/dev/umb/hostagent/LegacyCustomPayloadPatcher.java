package dev.umb.hostagent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Routes server input and client effects through the 26.2 custom-payload listeners. */
public final class LegacyCustomPayloadPatcher implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain d,byte[] bytes) {
        boolean server="net/minecraft/server/network/ServerGamePacketListenerImpl".equals(name);
        boolean client="net/minecraft/client/multiplayer/ClientPacketListener".equals(name);
        if (!server && !client) return null;
        try {
            ClassNode cn=new ClassNode(); new ClassReader(bytes).accept(cn,0); int hits=0;
            String desc=server ? "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V"
                    : "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V";
            for(MethodNode m:cn.methods) if("handleCustomPayload".equals(m.name)&&desc.equals(m.desc)) {
                InsnList x=new InsnList(); x.add(new VarInsnNode(Opcodes.ALOAD,0)); x.add(new VarInsnNode(Opcodes.ALOAD,1));
                x.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/umb/hostagent/Hooks",
                        server ? "serverCustomPayload" : "clientCustomPayload",
                        server ? "(Ljava/lang/Object;Ljava/lang/Object;)V" : "(Ljava/lang/Object;)V", false));
                m.instructions.insertBefore(m.instructions.getFirst(),x); hits++;
            }
            if(hits==0)return null; ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_MAXS);cn.accept(w);AgentLog.loud("PATCHED LegacyCustomPayloadPatcher");return w.toByteArray();
        }catch(Throwable t){AgentLog.error("LegacyCustomPayloadPatcher",t,3);return null;}
    }
}
