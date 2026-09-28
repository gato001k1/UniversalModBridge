package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-1: MixinInventory - the declared-vs-present picture and per-class require&gt;0 counting.
 * Mixin annotations are ASM-built directly (matched by descriptor, no SpongePowered runtime);
 * classes land in the jar exactly as javac-compiled mixin annotations do.
 */
class MixinInventoryTest {
    @TempDir
    Path tmp;

    private final BasicModAnalyzer analyzer = new BasicModAnalyzer();

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MAYBE_SERVER_TARGET = "net/minecraft/world/level/Level";

    private static final String FABRIC_MIN = """
            {
              "schemaVersion": 1,
              "id": "mixin-fixture",
              "version": "0.1.0",
              "mixins": ["a.mixins.json"]
            }
            """;

    // ------------------------------------------------------------------ ASM helpers

    private static AnnotationNode ann(String desc, Object... kv) {
        AnnotationNode a = new AnnotationNode(desc);
        a.values = new ArrayList<>();
        for (int i = 0; i < kv.length; i++) {
            a.values.add(kv[i]);
        }
        return a;
    }

    /** Standalone @At node: value is the point id (HEAD/TAIL/INVOKE/...). */
    private static AnnotationNode at(String id) {
        return ann("Lorg/spongepowered/asm/mixin/injection/At;", "value", id);
    }

    private static void withMixin(ClassNode node, AnnotationNode mixin) {
        node.visibleAnnotations = new ArrayList<>();
        node.visibleAnnotations.add(mixin);
    }

    private static ClassNode mixinClass(String internalName, AnnotationNode mixin) {
        ClassNode node = new ClassNode();
        node.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        withMixin(node, mixin);
        return node;
    }

    private static ClassNode accessorInterface(String internalName) {
        ClassNode node = new ClassNode();
        node.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        return node;
    }

    private static MethodNode methodNode(String name) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0, 0);
        return mn;
    }

    /** Adds an @Inject method. require == null means the member is absent (default applies). */
    private static void addInject(ClassNode node, String methodName, String selector,
                                  AnnotationNode atNode, Integer require) {
        MethodNode mn = methodNode(methodName);
        List<Object> kv = new ArrayList<>();
        kv.add("method");
        kv.add(List.of(selector));
        kv.add("at");
        kv.add(List.of(atNode));
        if (require != null) {
            kv.add("require");
            kv.add(require);
        }
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;", kv.toArray()));
        node.methods.add(mn);
    }

    private static void addRedirect(ClassNode node, String methodName, String selector,
                                    AnnotationNode atNode) {
        MethodNode mn = methodNode(methodName);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "method", List.of(selector), "at", atNode));
        node.methods.add(mn);
    }

    private static void addOverwrite(ClassNode node, String methodName) {
        MethodNode mn = methodNode(methodName);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/Overwrite;"));
        node.methods.add(mn);
    }

    private static void addShadowField(ClassNode node, String fieldName) {
        FieldNode fn = new FieldNode(Opcodes.ACC_PRIVATE, fieldName, "I", null, null);
        fn.visibleAnnotations = new ArrayList<>();
        fn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/Shadow;"));
        node.fields.add(fn);
    }

    private static void addAccessor(ClassNode node, String methodName, String value) {
        MethodNode mn = methodNode(methodName);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/gen/Accessor;", "value", value));
        node.methods.add(mn);
    }

    private static void addInvoker(ClassNode node, String methodName, String value) {
        MethodNode mn = methodNode(methodName);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/gen/Invoker;", "value", value));
        node.methods.add(mn);
    }

    private static byte[] toBytes(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] implInterface(String internalName, String interfaceName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[]{interfaceName});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }// ------------------------------------------------------------------ tests

    @Test
    void configLiesAndUndeclaredDetection() throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", bytes(FABRIC_MIN));
        entries.put("a.mixins.json", bytes("""
                {
                  "package": "com.example.mixin",
                  "mixins": ["ExampleMixin", "GhostMixin"],
                  "refmap": "a.refmap.json"
                }
                """));
        ClassNode example = mixinClass("com/example/mixin/ExampleMixin", ann(MIXIN_DESC,
                "value", List.of(Type.getObjectType("com/example/mixin/ExampleMixin"))));
        addInject(example, "injectExample", "method_1234_foo()V", at("HEAD"), 3);
        entries.put("com/example/mixin/ExampleMixin.class", toBytes(example));
        // RogueMixin is @Mixin-bearing but NOT declared in any config.
        ClassNode rogue = mixinClass("com/example/mixin/RogueMixin", ann(MIXIN_DESC,
                "targets", List.of(MAYBE_SERVER_TARGET)));
        entries.put("com/example/mixin/RogueMixin.class", toBytes(rogue));
        Path jar = jarOf(tmp, "lies.jar", entries);

        ModAnalysis a = analyzer.analyze(jar);
        ModAnalysis.MixinInventory inv = a.mixinInventory();

        assertEquals(1, inv.configs().size());
        ModAnalysis.MixinConfigInventory cfg = inv.configs().get(0);
        assertEquals("a.mixins.json", cfg.path());
        assertEquals("com.example.mixin", cfg.pkg());
        assertEquals("a.refmap.json", cfg.refmapName());
        assertEquals(true, cfg.refmapDeclared());
        assertEquals(2, cfg.declaredCount(), "declared: ExampleMixin + GhostMixin");
        assertEquals(1, cfg.presentCount());
        assertEquals(1, cfg.missingCount());
        assertEquals(List.of("com.example.mixin.GhostMixin"), cfg.missingMixins());
        ModAnalysis.MixinClassInventory ghost = cfg.mixins().get(1);
        assertEquals(ModAnalysis.MixinClassPresence.MISSING_FROM_JAR, ghost.presence());
        assertTrue(!ghost.mixinAnnotationPresent(), "a missing class carries no annotation facts");

        assertEquals(2, inv.declaredMixinCount());
        assertEquals(1, inv.presentInJarCount());
        assertEquals(1, inv.missingFromJarCount());
        assertEquals(List.of("com.example.mixin.RogueMixin"), inv.undeclaredMixinClasses());

        assertTrue(a.evidence().stream().anyMatch(e -> e.kind().equals("mixin")
                        && e.detail().contains("NOT in jar")
                        && e.detail().contains("GhostMixin")),
                "config lies must be surfaced as evidence");
        assertTrue(a.evidence().stream().anyMatch(e -> e.kind().equals("mixin")
                        && e.detail().contains("not listed in any config")
                        && e.detail().contains("RogueMixin")),
                "undeclared mixin classes must be surfaced as evidence");
    }

    @Test
    void requireCountsExplicitDefaultAndOptional() throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", bytes(FABRIC_MIN));
        entries.put("a.mixins.json", bytes("""
                {
                  "package": "com.example.mixin",
                  "mixins": ["HardMixin"],
                  "injectors": {"defaultRequire": 2}
                }
                """));
        ClassNode hard = mixinClass("com/example/mixin/HardMixin", ann(MIXIN_DESC,
                "targets", List.of(MAYBE_SERVER_TARGET)));
        addInject(hard, "injectHard", "method_101_hora()V", at("HEAD"), 3);
        addInject(hard, "injectOptional", "method_102_ora()V", at("HEAD"), 0);
        // no explicit require -> inherited config defaultRequire
        addInject(hard, "injectDefaulted", "method_103_ora()V", at("TAIL"), null);
        addRedirect(hard, "redirectConsts", "method_104_ora()V", at("INVOKE"));
        addOverwrite(hard, "overwriteMethod");
        entries.put("com/example/mixin/HardMixin.class", toBytes(hard));
        Path jar = jarOf(tmp, "require.jar", entries);

        ModAnalysis a = analyzer.analyze(jar);
        ModAnalysis.MixinConfigInventory cfg = a.mixinInventory().configs().get(0);
        assertEquals(2, cfg.defaultRequire());
        ModAnalysis.MixinClassInventory inc = cfg.mixins().get(0);
        assertEquals(5, inc.handlerCount(), "3 @Inject + 1 @Redirect + 1 @Overwrite");
        assertEquals(3, inc.requireGreaterThanZero(),
                "require=3, the defaulted TAIL and the @Redirect are hard; require=0 is optional");
        assertEquals(1, inc.overwrites(), "@Overwrite carried separately, never in require");
        assertEquals(List.of("HEAD", "HEAD", "TAIL", "INVOKE"), inc.atIds());
    }

    @Test
    void selectorNamespaceLightClassification() throws IOException {
        // Intermediary selector (method_1234_...)
        Map<String, byte[]> itp = new HashMap<>();
        itp.put("fabric.mod.json", bytes(FABRIC_MIN));
        itp.put("a.mixins.json", bytes("""
                {
                  "package": "com.example.mixin",
                  "mixins": ["ItpMixin"]
                }
                """));
        ClassNode itpMixin = mixinClass("com/example/mixin/ItpMixin", ann(MIXIN_DESC,
                "targets", List.of(MAYBE_SERVER_TARGET)));
        addInject(itpMixin, "injectItp", "method_1234_frobnicate()V", at("HEAD"), 0);
        itp.put("com/example/mixin/ItpMixin.class", toBytes(itpMixin));
        Path jarItp = jarOf(tmp, "itp.jar", itp);
        ModAnalysis.MixinClassInventory itpCl = analyzer.analyze(jarItp)
                .mixinInventory().configs().get(0).mixins().get(0);
        assertEquals(ModAnalysis.MappingNamespace.INTERMEDIARY, itpCl.selectorNamespace(),
                "method_ selector classifies INTERMEDIARY");

        // SRG selector (func_123456_...)
        Map<String, byte[]> srg = new HashMap<>();
        srg.put("fabric.mod.json", bytes(FABRIC_MIN));
        srg.put("a.mixins.json", bytes("""
                {
                  "package": "com.example.mixin",
                  "mixins": ["SrgMixin"]
                }
                """));
        ClassNode srgMixin = mixinClass("com/example/mixin/SrgMixin", ann(MIXIN_DESC,
                "targets", List.of(MAYBE_SERVER_TARGET)));
        addInject(srgMixin, "injectSrg", "func_123456_a()V", at("HEAD"), 0);
        srg.put("com/example/mixin/SrgMixin.class", toBytes(srgMixin));
        Path jarSrg = jarOf(tmp, "srg.jar", srg);
        ModAnalysis.MixinClassInventory srgCl = analyzer.analyze(jarSrg)
                .mixinInventory().configs().get(0).mixins().get(0);
        assertEquals(ModAnalysis.MappingNamespace.SRG, srgCl.selectorNamespace(),
                "func_ selector classifies SRG");
    }

    @Test
    void accessorInterfaceAndShadowAndInvoker() throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", bytes(FABRIC_MIN));
        entries.put("a.mixins.json", bytes("""
                {
                  "package": "com.example.mixin",
                  "mixins": ["ExampleMixin", "AccessorInterface"]
                }
                """));
        ClassNode ex = mixinClass("com/example/mixin/ExampleMixin", ann(MIXIN_DESC,
                "targets", List.of(MAYBE_SERVER_TARGET)));
        addShadowField(ex, "field_1234_thickness");
        addInvoker(ex, "callGetName", "method_5678_getName()Ljava/lang/String;");
        entries.put("com/example/mixin/ExampleMixin.class", toBytes(ex));
        ClassNode acc = accessorInterface("com/example/mixin/AccessorInterface");
        addAccessor(acc, "getValue", "field_9999_value");
        entries.put("com/example/mixin/AccessorInterface.class", toBytes(acc));
        Path jar = jarOf(tmp, "acc.jar", entries);

        ModAnalysis a = analyzer.analyze(jar);
        List<ModAnalysis.MixinClassInventory> mixins =
                a.mixinInventory().configs().get(0).mixins();
        ModAnalysis.MixinClassInventory example = mixins.get(0);
        assertEquals(List.of("field_1234_thickness"), example.shadowMembers());
        assertEquals(1, example.invokers());
        assertEquals(0, example.accessors());
        ModAnalysis.MixinClassInventory accessorIf = mixins.get(1);
        assertTrue(!accessorIf.mixinAnnotationPresent(),
                "an accessor interface has no @Mixin, only @Accessor methods");
        assertEquals(1, accessorIf.accessors());
        assertEquals(ModAnalysis.MixinClassPresence.PRESENT_IN_JAR, accessorIf.presence());
    }

    @Test
    void coremodShapesClassifyManifestAndJs() throws IOException {
        // IFMLLoadingPlugin coremod (manifest + implementor)
        Map<String, byte[]> ifml = new HashMap<>();
        ifml.put("META-INF/MANIFEST.MF", bytes(
                "Manifest-Version: 1.0\r\nFMLCorePlugin: com.example.CoreHook\r\n"));
        ifml.put("com/example/CoreHook.class", emptyClass("com/example/CoreHook"));
        ifml.put("com/example/HookImpl.class", implInterface("com/example/HookImpl",
                "cpw/mods/fml/relauncher/IFMLLoadingPlugin"));
        assertEquals(List.of(ModAnalysis.CoremodShape.IFML_PLUGIN),
                analyzer.analyze(jarOf(tmp, "ifml.jar", ifml)).coremodShapes());

        // LaunchWrapper tweaker (manifest TweakClass only)
        Map<String, byte[]> tw = new HashMap<>();
        tw.put("META-INF/MANIFEST.MF",
                bytes("Manifest-Version: 1.0\r\nTweakClass: a.b.Tweaker\r\n"));
        assertEquals(List.of(ModAnalysis.CoremodShape.LAUNCHWRAPPER_TWEAKER),
                analyzer.analyze(jarOf(tmp, "tw.jar", tw)).coremodShapes());

        // AT only — not a coremod
        Map<String, byte[]> atOnly = new HashMap<>();
        atOnly.put("META-INF/MANIFEST.MF",
                bytes("Manifest-Version: 1.0\r\nFMLAT: at.cfg\r\n"));
        atOnly.put("at.cfg", bytes("public net.minecraft.server.MinecraftServer f\n"));
        ModAnalysis at = analyzer.analyze(jarOf(tmp, "at.jar", atOnly));
        assertEquals(List.of(ModAnalysis.CoremodShape.AT_ONLY), at.coremodShapes());
        assertTrue(!at.hasCoremod(), "an AT widens access, it is not a coremod");

        // Modern JS coremod: mods.toml [[coremods]] + initializeCoreMod .js
        Map<String, byte[]> js = new HashMap<>();
        js.put("META-INF/mods.toml", bytes("\n[[coremods]]\nmodId=\"jsmod\"\n"
                + "coremodJarFileName=\"jsmod_core.js\"\n"));
        js.put("jsmod_core.js", bytes("function initializeCoreMod() { return {}; }"));
        assertEquals(List.of(ModAnalysis.CoremodShape.JS_COREMOD_MODERN),
                analyzer.analyze(jarOf(tmp, "js.jar", js)).coremodShapes());

        // Historical ModLoader-era .js (weak heuristic marker)
        Map<String, byte[]> hj = new HashMap<>();
        hj.put("mcmod.info", bytes("[{\"modid\":\"oldmod\",\"version\":\"1\",\"mcversion\":\"1.5.2\"}]"));
        hj.put("oldmod.js", bytes("function addOverride(cls, name) { return null; }"));
        assertEquals(List.of(ModAnalysis.CoremodShape.JS_COREMOD_HISTORICAL),
                analyzer.analyze(jarOf(tmp, "hj.jar", hj)).coremodShapes());

        // No signal -> no shapes, no coremod
        Map<String, byte[]> plain = new HashMap<>();
        plain.put("fabric.mod.json", bytes(FABRIC_MIN));
        ModAnalysis none = analyzer.analyze(jarOf(tmp, "plain.jar", plain));
        assertTrue(none.coremodShapes().isEmpty());
        assertTrue(!none.hasCoremod());
    }

    @Test
    void inventoryRecordsAreSerializableAndEmptyJarYieldsEmptyInventory() throws IOException {
        Path jar = jarOf(tmp, "plain.jar", Map.of(
                "fabric.mod.json", bytes(FABRIC_MIN),
                "com/example/Plain.class", emptyClass("com/example/Plain")));
        ModAnalysis.MixinInventory inv = analyzer.analyze(jar).mixinInventory();
        assertTrue(inv.configs().isEmpty());
        assertEquals(0, inv.declaredMixinCount());
        assertEquals(0, inv.presentInJarCount());
        assertEquals(0, inv.missingFromJarCount());
        assertTrue(inv.undeclaredMixinClasses().isEmpty());
    }

    @Test
    void inventorRecordTypesCarryDeclaredSerializable() {
        ModAnalysis.MixinInventory inv =
                new ModAnalysis.MixinInventory(List.of(), List.of(), 0, 0, 0);
        ModAnalysis.MixinConfigInventory cfg = new ModAnalysis.MixinConfigInventory(
                "a.mixins.json", "com.example.mixin", null, false, null, null, "", 0,
                0, 0, 0, List.of(), List.of(),
                dev.umb.core.Refmap.RefmapStatus.ABSENT, 0, null);
        ModAnalysis.MixinClassInventory cls = new ModAnalysis.MixinClassInventory(
                "com.example.mixin.X", ModAnalysis.MixinClassPresence.PRESENT_IN_JAR, true,
                List.of(), ModAnalysis.MappingNamespace.INTERMEDIARY,
                1, 1, 0, 0, 0, List.of(), List.of(), List.of());
        assertEquals(true, inv instanceof java.io.Serializable);
        assertEquals(true, cfg instanceof java.io.Serializable);
        assertEquals(true, cls instanceof java.io.Serializable);
    }
}