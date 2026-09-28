package dev.umb.objbridge;

import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexturePickTest {

    private static RenderMap.Asset a(String path) {
        return new RenderMap.Asset(path, "assets/" + path.replace(':', '/'));
    }

    @Test
    void assetPathHelpersSplitCorrectly() {
        RenderMap.Asset m = a("hbm:models/weapons/minigun.obj");
        assertEquals("minigun", m.baseName());
        assertEquals("models/weapons", m.dir());
        assertEquals("hbm", m.namespace());
        assertEquals("models/weapons/minigun.obj", m.bare());

        RenderMap.Asset flat = a("hbm:models/BombGeneric.obj");
        assertEquals("BombGeneric", flat.baseName());
        assertEquals("models", flat.dir());
    }

    @Test
    void basenameRuleBeatsEverythingElse() {
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/weapons/minigun.obj")),
                List.of(a("hbm:textures/models/weapons/aberrator.png"),
                        a("hbm:textures/models/weapons/minigun.png")),
                null);
        assertEquals(TexturePick.Reason.BASENAME, p.reason());
        assertEquals("hbm:textures/models/weapons/minigun.png", p.texture().path());
        assertEquals("hbm:models/weapons/minigun", p.spriteId());
        assertTrue(p.resolved());
    }

    @Test
    void familyRuleFiresWhenNoBasenameMatches() {
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/weapons/minigun.obj")),
                List.of(a("hbm:textures/models/armor/somethingelse.png"),
                        a("hbm:textures/models/weapons/eott.png")),
                null);
        assertEquals(TexturePick.Reason.FAMILY, p.reason());
        assertEquals("hbm:models/weapons/eott", p.spriteId());
    }

    @Test
    void effectTexturesAreNeverChosen() {
        // this is the real minigun row: two effect sheets listed before the body skin
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/weapons/minigun.obj")),
                List.of(a("hbm:textures/models/weapons/lilmac_plume.png"),
                        a("hbm:textures/models/weapons/laser_flash.png"),
                        a("hbm:textures/models/weapons/minigun.png")),
                null);
        assertEquals(TexturePick.Reason.BASENAME, p.reason());
        assertEquals("hbm:models/weapons/minigun", p.spriteId());

        for (String k : List.of("plume", "flash", "muzzle", "glow", "lens", "beam", "laser")) {
            assertTrue(TexturePick.isEffect("hbm:textures/models/x/foo_" + k + "_bar.png"),
                    k + " must be treated as an effect sheet");
        }
        assertFalse(TexturePick.isEffect("hbm:textures/models/weapons/minigun.png"));
    }

    @Test
    void anEffectOnlyRowResolvesNoTexture() {
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/weapons/minigun.obj")),
                List.of(a("hbm:textures/models/weapons/lilmac_plume.png")),
                null);
        assertNotNull(p.model());
        assertNull(p.texture());
        assertEquals(TexturePick.Reason.NONE, p.reason());
        assertFalse(p.resolved());
    }

    @Test
    void firstRuleIsTheLastResort() {
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/missile_parts/mp_f_10_15_kerosene.obj")),
                List.of(a("hbm:textures/models/missile_parts/fuselages/mp_f_10_15_balefire.png")),
                null);
        // fuselages/ is a child of the model's family models/missile_parts -> FAMILY, not FIRST
        assertEquals(TexturePick.Reason.FAMILY, p.reason());

        TexturePick.Pick q = TexturePick.choose(
                List.of(a("hbm:models/armor/AJR.obj")),
                List.of(a("hbm:textures/armor/ajr_helmet.png")),
                null);
        assertEquals(TexturePick.Reason.FIRST, q.reason(), "a wholly unrelated directory -> FIRST");
    }

    @Test
    void theCorruptOrphanObjIsNeverChosen() {
        TexturePick.Pick p = TexturePick.choose(
                List.of(a("hbm:models/weapons/.obj"), a("hbm:models/weapons/minigun.obj")),
                List.of(a("hbm:textures/models/weapons/minigun.png")),
                null);
        assertEquals("hbm:models/weapons/minigun.obj", p.model().path());
        assertTrue(TexturePick.isCorrupt("hbm:models/weapons/.obj"));
        assertFalse(TexturePick.isCorrupt("hbm:models/weapons/minigun.obj"));
    }

    @Test
    void aRowWithNoObjResolvesNothing() {
        TexturePick.Pick p = TexturePick.choose(List.of(), List.of(a("hbm:textures/models/x.png")), null);
        assertNull(p.model());
        assertNull(p.texture());
        assertEquals(TexturePick.Reason.NONE, p.reason());
    }

    @Test
    void spriteIdLowercasesAndRelocatesNonModelTextures() {
        TexturePick.Pick body = TexturePick.choose(
                List.of(a("hbm:models/BombGeneric.obj")),
                List.of(a("hbm:textures/models/BombGeneric.png")),
                null);
        assertEquals("hbm:models/bombgeneric", body.spriteId(),
                "Identifier paths only allow [a-z0-9/._-], so the sprite name is lowercased");

        TexturePick.Pick armour = TexturePick.choose(
                List.of(a("hbm:models/armor/AJR.obj")),
                List.of(a("hbm:textures/armor/AJR_Helmet.png")),
                null);
        assertEquals("hbm:models/" + TexturePick.RELOCATED + "/armor/ajr_helmet", armour.spriteId(),
                "anything outside textures/models/ is relocated so ONE atlas source suffices");
    }

    @Test
    void sanitizeMatchesTheHostAgentsLegacyIdsOnEveryRealHbmPath() throws Exception {
        // Keep the duplicated sanitizing rule in sync without adding a runtime dependency.
        Class<?> legacyIds;
        try {
            legacyIds = Class.forName("dev.umb.hostagent.content.LegacyIds");
        } catch (ClassNotFoundException e) {
            return;   // hostagent jar not on the test classpath
        }
        var sanitizePath = legacyIds.getMethod("sanitizePath", String.class);

        Path root = Paths.get("research/out/legacy/hbm-assets/assets/hbm");
        if (!Files.isDirectory(root)) return;
        int checked = 0;
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).limit(4000).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                assertEquals(sanitizePath.invoke(null, rel), TexturePick.sanitize(rel),
                        "sanitize disagreed on " + rel);
                checked++;
            }
        }
        assertTrue(checked > 100, "expected to check a meaningful number of paths, got " + checked);
    }

    @Test
    void aDanglingTextureReferenceIsNotUsable() {
        Path root = Paths.get("research/out/legacy/hbm-assets");
        if (!Files.isDirectory(root)) return;
        RenderMap.Asset endSky = new RenderMap.Asset(
                "minecraft:textures/environment/end_sky.png",
                "assets/minecraft/textures/environment/end_sky.png");
        assertFalse(TexturePick.usable(endSky, root),
                "a vanilla texture that was never extracted from the mod jar must be rejected");
        RenderMap.Asset real = new RenderMap.Asset(
                "hbm:textures/models/weapons/minigun.png",
                "assets/hbm/textures/models/weapons/minigun.png");
        assertTrue(TexturePick.usable(real, root));
        assertTrue(TexturePick.usable(endSky, null), "a null root skips the existence check");
    }

    @Test
    void mirrorRuleFindsTheOnDiskTwinOfTheObj() {
        Path root = Paths.get("research/out/legacy/hbm-assets");
        if (!Files.isDirectory(root)) return;
        RenderMap.Asset mirror = TexturePick.mirrorOf(a("hbm:models/weapons/minigun.obj"), root);
        assertNotNull(mirror);
        assertEquals("hbm:textures/models/weapons/minigun.png", mirror.path());
        assertNull(TexturePick.mirrorOf(a("hbm:models/does/not/exist.obj"), root));
    }
}
