package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Adds the universal UMB button to both the in-game pause menu and Options screen. */
public final class UmbMenuPatcher implements ClassFileTransformer {
    private static final String PAUSE = "net/minecraft/client/gui/screens/PauseScreen";
    private static final String OPTIONS = "net/minecraft/client/gui/screens/options/OptionsScreen";
    private static final String HOOK = "dev/umb/hostagent/Hooks";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String BUTTON = "net/minecraft/client/gui/components/Button";
    private static final String LISTENER = "net/minecraft/client/gui/components/events/GuiEventListener";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain,
                            byte[] bytes) {
        if (!PAUSE.equals(name) && !OPTIONS.equals(name)) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int count = 0;
            for (MethodNode method : node.methods) {
                if (!"init".equals(method.name) || !"()V".equals(method.desc)) continue;
                AbstractInsnNode last = method.instructions.getLast();
                while (last != null && last.getOpcode() == -1) last = last.getPrevious();
                if (last == null || last.getOpcode() != Opcodes.RETURN) continue;
                InsnList add = new InsnList();
                // Stack must be {this(receiver), button}: one `this` is the addRenderableWidget
                // receiver, the other is consumed by umbMenuButton(Screen).
                add.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                add.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "umbMenuButton",
                        "(L" + SCREEN + ";)L" + BUTTON + ";", false));
                add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SCREEN, "addRenderableWidget",
                        "(L" + LISTENER + ";)L" + LISTENER + ";", false));
                add.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
                method.instructions.insertBefore(last, add);
                count++;
            }
            if (count == 0) return null;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            AgentLog.loud("PATCHED UMB menu button in " + name);
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.error("UmbMenuPatcher " + name, t, 3);
            return null;
        }
    }
}
