package dev.umb.legacy1122.legacyside;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Rewrites only Forge's Java-8 final-field/enum internals (EnumHelper and
 * ObjectHolderRef$FinalFieldHelper, both built on sun.reflect.ReflectionFactory.newFieldAccessor,
 * which Java 25 removed); all other classes pass through unchanged.
 */
public final class Legacy1122EnumHelperTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraftforge.common.util.EnumHelper";
    private static final String HOLDER = "net.minecraftforge.registries.ObjectHolderRef$FinalFieldHelper";
    private static final String SHIM = "dev/umb/legacy1122/legacyside/EnumHelperShim";
    private static final String MAKE = "(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Enum;";
    private static final String FIELD = "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";

    @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return basicClass;
        if (HOLDER.equals(name) || HOLDER.equals(transformedName)) return rewriteHolder(basicClass);
        if (!(TARGET.equals(name) || TARGET.equals(transformedName))) return basicClass;
        ClassNode node = new ClassNode(); new ClassReader(basicClass).accept(node, 0);
        for (MethodNode m : node.methods) {
            if ("setup".equals(m.name) && "()V".equals(m.desc)) {
                m.instructions = new InsnList(); m.instructions.add(new InsnNode(Opcodes.RETURN));
                m.tryCatchBlocks.clear(); m.maxStack = 0; m.maxLocals = 0;
            } else if ("makeEnum".equals(m.name) && MAKE.equals(m.desc)) {
                m.instructions = new InsnList();
                for (int i = 0; i < 5; i++) m.instructions.add(new VarInsnNode(i == 2 ? Opcodes.ILOAD : Opcodes.ALOAD, i));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SHIM, "makeEnum", MAKE, false));
                m.instructions.add(new InsnNode(Opcodes.ARETURN)); m.tryCatchBlocks.clear(); m.maxStack = 5; m.maxLocals = 5;
            } else if ("setFailsafeFieldValue".equals(m.name) && FIELD.equals(m.desc)) {
                m.instructions = new InsnList();
                for (int i = 0; i < 3; i++) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, i));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SHIM, "setFailsafeFieldValue", FIELD, false));
                m.instructions.add(new InsnNode(Opcodes.RETURN)); m.tryCatchBlocks.clear(); m.maxStack = 3; m.maxLocals = 3;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * ObjectHolderRef$FinalFieldHelper: its static init reflects on removed JDK internals (the
     * ReflectionFactory accessor and Field.modifiers), which failed preInit for every mod with an
     * @ObjectHolder. makeWritable only needs setAccessible; setField writes through the Unsafe shim.
     */
    public static byte[] rewriteHolder(byte[] basicClass) {
        ClassNode node = new ClassNode(); new ClassReader(basicClass).accept(node, 0);
        for (MethodNode m : node.methods) {
            if ("<clinit>".equals(m.name)) {
                m.instructions = new InsnList(); m.instructions.add(new InsnNode(Opcodes.RETURN));
                m.tryCatchBlocks.clear(); m.localVariables = null;
            } else if ("makeWritable".equals(m.name)
                    && "(Ljava/lang/reflect/Field;)Ljava/lang/reflect/Field;".equals(m.desc)) {
                m.instructions = new InsnList();
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new InsnNode(Opcodes.ICONST_1));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field",
                        "setAccessible", "(Z)V", false));
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new InsnNode(Opcodes.ARETURN));
                m.tryCatchBlocks.clear(); m.localVariables = null;
            } else if ("setField".equals(m.name) && FIELD.equals(m.desc)) {
                m.instructions = new InsnList();
                for (int i = 0; i < 3; i++) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, i));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SHIM, "setFailsafeFieldValue", FIELD, false));
                m.instructions.add(new InsnNode(Opcodes.RETURN));
                m.tryCatchBlocks.clear(); m.localVariables = null;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
