package dev.umb.objbridge.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * generated {@code umb:obj} item def references its {@code .obj} model by path
 * ({@code "model": "hbm:models/weapons/uzi.obj"}), but {@link ObjPackGen} used to only ever copy
 * the row's TEXTURE into the output pack, never the model file itself - so 26.2 could not resolve
 * the reference and the item rendered as nothing at all, in both the hotbar/GUI icon and the held
 * first-person view. Confirmed live: {@code hbm-objmodels/assets/hbm/items/item.gun_uzi.json}
 * pointed at {@code hbm:models/weapons/uzi.obj}, which did not exist anywhere in the generated
 * pack, while its texture ({@code models/weapons/uzi.png}) did.
 *
 * <p>This runs the real {@link ObjPackGen#main} entry point end to end over a tiny synthetic
 * render map + snapshot + asset root (no game, no checked-in fixture jar needed) and asserts the
 * FINAL swapped-in {@code outDir} - not the {@code .staging} directory - actually contains the
 * referenced {@code .obj} file with the right bytes. Universal: this is the same code path every
 * {@code umb:obj} row in every mod goes through, not anything HBM/Uzi-specific.
 */
class ObjPackGenModelCopyTest {

    private static final String OBJ_BODY = """
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 0.0 1.0 0.0
            f 1 2 3
            """;

    @Test
    void generatedPackContainsTheReferencedObjFileNotJustItsTexture(@TempDir Path tmp) throws Exception {
        Path assetsRoot = tmp.resolve("assets-root");
        Path modelSrc = assetsRoot.resolve("assets/hbm/models/weapons/uzi.obj");
        Path textureSrc = assetsRoot.resolve("assets/hbm/textures/models/weapons/uzi.png");
        Files.createDirectories(modelSrc.getParent());
        Files.writeString(modelSrc, OBJ_BODY, StandardCharsets.UTF_8);
        Files.createDirectories(textureSrc.getParent());
        Files.write(textureSrc, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3}); // fake PNG bytes, content never parsed

        Path mapFile = tmp.resolve("render-map.json");
        Files.writeString(mapFile, """
                {
                  "items": [
                    {
                      "id": "hbm:item.gun_uzi",
                      "className": "com.hbm.items.weapon.ItemGunBaseNT",
                      "rendererClass": "com.hbm.render.item.ItemGunRenderer",
                      "models": [ { "path": "hbm:models/weapons/uzi.obj" } ],
                      "textures": [ { "path": "hbm:textures/models/weapons/uzi.png" } ]
                    }
                  ],
                  "blocks": []
                }
                """);

        Path snapFile = tmp.resolve("snapshot.json");
        Files.writeString(snapFile, """
                { "items": [ { "id": "hbm:item.gun_uzi", "unlocalizedName": "item.gun_uzi" } ], "blocks": [] }
                """);

        Path outDir = tmp.resolve("out-pack");

        ObjPackGen.main(new String[] {
                mapFile.toString(), snapFile.toString(), assetsRoot.toString(), outDir.toString(),
                "--ns", "hbm", "--report", tmp.resolve("report.md").toString()
        });

        // The bug: this file never existed at all in the swapped-in output pack.
        Path copiedModel = outDir.resolve("assets/hbm/models/weapons/uzi.obj");
        assertTrue(Files.isRegularFile(copiedModel),
                "the .obj model referenced by the generated item def must actually be copied into the pack");
        assertArrayEquals(Files.readAllBytes(modelSrc), Files.readAllBytes(copiedModel),
                "the copied model must be byte-identical to the source");

        // Not a regression on the pre-existing, already-working texture copy.
        Path copiedTexture = outDir.resolve("assets/hbm/textures/models/weapons/uzi.png");
        assertTrue(Files.isRegularFile(copiedTexture), "the texture copy path must still work");

        // And the generated item def really does reference that same path (the JSON side of the bug).
        Path itemDef = outDir.resolve("assets/hbm/items/item.gun_uzi.json");
        assertTrue(Files.isRegularFile(itemDef), "expected an item def to be written for item.gun_uzi");
        String json = Files.readString(itemDef, StandardCharsets.UTF_8);
        assertTrue(json.contains("hbm:models/weapons/uzi.obj"),
                "the item def must reference the same model path that was just proven to exist on disk");
    }
}
