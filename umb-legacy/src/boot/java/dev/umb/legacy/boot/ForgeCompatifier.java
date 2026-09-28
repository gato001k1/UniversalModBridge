package dev.umb.legacy.boot;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** UMB-authored install-time Forge compatibility patch; never bundled as a Forge runtime jar. */
public final class ForgeCompatifier {
    private ForgeCompatifier() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ForgeCompatifier <forge-srg.jar>");
        Path input = Path.of(args[0]);
        Path temp = input.resolveSibling(input.getFileName() + ".compat.tmp");
        Files.deleteIfExists(temp);
        try (JarFile in = new JarFile(input.toFile()); JarOutputStream out = new JarOutputStream(new FileOutputStream(temp.toFile()))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry source = entries.nextElement();
                if (source.isDirectory()) continue;
                String name = source.getName();
                if (name.equals("META-INF/MANIFEST.MF") || name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) continue;
                byte[] bytes;
                try (InputStream stream = in.getInputStream(source)) { bytes = stream.readAllBytes(); }
                if (name.equals("cpw/mods/fml/common/registry/ObjectHolderRef.class")) bytes = patchObjectHolderRef(bytes);
                JarEntry copy = new JarEntry(name);
                copy.setTime(0L);
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
        }
        Files.move(temp, input, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("forge-compatifier=ObjectHolderRef-name-fallback");
    }

    private static byte[] patchObjectHolderRef(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (method.name.equals("apply") && method.desc.equals("()V")) {
                InsnList guard = new InsnList();
                LabelNode continueApply = new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "injectedObject", "Ljava/lang/String;"));
                guard.add(new JumpInsnNode(Opcodes.IFNONNULL, continueApply));
                guard.add(new InsnNode(Opcodes.RETURN));
                guard.add(continueApply);
                method.instructions.insert(guard);
                continue;
            }
            if (!method.name.equals("<init>") || !method.desc.equals("(Ljava/lang/reflect/Field;Ljava/lang/String;Z)V")) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTFIELD || !put.owner.equals(node.name)) continue;
                if (!put.name.equals("isBlock")) continue;
                FieldInsnNode itemPut = null;
                for (var after = insn.getNext(); after != null; after = after.getNext()) {
                    if (after instanceof FieldInsnNode candidate && candidate.getOpcode() == Opcodes.PUTFIELD && candidate.owner.equals(node.name) && candidate.name.equals("isItem")) { itemPut = candidate; break; }
                }
                if (itemPut == null) break;
                method.instructions.insert(itemPut, fallbackRules(node.name));
                break;
            }
        }
        ClassWriter writer = new NoLoaderClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static InsnList fallbackRules(String owner) {
        InsnList code = new InsnList();
        LabelNode blockDone = new LabelNode();
        LabelNode itemDone = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "isBlock", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, blockDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "getType", "()Ljava/lang/Class;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        code.add(new LdcInsnNode("net.minecraft.block.Block"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, blockDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "isBlock", "Z"));
        code.add(blockDone);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "isItem", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, itemDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "getType", "()Ljava/lang/Class;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        code.add(new LdcInsnNode("net.minecraft.item.Item"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, itemDone));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "isItem", "Z"));
        code.add(itemDone);
        LabelNode keepChecking = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "injectedObject", "Ljava/lang/String;"));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, keepChecking));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, keepChecking));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "field", "Ljava/lang/reflect/Field;"));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(keepChecking);
        return code;
    }

    private static final class NoLoaderClassWriter extends ClassWriter {
        NoLoaderClassWriter(int flags) { super(flags); }
        @Override protected String getCommonSuperClass(String left, String right) { return Type.getInternalName(Object.class); }
    }
}
