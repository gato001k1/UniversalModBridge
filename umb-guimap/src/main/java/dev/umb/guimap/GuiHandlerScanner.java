package dev.umb.guimap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cross-check pairing source (GENERALIZATION-PLAN.md GAP 2's "and/or"): every vanilla
 * {@code cpw.mods.fml.common.network.IGuiHandler} implementor switches on the same {@code guiId}
 * int in both {@code getServerGuiElement} and {@code getClientGuiElement}. This walks each
 * method's {@code tableswitch}/{@code lookupswitch} case labels and records the first
 * {@code NEW <Container-subtype>} / {@code NEW <GuiScreen-subtype>} instruction reached in each
 * case's instruction range — giving an independent {@code guiId -> (guiClass, containerClass)}
 * mapping that does not depend on the GUI class's own constructor shape at all.
 */
public final class GuiHandlerScanner {

    public static final class Pairing {
        public final int guiId; public final String handlerClass, guiClass, containerClass;
        Pairing(int id, String h, String g, String c) { guiId = id; handlerClass = h; guiClass = g; containerClass = c; }
    }

    private final JarIndex jar;

    public GuiHandlerScanner(JarIndex jar) { this.jar = jar; }

    public List<Pairing> scan() {
        List<Pairing> out = new ArrayList<>();
        for (ClassNode cn : jar.implementorsOf(Vanilla.IGUI_HANDLER)) {
            MethodNode client = find(cn, "getClientGuiElement");
            MethodNode server = find(cn, "getServerGuiElement");
            if (client == null || server == null) continue;
            Map<Integer, String> clientMap = firstNewPerCase(client, Vanilla.GUI_SCREEN);
            Map<Integer, String> serverMap = firstNewPerCase(server, Vanilla.CONTAINER);
            TreeSet<Integer> ids = new TreeSet<>();
            ids.addAll(clientMap.keySet());
            ids.addAll(serverMap.keySet());
            for (int id : ids) out.add(new Pairing(id, JarIndex.dotted(cn.name), clientMap.get(id), serverMap.get(id)));
        }
        return out;
    }

    private MethodNode find(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn;
        return null;
    }

    private Map<Integer, String> firstNewPerCase(MethodNode mn, String requiredSuper) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Map<LabelNode, Integer> caseOf = new HashMap<>();
        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof TableSwitchInsnNode t) {
                int key = t.min;
                for (LabelNode l : t.labels) caseOf.put(l, key++);
            } else if (in instanceof LookupSwitchInsnNode l) {
                for (int i = 0; i < l.keys.size(); i++) caseOf.put(l.labels.get(i), l.keys.get(i));
            }
        }
        if (caseOf.isEmpty()) return out;
        Integer current = null;
        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof LabelNode ln && caseOf.containsKey(ln)) { current = caseOf.get(ln); continue; }
            if (current != null && in.getOpcode() == Opcodes.NEW) {
                String t = ((TypeInsnNode) in).desc;
                if (!out.containsKey(current) && jar.isSubclassOf(t, requiredSuper)) out.put(current, JarIndex.dotted(t));
            }
        }
        return out;
    }
}
