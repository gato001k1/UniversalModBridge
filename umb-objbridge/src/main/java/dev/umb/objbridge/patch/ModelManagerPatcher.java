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
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Appends {@code ALOAD 0; INVOKESTATIC dev/umb/objbridge/ObjBridge.onModelsApplied(ModelManager)V} to
 * the end of {@code net/minecraft/client/resources/model/ModelManager.apply(ModelManager$ReloadState)V}.
 *
 * <p>Why there: {@code apply} is the method that installs the freshly baked results - it is where
 * {@code blockStateModelSet} gets replaced with a {@code new BlockStateModelSet(reloadState
 * .blockStateModels, missing.block())} (javap-verified, single {@code RETURN} at offset 112), and the
 * map handed to that constructor is stored verbatim and is the mutable {@code IdentityHashMap} that
 * {@code createBlockStateToModelDispatch} built. Running at the end of {@code apply} therefore means
 * the live block-state dispatch map exists, is complete, and is still writable - and we are on the
 * render thread, the same thread that reads it.
 *
 * <p>{@code apply} is {@code private}; that is irrelevant to an {@code INVOKESTATIC} inserted inside
 * its own body. Two instructions before a {@code RETURN} change no stack map frame, so
 * {@code COMPUTE_MAXS} is enough. {@code transform()} never throws.
 */
public final class ModelManagerPatcher implements ClassFileTransformer {

    public static final String TARGET = "net/minecraft/client/resources/model/ModelManager";
    private static final String HOOK_OWNER = "dev/umb/objbridge/ObjBridge";
    private static final String HOOK_NAME = "onModelsApplied";
    private static final String HOOK_DESC = "(Lnet/minecraft/client/resources/model/ModelManager;)V";

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
                // the installer takes exactly one argument (the ReloadState record) and returns void
                if ("apply".equals(mn.name) && mn.desc.endsWith(")V")
                        && mn.desc.contains("ReloadState")) {
                    target = mn;
                    break;
                }
            }
            if (target == null) {
                // fall back to any single-arg void apply(...)
                for (MethodNode mn : cn.methods) {
                    if ("apply".equals(mn.name) && mn.desc.endsWith(")V")) {
                        target = mn;
                        break;
                    }
                }
            }
            if (target == null) {
                result = "PATCH-FAILED ModelManager.apply(..)V not found";
                ObjLog.loud(result);
                return null;
            }

            int inserted = 0;
            InsnList insns = target.instructions;
            for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    insns.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.insertBefore(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
                    inserted++;
                }
            }
            if (inserted == 0) {
                result = "PATCH-FAILED ModelManager.apply has no RETURN";
                ObjLog.loud(result);
                return null;
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            result = "ModelManager.apply" + target.desc + "@return x" + inserted;
            ObjLog.loud("PATCHED " + result);
            return cw.toByteArray();
        } catch (Throwable t) {
            result = "PATCH-FAILED ModelManager: " + t;
            ObjLog.loud(result);
            ObjLog.error("ModelManagerPatcher.transform", t, 6);
            return null;
        }
    }
}
