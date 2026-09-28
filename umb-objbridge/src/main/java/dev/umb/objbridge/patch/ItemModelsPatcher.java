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

/**
 * Appends {@code INVOKESTATIC dev/umb/objbridge/ObjBridge.registerItemModelType()V} to the end of
 * {@code net/minecraft/client/renderer/item/ItemModels.bootstrap()V}.
 *
 * <p>Why there: {@code bootstrap()} is the method that populates {@code ItemModels.ID_MAPPER} by
 * mutation (8 {@code put} calls, then one {@code RETURN} at offset 120 - javap-verified). Running
 * immediately after it means the mapper is fully populated and no {@code assets/*_/items/*.json} has
 * been decoded yet. {@code ID_MAPPER.codec()} captures {@code idToValue::get}, so a {@code put} after
 * {@code <clinit>} still binds - also javap-verified.
 *
 * <p>A no-arg static call inserted before a {@code RETURN} changes no stack map frame, so
 * {@code ClassWriter(COMPUTE_MAXS)} is enough; {@code COMPUTE_FRAMES} would need a class-hierarchy
 * resolver at agent time and is deliberately avoided. {@code transform()} never throws.
 */
public final class ItemModelsPatcher implements ClassFileTransformer {

    public static final String TARGET = "net/minecraft/client/renderer/item/ItemModels";
    private static final String HOOK_OWNER = "dev/umb/objbridge/ObjBridge";
    private static final String HOOK_NAME = "registerItemModelType";

    /** null until transform() runs; then either the variant name or a failure reason. */
    public static volatile String result;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classfileBuffer).accept(cn, 0);

            MethodNode target = null;
            for (MethodNode mn : cn.methods) {
                if ("bootstrap".equals(mn.name) && "()V".equals(mn.desc)) {
                    target = mn;
                    break;
                }
            }
            if (target == null) {
                result = "PATCH-FAILED ItemModels.bootstrap()V not found";
                ObjLog.loud(result);
                return null;
            }

            int inserted = 0;
            InsnList insns = target.instructions;
            for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    insns.insertBefore(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, "()V", false));
                    inserted++;
                }
            }
            if (inserted == 0) {
                result = "PATCH-FAILED ItemModels.bootstrap()V has no RETURN";
                ObjLog.loud(result);
                return null;
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            result = "ItemModels.bootstrap()V@return x" + inserted;
            ObjLog.loud("PATCHED " + result);
            return cw.toByteArray();
        } catch (Throwable t) {
            result = "PATCH-FAILED ItemModels: " + t;
            ObjLog.loud(result);
            ObjLog.error("ItemModelsPatcher.transform", t, 6);
            return null;   // never throw out of transform()
        }
    }
}
