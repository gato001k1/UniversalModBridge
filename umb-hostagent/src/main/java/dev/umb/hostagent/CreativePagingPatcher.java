package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds creative-tab filtering, column remapping, page input, and the page hint. New branch targets
 * receive explicit frames because computing frames would require the game's full class hierarchy.
 */
public final class CreativePagingPatcher implements ClassFileTransformer {

    public static final String TARGET =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

    static final String HOOKS = "dev/umb/hostagent/Hooks";
    static final String TABS = "net/minecraft/world/item/CreativeModeTabs";
    static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    static final String LIST = "()Ljava/util/List;";

    static final String M_SCROLL = "mouseScrolled";
    static final String D_SCROLL = "(DDDD)Z";
    static final String M_KEY = "keyPressed";
    static final String D_KEY = "(Lnet/minecraft/client/input/KeyEvent;)Z";
    static final String M_RENDER = "extractRenderState";
    static final String D_RENDER = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    /** What actually got rewritten, for the log and for the unit test. */
    public static volatile Map<String, Integer> lastCounts = new LinkedHashMap<>();
    public static volatile boolean applied = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            byte[] patched = patch(classfileBuffer);
            if (patched == null) {
                AgentLog.loud("PATCH-FAILED CreativeModeInventoryScreen paging: " + describe(lastCounts));
                return null;
            }
            applied = true;
            AgentLog.loud("PATCHED CreativeModeInventoryScreen tab paging " + describe(lastCounts));
            return patched;
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED CreativeModeInventoryScreen paging: " + t);
            AgentLog.error("CreativePagingPatcher.transform", t, 5);
            return null; // never throw out of a transformer
        }
    }

    static String describe(Map<String, Integer> c) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Returns the patched bytes, or null when any of the five edits found nothing.
     * Public so the unit test can run it against the real client.jar bytes.
     */
    public static byte[] patch(byte[] original) {
        ClassNode cn = new ClassNode();
        // EXPAND_FRAMES: edits 3 and 4 add a branch target, so a frame has to be inserted, and
        // mixing an explicit F_NEW frame with compressed frames read back from the class is not
        // allowed. Expanded in, recompressed by the writer.
        new ClassReader(original).accept(cn, ClassReader.EXPAND_FRAMES);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("tabs", 0);
        counts.put("column", 0);
        counts.put("scroll", 0);
        counts.put("key", 0);
        counts.put("hint", 0);

        for (MethodNode m : cn.methods) {
            if (m.instructions == null) continue;
            counts.merge("tabs", redirectTabs(m), Integer::sum);
            if ("getTabX".equals(m.name) || "extractTabButton".equals(m.name)) {
                counts.merge("column", redirectColumn(m), Integer::sum);
            }
            if (M_SCROLL.equals(m.name) && D_SCROLL.equals(m.desc)) {
                counts.merge("scroll", earlyReturn(cn, m, scrollArgs(cn), "creativeScroll",
                        "(Ljava/lang/Object;DDDD)Z"), Integer::sum);
            }
            if (M_KEY.equals(m.name) && D_KEY.equals(m.desc)) {
                counts.merge("key", earlyReturn(cn, m, keyArgs(cn), "creativeKey",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z"), Integer::sum);
            }
            if (M_RENDER.equals(m.name) && D_RENDER.equals(m.desc)) {
                counts.merge("hint", pageHint(m), Integer::sum);
            }
        }
        lastCounts = counts;

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() == 0) return null;
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static int redirectTabs(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) in;
            if (!TABS.equals(call.owner) || !"tabs".equals(call.name) || !LIST.equals(call.desc)) continue;
            m.instructions.set(call,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "visibleTabs", LIST, false));
            n++;
        }
        return n;
    }

    private static int redirectColumn(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) in;
            if (!TAB.equals(call.owner) || !"column".equals(call.name) || !"()I".equals(call.desc)) continue;
            m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "tabColumn",
                    "(L" + TAB + ";)I", false));
            n++;
        }
        return n;
    }

    /** {@code this, mouseX, mouseY, scrollX, scrollY} - locals 0, 1, 3, 5, 7. */
    private static List<Object> scrollArgs(ClassNode cn) {
        List<Object> frame = new ArrayList<>();
        frame.add(cn.name);
        frame.add(Opcodes.DOUBLE);
        frame.add(Opcodes.DOUBLE);
        frame.add(Opcodes.DOUBLE);
        frame.add(Opcodes.DOUBLE);
        return frame;
    }

    /** {@code this, keyEvent} - locals 0, 1. */
    private static List<Object> keyArgs(ClassNode cn) {
        List<Object> frame = new ArrayList<>();
        frame.add(cn.name);
        frame.add("net/minecraft/client/input/KeyEvent");
        return frame;
    }

    /**
     * Prepend {@code if (Hooks.<hook>(this, args...)) return true;}.
     *
     * The inserted frame is exactly the method's implicit entry frame (all parameters, empty
     * stack), written as F_NEW so the ClassWriter can recompress the whole table around it.
     */
    private static int earlyReturn(ClassNode cn, MethodNode m, List<Object> frameLocals,
                                   String hook, String hookDesc) {
        AbstractInsnNode first = m.instructions.getFirst();
        if (first instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && HOOKS.equals(call.owner)) {
            return 0; // already patched
        }
        LabelNode cont = new LabelNode(new Label());
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (frameLocals.size() == 5) {
            pre.add(new VarInsnNode(Opcodes.DLOAD, 1));
            pre.add(new VarInsnNode(Opcodes.DLOAD, 3));
            pre.add(new VarInsnNode(Opcodes.DLOAD, 5));
            pre.add(new VarInsnNode(Opcodes.DLOAD, 7));
        } else {
            pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        }
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, hookDesc, false));
        pre.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, cont));
        pre.add(new InsnNode(Opcodes.ICONST_1));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(cont);
        pre.add(new FrameNode(Opcodes.F_NEW, frameLocals.size(), frameLocals.toArray(), 0, new Object[0]));
        m.instructions.insert(pre);
        return 1;
    }

    private static int pageHint(MethodNode m) {
        int n = 0;
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.RETURN) returns.add(in);
        }
        for (AbstractInsnNode ret : returns) {
            AbstractInsnNode prev = ret.getPrevious();
            if (prev instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC && HOOKS.equals(call.owner)
                    && "renderPageHint".equals(call.name)) {
                continue; // already patched
            }
            InsnList add = new InsnList();
            add.add(new VarInsnNode(Opcodes.ALOAD, 1)); // GuiGraphicsExtractor
            add.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the screen
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "renderPageHint",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            m.instructions.insertBefore(ret, add);
            n++;
        }
        return n;
    }
}
