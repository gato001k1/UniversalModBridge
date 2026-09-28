package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Produces one JSON row for a single GUI class: panel size, background art, draw calls, labels,
 * and its paired Container. See {@code umb-guimap/README.md} for the full field-by-field schema.
 */
public final class GuiClassAnalyzer {

    private final JarIndex jar;
    private final FieldConstResolver resolver;
    private final ContainerPairer pairer;

    public GuiClassAnalyzer(JarIndex jar, FieldConstResolver resolver, ContainerPairer pairer) {
        this.jar = jar; this.resolver = resolver; this.pairer = pairer;
    }

    public JsonObject analyze(GuiScanner.Candidate cand) {
        ClassNode cn = cand.cn;
        JsonObject row = new JsonObject();
        row.addProperty("className", JarIndex.dotted(cn.name));
        row.addProperty("superClass", cn.superName == null ? null : JarIndex.dotted(cn.superName));
        row.addProperty("kind", cand.kind == GuiScanner.Kind.GUI_CONTAINER ? "GuiContainer" : "GuiScreen");
        // Additive control schema: both GuiContainer and GuiScreen-only classes are scanned. The
        // scanner is bounded to GuiButton construction and func_73864_a/func_73875_a shapes.
        row.add("buttons", ButtonScanner.scan(cn));

        JsonObject containerPairing = pairer.pair(cn, cand.kind);
        row.add("container", containerPairing);
        // Internal name of the paired Container class, when the pairing named one concretely — used
        // only to classify a STATE_LINEAR source field's origin (see DrawLayerScanner.describeSource).
        // "inferred"/"none"/"unresolved" leave this null; describeSource still falls back to the
        // generic isSubclassOf(owner, Vanilla.CONTAINER) check in that case.
        String containerInternal = null;
        if (containerPairing.has("className") && !containerPairing.get("className").isJsonNull()) {
            String confidence = containerPairing.get("confidence").getAsString();
            if ("exact".equals(confidence)) containerInternal = JarIndex.internal(containerPairing.get("className").getAsString());
        }
        // SYNC-BINDING lane (schemaVersion 4): the field <-> ICrafting sync-register-id map for the
        // paired Container class, cross-checked between the server (func_71112_a call sites) and
        // client (func_75137_b) routes — see ContainerSyncScanner. Only attempted for an "exact"
        // pairing whose Container class bytecode is actually present in this jar (an "inferred"
        // pairing names only a static supertype, not a concrete class to scan).
        if (containerInternal != null) {
            ClassNode containerNode = jar.cls(containerInternal);
            if (containerNode != null) {
                JsonArray syncBindings = ContainerSyncScanner.scanContainer(containerNode);
                if (syncBindings.size() > 0) containerPairing.add("syncBindings", syncBindings);
            }
        }

        DrawLayerScanner bg = new DrawLayerScanner(jar, resolver, cn.name, containerInternal);
        DrawLayerScanner fg = new DrawLayerScanner(jar, resolver, cn.name, containerInternal);
        JsonObject size;

        if (cand.kind == GuiScanner.Kind.GUI_CONTAINER) {
            size = sizeFromGuiContainerFields(cn);
            // A mod frequently puts the background/foreground override on a shared ABSTRACT base
            // class (e.g. HBM's GUITurretBase, shared by 11 concrete turret GUIs) rather than on
            // every concrete leaf. Walk the jar-declared superclass chain to find whichever level
            // actually declares it; MethodSim then runs on THAT class node, so any field it reads
            // resolves against wherever the bytecode really references it (the field-const
            // resolver keys purely off owner+name, not off which concrete class is being analysed).
            ClassNode bgOwner = findDeclaringClass(cn, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC);
            ClassNode fgOwner = findDeclaringClass(cn, Vanilla.M_DRAW_FG, Vanilla.M_DRAW_FG_DESC);
            if (bgOwner != null) bg.scan(bgOwner, findMethod(bgOwner, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
            else row.addProperty("note_noBackgroundMethod",
                    "no " + Vanilla.M_DRAW_BG + Vanilla.M_DRAW_BG_DESC + " override found anywhere in this"
                            + " class's jar-declared superclass chain (it is abstract on GuiContainer,"
                            + " so it must exist somewhere in the hierarchy - the chain must have left the jar)");
            if (fgOwner != null) fg.scan(fgOwner, findMethod(fgOwner, Vanilla.M_DRAW_FG, Vanilla.M_DRAW_FG_DESC));
            row.add("size", size);
            row.add("backgroundTextures", listOf(bg.textureBinds));
            row.add("backgroundDrawRects", listOf(bg.drawRects));
            row.add("foregroundLabels", listOf(fg.labels));
            row.add("foregroundDrawRects", listOf(fg.drawRects));
        } else {
            // No vanilla split exists off GuiScreen directly: scan every method declared on the
            // class itself for the same vanilla calls, so a mod that reimplements the two-layer
            // convention under its own method names (as HBM's RBMK console GUIs do) is still read
            // by its bytecode shape, never by guessing a method name.
            for (MethodNode mn : cn.methods) { bg.scan(cn, mn); }
            size = sizeFromDrawRectHeuristic(cn, bg.drawRects);
            row.add("size", size);
            row.add("backgroundTextures", listOf(bg.textureBinds));
            row.add("backgroundDrawRects", listOf(bg.drawRects));
            row.add("foregroundLabels", listOf(bg.labels));
            row.addProperty("note_noLayerSplit",
                    "class extends GuiScreen directly (not GuiContainer), so there is no vanilla"
                            + " drawGuiContainerBackgroundLayer/ForegroundLayer split to key on;"
                            + " all draw calls found anywhere in the class are reported together");
        }
        return row;
    }

    /**
     * GuiContainer path: field_146999_f / field_147000_g. Usually assigned in this class's own
     * {@code <init>}, but — same rationale as the background-method lookup above — sometimes only
     * assigned by a shared abstract base class's constructor (again: HBM's GUITurretBase sets both
     * for all 11 turret GUIs), so the search walks the jar-declared superclass chain.
     */
    private JsonObject sizeFromGuiContainerFields(ClassNode cn) {
        JsonObject o = new JsonObject();
        FieldConstResolver.IntField x = resolveIntFieldUpChain(cn, Vanilla.F_XSIZE);
        FieldConstResolver.IntField y = resolveIntFieldUpChain(cn, Vanilla.F_YSIZE);
        if (x != null && !x.conflict && y != null && !y.conflict) {
            o.addProperty("xSize", x.value);
            o.addProperty("ySize", y.value);
            o.addProperty("confidence", "exact");
            o.addProperty("source", "constructor assigns constants to field_146999_f/field_147000_g directly"
                    + " (found via " + JarIndex.dotted(cn.name) + "'s own or an ancestor's constructor)");
        } else if (x == null && y == null) {
            // subclass never overrides them -> vanilla GuiContainer's own <init> default applies
            o.addProperty("xSize", 176);
            o.addProperty("ySize", 166);
            o.addProperty("confidence", "inferred");
            o.addProperty("source", "class never assigns field_146999_f/field_147000_g;"
                    + " vanilla GuiContainer.<init> default (176x166) applies");
        } else {
            o.addProperty("xSize", x != null && !x.conflict ? x.value : null);
            o.addProperty("ySize", y != null && !y.conflict ? y.value : null);
            o.addProperty("confidence", "unresolved");
            o.addProperty("source", "field_146999_f/field_147000_g assigned a non-constant value,"
                    + " or conflicting constants across constructors");
        }
        return o;
    }

    /**
     * GuiScreen-only fallback: the first drawTexturedModalRect call whose u and v both resolve to
     * the constant 0 is, by the near-universal 1.7.10 convention, the full background blit —
     * its w/h args are the panel size.
     */
    private JsonObject sizeFromDrawRectHeuristic(ClassNode cn, java.util.List<JsonObject> rects) {
        JsonObject o = new JsonObject();
        for (JsonObject r : rects) {
            JsonArray args = r.getAsJsonArray("args");
            JsonObject u = args.get(2).getAsJsonObject(), v = args.get(3).getAsJsonObject();
            if (isConstZero(u) && isConstZero(v)) {
                JsonObject w = args.get(4).getAsJsonObject(), h = args.get(5).getAsJsonObject();
                if ("const".equals(w.get("kind").getAsString()) && "const".equals(h.get("kind").getAsString())) {
                    o.addProperty("xSize", w.get("value").getAsInt());
                    o.addProperty("ySize", h.get("value").getAsInt());
                    o.addProperty("confidence", "inferred");
                    o.addProperty("source", "u=0,v=0 drawTexturedModalRect call (background-blit convention);"
                            + " w/h resolved to constants");
                    return o;
                }
            }
        }
        o.addProperty("xSize", (Integer) null);
        o.addProperty("ySize", (Integer) null);
        o.addProperty("confidence", "unresolved");
        o.addProperty("source", "no u=0,v=0 drawTexturedModalRect call with constant w/h found on this class");
        return o;
    }

    private static boolean isConstZero(JsonObject arg) {
        return "const".equals(arg.get("kind").getAsString()) && arg.get("value").getAsInt() == 0;
    }

    private static JsonArray listOf(java.util.List<JsonObject> l) {
        JsonArray a = new JsonArray();
        for (JsonObject o : l) a.add(o);
        return a;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        return null;
    }

    /** Walks {@code cn}'s jar-declared superclass chain (itself included) for the first class
     *  that directly declares {@code name(desc)}; null once the chain leaves the jar. */
    private ClassNode findDeclaringClass(ClassNode cn, String name, String desc) {
        ClassNode level = cn;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (level != null && seen.add(level.name)) {
            if (findMethod(level, name, desc) != null) return level;
            level = level.superName == null ? null : jar.cls(level.superName);
        }
        return null;
    }

    /** Same walk as {@link #findDeclaringClass}, for a constant int field instead of a method. */
    private FieldConstResolver.IntField resolveIntFieldUpChain(ClassNode cn, String fieldName) {
        ClassNode level = cn;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (level != null && seen.add(level.name)) {
            FieldConstResolver.IntField f = resolver.intField(level.name, fieldName);
            if (f != null) return f;
            level = level.superName == null ? null : jar.cls(level.superName);
        }
        return null;
    }
}
