package dev.umb.core;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M8-2: extracts {@link MixinSelector} IR from ASM ClassNode trees.
 * Reads the SpongePowered annotation shapes directly (no runtime), matching
 * injection family by descriptor and parsing @At/@Slice members via
 * {@link AtSelector} and {@link MemberSelector}.
 * Synthetic fixtures only; no guessing — invalid selectors kept with errors.
 */
public final class MixinSelectorParser {

    private MixinSelectorParser() {}

    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SLICE_DESC = "Lorg/spongepowered/asm/mixin/injection/Slice;";
    private static final String DESC_ANN = "Lorg/spongepowered/asm/mixin/injection/Desc;";

    /** Parses all {@link MixinSelector} entries from a class node. */
    public static List<MixinSelector> parseClass(ClassNode node) {
        Objects.requireNonNull(node, "node");
        List<MixinSelector> out = new ArrayList<>();
        String internalName = node.name;
        for (MethodNode mn : node.methods) {
            for (AnnotationNode ann : visibleAndInvisible(mn)) {
                InjectionPointKind kind = InjectionPointKind.fromDescriptor(ann.desc);
                if (kind == InjectionPointKind.OTHER) continue;
                if (kind.isInjector() || kind == InjectionPointKind.OVERWRITE) {
                    out.add(parseMethod(internalName, mn, ann, kind));
                }
            }
        }
        return List.copyOf(out);
    }

    private static MixinSelector parseMethod(String internalName, MethodNode mn, AnnotationNode ann, InjectionPointKind kind) {
        // method selectors (string list) — absent means empty (e.g. @ModifyConstant without method)
        List<MemberSelector> targets = new ArrayList<>();
        Object methodVal = get(ann, "method");
        if (methodVal instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    targets.add(MemberSelector.parse(s));
                }
            }
        } else if (methodVal instanceof String s) {
            targets.add(MemberSelector.parse(s));
        }
        // Dynamic @Desc targets — store as method-like selectors when present
        Object targetVal = get(ann, "target");
        if (targetVal instanceof List<?> tlist) {
            for (Object o : tlist) {
                if (o instanceof AnnotationNode descNode && DESC_ANN.equals(descNode.desc)) {
                    String owner = str(descNode, "owner");
                    String name = str(descNode, "value");
                    String ret = str(descNode, "ret");
                    // Build synthetic selector: owner.name(args)ret when enough info; else name-only
                    StringBuilder sb = new StringBuilder();
                    if (owner != null) sb.append(owner.replace('.', '/')).append('.');
                    if (name != null) sb.append(name);
                    else sb.append("*");
                    Object argsObj = get(descNode, "args");
                    String args = null;
                    if (argsObj instanceof List<?> al) {
                        StringBuilder asb = new StringBuilder("(");
                        for (Object a : al) {
                            if (a instanceof org.objectweb.asm.Type t) asb.append(t.getDescriptor());
                            else if (a instanceof String ds) asb.append(ds);
                        }
                        asb.append(")");
                        args = asb.toString();
                    }
                    if (args != null) sb.append(args);
                    if (ret != null) {
                        if (args == null) sb.append("()");
                        sb.append(ret);
                    }
                    String synthetic = sb.toString();
                    // Only add if not purely "*"
                    if (!"*".equals(synthetic)) {
                        MemberSelector ms = MemberSelector.parse(synthetic);
                        targets.add(ms);
                    }
                } else if (o instanceof String s) {
                    targets.add(MemberSelector.parse(s));
                }
            }
        } else if (targetVal instanceof AnnotationNode descNode && DESC_ANN.equals(descNode.desc)) {
            String owner = str(descNode, "owner");
            String name = str(descNode, "value");
            if (name != null) {
                StringBuilder sb = new StringBuilder();
                if (owner != null) sb.append(owner.replace('.', '/')).append('.');
                sb.append(name);
                targets.add(MemberSelector.parse(sb.toString()));
            }
        }

        // @At
        List<AtSelector> ats = new ArrayList<>();
        Object atVal = get(ann, "at");
        if (atVal instanceof AnnotationNode singleAt) {
            ats.add(parseAt(singleAt));
        } else if (atVal instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AnnotationNode at) ats.add(parseAt(at));
            }
        }

        // @Slice
        List<MixinSelector.SliceSpec> slices = new ArrayList<>();
        Object sliceVal = get(ann, "slice");
        if (sliceVal instanceof AnnotationNode singleSl) {
            slices.add(parseSlice(singleSl));
        } else if (sliceVal instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AnnotationNode sl) slices.add(parseSlice(sl));
            }
        }

        int require = intOr(ann, "require", -1);
        int expect = intOr(ann, "expect", -1);
        Integer allow = intBox(ann, "allow");
        String sliceRef = str(ann, "slice"); // when slice is an id string (rare)
        if (sliceRef != null && get(ann, "slice") instanceof AnnotationNode) {
            sliceRef = null; // it's an annotation, not an id
        }

        return new MixinSelector(internalName, mn.name, mn.desc, kind, targets, ats, slices, require, expect, allow, sliceRef);
    }

    private static AtSelector parseAt(AnnotationNode at) {
        String value = str(at, "value");
        String target = str(at, "target");
        Integer ordinal = intBox(at, "ordinal");
        Integer opcode = intBox(at, "opcode");
        @SuppressWarnings("unchecked")
        List<String> args = null;
        Object rawArgs = get(at, "args");
        if (rawArgs instanceof List<?> list) {
            args = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) args.add(s);
            }
        }
        String slice = str(at, "slice");
        String id = str(at, "id");
        Boolean remap = boolBox(at, "remap");
        Boolean unsafe = boolBox(at, "unsafe");
        String shift = str(at, "shift");
        Integer by = intBox(at, "by");
        // Handle enum shift value (ASM stores enums as String[] { desc, value })
        if (shift == null) {
            Object shiftObj = get(at, "shift");
            if (shiftObj instanceof String[] arr && arr.length == 2) shift = arr[1];
            else if (shiftObj instanceof List<?> sl && sl.size() == 2 && sl.get(1) instanceof String s) shift = s;
        }
        return AtSelector.parse(value, target, ordinal, opcode, args, slice, id, remap, unsafe, shift, by);
    }

    private static MixinSelector.SliceSpec parseSlice(AnnotationNode sl) {
        Object fromObj = get(sl, "from");
        Object toObj = get(sl, "to");
        String sid = str(sl, "id");
        AtSelector from = null, to = null;
        if (fromObj instanceof AnnotationNode f) from = parseAt(f);
        if (toObj instanceof AnnotationNode t) to = parseAt(t);
        return new MixinSelector.SliceSpec(from, to, sid);
    }

    // ------------------------------------------------------------------ ASM helpers

    private static List<AnnotationNode> visibleAndInvisible(MethodNode mn) {
        List<AnnotationNode> out = new ArrayList<>();
        if (mn.visibleAnnotations != null) out.addAll(mn.visibleAnnotations);
        if (mn.invisibleAnnotations != null) out.addAll(mn.invisibleAnnotations);
        return out;
    }

    private static Object get(AnnotationNode ann, String key) {
        List<Object> v = ann.values;
        if (v == null) return null;
        for (int i = 0; i + 1 < v.size(); i += 2) {
            if (v.get(i).equals(key)) return v.get(i + 1);
        }
        return null;
    }

    private static String str(AnnotationNode ann, String key) {
        Object v = get(ann, key);
        if (v instanceof String s) return s;
        return null;
    }

    private static Integer intBox(AnnotationNode ann, String key) {
        Object v = get(ann, key);
        if (v instanceof Integer i) return i;
        return null;
    }

    private static int intOr(AnnotationNode ann, String key, int def) {
        Integer b = intBox(ann, key);
        return b == null ? def : b;
    }

    private static Boolean boolBox(AnnotationNode ann, String key) {
        Object v = get(ann, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Integer i) return i != 0;
        return null;
    }
}
