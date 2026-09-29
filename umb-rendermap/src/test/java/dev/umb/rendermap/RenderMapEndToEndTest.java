package dev.umb.rendermap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-pipeline smoke tests: writes a real, tiny jar + snapshot to disk and runs
 * {@code RenderMap.main} exactly the way {@code tools/windows/run-rendermap.ps1} would, for a mod whose
 * classes and package are nothing like HBM's. Covers the mandate's explicit "a mod with NO custom
 * renderers at all must produce a valid empty-ish map, not crash" requirement, and — as the
 * positive counterpart — a mod that DOES use the generic APIs, proving id attribution and renderer
 * resolution work together end to end without any HBM-named class anywhere in the loop.
 */
class RenderMapEndToEndTest implements Opcodes {

    @TempDir Path tempDir;

    @Test
    void modWithNoCustomRenderersAtAll_producesValidEmptyMapWithoutCrashing() throws Exception {
        ClassNode item = TestAsm.bareClass("any/pkg/PlainItem", "net/minecraft/item/Item");
        ClassNode block = TestAsm.bareClass("any/pkg/PlainBlock", "net/minecraft/block/Block");
        Path jarPath = tempDir.resolve("plainmod.jar");
        writeJar(jarPath, item, block);

        Path snapPath = tempDir.resolve("empty-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[],\"tileEntities\":[],\"entities\":[]}",
                StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-empty");

        assertDoesNotThrow(() ->
                RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()}));

        Path json = outDir.resolve("empty-render-map.json");
        assertTrue(Files.exists(json));
        JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(0, root.getAsJsonArray("items").size());
        assertEquals(0, root.getAsJsonArray("blocks").size());
        assertEquals(0, root.getAsJsonArray("orphanRenderers").size());
        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(0, cov.get("itemsWithCustomRenderer").getAsInt());
        assertEquals(0, cov.get("blocksMapped").getAsInt());
        assertTrue(Files.exists(outDir.resolve("REPORT.md")));
    }

    @Test
    void modWithAGenericItemRenderer_resolvesEndToEndWithNoHbmNamedClassAnywhere() throws Exception {
        ClassNode someItem = TestAsm.bareClass("any/pkg/SomeItem", "net/minecraft/item/Item");
        ClassNode renderer = TestAsm.bareClass("any/pkg/MyRenderer", null, "net/minecraftforge/client/IItemRenderer");
        ClassNode exampleMod = TestAsm.classWithMethod("any/pkg/ExampleMod", null, null,
                "init", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg/SomeItem");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/SomeItem", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");

                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitLdcInsn("mything");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerItem",
                            "(Lnet/minecraft/item/Item;Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
                    mv.visitInsn(POP);

                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitTypeInsn(NEW, "any/pkg/MyRenderer");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/MyRenderer", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/client/MinecraftForgeClient",
                            "registerItemRenderer",
                            "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
                });
        exampleMod.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "myItem", "Lnet/minecraft/item/Item;", null, null));

        Path jarPath = tempDir.resolve("examplemod.jar");
        writeJar(jarPath, someItem, renderer, exampleMod);

        Path snapPath = tempDir.resolve("example-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[{\"id\":\"examplemod:mything\","
                + "\"className\":\"any.pkg.SomeItem\",\"iconName\":\"examplemod:icon\"}],"
                + "\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-example");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("example-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.getAsJsonArray("items").size(), root.getAsJsonArray("items").toString());
        JsonObject row = root.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("examplemod:mything", row.get("id").getAsString());
        assertEquals("any.pkg.MyRenderer", row.get("rendererClass").getAsString());
        assertNotEquals("unresolved", row.get("confidence").getAsString());

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(1, cov.get("itemsWithCustomRenderer").getAsInt());
        assertEquals(1, cov.get("itemIdsAttributedToField").getAsInt());
        assertEquals(1, cov.get("itemIdsWithRendererGenericOnly").getAsInt());
    }

    /**
     * Gap 2 / A1 (mandate #2): a PLAIN ISBRH block — {@code getRenderType()} returns a custom
     * render id owned by an {@code ISimpleBlockRenderingHandler} the jar never explicitly
     * registers via a call site (found by BindingScanner's generic implementor-scan instead) —
     * gets no model/texture from its renderer's own bytecode (the handler class declares no
     * resolvable holder field at all), yet the block's own live-runtime {@code getIcon(side,meta)}
     * capture (the snapshot's {@code icons[]}, exactly the shape
     * {@code fixtures/umb-snapshot-1710}'s native IconDump produces) still yields a real, verified
     * texture. One side of the icon set is deliberately a connected-texture-mod compound key
     * (contains {@code '|'}) to prove it is honestly skipped, not guessed.
     */
    @Test
    void isbrhBlockWithNoRendererResource_stillResolvesATextureFromItsOwnGetIcon() throws Exception {
        ClassNode isbrh = TestAsm.classWithMethod("any/pkg2/PlainIsbrh", null,
                new String[]{"cpw/mods/fml/client/registry/ISimpleBlockRenderingHandler"},
                "getRenderId", "()I", ACC_PUBLIC, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "any/pkg2/PlainIsbrh", "RENDER_ID", "I");
                    mv.visitInsn(IRETURN);
                });
        isbrh.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "RENDER_ID", "I", null, null));

        ClassNode block = TestAsm.classWithMethod("any/pkg2/PlainIsbrhBlock", "net/minecraft/block/Block", null,
                "getRenderType", "()I", ACC_PUBLIC, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "any/pkg2/PlainIsbrh", "RENDER_ID", "I");
                    mv.visitInsn(IRETURN);
                });

        Path jarPath = tempDir.resolve("isbrhicon.jar");
        java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/examplemod/textures/blocks/carved.png", new byte[]{1, 2, 3});
        writeJar(jarPath, assets, isbrh, block);

        Path snapPath = tempDir.resolve("isbrhicon-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[{\"id\":\"examplemod:carved\","
                + "\"className\":\"any.pkg2.PlainIsbrhBlock\",\"renderType\":123,"
                + "\"hasTileEntity\":false,\"tileEntityClass\":null,"
                + "\"icons\":[{\"meta\":0,\"metas\":[0],\"sides\":["
                + "\"examplemod:carved\",\"examplemod:carved\",\"missingno\","
                + "\"examplemod:ctm/thing|0.0\",\"examplemod:carved\",\"examplemod:carved\"]}]}],"
                + "\"items\":[],\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-isbrhicon");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("isbrhicon-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject row = root.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("examplemod:carved", row.get("id").getAsString());
        assertEquals("any.pkg2.PlainIsbrh", row.get("isbrhClass").getAsString());
        assertEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());

        var textures = row.getAsJsonArray("textures");
        assertEquals(1, textures.size(), textures.toString()); // "missingno" and the CTM key are excluded
        JsonObject tex = textures.get(0).getAsJsonObject();
        assertEquals("examplemod:textures/blocks/carved", tex.get("path").getAsString());
        assertEquals("assets/examplemod/textures/blocks/carved.png", tex.get("assetPath").getAsString());

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(1, cov.get("blocksIsbrhIconTextureAdded").getAsInt());
        assertEquals(1, cov.get("blocksIsbrhIconTextureCtmSkipped").getAsInt());
        assertEquals(1, cov.get("blocksMapped").getAsInt());
    }

    @Test
    void unclaimedCustomRenderBlockGetsTheVerifiedIconSafetyNet() throws Exception {
        ClassNode block = TestAsm.bareClass("any/pkg4/CustomBlock", "net/minecraft/block/Block");
        Path jarPath = tempDir.resolve("custom-icon.jar");
        java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/examplemod/textures/blocks/track.png", new byte[]{1, 2, 3});
        writeJar(jarPath, assets, block);

        Path snapPath = tempDir.resolve("custom-icon-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[{\"id\":\"examplemod:track\","
                + "\"className\":\"any.pkg4.CustomBlock\",\"renderType\":101,"
                + "\"hasTileEntity\":false,\"tileEntityClass\":null,"
                + "\"icons\":[{\"meta\":0,\"metas\":[0],\"sides\":["
                + "\"examplemod:track.0\",\"examplemod:track.0\",\"examplemod:track.0\","
                + "\"examplemod:track.0\",\"examplemod:track.0\",\"examplemod:track.0\"]}]}],"
                + "\"items\":[],\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-custom-icon");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("custom-icon-render-map.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject row = root.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());
        assertEquals(1, row.getAsJsonArray("textures").size());
        assertEquals("examplemod:textures/blocks/track",
                row.getAsJsonArray("textures").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(1, root.getAsJsonObject("coverage").get("blocksCustomIconTextureAdded").getAsInt());
        assertEquals(1, root.getAsJsonObject("coverage").get("blocksMapped").getAsInt());
    }

    @Test
    void tileEntityBlockWithVanillaRenderTypeGetsTheVerifiedIconSafetyNet() throws Exception {
        ClassNode block = TestAsm.bareClass("any/pkg5/TileBlock", "net/minecraft/block/Block");
        Path jarPath = tempDir.resolve("tile-icon.jar");
        java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/examplemod/textures/blocks/machine.png", new byte[]{1, 2, 3});
        writeJar(jarPath, assets, block);

        Path snapPath = tempDir.resolve("tile-icon-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[{\"id\":\"examplemod:machine\","
                + "\"className\":\"any.pkg5.TileBlock\",\"renderType\":0,"
                + "\"hasTileEntity\":true,\"tileEntityClass\":null,"
                + "\"icons\":[{\"meta\":0,\"metas\":[0],\"sides\":["
                + "\"examplemod:machine.0\",\"examplemod:machine.0\",\"examplemod:machine.0\","
                + "\"examplemod:machine.0\",\"examplemod:machine.0\",\"examplemod:machine.0\"]}]}],"
                + "\"items\":[],\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-tile-icon");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("tile-icon-render-map.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject row = root.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());
        assertEquals("examplemod:textures/blocks/machine",
                row.getAsJsonArray("textures").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(1, root.getAsJsonObject("coverage").get("blocksCustomIconTextureAdded").getAsInt());
    }

    /**
     * Item-side mirror of {@code isbrhBlockWithNoRendererResource_stillResolvesATextureFromItsOwnGetIcon}
     * (ITEM-ICON-RESOLUTION.md): a GENERIC-tier item renderer ({@code MinecraftForgeClient.
     * registerItemRenderer}, a bare {@code IItemRenderer} implementor naming no static field at
     * all) resolves no model/texture from its own bytecode, yet the item's own live-runtime icon
     * (the snapshot's {@code iconName}, exactly the shape {@code fixtures/umb-snapshot-1710}'s
     * native capture produces via {@code getIconFromDamage(0)}) still yields a real, verified
     * texture under {@code textures/items/}.
     */
    @Test
    void genericItemWithNoRendererResource_stillResolvesATextureFromItsOwnRuntimeIcon() throws Exception {
        ClassNode someItem = TestAsm.bareClass("any/pkg3/SomeItem", "net/minecraft/item/Item");
        ClassNode renderer = TestAsm.bareClass("any/pkg3/MyRenderer", null, "net/minecraftforge/client/IItemRenderer");
        ClassNode exampleMod = TestAsm.classWithMethod("any/pkg3/ExampleMod", null, null,
                "init", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg3/SomeItem");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg3/SomeItem", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg3/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");

                    mv.visitFieldInsn(GETSTATIC, "any/pkg3/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitLdcInsn("mything");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerItem",
                            "(Lnet/minecraft/item/Item;Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
                    mv.visitInsn(POP);

                    mv.visitFieldInsn(GETSTATIC, "any/pkg3/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitTypeInsn(NEW, "any/pkg3/MyRenderer");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg3/MyRenderer", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/client/MinecraftForgeClient",
                            "registerItemRenderer",
                            "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
                });
        exampleMod.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "myItem", "Lnet/minecraft/item/Item;", null, null));

        Path jarPath = tempDir.resolve("itemicon.jar");
        java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/examplemod/textures/items/thing.png", new byte[]{1, 2, 3});
        writeJar(jarPath, assets, someItem, renderer, exampleMod);

        Path snapPath = tempDir.resolve("itemicon-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[{\"id\":\"examplemod:mything\","
                + "\"className\":\"any.pkg3.SomeItem\",\"iconName\":\"examplemod:thing\"}],"
                + "\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-itemicon");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("itemicon-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject row = root.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("examplemod:mything", row.get("id").getAsString());
        assertEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());

        var textures = row.getAsJsonArray("textures");
        assertEquals(1, textures.size(), textures.toString());
        JsonObject tex = textures.get(0).getAsJsonObject();
        assertEquals("examplemod:textures/items/thing", tex.get("path").getAsString());
        assertEquals("assets/examplemod/textures/items/thing.png", tex.get("assetPath").getAsString());
        assertTrue(tex.get("verifiedInJar").getAsBoolean());

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(1, cov.get("itemsIconTextureAdded").getAsInt());
        assertEquals(0, cov.get("itemsIconTextureCtmSkipped").getAsInt());
    }

    /**
     * Same GENERIC-tier, no-static-texture-field shape as the test above, but the item's own
     * live-runtime icon is {@code "missingno"} (the native boot's {@code getIconFromDamage(0)}
     * call returned no icon at all) — must be honestly skipped, not guessed, exactly the same
     * exclusion the block-side gap 2/A1 fix uses.
     */
    @Test
    void genericItemIconResolvingToMissingno_isSkippedHonestly() throws Exception {
        ClassNode someItem = TestAsm.bareClass("any/pkg4/SomeItem", "net/minecraft/item/Item");
        ClassNode renderer = TestAsm.bareClass("any/pkg4/MyRenderer", null, "net/minecraftforge/client/IItemRenderer");
        ClassNode exampleMod = TestAsm.classWithMethod("any/pkg4/ExampleMod", null, null,
                "init", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg4/SomeItem");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg4/SomeItem", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg4/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");

                    mv.visitFieldInsn(GETSTATIC, "any/pkg4/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitLdcInsn("mything");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerItem",
                            "(Lnet/minecraft/item/Item;Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
                    mv.visitInsn(POP);

                    mv.visitFieldInsn(GETSTATIC, "any/pkg4/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitTypeInsn(NEW, "any/pkg4/MyRenderer");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg4/MyRenderer", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/client/MinecraftForgeClient",
                            "registerItemRenderer",
                            "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
                });
        exampleMod.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "myItem", "Lnet/minecraft/item/Item;", null, null));

        Path jarPath = tempDir.resolve("itemicon-missingno.jar");
        writeJar(jarPath, someItem, renderer, exampleMod);

        Path snapPath = tempDir.resolve("itemicon-missingno-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[{\"id\":\"examplemod:mything\","
                + "\"className\":\"any.pkg4.SomeItem\",\"iconName\":\"missingno\"}],"
                + "\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-itemicon-missingno");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("itemicon-missingno-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject row = root.getAsJsonArray("items").get(0).getAsJsonObject();
        assertNotEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());
        assertEquals(0, row.getAsJsonArray("textures").size(), row.toString());

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(0, cov.get("itemsIconTextureAdded").getAsInt());
    }

    /**
     * Damage-variant fallback (mandate: "fall back across item damage/metadata variants the same
     * way the block path falls back across block metadata variants") — the base icon (damage 0,
     * {@link Snapshot.Itm#iconName}) is a connected-texture-mod compound key (contains {@code '|'},
     * genuinely not a single static texture, so it must be skipped and counted), but a damage
     * variant ({@link Snapshot.SubItm#iconName}) has a plain, resolvable icon. Mirrors the
     * block-side fix falling back across metas when meta 0 is CTM-only.
     */
    @Test
    void genericItemIconFallsBackAcrossDamageVariants_whenBaseIconIsACtmCompoundKey() throws Exception {
        ClassNode someItem = TestAsm.bareClass("any/pkg5/SomeItem", "net/minecraft/item/Item");
        ClassNode renderer = TestAsm.bareClass("any/pkg5/MyRenderer", null, "net/minecraftforge/client/IItemRenderer");
        ClassNode exampleMod = TestAsm.classWithMethod("any/pkg5/ExampleMod", null, null,
                "init", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg5/SomeItem");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg5/SomeItem", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg5/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");

                    mv.visitFieldInsn(GETSTATIC, "any/pkg5/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitLdcInsn("mything");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerItem",
                            "(Lnet/minecraft/item/Item;Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
                    mv.visitInsn(POP);

                    mv.visitFieldInsn(GETSTATIC, "any/pkg5/ExampleMod", "myItem", "Lnet/minecraft/item/Item;");
                    mv.visitTypeInsn(NEW, "any/pkg5/MyRenderer");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg5/MyRenderer", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/client/MinecraftForgeClient",
                            "registerItemRenderer",
                            "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
                });
        exampleMod.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "myItem", "Lnet/minecraft/item/Item;", null, null));

        Path jarPath = tempDir.resolve("itemicon-variant.jar");
        java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/examplemod/textures/items/variant.png", new byte[]{1, 2, 3});
        writeJar(jarPath, assets, someItem, renderer, exampleMod);

        Path snapPath = tempDir.resolve("itemicon-variant-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[{\"id\":\"examplemod:mything\","
                + "\"className\":\"any.pkg5.SomeItem\",\"iconName\":\"examplemod:ctm/thing|0.0\","
                + "\"subItems\":[{\"damage\":1,\"unlocalizedName\":\"mything.variant\","
                + "\"iconName\":\"examplemod:variant\"}]}],"
                + "\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-itemicon-variant");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("itemicon-variant-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject row = root.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("inferred-icon", row.get("confidence").getAsString(), row.toString());

        var textures = row.getAsJsonArray("textures");
        assertEquals(1, textures.size(), textures.toString());
        JsonObject tex = textures.get(0).getAsJsonObject();
        assertEquals("examplemod:textures/items/variant", tex.get("path").getAsString());
        assertEquals("assets/examplemod/textures/items/variant.png", tex.get("assetPath").getAsString());

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(1, cov.get("itemsIconTextureAdded").getAsInt());
        assertEquals(1, cov.get("itemsIconTextureCtmSkipped").getAsInt());
    }

    /**
     * No-leak rule: a model may attach to a block row ONLY through that block's own
     * registration construction ({@code holder.field = new B(config)} + config factory),
     * never through textual proximity in a shared registration method. Two blocks are
     * registered in ONE method, the first with a config object whose factory vends a
     * renderer with a model, the second with vanilla args only: the first row must carry
     * the model, the second must not, even though the config's GETSTATIC textually precedes
     * both stores. (The fixture deliberately reuses the HBM shape's names — a config class
     * with a renderer factory — so this fails on the old whole-method pending-leak scan;
     * production code never matches on those names.)
     */
    @Test
    void configModelDoesNotLeakIntoNeighborBlockInSharedRegistrationMethod() throws Exception {
        ClassNode blockBase = TestAsm.bareClass("net/minecraft/block/Block", "java/lang/Object");

        // Config class with a static instance + a no-arg factory returning an interface.
        ClassNode config = TestAsm.bareClass("any/pkg/DoorDecl", "java/lang/Object");
        config.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "CFG", "Lany/pkg/DoorDecl;", null, null));
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_8, ACC_PUBLIC, "any/pkg/DoorDecl", null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "CFG", "Lany/pkg/DoorDecl;", null, null);
            MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, "any/pkg/DoorDecl");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/DoorDecl", "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/DoorDecl", "CFG", "Lany/pkg/DoorDecl;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            mv = cw.visitMethod(ACC_PUBLIC, "getSEDNARenderer", "()Lany/pkg/RendererIface;", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, "any/pkg/RendererImpl", "INSTANCE", "Lany/pkg/RendererImpl;");
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            config = new ClassNode();
            new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(config, 0);
        }

        ClassNode rendererIface;
        {
            // A real interface needs explicit ASM (TestAsm always emits a concrete class).
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_8, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, "any/pkg/RendererIface",
                    null, "java/lang/Object", null);
            cw.visitEnd();
            rendererIface = new ClassNode();
            new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(rendererIface, 0);
        }

        // Renderer with resolvable model + texture holders, read by its own render method.
        ClassNode renderer;
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_8, ACC_PUBLIC, "any/pkg/RendererImpl", null, "java/lang/Object",
                    new String[]{"any/pkg/RendererIface"});
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "INSTANCE", "Lany/pkg/RendererImpl;", null, null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "MODEL", "Lnet/minecraft/util/ResourceLocation;", null, null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "TEX", "Lnet/minecraft/util/ResourceLocation;", null, null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, "any/pkg/RendererImpl");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/RendererImpl", "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/RendererImpl", "INSTANCE", "Lany/pkg/RendererImpl;");
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("examplemod");
            mv.visitLdcInsn("models/a.obj");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/RendererImpl", "MODEL",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("examplemod");
            mv.visitLdcInsn("textures/a.png");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/RendererImpl", "TEX",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            mv = cw.visitMethod(ACC_PUBLIC, "render", "()V", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, "any/pkg/RendererImpl", "MODEL",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitInsn(POP);
            mv.visitFieldInsn(GETSTATIC, "any/pkg/RendererImpl", "TEX",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            renderer = new ClassNode();
            new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(renderer, 0);
        }

        // BlockA takes the config in its constructor; BlockB takes nothing.
        ClassNode blockA;
        {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_8, ACC_PUBLIC, "any/pkg/BlockA", null, "net/minecraft/block/Block", null);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lany/pkg/DoorDecl;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/block/Block", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            blockA = new ClassNode();
            new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(blockA, 0);
        }
        ClassNode blockB = TestAsm.bareClass("any/pkg/BlockB", "net/minecraft/block/Block");

        // ONE shared registration method: config-fed block first, vanilla block second.
        ClassNode modBlocks = TestAsm.classWithMethod("any/pkg/ModBlocks", null, null,
                "init", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg/BlockA");
                    mv.visitInsn(DUP);
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/DoorDecl", "CFG", "Lany/pkg/DoorDecl;");
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/BlockA", "<init>", "(Lany/pkg/DoorDecl;)V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg/ModBlocks", "blockA", "Lnet/minecraft/block/Block;");
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ModBlocks", "blockA", "Lnet/minecraft/block/Block;");
                    mv.visitLdcInsn("blocka");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerBlock",
                            "(Lnet/minecraft/block/Block;Ljava/lang/String;)Lnet/minecraft/block/Block;", false);
                    mv.visitInsn(POP);
                    mv.visitTypeInsn(NEW, "any/pkg/BlockB");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/BlockB", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "any/pkg/ModBlocks", "blockB", "Lnet/minecraft/block/Block;");
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ModBlocks", "blockB", "Lnet/minecraft/block/Block;");
                    mv.visitLdcInsn("blockb");
                    mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerBlock",
                            "(Lnet/minecraft/block/Block;Ljava/lang/String;)Lnet/minecraft/block/Block;", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                });
        modBlocks.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "blockA", "Lnet/minecraft/block/Block;", null, null));
        modBlocks.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "blockB", "Lnet/minecraft/block/Block;", null, null));

        Path jarPath = tempDir.resolve("noleak.jar");
        writeJar(jarPath, blockBase, config, rendererIface, renderer, blockA, blockB, modBlocks);

        Path snapPath = tempDir.resolve("noleak-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[{\"id\":\"examplemod:blocka\","
                + "\"className\":\"any.pkg.BlockA\",\"renderType\":0,"
                + "\"hasTileEntity\":false,\"tileEntityClass\":null},"
                + "{\"id\":\"examplemod:blockb\","
                + "\"className\":\"any.pkg.BlockB\",\"renderType\":0,"
                + "\"hasTileEntity\":false,\"tileEntityClass\":null}],"
                + "\"items\":[],\"tileEntities\":[],\"entities\":[]}", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-noleak");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString()});

        JsonObject root = JsonParser.parseString(
                Files.readString(outDir.resolve("noleak-render-map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject byId = new JsonObject();
        for (var e : root.getAsJsonArray("blocks")) byId.add(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        JsonObject rowA = byId.getAsJsonObject("examplemod:blocka");
        JsonObject rowB = byId.getAsJsonObject("examplemod:blockb");
        assertNotNull(rowA, "blocka row missing");
        assertNotNull(rowB, "blockb row missing");

        boolean aHasModel = false;
        for (var e : rowA.getAsJsonArray("models"))
            if ("examplemod:models/a.obj".equals(e.getAsJsonObject().get("path").getAsString())) aHasModel = true;
        assertTrue(aHasModel, "blocka row lacks its config's model: " + rowA);
        assertTrue(rowA.has("tesrVia") && rowA.get("tesrVia").getAsString().contains("config-object"),
                "blocka row lacks the config-factory linkage note: " + rowA);

        for (var e : rowB.getAsJsonArray("models"))
            assertFalse("examplemod:models/a.obj".equals(e.getAsJsonObject().get("path").getAsString()),
                    "LEAK: blockb row carries blocka's config model: " + rowB);
        if (rowB.has("tesrVia") && !rowB.get("tesrVia").isJsonNull())
            assertFalse(rowB.get("tesrVia").getAsString().contains("config-object"),
                    "LEAK: blockb row carries a config-factory linkage note: " + rowB);
    }

    @Test
    void mapFileNameDerivesFromSnapshotStemWithCompatCopy() throws Exception {        ClassNode item = TestAsm.bareClass("any/pkg/PlainItem", "net/minecraft/item/Item");
        Path jarPath = tempDir.resolve("plainmod2.jar");
        writeJar(jarPath, item);

        Path snapPath = tempDir.resolve("probedmod-snapshot.json");
        Files.writeString(snapPath, "{\"blocks\":[],\"items\":[],\"tileEntities\":[],\"entities\":[]}",
                StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out-probedmod");

        RenderMap.main(new String[]{jarPath.toString(), snapPath.toString(), outDir.toString(),
                "--also-write", "legacy-render-map.json"});

        Path primary = outDir.resolve("probedmod-render-map.json");
        Path compat = outDir.resolve("legacy-render-map.json");
        assertTrue(Files.exists(primary), "primary <namespace>-render-map.json missing");
        assertTrue(Files.exists(compat), "compat copy missing");
        assertArrayEquals(Files.readAllBytes(primary), Files.readAllBytes(compat));
        assertTrue(Files.exists(outDir.resolve("REPORT.md")));
    }

    private static void writeJar(Path path, ClassNode... classes) throws IOException {
        writeJar(path, null, classes);
    }

    private static void writeJar(Path path, java.util.Map<String, byte[]> assets, ClassNode... classes)
            throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            for (ClassNode cn : classes) {
                ClassWriter cw = new ClassWriter(0);
                cn.accept(cw);
                jos.putNextEntry(new JarEntry(cn.name + ".class"));
                jos.write(cw.toByteArray());
                jos.closeEntry();
            }
            if (assets != null) {
                for (var e : assets.entrySet()) {
                    jos.putNextEntry(new JarEntry(e.getKey()));
                    jos.write(e.getValue());
                    jos.closeEntry();
                }
            }
        }
    }
}
