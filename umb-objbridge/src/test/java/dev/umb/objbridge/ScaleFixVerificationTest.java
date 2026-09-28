package dev.umb.objbridge;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.item.ObjTransforms;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import dev.umb.objbridge.obj.ObjMesh;
import dev.umb.objbridge.transform.PathClass;
import dev.umb.objbridge.transform.RenderFit;
import dev.umb.objbridge.transform.RendererTransforms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end, headless (no game classpath, no window) numeric evidence for the fix, using the REAL
 * render map, REAL {@code renderer-transforms.json} and REAL OBJ files shipped in this repo - not
 * synthetic fixtures. This is exactly the "before vs after" the lane brief asks the final report to
 * quote, made a permanent regression test so a future change cannot silently reintroduce the bug.
 *
 * <p>"Before" = the OLD behaviour ({@link Fit#BLOCK}, auto-fit largest-extent-to-1). "After" = what
 * {@link dev.umb.objbridge.ObjBridge#onModelsApplied} now actually produces
 * ({@link RenderFit#forPath} + {@link MeshBaker}).
 */
class ScaleFixVerificationTest {

    private static final Path ASSETS = Path.of("research/out/legacy/hbm-assets");
    private static final Path RENDER_MAP = Path.of("research/out/legacy/rendermap/hbm-render-map.json");
    private static final Path TRANSFORMS_FILE = Path.of("research/out/legacy/rendermap/renderer-transforms.json");

    private static boolean dataPresent() {
        return Files.isDirectory(ASSETS) && Files.isRegularFile(RENDER_MAP) && Files.isRegularFile(TRANSFORMS_FILE);
    }

    private record Sized(float dx, float dy, float dz, boolean resolved, boolean identity,
                         boolean clamped, boolean fellBack) {
        float longest() { return Math.max(dx, Math.max(dy, dz)); }
    }

    private static Sized size(RenderMap.BlockRow row, RendererTransforms transforms) throws Exception {
        TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), ASSETS);
        assertTrue(pick.model() != null, row.id() + ": no OBJ resolved by TexturePick");
        ObjMesh mesh = ObjMesh.parse(dev.umb.objbridge.obj.ObjAssets.resolve(ASSETS, pick.model().path()));

        String rendererClass = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
        RenderFit.Outcome outcome = RenderFit.forPath(transforms, rendererClass, PathClass.WORLD, true);
        MeshBaker.Result r = MeshBaker.bake(mesh, outcome.fit());
        float[] e = extents(r);
        return new Sized(e[3] - e[0], e[4] - e[1], e[5] - e[2], outcome.resolved(), outcome.identity(),
                r.clamped(), r.fellBackToAutoFit());
    }

    private static float oldAutoFitLongest(RenderMap.BlockRow row) throws Exception {
        TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), ASSETS);
        ObjMesh mesh = ObjMesh.parse(dev.umb.objbridge.obj.ObjAssets.resolve(ASSETS, pick.model().path()));
        MeshBaker.Result r = MeshBaker.bake(mesh, Fit.BLOCK);
        float[] e = extents(r);
        return Math.max(e[3] - e[0], Math.max(e[4] - e[1], e[5] - e[2]));
    }

    private static RenderMap.BlockRow findBlock(RenderMap map, String id) {
        return map.blocks().stream().filter(b -> id.equals(b.id())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " not found in render map"));
    }

    @Test
    void beforeAndAfterSizesForTheFourNamedExamples() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present in this checkout");

        RenderMap map = RenderMap.read(RENDER_MAP);
        RendererTransforms transforms = RendererTransforms.read(TRANSFORMS_FILE);

        // --- 1. large radar - genuinely tall multi-block structure in HBM, declared as a 1x1x1 cube
        RenderMap.BlockRow radar = findBlock(map, "hbm:tile.machine_radar_large");
        float radarBefore = oldAutoFitLongest(radar);
        Sized radarAfter = size(radar, transforms);
        report("machine_radar_large", radarBefore, radarAfter);
        assertTrue(radarBefore <= 1.0f + 1e-4f, "old auto-fit must cap the longest axis at 1 block");
        assertTrue(radarAfter.longest() > 3.0f, "radar must now be clearly larger than one block");
        // RenderRadarLarge's WORLD path has translate(dynamic, skipped)+rotate ops but NO scale op, so
        // this resolves (opsUsed > 0) without being the "zero ops at all" identity() case - the fix is
        // that the composed transform still carries no SCALE, which is what keeps the extent authored.
        assertTrue(radarAfter.resolved());

        // --- 2. Tsar Bomba - the exact example from the bug report
        RenderMap.BlockRow tsar = findBlock(map, "hbm:tile.nuke_tsar");
        float tsarBefore = oldAutoFitLongest(tsar);
        Sized tsarAfter = size(tsar, transforms);
        report("nuke_tsar", tsarBefore, tsarAfter);
        assertTrue(tsarBefore <= 1.0f + 1e-4f);
        assertTrue(tsarAfter.longest() > 3.0f, "the Tsar Bomba must now be clearly larger than one block");
        assertTrue(tsarAfter.resolved());

        // --- 3. launch table - this repo's closest analogue to a "cargo/landing pad" block: a large,
        // walkable platform with a TESR that (like the two above) applies no scale on its world path
        RenderMap.BlockRow launchTable = findBlock(map, "hbm:tile.launch_table");
        float padBefore = oldAutoFitLongest(launchTable);
        Sized padAfter = size(launchTable, transforms);
        report("launch_table (landing pad)", padBefore, padAfter);
        assertTrue(padBefore <= 1.0f + 1e-4f);
        assertTrue(padAfter.longest() > 3.0f, "the landing pad must now be clearly larger than one block");
    }

    @Test
    void heldGunGetsAPerItemPerspectiveScaleInsteadOfOneFlatVanillaValue() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present in this checkout");
        RenderMap map = RenderMap.read(RENDER_MAP);
        RendererTransforms transforms = RendererTransforms.read(TRANSFORMS_FILE);

        Optional<RenderMap.ItemRow> minigunRow = map.items().stream()
                .filter(r -> "hbm:item.gun_minigun".equals(r.id())).findFirst();
        assertTrue(minigunRow.isPresent(), "hbm:item.gun_minigun not found in render map");
        RenderMap.ItemRow row = minigunRow.get();
        assertTrue("com.hbm.render.item.weapon.sedna.ItemRenderMinigun".equals(row.rendererClass()));

        TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), ASSETS);
        assertTrue(pick.resolved(), "minigun model+texture must resolve");

        // BEFORE this lane: every item used this exact same static transform, minigun included.
        var before = ObjTransforms.BLOCK_ITEM;
        // AFTER: item-specific, derived from ItemRenderMinigun's own setupInv/renderFirstPerson/
        // setupThirdPerson ratios (see ObjTransformsTest for the worked coilgun example).
        var after = ObjTransforms.forItem(transforms, row.rendererClass());

        System.out.println("[VERIFY] minigun BEFORE gui.scale=" + before.gui().scale()
                + " fp.scale=" + before.firstPersonRightHand().scale()
                + " tp.scale=" + before.thirdPersonRightHand().scale());
        System.out.println("[VERIFY] minigun AFTER  gui.scale=" + after.gui().scale()
                + " fp.scale=" + after.firstPersonRightHand().scale()
                + " tp.scale=" + after.thirdPersonRightHand().scale());

        assertTrue(after != before, "the minigun's own renderer data must resolve to something other "
                + "than the flat vanilla fallback - ItemRenderMinigun has setupInv/renderFirstPerson/"
                + "setupThirdPerson ops");
    }

    /**
     * The distribution the brief asks for: across every block row this lane can splice at all (same
     * model+texture resolution {@link ObjBridge#spliceBlocks} uses), how many now come out larger than
     * one block, how many stayed at/below, and how many fell back to plain auto-fit because no renderer
     * transform resolved. Printed rather than asserted into a brittle exact count (real HBM data drifts
     * lane to lane) - the assertions below only pin down the qualitative shape the fix must have.
     */
    @Test
    void distributionAcrossEverySplicableBlock() throws Exception {
        Assumptions.assumeTrue(dataPresent(), "real render-map/transforms/assets not present in this checkout");
        RenderMap map = RenderMap.read(RENDER_MAP);
        RendererTransforms transforms = RendererTransforms.read(TRANSFORMS_FILE);

        int total = 0, resolved = 0, identity = 0, autoFitFallback = 0, clamped = 0, fellBack = 0;
        int biggerThanOneBlock = 0, atOrBelowOneBlock = 0;

        for (RenderMap.BlockRow row : map.blocks()) {
            if (row.id() == null || !row.id().startsWith("hbm:")) continue;
            java.util.List<RenderMap.Asset> textures = new java.util.ArrayList<>(row.textures());
            if (textures.isEmpty()) {
                RenderMap.TeRow te = map.tileEntityFor(row.id());
                if (te != null) textures.addAll(te.textures());
            }
            TexturePick.Pick pick = TexturePick.choose(row.models(), textures, ASSETS);
            if (pick.model() == null || pick.texture() == null) continue;
            ObjMesh mesh;
            try {
                mesh = ObjMesh.parse(dev.umb.objbridge.obj.ObjAssets.resolve(ASSETS, pick.model().path()));
            } catch (Exception e) {
                continue;
            }
            total++;

            String rendererClass = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
            RenderFit.Outcome outcome = RenderFit.forPath(transforms, rendererClass, PathClass.WORLD, true);
            if (outcome.resolved()) {
                if (outcome.identity()) identity++; else resolved++;
            } else {
                autoFitFallback++;
            }
            MeshBaker.Result r = MeshBaker.bake(mesh, outcome.fit(), row.groups());
            if (r.clamped()) clamped++;
            if (r.fellBackToAutoFit()) fellBack++;

            float[] e = extents(r);
            float longest = Math.max(e[3] - e[0], Math.max(e[4] - e[1], e[5] - e[2]));
            if (longest > 1.0f + 1e-4f) biggerThanOneBlock++; else atOrBelowOneBlock++;
        }

        System.out.println("[VERIFY] distribution over " + total + " splicable blocks: "
                + "resolvedWithTransform=" + resolved + " resolvedIdentity=" + identity
                + " autoFitFallback=" + autoFitFallback + " clamped=" + clamped + " fellBack=" + fellBack
                + " | biggerThan1Block=" + biggerThanOneBlock + " atOrBelow1Block=" + atOrBelowOneBlock);

        assertTrue(total > 100, "expected on the order of 162 splicable blocks, got " + total);
        assertTrue(biggerThanOneBlock > 0, "the fix must make at least some real blocks bigger than 1 block");
        assertTrue(resolved + identity + autoFitFallback == total);
    }

    private static void report(String label, float before, Sized after) {
        System.out.println("[VERIFY] " + label + "  BEFORE(auto-fit longest axis)=" + before
                + " block   AFTER(x,y,z)=(" + after.dx() + "," + after.dy() + "," + after.dz()
                + ") blocks   resolved=" + after.resolved() + " identity=" + after.identity()
                + " clamped=" + after.clamped() + " fellBack=" + after.fellBack());
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
}
