package dev.umb.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable result of analyzing a mod jar. Produced by {@link ModAnalyzer}, consumed by
 * the translation pipeline and the compatibility report. See spec §17.
 */
public record ModAnalysis(
        String modId,
        String modVersion,
        Optional<String> sourceMcVersion,
        LoaderKind loader,
        MappingNamespace namespace,
        int classFileVersionMax,
        List<String> entrypoints,
        List<MixinConfigInfo> mixinConfigs,
        List<String> accessWideners,
        List<String> accessTransformers,
        boolean hasCoremod,
        List<String> coremodClasses,
        List<EmbeddedLibrary> embeddedLibraries,
        Map<String, Integer> packageHistogram,
        List<String> networkChannels,
        List<String> knownIncompatibilities,
        MixinInventory mixinInventory,
        List<CoremodShape> coremodShapes,
        List<Evidence> evidence
) {

    public enum LoaderKind { FABRIC, FORGE, NEOFORGE, QUILT, UNKNOWN }

    public enum MappingNamespace {
        /** Pre-flattening Forge runtime names (SRG). */
        SRG,
        /** Fabric intermediary (stable cross-version names like method_1234). */
        INTERMEDIARY,
        /** Mojang official names. */
        MOJANG,
        MCP,
        OBFUSCATED,
        UNKNOWN
    }

    public record MixinConfigInfo(String path, String pkg, List<String> mixins, boolean refmapPresent) {}

    public record EmbeddedLibrary(String name, String sha1) {}

    /**
     * One piece of evidence for a detection decision. Every guess must be backed by at least
     * one of these; spec §18 forbids silent guessing.
     */
    public record Evidence(String kind, String detail, double confidence) {}

    /**
     * Classified shape of a Forge-era coremod in a mod jar. Replaces the bare boolean
     * {@code hasCoremod} as the informative structure — each shape is a distinct refusal
     * or translation surface for M8 (mixin translation / coremods compatibility-kernel layer).
     */
    public enum CoremodShape {
        /** No coremod signal at all. */
        NONE,
        /**
         * Forge 1.7-1.12 {@code IFMLLoadingPlugin} implementation (manifest
         * {@code FMLCorePlugin} or an IFMLLoadingPlugin implementor) — ships arbitrary ASM
         * transformers ({@code IClassTransformer.transform}); not graph-translatable, refuse.
         */
        IFML_PLUGIN,
        /** LaunchWrapper/Forge {@code TweakClass} — a launch-pipeline tweaker chain. */
        LAUNCHWRAPPER_TWEAKER,
        /** Modern Forge/NeoForge ≥1.13 {@code [[coremods]]} JS coremod calling {@code initializeCoreMod}. */
        JS_COREMOD_MODERN,
        /** Historical ModLoader-era {@code .js} behavior patch (heuristic; weak signal). */
        JS_COREMOD_HISTORICAL,
        /** An access transformer only (FMLAT/accesstransformer) — widens access, not a coremod per se. */
        AT_ONLY
    }

    /** Whether a config-declared mixin class actually exists in the jar (the "config lies" test). */
    public enum MixinClassPresence { PRESENT_IN_JAR, MISSING_FROM_JAR }

    /**
     * Serializable inventory of every mixin config, every mixin class (declared and not),
     * and the honest declared-vs-actually-present counts that drive every later M8 honesty claim.
     */
    public record MixinInventory(
            List<MixinConfigInventory> configs,
            List<String> undeclaredMixinClasses,
            int declaredMixinCount,
            int presentInJarCount,
            int missingFromJarCount) implements java.io.Serializable {

        private static final long serialVersionUID = 1L;
    }

    /** One mixin config's declared-vs-present picture. */
    public record MixinConfigInventory(
            String path,
            String pkg,
            String refmapName,
            boolean refmapDeclared,
            String plugin,
            String minVersion,
            String compatibilityLevel,
            int defaultRequire,
            int declaredCount,
            int presentCount,
            int missingCount,
            List<String> missingMixins,
            List<MixinClassInventory> mixins,
            Refmap.RefmapStatus refmapStatus,
            int refmapEntryCount,
            String refmapError) implements java.io.Serializable {

        private static final long serialVersionUID = 1L;

        /** Compact helper for callers that do not track refmap details (defaults to ABSENT). */
        public MixinConfigInventory(String path, String pkg, String refmapName, boolean refmapDeclared,
                                    String plugin, String minVersion, String compatibilityLevel,
                                    int defaultRequire, int declaredCount, int presentCount,
                                    int missingCount, List<String> missingMixins,
                                    List<MixinClassInventory> mixins) {
            this(path, pkg, refmapName, refmapDeclared, plugin, minVersion, compatibilityLevel,
                    defaultRequire, declaredCount, presentCount, missingCount, missingMixins, mixins,
                    Refmap.RefmapStatus.ABSENT, 0, null);
        }
    }

    /** What the analyzer knows about one mixin class (declared or not). */
    public record MixinClassInventory(
            String className,
            MixinClassPresence presence,
            boolean mixinAnnotationPresent,
            List<String> mixinTargets,
            MappingNamespace selectorNamespace,
            int handlerCount,
            int requireGreaterThanZero,
            int overwrites,
            int accessors,
            int invokers,
            List<String> shadowMembers,
            List<String> atIds,
            List<MixinSelector> selectors) implements java.io.Serializable {

        private static final long serialVersionUID = 1L;

        /** Compact helper for callers that do not track selector IR (defaults to empty). */
        public MixinClassInventory(String className, MixinClassPresence presence,
                                   boolean mixinAnnotationPresent, List<String> mixinTargets,
                                   MappingNamespace selectorNamespace, int handlerCount,
                                   int requireGreaterThanZero, int overwrites, int accessors,
                                   int invokers, List<String> shadowMembers, List<String> atIds) {
            this(className, presence, mixinAnnotationPresent, mixinTargets, selectorNamespace,
                    handlerCount, requireGreaterThanZero, overwrites, accessors, invokers,
                    shadowMembers, atIds, List.of());
        }
    }
}