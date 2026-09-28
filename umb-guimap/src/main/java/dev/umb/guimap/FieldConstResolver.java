package dev.umb.guimap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lazily resolves "what constant did this field actually get assigned" by re-running
 * {@link MethodSim} over the declaring class's {@code <clinit>} and every {@code <init>} the
 * first time that class is asked about. Two kinds of field are tracked, both vanilla-generic:
 *
 * <ul>
 *   <li>{@code int} fields assigned a literal constant — this is exactly how every GuiContainer
 *       subclass sets {@link Vanilla#F_XSIZE}/{@link Vanilla#F_YSIZE} (a {@code sipush}/{@code putfield}
 *       pair in the constructor), and how a plain {@code GuiScreen} subclass sets its own
 *       (non-vanilla-named) panel-size fields the same way.</li>
 *   <li>{@code net.minecraft.util.ResourceLocation} fields assigned {@code new ResourceLocation(...)}
 *       with literal string argument(s) — this is how every GUI's background texture field is
 *       initialised.</li>
 * </ul>
 *
 * A field written with two different constants (from two constructors, or a conditional branch)
 * is recorded as {@code conflict} rather than guessed at.
 */
public final class FieldConstResolver {

    public static final class IntField {
        public final int value; public final boolean conflict;
        IntField(int v, boolean c) { value = v; conflict = c; }
    }

    public static final class TexField {
        public final String resolved; // "domain:path", or null if unresolved
        public final boolean conflict;
        public final String reason;   // set when resolved == null
        TexField(String r, boolean c, String reason) { resolved = r; conflict = c; this.reason = reason; }
    }

    private final JarIndex jar;
    private final Set<String> scanned = new HashSet<>();
    private final Map<String, IntField> intFields = new HashMap<>();
    private final Map<String, TexField> texFields = new HashMap<>();

    public FieldConstResolver(JarIndex jar) { this.jar = jar; }

    private void ensureScanned(String ownerInternal) {
        if (!scanned.add(ownerInternal)) return;
        ClassNode cn = jar.cls(ownerInternal);
        if (cn == null) return;
        for (MethodNode mn : cn.methods) {
            if (!"<clinit>".equals(mn.name) && !"<init>".equals(mn.name)) continue;
            MethodSim.run(cn, mn, (insn, stack, locals) -> {
                int op = insn.getOpcode();
                if (op != Opcodes.PUTSTATIC && op != Opcodes.PUTFIELD) return;
                FieldInsnNode f = (FieldInsnNode) insn;
                if (stack.isEmpty()) return;
                Val value = stack.get(stack.size() - 1);
                if (op == Opcodes.PUTFIELD) {
                    if (stack.size() < 2) return;
                    Val objRef = stack.get(stack.size() - 2);
                    if (objRef.kind != Val.Kind.THIS) return; // only `this.field = ...`, never aliasing
                }
                String key = ownerInternal + "." + f.name;
                if ("I".equals(f.desc) && value.kind == Val.Kind.NUMBER) {
                    recordInt(key, value.numberValue.intValue());
                } else if (("L" + Vanilla.RESOURCE_LOCATION + ";").equals(f.desc)
                        && value.kind == Val.Kind.NEW_OBJ && Vanilla.RESOURCE_LOCATION.equals(value.typeName)) {
                    recordTex(key, resolveResourceLocationCtor(value));
                }
            });
        }
    }

    private void recordInt(String key, int v) {
        IntField prev = intFields.get(key);
        if (prev == null) intFields.put(key, new IntField(v, false));
        else if (!prev.conflict && prev.value != v) intFields.put(key, new IntField(prev.value, true));
    }

    private void recordTex(String key, TexField t) {
        TexField prev = texFields.get(key);
        if (prev == null) { texFields.put(key, t); return; }
        if (!prev.conflict && prev.resolved != null && t.resolved != null && !prev.resolved.equals(t.resolved)) {
            texFields.put(key, new TexField(prev.resolved, true, null));
        }
        // if the new write is unresolved but we already have a resolved one, keep the resolved one
    }

    /** {@code new ResourceLocation("domain:path")} or {@code new ResourceLocation("domain","path")}. */
    static TexField resolveResourceLocationCtor(Val newObj) {
        List<Val> args = newObj.ctorArgs;
        if (args == null || args.isEmpty() || args.size() > 2) {
            return new TexField(null, false, "unexpected ResourceLocation ctor arity " + (args == null ? 0 : args.size()));
        }
        if (args.size() == 1) {
            Val a = args.get(0);
            if (a.kind == Val.Kind.STRING) return new TexField(a.stringValue, false, null);
            return new TexField(null, false, "non-literal ResourceLocation(" + a + ")");
        }
        Val d = args.get(0), p = args.get(1);
        if (d.kind == Val.Kind.STRING && p.kind == Val.Kind.STRING) {
            return new TexField(d.stringValue + ":" + p.stringValue, false, null);
        }
        return new TexField(null, false, "non-literal ResourceLocation(" + d + "," + p + ")");
    }

    /** Resolves a field the analyser saw with GETSTATIC/GETFIELD owner/name, as a constant int. */
    public IntField intField(String owner, String name) {
        ensureScanned(owner);
        return intFields.get(owner + "." + name);
    }

    /** Resolves a field the analyser saw bound as a texture, as a "domain:path" constant. */
    public TexField texField(String owner, String name) {
        ensureScanned(owner);
        return texFields.get(owner + "." + name);
    }
}
