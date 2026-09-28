package dev.umb.guimap;

import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pairs a GUI class with the {@code net.minecraft.inventory.Container} it displays, per
 * GENERALIZATION-PLAN.md GAP 2's pairing requirement — the host keys everything by container.
 *
 * <p>A Java constructor is REQUIRED by the JVM to invoke its direct superclass's {@code <init>}
 * via {@code invokespecial} exactly once. For a {@code GuiContainer} subclass that superclass
 * constructor accepts (or, one level further up a mod's own abstract base class, eventually
 * forwards) a {@code Container}. We simulate the GUI class's own {@code <init>}, find that
 * {@code invokespecial}, and inspect whichever argument the target descriptor types as
 * {@code Container} (or a subtype declared in the jar):
 * <ul>
 *   <li>a freshly constructed object ({@code new FooContainer(...)}) — the container class is
 *       {@code FooContainer} directly, confidence {@code exact}.</li>
 *   <li>a passed-through constructor parameter — the container's STATIC type is read off the
 *       GUI constructor's own descriptor for that parameter slot, confidence {@code exact} if
 *       that declared type is a concrete Container subclass in the jar, else {@code inferred}
 *       (declared as the vanilla {@code Container} base — a real class exists at runtime but the
 *       static type doesn't say which).</li>
 *   <li>anything else (a field, a static call result, ...) — {@code unresolved}, with the reason.</li>
 * </ul>
 * A plain {@code GuiScreen} (not a {@code GuiContainer}) that never calls a Container-taking
 * super constructor legitimately has no container — reported as confidence {@code none}.
 *
 * <p>Some mods put the Container-constructing logic in an intermediate ABSTRACT base class
 * rather than the concrete GUI itself — e.g. HBM's eleven {@code GUITurretXxx} classes all just
 * forward {@code (InventoryPlayer, TileEntity)} to {@code GUITurretBase}, which is the class that
 * actually does {@code new ContainerTurretBase(...)}. When the concrete class's own super-call
 * doesn't carry a Container argument, we repeat the same inspection one level up (the abstract
 * base's own {@code <init>} against ITS direct superclass), and so on up the jar-declared chain,
 * since whichever level constructs the Container is the one that ends up backing every subclass.
 */
public final class ContainerPairer {

    private final JarIndex jar;

    public ContainerPairer(JarIndex jar) { this.jar = jar; }

    public JsonObject pair(ClassNode cn, GuiScanner.Kind kind) {
        JsonObject out = new JsonObject();
        if (kind == GuiScanner.Kind.GUI_SCREEN_ONLY) {
            out.addProperty("className", (String) null);
            out.addProperty("confidence", "none");
            out.addProperty("source", "class extends GuiScreen directly, not GuiContainer;"
                    + " no vanilla Container-taking super constructor exists to inspect");
            return out;
        }

        Set<String> visited = new LinkedHashSet<>();
        ClassNode level = cn;
        int hops = 0;
        while (level != null && visited.add(level.name)) {
            Attempt a = attemptAt(level);
            if (a.result != null) {
                if (hops > 0) {
                    a.result.addProperty("source", a.result.get("source").getAsString()
                            + " (found " + hops + " superclass level(s) above " + JarIndex.dotted(cn.name) + ")");
                }
                return a.result;
            }
            if (a.unresolvedFinal != null) return a.unresolvedFinal; // a real value was found, just not a usable one
            level = level.superName == null ? null : jar.cls(level.superName); // stop once we leave the jar
            hops++;
        }
        out.addProperty("className", (String) null);
        out.addProperty("confidence", "unresolved");
        out.addProperty("source", "walked " + hops + " superclass level(s) from " + JarIndex.dotted(cn.name)
                + " without finding a constructor whose direct-super call carries a Container-typed argument");
        return out;
    }

    private static final class Attempt {
        JsonObject result;          // non-null: found something usable, stop and return this
        JsonObject unresolvedFinal; // non-null: found a Container-typed arg but couldn't name a class - stop, don't keep walking
    }

    /** One level of the walk: inspect {@code level}'s own primary constructor's direct-super call. */
    private Attempt attemptAt(ClassNode level) {
        Attempt out = new Attempt();
        MethodNode ctor = pickPrimaryCtor(level);
        if (ctor == null) return out; // no constructor here - keep walking up
        Type[] ctorParams = Type.getArgumentTypes(ctor.desc);

        Val[] superArgs = new Val[1];
        String[] superOwner = new String[1];
        MethodSim.run(level, ctor, (insn, stack, locals) -> {
            if (insn.getOpcode() != Opcodes.INVOKESPECIAL) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            if (!"<init>".equals(m.name)) return;
            if (!m.owner.equals(level.superName)) return; // the ONE call to level's direct superclass's <init>
            Type[] args = Type.getArgumentTypes(m.desc);
            int containerIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i].getSort() == Type.OBJECT && isContainerType(args[i].getInternalName())) { containerIdx = i; break; }
            }
            if (containerIdx < 0) return; // this super ctor doesn't take a Container at all
            int fromTop = args.length - 1 - containerIdx;
            if (fromTop < 0 || fromTop >= stack.size()) return;
            superArgs[0] = stack.get(stack.size() - 1 - fromTop);
            superOwner[0] = m.owner;
        });

        if (superArgs[0] == null) return out; // this level's super ctor takes no Container - keep walking up

        Val v = superArgs[0];
        if (v.kind == Val.Kind.NEW_OBJ && isContainerType(v.typeName)) {
            JsonObject r = new JsonObject();
            r.addProperty("className", JarIndex.dotted(v.typeName));
            r.addProperty("confidence", "exact");
            r.addProperty("source", "constructed inline in " + JarIndex.dotted(level.name)
                    + "'s constructor and passed straight to super(" + JarIndex.dotted(superOwner[0]) + ")");
            out.result = r;
            return out;
        }
        if (v.kind == Val.Kind.PARAM) {
            int argIndex = v.numberValue.intValue();
            if (argIndex >= 0 && argIndex < ctorParams.length && ctorParams[argIndex].getSort() == Type.OBJECT) {
                String t = ctorParams[argIndex].getInternalName();
                boolean concrete = jar.cls(t) != null && !t.equals(Vanilla.CONTAINER);
                JsonObject r = new JsonObject();
                r.addProperty("className", JarIndex.dotted(t));
                r.addProperty("confidence", concrete ? "exact" : "inferred");
                r.addProperty("source", "constructor parameter #" + argIndex + " (declared type "
                        + JarIndex.dotted(t) + ") forwarded unchanged to super(" + JarIndex.dotted(superOwner[0]) + ")"
                        + " on " + JarIndex.dotted(level.name));
                out.result = r;
                return out;
            }
        }
        JsonObject u = new JsonObject();
        u.addProperty("className", (String) null);
        u.addProperty("confidence", "unresolved");
        u.addProperty("source", "super(" + JarIndex.dotted(superOwner[0]) + ") Container argument on "
                + JarIndex.dotted(level.name) + " resolved to " + v + " — not a `new` or a passed-through parameter");
        out.unresolvedFinal = u;
        return out;
    }

    private boolean isContainerType(String internalName) {
        return jar.isSubclassOf(internalName, Vanilla.CONTAINER);
    }

    private MethodNode pickPrimaryCtor(ClassNode cn) {
        MethodNode best = null;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name)) continue;
            if (best == null || Type.getArgumentTypes(mn.desc).length > Type.getArgumentTypes(best.desc).length) best = mn;
        }
        return best;
    }
}
