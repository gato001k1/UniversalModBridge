package dev.umb.rendermap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, conservative input/packet analysis.  It deliberately records unresolved sites
 * instead of guessing through arbitrary control flow.  The output is data consumed by the
 * legacy-side loader; no mod class name is used by the runtime bridge.
 */
public final class InputPlanAnalyzer {
    public static final int MAX_CLASSES = 100000;
    public static final int MAX_INSTRUCTIONS = 12000;
    public static final int MAX_BACKTRACK = 96;
    private static final String CLIENT_REGISTRY = "cpw/mods/fml/client/registry/ClientRegistry";
    private static final String KEY_BINDING = "net/minecraft/client/settings/KeyBinding";
    private static final String SIMPLE = "cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper";
    private static final String FML_CHANNEL = "cpw/mods/fml/common/network/FMLEventChannel";

    private InputPlanAnalyzer() {}

    public static JsonObject analyze(Path jarPath) throws IOException {
        JarIndex jar = JarIndex.open(jarPath);
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("sourceJar", jarPath.toString());
        root.addProperty("bounded", true);
        root.addProperty("maxInstructionsPerMethod", MAX_INSTRUCTIONS);
        root.addProperty("maxBacktrack", MAX_BACKTRACK);
        JsonArray keybindings = new JsonArray();
        JsonArray plans = new JsonArray();
        JsonArray unresolved = new JsonArray();
        Map<String, JsonObject> fields = findKeyBindings(jar);
        for (ClassNode cn : jar.classes.values()) {
            if (cn.methods == null) continue;
            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null || mn.instructions.size() > MAX_INSTRUCTIONS) {
                    if (mn.instructions != null && mn.instructions.size() > MAX_INSTRUCTIONS)
                        unresolved.add(site(cn, mn) + ": instruction bound exceeded");
                    continue;
                }
                List<AbstractInsnNode> insns = realInstructions(mn);
                Set<String> seenKeys = new LinkedHashSet<>();
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode n = insns.get(i);
                    if (!(n instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) n;
                    if (call.getOpcode() == Opcodes.INVOKESTATIC && CLIENT_REGISTRY.equals(call.owner)
                            && "registerKeyBinding".equals(call.name)) {
                        String field = previousKeyField(insns, i);
                        JsonObject kb = fields.get(field);
                        if (kb == null) {
                            kb = new JsonObject();
                            kb.addProperty("field", field == null ? "unresolved" : field);
                            kb.addProperty("stableId", "legacy:key:unresolved:" + (field == null ? "unknown" : field));
                            kb.addProperty("resolved", false);
                        }
                        String id = kb.get("stableId").getAsString();
                        if (seenKeys.add(id)) keybindings.add(kb);
                    }
                    if (isSendToServer(call) && isInputSite(jar, cn, mn, insns)) {
                        JsonObject p = findPacketPlan(insns, i, cn, mn, fields);
                        if (p != null) addExpandedPlans(plans, p, fields, jar);
                        else unresolved.add(site(cn, mn) + ": sendToServer without bounded message construction");
                    }
                }
            }
        }
        scanCustomKeyPacketSites(jar, keybindings, plans);
        keybindings = dedupeKeybindings(keybindings);
        root.add("keybindings", keybindings);
        root.add("plans", dedupePlans(plans));
        root.add("unresolved", unresolved);
        root.addProperty("keybindingCount", keybindings.size());
        root.addProperty("planCount", root.getAsJsonArray("plans").size());
        root.addProperty("unresolvedCount", unresolved.size());
        return root;
    }

    private static void scanCustomKeyPacketSites(JarIndex jar, JsonArray keybindings, JsonArray plans) {
        Set<String> keys = new LinkedHashSet<>();
        for (ClassNode cn : jar.classes.values()) {
            if (!cn.name.contains("Client") || cn.methods == null) continue;
            for (MethodNode mn : cn.methods) {
                List<AbstractInsnNode> insns = realInstructions(mn);
                if (!isInputSite(jar, cn, mn, insns)) continue;
                List<String> localKeys = new ArrayList<>();
                for (AbstractInsnNode n : insns) if (n instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode)n;
                    if (f.getOpcode() == Opcodes.GETFIELD && isKeyStateHolder(jar, f.desc)) {
                        String id = "legacy:key:custom:" + f.owner + "#" + f.name;
                        if (keys.add(id)) {
                            JsonObject k = new JsonObject(); k.addProperty("field", f.owner + "#" + f.name);
                            k.addProperty("description", f.name); k.addProperty("keyCode", Integer.MIN_VALUE);
                            k.addProperty("category", "custom-key"); k.addProperty("stableId", id);
                            k.addProperty("resolved", false); keybindings.add(k);
                        }
                        localKeys.add(id);
                    }
                }
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode n = insns.get(i);
                    if (!(n instanceof org.objectweb.asm.tree.TypeInsnNode) || n.getOpcode() != Opcodes.NEW) continue;
                    String packet = ((org.objectweb.asm.tree.TypeInsnNode)n).desc;
                    if (!isPacketType(jar, packet)) continue;
                    JsonObject p = new JsonObject(); p.addProperty("keybinding", localKeys.isEmpty() ? "unresolved" : localKeys.get(0));
                    p.addProperty("trigger", "held-or-state-change"); p.addProperty("messageClass", JarIndex.dotted(packet));
                    p.addProperty("messageConstructor", "()V"); p.addProperty("site", site(cn, mn));
                    p.add("constructorArgs", new JsonArray()); p.addProperty("resolved", false);
                    JsonArray candidates = new JsonArray(); for (String k : localKeys) candidates.add(k); p.add("keyCandidates", candidates);
                    plans.add(p);
                }
            }
        }
    }

    /**
     * A custom per-key state-holder type: a jar-declared class with a no-arg boolean query
     * method shaped like a key-state read (name contains "Key" or "Press"). This is the
     * structural generalization of one mod's bespoke key-state class: any mod that polls its
     * own holder instead of vanilla KeyBindings is recorded the same way, with a stable id
     * derived from the field (never a mod name).
     */
    private static boolean isKeyStateHolder(JarIndex jar, String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return false;
        // Vanilla KeyBindings have their own first-class table (findKeyBindings) — the holder
        // path is only for a mod's own bespoke key-state classes.
        if (("L" + KEY_BINDING + ";").equals(desc)) return false;
        ClassNode cn = jar.cls(desc.substring(1, desc.length() - 1));
        if (cn == null) return false;
        for (ClassNode c : jar.superChain(cn.name)) {
            if (c.methods == null) continue;
            for (MethodNode mn : c.methods) {
                if (mn == null || mn.desc == null) continue;
                if (Type.getArgumentTypes(mn.desc).length != 0) continue;
                if (!"boolean".equals(Type.getReturnType(mn.desc).getClassName())) continue;
                if (mn.name.contains("Key") || mn.name.contains("Press")) return true;
            }
        }
        return false;
    }

    /**
     * A Forge network packet type: the class implements
     * {@code cpw.mods.fml.common.network.simpleimpl.IMessage}. Replaces the old
     * path-substring packet filter with the interface Forge itself defines for packets.
     */
    private static boolean isPacketType(JarIndex jar, String internalName) {
        if (internalName == null) return false;
        ClassNode cn = jar.cls(internalName);
        if (cn == null) return false;
        for (ClassNode c : jar.superChain(internalName)) {
            if (c.interfaces == null) continue;
            for (String i : c.interfaces)
                if ("cpw/mods/fml/common/network/simpleimpl/IMessage".equals(i)) return true;
        }
        return false;
    }

    private static Map<String, JsonObject> findKeyBindings(JarIndex jar) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (ClassNode cn : jar.classes.values()) for (MethodNode mn : cn.methods) {
            List<AbstractInsnNode> insns = realInstructions(mn);
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode n = insns.get(i);
                if (!(n instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) n;
                if (!KEY_BINDING.equals(call.owner) || !"<init>".equals(call.name)) continue;
                Type[] args = Type.getArgumentTypes(call.desc);
                if (args.length != 3 || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.INT) continue;
                String field = nextPutStatic(insns, i + 1, MAX_BACKTRACK);
                if (field == null) continue;
                List<String> strings = previousStrings(insns, i);
                String category = strings.size() > 0 ? strings.get(0) : null;
                String desc = strings.size() > 1 ? strings.get(1) : null;
                Integer code = previousInt(insns, i);
                JsonObject kb = new JsonObject();
                kb.addProperty("field", field);
                kb.addProperty("description", desc == null ? "unresolved" : desc);
                kb.addProperty("keyCode", code == null ? Integer.MIN_VALUE : code);
                kb.addProperty("category", category == null ? "unresolved" : category);
                kb.addProperty("stableId", "legacy:key:" + (desc == null ? field : desc) + ":" + (code == null ? "unknown" : code) + ":" + (category == null ? "unresolved" : category));
                kb.addProperty("resolved", desc != null && code != null && category != null);
                out.putIfAbsent(cn.name + "#" + field, kb);
                out.putIfAbsent(field, kb);
            }
        }
        return out;
    }

    private static JsonObject findPacketPlan(List<AbstractInsnNode> insns, int send, ClassNode cn,
                                             MethodNode mn, Map<String, JsonObject> fields) {
        int start = Math.max(0, send - MAX_BACKTRACK);
        for (int i = send - 1; i >= start; i--) {
            AbstractInsnNode n = insns.get(i);
            if (!(n instanceof MethodInsnNode)) continue;
            MethodInsnNode ctor = (MethodInsnNode) n;
            if (!"<init>".equals(ctor.name)) continue;
            String packet = ctor.owner;
            JsonObject p = new JsonObject();
            p.addProperty("keybinding", inferKey(insns, i, fields));
            p.addProperty("trigger", inferTrigger(mn, insns, i));
            p.addProperty("messageClass", JarIndex.dotted(packet));
            p.addProperty("messageConstructor", ctor.desc);
            p.addProperty("site", site(cn, mn));
            Type[] ctorArgs = Type.getArgumentTypes(ctor.desc);
            if (ctorArgs.length > 0 && ctorArgs[0].getSort() == Type.OBJECT
                    && (i < 2 || !(insns.get(i - 2) instanceof FieldInsnNode)
                    || insns.get(i - 2).getOpcode() != Opcodes.GETSTATIC
                    || hasEnumValuesCall(insns, i))) {
                // The value is a loop/local enum (e.g. values()[i]), not the nearby keybinding
                // used by an earlier branch. Expansion below correlates enum constants to keys.
                p.addProperty("keybinding", "unresolved");
            }
            JsonArray args = new JsonArray();
            for (Type t : ctorArgs) {
                JsonObject a = new JsonObject();
                a.addProperty("type", t.getClassName());
                a.addProperty("source", inferArgSource(insns, i, t));
                args.add(a);
            }
            p.add("constructorArgs", args);
            if (args.size() > 0 && "input.enumConstant".equals(args.get(0).getAsJsonObject().get("source").getAsString()))
                p.addProperty("keybinding", "unresolved");
            p.addProperty("resolved", !"unresolved".equals(p.get("keybinding").getAsString()));
            return p;
        }
        return null;
    }

    private static void addExpandedPlans(JsonArray out, JsonObject p, Map<String, JsonObject> fields, JarIndex jar) {
        if (!"unresolved".equals(p.get("keybinding").getAsString())) { out.add(p); return; }
        JsonArray args = p.getAsJsonArray("constructorArgs");
        if (args.size() == 0 || !args.get(0).getAsJsonObject().get("type").getAsString().contains("Enum")) { out.add(p); return; }
        // Dynamic enum arguments (HBM's handleProps loop) are expanded from the enum constants
        // and the independently recovered KeyBinding table. This is analysis data, not runtime
        // knowledge of a particular mod.
        String enumType = args.get(0).getAsJsonObject().get("type").getAsString();
        ClassNode ec = jar.cls(JarIndex.internal(enumType));
        if (ec == null || ec.fields == null) { out.add(p); return; }
        boolean any = false;
        for (org.objectweb.asm.tree.FieldNode f : ec.fields) {
            if (!enumType.equals(Type.getType(f.desc).getClassName()) || "$VALUES".equals(f.name)) continue;
            JsonObject kb = matchingKey(f.name, fields);
            if (kb == null) continue;
            JsonObject q = p.deepCopy();
            q.addProperty("keybinding", kb.get("stableId").getAsString());
            q.addProperty("resolved", true);
            JsonObject first = q.getAsJsonArray("constructorArgs").get(0).getAsJsonObject();
            first.addProperty("source", "enum:" + ec.name + "#" + f.name);
            out.add(q); any = true;
        }
        if (!any) out.add(p);
    }

    private static JsonObject matchingKey(String enumName, Map<String, JsonObject> fields) {
        String want = compact(enumName);
        for (JsonObject kb : new LinkedHashSet<JsonObject>(fields.values())) {
            if (!kb.has("description")) continue;
            String desc = compact(kb.get("description").getAsString());
            String field = kb.has("field") ? compact(kb.get("field").getAsString()) : "";
            if (desc.contains(want) || want.contains(desc) || field.contains(want) || want.contains(field)) return kb;
        }
        return null;
    }

    private static String compact(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]", "").replace("key", ""); }

    private static boolean isInputSite(JarIndex jar, ClassNode cn, MethodNode mn, List<AbstractInsnNode> insns) {
        String s = cn.name + "#" + mn.name + mn.desc;
        if (s.contains("ClientTickEvent") || s.contains("KeyInputEvent") || s.contains("MouseInputEvent")
                || s.toLowerCase().contains("clienttick") || s.toLowerCase().contains("keybind")
                || s.toLowerCase().contains("playercontrol")) return true;
        for (AbstractInsnNode n : insns) if (n instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode)n;
            if (KEY_BINDING.equals(m.owner) && ("func_151470_d".equals(m.name) || "func_151463_i".equals(m.name))) return true;
            if ((m.name.contains("Key") || m.name.contains("Press")) && isKeyStateHolder(jar, "L" + m.owner + ";")) return true;
        }
        return false;
    }

    private static boolean hasEnumValuesCall(List<AbstractInsnNode> insns, int at) {
        for (int i = Math.max(0, at - 48); i < at; i++)
            if (insns.get(i) instanceof MethodInsnNode && "values".equals(((MethodInsnNode)insns.get(i)).name)) return true;
        return false;
    }

    private static String inferKey(List<AbstractInsnNode> insns, int at, Map<String, JsonObject> fields) {
        for (int i = at - 1; i >= Math.max(0, at - 24); i--) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.GETSTATIC
                    && KEY_BINDING.equals(((FieldInsnNode)n).desc.substring(1, ((FieldInsnNode)n).desc.length() - 1))) {
                FieldInsnNode f = (FieldInsnNode)n;
                JsonObject kb = fields.get(f.owner + "#" + f.name);
                if (kb == null) kb = fields.get(f.name);
                if (kb != null) return kb.get("stableId").getAsString();
            }
        }
        return "unresolved";
    }

    private static String inferTrigger(MethodNode mn, List<AbstractInsnNode> insns, int at) {
        String n = mn.name.toLowerCase();
        if (n.contains("tick") || n.contains("update")) return "held-or-state-change";
        for (int i = Math.max(0, at - 40); i < at; i++) if (insns.get(i) instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode)insns.get(i);
            if ("func_151470_d".equals(m.name) || "isPressed".equals(m.name) || "getIsKeyPressed".equals(m.name)) return "pressed";
        }
        return "event-or-state-change";
    }

    private static String inferArgSource(List<AbstractInsnNode> insns, int at, Type type) {
        if (type.getSort() == Type.BOOLEAN) return "input.pressedBoolean";
        if (type.getSort() == Type.OBJECT && type.getClassName().contains("Enum")) {
            if (hasEnumValuesCall(insns, at)) return "input.enumConstant";
            for (int i = at - 1; i >= Math.max(0, at - 4); i--) {
                AbstractInsnNode n = insns.get(i);
                if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.GETSTATIC
                        && ((FieldInsnNode)n).desc.equals(type.getDescriptor()))
                    return "enum:" + ((FieldInsnNode)n).owner + "#" + ((FieldInsnNode)n).name;
            }
            return "input.enumConstant";
        }
        if (type.getSort() == Type.OBJECT) {
            for (int i = at - 1; i >= Math.max(0, at - 4); i--) {
                AbstractInsnNode n = insns.get(i);
                if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.GETSTATIC
                        && ((FieldInsnNode)n).desc.equals(type.getDescriptor()))
                    return "enum:" + ((FieldInsnNode)n).owner + "#" + ((FieldInsnNode)n).name;
            }
        }
        for (int i = at - 1; i >= Math.max(0, at - 12); i--) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode)n;
                if ("func_151463_i".equals(m.name)) return "keybinding.keyCode";
                if ("getLookVec".equals(m.name) || "func_70676_i".equals(m.name)) return "player.lookVector";
                if ("getPosition".equals(m.name) || "func_70666_h".equals(m.name)) return "player.position";
            }
            if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode)n;
                if ("field_74513_e".equals(f.name)) return "keybinding.pressedBoolean";
            }
            if (n instanceof LdcInsnNode) return "constant:" + ((LdcInsnNode)n).cst;
            if (n instanceof IntInsnNode) return "constant:" + ((IntInsnNode)n).operand;
        }
        return type.getSort() == Type.BOOLEAN ? "input.pressedBoolean" : "unresolved";
    }

    private static boolean isSendToServer(MethodInsnNode m) {
        // The owner is intentionally not restricted: Forge wrappers, custom mod wrappers, and
        // MCHeli's W_PacketHandler all expose the same semantic sendToServer edge.
        return "sendToServer".equals(m.name) && Type.getArgumentTypes(m.desc).length == 1;
    }

    private static String previousKeyField(List<AbstractInsnNode> insns, int at) {
        for (int i = at - 1; i >= Math.max(0, at - 4); i--) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.GETSTATIC) return ((FieldInsnNode)n).owner + "#" + ((FieldInsnNode)n).name;
        }
        return null;
    }

    private static String nextPutStatic(List<AbstractInsnNode> insns, int at, int bound) {
        for (int i = at; i < Math.min(insns.size(), at + bound); i++) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.PUTSTATIC) return ((FieldInsnNode)n).owner + "#" + ((FieldInsnNode)n).name;
            if (n.getOpcode() == Opcodes.RETURN || n.getOpcode() == Opcodes.ARETURN) break;
        }
        return null;
    }

    private static String previousString(List<AbstractInsnNode> insns, int at) {
        for (int i = at - 1; i >= Math.max(0, at - 8); i--) if (insns.get(i) instanceof LdcInsnNode && ((LdcInsnNode)insns.get(i)).cst instanceof String) return (String)((LdcInsnNode)insns.get(i)).cst;
        return null;
    }

    private static List<String> previousStrings(List<AbstractInsnNode> insns, int at) {
        List<String> out = new ArrayList<>();
        for (int i = at - 1; i >= Math.max(0, at - 10) && out.size() < 2; i--)
            if (insns.get(i) instanceof LdcInsnNode && ((LdcInsnNode)insns.get(i)).cst instanceof String)
                out.add((String)((LdcInsnNode)insns.get(i)).cst);
        return out;
    }

    private static Integer previousInt(List<AbstractInsnNode> insns, int at) {
        for (int i = at - 1; i >= Math.max(0, at - 8); i--) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof IntInsnNode) return ((IntInsnNode)n).operand;
            switch (n.getOpcode()) {
                case Opcodes.ICONST_M1: return -1; case Opcodes.ICONST_0: return 0; case Opcodes.ICONST_1: return 1; case Opcodes.ICONST_2: return 2; case Opcodes.ICONST_3: return 3; case Opcodes.ICONST_4: return 4; case Opcodes.ICONST_5: return 5;
                default: break;
            }
        }
        return null;
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode mn) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) if (n.getOpcode() >= 0) out.add(n);
        return out;
    }

    private static String site(ClassNode cn, MethodNode mn) { return JarIndex.dotted(cn.name) + "." + mn.name + mn.desc; }

    private static JsonArray dedupePlans(JsonArray in) {
        JsonArray out = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < in.size(); i++) { JsonObject p = in.get(i).getAsJsonObject(); String k = p.get("messageClass").getAsString() + "|" + p.get("site").getAsString() + "|" + p.get("keybinding").getAsString(); if (seen.add(k)) out.add(p); }
        return out;
    }

    private static JsonArray dedupeKeybindings(JsonArray in) {
        JsonArray out = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < in.size(); i++) {
            JsonObject k = in.get(i).getAsJsonObject();
            String id = k.has("stableId") ? k.get("stableId").getAsString() : "unresolved:" + i;
            if (seen.add(id)) out.add(k);
        }
        return out;
    }

    public static void write(Path jar, Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(analyze(jar)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: InputPlanAnalyzer <mod.jar> <out.json>");
        write(Path.of(args[0]), Path.of(args[1]));
    }
}
