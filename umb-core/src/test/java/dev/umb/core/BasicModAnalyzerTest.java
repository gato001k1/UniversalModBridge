package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §17–§18: metadata probing, evidence discipline, structural signals.
 * Fixtures are synthetic jars built in-memory — no third-party code involved.
 */
class BasicModAnalyzerTest {

    @TempDir
    Path tmp;

    private final BasicModAnalyzer analyzer = new BasicModAnalyzer();

    // ------------------------------------------------------------------ fixtures

    /** Builds a jar from {@code name -> bytes} entries. */
    private static Path jarOf(Path dir, String name, java.util.Map<String, byte[]> entries)
            throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final String FABRIC_JSON = """
            {
              "schemaVersion": 1,
              "id": "test-fabric-mod",
              "version": "1.2.3",
              "entrypoints": {"main": ["com.example.TestMod"]},
              "depends": {"minecraft": "~1.20.1"},
              "mixins": ["testmod.mixins.json"]
            }
            """;

    private static final String MIXIN_CFG = """
            {
              "package": "com.example.mixin",
              "mixins": ["ExampleMixin"],
              "refmap": "testmod.refmap.json"
            }
            """;

    private static final String MODS_TOML = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="testforge"
            version="4.5.6"
            displayName="Test Forge Mod"

            [[dependencies.testforge]]
            modId="minecraft"
            type="required"
            versionRange="[1.16.5,)"
            ordering="NONE"
            side="BOTH"
            """;

    private static final String MCMOD_INFO = """
            [{
              "modid": "legacymod",
              "name": "Legacy Mod",
              "version": "0.9",
              "mcversion": "1.7.10"
            }]
            """;

    /**
     * A compiled class carrying namespace markers in string constants, mimicking what a
     * real mod's reflection lookups leave behind. Generated with ASM directly so the test
     * needs no prebuilt class files on disk.
     */
    private static byte[] classWithStrings(String internalName, String... strings) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "probe", "()V", null, null);
        for (String s : strings) {
            mv.visitLdcInsn(s);
            mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Same shape as {@link #classWithStrings} but with an explicit classfile version. */
    private static byte[] classWithVersion(String internalName, int classFileVersion) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(classFileVersion, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName,
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fabricMetadataYieldsFullDetection() throws IOException {
        Path j = jarOf(tmp, "fabric-mod.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "com/example/TestMod.class", classWithStrings("com/example/TestMod"),
                "testmod.mixins.json", bytes(MIXIN_CFG)));
        ModAnalysis a = analyzer.analyze(j);

        assertEquals(ModAnalysis.LoaderKind.FABRIC, a.loader());
        assertEquals("test-fabric-mod", a.modId());
        assertEquals("1.2.3", a.modVersion());
        assertEquals(java.util.Optional.of("1.20.1"), a.sourceMcVersion());
        assertEquals(1, a.entrypoints().size());
        assertTrue(a.entrypoints().get(0).startsWith("main:"));
        assertEquals(1, a.mixinConfigs().size(), "mixin config parsed");
        assertTrue(a.mixinConfigs().get(0).refmapPresent());
        assertEquals("com.example.mixin.ExampleMixin", a.mixinConfigs().get(0).mixins().get(0));
        assertFalse(a.hasCoremod());
        assertFalse(a.evidence().isEmpty(), "every decision must carry evidence");
    }

    @Test
    void forgeModsTomlDetectedWithRange() throws IOException {
        Path j = jarOf(tmp, "forge-mod.jar", java.util.Map.of(
                "META-INF/mods.toml", bytes(MODS_TOML),
                "com/example/ForgeThing.class", classWithStrings(
                        "com/example/ForgeThing",
                        "func_71410_x", "field_71474_e")));
        ModAnalysis a = analyzer.analyze(j);

        assertEquals(ModAnalysis.LoaderKind.FORGE, a.loader());
        assertEquals("testforge", a.modId());
        assertEquals("4.5.6", a.modVersion());
        assertEquals(java.util.Optional.of("1.16.5"), a.sourceMcVersion(),
                "highest concrete version in the range wins");
        assertEquals(ModAnalysis.MappingNamespace.SRG, a.namespace(),
                "func_/field_ string constants mark SRG");
    }

    @Test
    void legacyMcModInfoDetected() throws IOException {
        Path j = jarOf(tmp, "legacy.jar", java.util.Map.of(
                "mcmod.info", bytes(MCMOD_INFO),
                "com/legacy/Old.class", classWithStrings("com/legacy/Old",
                        "func_70005_c_", "field_70170_p", "func_71410_x")));
        ModAnalysis a = analyzer.analyze(j);

        assertEquals(ModAnalysis.LoaderKind.FORGE, a.loader());
        assertEquals("legacymod", a.modId());
        assertEquals(java.util.Optional.of("1.7.10"), a.sourceMcVersion(),
                "explicit mcversion beats inference");
        assertEquals(ModAnalysis.MappingNamespace.SRG, a.namespace());
    }

    @Test
    void intermediaryStringsMarkFabricNamespace() throws IOException {
        Path j = jarOf(tmp, "itp.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "com/example/M.class", classWithStrings("com/example/M",
                        "method_1234_bar", "method_9999_baz", "field_1234_buz",
                        "method_5555_qux")));
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.MappingNamespace.INTERMEDIARY, a.namespace());
    }

    @Test
    void namedRefsWithoutObfMarkersSuggestMojang() throws IOException {
        StringBuilder manyNames = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            manyNames.append("net/minecraft/world/level/Level").append(i).append('\n');
        }
        Path j = jarOf(tmp, "named.jar", java.util.Map.of(
                "fabric.mod.json", bytes("{\"schemaVersion\":1,\"id\":\"n\",\"version\":\"1\"}"),
                "com/example/N.class", classWithStrings("com/example/N",
                        manyNames.toString().split("\n"))));
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.MappingNamespace.MOJANG, a.namespace());
    }

    @Test
    void ornitheUnmappedIdsMarkIntermediaryNamespace() throws IOException {
        // Ornithe legacy-intermediary remap (Calamus gen2): classes at
        // net/minecraft/unmapped/C_<id>, members named f_<id>/m_<id>. These markers are
        // checked before the "named net.minecraft refs" fallback, which used to read the
        // unmapped/C_ package prefix as mojang names and report MOJANG for such jars.
        Path j = jarOf(tmp, "ornithe.jar", java.util.Map.of(
                "net/minecraft/unmapped/C_12345678.class", classWithStrings(
                        "net/minecraft/unmapped/C_12345678", "m_12345678_", "f_55433676"),
                "net/minecraft/unmapped/C_87654321.class", classWithStrings(
                        "net/minecraft/unmapped/C_87654321", "f_11112222"),
                "net/minecraft/unmapped/C_11223344.class", classWithStrings(
                        "net/minecraft/unmapped/C_11223344")));
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.MappingNamespace.INTERMEDIARY, a.namespace(),
                "unmapped/C_ classes and f_/m_ members must outrank the named-refs fallback");
        assertTrue(a.evidence().stream().anyMatch(e -> e.kind().equals("namespace")
                        && e.detail().startsWith("unmapped/C_ intermediary ids")),
                "evidence must name the mediating marker");
    }

    @Test
    void classWithoutNamespaceSignalsStaysUnknown() throws IOException {
        // Regression: the added Ornithe patterns must not make silence look positive —
        // a real class carrying no markers still infers UNKNOWN, never a guessed namespace.
        Path j = jarOf(tmp, "silent.jar", java.util.Map.of(
                "com/example/Plain.class", classWithStrings("com/example/Plain")));
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.MappingNamespace.UNKNOWN, a.namespace());
    }

    @Test
    void classFileVersionComesFromLowHalfOfAsmPacking() throws IOException {
        // ASM packs ClassNode.version as minor<<16 | major (verified on ASM 9.9 with a
        // major-65/minor-0 classfile: raw int 65). Reading the high half used to yield
        // the minor version — 0 for every real jar — and silently zeroed maxMajor.
        Path j = jarOf(tmp, "ver.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "com/example/V.class", classWithVersion("com/example/V", 52)));
        ModAnalysis a = analyzer.analyze(j);

        assertEquals(52, a.classFileVersionMax(),
                "major must be read from the low 16 bits of ASM's packed version");
    }

    @Test
    void classfileBeyondJava25IsFlaggedAsRisk() throws IOException {
        Path j = jarOf(tmp, "futurever.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "com/example/Future.class", classWithVersion("com/example/Future", 70)));
        ModAnalysis a = analyzer.analyze(j);

        assertTrue(a.knownIncompatibilities().stream()
                        .anyMatch(s -> s.contains("class file major 70")),
                "classfile beyond the Java 25 runtime ceiling must be surfaced as risk");
    }

    @Test
    void nativeAndEmbeddedLibrariesFlagged() throws IOException {
        Path j = jarOf(tmp, "native.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "libs/gson-2.8.jar", bytes("fake embedded jar"),
                "natives/fastmath.dll", bytes("MZ fake dll"),
                "com/example/T.class", classWithStrings("com/example/T")));
        ModAnalysis a = analyzer.analyze(j);

        assertEquals(1, a.embeddedLibraries().size());
        assertEquals("libs/gson-2.8.jar", a.embeddedLibraries().get(0).name());
        boolean flaggedNative = a.knownIncompatibilities().stream()
                .anyMatch(s -> s.contains("NATIVE_DEPENDENCY"));
        boolean flaggedCollision = a.knownIncompatibilities().stream()
                .anyMatch(s -> s.contains("gson-2.8"));
        assertTrue(flaggedNative, "native lib must be surfaced");
        assertTrue(flaggedCollision, "host-colliding embedded lib must be surfaced");
    }

    @Test
    void coremodViaManifestAndClassScan() throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes(
                "Manifest-Version: 1.0\r\nFMLCorePlugin: com.example.CoreHook\r\nFMLAT: at.cfg\r\n"));
        entries.put("com/example/CoreHook.class",
                classWithStrings("com/example/CoreHook"));
        // IFMLLoadingPlugin implementor found via interface list
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "com/example/HookImpl", null,
                "java/lang/Object", new String[]{"cpw/mods/fml/relauncher/IFMLLoadingPlugin"});
        cw.visitEnd();
        entries.put("com/example/HookImpl.class", cw.toByteArray());
        entries.put("at.cfg", bytes("public net.minecraft.server.MinecraftServer f\n"));

        Path j = jarOf(tmp, "coremod.jar", entries);
        ModAnalysis a = analyzer.analyze(j);

        assertTrue(a.hasCoremod());
        assertTrue(a.accessTransformers().contains("at.cfg"));
        assertTrue(a.evidence().stream()
                .anyMatch(e -> e.kind().equals("coremod") && e.detail().contains("IFMLLoadingPlugin")));
    }

    @Test
    void bareFmlAtNameResolvesAgainstMetaInf() throws IOException {
        // Real Forge mods declare the bare name (FMLAT: HBM_at.cfg) with the entry
        // at META-INF/HBM_at.cfg (Forge ModAccessTransformer.addJar joins
        // "META-INF/"+name). The analyzer must record the RESOLVED path so
        // MixinApplyPass.lookup finds it; the raw value stays in evidence.
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes(
                "Manifest-Version: 1.0\r\nFMLAT: HBM_at.cfg\r\n"));
        entries.put("META-INF/HBM_at.cfg",
                bytes("public net.minecraft.server.MinecraftServer f\n"));
        entries.put("com/example/P.class", classWithStrings("com/example/P"));
        Path j = jarOf(tmp, "fmlat-meta.jar", entries);
        ModAnalysis a = analyzer.analyze(j);

        assertTrue(a.accessTransformers().contains("META-INF/HBM_at.cfg"),
                "bare FMLAT name must resolve to its META-INF entry: "
                        + a.accessTransformers());
        assertTrue(a.evidence().stream().anyMatch(e -> e.kind().equals("access")
                        && e.detail().contains("FMLAT=HBM_at.cfg")),
                "evidence must keep the raw manifest value for provenance");
    }

    @Test
    void verbatimFmlAtEntryNeedsNoResolution() throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes(
                "Manifest-Version: 1.0\r\nFMLAT: META-INF/accesstransformer.cfg\r\n"));
        entries.put("META-INF/accesstransformer.cfg",
                bytes("public net.minecraft.server.MinecraftServer f\n"));
        Path j = jarOf(tmp, "fmlat-verbatim.jar", entries);
        ModAnalysis a = analyzer.analyze(j);
        assertTrue(a.accessTransformers().contains("META-INF/accesstransformer.cfg"),
                "verbatim jar-relative path must pass through: " + a.accessTransformers());
    }

    @Test
    void unresolvableFmlAtPassesThroughVerbatim() throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes(
                "Manifest-Version: 1.0\r\nFMLAT: missing_at.cfg\r\n"));
        Path j = jarOf(tmp, "fmlat-missing.jar", entries);
        ModAnalysis a = analyzer.analyze(j);
        assertTrue(a.accessTransformers().contains("missing_at.cfg"),
                "unresolvable name keeps verbatim path so downstream reports 'not found in jar': "
                        + a.accessTransformers());
    }

    @Test
    void corruptClassDoesNotCrashAnalysis() throws IOException {
        Path j = jarOf(tmp, "corrupt.jar", java.util.Map.of(
                "fabric.mod.json", bytes(FABRIC_JSON),
                "com/bad/Broken.class", bytes("this is not a valid class file at all")));
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.LoaderKind.FABRIC, a.loader(),
                "metadata detection unaffected by malformed classes");
    }

    @Test
    void emptyJarYieldsUnknownEverything() throws IOException {
        Path j = jarOf(tmp, "empty.jar", java.util.Map.of());
        ModAnalysis a = analyzer.analyze(j);
        assertEquals(ModAnalysis.LoaderKind.UNKNOWN, a.loader());
        assertEquals(ModAnalysis.MappingNamespace.UNKNOWN, a.namespace());
        assertTrue(a.evidence().isEmpty(), "no decisions => no fabricated evidence");
    }

    @Test
    void versionOrderingModelIsExplicit() {
        assertTrue(BasicModAnalyzer.compareVersions("26.2", "1.21.11") > 0,
                "modern year-style outranks legacy 1.x");
        assertTrue(BasicModAnalyzer.compareVersions("1.21.11", "1.20.1") > 0);
        assertTrue(BasicModAnalyzer.compareVersions("1.7.10", "1.7.2") > 0);
        assertEquals(0, BasicModAnalyzer.compareVersions("1.12.2", "1.12.2"));
        assertTrue(BasicModAnalyzer.compareVersions("26.1", "26.2") < 0);
    }
}
