package dev.umb.hostagent.content;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * suggestion: a stone-slab-shaped and an oak-stairs-shaped synthetic group) and the two real HBM
 * shapes this session's out-of-cell decision was written for
 * ({@code hbm:tile.machine_purex} = full cube, {@code hbm:tile.launch_pad_large} meta 12 =
 * boxes reaching outside the cell).
 */
class BlockShapesTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    private static final double EPS = 1.0E-7;

    @Test
    void nullGroupIsTheDefaultFullCube() {
        VoxelShape shape = BlockShapes.build(null);
        assertTrue(Shapes.equal(Shapes.block(), shape));
        assertEquals(0, BlockShapes.contributingBoxCount(null));
        assertTrue(!BlockShapes.isNonDefault(null));
    }

    @Test
    void isFullCubeGroupIsTheDefaultFullCubeEvenWithBoxesPresent() {
        // hbm:tile.machine_purex: isOpaqueCube=false, renderAsNormalBlock=false, but isFullCube=true -
        BlockShapeProfile.MetaGroup purex = group(new int[]{0}, new double[]{0, 0, 0, 1, 1, 1},
                new double[]{0, 0, 0, 1, 1, 1}, new double[][]{{0, 0, 0, 1, 1, 1}}, true);
        VoxelShape shape = BlockShapes.build(purex);
        assertTrue(Shapes.equal(Shapes.block(), shape));
        assertEquals(0, BlockShapes.contributingBoxCount(purex), "isFullCube short-circuits before counting boxes");
    }

    @Test
    void vanillaControlStoneSlabShapedGroupProducesTheHalfHeightBounds() {
        // minecraft:stone_slab (bottom half): a single box, not a full cube.
        BlockShapeProfile.MetaGroup slab = group(new int[]{0}, new double[]{0, 0, 0, 1, 0.5, 1},
                new double[]{0, 0, 0, 1, 0.5, 1}, new double[][]{{0, 0, 0, 1, 0.5, 1}}, false);

        VoxelShape shape = BlockShapes.build(slab);
        AABB b = shape.bounds();
        assertEquals(0.0, b.minX, EPS);
        assertEquals(0.0, b.minY, EPS);
        assertEquals(0.0, b.minZ, EPS);
        assertEquals(1.0, b.maxX, EPS);
        assertEquals(0.5, b.maxY, EPS, "half-height, exactly like a real bottom slab");
        assertEquals(1.0, b.maxZ, EPS);
        assertEquals(1, BlockShapes.contributingBoxCount(slab));
        assertTrue(BlockShapes.isNonDefault(slab));
    }

    @Test
    void vanillaControlOakStairsShapedGroupUnionsBothBoxes() {
        // minecraft:oak_stairs (south/bottom-half ascending): 2 boxes - a full-width bottom slab
        BlockShapeProfile.MetaGroup stairs = group(new int[]{0}, null, null, new double[][]{
                {0, 0, 0, 1, 0.5, 1},
                {0, 0.5, 0, 1, 1, 0.5},
        }, false);

        VoxelShape shape = BlockShapes.build(stairs);
        assertEquals(2, BlockShapes.contributingBoxCount(stairs), "both boxes must survive clamping unchanged");
        AABB b = shape.bounds();
        assertEquals(0.0, b.minY, EPS);
        assertEquals(1.0, b.maxY, EPS, "the union's bounding box spans the full block height");
        assertEquals(0.0, b.minZ, EPS);
        assertEquals(1.0, b.maxZ, EPS);
    }

    @Test
    void launchPadLargeMeta12StaysClampedBecauseItsBoxesSitAtTheProbeWindowEdge() {
        // real hbm:tile.launch_pad_large meta-12 data. Every one of its 3 boxes has a coordinate at
        // EXACTLY -4.0 or 5.0 - the probe's own query-window edge (BlockShapeProbe: SYNTH_Y=4, a
        // +-4/+5 mask) - so BlockShapes.isUnclamped must NOT trust this as the block's complete
        // declared shape (a real structure this size could easily extend further; the extractor
        // declaresOutOfCellShape is true here, isUnclamped is false, and the OLD clamp behaviour
        // this test originally asserted is therefore unchanged - it just now holds for the RIGHT,
        // checked reason instead of an unconditional one.
        BlockShapeProfile.MetaGroup pad = group(new int[]{12}, new double[]{0, 0, 0, 1, 0.999, 1},
                new double[]{0, 0, 0, 1, 0.999, 1}, new double[][]{
                        {1, 0, -4, 5, 1, 5},
                        {-4, 0, -4, 0, 1, 5},
                        {0, 0.875, -4, 1, 1, 5},
                }, false);

        assertTrue(BlockShapes.declaresOutOfCellShape(pad), "these boxes genuinely reach outside the cell");
        assertTrue(!BlockShapes.isUnclamped(pad), "but every box sits at the probe's own window edge");

        VoxelShape shape = BlockShapes.build(pad);
        assertEquals(1, BlockShapes.contributingBoxCount(pad),
                "2 of the 3 boxes are entirely outside the cell and must be dropped");
        AABB b = shape.bounds();
        assertEquals(0.0, b.minX, EPS);
        assertEquals(1.0, b.maxX, EPS);
        assertEquals(0.875, b.minY, EPS);
        assertEquals(1.0, b.maxY, EPS);
        assertEquals(0.0, b.minZ, EPS);
        assertEquals(1.0, b.maxZ, EPS, "the box's out-of-cell Z extent (-4..5) is clamped to 0..1, never extends beyond it");
        // never out-of-cell: this is exactly what keeps getFaceOcclusionShape (which
        // BlockBehaviour's default delegates back to this same shape - see the class javadoc, and
        // Registrar.primeBlockStateCaches) from ever seeing geometry reaching into a neighbouring cell.
        assertTrue(b.minX >= 0.0 && b.maxX <= 1.0 && b.minY >= 0.0 && b.maxY <= 1.0
                && b.minZ >= 0.0 && b.maxZ <= 1.0);
    }

    @Test
    void machineCentrifugeMeta12DeclaresARealChimneyAndGetsBuiltUnclamped() {
        // real hbm:tile.machine_centrifuge meta 12-15 data (a BlockDummyable-family controller,
        // MULTIBLOCK-LANE.md): a base full-ish box plus a second box whose Y reaches from 1.0 to
        // 4.0 - a real "chimney" extending 3 cells above the controller's own position, declared by
        // the block's own func_149743_a (addCollisionBoxesToList), well inside the extractor's
        // unclamp path exists for.
        BlockShapeProfile.MetaGroup centrifuge = group(new int[]{12, 13, 14, 15},
                new double[]{0, 0, 0, 1, 0.999, 1}, new double[]{0, 0, 0, 1, 0.999, 1},
                new double[][]{
                        {0, 0, 0, 1, 1, 1},
                        {0.125, 1.0, 0.125, 0.875, 4.0, 0.875},
                }, false);

        assertTrue(BlockShapes.declaresOutOfCellShape(centrifuge));
        assertTrue(BlockShapes.isUnclamped(centrifuge), "well inside the probe window - trusted as complete");

        VoxelShape shape = BlockShapes.build(centrifuge);
        assertEquals(2, BlockShapes.contributingBoxCount(centrifuge), "both boxes survive, unclamped");
        AABB b = shape.bounds();
        assertEquals(0.0, b.minY, EPS);
        assertEquals(4.0, b.maxY, EPS, "the chimney's true height (3 cells above the controller) is preserved");
        assertEquals(0.0, b.minX, EPS);
        assertEquals(1.0, b.maxX, EPS);
    }

    @Test
    void anOrdinaryFullCubeBlockIsNeverTreatedAsOutOfCellOrUnclamped() {
        // Regression guard: the overwhelming majority of 1.7.10 blocks are genuinely one cube -
        BlockShapeProfile.MetaGroup ordinary = group(new int[]{0}, new double[]{0, 0, 0, 1, 1, 1},
                new double[]{0, 0, 0, 1, 1, 1}, new double[][]{{0, 0, 0, 1, 1, 1}}, false);
        assertTrue(!BlockShapes.declaresOutOfCellShape(ordinary));
        assertTrue(!BlockShapes.isUnclamped(ordinary));
        VoxelShape shape = BlockShapes.build(ordinary);
        assertTrue(Shapes.equal(shape, Shapes.block()));
    }

    @Test
    void aBoxOnlyNegligiblyOverOneFromFloatRoundingIsNotTreatedAsOutOfCell() {
        // e.g. 0.9990000128746033 (a real value seen in block-shapes.json for other HBM blocks) or
        // a hypothetical 1.0000001 must not falsely trip the new out-of-cell path - EPS exists
        // exactly for this, matching the probe's own isFullCube rounding tolerance.
        BlockShapeProfile.MetaGroup nearOne = group(new int[]{0}, new double[]{0, 0, 0, 1, 1.0000001, 1},
                new double[]{0, 0, 0, 1, 1.0000001, 1}, new double[][]{{0, 0, 0, 1, 1.0000001, 1}}, false);
        assertTrue(!BlockShapes.declaresOutOfCellShape(nearOne), "within EPS of 1.0 - not a real out-of-cell declaration");
    }

    @Test
    void aBoxRightAtTheProbeWindowEdgeIsDistrustedEvenWithASingleOffendingCoordinate() {
        // Only ONE coordinate needs to sit at the window edge to distrust the whole group - a
        // structure could easily continue past that single edge even if its other 5 coordinates
        // are comfortable.
        BlockShapeProfile.MetaGroup edge = group(new int[]{0}, null, null,
                new double[][]{{0, 0, 0, 1, 1, 5.0}}, false);
        assertTrue(BlockShapes.declaresOutOfCellShape(edge));
        assertTrue(!BlockShapes.isUnclamped(edge), "Z max sits exactly at the probe window's +5 edge");
    }

    @Test
    void launchPadLargeMetaZeroToElevenFallsBackToCollisionAabbWhenBoxesAreEmpty() {
        // metas 0-11 declare NO collisionBoxes at all - the fallback ladder must use collisionAabb.
        BlockShapeProfile.MetaGroup slab = group(new int[]{0, 1, 2}, new double[]{0, 0, 0, 1, 0.999, 1},
                new double[]{0, 0, 0, 1, 0.999, 1}, new double[][]{}, false);
        VoxelShape shape = BlockShapes.build(slab);
        AABB b = shape.bounds();
        assertEquals(0.999, b.maxY, EPS);
        assertEquals(1, BlockShapes.contributingBoxCount(slab));
    }

    @Test
    void aGroupWithNothingUsableFallsAllTheWayBackToTheDefaultFullCube() {
        BlockShapeProfile.MetaGroup empty = group(new int[]{0}, null, null, new double[][]{}, false);
        VoxelShape shape = BlockShapes.build(empty);
        assertTrue(Shapes.equal(Shapes.block(), shape));
        assertEquals(0, BlockShapes.contributingBoxCount(empty));
    }

    // func_149668_a returning null, recorded by the extractor as an EXPLICIT "collisionAabb":null
    // with an empty collisionBoxes list. buildCollision must turn exactly that into
    // Shapes.empty() while build (outline/selection) keeps its non-empty shape. ----

    @Test
    void spikesShapedExplicitNullCollisionGroupGetsEmptyCollisionButKeepsItsClickableOutline() {
        // real hbm:tile.spikes data: rawBounds/selection = full cube, collisionAabb EXPLICIT null,
        // collisionBoxes [], isFullCube false - you must be able to walk into it (and get hurt by
        BlockShapeProfile.MetaGroup spikes = explicitNullCollisionGroup(
                new double[]{0, 0, 0, 1, 1, 1}, new double[][]{}, false);
        assertTrue(BlockShapes.hasNoCollision(spikes), "explicit-null aabb + no boxes = recorded no-collision");
        assertTrue(BlockShapes.buildCollision(spikes).isEmpty(), "collision must be EMPTY, like vanilla torches/fire");
        assertTrue(Shapes.equal(Shapes.block(), BlockShapes.build(spikes)),
                "the outline keeps rawBounds' full cube so the block stays clickable");
    }

    @Test
    void anAbsentCollisionAabbKeyIsNoDataNotNoCollision() {
        // the same geometry but with the collisionAabb key MISSING entirely (a malformed or
        // pre-schema record) must conservatively KEEP solid collision - "no data" != "no collision".
        BlockShapeProfile.MetaGroup noData = group(new int[]{0}, new double[]{0, 0, 0, 1, 1, 1},
                null, new double[][]{}, false);
        assertTrue(!BlockShapes.hasNoCollision(noData));
        assertTrue(Shapes.equal(BlockShapes.build(noData), BlockShapes.buildCollision(noData)),
                "collision falls back to the outline shape exactly as before this lane");
    }

    @Test
    void explicitNullAabbWithRealCollisionBoxesKeepsTheBoxes() {
        // a group can record a null AABB but still list real boxes - boxes win, not the null.
        BlockShapeProfile.MetaGroup boxed = explicitNullCollisionGroup(
                new double[]{0, 0, 0, 1, 1, 1}, new double[][]{{0, 0, 0, 1, 0.5, 1}}, false);
        assertTrue(!BlockShapes.hasNoCollision(boxed));
        VoxelShape collision = BlockShapes.buildCollision(boxed);
        assertTrue(!collision.isEmpty());
        assertEquals(0.5, collision.bounds().maxY, EPS);
    }

    @Test
    void aFullCubeGroupNeverLosesCollisionEvenWithAnExplicitNullAabb() {
        // defensive: no such record exists in the corpus (verified 0 of 276), but a contradictory
        // full-cube-with-null-collision record must resolve to the solid cube, not walk-through.
        BlockShapeProfile.MetaGroup contradictory = explicitNullCollisionGroup(
                new double[]{0, 0, 0, 1, 1, 1}, new double[][]{}, true);
        assertTrue(!BlockShapes.hasNoCollision(contradictory));
        assertTrue(Shapes.equal(Shapes.block(), BlockShapes.buildCollision(contradictory)));
    }

    @Test
    void corpusExactly276Of1900MetaGroupsRecordNoCollisionAndAllBuildEmptyCollision() {
        // Baseline independently verified twice before writing this test: 276 explicit
        // "collisionAabb":null occurrences in the raw JSON text, over 1900 total collisionAabb
        // keys; zero of the 276 are isFullCube/opaque/errored (LIVE-GAP-ANALYSIS.md section 4).
        java.nio.file.Path file = java.nio.file.Path.of("research", "out", "legacy", "block-shapes.json");
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(file),
                "block-shapes.json not present in this checkout: " + file.toAbsolutePath());
        BlockShapeProfile profile = BlockShapeProfile.load(file);
        int totalGroups = 0, noCollision = 0, emptyBuilt = 0, outlinesKeptNonEmpty = 0;
        for (BlockShapeProfile.BlockEntry be : profile.entries()) {
            for (BlockShapeProfile.MetaGroup g : be.metaGroups) {
                totalGroups++;
                if (BlockShapes.hasNoCollision(g)) {
                    noCollision++;
                    if (BlockShapes.buildCollision(g).isEmpty()) emptyBuilt++;
                    if (!BlockShapes.build(g).isEmpty()) outlinesKeptNonEmpty++;
                }
            }
        }
        assertEquals(1900, totalGroups, "corpus meta-group denominator");
        assertEquals(276, noCollision, "meta-groups recording 1.7.10 no-collision");
        assertEquals(276, emptyBuilt, "every one of them must build an EMPTY collision shape");
        assertEquals(276, outlinesKeptNonEmpty, "and every one keeps a non-empty clickable outline");
        // the known walk-through blocks from the live report must be among them
        for (String id : new String[]{"hbm:tile.spikes", "minecraft:fire"}) {
            BlockShapeProfile.BlockEntry e = profile.get(id);
            assertTrue(e != null && BlockShapes.hasNoCollision(e.groupFor(0)), id + " must be no-collision");
        }
    }

    /** Like {@link #group} but with an EXPLICIT {@code "collisionAabb":null} key, the extractor's
     *  faithful record of 1.7.10's func_149668_a returning null (the existing helper OMITS the key
     *  for a null array, which is the different "no data" case). */
    private static BlockShapeProfile.MetaGroup explicitNullCollisionGroup(double[] rawBounds,
            double[][] boxes, boolean isFullCube) {
        StringBuilder sb = new StringBuilder("{\"metas\":[0]");
        if (rawBounds != null) sb.append(",\"rawBounds\":").append(arr(rawBounds));
        sb.append(",\"collisionAabb\":null");
        sb.append(",\"collisionBoxes\":[");
        for (int i = 0; i < boxes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr(boxes[i]));
        }
        sb.append("],\"isFullCube\":").append(isFullCube).append('}');
        BlockShapeProfile p = BlockShapeProfile.parseString(
                "{\"blocks\":[{\"id\":\"hbm:tile.synthetic\",\"className\":\"x\",\"metaGroups\":[" + sb + "]}]}");
        return p.get("hbm:tile.synthetic").metaGroups.get(0);
    }

    private static BlockShapeProfile.MetaGroup group(int[] metas, double[] rawBounds, double[] collisionAabb,
                                                      double[][] boxes, boolean isFullCube) {
        java.util.List<Integer> metaList = new java.util.ArrayList<>();
        for (int m : metas) metaList.add(m);
        java.util.List<double[]> boxList = new java.util.ArrayList<>();
        for (double[] b : boxes) boxList.add(b);
        return newMetaGroup(metaList, rawBounds, collisionAabb, boxList, isFullCube);
    }

    /** Package-private access to the record-shaped constructor - this test lives in the same
     *  package as {@link BlockShapeProfile} on purpose, exactly like every other white-box test here. */
    private static BlockShapeProfile.MetaGroup newMetaGroup(java.util.List<Integer> metas, double[] rawBounds,
            double[] collisionAabb, java.util.List<double[]> collisionBoxes, boolean isFullCube) {
        String json = toJson(metas, rawBounds, collisionAabb, collisionBoxes, isFullCube);
        BlockShapeProfile p = BlockShapeProfile.parseString(
                "{\"blocks\":[{\"id\":\"hbm:tile.synthetic\",\"className\":\"x\",\"metaGroups\":[" + json + "]}]}");
        return p.get("hbm:tile.synthetic").metaGroups.get(0);
    }

    private static String toJson(java.util.List<Integer> metas, double[] rawBounds, double[] collisionAabb,
                                  java.util.List<double[]> collisionBoxes, boolean isFullCube) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"metas\":").append(metas);
        if (rawBounds != null) sb.append(",\"rawBounds\":").append(arr(rawBounds));
        if (collisionAabb != null) sb.append(",\"collisionAabb\":").append(arr(collisionAabb));
        sb.append(",\"collisionBoxes\":[");
        for (int i = 0; i < collisionBoxes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(arr(collisionBoxes.get(i)));
        }
        sb.append(']');
        sb.append(",\"isFullCube\":").append(isFullCube);
        sb.append('}');
        return sb.toString();
    }

    private static String arr(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }
}
