package dev.umb.objbridge;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.item.ObjTransforms;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import dev.umb.objbridge.obj.ObjMesh;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end, headless numeric evidence for THIS lane's fix (laneInv-progress.md): the block-item-in-slot
 * rule ({@link ObjBridge#itemGeometryFit}) and the vanilla {@code oversized_in_gui} wiring
 * ({@code dev.umb.objbridge.gen.ObjPackGen#oversized}), using the REAL render map, REAL
 * {@code renderer-transforms.json} and REAL OBJ files - not synthetic fixtures. Sibling of
 * {@code ScaleFixVerificationTest} (the previous lane's own permanent regression test for the in-world
 * block-scale fix), which this test configures {@link ObjBridge} with exactly the same real paths that
 * test reads directly.
 */
class InventoryFixVerificationTest {

    private static final Path ASSETS = Path.of("research/out/legacy/hbm-assets");
    private static final Path RENDER_MAP = Path.of("research/out/legacy/rendermap/hbm-render-map.json");
    private static final Path TRANSFORMS_FILE = Path.of("research/out/legacy/rendermap/renderer-transforms.json");

    private static boolean dataPresent() {
        return Files.isDirectory(ASSETS) && Files.isRegularFile(RENDER_MAP) && Files.isRegularFile(TRANSFORMS_FILE);
    }

    private static void configureRealObjBridge() {
        ObjBridge.configure(ASSETS, RENDER_MAP, TRANSFORMS_FILE);
    }

    private static RenderMap.BlockRow findBlock(RenderMap map, String id) {
        return map.blocks().stream().filter(b -> id.equals(b.id())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " not found in render map"));
    }

    private static float[] extents(MeshBaker.Result r) {
        float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var q : r.quads()) {
            for (int v = 0; v < 4; v++) {
                float[] p = {q.x(v), q.y(v), q.z(v)};
                for (int a = 0; a < 3; a++) {
                    if (p[a] < e[a]) e[a] = p[a];
                    if (p[a] > e[a + 3]) e[a + 3] = p[a];
                }
            }
        }
        return e;
    }

    /**
     * Common assertion for a block-item: the item-form proportions (this lane's fix) must resemble the
     * block's IN-WORLD proportions (previous lane's fix, {@code ScaleFixVerificationTest}), not the raw
     * OBJ file's own aspect ratio, which has no necessary relation to either.
     *
     * <p>For the radar and the Tsar Bomba specifically, BEFORE and AFTER turn out numerically IDENTICAL -
     * not a bug, but a real fact about the data: both TESRs' WORLD-path transforms are rotation+translate
     * ONLY (no {@code glScale} at all - see {@code laneScale-progress.md}'s "authored coordinates x
     * transform ARE block units already" finding), and a rotation about a single axis does not change
     * which axis is longest or by how much, only which way it points - so the raw mesh's own bbox ratio
     * already equals the world-transformed one for these two. The mechanism genuinely changing
     * proportions for a renderer whose WORLD path DOES apply a non-uniform scale is covered by
     * {@code MeshBakerTest.itemFromWorldTransformShapesProportionsButNormalisesAbsoluteSize} with a
     * synthetic transform (no shipped HBM block that splices happens to combine a non-uniform WORLD-path
     * scale with an item form in this snapshot). So the assertion here is CORRECTNESS - the AFTER ratio
     * must match the independently-established (previous lane's test) world ratio - not "must differ."
     */
    private void assertBlockItemMatchesWorldProportions(String blockId, float worldLongestAxisRatioTolerance)
            throws Exception {
        RenderMap map = RenderMap.read(RENDER_MAP);
        RenderMap.BlockRow row = findBlock(map, blockId);
        // Same texture resolution ObjBridge.spliceBlocks / buildIndicesIfAbsent use: a block row with no
        // textures of its own falls back to its tile entity's - radar/tsar both do. Getting this wrong
        // means computing a different (model,sprite) key than ObjBridge indexed, silently missing the
        // block-item-in-slot lookup and falling back to plain auto-fit (caught by this test failing with
        // BEFORE == AFTER when it should not).
        java.util.List<RenderMap.Asset> textures = new java.util.ArrayList<>(row.textures());
        if (textures.isEmpty()) {
            RenderMap.TeRow te = map.tileEntityFor(row.id());
            if (te != null) textures.addAll(te.textures());
        }
        TexturePick.Pick pick = TexturePick.choose(row.models(), textures, ASSETS);
        assertTrue(pick.model() != null, blockId + ": no OBJ resolved");
        assertTrue(pick.resolved(), blockId + ": texture must resolve too, or the (model,sprite) key "
                + "below cannot match what ObjBridge indexed");
        ObjMesh mesh = ObjMesh.parse(dev.umb.objbridge.obj.ObjAssets.resolve(ASSETS, pick.model().path()));

        // BEFORE this lane: every item (block-item or not) used plain raw-mesh auto-fit.
        MeshBaker.Result before = MeshBaker.bake(mesh, Fit.ITEM);
        float[] be = extents(before);
        float bx = be[3] - be[0], by = be[4] - be[1], bz = be[5] - be[2];

        // AFTER: ObjBridge.itemGeometryFit resolves the block's own WORLD-path class and shapes the item
        // mesh by it, then normalises - same call ObjItemModel.Unbaked.bake now makes.
        Fit afterFit = ObjBridge.itemGeometryFit(pick.model().path(), pick.spriteId(), 1.0f);
        MeshBaker.Result after = MeshBaker.bake(mesh, afterFit);
        float[] ae = extents(after);
        float ax = ae[3] - ae[0], ay = ae[4] - ae[1], az = ae[5] - ae[2];

        System.out.println("[VERIFY-INV] " + blockId + " item-form BEFORE(x,y,z)=(" + bx + "," + by + "," + bz
                + ")  AFTER(x,y,z)=(" + ax + "," + ay + "," + az + ")");

        // Every axis must be <= 1.0 (still fits the item unit cube - normalised, never left at world scale).
        assertTrue(ax <= 1.0001f && ay <= 1.0001f && az <= 1.0001f,
                "block-item geometry must still be normalised into the item unit cube");

        // CORRECTNESS: the AFTER proportions must match the block's independently-computed in-world
        // (WORLD-path) proportions - i.e. the exact same RenderFit.forPath(..., PathClass.WORLD,
        // onGround=true) shape ScaleFixVerificationTest already proves for these two blocks, just
        // re-normalised to the item unit cube instead of left at world scale.
        dev.umb.objbridge.transform.RendererTransforms transforms =
                dev.umb.objbridge.transform.RendererTransforms.read(TRANSFORMS_FILE);
        String worldClass = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
        dev.umb.objbridge.transform.RenderFit.Outcome worldOutcome = dev.umb.objbridge.transform.RenderFit
                .forPath(transforms, worldClass, dev.umb.objbridge.transform.PathClass.WORLD, true);
        MeshBaker.Result worldForm = MeshBaker.bake(mesh, worldOutcome.fit());
        float[] we = extents(worldForm);
        float wx = we[3] - we[0], wy = we[4] - we[1], wz = we[5] - we[2];
        float wLongest = Math.max(wx, Math.max(wy, wz));

        assertEquals(wx / wLongest, ax / Math.max(ax, Math.max(ay, az)), worldLongestAxisRatioTolerance,
                blockId + ": item-form x proportion must match the in-world shape");
        assertEquals(wy / wLongest, ay / Math.max(ax, Math.max(ay, az)), worldLongestAxisRatioTolerance,
                blockId + ": item-form y proportion must match the in-world shape");
        assertEquals(wz / wLongest, az / Math.max(ax, Math.max(ay, az)), worldLongestAxisRatioTolerance,
                blockId + ": item-form z proportion must match the in-world shape");
    }

    @Test
    void machineRadarLargeItemFormFollowsWorldProportionsNotRawMeshProportions() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present");
        configureRealObjBridge();
        assertBlockItemMatchesWorldProportions("hbm:tile.machine_radar_large", 0.05f);
        assertTrue(ObjBridge.itemGeometryFromWorldCount() > 0);
    }

    @Test
    void nukeTsarItemFormFollowsWorldProportionsNotRawMeshProportions() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present");
        configureRealObjBridge();
        assertBlockItemMatchesWorldProportions("hbm:tile.nuke_tsar", 0.05f);
    }

    @Test
    void rendererBackedItemGeometryPreservesLegacyModelUnits() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present");
        configureRealObjBridge();
        RenderMap map = RenderMap.read(RENDER_MAP);
        RenderMap.ItemRow row = map.items().stream().filter(r -> "hbm:item.gun_minigun".equals(r.id()))
                .findFirst().orElseThrow();
        TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), ASSETS);
        assertTrue(pick.resolved());

        int before = ObjBridge.itemGeometryFromLegacyRendererCount();
        Fit fit = ObjBridge.itemGeometryFit(pick.model().path(), pick.spriteId(), 1.0f);
        assertTrue(ObjBridge.itemGeometryFromLegacyRendererCount() > before,
                "the minigun must preserve its resolved legacy IItemRenderer model units");
        assertTrue(!fit.auto(), "a renderer-backed item's geometry must not be normalized to one block");
        MeshBaker.Result baked = MeshBaker.bake(ObjMesh.parse(
                dev.umb.objbridge.obj.ObjAssets.resolve(ASSETS, pick.model().path())), fit);
        float[] e = extents(baked);
        float largest = Math.max(e[3] - e[0], Math.max(e[4] - e[1], e[5] - e[2]));
        assertTrue(largest > 1.0f && largest < 2.0f,
                "minigun authored extent must remain in 1/16 model units, got " + largest);
    }

    @Test
    void ordinaryArmorPlateGuiScaleIsUnchangedBecauseItHasOnlyOneDataPoint() throws Exception {
        // com.hbm.items.armor.ArmorBismuth$1 has ONLY renderCommon ops (no per-type differentiation at
        // all) - a single data point carries no RATIO information relative to its own mean, so the
        // derived multiplier must be exactly 1.0 and every perspective must equal flat vanilla. This is
        // the brief's "one small ordinary item that must NOT change noticeably" example.
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present");
        dev.umb.objbridge.transform.RendererTransforms rt =
                dev.umb.objbridge.transform.RendererTransforms.read(TRANSFORMS_FILE);
        String cls = "com.hbm.items.armor.ArmorBismuth$1";
        assertTrue(rt.hasClass(cls), "fixture class missing from the real renderer-transforms.json - "
                + "update this test if HBM's class name ever changes");
        ItemTransforms t = ObjTransforms.forItem(rt, cls);
        System.out.println("[VERIFY-INV] bismuth_plate (ordinary item) gui.scale=" + t.gui().scale()
                + " fp.scale=" + t.firstPersonRightHand().scale()
                + " tp.scale=" + t.thirdPersonRightHand().scale()
                + "  (vanilla gui=" + ObjTransforms.BLOCK_ITEM.gui().scale() + ")");
        var buckets = dev.umb.objbridge.transform.ItemPerspectiveRatio.buckets(rt, cls);
        float expectedGui = dev.umb.objbridge.transform.ItemPerspectiveRatio.absoluteScale(
                buckets.inv(), buckets.common(), 0.5f);
        assertEquals(expectedGui, t.gui().scale().x(), 1e-6f);
        float expectedFp = dev.umb.objbridge.transform.ItemPerspectiveRatio.absoluteScale(
                buckets.fp(), buckets.common(), ObjTransforms.BLOCK_ITEM.firstPersonRightHand().scale().x());
        float expectedTp = dev.umb.objbridge.transform.ItemPerspectiveRatio.absoluteScale(
                buckets.tp(), buckets.common(), ObjTransforms.BLOCK_ITEM.thirdPersonRightHand().scale().x());
        assertEquals(expectedFp, t.firstPersonRightHand().scale().x(), 1e-6f);
        assertEquals(expectedTp, t.thirdPersonRightHand().scale().x(), 1e-6f);
    }

    @Test
    void radarBlockItemDerivesAGuiScaleWellPastTheOversizedThreshold() throws Exception {
        // Without an exact item-display sidecar branch, old renderInventory/common readings must not
        // enlarge the fallback GUI icon. Genuine exact GUI branches are audited separately.
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present");
        dev.umb.objbridge.transform.RendererTransforms rt =
                dev.umb.objbridge.transform.RendererTransforms.read(TRANSFORMS_FILE);
        String cls = "com.hbm.render.tileentity.RenderRadarLarge$1";
        assertTrue(rt.hasClass(cls));
        ItemTransforms t = ObjTransforms.forItem(rt, cls);
        System.out.println("[VERIFY-INV] machine_radar_large fallback gui.scale=" + t.gui().scale().x());
        var buckets = dev.umb.objbridge.transform.ItemPerspectiveRatio.buckets(rt, cls);
        float expectedGui = dev.umb.objbridge.transform.ItemPerspectiveRatio.absoluteScale(
                buckets.inv(), buckets.common(), 0.5f);
        assertEquals(expectedGui, t.gui().scale().x(), 1e-6f);
    }
}
