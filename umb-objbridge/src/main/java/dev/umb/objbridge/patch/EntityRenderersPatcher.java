package dev.umb.objbridge.patch;

import dev.umb.objbridge.ObjLog;
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

/** Calls the generic entity renderer registration immediately before the provider map is consumed. */
public final class EntityRenderersPatcher implements ClassFileTransformer {
    public static final String TARGET = "net.minecraft.client.renderer.entity.EntityRenderers";
    private static final String HOOK_OWNER = "dev/umb/objbridge/ObjBridge";
    private static final String HOOK_NAME = "registerEntityRenderers";
    private static final String HOOK_DESC = "()V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, 0);
            MethodNode target = null;
            for (MethodNode mn : cn.methods) {
                if ("createEntityRenderers".equals(mn.name)
                        && mn.desc.contains("EntityRendererProvider$Context")
                        && mn.desc.endsWith(")Ljava/util/Map;")) {
                    target = mn;
                    break;
                }
            }
            if (target == null) {
                ObjLog.loud("PATCH-FAILED EntityRenderers.createEntityRenderers(..) not found");
                return null;
            }
            for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC
                        && insn instanceof MethodInsnNode call
                        && HOOK_OWNER.equals(call.owner)
                        && HOOK_NAME.equals(call.name)
                        && HOOK_DESC.equals(call.desc)) {
                    return null;
                }
            }
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
            target.instructions.insert(hook);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            ObjLog.loud("PATCHED EntityRenderers.createEntityRenderers" + target.desc + "@entry");
            return cw.toByteArray();
        } catch (Throwable t) {
            ObjLog.loud("PATCH-FAILED EntityRenderers: " + t);
            ObjLog.error("EntityRenderersPatcher.transform", t, 6);
            return null;
        }
    }
}
