package dev.umb.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Evidence-driven mod analyzer (spec §17–§18). Every detection decision is backed by
 * recorded {@link ModAnalysis.Evidence}; nothing is guessed silently. Metadata sources are
 * probed in priority order (fabric.mod.json → mods.toml/neoforge.mods.toml → mcmod.info →
 * manifest), then a full entry scan collects structural signals (bytecode version, package
 * histogram, coremod implementors, mapping-namespace markers, embedded/native payloads).
 */
public final class BasicModAnalyzer implements ModAnalyzer {

    private static final Pattern SRG_REF = Pattern.compile("\\b(?:func|field)_\\d+_");
    private static final Pattern INTERMEDIARY_REF = Pattern.compile("\\b(?:method|field)_\\d+_\\w");
    private static final Pattern VANILLA_REF =
            Pattern.compile("(?:net/minecraft/|net\\.minecraft\\.)");
    /**
     * Ornithe legacy intermediary (Calamus gen2): classes are net/minecraft/unmapped/C_<id>,
     * members are named f_<id>/m_<id>. The member pattern matches the scheme as it lands in
     * jars (f_58001851, no trailing separator) and the mapping-file spelling with one.
     */
    private static final Pattern ORNITHE_CLASS_REF = Pattern.compile("^net/minecraft/unmapped/C_\\d+$");
    private static final Pattern ORNITHE_MEMBER_REF = Pattern.compile("\\b[fm]_\\d+");
    /** unmapped/C_ ids are intermediary markers, not "named" mojang refs — the fallback must exclude them. */
    private static final Pattern UNMAPPED_REF = Pattern.compile("net/minecraft/unmapped/C_\\d+");
    private static final Pattern VERSION_TOKEN =
            Pattern.compile("(\\d+(?:\\.\\d+){1,3}(?:-pre\\d+)?)");
    private static final Pattern TOML_MC_RANGE =
            Pattern.compile("versionRange\\s*=\\s*\"([^\"]*\\d+(?:\\.\\d+)+[^\"]*)\"");

    @Override
    public ModAnalysis analyze(Path jar) throws IOException {
        List<ModAnalysis.Evidence> evidence = new ArrayList<>();
        List<String> entrypoints = new ArrayList<>();
        ClassScan classScan = new ClassScan();

        String modId = null;
        String modVersion = null;
        Optional<String> sourceMcVersion = Optional.empty();
        ModAnalysis.LoaderKind loader = ModAnalysis.LoaderKind.UNKNOWN;

        List<String> mixinConfigs = new ArrayList<>();
        List<ModAnalysis.MixinConfigInfo> mixinInfo = new ArrayList<>();
        List<String> accessWideners = new ArrayList<>();
        List<String> accessTransformers = new ArrayList<>();
        List<ModAnalysis.EmbeddedLibrary> embeddedLibraries = new ArrayList<>();
        List<String> knownIncompatibilities = new ArrayList<>();
        String corePlugin = null;
        String tweakClass = null;
        List<String> jsCoremods = new ArrayList<>();
        List<String> historicalCoremodJs = new ArrayList<>();
        MixinInventoryBuilder mixinBuilder = new MixinInventoryBuilder();

        try (JarFile jf = new JarFile(jar.toFile())) {
            // ---------------- metadata probes (priority order) ----------------
            JsonObject fabricJson = readJsonEntry(jf, "fabric.mod.json");
            if (fabricJson != null) {
                loader = ModAnalysis.LoaderKind.FABRIC;
                modId = optString(fabricJson, "id");
                modVersion = optString(fabricJson, "version");
                evidence.add(new ModAnalysis.Evidence("metadata", "fabric.mod.json present", 0.98));
                String depends = dependencyConstraint(fabricJson.getAsJsonObject("depends"), "minecraft");
                if (depends != null) {
                    sourceMcVersion = highestMentionedVersion(depends);
                    evidence.add(new ModAnalysis.Evidence("mc-version",
                            "fabric depends.minecraft=" + depends + " -> "
                                    + sourceMcVersion.orElse("(range only)"),
                            sourceMcVersion.isPresent() ? 0.8 : 0.4));
                }
                collectEntrypoints(fabricJson, entrypoints);
                JsonElement aw = fabricJson.get("accessWidener");
                if (aw != null && !aw.isJsonNull()) {
                    accessWideners.add(aw.getAsString());
                    evidence.add(new ModAnalysis.Evidence("access",
                            "accessWidener: " + aw.getAsString(), 0.95));
                }
                for (JsonElement m : orEmpty(fabricJson.getAsJsonArray("mixins"))) {
                    mixinConfigs.add(m.isJsonObject()
                            ? optString(m.getAsJsonObject(), "value")
                            : m.getAsString());
                }
                mixinConfigs.removeIf(java.util.Objects::isNull);
            }

            String modsToml = readTextEntry(jf, "META-INF/mods.toml");
            String neoToml = readTextEntry(jf, "META-INF/neoforge.mods.toml");
            String toml = neoToml != null ? neoToml : modsToml;
            if (toml != null) {
                loader = neoToml != null ? ModAnalysis.LoaderKind.NEOFORGE : ModAnalysis.LoaderKind.FORGE;
                modId = firstTomlValue(toml, "modId");
                modVersion = firstTomlValue(toml, "version");
                evidence.add(new ModAnalysis.Evidence("metadata",
                        (neoToml != null ? "neoforge.mods.toml" : "mods.toml") + " present", 0.95));
                Matcher mm = TOML_MC_RANGE.matcher(toml);
                if (mm.find()) {
                    sourceMcVersion = highestMentionedVersion(mm.group(1));
                    evidence.add(new ModAnalysis.Evidence("mc-version",
                            "dependency range=" + mm.group(1) + " -> "
                                    + sourceMcVersion.orElse("(range only)"),
                            sourceMcVersion.isPresent() ? 0.85 : 0.4));
                }
            } else {
                String mcmod = readTextEntry(jf, "mcmod.info");
                if (mcmod != null) {
                    loader = ModAnalysis.LoaderKind.FORGE;
                    evidence.add(new ModAnalysis.Evidence("metadata",
                            "mcmod.info present (legacy Forge)", 0.9));
                    try {
                        JsonElement rootEl = JsonParser.parseString(mcmod);
                        JsonArray arr;
                        if (rootEl.isJsonArray()) {
                            // canonical legacy form: a bare array of mod entries
                            arr = rootEl.getAsJsonArray();
                        } else {
                            // some packs wrap it: {"modList":[...]} or {"mods":[...]}
                            JsonObject obj = rootEl.getAsJsonObject();
                            arr = obj.has("modList") ? obj.getAsJsonArray("modList")
                                    : obj.has("mods") ? obj.getAsJsonArray("mods") : null;
                        }
                        if (arr != null && !arr.isEmpty()) {
                            JsonObject first = arr.get(0).getAsJsonObject();
                            modId = optString(first, "modid");
                            modVersion = optString(first, "version");
                        }
                    } catch (RuntimeException untrustedInput) {
                        // jar contents are untrusted (spec §131): record, don't crash.
                        knownIncompatibilities.add(
                                "mcmod.info unparseable: " + untrustedInput.getMessage());
                    }
                    Matcher mcver = Pattern.compile("\"mcversion\"\\s*:\\s*\"([^\"]+)\"").matcher(mcmod);
                    if (mcver.find()) {
                        sourceMcVersion = Optional.of(mcver.group(1));
                        evidence.add(new ModAnalysis.Evidence("mc-version",
                                "mcmod.info mcversion=" + mcver.group(1), 0.95));
                    }
                }
            }

            // Modern Forge/NeoForge (>=1.13) register JS coremods in mods.toml:
            // [[coremods]] modId=... coremodJarFileName=... The .js body is verified when its
            // content is scanned on the entry pass (initializeCoreMod marks JS_COREMOD_MODERN).
            if (toml != null) {
                Matcher coreJs = Pattern.compile("coremodJarFileName\\s*=\\s*\"([^\"]+)\"")
                        .matcher(toml);
                while (coreJs.find()) {
                    jsCoremods.add(coreJs.group(1));
                    evidence.add(new ModAnalysis.Evidence("coremod",
                            "mods.toml [[coremods]] JS coremod: " + coreJs.group(1), 0.95));
                }
            }

            java.util.jar.Manifest mf = jf.getManifest();
            if (mf != null) {
                java.util.jar.Attributes attrs = mf.getMainAttributes();
                corePlugin = attrs.getValue("FMLCorePlugin");
                if (corePlugin != null) {
                    evidence.add(new ModAnalysis.Evidence("coremod",
                            "MANIFEST FMLCorePlugin=" + corePlugin, 0.95));
                }
                String fmlAt = attrs.getValue("FMLAT");
                if (fmlAt != null) {
                    for (String at : fmlAt.split("[;, ]")) {
                        if (!at.isBlank()) {
                            accessTransformers.add(resolveFmlAtPath(jf, at.trim()));
                        }
                    }
                    evidence.add(new ModAnalysis.Evidence("access", "MANIFEST FMLAT=" + fmlAt, 0.95));
                }
                tweakClass = attrs.getValue("TweakClass");
                if (tweakClass != null) {
                    evidence.add(new ModAnalysis.Evidence("coremod",
                            "LaunchWrapper TweakClass=" + tweakClass, 0.7));
                    knownIncompatibilities.add("LaunchWrapper tweaker present: " + tweakClass);
                }
                String mixinAttr = attrs.getValue("MixinConfigs");
                if (mixinAttr != null) {
                    for (String cfg : mixinAttr.split(",")) {
                        if (!cfg.isBlank()) {
                            mixinConfigs.add(cfg.trim());
                        }
                    }
                }
            }

            // ---------------- per-entry scan ----------------
            for (java.util.Enumeration<JarEntry> entries = jf.entries(); entries.hasMoreElements(); ) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (name.endsWith(".class")) {
                    scanClass(jf, e, name, classScan, mixinBuilder);
                } else if (name.endsWith(".js")) {
                    String text = readTextEntry(jf, name);
                    if (text != null && text.contains("initializeCoreMod")) {
                        jsCoremods.add(name);
                        evidence.add(new ModAnalysis.Evidence("coremod",
                                "JS coremod initializeCoreMod: " + name, 0.9));
                    } else if (text != null && historicalJsMarker(text)) {
                        historicalCoremodJs.add(name);
                        evidence.add(new ModAnalysis.Evidence("coremod",
                                "historical ModLoader-era JS patch: " + name, 0.4));
                    }
                } else if (name.endsWith(".jar") && !name.startsWith("META-INF/versions/")) {
                    embeddedLibraries.add(new ModAnalysis.EmbeddedLibrary(name,
                            String.format(Locale.ROOT, "%08x", e.getCrc())));
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("guava") || lower.contains("/asm") || lower.contains("netty")
                            || lower.contains("gson") || lower.contains("commons")) {
                        knownIncompatibilities.add("embedded library may collide with host: " + name);
                    }
                } else if (name.endsWith(".dll") || name.endsWith(".so")
                        || name.endsWith(".jnilib") || name.endsWith(".dylib")) {
                    knownIncompatibilities.add("NATIVE_DEPENDENCY: " + name);
                } else if (name.startsWith("META-INF/accesstransformer")) {
                    accessTransformers.add(name);
                    evidence.add(new ModAnalysis.Evidence("access",
                            "accesstransformer file: " + name, 0.95));
                }
            }
            if (!classScan.coremodImpls.isEmpty()) {
                evidence.add(new ModAnalysis.Evidence("coremod",
                        "IFMLLoadingPlugin implementors: " + classScan.coremodImpls, 0.95));
            }

            // ---------------- mixin configs ----------------
            for (String cfgPath : mixinConfigs) {
                String text = readTextEntry(jf, cfgPath);
                if (text != null) {
                    parseMixinConfig(cfgPath, text, mixinInfo, evidence, mixinBuilder);
                }
            }
            // also discover configs not referenced from metadata (common on old Forge)
            for (java.util.Enumeration<JarEntry> entries = jf.entries(); entries.hasMoreElements(); ) {
                String n = entries.nextElement().getName();
                boolean looksLikeMixinConfig = n.endsWith(".json")
                        && (n.contains("mixins.") || n.contains(".mixins."));
                if (looksLikeMixinConfig && !alreadyRecorded(mixinInfo, n)) {
                    String text = readTextEntry(jf, n);
                    if (text != null && text.contains("\"package\"")) {
                        parseMixinConfig(n, text, mixinInfo, evidence, mixinBuilder);
                    }
                }
            }
        }

        // ---------------- refmap load (M8-2: per-config refmap, EMPTY fallback per D4) ----------------
        try (JarFile jf2 = new JarFile(jar.toFile())) {
            for (ModAnalysis.MixinConfigInfo cfg : mixinInfo) {
                String refmapPath = null;
                String text2 = readTextEntry(jf2, cfg.path());
                if (text2 != null) {
                    try {
                        JsonObject obj2 = JsonParser.parseString(text2).getAsJsonObject();
                        if (obj2.has("refmap") && !obj2.get("refmap").isJsonNull()) {
                            refmapPath = obj2.get("refmap").getAsString();
                        }
                    } catch (RuntimeException ignored) {}
                }
                Refmap rm;
                if (refmapPath != null && !refmapPath.isBlank()) {
                    rm = Refmap.loadFromJar(jf2, refmapPath);
                    if (rm.isAbsent() || rm.isMalformed()) {
                        evidence.add(new ModAnalysis.Evidence("mixin",
                                "refmap " + refmapPath + " for " + cfg.path() + " " + rm.status()
                                        + (rm.error() != null ? ": " + rm.error() : ""), 0.8));
                    }
                } else {
                    rm = Refmap.EMPTY;
                }
                mixinBuilder.putRefmap(cfg.path(), rm);
            }
        } catch (IOException ignored) {}

        // ---------------- synthesis ----------------
        ModAnalysis.MappingNamespace ns = inferNamespace(classScan, evidence);

        ModAnalysis.MixinInventory inv = mixinBuilder.build(classScan.classNames);
        for (ModAnalysis.MixinConfigInventory cfg : inv.configs()) {
            if (!cfg.missingMixins().isEmpty()) {
                evidence.add(new ModAnalysis.Evidence("mixin",
                        "config " + cfg.path() + " declares " + cfg.declaredCount()
                                + " mixins, " + cfg.missingCount() + " NOT in jar: "
                                + String.join(", ", cfg.missingMixins()), 0.95));
            }
        }
        if (!inv.undeclaredMixinClasses().isEmpty()) {
            evidence.add(new ModAnalysis.Evidence("mixin",
                    inv.undeclaredMixinClasses().size()
                            + " @Mixin-bearing class(es) not listed in any config: "
                            + String.join(", ", inv.undeclaredMixinClasses()), 0.9));
        }

        List<ModAnalysis.CoremodShape> coremodShapes = classifyCoremodShapes(classScan,
                corePlugin, tweakClass, jsCoremods, historicalCoremodJs,
                !accessTransformers.isEmpty());
        boolean hasCoremod = coremodShapes.stream()
                .anyMatch(s -> s != ModAnalysis.CoremodShape.AT_ONLY);
        List<String> coremodClasses = new ArrayList<>(classScan.coremodImpls);
        if (corePlugin != null && !coremodClasses.contains(corePlugin)) {
            coremodClasses.add(corePlugin);
        }
        if (tweakClass != null && !coremodClasses.contains(tweakClass)) {
            coremodClasses.add(tweakClass);
        }
        if (!coremodShapes.isEmpty()) {
            evidence.add(new ModAnalysis.Evidence("coremod",
                    "shapes=" + coremodShapes.stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.joining(",")), 0.95));
        }

        if (classScan.maxMajor > 69) {
            knownIncompatibilities.add("class file major " + classScan.maxMajor
                    + " exceeds Java 25 runtime major 69");
        }
        if (loader == ModAnalysis.LoaderKind.UNKNOWN && classScan.maxMajor > 0) {
            evidence.add(new ModAnalysis.Evidence("metadata",
                    "no metadata file found; loader inferred from structure only", 0.2));
        }
        if (!embeddedLibraries.isEmpty()) {
            evidence.add(new ModAnalysis.Evidence("deps",
                    embeddedLibraries.size() + " embedded jar libraries", 1.0));
        }

        return new ModAnalysis(
                modId, modVersion, sourceMcVersion, loader, ns, classScan.maxMajor,
                List.copyOf(entrypoints), List.copyOf(mixinInfo),
                List.copyOf(accessWideners), List.copyOf(accessTransformers),
                hasCoremod, List.copyOf(coremodClasses),
                List.copyOf(embeddedLibraries), Map.copyOf(classScan.packages),
                List.of(), List.copyOf(knownIncompatibilities), inv,
                List.copyOf(coremodShapes), List.copyOf(evidence));
    }

    // ------------------------------------------------------------------ helpers

    /** Mutable accumulation of the per-entry class scan. */
    private static final class ClassScan {
        final Map<String, Integer> packages = new HashMap<>();
        final List<String> coremodImpls = new ArrayList<>();
        final Set<String> classNames = new HashSet<>();
        int maxMajor;
        int srgRefs;
        int intermediaryRefs;
        int vanillaRefs;
        int ornitheClassRefs;   // classes named net/minecraft/unmapped/C_<id>
        int ornitheMemberRefs;  // f_<id>/m_<id> markers in descriptor/string pools
        int unmappedRefs;       // net/minecraft/unmapped/C_<id> refs in descriptor/string pools
    }

    private void scanClass(JarFile jf, JarEntry entry, String name,
                               ClassScan out, MixinInventoryBuilder mixinBuilder) {
        try (InputStream in = jf.getInputStream(entry)) {
            ClassReader cr = new ClassReader(in);
            ClassNode node = new ClassNode();
            // Full parse (SKIP_FRAMES only): string-constant pools carry the namespace
            // markers, and skipping code would leave instructions — and LDC strings — empty.
            cr.accept(node, ClassReader.SKIP_FRAMES);

            out.classNames.add(node.name);

            // ASM packs ClassNode.version as minor<<16 | major (verified empirically on
            // ASM 9.9: a major-65/minor-0 classfile yields raw int 65), so the MAJOR
            // lives in the low half — the >>> 16 form read the minor and always got 0.
            int major = node.version & 0xFFFF;
            if (major > out.maxMajor) {
                out.maxMajor = major;
            }
            int slash = node.name.lastIndexOf('/');
            if (slash > 0) {
                out.packages.merge(node.name.substring(0, slash), 1, Integer::sum);
            }
            for (String itf : node.interfaces) {
                if (itf.contains("IFMLLoadingPlugin")) {
                    out.coremodImpls.add(node.name);
                }
            }
            if (ORNITHE_CLASS_REF.matcher(node.name).matches()) {
                out.ornitheClassRefs++;
            }
            countNamespaceMarkers(node, out);
            mixinBuilder.observeClass(node);
        } catch (IOException | RuntimeException malformedClassFile) {
            // Third-party jars are untrusted input (spec §131): record nothing, crash never.
        }
    }

    /**
     * Counts mapping-namespace markers found in descriptor/string pools. LDC strings carry
     * reflection lookups like <c>Class.forName("func_71410_x")</c>, which are the strongest
     * namespace signal that survives compilation.
     */
    private static void countNamespaceMarkers(ClassNode node, ClassScan out) {
        StringBuilder sb = new StringBuilder();
        for (var mn : node.methods) {
            sb.append(mn.desc).append('\n');
            for (var insn : mn.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    sb.append(s).append('\n');
                }
            }
        }
        for (var fn : node.fields) {
            sb.append(fn.name).append(' ').append(fn.desc).append('\n');
        }
        String hay = sb.toString();
        Matcher srg = SRG_REF.matcher(hay);
        while (srg.find()) { out.srgRefs++; }
        Matcher itp = INTERMEDIARY_REF.matcher(hay);
        while (itp.find()) { out.intermediaryRefs++; }
        // Count every occurrence, not just "class mentions vanilla": proportional signal.
        Matcher vm = VANILLA_REF.matcher(hay);
        while (vm.find()) { out.vanillaRefs++; }
        // unmapped/C_ ids are Ornithe intermediary markers (a subset of the vanilla refs
        // above) — the MOJANG fallback subtracts them so such refs never read as named.
        Matcher unm = UNMAPPED_REF.matcher(hay);
        while (unm.find()) { out.unmappedRefs++; }
        Matcher om = ORNITHE_MEMBER_REF.matcher(hay);
        while (om.find()) { out.ornitheMemberRefs++; }
    }

    private static ModAnalysis.MappingNamespace inferNamespace(
            ClassScan scan, List<ModAnalysis.Evidence> ev) {
        if (scan.srgRefs > 1 && scan.srgRefs >= scan.intermediaryRefs * 2) {
            ev.add(new ModAnalysis.Evidence("namespace",
                    "srg func_/field_ refs=" + scan.srgRefs, 0.9));
            return ModAnalysis.MappingNamespace.SRG;
        }
        if (scan.intermediaryRefs > 2) {
            ev.add(new ModAnalysis.Evidence("namespace",
                    "intermediary method_/field_ refs=" + scan.intermediaryRefs, 0.9));
            return ModAnalysis.MappingNamespace.INTERMEDIARY;
        }
        // Ornithe legacy intermediary markers, checked BEFORE the "named refs" fallback so
        // a jar whose net.minecraft refs are all unmapped C_ ids infers INTERMEDIARY,
        // never MOJANG (the unmapped/C_ package prefix used to read as "named" refs).
        int ornitheRefs = scan.ornitheClassRefs + scan.ornitheMemberRefs;
        if (ornitheRefs > 2) {
            ev.add(new ModAnalysis.Evidence("namespace",
                    "unmapped/C_ intermediary ids (" + ornitheRefs + ")", 0.9));
            return ModAnalysis.MappingNamespace.INTERMEDIARY;
        }
        // "Named" mojang refs are the non-unmapped remainder: unmapped/C_ ids are
        // intermediary markers and must not push a jar past the named-refs threshold.
        int namedRefs = scan.vanillaRefs - scan.unmappedRefs;
        if (namedRefs > 4 && scan.srgRefs == 0 && scan.intermediaryRefs == 0) {
            ev.add(new ModAnalysis.Evidence("namespace",
                    "named net.minecraft refs (" + namedRefs
                            + ") without obfuscation markers", 0.6));
            return ModAnalysis.MappingNamespace.MOJANG;
        }
        // No positive signal ⇒ UNKNOWN, never a guess (spec §17–§18). A jar with zero
        // net.minecraft contact carries no namespace evidence at all; reporting OBFUSCATED
        // there used to convert absence of data into an affirmative (wrong) claim.
        return ModAnalysis.MappingNamespace.UNKNOWN;
    }

    /**
     * One classified shape per coremod mechanism present (deduped). NONE is never added —
     * an empty list IS the "no coremod" verdict, and a hollow AT-only jar is its own shape.
     * {@code AT_ONLY} is the only shape that does not make {@code hasCoremod} true: an
     * access transformer widens access, it is not a bytecode-rewriting coremod.
     */
    private static List<ModAnalysis.CoremodShape> classifyCoremodShapes(
            ClassScan scan, String corePlugin, String tweakClass,
            List<String> jsCoremods, List<String> historicalJs, boolean atSignals) {
        List<ModAnalysis.CoremodShape> out = new ArrayList<>();
        boolean ifmlPlugin = !scan.coremodImpls.isEmpty()
                || (corePlugin != null && !scan.coremodImpls.contains(corePlugin));
        if (ifmlPlugin) {
            out.add(ModAnalysis.CoremodShape.IFML_PLUGIN);
        }
        if (tweakClass != null) {
            out.add(ModAnalysis.CoremodShape.LAUNCHWRAPPER_TWEAKER);
        }
        if (!jsCoremods.isEmpty()) {
            out.add(ModAnalysis.CoremodShape.JS_COREMOD_MODERN);
        }
        if (!historicalJs.isEmpty()) {
            out.add(ModAnalysis.CoremodShape.JS_COREMOD_HISTORICAL);
        }
        if (out.isEmpty() && atSignals) {
            out.add(ModAnalysis.CoremodShape.AT_ONLY);
        }
        return out;
    }

    /** Heuristic marker for a ModLoader-era (pre-1.6) .js behavior patch; weak signal, kept low confidence. */
    private static boolean historicalJsMarker(String text) {
        return text.contains("addOverride") || text.contains("ModLoader");
    }

    private void parseMixinConfig(String path, String json,
                                  List<ModAnalysis.MixinConfigInfo> sink,
                                  List<ModAnalysis.Evidence> evidence,
                                  MixinInventoryBuilder mixinBuilder) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String pkg = obj.has("package") && !obj.get("package").isJsonNull()
                    ? obj.get("package").getAsString() : "";
            List<String> mixins = new ArrayList<>();
            for (String key : new String[]{"mixins", "client", "server"}) {
                for (JsonElement m : orEmpty(obj.getAsJsonArray(key))) {
                    mixins.add(m.isJsonObject() ? m.toString() : pkg + '.' + m.getAsString());
                }
            }
            String refmap = obj.has("refmap") && !obj.get("refmap").isJsonNull()
                    ? obj.get("refmap").getAsString() : null;
            String plugin = optString(obj, "plugin");
            String minVersion = optString(obj, "minVersion");
            String compatLevel = obj.has("compatibilityLevel")
                    ? obj.get("compatibilityLevel").getAsString() : "";
            int defaultRequire = 0;
            JsonObject injectors = obj.getAsJsonObject("injectors");
            if (injectors != null && injectors.has("defaultRequire")
                    && injectors.get("defaultRequire").isJsonPrimitive()) {
                defaultRequire = Math.max(0, injectors.get("defaultRequire").getAsInt());
            }
            mixinBuilder.addConfig(path, pkg, refmap, refmap != null, plugin, minVersion,
                    compatLevel, defaultRequire, mixins);
            sink.add(new ModAnalysis.MixinConfigInfo(path, pkg, mixins, refmap != null));
            evidence.add(new ModAnalysis.Evidence("mixin",
                    "config " + path + ": " + mixins.size() + " mixins, refmap="
                            + (refmap != null ? refmap : "none"), 0.95));
        } catch (RuntimeException badJson) {
            evidence.add(new ModAnalysis.Evidence("mixin", "unparseable config " + path, 0.2));
        }
    }

    private static boolean alreadyRecorded(List<ModAnalysis.MixinConfigInfo> infos, String path) {
        return infos.stream().anyMatch(i -> i.path().equals(path));
    }

    private static void collectEntrypoints(JsonObject fabric, List<String> sink) {
        JsonObject eps = fabric.getAsJsonObject("entrypoints");
        if (eps == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> en : eps.entrySet()) {
            for (JsonElement v : orEmpty(en.getValue().getAsJsonArray())) {
                sink.add(en.getKey() + ':' + (v.isJsonObject()
                        ? v.getAsJsonObject().get("value").getAsString()
                        : v.getAsString()));
            }
        }
    }

    private static JsonArray orEmpty(JsonArray a) {
        return a == null ? new JsonArray() : a;
    }

    private static String optString(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static String dependencyConstraint(JsonObject depends, String key) {
        if (depends == null) {
            return null;
        }
        for (String k : depends.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                JsonElement v = depends.get(k);
                if (v.isJsonPrimitive()) {
                    return v.getAsString();
                }
            }
        }
        return null;
    }

    /** Extracts the highest concrete version mentioned in a constraint/range string. */
    private static Optional<String> highestMentionedVersion(String constraint) {
        Matcher m = VERSION_TOKEN.matcher(constraint);
        Optional<String> best = Optional.empty();
        while (m.find()) {
            String cand = m.group(1);
            if (best.isEmpty() || compareVersions(cand, best.get()) > 0) {
                best = Optional.of(cand);
            }
        }
        return best;
    }

    /**
     * Explicit ordering model — NEVER lexicographic ("26.2" vs "1.21.11" sorts wrong).
     * Modern year-style versions (first component ≥ 20) outrank the whole legacy 1.x
     * family; within a family compare component-wise numerically.
     */
    static int compareVersions(String a, String b) {
        int[] pa = parts(a);
        int[] pb = parts(b);
        boolean modernA = pa.length > 0 && pa[0] >= 20;
        boolean modernB = pb.length > 0 && pb[0] >= 20;
        if (modernA != modernB) {
            return modernA ? 1 : -1;
        }
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        Matcher m = Pattern.compile("\\d+").matcher(v);
        List<Integer> list = new ArrayList<>();
        while (m.find()) {
            list.add(Integer.parseInt(m.group()));
        }
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static String firstTomlValue(String toml, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"",
                        Pattern.MULTILINE)
                .matcher(toml);
        return m.find() ? m.group(1) : null;
    }

    private static String resolveFmlAtPath(JarFile jf, String raw) {
        // Forge resolves the bare manifest name as META-INF/<name> (verified in
        // ModAccessTransformer.addJar: jar.getJarEntry("META-INF/"+at)). Real Forge
        // mods (e.g. HBM) declare FMLAT: HBM_at.cfg with the entry at
        // META-INF/HBM_at.cfg, so resolve before recording. Raw manifest value stays
        // in the evidence string for provenance; unresolvable names pass through
        // verbatim so downstream still reports "not found in jar" honestly.
        if (jf.getEntry(raw) != null) {
            return raw;
        }
        String stripped = raw.startsWith("/") ? raw.substring(1) : raw;
        if (!stripped.equals(raw) && jf.getEntry(stripped) != null) {
            return stripped;
        }
        String meta = "META-INF/" + stripped;
        if (jf.getEntry(meta) != null) {
            return meta;
        }
        return raw;
    }

    private static String readTextEntry(JarFile jf, String path) throws IOException {
        ZipEntry e = jf.getEntry(path);
        if (e == null) {
            return null;
        }
        try (InputStream in = jf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonObject readJsonEntry(JarFile jf, String path) throws IOException {
        String text = readTextEntry(jf, path);
        if (text == null) {
            return null;
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
