package dev.umb.rendermap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extracts OpenGL transform constants from legacy renderer bytecode.
 * Disassembles each renderer class and captures constant values for glScale, glTranslate, glRotate.
 */
public class RendererTransformExtractor {

    /** Named bound for following renderer -> same-jar transform helpers. */
    static final int MAX_HELPER_CALL_DEPTH = 3;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RendererTransformExtractor <mod.jar> <rendermap.json> <outDir>");
            System.exit(2);
        }
        Path jarPath = Paths.get(args[0]);
        Path rendermapPath = Paths.get(args[1]);
        Path outDir = Paths.get(args[2]);
        Files.createDirectories(outDir);

        long t0 = System.currentTimeMillis();
        log("reading jar " + jarPath);
        JarIndex jar = JarIndex.open(jarPath);
        log("  " + jar.classes.size() + " classes");

        log("extracting unique renderer classes from " + rendermapPath);
        Set<String> renderers = extractRendererClasses(rendermapPath);
        // The render map is item-use driven and can omit orphan/anonymous IItemRenderer and
        // ISimpleBlockRenderingHandler implementations.  Include the interfaces directly so the
        // transform sidecar's denominator is every renderer implementation in the target jar, not
        // merely the classes reached by a registration row.
        for (ClassNode cn : jar.implementorsOf("net/minecraftforge/client/IItemRenderer"))
            renderers.add(JarIndex.dotted(cn.name));
        for (ClassNode cn : jar.implementorsOf("net/minecraft/client/renderer/ISimpleBlockRenderingHandler"))
            renderers.add(JarIndex.dotted(cn.name));
        log("  " + renderers.size() + " unique renderer classes");

        log("scanning renderer classes for GL transforms");
        Map<String, List<TransformRecord>> allTransforms = new TreeMap<>();
        int scanned = 0, withTransforms = 0;
        for (String rendererName : renderers) {
            String internalName = JarIndex.internal(rendererName);
            ClassNode cn = jar.cls(internalName);
            if (cn == null) {
                log("  WARNING: renderer class not found: " + rendererName);
                continue;
            }
            scanned++;
            List<TransformRecord> transforms = extractTransforms(cn, jar);
            if (!transforms.isEmpty()) {
                allTransforms.put(rendererName, transforms);
                withTransforms++;
            }
        }
        log("  scanned " + scanned + " renderer classes, " + withTransforms + " have transforms");

        // GUI-transforms lane: ItemRenderType branch attribution coverage, with the denominator
        int rtAttributed = 0, rtOpsInTypeMethods = 0, rtClasses = 0;
        Map<String, Integer> rtHistogram = new TreeMap<>();
        for (Map.Entry<String, List<TransformRecord>> e : allTransforms.entrySet()) {
            boolean any = false;
            for (TransformRecord t : e.getValue()) {
                if ("renderItem".equals(t.method)) rtOpsInTypeMethods++;
                if (t.renderTypes != null && !t.renderTypes.isEmpty()) {
                    rtAttributed++;
                    any = true;
                    for (String rt : t.renderTypes) rtHistogram.merge(rt, 1, Integer::sum);
                }
            }
            if (any) rtClasses++;
        }
        log("  renderType attribution: " + rtAttributed + " ops attributed across " + rtClasses
                + " classes (renderItem ops total: " + rtOpsInTypeMethods + "); per type: " + rtHistogram);

        // Count statistics and analyze scale distribution by method kind
        int totalConstant = 0, totalDynamic = 0;
        Map<String, Integer> scaleValues = new TreeMap<>();
        Map<String, Map<String, Integer>> scaleByMethod = new TreeMap<>();  // method name -> scale value -> count
        int inWorldScaleGt1 = 0, inWorldScaleLt1 = 0;  // Analyze in-world scale direction

        for (List<TransformRecord> transforms : allTransforms.values()) {
            for (TransformRecord t : transforms) {
                if (t.isDynamic) {
                    totalDynamic++;
                } else {
                    totalConstant++;
                    if ("glScalef".equals(t.op) || "glScaled".equals(t.op)) {
                        // Record scale values
                        if (t.args.size() >= 1 && t.args.get(0) != null) {
                            String arg0 = String.valueOf(t.args.get(0));
                            scaleValues.put(arg0, scaleValues.getOrDefault(arg0, 0) + 1);

                            // Track by method kind
                            scaleByMethod.putIfAbsent(t.method, new TreeMap<>());
                            scaleByMethod.get(t.method).put(arg0, scaleByMethod.get(t.method).getOrDefault(arg0, 0) + 1);

                            // Analyze in-world scale direction (methods like func_147500_a, renderBlock, etc)
                            if (t.method.contains("render") && !t.method.contains("Inventory") && !t.method.contains("FirstPerson")) {
                                try {
                                    double scale = Double.parseDouble(arg0);
                                    if (scale > 1.0) inWorldScaleGt1++;
                                    else if (scale < 1.0) inWorldScaleLt1++;
                                } catch (NumberFormatException e) {
                                    // Skip if not a number
                                }
                            }
                        }
                    }
                }
            }
        }
        String mostCommonScale = scaleValues.entrySet().stream()
                .max((a, b) -> a.getValue() - b.getValue())
                .map(Map.Entry::getKey)
                .orElse("(none)");

        // Write output
        Path outPath = outDir.resolve("renderer-transforms.json");
        writeOutput(outPath, allTransforms);
        log("wrote " + outPath);

        Path displayPath = outDir.resolve("item-display-transforms.json");
        ItemDisplayOutput display = buildItemDisplayOutput(allTransforms, jar);
        writeItemDisplayOutput(displayPath, display);
        log("wrote " + displayPath + " (IItemRenderer classes=" + display.itemRendererClasses
                + ", contexts=" + display.contextCounts + ", fallbacks=" + display.fallbackCounts + ")");

        // Moving-parts lane: symbolic dynamic ops per renderPart group (new sidecar file; every
        // output above is untouched). A failure here must never break the existing outputs.
        try {
            Path dynPath = outDir.resolve("renderer-dynamic-ops.json");
            DynamicOpSidecar.write(dynPath, jar, rendermapPath);
            log("wrote " + dynPath);
        } catch (Throwable t) {
            log("WARNING: dynamic-ops sidecar failed (existing outputs unaffected): " + t);
        }

        // Print final report
        log("");
        log("EXTRACTION COMPLETE");
        log("  Renderer classes scanned: " + scanned);
        log("  Classes with at least one transform: " + withTransforms);
        log("  Total transforms (constant): " + totalConstant);
        log("  Total transforms (dynamic): " + totalDynamic);
        log("  Most common scale value: " + mostCommonScale + " (count=" + scaleValues.getOrDefault(mostCommonScale, 0) + ")");
        log("");
        log("SCALE VALUE DISTRIBUTION (all methods):");
        scaleValues.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> log("    " + e.getKey() + ": " + e.getValue() + " times"));
        log("");
        log("SCALE BY METHOD KIND:");
        scaleByMethod.forEach((method, scales) -> {
            log("  " + method + ":");
            scales.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(5)
                    .forEach(e -> log("    " + e.getKey() + ": " + e.getValue()));
        });
        log("");
        log("IN-WORLD SCALE DIRECTION (render methods, excluding Inventory/FirstPerson):");
        log("  Scale > 1.0 (enlarge): " + inWorldScaleGt1 + " operations");
        log("  Scale < 1.0 (shrink): " + inWorldScaleLt1 + " operations");
        log("  Conclusion: Models are authored in " +
                (inWorldScaleGt1 > inWorldScaleLt1 ? "SMALL coordinate space (scaled UP)" : "LARGE coordinate space (scaled DOWN)"));
        log("");
        log("done in " + (System.currentTimeMillis() - t0) + " ms");
    }

    static Set<String> extractRendererClasses(Path rendermapPath) throws Exception {
        Set<String> renderers = new HashSet<>();
        String json = Files.readString(rendermapPath, StandardCharsets.UTF_8);
        // Simple regex extraction: "rendererClass":"ClassName"
        String pattern = "\"rendererClass\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        while (m.find()) {
            renderers.add(m.group(1));
        }
        return renderers;
    }

    static List<TransformRecord> extractTransforms(ClassNode cn) {
        return extractTransforms(cn, null);
    }

    /**
     * {@code jar} enables {@link RenderTypeFlow}'s $SwitchMap companion decoding (the javac enum
     * switch idiom stores its case mapping in a separate synthetic class); null keeps the old
     * behaviour byte-identical except that if_acmp-guarded methods still gain attribution.
     */
    static List<TransformRecord> extractTransforms(ClassNode cn, JarIndex jar) {
        List<TransformRecord> results = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) continue;
            collectMethodTransforms(cn, mn, jar, 0, 0, visited, results);
        }
        return results;
    }

    private static void collectMethodTransforms(ClassNode owner, MethodNode mn, JarIndex jar,
                                                int depth, int forcedMask, Set<String> visited,
                                                List<TransformRecord> out) {
        String visitKey = owner.name + "#" + mn.name + mn.desc + "@" + forcedMask;
        if (!visited.add(visitKey)) return;
        RenderTypeFlow.Result flow = RenderTypeFlow.analyze(owner, mn, jar);
        out.addAll(extractFromMethod(owner, mn, flow, forcedMask == 0 ? null : forcedMask));
        if (jar == null || depth >= MAX_HELPER_CALL_DEPTH) return;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if ("org/lwjgl/opengl/GL11".equals(call.owner)) continue;
            ClassNode targetOwner = jar.cls(call.owner);
            if (targetOwner == null) continue;
            MethodNode target = null;
            for (MethodNode candidate : targetOwner.methods)
                if (candidate.name.equals(call.name) && candidate.desc.equals(call.desc)) { target = candidate; break; }
            if (target == null || target.instructions == null || target.instructions.size() == 0) continue;
            Integer branchMask = flow.masks.get(insn);
            // A helper reached from shared/pre-dispatch code is intentionally un-attributed. It is
            // still scanned for ordinary world transforms, but cannot be assigned to a GUI/hand
            // context without a positive branch fact.
            int nextMask = branchMask != null && branchMask != 0 && branchMask != RenderTypeFlow.ALL_TYPES_MASK
                    ? branchMask : 0;
            collectMethodTransforms(targetOwner, target, jar, depth + 1, nextMask, visited, out);
        }
    }

    static List<TransformRecord> extractFromMethod(ClassNode cn, MethodNode mn) {
        return extractFromMethod(cn, mn, RenderTypeFlow.analyze(cn, mn, null));
    }

    static List<TransformRecord> extractFromMethod(ClassNode cn, MethodNode mn, RenderTypeFlow.Result flow) {
        return extractFromMethod(cn, mn, flow, null);
    }

    static List<TransformRecord> extractFromMethod(ClassNode cn, MethodNode mn, RenderTypeFlow.Result flow,
                                                   Integer forcedMask) {
        List<TransformRecord> results = new ArrayList<>();

        // Use MethodSim to track stack state and extract argument values
        MethodSim.run(cn, mn, (AbstractInsnNode insn, java.util.List<Val> stack, java.util.Map<Integer, Val> locals) -> {
            if (!(insn instanceof MethodInsnNode)) return;
            MethodInsnNode minst = (MethodInsnNode) insn;

            // Check if this is a GL transform call
            String op = null;
            int argCount = 0;
            if ("org/lwjgl/opengl/GL11".equals(minst.owner)) {
                switch (minst.name) {
                    case "glScalef": op = "glScalef"; argCount = 3; break;
                    case "glScaled": op = "glScaled"; argCount = 3; break;
                    case "glTranslatef": op = "glTranslatef"; argCount = 3; break;
                    case "glTranslated": op = "glTranslated"; argCount = 3; break;
                    case "glRotatef": op = "glRotatef"; argCount = 4; break;
                    case "glRotated": op = "glRotated"; argCount = 4; break;
                    case "glPushMatrix": op = "glPushMatrix"; argCount = 0; break;
                    case "glPopMatrix": op = "glPopMatrix"; argCount = 0; break;
                    default: return;
                }
            } else {
                return;
            }

            // Extract arguments from stack using the values tracked by MethodSim
            // Always emit full arity, with null for dynamic arguments
            List<Object> args = new ArrayList<>();
            List<Integer> dynamicIndices = new ArrayList<>();
            List<String> argNotes = new ArrayList<>();
            boolean isDynamic = false;
            String dynamicNote = "";

            if (argCount > 0 && stack.size() >= argCount) {
                // The topmost argCount values on the stack are the arguments
                // They are in order from oldest (bottom) to newest (top)
                for (int i = 0; i < argCount; i++) {
                    Val v = stack.get(stack.size() - argCount + i);
                    Object value = extractValAsConstant(v);
                    if (value != null) {
                        args.add(value);
                        argNotes.add(null);
                    } else {
                        args.add(null);
                        dynamicIndices.add(i);
                        isDynamic = true;
                        String note = describeVal(v);
                        argNotes.add(note);
                        if (dynamicNote.isEmpty()) dynamicNote = note;
                    }
                }
            } else if (argCount > 0 && stack.size() < argCount) {
                isDynamic = true;
                dynamicNote = "Insufficient stack depth for " + argCount + " args";
                for (int i = 0; i < argCount; i++) {
                    args.add(null);
                    dynamicIndices.add(i);
                    argNotes.add("missing");
                }
            }

            TransformRecord rec = new TransformRecord();
            rec.method = mn.name;
            rec.op = op;
            rec.args = args;
            rec.dynamicIndices = dynamicIndices;
            rec.argNotes = argNotes;
            rec.isDynamic = isDynamic;
            rec.dynamicNote = dynamicNote;
            // GUI-transforms lane: which ItemRenderType branches can execute this op. Only a
            // PROPER non-empty subset is informative — pre-dispatch/shared-tail code (mask ==
            // ALL) and unreached code (mask == 0/absent) stay unattributed, exactly like before.
            if (forcedMask != null && forcedMask != 0 && forcedMask != RenderTypeFlow.ALL_TYPES_MASK) {
                rec.renderTypes = RenderTypeFlow.names(forcedMask);
            } else if (flow != null && flow.skipped == null) {
                Integer mask = flow.masks.get(insn);
                if (mask != null && mask != 0 && mask != RenderTypeFlow.ALL_TYPES_MASK) {
                    rec.renderTypes = RenderTypeFlow.names(mask);
                }
            }
            synchronized (results) {
                results.add(rec);
            }
        });
        return results;
    }

    static String describeVal(Val v) {
        if (v == null) return "null";
        switch (v.kind) {
            case UNKNOWN: return v.reason != null ? v.reason : "unknown";
            case STATIC_FIELD: return "field:" + v.owner + "." + v.name;
            case PARAM: return "param_" + v.numberValue;
            case CALL: return "call:" + v.owner + "." + v.name;
            case DERIVED: return "derived:" + v.stringValue;
            case NEW_OBJ: return "new:" + v.typeName;
            case ARRAY: return "array";
            default: return v.kind.toString().toLowerCase();
        }
    }

    static Object extractValAsConstant(Val v) {
        if (v == null) return null;
        switch (v.kind) {
            case NUMBER:
                return v.numberValue;
            case STRING:
                return v.stringValue;
            default:
                return null;
        }
    }


    static void writeOutput(Path path, Map<String, List<TransformRecord>> data) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject transforms = new JsonObject();

        for (Map.Entry<String, List<TransformRecord>> e : data.entrySet()) {
            JsonArray arr = new JsonArray();
            for (TransformRecord rec : e.getValue()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("method", rec.method);
                obj.addProperty("op", rec.op);
                JsonArray argsArr = new JsonArray();
                for (Object arg : rec.args) {
                    if (arg == null) {
                        argsArr.add((String) null);  // Emit null
                    } else if (arg instanceof Float) {
                        argsArr.add((Float) arg);
                    } else if (arg instanceof Double) {
                        argsArr.add((Double) arg);
                    } else if (arg instanceof Integer) {
                        argsArr.add((Integer) arg);
                    } else if (arg instanceof Long) {
                        argsArr.add((Long) arg);
                    } else {
                        argsArr.add(String.valueOf(arg));
                    }
                }
                obj.add("args", argsArr);
                if (!rec.dynamicIndices.isEmpty()) {
                    JsonArray dynArr = new JsonArray();
                    for (int idx : rec.dynamicIndices) {
                        dynArr.add(idx);
                    }
                    obj.add("dynamicArgs", dynArr);
                }
                obj.addProperty("dynamic", rec.isDynamic);
                if (rec.isDynamic && !rec.dynamicNote.isEmpty()) {
                    obj.addProperty("note", rec.dynamicNote);
                }
                if (rec.renderTypes != null && !rec.renderTypes.isEmpty()) {
                    JsonArray rtArr = new JsonArray();
                    for (String rt : rec.renderTypes) rtArr.add(rt);
                    obj.add("renderTypes", rtArr);
                }
                arr.add(obj);
            }
            transforms.add(e.getKey(), arr);
        }
        root.add("renderers", transforms);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(path, gson.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Produces the new, context-oriented sidecar consumed by the object bridge.  The old file is
     * deliberately left byte-for-byte in its existing shape.  Context attribution is only emitted
     * for a proper ItemRenderType branch subset, or for the explicitly named inventory callback;
     * unclassified/shared code is counted as a fallback instead of being guessed into GUI.
     */
    static ItemDisplayOutput buildItemDisplayOutput(Map<String, List<TransformRecord>> all,
                                                    JarIndex jar) {
        Map<String, Map<String, List<TransformRecord>>> byClass = new TreeMap<>();
        Map<String, Integer> contexts = new TreeMap<>();
        Map<String, Integer> fallbacks = new TreeMap<>();
        int itemRendererClasses = 0;
        List<String> itemRendererNames = new ArrayList<>();
        if (jar != null) {
            for (org.objectweb.asm.tree.ClassNode cn : jar.implementorsOf(
                    "net/minecraftforge/client/IItemRenderer")) {
                itemRendererClasses++;
                itemRendererNames.add(JarIndex.dotted(cn.name));
                List<TransformRecord> records = all.getOrDefault(JarIndex.dotted(cn.name), List.of());
                boolean any = false;
                for (TransformRecord r : records) {
                    List<String> names = displayContexts(r);
                    if (names.isEmpty()) continue;
                    any = true;
                    for (String context : names) {
                        byClass.computeIfAbsent(JarIndex.dotted(cn.name), k -> new TreeMap<>())
                                .computeIfAbsent(context, k -> new ArrayList<>()).add(r);
                        contexts.merge(context, 1, Integer::sum);
                    }
                }
                if (!any) fallbacks.merge("no-extractable-item-context", 1, Integer::sum);
            }
        }
        for (Map.Entry<String, List<TransformRecord>> e : all.entrySet()) {
            for (TransformRecord r : e.getValue()) {
                if (!"renderInventoryBlock".equals(r.method)) continue;
                byClass.computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                        .computeIfAbsent("gui", k -> new ArrayList<>()).add(r);
                contexts.merge("gui", 1, Integer::sum);
            }
        }
        return new ItemDisplayOutput(byClass, contexts, fallbacks, itemRendererClasses, itemRendererNames);
    }

    private static List<String> displayContexts(TransformRecord r) {
        if (r.renderTypes != null && !r.renderTypes.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String type : r.renderTypes) {
                String context = switch (type) {
                    case "INVENTORY" -> "gui";
                    case "EQUIPPED_FIRST_PERSON" -> "firstperson_righthand";
                    case "EQUIPPED" -> "thirdperson_righthand";
                    case "ENTITY" -> "ground";
                    case "FIRST_PERSON_MAP" -> "fixed";
                    default -> null;
                };
                if (context != null && !out.contains(context)) out.add(context);
            }
            return out;
        }
        if ("renderInventoryBlock".equals(r.method)) return List.of("gui");
        return List.of();
    }

    static void writeItemDisplayOutput(Path path, ItemDisplayOutput data) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "umb.item-display-transforms.v1");
        root.addProperty("analysisBound", RenderTypeFlow.MAX_DATAFLOW_STEPS_PER_INSN);
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> e : data.contextCounts.entrySet()) counts.addProperty(e.getKey(), e.getValue());
        root.add("attributedOpsByContext", counts);
        JsonObject fallback = new JsonObject();
        for (Map.Entry<String, Integer> e : data.fallbackCounts.entrySet()) fallback.addProperty(e.getKey(), e.getValue());
        root.add("fallbacks", fallback);
        root.addProperty("itemRendererClassCount", data.itemRendererClasses);
        JsonArray names = new JsonArray();
        for (String name : data.itemRendererNames) names.add(name);
        root.add("itemRendererClasses", names);
        JsonObject renderers = new JsonObject();
        for (Map.Entry<String, Map<String, List<TransformRecord>>> ce : data.byClass.entrySet()) {
            JsonObject contexts = new JsonObject();
            for (Map.Entry<String, List<TransformRecord>> xe : ce.getValue().entrySet()) {
                JsonArray arr = new JsonArray();
                for (TransformRecord rec : xe.getValue()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("method", rec.method);
                    obj.addProperty("op", rec.op);
                    JsonArray args = new JsonArray();
                    for (Object arg : rec.args) {
                        if (arg == null) args.add((String) null); else args.add(String.valueOf(arg));
                    }
                    obj.add("args", args);
                    obj.addProperty("dynamic", rec.isDynamic);
                    if (rec.isDynamic && !rec.dynamicNote.isEmpty()) obj.addProperty("note", rec.dynamicNote);
                    arr.add(obj);
                }
                contexts.add(xe.getKey(), arr);
            }
            renderers.add(ce.getKey(), contexts);
        }
        root.add("renderers", renderers);
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }

    static final class ItemDisplayOutput {
        final Map<String, Map<String, List<TransformRecord>>> byClass;
        final Map<String, Integer> contextCounts, fallbackCounts;
        final int itemRendererClasses;
        final List<String> itemRendererNames;
        ItemDisplayOutput(Map<String, Map<String, List<TransformRecord>>> byClass,
                          Map<String, Integer> contextCounts, Map<String, Integer> fallbackCounts,
                          int itemRendererClasses, List<String> itemRendererNames) {
            this.byClass = byClass; this.contextCounts = contextCounts;
            this.fallbackCounts = fallbackCounts; this.itemRendererClasses = itemRendererClasses;
            this.itemRendererNames = itemRendererNames;
        }
    }

    static void log(String msg) {
        System.out.println("[RendererTransformExtractor] " + msg);
    }

    static class TransformRecord {
        String method;
        String op;
        List<Object> args;
        List<Integer> dynamicIndices;
        List<String> argNotes;
        boolean isDynamic;
        String dynamicNote;
        /** ItemRenderType branch attribution ({@link RenderTypeFlow}), or null when this op is
         *  type-independent / pre-dispatch / in a method with no ItemRenderType parameter. */
        List<String> renderTypes;
    }
}
