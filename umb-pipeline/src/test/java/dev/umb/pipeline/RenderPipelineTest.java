package dev.umb.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.core.BasicModAnalyzer;
import dev.umb.core.ModAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10: RenderPipelinePass — model/blockstate JSON remap (namespace translation via mapping graph),
 * texture namespace translation, host atlas stitching hook. Synthetic fixtures CC0, ASM 9.9, D4.
 */
class RenderPipelineTest {

    @TempDir Path tmp;

    private static byte[] dummyClass(String internal) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        cn.methods.add(m);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
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

    private static ModAnalysis analyze(Path jar) throws IOException {
        return new BasicModAnalyzer().analyze(jar);
    }

    private static String readEntry(Path jar, String entry) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            java.util.zip.ZipEntry je = jf.getEntry(entry);
            assertNotNull(je, "entry missing: " + entry + " in " + jar);
            return new String(jf.getInputStream(je).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasEntry(Path jar, String entry) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry(entry) != null;
        }
    }

    // ------------------------------------------------------------------ helpers to build fixtures

    private Map<String, byte[]> baseEntries(String modId) {
        Map<String, byte[]> m = new HashMap<>();
        m.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1\"}").getBytes(StandardCharsets.UTF_8));
        m.put("com/example/Dummy.class", dummyClass("com/example/Dummy"));
        return m;
    }

    @Test
    void blockstateModelRemapAndTextureTranslateAndAtlasHook() throws Exception {
        String modId = "render-mod";
        Map<String, byte[]> entries = baseEntries(modId);
        // Blockstate referencing a model
        entries.put("assets/render-mod/blockstates/cool_block.json",
                ("{\"variants\":{\"\":{\"model\":\"render-mod:block/cool_block\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        // Model with parent + textures using legacy blocks/ segment and items/ analog
        entries.put("assets/render-mod/models/block/cool_block.json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"render-mod:blocks/cool\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        // Backing png for the texture (so not unresolvable)
        entries.put("assets/render-mod/textures/blocks/cool.png", new byte[]{0x01, 0x02});
        entries.put("assets/render-mod/textures/block/other.png", new byte[]{0x03});
        entries.put("assets/render-mod/models/item/cool_item.json",
                ("{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"render-mod:items/cool_item\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/render-mod/textures/items/cool_item.png", new byte[]{0x04});

        Path in = jarOf(tmp, "in-render.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-render.jar");
        RenderPipelinePass pass = new RenderPipelinePass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(Files.exists(out));

        // Blockstate model still render-mod:block/cool_block (mod namespace kept, no invent)
        String bs = readEntry(out, "assets/render-mod/blockstates/cool_block.json");
        assertTrue(bs.contains("render-mod:block/cool_block"), "blockstate model ref must survive: " + bs);

        // Model textures: blocks/ -> block/, items/ -> item/
        String blockModel = readEntry(out, "assets/render-mod/models/block/cool_block.json");
        JsonObject bm = JsonParser.parseString(blockModel).getAsJsonObject();
        String texAll = bm.getAsJsonObject("textures").get("all").getAsString();
        assertEquals("render-mod:block/cool", texAll, "texture blocks/ must become block/: " + blockModel);

        String itemModel = readEntry(out, "assets/render-mod/models/item/cool_item.json");
        JsonObject im = JsonParser.parseString(itemModel).getAsJsonObject();
        String layer0 = im.getAsJsonObject("textures").get("layer0").getAsString();
        assertEquals("render-mod:item/cool_item", layer0, "texture items/ -> item/: " + itemModel);

        // Atlas hook emitted for blocks atlas (26.2 merged)
        String atlasPath = "META-INF/umb/atlas/" + modId + ".json";
        assertTrue(hasEntry(out, atlasPath), "atlas hook must be emitted: " + atlasPath);
        String atlasText = readEntry(out, atlasPath);
        JsonObject atlas = JsonParser.parseString(atlasText).getAsJsonObject();
        assertEquals("minecraft:blocks", atlas.get("parent_atlas").getAsString());
        assertTrue(atlas.getAsJsonArray("textures").size() >= 2, "atlas must list textures: " + atlasText);

        // Texture entry paths translated: blocks/ -> block/, items/ -> item/
        assertTrue(hasEntry(out, "assets/render-mod/textures/block/cool.png"), "translated texture entry must exist");
        assertTrue(hasEntry(out, "assets/render-mod/textures/item/cool_item.png"), "translated item texture entry must exist");

        // §24: original jar unchanged (still has legacy paths)
        assertTrue(hasEntry(in, "assets/render-mod/textures/blocks/cool.png"), "input must keep legacy path (§24)");
        assertTrue(hasEntry(in, "assets/render-mod/textures/items/cool_item.png"), "input must keep legacy path (§24)");
    }

    @Test
    void unresolvableModelAndTextureAreD4EvidenceAndNotInvented() throws Exception {
        String modId = "d4-mod";
        Map<String, byte[]> entries = baseEntries(modId);
        entries.put("assets/d4-mod/blockstates/ghost.json",
                ("{\"variants\":{\"\":{\"model\":\"d4-mod:block/missing_block\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/d4-mod/models/block/ghost.json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"d4-mod:block/missing_tex\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        // No backing model or png — intentionally unresolvable

        Path in = jarOf(tmp, "in-d4.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-d4.jar");
        PassReport r = new RenderPipelinePass().run(a, in, out);
        // Must be WARN (D4 evidence) not FAIL — never invent, never refuse render
        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("RENDER_UNRESOLVABLE") || d.message().contains("unresolvable")),
                "must carry unresolvable diagnostic: " + r.diagnostics());
        // Original ref kept verbatim
        String ghost = readEntry(out, "assets/d4-mod/models/block/ghost.json");
        assertTrue(ghost.contains("d4-mod:block/missing_tex"), "unresolvable texture kept verbatim per D4: " + ghost);
        assertTrue(hasEntry(out, "assets/d4-mod/blockstates/ghost.json"));
    }

    @Test
    void malformedJsonToleratedPer131() throws Exception {
        String modId = "badjson-mod";
        Map<String, byte[]> entries = baseEntries(modId);
        entries.put("assets/badjson-mod/blockstates/broken.json",
                ("{ this is not json }").getBytes(StandardCharsets.UTF_8));
        entries.put("assets/badjson-mod/models/block/ok.json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"badjson-mod:block/ok\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/badjson-mod/textures/block/ok.png", new byte[]{0x01});

        Path in = jarOf(tmp, "in-badjson.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-badjson.jar");
        PassReport r = new RenderPipelinePass().run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        // Broken file kept verbatim
        String broken = readEntry(out, "assets/badjson-mod/blockstates/broken.json");
        assertTrue(broken.contains("this is not json"), "malformed json kept verbatim: " + broken);
        // Other model still processed
        assertTrue(hasEntry(out, "assets/badjson-mod/models/block/ok.json"));
    }

    @Test
    void noAssetsSkipped() throws Exception {
        Map<String, byte[]> entries = baseEntries("plain-mod");
        Path in = jarOf(tmp, "in-plain.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-plain.jar");
        PassReport r = new RenderPipelinePass().run(a, in, out);
        assertEquals(PassReport.Status.SKIPPED, r.status(), r.notes().toString());
        assertTrue(Files.exists(out));
        assertTrue(hasEntry(out, "com/example/Dummy.class"));
    }

    @Test
    void originalJarNeverMutated24() throws Exception {
        String modId = "immut-mod";
        Map<String, byte[]> entries = baseEntries(modId);
        entries.put("assets/immut-mod/models/block/foo.json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"immut-mod:blocks/foo\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/immut-mod/textures/blocks/foo.png", new byte[]{0x05});
        Path in = jarOf(tmp, "in-immut.jar", entries);
        byte[] before = Files.readAllBytes(in);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-immut.jar");
        new RenderPipelinePass().run(a, in, out);
        byte[] after = Files.readAllBytes(in);
        assertArrayEquals(before, after, "input jar must not be mutated (§24)");
        // Output is different (texture rewritten)
        String foo = readEntry(out, "assets/immut-mod/models/block/foo.json");
        assertTrue(foo.contains("immut-mod:block/foo"), "output must be remapped: " + foo);
    }

    @Test
    void determinismSameInputSameOutput() throws Exception {
        String modId = "det-mod";
        Map<String, byte[]> entries = baseEntries(modId);
        entries.put("assets/det-mod/models/block/a.json",
                ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"det-mod:blocks/a\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/det-mod/textures/blocks/a.png", new byte[]{0x06});
        Path in = jarOf(tmp, "in-det.jar", entries);
        ModAnalysis a = analyze(in);
        Path out1 = tmp.resolve("out-det1.jar");
        Path out2 = tmp.resolve("out-det2.jar");
        new RenderPipelinePass().run(a, in, out1);
        new RenderPipelinePass().run(a, in, out2);
        // Compare relevant entries
        String j1 = readEntry(out1, "assets/det-mod/models/block/a.json");
        String j2 = readEntry(out2, "assets/det-mod/models/block/a.json");
        assertEquals(j1, j2, "deterministic output");
        String atlas1 = hasEntry(out1, "META-INF/umb/atlas/det-mod.json") ? readEntry(out1, "META-INF/umb/atlas/det-mod.json") : "";
        String atlas2 = hasEntry(out2, "META-INF/umb/atlas/det-mod.json") ? readEntry(out2, "META-INF/umb/atlas/det-mod.json") : "";
        assertEquals(atlas1, atlas2, "atlas hook deterministic");
    }
}
