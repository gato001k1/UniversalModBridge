package dev.umb.objbridge;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.bake.QuadGeom;
import dev.umb.objbridge.obj.ObjMesh;
import net.minecraft.client.model.geom.builders.UVPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshBakerTest {

    /** A 24x8x8 box-ish mesh well off the origin, so any fit bug shows up loudly. */
    private static ObjMesh box() {
        return ObjMesh.parse("""
                v 10 20 30
                v 34 20 30
                v 10 28 30
                v 34 28 38
                vt 0 0
                vt 1 0
                vt 0 1
                f 1/1 2/2 3/3
                f 2/2 4/1 3/3
                """, "box");
    }

    @Test
    void itemFitCentresAllThreeAxesOnHalf() {
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.ITEM);
        float[] e = extents(r);
        // largest extent is x: 34-10 = 24 -> scale 1/24; y and z span 8 -> 8/24 wide, centred
        assertEquals(0.0f, e[0], 1e-5, "minX");
        assertEquals(1.0f, e[3], 1e-5, "maxX == the fit size");
        assertEquals(0.5f, (e[1] + e[4]) * 0.5f, 1e-5, "y centred on 0.5");
        assertEquals(0.5f, (e[2] + e[5]) * 0.5f, 1e-5, "z centred on 0.5");
        assertEquals(0.5f, (e[0] + e[3]) * 0.5f, 1e-5, "x centred on 0.5");
        assertEquals(1.0f / 24.0f, r.scale(), 1e-6);
    }

    @Test
    void blockFitRestsOnZeroAndCentresXZ() {
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.BLOCK);
        float[] e = extents(r);
        assertEquals(0.0f, e[1], 1e-5, "minY == 0, the mesh sits on the block floor");
        assertEquals(8.0f / 24.0f, e[4], 1e-5, "maxY == the y extent scaled");
        assertEquals(0.5f, (e[0] + e[3]) * 0.5f, 1e-5, "x centred");
        assertEquals(0.5f, (e[2] + e[5]) * 0.5f, 1e-5, "z centred");
    }

    @Test
    void aConfigurableFitScalesTheWholeMesh() {
        MeshBaker.Result half = MeshBaker.bake(box(), new Fit(false, 0.5f));
        float[] e = extents(half);
        assertEquals(0.25f, e[0], 1e-5);
        assertEquals(0.75f, e[3], 1e-5, "largest extent is now 0.5 wide, still centred on 0.5");
        assertEquals(0.5f / 24.0f, half.scale(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> new Fit(false, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new Fit(false, Float.NaN));
    }

    @Test
    void everyQuadIsADegenerateTriangle() {
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.ITEM);
        assertEquals(2, r.quads().size());
        for (QuadGeom q : r.quads()) {
            assertEquals(q.x(2), q.x(3), 0f, "4th vertex repeats the 3rd");
            assertEquals(q.y(2), q.y(3), 0f);
            assertEquals(q.z(2), q.z(3), 0f);
            assertEquals(q.u(2), q.u(3), 0f, "and so do its UVs");
            assertEquals(q.v(2), q.v(3), 0f);
        }
    }

    @Test
    void vIsFlippedBecauseObjCountsUpFromTheBottom() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0.25 0.0
                vt 0.75 1.0
                vt 0.10 0.4
                f 1/1 2/2 3/3
                """, "uv");
        QuadGeom q = MeshBaker.bake(m, Fit.ITEM).quads().get(0);
        assertEquals(0.25f, q.u(0), 1e-6, "u passes through untouched");
        assertEquals(1.0f, q.v(0), 1e-6, "OBJ v=0 (image bottom) -> sprite v=1");
        assertEquals(0.75f, q.u(1), 1e-6);
        assertEquals(0.0f, q.v(1), 1e-6, "OBJ v=1 (image top) -> sprite v=0");
        assertEquals(0.60f, q.v(2), 1e-6, "OBJ v=0.4 -> 0.6");
    }

    @Test
    void meshWithNoUvsGetsAPlanarProjectionInsideZeroToOne() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 4 0 0
                v 0 0 4
                f 1 2 3
                """, "noUv");
        MeshBaker.Result r = MeshBaker.bake(m, Fit.ITEM);
        assertTrue(r.projectedUvs(), "the result must report that it projected");
        QuadGeom q = r.quads().get(0);
        for (int i = 0; i < 4; i++) {
            assertTrue(q.u(i) >= 0f && q.u(i) <= 1f, "u in range: " + q.u(i));
            assertTrue(q.v(i) >= 0f && q.v(i) <= 1f, "v in range: " + q.v(i));
        }
        // an up-facing triangle projects on XZ, so the two non-degenerate corners must differ in u
        assertTrue(Math.abs(q.u(0) - q.u(1)) > 0.5f, "the projection must actually spread the UVs");
    }

    @Test
    void faceWithAMissingUvIndexAlsoFallsBackToProjection() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 4 0 0
                v 0 0 4
                vt 0 0
                f 1/1 2 3
                """, "partialUv");
        QuadGeom q = MeshBaker.bake(m, Fit.ITEM).quads().get(0);
        for (int i = 0; i < 4; i++) {
            assertTrue(q.u(i) >= 0f && q.u(i) <= 1f);
            assertTrue(q.v(i) >= 0f && q.v(i) <= 1f);
        }
    }

    @Test
    void namedGroupSelectionPicksOnlyThoseGroups() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 1
                g keep
                f 1 2 3
                g drop
                f 1 2 4
                f 1 3 4
                """, "grouped");
        assertEquals(3, MeshBaker.bake(m, Fit.ITEM).quads().size(), "no selection == every group");
        assertEquals(1, MeshBaker.bake(m, Fit.ITEM, List.of("keep")).quads().size());
        assertEquals(2, MeshBaker.bake(m, Fit.ITEM, List.of("drop")).quads().size());
        assertEquals(3, MeshBaker.bake(m, Fit.ITEM, List.of("nope")).quads().size(),
                "an unknown group name falls back to drawing everything rather than nothing");
    }

    @Test
    void dynamicPartUsesSharedModelAnchor() {
        ObjMesh m = ObjMesh.parse("""
                g left
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                g right
                v 10 0 0
                v 11 0 0
                v 10 1 0
                f 4 5 6
                """, "shared-anchor");
        MeshBaker.Result local = MeshBaker.bake(m, Fit.transformed(true, Fit.IDENTITY),
                List.of("right"));
        MeshBaker.Result shared = MeshBaker.bake(m, Fit.transformed(true, Fit.IDENTITY),
                List.of("right"), List.of("left", "right"));
        float[] localBounds = extents(local);
        float[] sharedBounds = extents(shared);
        float localCx = (localBounds[0] + localBounds[3]) * 0.5f;
        float sharedCx = (sharedBounds[0] + sharedBounds[3]) * 0.5f;
        assertEquals(0.5f, localCx, 1e-5f, "old per-part bake recenters the selected part");
        assertEquals(5.5f, sharedCx, 1e-5f,
                "dynamic parts retain the common authored model frame");
    }

    @Test
    void faceNormalPointsTheRightWay() {
        // counter-clockwise seen from +Z -> normal +Z
        float[] n = MeshBaker.faceNormal(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0});
        assertEquals(0f, n[0], 1e-6);
        assertEquals(0f, n[1], 1e-6);
        assertEquals(1f, n[2], 1e-6);
        assertEquals(1.0f, (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1e-6);
    }

    @Test
    void packUvMatchesMinecraftsOwnUVPair() {
        float[] samples = {0f, 0.5f, 1f, 0.03125f, 0.99999f, 0.123456f};
        for (float u : samples) {
            for (float v : samples) {
                assertEquals(UVPair.pack(u, v), MeshBaker.packUv(u, v),
                        () -> "packUv(" + u + "," + v + ") must equal UVPair.pack");
            }
        }
    }

    @Test
    void anEmptyMeshBakesToNothingWithoutThrowing() {
        ObjMesh m = ObjMesh.parse("# nothing here\n", "empty");
        MeshBaker.Result r = MeshBaker.bake(m, Fit.ITEM);
        assertEquals(0, r.quads().size());
        assertEquals(1.0f, r.scale(), 0f, "a degenerate bbox must not produce an infinite scale");
    }

    // ---------------------------------------------------------------- scale-fix (authored-transform mode)

    @Test
    void identityTransformBakesTheAuthoredCoordinatesDirectlyWithoutNormalising() {
        // box() spans 24x8x8 - the OLD auto-fit behaviour (Fit.BLOCK) squashes that to exactly 1 block
        // wide. Fit.transformed with the identity linear map is what a renderer with NO scale op (the
        // Tsar Bomba / large radar / launch table pattern) should produce, and it must NOT squash it.
        // maxExtent raised well above 24 so the (separately tested) sanity clamp does not interfere.
        Fit transformedIdentity = Fit.transformed(true, Fit.IDENTITY, 1000.0f);
        MeshBaker.Result r = MeshBaker.bake(box(), transformedIdentity);
        float[] e = extents(r);
        assertEquals(24.0f, e[3] - e[0], 1e-4f, "authored 24-wide extent must survive unscaled");
        assertEquals(8.0f, e[4] - e[1], 1e-4f);
        assertEquals(8.0f, e[5] - e[2], 1e-4f);
        assertEquals(1.0f, r.scale(), 1e-6f, "scale must be 1.0 - authored coords ARE block units here");
        assertEquals(0.0f, e[1], 1e-4f, "onGround still rests the mesh on y=0");
        assertFalse(r.clamped());
        assertFalse(r.fellBackToAutoFit());
    }

    @Test
    void aScalingLinearMapMultipliesTheAuthoredExtentDirectly() {
        // row-major 3x3 uniform x2 scale; maxExtent set high so the clamp (tested separately below)
        // does not interfere with this assertion
        float[] doubled = {2, 0, 0, 0, 2, 0, 0, 0, 2};
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.transformed(false, doubled, 1000.0f));
        float[] e = extents(r);
        assertEquals(48.0f, e[3] - e[0], 1e-3f);
        assertEquals(16.0f, e[4] - e[1], 1e-3f);
        assertEquals(16.0f, e[5] - e[2], 1e-3f);
    }

    @Test
    void extentBeyondMaxIsClampedAndReported() {
        // box() is 24 wide; clamp max at 10 must uniformly scale everything down by 10/24
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.transformed(false, Fit.IDENTITY, 10.0f));
        float[] e = extents(r);
        assertEquals(10.0f, e[3] - e[0], 1e-3f, "clamped to exactly maxExtent on the longest axis");
        assertTrue(r.clamped());
        assertFalse(r.fellBackToAutoFit());
        assertEquals(10.0f / 24.0f, r.scale(), 1e-5f);
    }

    @Test
    void withinMaxExtentIsNotClamped() {
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.transformed(false, Fit.IDENTITY, 100.0f));
        assertFalse(r.clamped());
        assertEquals(1.0f, r.scale(), 0f);
    }

    @Test
    void degenerateLinearMapFallsBackToAutoFitInsteadOfDividingByZero() {
        // all-zero linear map collapses the mesh to a point - must fall back to plain auto-fit on the
        // untransformed mesh rather than produce NaN/garbage.
        float[] zero = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.transformed(false, zero));
        assertTrue(r.fellBackToAutoFit());
        assertFalse(r.clamped());
        float[] e = extents(r);
        assertEquals(1.0f, e[3] - e[0], 1e-4f, "fell back to the same auto-fit as Fit.ITEM would give");
        assertEquals(1.0f / 24.0f, r.scale(), 1e-6f);
    }

    // ---------------------------------------------------------------- block-item-in-slot (normalize)

    @Test
    void itemFromWorldTransformShapesProportionsButNormalisesAbsoluteSize() {
        // box() is 24x8x8 raw. A world transform that stretches y by 4x (as if this were a block whose
        // TESR scales it tall) would make the TRUE in-world shape 24x32x8 - i.e. Y becomes the longest
        // axis instead of X. Fit.itemFromWorldTransform must reflect that PROPORTION (Y longest) while
        // still normalising the overall size to fit.size(), unlike Fit.transformed (WORLD/TESR splice
        // mode) which would leave the absolute scale at native world size instead.
        float[] stretchY = {1, 0, 0, 0, 4, 0, 0, 0, 1};
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.itemFromWorldTransform(stretchY, 1.0f));
        float[] e = extents(r);
        float dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
        assertEquals(1.0f, dy, 1e-4f, "the now-longest (stretched) axis must be normalised to exactly size()");
        assertTrue(dy > dx, "y must now be the longest axis (24*1 vs 8*4=32), not x (the raw mesh's longest)");
        assertEquals(0.5f, (e[1] + e[4]) * 0.5f, 1e-5f, "items centre on 0.5, not rest on y=0");
    }

    @Test
    void itemFromWorldTransformWithIdentityLinearIsThePlainAutoFitShape() {
        // linear=IDENTITY means "no world transform resolved" (RenderFit's own meaning) - the
        // proportions must degrade to exactly the same shape plain Fit.ITEM auto-fit would give.
        MeshBaker.Result normalized = MeshBaker.bake(box(), Fit.itemFromWorldTransform(Fit.IDENTITY, 1.0f));
        MeshBaker.Result plainAuto = MeshBaker.bake(box(), Fit.ITEM);
        float[] a = extents(normalized), b = extents(plainAuto);
        for (int i = 0; i < 6; i++) assertEquals(b[i], a[i], 1e-5f, "component " + i);
    }

    @Test
    void itemFromWorldTransformIgnoresTheMaxExtentClamp() {
        // normalize mode always fits to size() - the clamp (which only matters for the un-normalised
        // WORLD/TESR splice mode) must never engage here, however small maxExtent is.
        float[] doubled = {2, 0, 0, 0, 2, 0, 0, 0, 2};
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.itemFromWorldTransform(doubled, 1.0f, 0.001f));
        float[] e = extents(r);
        assertEquals(1.0f, e[3] - e[0], 1e-4f, "normalised to size() regardless of the tiny maxExtent");
        assertFalse(r.clamped());
    }

    @Test
    void itemFromWorldTransformDegenerateLinearFallsBackToAutoFit() {
        float[] zero = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        MeshBaker.Result r = MeshBaker.bake(box(), Fit.itemFromWorldTransform(zero, 1.0f));
        assertTrue(r.fellBackToAutoFit());
        float[] e = extents(r);
        assertEquals(1.0f, e[3] - e[0], 1e-4f);
    }

    @Test
    void legacyTwoArgConstructorStillMeansPlainAutoFit() {
        Fit f = new Fit(true, 2.0f);
        assertTrue(f.auto());
        assertEquals(2.0f, f.size(), 0f);
        assertEquals(Fit.DEFAULT_MAX_EXTENT, f.maxExtent(), 0f);
        assertEquals(Fit.IDENTITY.length, f.linear().length);
        assertEquals(1f, f.linear()[0], 0f);
    }

    @Test
    void fiveArgConstructorAndTransformedFactoryStillMeanNormalizeFalse() {
        Fit f = new Fit(true, 1.0f, false, Fit.IDENTITY, 16f);
        assertFalse(f.normalize(), "the pre-existing 5-arg shape must default to WORLD/TESR-splice mode");
        assertFalse(Fit.transformed(true, Fit.IDENTITY).normalize());
        assertTrue(Fit.itemFromWorldTransform(Fit.IDENTITY, 1.0f).normalize());
    }

    @Test
    void fitConstructorRejectsBadLinearAndMaxExtent() {
        assertThrows(IllegalArgumentException.class, () -> new Fit(true, 1.0f, false, new float[] {1, 2, 3}, 16f));
        assertThrows(IllegalArgumentException.class, () -> new Fit(true, 1.0f, false, Fit.IDENTITY, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Fit(true, 1.0f, false, Fit.IDENTITY, Float.NaN));
    }

    private static float[] extents(MeshBaker.Result r) {
        float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (QuadGeom q : r.quads()) {
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
