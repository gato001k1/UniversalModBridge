package dev.umb.objbridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.umb.objbridge.gen.ObjPackGen;
import dev.umb.objbridge.item.ObjItemModel;
import dev.umb.objbridge.obj.ObjAssets;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code umb:obj} codec, round-tripped through {@code JsonOps} on its own (the full dispatch
 * through {@code ItemModels.CODEC} needs {@code ItemModels.bootstrap()} to have run under the agent -
 * that is what {@code tools/probe-objbridge.ps1} proves).
 */
class CodecRoundTripTest {

    @Test
    void encodesAndDecodesEveryField() {
        ObjItemModel.Unbaked in = new ObjItemModel.Unbaked(
                "hbm:models/weapons/minigun.obj",
                Identifier.fromNamespaceAndPath("hbm", "models/weapons/minigun"),
                Optional.of(0.75f),
                List.of("Gun", "Grip"));

        DataResult<JsonElement> enc = ObjItemModel.Unbaked.MAP_CODEC.codec()
                .encodeStart(JsonOps.INSTANCE, in);
        JsonElement json = enc.getOrThrow();
        JsonObject o = json.getAsJsonObject();
        assertEquals("hbm:models/weapons/minigun.obj", o.get("model").getAsString());
        assertEquals("hbm:models/weapons/minigun", o.get("texture").getAsString());
        assertEquals(0.75f, o.get("fit").getAsFloat(), 0f);
        assertEquals(2, o.get("groups").getAsJsonArray().size());

        ObjItemModel.Unbaked out = ObjItemModel.Unbaked.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(in, out);
    }

    @Test
    void fitAndGroupsAreOptional() {
        JsonObject o = new JsonObject();
        o.addProperty("model", "hbm:models/BombGeneric.obj");
        o.addProperty("texture", "hbm:models/bombgeneric");
        ObjItemModel.Unbaked out = ObjItemModel.Unbaked.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, o).getOrThrow();
        assertEquals("hbm:models/BombGeneric.obj", out.model());
        assertEquals(Identifier.fromNamespaceAndPath("hbm", "models/bombgeneric"), out.texture());
        assertTrue(out.fit().isEmpty());
        assertTrue(out.groups().isEmpty());
    }

    @Test
    void modelIsAPlainStringSoMixedCaseObjNamesSurvive() {
        // hbm ships 269 OBJs whose file name has uppercase letters; Identifier.isValidPath rejects
        // those, which is exactly why "model" is a String and not an Identifier.
        assertTrue(Identifier.tryParse("hbm:models/BombGeneric.obj") == null
                        || !Identifier.isValidPath("models/BombGeneric.obj"),
                "an uppercase path must not be a legal Identifier path");
        JsonObject o = new JsonObject();
        o.addProperty("model", "hbm:models/armor/AJR.obj");
        o.addProperty("texture", "hbm:models/_umb/armor/ajr_helmet");
        ObjItemModel.Unbaked out = ObjItemModel.Unbaked.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, o).getOrThrow();
        assertEquals("hbm:models/armor/AJR.obj", out.model());
    }

    @Test
    void aMissingRequiredFieldIsAnError() {
        JsonObject o = new JsonObject();
        o.addProperty("model", "hbm:models/x.obj");
        DataResult<ObjItemModel.Unbaked> r =
                ObjItemModel.Unbaked.MAP_CODEC.codec().parse(JsonOps.INSTANCE, o);
        assertTrue(r.isError(), "texture is required");
    }

    @Test
    void theGeneratorsOwnJsonDecodesBackToTheSameThing() {
        String json = ObjPackGen.itemDef("hbm:models/weapons/minigun.obj", "hbm:models/weapons/minigun");
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        JsonObject model = root.getAsJsonObject("model");
        assertEquals("umb:obj", model.get("type").getAsString());
        model.remove("type");   // the type key is consumed by ItemModels' dispatch codec
        ObjItemModel.Unbaked out = ObjItemModel.Unbaked.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, model).getOrThrow();
        assertEquals("hbm:models/weapons/minigun.obj", out.model());
        assertEquals(Identifier.fromNamespaceAndPath("hbm", "models/weapons/minigun"), out.texture());
    }

    @Test
    void everyGeneratedItemDefInTheOverlayPackDecodes() throws Exception {
        Path dir = Paths.get("research/out/legacy/packs/hbm-objmodels/assets/hbm/items");
        if (!Files.isDirectory(dir)) return;
        int n = 0;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                JsonObject root = new Gson().fromJson(Files.readString(p), JsonObject.class);
                JsonObject model = root.getAsJsonObject("model");
                assertEquals("umb:obj", model.get("type").getAsString(), p.toString());
                model.remove("type");
                ObjItemModel.Unbaked u = ObjItemModel.Unbaked.MAP_CODEC.codec()
                        .parse(JsonOps.INSTANCE, model).getOrThrow();
                assertNotNull(u.model());
                assertNotNull(u.texture());
                assertTrue(Identifier.isValidPath(u.texture().getPath()),
                        "sprite path must be a legal Identifier path: " + u.texture());
                n++;
            }
        }
        assertTrue(n > 400, "expected the full generated pack, found " + n + " defs");
    }

    @Test
    void everyGeneratedDefPointsAtATextureThePackActuallyShips() throws Exception {
        Path pack = Paths.get("research/out/legacy/packs/hbm-objmodels");
        Path dir = pack.resolve("assets/hbm/items");
        if (!Files.isDirectory(dir)) return;
        int checked = 0;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                JsonObject root = new Gson().fromJson(Files.readString(p), JsonObject.class);
                JsonObject model = root.getAsJsonObject("model");
                String sprite = model.get("texture").getAsString();     // hbm:models/weapons/minigun
                String rel = sprite.substring(sprite.indexOf(':') + 1);
                Path png = pack.resolve("assets/hbm/textures/" + rel + ".png");
                assertTrue(Files.isRegularFile(png),
                        p.getFileName() + " points at " + sprite + " but " + png + " is not in the pack");
                // and the sprite must be under the ONE directory source the pack declares
                assertTrue(rel.startsWith("models/"),
                        "sprite outside the declared models/ atlas source: " + sprite);
                checked++;
            }
        }
        assertTrue(checked > 400);
    }

    @Test
    void everyGeneratedDefPointsAtAnObjThatExists() throws Exception {
        Path pack = Paths.get("research/out/legacy/packs/hbm-objmodels/assets/hbm/items");
        Path assets = Paths.get("research/out/legacy/hbm-assets");
        if (!Files.isDirectory(pack) || !Files.isDirectory(assets)) return;
        int checked = 0;
        try (var s = Files.list(pack)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                JsonObject root = new Gson().fromJson(Files.readString(p), JsonObject.class);
                String model = root.getAsJsonObject("model").get("texture") == null ? null
                        : root.getAsJsonObject("model").get("model").getAsString();
                assertNotNull(model);
                Path obj = ObjAssets.resolve(assets, model);
                assertTrue(obj != null && Files.isRegularFile(obj),
                        p.getFileName() + " points at " + model + " -> " + obj);
                checked++;
            }
        }
        assertTrue(checked > 400);
    }
}
