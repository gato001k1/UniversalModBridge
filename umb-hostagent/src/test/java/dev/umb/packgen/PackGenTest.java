package dev.umb.packgen;

import dev.umb.hostagent.content.BlockShapeProfile;
import dev.umb.hostagent.content.GuiProfile;
import dev.umb.hostagent.content.LangTable;
import dev.umb.hostagent.content.LegacySnapshot;
import dev.umb.hostagent.content.VariantPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackGenTest {

    /** two blocks (one uniform, one multi-sided) + two items, one of them an ItemBlock. */
    private static final String JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.ore_uranium","unlocalizedName":"tile.ore_uranium","displayName":"tile.ore_uranium.name",
             "hardness":3.0,"resistance":5.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "icons":[{"meta":0,"sides":["hbm:ore_uranium","hbm:ore_uranium","hbm:ore_uranium","hbm:ore_uranium","hbm:ore_uranium","hbm:ore_uranium"]}]},
            {"id":"hbm:tile.#undef","unlocalizedName":"tile.#undef","displayName":"tile.#undef.name",
             "hardness":"Infinity","resistance":"Infinity",
             "icons":[{"meta":0,"sides":["hbm:top","hbm:top","hbm:side","hbm:side","hbm:side","hbm:side"]}]}
          ],
          "items": [
            {"id":"hbm:item.ingot_uranium","unlocalizedName":"item.ingot_uranium","displayName":"Uranium Ingot",
             "maxStackSize":64,"creativeTab":"tabParts","iconName":"hbm:ingot_uranium"},
            {"id":"hbm:tile.ore_uranium","unlocalizedName":"tile.ore_uranium","displayName":"tile.ore_uranium.name",
             "isBlockItem":"hbm:tile.ore_uranium","iconName":"hbm:ore_uranium"}
          ]
        }
        """;

    private Path buildAssets(Path root) throws Exception {
        // a 1x1 png is enough: PackGen only copies bytes
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        for (String n : new String[]{"ore_uranium", "top", "side"}) {
            Path p = root.resolve("assets/hbm/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        Path ip = root.resolve("assets/hbm/textures/items/ingot_uranium.png");
        Files.createDirectories(ip.getParent());
        Files.write(ip, png);
        Path lang = root.resolve("assets/hbm/lang/en_US.lang");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, "tile.ore_uranium.name=Uranium Ore\ntile.#undef.name=Undefined\n",
                StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void generatesTheFullPackForASyntheticSnapshot(@TempDir Path tmp) throws Exception {
        Path assets = buildAssets(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        LegacySnapshot snap = LegacySnapshot.parseString(JSON, "hbm");
        LangTable lang = LangTable.load(assets.resolve("assets/hbm/lang/en_US.lang"));
        PackGen gen = new PackGen("hbm", assets, out, lang);
        gen.generate(snap);

        // pack.mcmeta uses the 26.2 min_format/max_format shape
        String mcmeta = Files.readString(out.resolve("pack.mcmeta"));
        assertTrue(mcmeta.contains("\"min_format\": 88"), mcmeta);
        assertTrue(mcmeta.contains("\"max_format\": 88"), mcmeta);
        assertFalse(mcmeta.contains("pack_format"), "must not emit the pre-1.21 pack_format key");

        // uniform block -> cube_all
        String m1 = Files.readString(out.resolve("assets/hbm/models/block/tile.ore_uranium.json"));
        assertTrue(m1.contains("minecraft:block/cube_all"), m1);
        assertTrue(m1.contains("\"all\": \"hbm:block/ore_uranium\""), m1);

        // junk id sanitised, multi-sided block -> cube with all six faces + particle
        String m2 = Files.readString(out.resolve("assets/hbm/models/block/tile._undef.json"));
        assertTrue(m2.contains("minecraft:block/cube\""), m2);
        assertTrue(m2.contains("\"down\": \"hbm:block/top\""), m2);
        assertTrue(m2.contains("\"north\": \"hbm:block/side\""), m2);
        assertTrue(m2.contains("\"particle\":"), m2);

        // blockstate
        String bs = Files.readString(out.resolve("assets/hbm/blockstates/tile.ore_uranium.json"));
        assertTrue(bs.contains("\"model\": \"hbm:block/tile.ore_uranium\""), bs);

        // 1.21.4+ client item definitions exist for both the block item and the plain item
        String def = Files.readString(out.resolve("assets/hbm/items/tile.ore_uranium.json"));
        assertTrue(def.contains("\"type\": \"minecraft:model\""), def);
        assertTrue(def.contains("hbm:item/tile.ore_uranium"), def);
        String blockItemModel = Files.readString(out.resolve("assets/hbm/models/item/tile.ore_uranium.json"));
        assertTrue(blockItemModel.contains("\"parent\": \"hbm:block/tile.ore_uranium\""), blockItemModel);

        String itemModel = Files.readString(out.resolve("assets/hbm/models/item/item.ingot_uranium.json"));
        assertTrue(itemModel.contains("minecraft:item/generated"), itemModel);
        assertTrue(itemModel.contains("\"layer0\": \"hbm:item/ingot_uranium\""), itemModel);

        // textures copied into the modern singular dirs
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/block/ore_uranium.png")));
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/block/top.png")));
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/item/ingot_uranium.png")));

        // lang prefers en_US.lang when the snapshot displayName is still a raw key
        String langJson = Files.readString(out.resolve("assets/hbm/lang/en_us.json"));
        assertTrue(langJson.contains("\"block.hbm.tile.ore_uranium\": \"Uranium Ore\""), langJson);
        assertTrue(langJson.contains("\"item.hbm.item.ingot_uranium\": \"Uranium Ingot\""), langJson);
        assertTrue(langJson.contains("\"block.hbm.tile._undef\": \"Undefined\""), langJson);

        assertEquals(2, gen.blockstates);
        assertEquals(2, gen.blockModels);
        assertEquals(3, gen.itemDefs);   // 2 block items + 1 plain item
        assertEquals(0, gen.texturesMissing);
        assertEquals(4, gen.texturesCopied);
    }

    @Test
    void missingTexturesAreReportedNotFatal(@TempDir Path tmp) throws Exception {
        Path assets = tmp.resolve("empty");
        Files.createDirectories(assets);
        Path out = tmp.resolve("out2");
        LegacySnapshot snap = LegacySnapshot.parseString(JSON, "hbm");
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty());
        gen.generate(snap);
        assertTrue(gen.texturesMissing > 0);
        assertTrue(gen.blockModels > 0, "models are still written so the pack stays coherent");
        Path report = tmp.resolve("report.txt");
        gen.writeReport(report, snap);
        String r = Files.readString(report);
        assertTrue(r.contains("missing source"), r);
    }

    /**
     * A snapshot with real 1.7.10 metadata variants: one block whose subBlocks carry their own
     * per-meta icon rows, and one item whose sub-items carry their own icons.
     */
    private static final String VARIANT_JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.block_cap","unlocalizedName":"tile.block_cap","displayName":"tile.block_cap",
             "hardness":3.0,"resistance":5.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "icons":[
               {"meta":0,"sides":["hbm:cap_nuka_top","hbm:cap_nuka_top","hbm:cap_nuka","hbm:cap_nuka","hbm:cap_nuka","hbm:cap_nuka"]},
               {"meta":1,"sides":["hbm:cap_q","hbm:cap_q","hbm:cap_q","hbm:cap_q","hbm:cap_q","hbm:cap_q"]}],
             "subBlocks":[
               {"meta":0,"unlocalizedName":"tile.block_cap_nuka","displayName":"Block of Nuka Cola Bottle Caps"},
               {"meta":1,"unlocalizedName":"tile.block_cap_quantum","displayName":"Block of Quantum Bottle Caps"}]}
          ],
          "items": [
            {"id":"hbm:item.drillbit","unlocalizedName":"item.drillbit","displayName":"item.drillbit.name",
             "maxStackSize":1,"hasSubtypes":true,"creativeTab":"tabControl","iconName":"hbm:drillbit_steel",
             "subItems":[
               {"damage":0,"unlocalizedName":"item.drillbit_steel","displayName":"Steel Drillbit","iconName":"hbm:drillbit_steel"},
               {"damage":1,"unlocalizedName":"item.drillbit_hss","displayName":"High-Speed Steel Drillbit","iconName":"hbm:drillbit_hss"}]},
            {"id":"hbm:tile.block_cap","unlocalizedName":"tile.block_cap","displayName":"tile.block_cap",
             "isBlockItem":"hbm:tile.block_cap","iconName":"hbm:cap_nuka_top",
             "subItems":[
               {"damage":0,"unlocalizedName":"tile.block_cap_nuka","displayName":"Block of Nuka Cola Bottle Caps"},
               {"damage":1,"unlocalizedName":"tile.block_cap_quantum","displayName":"Block of Quantum Bottle Caps"}]}
          ]
        }
        """;

    @Test
    void everyMetadataVariantGetsItsOwnModelsAndLangRow(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("src2");
        for (String n : new String[]{"cap_nuka_top", "cap_nuka", "cap_q"}) {
            Path p = assets.resolve("assets/hbm/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        for (String n : new String[]{"drillbit_steel", "drillbit_hss"}) {
            Path p = assets.resolve("assets/hbm/textures/items/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }

        Path out = tmp.resolve("outv");
        LegacySnapshot snap = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty());
        gen.generate(snap);

        // block meta 0 keeps the base id; meta 1 becomes its own blockstate + model
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/blockstates/tile.block_cap.json")));
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/blockstates/tile.block_cap_quantum.json")));
        String base = Files.readString(out.resolve("assets/hbm/models/block/tile.block_cap.json"));
        assertTrue(base.contains("hbm:block/cap_nuka_top"), base);
        String variant = Files.readString(out.resolve("assets/hbm/models/block/tile.block_cap_quantum.json"));
        assertTrue(variant.contains("minecraft:block/cube_all"), variant);
        assertTrue(variant.contains("\"all\": \"hbm:block/cap_q\""),
                "the variant must use its OWN icons[] row: " + variant);

        // each variant block gets its own BlockItem definition + model
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/items/tile.block_cap_quantum.json")));
        String vim = Files.readString(out.resolve("assets/hbm/models/item/tile.block_cap_quantum.json"));
        assertTrue(vim.contains("\"parent\": \"hbm:block/tile.block_cap_quantum\""), vim);

        // item variants: readable ids, own textures, and NO bare base
        String steel = Files.readString(out.resolve("assets/hbm/models/item/item.drillbit_steel.json"));
        assertTrue(steel.contains("\"layer0\": \"hbm:item/drillbit_steel\""), steel);
        String hss = Files.readString(out.resolve("assets/hbm/models/item/item.drillbit_hss.json"));
        assertTrue(hss.contains("\"layer0\": \"hbm:item/drillbit_hss\""), hss);
        assertFalse(Files.exists(out.resolve("assets/hbm/models/item/item.drillbit.json")),
                "the bare base must not be emitted when a damage-0 sub-item exists");

        // lang: real English for every id, no raw keys at all
        String langJson = Files.readString(out.resolve("assets/hbm/lang/en_us.json"));
        assertTrue(langJson.contains("\"block.hbm.tile.block_cap\": \"Block of Nuka Cola Bottle Caps\""), langJson);
        assertTrue(langJson.contains("\"block.hbm.tile.block_cap_quantum\": \"Block of Quantum Bottle Caps\""), langJson);
        assertTrue(langJson.contains("\"item.hbm.item.drillbit_steel\": \"Steel Drillbit\""), langJson);
        assertTrue(langJson.contains("\"item.hbm.item.drillbit_hss\": \"High-Speed Steel Drillbit\""), langJson);
        assertFalse(langJson.contains("item.drillbit.name"), langJson);
        assertEquals(java.util.List.of(), gen.rawKeyLangValues());

        assertEquals(2, gen.blockstates);
        assertEquals(2, gen.blockModels);
        assertEquals(1, gen.blockVariants);
        assertEquals(2, gen.itemVariants);
        assertEquals(4, gen.itemDefs);   // 2 block items + 2 item variants
        assertEquals(4, gen.langOut.size());
        assertEquals(0, gen.texturesMissing);
        assertEquals(java.util.List.of(), gen.plan.duplicatePaths());
    }

    /**
     * The lane-A3 case: HBM composites some sprites at RUNTIME, so they have no png anywhere in
     * the mod jar and can only come from a dump of the running client's stitched atlas. Here the
     * jar ships nothing for {@code hbm:drillbit_hss} (item) or {@code hbm:cap_q} (block); the
     * runtime dump supplies both - one via its icon-index.json row, one via the sanitized path
     * only - and the variant must then use its OWN icon instead of falling back to the base's.
     */
    @Test
    void runtimeAtlasDumpSuppliesIconsTheModJarNeverShipped(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("jar");
        for (String n : new String[]{"cap_nuka_top", "cap_nuka"}) {
            Path p = assets.resolve("assets/hbm/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        Path ip = assets.resolve("assets/hbm/textures/items/drillbit_steel.png");
        Files.createDirectories(ip.getParent());
        Files.write(ip, png);

        // the runtime dump: hbm:drillbit_hss listed in the index, hbm:cap_q by path only
        Path dump = tmp.resolve("runtime");
        Path hss = dump.resolve("assets/hbm/textures/atlas-dump/items/drillbit_hss.png");
        Files.createDirectories(hss.getParent());
        Files.write(hss, png);
        Path capq = dump.resolve("assets/hbm/textures/atlas-dump/blocks/cap_q.png");
        Files.createDirectories(capq.getParent());
        Files.write(capq, png);
        Files.writeString(dump.resolve("icon-index.json"), """
            {
              "counts": {"iconsReferencedUnique": 2, "dumped": 2},
              "icons": [
                {"name":"hbm:drillbit_hss","atlas":"items","file":"assets/hbm/textures/atlas-dump/items/drillbit_hss.png",
                 "width":16,"height":16,"frames":0,"missing":false,"source":"glReadback"},
                {"name":"hbm:nowhere","atlas":"items","file":null,"missing":true,"source":null}
              ]
            }
            """, StandardCharsets.UTF_8);

        Path out = tmp.resolve("outr");
        LegacySnapshot snap = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty(), dump);
        gen.generate(snap);

        assertEquals(0, gen.texturesMissing, "the dump covers every icon the jar is missing");
        assertEquals(2, gen.texturesFromRuntimeDump);
        assertEquals(0, gen.iconFallbacks,
                "a variant whose icon the dump supplies must keep its OWN icon");
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/item/drillbit_hss.png")),
                "index-resolved icon copied into the pack");
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/block/cap_q.png")),
                "path-resolved icon copied into the pack");
        String hssModel = Files.readString(out.resolve("assets/hbm/models/item/item.drillbit_hss.json"));
        assertTrue(hssModel.contains("\"layer0\": \"hbm:item/drillbit_hss\""), hssModel);
        String variant = Files.readString(out.resolve("assets/hbm/models/block/tile.block_cap_quantum.json"));
        assertTrue(variant.contains("\"all\": \"hbm:block/cap_q\""), variant);

        Path report = tmp.resolve("report-runtime.txt");
        gen.writeReport(report, snap);
        String r = Files.readString(report);
        assertTrue(r.contains("texturesFromRuntimeDump=2"), r);
        assertTrue(r.contains("hbm:drillbit_hss [item] <-"), r);
    }

    /** Without --runtime-assets nothing changes: the same snapshot still reports the same misses. */
    @Test
    void withoutARuntimeDumpTheMissesAreUnchanged(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("jar2");
        for (String n : new String[]{"cap_nuka_top", "cap_nuka"}) {
            Path p = assets.resolve("assets/hbm/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        Path ip = assets.resolve("assets/hbm/textures/items/drillbit_steel.png");
        Files.createDirectories(ip.getParent());
        Files.write(ip, png);

        LegacySnapshot snap = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        Path bareOut = tmp.resolve("out-bare");
        PackGen bare = new PackGen("hbm", assets, bareOut, LangTable.empty());
        bare.generate(snap);
        // Without a dump, hbm:cap_q and hbm:drillbit_hss resolve to nothing, so the old
        // behaviour stands: both variants borrow the BASE record's icon (that is what the
        // 157 checkerboards were being papered over with).
        assertEquals(0, bare.texturesFromRuntimeDump);
        assertEquals(7, bare.iconFallbacks, "6 block faces + 1 item icon borrowed from the base");
        String bareHss = Files.readString(bareOut.resolve("assets/hbm/models/item/item.drillbit_hss.json"));
        assertTrue(bareHss.contains("\"layer0\": \"hbm:item/drillbit_steel\""), bareHss);
        assertFalse(Files.exists(bareOut.resolve("assets/hbm/textures/item/drillbit_hss.png")));

        // a --runtime-assets pointing at a directory that does not exist must behave identically
        PackGen absent = new PackGen("hbm", assets, tmp.resolve("out-absent"), LangTable.empty(),
                tmp.resolve("no-such-dump"));
        absent.generate(snap);
        assertEquals(bare.texturesMissing, absent.texturesMissing);
        assertEquals(bare.texturesCopied, absent.texturesCopied);
        assertEquals(bare.iconFallbacks, absent.iconFallbacks);
        assertEquals(0, absent.texturesFromRuntimeDump);
    }

    /**
     * Task B (laneConsume-progress.md): {@link PackGen#copyGuiTextures} copies every resolvable
     * GUI background texture into the pack at the SAME namespaced path the mod jar used, dedupes
     * textures multiple GUIs share, and reports missing ones without touching the block/item pass.
     */
    @Test
    void copyGuiTexturesCopiesResolvedTexturesAtTheirRealNamespacedPath(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("jarAssets");
        Path realTex = assets.resolve("assets/hbm/textures/gui/processing/gui_purex.png");
        Files.createDirectories(realTex.getParent());
        Files.write(realTex, png);

        String json = """
            {"guis":[
              {"className":"A","container":{"className":"ContainerA","confidence":"exact"},
               "size":{"xSize":176,"ySize":256,"confidence":"exact"},
               "backgroundTextures":[{"path":"hbm:textures/gui/processing/gui_purex.png","existsInJar":true,
                 "assetPath":"assets/hbm/textures/gui/processing/gui_purex.png","sheetWidth":256,"sheetHeight":256}]},
              {"className":"B","container":{"className":"ContainerB","confidence":"exact"},
               "size":{"xSize":176,"ySize":256,"confidence":"exact"},
               "backgroundTextures":[{"path":"hbm:textures/gui/processing/gui_purex.png","existsInJar":true,
                 "assetPath":"assets/hbm/textures/gui/processing/gui_purex.png","sheetWidth":256,"sheetHeight":256}]},
              {"className":"C","container":{"className":"ContainerC","confidence":"exact"},
               "size":{"xSize":176,"ySize":200,"confidence":"exact"},
               "backgroundTextures":[{"path":"hbm:textures/gui/missing.png","existsInJar":false}]}
            ]}
            """;
        GuiProfile profile = GuiProfile.parseString(json);

        Path out = tmp.resolve("outGui");
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty());
        PackGen.GuiTextureReport report = gen.copyGuiTextures(profile);

        assertEquals(1, report.copied, "A and B share one texture - copied exactly once");
        assertEquals(1, report.missing, "C's texture never existed in the jar");
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/textures/gui/processing/gui_purex.png")));
        assertTrue(report.missingDetails.get(0).contains("C"));
    }

    /**
     * Mesh-replay gap (gui-capture lane): {@link PackGen#copyGuiTextures} also ships every GUI
     * art PNG the mod jar carries that no static profile entry resolved (e.g. wrapper-style
     * binds the profile cannot see), at the same namespaced path, without double-copying
     * profile-resolved ones.
     */
    @Test
    void copyGuiTexturesShipsUnprofiledGuiArt(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("jarAssets");
        Path unprofiled = assets.resolve("assets/mcheli/textures/gui/gui.png");
        Files.createDirectories(unprofiled.getParent());
        Files.write(unprofiled, png);
        Path profiled = assets.resolve("assets/mcheli/textures/gui/other.png");
        Files.write(profiled, png);

        String json = """
            {"guis":[
              {"className":"A","container":{"className":"ContainerA","confidence":"exact"},
               "size":{"xSize":176,"ySize":166,"confidence":"exact"},
               "backgroundTextures":[{"path":"mcheli:textures/gui/other.png","existsInJar":true,
                 "assetPath":"assets/mcheli/textures/gui/other.png","sheetWidth":256,"sheetHeight":256}]}
            ]}
            """;
        GuiProfile profile = GuiProfile.parseString(json);

        Path out = tmp.resolve("outGui");
        PackGen gen = new PackGen("mcheli", assets, out, LangTable.empty());
        PackGen.GuiTextureReport report = gen.copyGuiTextures(profile);

        assertEquals(2, report.copied, "profile texture + unprofiled gui art");
        assertEquals(0, report.missing);
        assertTrue(Files.isRegularFile(out.resolve("assets/mcheli/textures/gui/gui.png")));
        assertTrue(Files.isRegularFile(out.resolve("assets/mcheli/textures/gui/other.png")));
    }

    /**
     * laneCasing / Bug 1 (GENERALITY-REPORT.md finding 8, the Iron Chests pack): a mixed-case
     * legacy modid (mcmod.info's raw "IronChest", exactly as harness/legacy.ps1 would pass it
     * through {@code --ns} when the user doesn't override it) must produce a pack whose namespace
     * is fully, consistently lowercase EVERYWHERE - the assets directory, every blockstate/model/
     * item-def reference, and every lang key - never a mix of "IronChest:" and "ironchest:" like
     * the real generated pack this bug report documented.
     */
    private static final String MIXED_CASE_JSON = """
        {
          "blocks": [
            {"id":"IronChest:BlockIronChest","unlocalizedName":"tile.chest","displayName":"Iron Chest",
             "hardness":3.0,"resistance":5.0,"creativeTab":"decorations","stepSound":"wood",
             "icons":[{"meta":0,"sides":["ironchest:iron_top","ironchest:iron_top","ironchest:iron_side","ironchest:iron_side","ironchest:iron_side","ironchest:iron_side"]}]}
          ],
          "items": [
            {"id":"IronChest:itemChestChanger","unlocalizedName":"item.changer","displayName":"Chest Changer",
             "maxStackSize":1,"iconName":"ironchest:changer"}
          ]
        }
        """;

    @Test
    void mixedCaseLegacyModidProducesAFullyLowercasePack(@TempDir Path tmp) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        Path assets = tmp.resolve("src-ic");
        for (String n : new String[]{"iron_top", "iron_side"}) {
            Path p = assets.resolve("assets/ironchest/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        Path ip = assets.resolve("assets/ironchest/textures/items/changer.png");
        Files.createDirectories(ip.getParent());
        Files.write(ip, png);
        // the extract stage unzips the jar's OWN (lowercase) folder - not the raw mcmod.info modid
        Path lang = assets.resolve("assets/ironchest/lang/en_US.lang");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, "tile.chest.name=Iron Chest\n", StandardCharsets.UTF_8);

        Path out = tmp.resolve("out-ic");
        // exactly what harness/legacy.ps1 passes by default: the RAW mcmod.info modid, mixed case
        LegacySnapshot snap = LegacySnapshot.parseString(MIXED_CASE_JSON, "IronChest");
        LangTable langTable = LangTable.load(PackGen.resolveSourceLangFile(assets, "IronChest"));
        PackGen gen = new PackGen("IronChest", assets, out, langTable);
        gen.generate(snap);

        // the assets directory itself is lowercase, never the raw "IronChest". Listing the parent
        // (rather than probing Files.isDirectory("assets/IronChest")) matters on Windows, whose
        // case-insensitive filesystem would resolve either spelling to the same real directory and
        // hide a regression - the directory entry's OWN recorded name is what actually matters.
        try (var listing = Files.list(out.resolve("assets"))) {
            java.util.List<String> names = listing.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.contains("ironchest"), names.toString());
            assertFalse(names.contains("IronChest"), names.toString());
        }

        String bs = Files.readString(out.resolve("assets/ironchest/blockstates/blockironchest.json"));
        assertTrue(bs.contains("\"ironchest:block/blockironchest\""), bs);
        assertFalse(bs.contains("IronChest:"), "must never emit the raw mixed-case namespace: " + bs);

        String model = Files.readString(out.resolve("assets/ironchest/models/block/blockironchest.json"));
        assertFalse(model.contains("IronChest:"), model);
        assertTrue(model.contains("ironchest:block/iron_top") || model.contains("ironchest:block/iron_side"), model);

        String itemDef = Files.readString(out.resolve("assets/ironchest/items/itemchestchanger.json"));
        assertFalse(itemDef.contains("IronChest:"), itemDef);
        assertTrue(itemDef.contains("ironchest:item/itemchestchanger"), itemDef);

        String langJson = Files.readString(out.resolve("assets/ironchest/lang/en_us.json"));
        assertFalse(langJson.contains("IronChest"), langJson);
        assertTrue(langJson.contains("\"block.ironchest.blockironchest\": \"Iron Chest\""), langJson);

        // the report is allowed to mention the raw legacy namespace for humans, but the
        // "namespace=" line itself must be the sanitized one PackGen actually wrote everything under
        Path report = tmp.resolve("report-ic.txt");
        gen.writeReport(report, snap);
        String r = Files.readString(report);
        assertTrue(r.contains("namespace=ironchest"), r);
    }

    @Test
    void vanillaNamespaceIconsAreAliasedNotCopied(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out3");
        PackGen gen = new PackGen("hbm", tmp.resolve("nope"), out, LangTable.empty());
        assertEquals("minecraft:block/oak_log", gen.textureRef("block", "log_oak", "x"));
        assertEquals("minecraft:block/stone_bricks", gen.textureRef("block", "stonebrick", "x"));
        assertEquals("minecraft:block/bedrock", gen.textureRef("block", "bedrock", "x"));
        assertEquals(0, gen.texturesMissing, "vanilla icons must not count as missing");
    }

    // ============================================================================================
    // multiblock-notes/PACKGEN-SHAPE-VARIANT.md: shape-only twins (VariantPlan.BlockEntry#shapeOnly)
    // now get a blockstate + block model too, reusing VARIANT_JSON's own "hbm:tile.block_cap" block
    // (base meta 0 + a REAL subBlocks variant at meta 1) plus a synthetic block-shapes.json-style
    // profile that gives meta 5 - not in subBlocks - a genuinely different collision box.
    // ============================================================================================

    private static final String SHAPE_ONLY_PROFILE_JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.block_cap","metaGroups":[
               {"metas":[0],"collisionAabb":[0,0,0,1,1,1],"isFullCube":false},
               {"metas":[5],"collisionAabb":[0,0,0,1,2,1],"isFullCube":false}
            ]}
          ]
        }
        """;

    private Path buildVariantAssets(Path root) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        for (String n : new String[]{"cap_nuka_top", "cap_nuka", "cap_q"}) {
            Path p = root.resolve("assets/hbm/textures/blocks/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        for (String n : new String[]{"drillbit_steel", "drillbit_hss"}) {
            Path p = root.resolve("assets/hbm/textures/items/" + n + ".png");
            Files.createDirectories(p.getParent());
            Files.write(p, png);
        }
        return root;
    }

    private static VariantPlan.BlockEntry shapeOnlyTwin(PackGen gen, String baseId) {
        for (VariantPlan.BlockEntry e : gen.plan.blocks) {
            if (e.shapeOnly && e.base.id.equals(baseId)) return e;
        }
        return null;
    }

    /**
     * Rule 1/2 of PACKGEN-SHAPE-VARIANT.md: the gap was that {@code PackGen} never even saw a
     * shape-only twin (it always called the two-arg {@code VariantPlan.build}, which forces an
     * empty {@link BlockShapeProfile}) - not a skip inside the per-entry loop. Once a real profile
     * is supplied, the same walk now must emit a blockstate + block model for the twin (so the
     * registered Block renders as something real instead of 26.2's undeclared-state fallback) but
     * NO item definition, item model, or lang row (it has no BlockItem and never will).
     */
    @Test
    void shapeOnlyTwinGetsABlockstateAndModelButNoItemOrLangRow(@TempDir Path tmp) throws Exception {
        Path assets = buildVariantAssets(tmp.resolve("src-shapeonly"));
        Path out = tmp.resolve("out-shapeonly");
        LegacySnapshot snap = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        BlockShapeProfile shapes = BlockShapeProfile.parseString(SHAPE_ONLY_PROFILE_JSON);
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty(), null, shapes);
        gen.generate(snap);

        VariantPlan.BlockEntry twin = shapeOnlyTwin(gen, "hbm:tile.block_cap");
        assertTrue(twin != null, "meta 5 genuinely differs from meta 0's shape and is not in subBlocks");
        assertEquals(5, twin.meta);
        assertNull(twin.displayName, "a shape-only twin has no synthetic display name");

        String path = twin.path;
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/blockstates/" + path + ".json")),
                "shape-only twin must still get a blockstate, or the registered Block has nothing to render");
        String model = Files.readString(out.resolve("assets/hbm/models/block/" + path + ".json"));
        // visual-fidelity assumption: no icon row was recorded specifically for meta 5, so
        // BlockRec#sidesFor(5) falls back to meta 0's own row - the SAME visual as the base.
        assertTrue(model.contains("hbm:block/cap_nuka_top") || model.contains("hbm:block/cap_nuka"),
                "must reuse the base metadata's own resolved texture: " + model);

        assertFalse(Files.exists(out.resolve("assets/hbm/items/" + path + ".json")),
                "a shape-only twin has no BlockItem - no item definition may exist for it");
        assertFalse(Files.exists(out.resolve("assets/hbm/models/item/" + path + ".json")),
                "a shape-only twin has no BlockItem - no item model may exist for it");

        String langJson = Files.readString(out.resolve("assets/hbm/lang/en_us.json"));
        assertFalse(langJson.contains("\"block.hbm." + path + "\""),
                "a shape-only twin must get no lang row at all (it has no display name): " + langJson);
        assertFalse(gen.rawKeyLangValues().stream().anyMatch(s -> s.startsWith("block.hbm." + path)),
                "must never report a shape-only twin's absent name as a bogus raw-key lang value");

        assertEquals(1, gen.blockShapeOnly);
    }

    /**
     * Rule "do not regress the original 1244 blocks / 2163 item variants" of
     * PACKGEN-SHAPE-VARIANT.md, done as a self-contained before/after diff rather than trusting
     * the implementation: generate the SAME snapshot once with an empty {@link BlockShapeProfile}
     * (byte-for-byte what every pre-existing PackGen caller does) and once with the real profile
     * that adds a meta-5 shape-only twin, then assert every file the "before" run wrote is present
     * and BYTE-IDENTICAL in the "after" run, and that the "after" run's file set adds EXACTLY the
     * two new shape-only files (no new item/lang output anywhere).
     */
    @Test
    void shapeOnlyTwinsDoNotAlterAnyPreExistingEntrysOutput(@TempDir Path tmp) throws Exception {
        Path assetsBefore = buildVariantAssets(tmp.resolve("src-before"));
        Path outBefore = tmp.resolve("out-before");
        LegacySnapshot snapBefore = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        PackGen before = new PackGen("hbm", assetsBefore, outBefore, LangTable.empty());
        before.generate(snapBefore);

        Path assetsAfter = buildVariantAssets(tmp.resolve("src-after"));
        Path outAfter = tmp.resolve("out-after");
        LegacySnapshot snapAfter = LegacySnapshot.parseString(VARIANT_JSON, "hbm");
        BlockShapeProfile shapes = BlockShapeProfile.parseString(SHAPE_ONLY_PROFILE_JSON);
        PackGen after = new PackGen("hbm", assetsAfter, outAfter, LangTable.empty(), null, shapes);
        after.generate(snapAfter);

        java.util.List<Path> beforeFiles;
        try (var walk = Files.walk(outBefore)) {
            beforeFiles = walk.filter(Files::isRegularFile).map(outBefore::relativize).sorted().toList();
        }
        java.util.List<Path> afterFiles;
        try (var walk = Files.walk(outAfter)) {
            afterFiles = walk.filter(Files::isRegularFile).map(outAfter::relativize).sorted().toList();
        }

        for (Path rel : beforeFiles) {
            assertTrue(Files.isRegularFile(outAfter.resolve(rel)),
                    "pre-existing output file missing after adding shape-only twins: " + rel);
            byte[] b = Files.readAllBytes(outBefore.resolve(rel));
            byte[] a = Files.readAllBytes(outAfter.resolve(rel));
            assertTrue(java.util.Arrays.equals(b, a),
                    "pre-existing output file changed byte-for-byte for " + rel);
        }

        VariantPlan.BlockEntry twin = shapeOnlyTwin(after, "hbm:tile.block_cap");
        assertTrue(twin != null);
        java.util.Set<Path> added = new java.util.LinkedHashSet<>(afterFiles);
        for (Path rel : beforeFiles) added.remove(rel);
        java.util.Set<Path> expectedAdded = java.util.Set.of(
                Path.of("assets", "hbm", "blockstates", twin.path + ".json"),
                Path.of("assets", "hbm", "models", "block", twin.path + ".json"));
        assertEquals(expectedAdded, added,
                "exactly the shape-only twin's own blockstate+model must be added - nothing else: " + added);

        // aggregate counters: identical for the pre-existing population, plus exactly the new twin
        assertEquals(before.blockstates + 1, after.blockstates);
        assertEquals(before.blockModels + 1, after.blockModels);
        assertEquals(before.itemDefs, after.itemDefs, "no new item definition for a shape-only twin");
        assertEquals(before.itemModels, after.itemModels, "no new item model for a shape-only twin");
        assertEquals(before.langOut.size(), after.langOut.size(), "no new lang row for a shape-only twin");
        assertEquals(before.blockVariants, after.blockVariants,
                "a shape-only twin is not a real subBlocks variant - must not inflate blockVariants");
        assertEquals(0, before.blockShapeOnly);
        assertEquals(1, after.blockShapeOnly);
    }

    // ============================================================================================
    // missing-models lane: a block with NO icons at all used to get no blockstate, so 26.2 logged
    // "Missing model for variant" for it - but when the snapshot PROVES the block rendered nothing
    // (renderType -1 = not drawn by the 1.7.10 block renderer, and unbreakable so no particle can
    // ever sample a texture), an empty model is exact, not a guess. Anything else no-icon keeps
    // the honest skip (and the warning).
    // ============================================================================================

    private static final String NO_ICON_JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.spotlight_beam","unlocalizedName":"tile.spotlight_beam","displayName":"tile.spotlight_beam.name",
             "hardness":-1.0,"resistance":6000000.0,"renderType":-1,"hasTileEntity":true,
             "icons":[{"meta":0,"sides":[null,null,null,null,null,null]}]},
            {"id":"hbm:tile.mystery_noicon","unlocalizedName":"tile.mystery_noicon","displayName":"Mystery",
             "hardness":3.0,"resistance":5.0,"renderType":0,
             "icons":[{"meta":0,"sides":[null,null,null,null,null,null]}]}
          ],
          "items": []
        }
        """;

    @Test
    void provablyInvisibleNoIconBlockGetsAnEmptyModelWhileOtherNoIconBlocksStaySkipped(
            @TempDir Path tmp) throws Exception {
        Path assets = tmp.resolve("src-noicon");
        Files.createDirectories(assets);
        Path out = tmp.resolve("out-noicon");
        LegacySnapshot snap = LegacySnapshot.parseString(NO_ICON_JSON, "hbm");
        PackGen gen = new PackGen("hbm", assets, out, LangTable.empty());
        gen.generate(snap);

        // the renderType-1, unbreakable beam: blockstate + a model that draws zero quads
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/blockstates/tile.spotlight_beam.json")),
                "a provably invisible block must still get a blockstate, or 26.2 warns Missing model");
        String model = Files.readString(out.resolve("assets/hbm/models/block/tile.spotlight_beam.json"));
        assertTrue(model.contains("minecraft:block/block"),
                "must parent the element-less vanilla base so nothing renders: " + model);
        assertFalse(model.contains("cube"),
                "must not be a cube - 1.7.10 rendered nothing for this block: " + model);
        assertTrue(model.contains("\"particle\""),
                "an explicit particle keeps the model valid even though it can never show: " + model);
        // uniform with every other block: item definition + item model + lang row still emitted
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/items/tile.spotlight_beam.json")));
        assertTrue(Files.isRegularFile(out.resolve("assets/hbm/models/item/tile.spotlight_beam.json")));
        assertTrue(Files.readString(out.resolve("assets/hbm/lang/en_us.json"))
                .contains("\"block.hbm.tile.spotlight_beam\""));

        // the breakable, renderType-0 no-icon block: still skipped, still honestly missing
        assertFalse(Files.exists(out.resolve("assets/hbm/blockstates/tile.mystery_noicon.json")),
                "a no-icon block with no invisibility proof must keep the honest skip");

        assertEquals(1, gen.blocksInvisible);
        assertEquals(1, gen.blocksNoIcon);
    }

    /**
     * A renderType -1 row is staticless even when icons exist; a positive custom render type is
     * not the TESR-only contract and retains its extracted static data model until P4 handles it.
     */
    @Test
    void tesrOnlyIsStaticlessButCustomRenderTypeKeepsItsDataModel(@TempDir Path tmp) throws Exception {
        String json = """
            {
              "blocks": [
                {"id":"test:tesr_machine","unlocalizedName":"tile.tesr_machine","displayName":"TESR",
                 "hardness":3.0,"resistance":5.0,"renderType":-1,"hasTileEntity":true,
                 "icons":[{"meta":0,"sides":["test:machine","test:machine","test:machine","test:machine","test:machine","test:machine"]}]},
                {"id":"test:isbrh_machine","unlocalizedName":"tile.isbrh_machine","displayName":"ISBRH",
                 "hardness":3.0,"resistance":5.0,"renderType":113,"hasTileEntity":false,
                 "icons":[{"meta":0,"sides":["test:machine_top","test:machine_top","test:machine","test:machine","test:machine","test:machine"]}]} 
              ],
              "items": []
            }
            """;
        Path assets = tmp.resolve("src-custom-render");
        Path icon = assets.resolve("assets/test/textures/blocks/machine.png");
        Files.createDirectories(icon.getParent());
        Files.write(icon, java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        Path top = assets.resolve("assets/test/textures/blocks/machine_top.png");
        Files.write(top, Files.readAllBytes(icon));
        Path out = tmp.resolve("out-custom-render");
        PackGen gen = new PackGen("test", assets, out, LangTable.empty());
        gen.generate(LegacySnapshot.parseString(json, "test"));

        assertTrue(Files.isRegularFile(out.resolve("assets/test/blockstates/tesr_machine.json")));
        String tesr = Files.readString(out.resolve("assets/test/models/block/tesr_machine.json"));
        assertTrue(tesr.contains("minecraft:block/block"), tesr);
        assertFalse(tesr.contains("minecraft:block/cube"), tesr);

        String path = "isbrh_machine";
        assertTrue(Files.isRegularFile(out.resolve("assets/test/blockstates/" + path + ".json")), path);
        String model = Files.readString(out.resolve("assets/test/models/block/" + path + ".json"));
        assertTrue(model.contains("minecraft:block/cube"), model);
        assertFalse(model.contains("minecraft:block/block"), model);
        assertEquals(2, gen.blockModels);
        assertEquals(0, gen.blocksNoIcon);
    }
}
