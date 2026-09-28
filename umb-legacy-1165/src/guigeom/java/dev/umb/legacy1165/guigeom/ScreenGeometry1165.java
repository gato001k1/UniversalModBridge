package dev.umb.legacy1165.guigeom;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Build-time extractor for 1.16.5-era container-screen geometry (the 1165 counterpart of
 * what umb-guimap does for 1.7.10, but as a standalone era tool: different screen base,
 * different registry idiom, no shared code).
 *
 * <p>A 1.16.5 {@code ContainerScreen} subclass sets its panel size in {@code <init>} via the
 * stable SRG fields {@code field_146999_f} (xSize) / {@code field_147000_g} (ySize) - the same
 * SRG names 1.7.10's {@code GuiContainer} uses, grounded in-lane by
 * {@code ContainerHandle1165}'s own javadoc plus javap on the corpus jar - and binds its
 * background texture through {@code TextureManager.func_110577_a(ResourceLocation)}
 * (javap-verified on the corpus screen class). Two shapes are proven, everything else is an
 * honest, counted absence:
 *
 * <ol>
 *   <li><b>Literal shape</b>: an int constant stored to xSize/ySize and an inline
 *   {@code new ResourceLocation(ns, path)} bound in a screen method. Sheet dims come from
 *   the texture PNG's own IHDR in the jar (evidence, never assumed 256).</li>
 *   <li><b>Descriptor-enum shape</b> (IronChest, the motivating corpus member): the screen
 *   reads int/ResourceLocation fields off a value returned by a no-arg method on the live
 *   container (e.g. {@code getChestType()}), whose return type is an enum. The enum's
 *   {@code <clinit>} assigns one constant per chest variant with plain int + inline
 *   ResourceLocation args - possibly through a pure-forwarding secondary constructor
 *   (resolved, depth-bounded). The variant-to-container-id link is the static factory on
 *   the container class that passes BOTH a {@code RegistryObject.get()} ContainerType AND
 *   an enum constant into one container constructor call, with the registry id from the
 *   holder's {@code DeferredRegister.register("id", ...)} + {@code create(..., "ns")} calls
 *   in the same {@code <clinit>} (all javap-verified on the corpus jar).</li>
 * </ol>
 *
 * <p>Output is one GuiProfile-schema row per PROVEN (containerId, geometry, texture) triple,
 * keyed by container class + container registry id (several 1.16.5 ContainerTypes share one
 * Container class, so class alone under-keys - see the hostagent {@code containerId} lane).
 * Unresolvable links are omitted and counted in {@code meta} (never invented values).
 *
 * <p>Known limitation (documented, not silently worked around): when a screen binds more
 * than one background texture, the FIRST descriptor-enum texture bind wins; per-rect extras
 * are the 1.7.10 rects lane's job and have no 1165 counterpart yet.
 *
 * <p>Bounds (brief: every interprocedural walk has a NAMED CONSTANT):
 * {@link #MAX_CLASSES}, {@link #MAX_HIERARCHY_DEPTH}, {@link #MAX_METHOD_INSNS},
 * {@link #MAX_ENUM_CONSTANTS}, {@link #MAX_FORWARD_DEPTH}.
 */
public final class ScreenGeometry1165 {

    static final int MAX_CLASSES = 4096;
    static final int MAX_HIERARCHY_DEPTH = 16;
    static final int MAX_METHOD_INSNS = 8192;
    static final int MAX_ENUM_CONSTANTS = 256;
    static final int MAX_FORWARD_DEPTH = 4;

    static final String SCREEN_BASE =
            "net/minecraft/client/gui/screen/inventory/ContainerScreen";
    static final String CONTAINER_BASE = "net/minecraft/inventory/container/Container";
    static final String X_SIZE_FIELD = "field_146999_f";
    static final String Y_SIZE_FIELD = "field_147000_g";
    static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    static final String BIND_METHOD = "func_110577_a";
    static final String RL_INTERNAL = "net/minecraft/util/ResourceLocation";
    static final String RL_DESC = "L" + RL_INTERNAL + ";";
    static final String DEFERRED_REGISTER = "net/minecraftforge/registries/DeferredRegister";
    static final String REGISTRY_OBJECT = "net/minecraftforge/fml/RegistryObject";
    static final String REGISTRY_OBJECT_DESC = "L" + REGISTRY_OBJECT + ";";

    private ScreenGeometry1165() {
    }

    /** One emitted row (GuiProfile-schema subset). */
    static final class Row {
        String screenClass;
        String containerClass;
        String containerId;
        int xSize;
        int ySize;
        String textureNs;
        String texturePath;
        int sheetWidth;
        int sheetHeight;
    }

    /** Everything the run learned, for the report. */
    static final class Result {
        final List<Row> rows = new ArrayList<Row>();
        int screensFound;
        int containersFound;
        final List<String> screensUnpaired = new ArrayList<String>();
        final List<String> literalRows = new ArrayList<String>();
        final List<String> enumsUnresolved = new ArrayList<String>();
        final List<String> factoriesAmbiguous = new ArrayList<String>();
        final List<String> registryIdsUnresolved = new ArrayList<String>();
        final List<String> texturesMissingInJar = new ArrayList<String>();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: ScreenGeometry1165 <modjar> <out.json>");
            System.exit(2);
        }
        Result r = extract(new File(args[0]));
        writeJson(r, new File(args[1]));
        System.out.println("screens=" + r.screensFound + " containers=" + r.containersFound
                + " rows=" + r.rows.size() + " unpaired=" + r.screensUnpaired.size()
                + " enumsUnresolved=" + r.enumsUnresolved.size()
                + " factoriesAmbiguous=" + r.factoriesAmbiguous.size()
                + " idsUnresolved=" + r.registryIdsUnresolved.size()
                + " texturesMissing=" + r.texturesMissingInJar.size());
    }

    static Result extract(File jar) throws Exception {
        Result r = new Result();
        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        Map<String, byte[]> pngs = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("package-info")
                        && !n.contains("module-info")) {
                    if (classes.size() >= MAX_CLASSES) break;
                    classes.put(n.substring(0, n.length() - 6), readAll(zf.getInputStream(e)));
                } else if (n.startsWith("assets/") && n.endsWith(".png")) {
                    pngs.put(n, readAll(zf.getInputStream(e)));
                }
            }
        } finally {
            zf.close();
        }
        Map<String, String> superOf = new LinkedHashMap<String, String>();
        Map<String, ClassNode> nodes = new LinkedHashMap<String, ClassNode>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            ClassNode cn = new ClassNode();
            // No SKIP_CODE: the same nodes feed the hierarchy walk AND the
            // instruction scans (a SKIP_CODE hierarchy pass would leave every
            // method body empty and pair nothing - caught live on first run).
            new ClassReader(e.getValue()).accept(cn,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            nodes.put(e.getKey(), cn);
            superOf.put(e.getKey(), cn.superName);
        }
        List<String> screens = new ArrayList<String>();
        List<String> containers = new ArrayList<String>();
        for (String n : nodes.keySet()) {
            if (isSubclassOf(n, SCREEN_BASE, superOf)) screens.add(n);
            else if (isSubclassOf(n, CONTAINER_BASE, superOf)) containers.add(n);
        }
        r.screensFound = screens.size();
        r.containersFound = containers.size();
        for (String s : screens) {
            extractScreen(s, nodes, superOf, pngs, r);
        }
        return r;
    }

    private static boolean debugOn() {
        return System.getenv("UMB_GUIGEOM_DEBUG") != null;
    }

    private static void debug(String msg) {
        if (debugOn()) System.out.println("[guigeom] " + msg);
    }

    private static String cls(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName() + "=" + o;
    }

    private static boolean isSubclassOf(String name, String target,
            Map<String, String> superOf) {
        String cur = superOf.get(name);
        for (int d = 0; d < MAX_HIERARCHY_DEPTH && cur != null; d++) {
            if (cur.equals(target)) return true;
            cur = superOf.get(cur);
        }
        return false;
    }

    // ---------------------------------------------------------------- screen pass

    private static void extractScreen(String screen, Map<String, ClassNode> nodes,
            Map<String, String> superOf, Map<String, byte[]> pngs, Result r) {
        ClassNode sn = nodes.get(screen);
        if (sn == null) return;
        // Pairing: the <init> parameter whose type is a known Container subclass.
        String container = null;
        int containerSlot = -1;
        for (Object mo : sn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("<init>")) continue;
            Type[] args = Type.getArgumentTypes(m.desc);
            int slot = 1;
            for (Type t : args) {
                if (t.getSort() == Type.OBJECT
                        && isSubclassOf(t.getInternalName(), CONTAINER_BASE, superOf)) {
                    container = t.getInternalName();
                    containerSlot = slot;
                    break;
                }
                slot += t.getSize();
            }
            if (container != null) break;
        }
        if (container == null) {
            r.screensUnpaired.add(screen.replace('/', '.'));
            return;
        }
        ScreenShape shape = readScreenShape(sn, screen, containerSlot);
        if (shape.literalX != null && shape.literalY != null
                && shape.literalTexNs != null && shape.literalTexPath != null) {
            int[] wh = pngDims(pngs, shape.literalTexNs, shape.literalTexPath);
            if (wh == null) {
                r.texturesMissingInJar.add(
                        shape.literalTexNs + ":" + shape.literalTexPath);
                return;
            }
            Row row = new Row();
            row.screenClass = screen.replace('/', '.');
            row.containerClass = container.replace('/', '.');
            row.containerId = null;
            row.xSize = shape.literalX.intValue();
            row.ySize = shape.literalY.intValue();
            row.textureNs = shape.literalTexNs;
            row.texturePath = shape.literalTexPath;
            row.sheetWidth = wh[0];
            row.sheetHeight = wh[1];
            r.rows.add(row);
            r.literalRows.add(row.screenClass);
            return;
        }
        if (shape.descEnum == null || shape.xField == null || shape.yField == null
                || shape.textureField == null) {
            r.screensUnpaired.add(screen.replace('/', '.'));
            return;
        }
        Map<String, Map<String, Object>> constants =
                readEnumConstants(shape.descEnum, nodes);
        if (constants == null) {
            r.enumsUnresolved.add(shape.descEnum.replace('/', '.'));
            return;
        }
        if (debugOn()) {
            for (Map.Entry<String, Map<String, Object>> ce : constants.entrySet()) {
                StringBuilder vs = new StringBuilder();
                for (Map.Entry<String, Object> fe : ce.getValue().entrySet()) {
                    if (vs.length() > 0) vs.append(' ');
                    vs.append(fe.getKey()).append('=').append(cls(fe.getValue()));
                }
                debug("const " + ce.getKey() + ": " + vs);
            }
        }
        debug("enum=" + shape.descEnum + " xField=" + shape.xField + " yField="
                + shape.yField + " texField=" + shape.textureField + " constants="
                + constants.keySet());
        Map<String, String> typeFieldToConst =
                readFactoryPairs(container, shape.descEnum, nodes);
        if (typeFieldToConst.isEmpty()) {
            r.factoriesAmbiguous.add(container.replace('/', '.'));
            return;
        }
        debug("pairs=" + typeFieldToConst);
        Map<String, String> fieldToId = readRegistryIds(nodes);
        debug("ids=" + fieldToId);
        for (Map.Entry<String, String> e : typeFieldToConst.entrySet()) {
            String holderField = e.getKey();
            String constName = e.getValue();
            String id = fieldToId.get(holderField);
            if (id == null) {
                if (!r.registryIdsUnresolved.contains(holderField)) {
                    r.registryIdsUnresolved.add(holderField);
                }
                continue;
            }
            Map<String, Object> vals = constants.get(constName);
            if (vals == null) {
                debug("no constant row for " + constName);
                continue;
            }
            Object xv = vals.get(shape.xField);
            Object yv = vals.get(shape.yField);
            Object tv = vals.get(shape.textureField);
            if (!(xv instanceof Integer) || !(yv instanceof Integer)
                    || !(tv instanceof RLRef)) {
                debug("untyped fields for " + constName + ": x=" + cls(xv) + " y="
                        + cls(yv) + " t=" + cls(tv));
                continue;
            }
            RLRef tex = (RLRef) tv;
            int[] wh = pngDims(pngs, tex.ns, tex.path);
            if (wh == null) {
                r.texturesMissingInJar.add(tex.ns + ":" + tex.path);
                continue;
            }
            Row row = new Row();
            row.screenClass = screen.replace('/', '.');
            row.containerClass = container.replace('/', '.');
            row.containerId = id;
            row.xSize = ((Integer) xv).intValue();
            row.ySize = ((Integer) yv).intValue();
            row.textureNs = tex.ns;
            row.texturePath = tex.path;
            row.sheetWidth = wh[0];
            row.sheetHeight = wh[1];
            r.rows.add(row);
        }
    }

    /** What the screen's own bytecode says about size/texture sources. */
    private static final class ScreenShape {
        Integer literalX;
        Integer literalY;
        String literalTexNs;
        String literalTexPath;
        String descEnum;
        String xField;
        String yField;
        String textureField;
    }

    /**
     * Tiny stack lattice over each screen method. Values: const-int, fresh-RL(ns,path),
     * the container reference (ALOAD of the paired ctor param), ThisRef (ALOAD 0),
     * DescCall (no-arg call on the container returning an object type, or a screen field
     * the ctor proved holds such a value), DescField (GETFIELD on a DescCall). Size roles
     * come from WHERE a DescField is stored: X/Y_SIZE_FIELD vs nothing.
     */
    private static ScreenShape readScreenShape(ClassNode sn, String screen,
            int containerSlot) {
        ScreenShape s = new ScreenShape();
        // Screen fields the ctor proves hold the container's descriptor value
        // (ALOAD0/GETFIELD of these re-enters the lattice as a DescCall).
        Map<String, String> screenDescFields = new LinkedHashMap<String, String>();
        for (Object mo : sn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.instructions == null || m.instructions.size() > MAX_METHOD_INSNS) continue;
            List<Object> stack = new ArrayList<Object>();
            for (AbstractInsnNode insn = m.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (insn instanceof LdcInsnNode) {
                    Object cst = ((LdcInsnNode) insn).cst;
                    stack.add(cst instanceof Integer ? cst : NIL.INSTANCE);
                } else if (insn instanceof IntInsnNode
                        && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
                    stack.add(Integer.valueOf(((IntInsnNode) insn).operand));
                } else if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                    stack.add(Integer.valueOf(op - Opcodes.ICONST_0));
                } else if (insn instanceof TypeInsnNode && op == Opcodes.NEW) {
                    stack.add(new NewMark(((TypeInsnNode) insn).desc));
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    int pops = Type.getArgumentTypes(mi.desc).length
                            + (op == Opcodes.INVOKESTATIC ? 0 : 1);
                    List<Object> args = popAll(stack, pops);
                    Object receiver = pops > Type.getArgumentTypes(mi.desc).length
                            ? args.get(0) : null;
                    if ((op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKEVIRTUAL)
                            && mi.name.equals("<init>")) {
                        // Constructor call: [dup-mate, receiver, args...] -> [object].
                        // The DUP mate underneath (a matching NewMark) IS the constructed
                        // object: replace it with the resolved value instead of pushing
                        // (pushing would leave a phantom slot that shifts every later
                        // positional read - the nested-ResourceLocation bug caught live).
                        if (!stack.isEmpty() && stack.get(stack.size() - 1)
                                instanceof NewMark
                                && ((NewMark) stack.get(stack.size() - 1)).desc
                                        .equals(mi.owner)) {
                            popOne(stack);
                        }
                    }
                    if (mi.owner.equals(RL_INTERNAL) && mi.name.equals("<init>")
                            && args.size() == 3 && args.get(1) instanceof String
                            && args.get(2) instanceof String
                            && args.get(0) instanceof NewMark) {
                        stack.add(new RLRef((String) args.get(1), (String) args.get(2)));
                    } else if ((op == Opcodes.INVOKESPECIAL
                            || op == Opcodes.INVOKEVIRTUAL) && mi.name.equals("<init>")) {
                        stack.add(NIL.INSTANCE);
                    } else if (receiver == ContainerRef.INSTANCE
                            && Type.getArgumentTypes(mi.desc).length == 0
                            && Type.getReturnType(mi.desc).getSort() == Type.OBJECT) {
                        stack.add(new DescCall(
                                Type.getReturnType(mi.desc).getInternalName()));
                    } else if (mi.owner.equals(TEXTURE_MANAGER)
                            && mi.name.equals(BIND_METHOD) && !args.isEmpty()) {
                        Object bound = args.get(args.size() - 1);
                        if (bound instanceof RLRef) {
                            if (s.literalTexNs == null) {
                                s.literalTexNs = ((RLRef) bound).ns;
                                s.literalTexPath = ((RLRef) bound).path;
                            }
                        } else if (bound instanceof DescField
                                && ((DescField) bound).desc.equals(RL_DESC)) {
                            adoptTextureField(s, (DescField) bound);
                        }
                        stack.add(NIL.INSTANCE);
                    } else {
                        // Unknown call (void or valued): push one opaque slot so the
                        // depth stays aligned with the real stack. A valued unknown is
                        // NIL (never a proven constant) - honest, not a literal.
                        stack.add(NIL.INSTANCE);
                    }
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    if (op == Opcodes.GETSTATIC) {
                        stack.add(NIL.INSTANCE);
                    } else if (op == Opcodes.GETFIELD) {
                        Object recv = popOne(stack);
                        if (recv instanceof DescCall) {
                            stack.add(new DescField(((DescCall) recv).enumType, fi.name,
                                    fi.desc));
                        } else if (recv == ThisRef.INSTANCE
                                && screenDescFields.containsKey(fi.name)) {
                            stack.add(new DescCall(screenDescFields.get(fi.name)));
                        } else {
                            stack.add(NIL.INSTANCE);
                        }
                    } else if (op == Opcodes.PUTFIELD) {
                        Object val = popOne(stack);
                        popOne(stack);
                        if (fi.owner.equals(screen) && val instanceof DescCall) {
                            // The ctor keeps the descriptor in its own field (e.g.
                            // this.chestType = container.getChestType()): later
                            // reads of it re-enter the lattice above.
                            screenDescFields.put(fi.name,
                                    ((DescCall) val).enumType);
                        }
                        if (!isScreenSizeField(fi)) {
                            continue;
                        }
                        if (val instanceof Integer) {
                            if (fi.name.equals(X_SIZE_FIELD) && s.literalX == null) {
                                s.literalX = (Integer) val;
                            } else if (fi.name.equals(Y_SIZE_FIELD)
                                    && s.literalY == null) {
                                s.literalY = (Integer) val;
                            }
                        } else if (val instanceof DescField
                                && ((DescField) val).desc.equals("I")) {
                            adoptSizeField(s, (DescField) val,
                                    fi.name.equals(X_SIZE_FIELD));
                        }
                    } else {
                        stack.add(NIL.INSTANCE);
                    }
                } else if (insn instanceof VarInsnNode) {
                    int vop = insn.getOpcode();
                    if (vop == Opcodes.ALOAD && ((VarInsnNode) insn).var == containerSlot) {
                        stack.add(ContainerRef.INSTANCE);
                    } else if (vop == Opcodes.ALOAD
                            && ((VarInsnNode) insn).var == 0) {
                        stack.add(ThisRef.INSTANCE);
                    } else if (vop >= Opcodes.ILOAD && vop <= Opcodes.ALOAD) {
                        stack.add(NIL.INSTANCE);
                    } else if (vop >= Opcodes.ISTORE && vop <= Opcodes.ASTORE) {
                        popOne(stack);
                    }
                } else if (op == Opcodes.DUP) {
                    if (!stack.isEmpty()) stack.add(stack.get(stack.size() - 1));
                } else if (op == Opcodes.POP) {
                    popOne(stack);
                } else if (op == Opcodes.SWAP) {
                    int n = stack.size();
                    if (n >= 2) {
                        Object a = stack.remove(n - 1);
                        Object b = stack.remove(n - 2);
                        stack.add(a);
                        stack.add(b);
                    }
                }
            }
        }
        return s;
    }

    private static void adoptSizeField(ScreenShape s, DescField f, boolean isX) {
        if (s.descEnum == null) s.descEnum = f.enumType;
        if (!f.enumType.equals(s.descEnum)) return;
        if (isX) {
            if (s.xField == null) s.xField = f.name;
        } else {
            if (s.yField == null) s.yField = f.name;
        }
    }

    private static void adoptTextureField(ScreenShape s, DescField f) {
        if (s.descEnum == null) s.descEnum = f.enumType;
        if (!f.enumType.equals(s.descEnum)) return;
        if (s.textureField == null) s.textureField = f.name;
    }

    private static boolean isScreenSizeField(FieldInsnNode fi) {
        // SRG field names are unique per Mojang name across 1.16.5, and these two live on
        // the screen base (subclass PUTFIELDs name the inherited field): name match is the
        // same grounding ContainerHandle1165's javadoc cites for the slot fields.
        return fi.name.equals(X_SIZE_FIELD) || fi.name.equals(Y_SIZE_FIELD);
    }

    // Value lattice markers.
    private enum NIL {
        INSTANCE
    }

    private enum ContainerRef {
        INSTANCE
    }

    private enum ThisRef {
        INSTANCE
    }

    private static final class RLRef {
        final String ns;
        final String path;

        RLRef(String ns, String path) {
            this.ns = ns;
            this.path = path;
        }
    }

    private static final class DescCall {
        final String enumType;

        DescCall(String enumType) {
            this.enumType = enumType;
        }
    }

    private static final class DescField {
        final String enumType;
        final String name;
        final String desc;

        DescField(String enumType, String name, String desc) {
            this.enumType = enumType;
            this.name = name;
            this.desc = desc;
        }
    }

    private static Object popOne(List<Object> stack) {
        if (stack.isEmpty()) return NIL.INSTANCE;
        return stack.remove(stack.size() - 1);
    }

    private static List<Object> popAll(List<Object> stack, int n) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < n; i++) out.add(0, popOne(stack));
        return out;
    }

    // ---------------------------------------------------------------- enum pass
    //
    // Reads E.<clinit>'s linear NEW/DUP/args/<init>/PUTSTATIC runs. Constructor argument
    // positions map to fields via the called <init>'s own ILOAD_n/ALOAD_n -> PUTFIELD
    // trace, following pure-forwarding secondary constructors up to MAX_FORWARD_DEPTH.

    private static Map<String, Map<String, Object>> readEnumConstants(String enumName,
            Map<String, ClassNode> nodes) {
        ClassNode en = nodes.get(enumName);
        if (en == null || (en.access & Opcodes.ACC_ENUM) == 0) return null;
        MethodNode clinit = null;
        for (Object mo : en.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("<clinit>")) clinit = m;
        }
        if (clinit == null || clinit.instructions.size() > MAX_METHOD_INSNS) return null;
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        List<Object> stack = new ArrayList<Object>();
        int constants = 0;
        for (AbstractInsnNode insn = clinit.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                stack.add(cst instanceof Integer || cst instanceof String ? cst : NIL.INSTANCE);
            } else if (insn instanceof IntInsnNode
                    && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
                stack.add(Integer.valueOf(((IntInsnNode) insn).operand));
            } else if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                stack.add(Integer.valueOf(op - Opcodes.ICONST_0));
            } else if (insn instanceof TypeInsnNode && op == Opcodes.NEW) {
                stack.add(new NewMark(((TypeInsnNode) insn).desc));
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                int pops = Type.getArgumentTypes(mi.desc).length
                        + (op == Opcodes.INVOKESTATIC ? 0 : 1);
                List<Object> args = popAll(stack, pops);
                if ((op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKEVIRTUAL)
                        && mi.name.equals("<init>")) {
                    // Same DUP-mate rule as the screen lattice: the matching NewMark
                    // underneath becomes the constructed object (never an extra slot).
                    if (!stack.isEmpty() && stack.get(stack.size() - 1)
                            instanceof NewMark
                            && ((NewMark) stack.get(stack.size() - 1)).desc
                                    .equals(mi.owner)) {
                        popOne(stack);
                    }
                }
                if (mi.owner.equals(RL_INTERNAL) && mi.name.equals("<init>")
                        && args.size() == 3 && args.get(1) instanceof String
                        && args.get(2) instanceof String
                        && args.get(0) instanceof NewMark) {
                    stack.add(new RLRef((String) args.get(1), (String) args.get(2)));
                } else if (mi.owner.equals(enumName) && mi.name.equals("<init>")
                        && (op == Opcodes.INVOKESPECIAL
                                || op == Opcodes.INVOKEVIRTUAL)) {
                    // args[0] is the receiver; descriptor positions follow 1:1.
                    Map<Integer, String> argToField =
                            resolveCtor(en, mi.desc, 0);
                    if (argToField == null) return null;
                    stack.add(new CtorArgs(args, argToField));
                } else {
                    stack.add(NIL.INSTANCE);
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (op == Opcodes.PUTSTATIC && fi.owner.equals(enumName)) {
                    Object val = popOne(stack);
                    if (val instanceof CtorArgs) {
                        if (++constants > MAX_ENUM_CONSTANTS) return null;
                        out.put(fi.name, ((CtorArgs) val).toFieldMap());
                    }
                    // Non-ctor statics (e.g. $VALUES: array built with
                    // ANEWARRAY/AASTORE below) are irrelevant, never failures.
                } else if (op == Opcodes.GETSTATIC) {
                    stack.add(NIL.INSTANCE);
                } else {
                    // GETFIELD/PUTFIELD inside <clinit> means computed state: give up
                    // on the whole enum rather than half-read it.
                    return null;
                }
            } else if (insn instanceof TypeInsnNode && op == Opcodes.ANEWARRAY) {
                popOne(stack);
                stack.add(NIL.INSTANCE);
            } else if (op == Opcodes.AASTORE) {
                popOne(stack);
                popOne(stack);
                popOne(stack);
            } else if (insn instanceof VarInsnNode) {
                return null;
            } else if (op == Opcodes.DUP) {
                if (!stack.isEmpty()) stack.add(stack.get(stack.size() - 1));
            } else if (op == Opcodes.POP) {
                popOne(stack);
            }
        }
        return out;
    }

    private static final class NewMark {
        final String desc;

        NewMark(String desc) {
            this.desc = desc;
        }
    }

    /**
     * One ctor call's raw args (index 0 = receiver, then the descriptor's own params in
     * order) plus the descriptor-position to field map. Enum name/ordinal are ordinary
     * descriptor positions 0/1 - no special-casing needed.
     */
    private static final class CtorArgs {
        final List<Object> args;
        final Map<Integer, String> argToField;

        CtorArgs(List<Object> args, Map<Integer, String> argToField) {
            this.args = args;
            this.argToField = argToField;
        }

        Map<String, Object> toFieldMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<Integer, String> e : argToField.entrySet()) {
                int callIdx = e.getKey().intValue() + 1;
                if (callIdx >= 0 && callIdx < args.size()) {
                    out.put(e.getValue(), args.get(callIdx));
                }
            }
            return out;
        }
    }

    /**
     * Descriptor-position to field map for one enum constructor: direct
     * ILOAD_n/ALOAD_n-to-PUTFIELD stores, else (pure-forwarding secondary ctor) the
     * composed map through the delegated constructor. Depth-bounded; null = unproven.
     */
    private static Map<Integer, String> resolveCtor(ClassNode en, String desc, int depth) {
        if (depth > MAX_FORWARD_DEPTH) return null;
        MethodNode target = null;
        for (Object mo : en.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("<init>") && m.desc.equals(desc)) target = m;
        }
        if (target == null || target.instructions == null
                || target.instructions.size() > MAX_METHOD_INSNS) return null;
        Map<Integer, String> direct = directStores(target, en.name);
        if (!direct.isEmpty()) return direct;
        // No direct stores: maybe a pure forwarder to a sibling constructor.
        Forward fwd = forwardArgs(target, en.name);
        if (fwd == null) return null;
        Map<Integer, String> inner = resolveCtor(en, fwd.delegateDesc, depth + 1);
        if (inner == null) return null;
        Map<Integer, String> out = new LinkedHashMap<Integer, String>();
        for (Map.Entry<Integer, Integer> e : fwd.paramMap.entrySet()) {
            String field = inner.get(e.getValue());
            if (field != null) out.put(e.getKey(), field);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Pairs each PUTFIELD-to-this-enum with the nearest preceding same-block load of a
     * constructor parameter local. Calls/other computation between load and store break
     * the pairing (their results are never attributed to a parameter) - so a
     * computed field (e.g. the derived english name) simply has no entry, while every
     * plain stored parameter does.
     */
    private static Map<Integer, String> directStores(MethodNode m, String owner) {
        Type[] argTypes = Type.getArgumentTypes(m.desc);
        int[] slotOf = new int[argTypes.length];
        int slot = 1;
        for (int i = 0; i < argTypes.length; i++) {
            slotOf[i] = slot;
            slot += argTypes[i].getSize();
        }
        Map<Integer, Integer> localToArg = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < argTypes.length; i++) localToArg.put(slotOf[i], Integer.valueOf(i));
        Map<Integer, String> out = new LinkedHashMap<Integer, String>();
        Integer pendingLoad = null;
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode) {
                int vop = insn.getOpcode();
                if (vop >= Opcodes.ILOAD && vop <= Opcodes.ALOAD) {
                    pendingLoad = Integer.valueOf(((VarInsnNode) insn).var);
                } else {
                    pendingLoad = null;
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (insn.getOpcode() == Opcodes.PUTFIELD && fi.owner.equals(owner)
                        && pendingLoad != null && localToArg.containsKey(pendingLoad)) {
                    out.put(localToArg.get(pendingLoad), fi.name);
                    pendingLoad = null;
                } else if (insn.getOpcode() != Opcodes.GETFIELD
                        && insn.getOpcode() != Opcodes.GETSTATIC) {
                    pendingLoad = null;
                }
            } else if (insn.getOpcode() != -1) {
                if (!(insn instanceof org.objectweb.asm.tree.LabelNode)
                        && !(insn instanceof org.objectweb.asm.tree.LineNumberNode)
                        && !(insn instanceof org.objectweb.asm.tree.FrameNode)) {
                    pendingLoad = null;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- factory pass
    //
    // In each static method of C, a call to a C.<init> whose argument list contains
    // exactly one RegistryObject.get() ContainerType (field H.F) and exactly one
    // GETSTATIC of the descriptor enum E (constant K) pairs H.F <-> E.K.

    private static Map<String, String> readFactoryPairs(String container, String descEnum,
            Map<String, ClassNode> nodes) {
        ClassNode cn = nodes.get(container);
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (cn == null) return out;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.instructions == null || m.instructions.size() > MAX_METHOD_INSNS) continue;
            boolean callsCtor = false;
            for (AbstractInsnNode insn = m.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (mi.owner.equals(container) && mi.name.equals("<init>")) {
                        callsCtor = true;
                        break;
                    }
                }
            }
            if (!callsCtor) continue;
            String typeField = null;
            String constName = null;
            boolean ambiguous = false;
            for (AbstractInsnNode insn = m.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode
                        && insn.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    if (fi.desc.equals(REGISTRY_OBJECT_DESC)) {
                        // Only counts when .get() is called on it (sibling holders
                        // also carry RegistryObjects for items/blocks).
                        if (callsRegistryGet(m)) {
                            if (typeField != null && !typeField.equals(
                                    fi.owner + "." + fi.name)) ambiguous = true;
                            typeField = fi.owner + "." + fi.name;
                        }
                    } else if (fi.owner.equals(descEnum)) {
                        if (constName != null && !constName.equals(fi.name)) ambiguous = true;
                        constName = fi.name;
                    }
                }
            }
            if (!ambiguous && typeField != null && constName != null
                    && !out.containsKey(typeField)) {
                out.put(typeField, constName);
            }
        }
        return out;
    }

    private static boolean callsRegistryGet(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.owner.equals(REGISTRY_OBJECT) && mi.name.equals("get")) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- registry pass
    //
    // Holder.<clinit>: CONTAINERS.register("id", supplier) -> PUTSTATIC H.F, with the
    // namespace from DeferredRegister.create(<registry>, "ns") in the same method.

    private static Map<String, String> readRegistryIds(Map<String, ClassNode> nodes) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (ClassNode cn : nodes.values()) {
            for (Object mo : cn.methods) {
                MethodNode m = (MethodNode) mo;
                if (!m.name.equals("<clinit>") || m.instructions == null
                        || m.instructions.size() > MAX_METHOD_INSNS) continue;
                String lastLdc = null;
                String pendingId = null;
                String namespace = null;
                for (AbstractInsnNode insn = m.instructions.getFirst();
                        insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode
                            && ((LdcInsnNode) insn).cst instanceof String) {
                        lastLdc = (String) ((LdcInsnNode) insn).cst;
                    } else if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.owner.equals(DEFERRED_REGISTER)
                                && mi.name.equals("register")) {
                            pendingId = lastLdc;
                        } else if (mi.owner.equals(DEFERRED_REGISTER)
                                && mi.name.equals("create")) {
                            namespace = lastLdc;
                        }
                    } else if (insn instanceof FieldInsnNode
                            && insn.getOpcode() == Opcodes.PUTSTATIC) {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        if (fi.desc.equals(REGISTRY_OBJECT_DESC)
                                && pendingId != null && namespace != null) {
                            out.put(fi.owner + "." + fi.name,
                                    namespace + ":" + pendingId);
                        }
                        pendingId = null;
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- forwarding
    //
    // A pure-forwarding secondary constructor loads only its receiver, its own parameter
    // locals (and at most ACONST_NULL defaults), then calls one sibling <init>. Each
    // positional call argument maps back to a D descriptor index, giving D->P forwarding.

    private static final class Forward {
        final String delegateDesc;
        final Map<Integer, Integer> paramMap;

        Forward(String delegateDesc, Map<Integer, Integer> paramMap) {
            this.delegateDesc = delegateDesc;
            this.paramMap = paramMap;
        }
    }

    private static Forward forwardArgs(MethodNode d, String owner) {
        Type[] dArgs = Type.getArgumentTypes(d.desc);
        int[] slotOf = new int[dArgs.length];
        int slot = 1;
        for (int i = 0; i < dArgs.length; i++) {
            slotOf[i] = slot;
            slot += dArgs[i].getSize();
        }
        Map<Integer, Integer> localToArg = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < dArgs.length; i++) localToArg.put(slotOf[i], Integer.valueOf(i));
        List<Integer> pushedParams = new ArrayList<Integer>();
        String delegateDesc = null;
        int calls = 0;
        boolean seenReceiver = false;
        for (AbstractInsnNode insn = d.instructions.getFirst();
                insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode) {
                int vop = insn.getOpcode();
                int var = ((VarInsnNode) insn).var;
                if (vop == Opcodes.ALOAD && var == 0 && !seenReceiver) {
                    seenReceiver = true;
                    continue;
                }
                if (vop >= Opcodes.ILOAD && vop <= Opcodes.ALOAD
                        && localToArg.containsKey(Integer.valueOf(var))) {
                    pushedParams.add(localToArg.get(Integer.valueOf(var)));
                } else {
                    return null;
                }
            } else if (insn.getOpcode() == Opcodes.ACONST_NULL) {
                pushedParams.add(null);
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                calls++;
                if (calls > 1 || !mi.owner.equals(owner) || !mi.name.equals("<init>")) {
                    return null;
                }
                delegateDesc = mi.desc;
            } else if (insn.getOpcode() == Opcodes.RETURN
                    || insn instanceof org.objectweb.asm.tree.LabelNode
                    || insn instanceof org.objectweb.asm.tree.LineNumberNode
                    || insn instanceof org.objectweb.asm.tree.FrameNode) {
                continue;
            } else {
                return null;
            }
        }
        if (delegateDesc == null || !seenReceiver) return null;
        Type[] pArgs = Type.getArgumentTypes(delegateDesc);
        if (pushedParams.size() != pArgs.length) return null;
        Map<Integer, Integer> map = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < pArgs.length; i++) {
            Integer src = pushedParams.get(i);
            if (src != null) map.put(src, Integer.valueOf(i));
        }
        // Every D parameter must be forwarded (a dropped parameter is a computation,
        // not a pure forward); P positions filled by constants stay unmapped.
        if (map.size() != dArgs.length) return null;
        return new Forward(delegateDesc, map);
    }

    // ---------------------------------------------------------------- png + json

    /**
     * PNG bytes for a resource path. 1.16.5 GUI paths already include the
     * {@code textures/} prefix ({@code new ResourceLocation(ns, "textures/gui/...")});
     * atlas-sprite-style paths ({@code "model/iron_chest"}) do not.
     */
    private static int[] pngDims(Map<String, byte[]> pngs, String ns, String path) {
        String rel = path.startsWith("textures/") ? path : "textures/" + path;
        // GUI-texture paths already carry their suffix
        // (new ResourceLocation(ns, "textures/gui/x.png")); atlas-sprite-style
        // paths ("model/iron_chest") do not.
        if (!rel.endsWith(".png")) rel = rel + ".png";
        byte[] d = pngs.get("assets/" + ns + "/" + rel);
        if (d == null || d.length < 24) return null;
        if (d[0] != (byte) 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') return null;
        int w = ((d[16] & 0xFF) << 24) | ((d[17] & 0xFF) << 16)
                | ((d[18] & 0xFF) << 8) | (d[19] & 0xFF);
        int h = ((d[20] & 0xFF) << 24) | ((d[21] & 0xFF) << 16)
                | ((d[22] & 0xFF) << 8) | (d[23] & 0xFF);
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
        return new int[] {w, h};
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static void writeJson(Result r, File out) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{");
        sb.append("\"generator\":\"umb-legacy1165 ScreenGeometry1165\",");
        sb.append("\"screensFound\":").append(r.screensFound).append(',');
        sb.append("\"containersFound\":").append(r.containersFound).append(',');
        sb.append("\"rowsEmitted\":").append(r.rows.size()).append(',');
        appendStrList(sb, "screensUnpaired", r.screensUnpaired);
        sb.append(',');
        appendStrList(sb, "enumsUnresolved", r.enumsUnresolved);
        sb.append(',');
        appendStrList(sb, "factoriesAmbiguous", r.factoriesAmbiguous);
        sb.append(',');
        appendStrList(sb, "registryIdsUnresolved", r.registryIdsUnresolved);
        sb.append(',');
        appendStrList(sb, "texturesMissingInJar", r.texturesMissingInJar);
        sb.append("},\"guis\":[");
        // Deterministic order: by containerId, then container class.
        Map<String, Row> ordered = new TreeMap<String, Row>();
        for (Row row : r.rows) {
            ordered.put((row.containerId == null ? "~" + row.containerClass
                    : row.containerId) + "\0" + row.containerClass, row);
        }
        boolean first = true;
        for (Row row : ordered.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"className\":").append(q(row.screenClass)).append(',');
            sb.append("\"container\":{\"className\":").append(q(row.containerClass));
            sb.append(",\"confidence\":\"exact\"");
            if (row.containerId != null) {
                sb.append(",\"containerId\":").append(q(row.containerId));
            }
            sb.append("},");
            sb.append("\"size\":{\"confidence\":\"exact\",\"xSize\":").append(row.xSize);
            sb.append(",\"ySize\":").append(row.ySize).append("},");
            String texPath = row.texturePath.startsWith("textures/")
                    ? row.texturePath : "textures/" + row.texturePath;
            sb.append("\"backgroundTextures\":[{\"path\":")
                    .append(q(row.textureNs + ":" + texPath));
            sb.append(",\"assetPath\":")
                    .append(q("assets/" + row.textureNs + "/" + texPath));
            sb.append(",\"existsInJar\":true");
            sb.append(",\"sheetWidth\":").append(row.sheetWidth);
            sb.append(",\"sheetHeight\":").append(row.sheetHeight).append("}],");
            sb.append("\"backgroundDrawRects\":[],\"foregroundLabels\":[],\"buttons\":[]}");
        }
        sb.append("]}");
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
        try {
            w.write(sb.toString());
            w.write('\n');
        } finally {
            w.close();
        }
        // File.renameTo fails on Windows when the target exists: remove first.
        if (out.exists() && !out.delete()) {
            throw new java.io.IOException("cannot replace: " + out);
        }
        if (!tmp.renameTo(out)) {
            throw new java.io.IOException("rename failed: " + tmp + " -> " + out);
        }
    }

    private static void appendStrList(StringBuilder sb, String key, List<String> vals) {
        sb.append(q(key)).append(":[");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(vals.get(i)));
        }
        sb.append(']');
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
