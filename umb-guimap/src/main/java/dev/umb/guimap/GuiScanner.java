package dev.umb.guimap;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds every concrete class in the jar whose superclass chain reaches vanilla
 * {@code GuiContainer} or (failing that) {@code GuiScreen}, per GENERALIZATION-PLAN.md GAP 2's
 * target-class definition. Abstract classes are skipped — nothing gets on-screen for them and
 * the plan's numbers are about concrete, instantiable GUIs.
 */
public final class GuiScanner {

    public enum Kind { GUI_CONTAINER, GUI_SCREEN_ONLY }

    public static final class Candidate {
        public final ClassNode cn;
        public final Kind kind;
        Candidate(ClassNode cn, Kind kind) { this.cn = cn; this.kind = kind; }
    }

    private final JarIndex jar;

    public GuiScanner(JarIndex jar) { this.jar = jar; }

    public List<Candidate> scan() {
        List<Candidate> out = new ArrayList<>();
        for (ClassNode cn : jar.classes.values()) {
            if ((cn.access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0) continue;
            if ((cn.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) continue;
            if (reaches(cn.name, Vanilla.GUI_CONTAINER)) out.add(new Candidate(cn, Kind.GUI_CONTAINER));
            else if (reaches(cn.name, Vanilla.GUI_SCREEN)) out.add(new Candidate(cn, Kind.GUI_SCREEN_ONLY));
        }
        return out;
    }

    /** True if {@code internalName}'s superclass chain reaches {@code target}, vanilla base included. */
    private boolean reaches(String internalName, String target) {
        String cur = internalName;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur)) {
            if (cur.equals(target)) return true;
            ClassNode cn = jar.cls(cur);
            if (cn == null) return false; // left the jar without hitting target (e.g. hit Object)
            cur = cn.superName;
        }
        return false;
    }
}
