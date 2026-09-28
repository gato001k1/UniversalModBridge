package dev.umb.core;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M8-1: builds the {@link ModAnalysis.MixinInventory} — the honest declared-vs-present view
 * of a mod jar's mixin surface. Reads the SpongePowered annotation shapes directly off the
 * classfile (M8 groundwork, report §1), never a runtime. Every selector string is collected
 * for the light namespace classification; every injector's {@code require} is counted
 * (effective require = explicit value if >= 0, else the owning config's
 * {@code injectors.defaultRequire}) because require &gt; 0 is the injector that
 * FAILS HARD when it cannot bind post-translation (the M8-5/M8-7 honesty driver). Full
 * MemberInfo parse+resolve is M8-2; here the strings are only collected and lightly
 * namespace-classified.
 */
final class MixinInventoryBuilder {

    // --- SpongePowered annotation descriptors (matched by shape, no runtime needed) ---
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final List<String> INJECTION_FAMILY = List.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;");

    // --- light namespace markers (mirror of BasicModAnalyzer's classifier) ---
    private static final Pattern SRG_REF = Pattern.compile("\\b(?:func|field)_\\d+_");
    private static final Pattern INTERMEDIARY_REF = Pattern.compile("\\b(?:method|field)_\\d+_\\w");
    private static final Pattern VANILLA_REF = Pattern.compile("(?:net/minecraft/|net\\.minecraft\\.)");
    private static final Pattern UNMAPPED_REF = Pattern.compile("net/minecraft/unmapped/C_\\d+");

    /** One handler-bearing method annotation, with its raw require (null = absent). */
    private record Handler(String annotationDesc, Integer rawRequire) {}

    /** Config metadata captured during parseMixinConfig. */
    private record ConfigMeta(
            String path, String pkg, String refmapName, boolean refmapDeclared,
            String plugin, String minVersion, String compatibilityLevel, int defaultRequire,
            List<String> declaredDotNames) {}

    /** Per-config refmap verdict captured during build(). */
    private final Map<String, Refmap> refmapsByConfig = new LinkedHashMap<>();

    /** Accumulated per-class mixin scan. */
    private static final class ClassScan {
        final String internalName;
        final List<String> targets = new ArrayList<>();
        final List<String> selectors = new ArrayList<>();
        final List<Handler> handlers = new ArrayList<>();
        final List<String> shadowMembers = new ArrayList<>();
        final List<String> atIds = new ArrayList<>();
        final List<MixinSelector> mixinSelectors = new ArrayList<>();
        int accessors;
        int invokers;
        boolean mixinAnnotationPresent;
        ClassNode classNodeRef;

        ClassScan(String internalName) {
            this.internalName = internalName;
        }
    }

    private final Map<String, ConfigMeta> configs = new LinkedHashMap<>();
    private final Map<String, ClassScan> mixinClasses = new LinkedHashMap<>();

    /** Records a parsed config's declared-mixin list (first occurrence per path wins). */
    void addConfig(String path, String pkg, String refmapName, boolean refmapDeclared,
                   String plugin, String minVersion, String compatibilityLevel,
                   int defaultRequire, List<String> declaredDotNames) {
        if (!configs.containsKey(path)) {
            configs.put(path, new ConfigMeta(path, pkg, refmapName, refmapDeclared,
                    plugin, minVersion, compatibilityLevel, defaultRequire, List.copyOf(declaredDotNames)));
        }
    }

    /** Observes one parsed class; only @Mixin-bearing or accessor/invoker classes register. */
    void observeClass(ClassNode node) {
        boolean mixin = hasAnnotation(node, MIXIN);
        ClassScan scan = null;
        if (mixin) {
            scan = new ClassScan(node.name);
            scan.mixinAnnotationPresent = true;
            AnnotationNode ann = findAnnotation(node, MIXIN);
            if (ann != null) {
                for (Object t : strings(ann, "targets")) {
                    scan.targets.add((String) t);
                    scan.selectors.add((String) t);
                }
                for (Object v : clazzes(ann, "value")) {
                    scan.targets.add(((Type) v).getInternalName());
                }
            }
        }
        for (FieldNode fn : node.fields) {
            if (hasAnnotation(fn, SHADOW)) {
                if (scan == null) {
                    scan = new ClassScan(node.name);
                }
                scan.shadowMembers.add(fn.name);
            }
        }
        for (MethodNode mn : node.methods) {
            for (AnnotationNode ann : annotations(mn)) {
                String desc = ann.desc;
                if (INJECTION_FAMILY.contains(desc) || OVERWRITE.equals(desc)) {
                    if (scan == null) {
                        scan = new ClassScan(node.name);
                    }
                    scan.handlers.add(new Handler(desc, intValue(ann, "require")));
                    for (Object s : strings(ann, "method")) {
                        scan.selectors.add((String) s);
                    }
                    collectAts(ann, scan);
                } else if (ACCESSOR.equals(desc)) {
                    if (scan == null) {
                        scan = new ClassScan(node.name);
                    }
                    scan.accessors++;
                    String v = strValue(ann, "value");
                    if (v != null) {
                        scan.selectors.add(v);
                    }
                } else if (INVOKER.equals(desc)) {
                    if (scan == null) {
                        scan = new ClassScan(node.name);
                    }
                    scan.invokers++;
                    String v = strValue(ann, "value");
                    if (v != null) {
                        scan.selectors.add(v);
                    }
                } else if (SHADOW.equals(desc)) {
                    if (scan == null) {
                        scan = new ClassScan(node.name);
                    }
                    scan.shadowMembers.add(mn.name);
                }
            }
        }
        if (scan != null) {
            scan.classNodeRef = node;
            // Structured IR (M8-2): emit consumable MixinSelector records alongside the legacy inventory.
            try {
                scan.mixinSelectors.addAll(MixinSelectorParser.parseClass(node));
            } catch (RuntimeException ignored) {
                // Parser never throws on well-formed ASM trees; keep best-effort.
            }
            mixinClasses.put(node.name, scan);
        }
    }

    /** Supplies per-config refmap views captured from the jar (path -> Refmap). Call before build(). */
    void putRefmap(String configPath, Refmap refmap) {
        refmapsByConfig.put(configPath, refmap == null ? Refmap.EMPTY : refmap);
    }

    /** Finalizes the inventory against the full set of class file names in the jar. */
    ModAnalysis.MixinInventory build(Set<String> allClassNames) {
        Set<String> declaredInternals = new HashSet<>();
        List<ModAnalysis.MixinConfigInventory> configOut = new ArrayList<>();
        for (ConfigMeta cfg : configs.values()) {
            List<String> missing = new ArrayList<>();
            List<ModAnalysis.MixinClassInventory> clsOut = new ArrayList<>();
            for (String dot : cfg.declaredDotNames()) {
                String internal = toInternal(dot);
                declaredInternals.add(internal);
                ClassScan scan = mixinClasses.get(internal);
                boolean present = allClassNames.contains(internal);
                ModAnalysis.MixinClassPresence presence = present
                        ? ModAnalysis.MixinClassPresence.PRESENT_IN_JAR
                        : ModAnalysis.MixinClassPresence.MISSING_FROM_JAR;
                if (!present) {
                    missing.add(dot);
                }
                clsOut.add(finishClass(dot, presence, scan, cfg.defaultRequire()));
            }
            int presentCount = (int) clsOut.stream()
                    .filter(c -> c.presence() == ModAnalysis.MixinClassPresence.PRESENT_IN_JAR).count();
            Refmap rm = refmapsByConfig.getOrDefault(cfg.path(), Refmap.EMPTY);
            configOut.add(new ModAnalysis.MixinConfigInventory(
                    cfg.path(), cfg.pkg(), cfg.refmapName(), cfg.refmapDeclared(),
                    cfg.plugin(), cfg.minVersion(), cfg.compatibilityLevel(), cfg.defaultRequire(),
                    clsOut.size(), presentCount, missing.size(), List.copyOf(missing),
                    List.copyOf(clsOut), rm.status(), rm.entryCount(), rm.error()));
        }
        List<String> undeclared = new ArrayList<>();
        for (String internal : mixinClasses.keySet()) {
            if (!declaredInternals.contains(internal)) {
                undeclared.add(internal.replace('/', '.'));
            }
        }
        undeclared.sort(String::compareTo);
        int declared = configOut.stream().mapToInt(ModAnalysis.MixinConfigInventory::declaredCount).sum();
        int present = configOut.stream().mapToInt(ModAnalysis.MixinConfigInventory::presentCount).sum();
        int missingJ = configOut.stream().mapToInt(ModAnalysis.MixinConfigInventory::missingCount).sum();
        return new ModAnalysis.MixinInventory(List.copyOf(configOut), List.copyOf(undeclared),
                declared, present, missingJ);
    }

    private static ModAnalysis.MixinClassInventory finishClass(
            String dotName, ModAnalysis.MixinClassPresence presence, ClassScan scan,
            int configDefaultRequire) {
        if (scan == null) {
            return new ModAnalysis.MixinClassInventory(
                    dotName, presence, false, List.of(), ModAnalysis.MappingNamespace.UNKNOWN,
                    0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }
        int requireHard = 0;
        for (Handler h : scan.handlers) {
            // @Overwrite carries no require (its own semantics) — counted separately.
            if (OVERWRITE.equals(h.annotationDesc())) {
                continue;
            }
            int eff = h.rawRequire() != null && h.rawRequire() >= 0
                    ? h.rawRequire() : configDefaultRequire;
            if (eff > 0) {
                requireHard++;
            }
        }
        int overwrites = (int) scan.handlers.stream()
                .filter(h -> OVERWRITE.equals(h.annotationDesc())).count();
        return new ModAnalysis.MixinClassInventory(
                dotName, presence, scan.mixinAnnotationPresent,
                List.copyOf(scan.targets), classifyNamespace(scan.selectors),
                scan.handlers.size(), requireHard, overwrites,
                scan.accessors, scan.invokers,
                List.copyOf(scan.shadowMembers), List.copyOf(scan.atIds),
                List.copyOf(scan.mixinSelectors));
    }

    // ------------------------------------------------------------------ ASM annotation helpers

    private static List<AnnotationNode> annotations(MethodNode mn) {
        List<AnnotationNode> out = new ArrayList<>();
        if (mn.visibleAnnotations != null) {
            out.addAll(mn.visibleAnnotations);
        }
        if (mn.invisibleAnnotations != null) {
            out.addAll(mn.invisibleAnnotations);
        }
        return out;
    }

    private static boolean hasAnnotation(ClassNode node, String desc) {
        return findAnnotation(node, desc) != null;
    }

    private static boolean hasAnnotation(FieldNode fn, String desc) {
        return findAnnotation(fn, desc) != null;
    }

    private static AnnotationNode findAnnotation(ClassNode node, String desc) {
        AnnotationNode a = first(node.visibleAnnotations, desc);
        return a != null ? a : first(node.invisibleAnnotations, desc);
    }

    private static AnnotationNode findAnnotation(FieldNode fn, String desc) {
        AnnotationNode a = first(fn.visibleAnnotations, desc);
        return a != null ? a : first(fn.invisibleAnnotations, desc);
    }

    private static AnnotationNode first(List<AnnotationNode> list, String desc) {
        if (list == null) {
            return null;
        }
        for (AnnotationNode a : list) {
            if (a.desc.equals(desc)) {
                return a;
            }
        }
        return null;
    }

    private static void collectAts(AnnotationNode ann, ClassScan scan) {
        Object at = value(ann, "at");
        if (at instanceof AnnotationNode single) {
            collectAt(single, scan);
        } else if (at instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AnnotationNode atNode) {
                    collectAt(atNode, scan);
                }
            }
        }
    }

    private static void collectAt(AnnotationNode atNode, ClassScan scan) {
        String id = strValue(atNode, "value");
        if (id != null) {
            scan.atIds.add(id);
        }
        String target = strValue(atNode, "target");
        if (target != null) {
            scan.selectors.add(target);
        }
    }

    /** Reads a {@code String[]} annotation member (method selectors, guarded targets). */
    private static List<?> strings(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        if (v instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    /** Reads a {@code Class<?>[]} annotation member (@Mixin value). */
    private static List<?> clazzes(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        if (v instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private static Integer intValue(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        return v instanceof Integer i ? i : null;
    }

    private static String strValue(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        return v instanceof String s ? s : null;
    }

    private static Object value(AnnotationNode ann, String key) {
        List<Object> values = ann.values;
        if (values == null) {
            return null;
        }
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (values.get(i).equals(key)) {
                return values.get(i + 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ classification

    private static String toInternal(String dot) {
        return dot.indexOf('/') >= 0 ? dot : dot.replace('.', '/');
    }

    /** Light selector-namespace classification (M8-1; full parse+resolve is M8-2). */
    private static ModAnalysis.MappingNamespace classifyNamespace(List<String> selectors) {
        int srg = 0, itp = 0, named = 0, unmapped = 0;
        for (String s : selectors) {
            if (SRG_REF.matcher(s).find()) {
                srg++;
            }
            Matcher im = INTERMEDIARY_REF.matcher(s);
            while (im.find()) {
                itp++;
            }
            Matcher nm = VANILLA_REF.matcher(s);
            while (nm.find()) {
                named++;
            }
            if (UNMAPPED_REF.matcher(s).find()) {
                unmapped++;
            }
        }
        if (srg > 0) {
            return ModAnalysis.MappingNamespace.SRG;
        }
        if (itp > 0) {
            return ModAnalysis.MappingNamespace.INTERMEDIARY;
        }
        if (named - unmapped > 0) {
            return ModAnalysis.MappingNamespace.MOJANG;
        }
        return ModAnalysis.MappingNamespace.UNKNOWN;
    }
}