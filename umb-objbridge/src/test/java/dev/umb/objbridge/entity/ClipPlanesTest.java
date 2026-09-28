package dev.umb.objbridge.entity;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.bake.QuadGeom;
import dev.umb.objbridge.obj.ObjMesh;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door-live follow-up (GL-state ops): clip-plane math plus the bake-map mirror.
 * Pure geometry (synthetic meshes, no atlas), so failures mean wrong math, never
 * missing assets.
 */
class ClipPlanesTest {

    private static double[][] rect(double x0, double y0, double x1, double y1) {
        return new double[][]{{x0, y0, 0}, {x1, y0, 0}, {x1, y1, 0}, {x0, y1, 0}};
    }

    private static double[][] nov() {
        return new double[][]{{0, 0}, {0, 0}, {0, 0}, {0, 0}};
    }

    @Test
    void fullyInsidePassesThrough() {
        ClipPlanes.Poly p = ClipPlanes.clip(rect(0, 0, 1, 1), nov(),
                List.of(new double[]{1, 0, 0, 0}));
        assertEquals(4, p.pos.length);
    }

    @Test
    void fullyOutsideVanishes() {
        ClipPlanes.Poly p = ClipPlanes.clip(rect(0, 0, 1, 1), nov(),
                List.of(new double[]{-1, 0, 0, -0.5}));
        assertEquals(0, p.pos.length);
    }

    @Test
    void straddleCutsExactVerts() {
        // Unit square kept where x >= 0.25: cut edge interpolates exactly.
        ClipPlanes.Poly p = ClipPlanes.clip(rect(0, 0, 1, 1), nov(),
                List.of(new double[]{1, 0, 0, -0.25}));
        assertEquals(4, p.pos.length);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (double[] v : p.pos) {
            minX = Math.min(minX, v[0]);
            maxX = Math.max(maxX, v[0]);
        }
        assertEquals(0.25, minX, 1e-9);
        assertEquals(1.0, maxX, 1e-9);
    }

    @Test
    void payloadLerpsAlongCutEdge() {
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        ClipPlanes.Poly p = ClipPlanes.clip(rect(0, 0, 1, 1), uv,
                List.of(new double[]{1, 0, 0, -0.25}));
        assertEquals(4, p.pos.length);
        for (int i = 0; i < 4; i++) {
            // Affine UVs: u tracks x exactly on this rect.
            assertEquals(p.pos[i][0], p.uv[i][0], 1e-9, "u lerps with x at vert " + i);
        }
    }

    @Test
    void toLocalInvertsSpecFrame() {
        // Spec frame = rotY(90); total adds a draw-space slide of -3 on x.
        // p_local = T^T * p with T = pre^-1 * all (entry cancels; verified against
        // eye-space clipping: plane (MVcall)^-T p, verts (MVdraw) v).
        Matrix4f pre = new Matrix4f().rotateY((float) Math.toRadians(90));
        Matrix4f all = new Matrix4f(pre).translate(-3f, 0f, 0f);
        double[] local = ClipPlanes.toLocal(pre, all, new double[]{1, 0, 0, 3.4375});
        assertNotNull(local);
        assertEquals(1.0, local[0], 1e-5);
        assertEquals(0.0, local[1], 1e-5);
        assertEquals(0.0, local[2], 1e-5);
        assertEquals(0.4375, local[3], 1e-4);
    }

    @Test
    void toLocalRejectsSingular() {
        Matrix4f pre = new Matrix4f().scale(0f, 1f, 1f);
        assertNull(ClipPlanes.toLocal(pre, new Matrix4f(), new double[]{1, 0, 0, 0}));
    }

    /** Two authored halves; the fit map must reproduce the bake's fitted verts exactly. */
    private static ObjMesh halves() {
        return ObjMesh.parse("""
                o Left
                v -3 0 0
                v 0 0 0
                v -3 4 0
                v 0 4 0
                vt 0 0
                vt 1 0
                vt 0 1
                vt 1 1
                f 1/1 2/2 4/4
                f 1/1 4/4 3/3
                o Right
                v 0 0 0
                v 3 0 0
                v 0 4 0
                v 3 4 0
                f 5/1 6/2 8/4
                f 5/1 8/4 7/3
                """, "halves");
    }

    @Test
    void fitMapMirrorsBakeExactly() {
        ObjMesh mesh = halves();
        Fit fit = Fit.transformed(true, Fit.IDENTITY);
        MeshBaker.Result r = MeshBaker.bake(mesh, fit, List.of("Left", "Right"));
        ClipPlanes.FitMap fm = ClipPlanes.fitMap(mesh, List.of("Left", "Right"), fit);
        // Invert the map and check every baked vertex lands back on authored coords.
        Matrix4f a = new Matrix4f()
                .translation((float) fm.offset[0], (float) fm.offset[1], (float) fm.offset[2])
                .scale((float) fm.scale)
                .mul(new Matrix4f(
                        fm.lin[0], fm.lin[3], fm.lin[6], 0f,
                        fm.lin[1], fm.lin[4], fm.lin[7], 0f,
                        fm.lin[2], fm.lin[5], fm.lin[8], 0f,
                        0f, 0f, 0f, 1f))
                .translate((float) -fm.centre[0], (float) -fm.centre[1], (float) -fm.centre[2]);
        org.joml.Matrix4f inv = new Matrix4f(a).invert();
        java.util.Set<String> got = new java.util.HashSet<>();
        for (QuadGeom g : r.quads()) {
            for (int i = 0; i < 3; i++) {
                org.joml.Vector4f v = new org.joml.Vector4f(g.x(i), g.y(i), g.z(i), 1f);
                inv.transform(v);
                got.add(Math.round(v.x * 1e3) + "," + Math.round(v.y * 1e3)
                        + "," + Math.round(v.z * 1e3));
            }
        }
        // Every authored corner must round-trip exactly (map is the bake's inverse).
        for (String c : new String[]{"-3000,0,0", "0,0,0", "3000,0,0",
                "-3000,4000,0", "0,4000,0", "3000,4000,0"}) {
            assertTrue(got.contains(c), "authored corner recovered: " + c);
        }
    }

    @Test
    void doorSlideKeepsSliverAtOpen() {
        // The HBM door shape, end to end: rest slide 0 keeps the whole left panel,
        // open slide -3 keeps only the housing sliver (eye-space verified).
        Matrix4f pre = new Matrix4f().rotateY((float) Math.toRadians(90));
        double[] spec = {1, 0, 0, 3.4375};
        Matrix4f rest = new Matrix4f(pre).translate(0f, 0f, 0f);
        double[] restLocal = ClipPlanes.toLocal(pre, rest, spec);
        assertNotNull(restLocal);
        // Panel spans mesh x -3.37..0.19, all inside x >= -3.4375.
        ClipPlanes.Poly kept = ClipPlanes.clip(
                new double[][]{{-3.37, 0, 0}, {0.19, 0, 0}, {0.19, 4, 0}, {-3.37, 4, 0}},
                nov(), List.of(restLocal));
        assertEquals(4, kept.pos.length, "rest keeps the whole panel");
        Matrix4f open = new Matrix4f(pre).translate(-3f, 0f, 0f);
        double[] openLocal = ClipPlanes.toLocal(pre, open, spec);
        assertNotNull(openLocal);
        ClipPlanes.Poly sliver = ClipPlanes.clip(
                new double[][]{{-3.37, 0, 0}, {0.19, 0, 0}, {0.19, 4, 0}, {-3.37, 4, 0}},
                nov(), List.of(openLocal));
        assertEquals(4, sliver.pos.length, "open keeps the inner sliver quad");
        double minX = Double.MAX_VALUE;
        for (double[] v : sliver.pos) minX = Math.min(minX, v[0]);
        assertEquals(-0.4375, minX, 1e-4, "cut edge at the housing plane");
    }
}
