package dev.umb.objbridge;

import dev.umb.objbridge.obj.ObjMesh;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjMeshTest {

    @Test
    void parsesTablesAndOneTriangle() {
        ObjMesh m = ObjMesh.parse("""
                # a comment
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1
                """, "t");
        assertEquals(3, m.vertexCount());
        assertEquals(3, m.uvCount());
        assertEquals(1, m.normalCount());
        assertEquals(1, m.triangleCount());
        assertEquals(1, m.groups().size(), "faces before any g/o land in one default group");
        assertEquals("", m.groups().get(0).name());
        int[] t = m.groups().get(0).triangle(0);
        // 1-based in the file -> 0-based here
        assertEquals(0, t[0]);
        assertEquals(0, t[1]);
        assertEquals(0, t[2]);
        assertEquals(1, t[3]);
        assertEquals(1, t[4]);
        assertEquals(2, t[6]);
        assertTrue(m.errors().isEmpty(), () -> "unexpected errors: " + m.errors());
    }

    @Test
    void supportsAllFourFaceCornerForms() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vn 0 0 1
                g plain
                f 1 2 3
                g uvOnly
                f 1/1 2/1 3/1
                g normalOnly
                f 1//1 2//1 3//1
                g both
                f 1/1/1 2/1/1 3/1/1
                """, "t");
        assertEquals(4, m.groups().size());
        assertEquals(-1, m.groupsByName().get("plain").triangle(0)[1], "no vt index");
        assertEquals(-1, m.groupsByName().get("plain").triangle(0)[2], "no vn index");
        assertEquals(0, m.groupsByName().get("uvOnly").triangle(0)[1]);
        assertEquals(-1, m.groupsByName().get("uvOnly").triangle(0)[2]);
        assertEquals(-1, m.groupsByName().get("normalOnly").triangle(0)[1]);
        assertEquals(0, m.groupsByName().get("normalOnly").triangle(0)[2]);
        assertEquals(0, m.groupsByName().get("both").triangle(0)[1]);
        assertEquals(0, m.groupsByName().get("both").triangle(0)[2]);
    }

    @Test
    void negativeIndicesCountBackFromTheEnd() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0.25 0.75
                f -3 -2 -1
                """, "t");
        int[] t = m.groups().get(0).triangle(0);
        assertEquals(0, t[0]);
        assertEquals(1, t[3]);
        assertEquals(2, t[6]);
    }

    @Test
    void negativeUvIndexResolvesAgainstTheUvTable() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 0.5 0.5
                f 1/-1 2/-2 3/-1
                """, "t");
        int[] t = m.groups().get(0).triangle(0);
        assertEquals(1, t[1], "-1 == last vt");
        assertEquals(0, t[4], "-2 == first of two vt");
        assertEquals(0.5f, m.u(1), 1e-6);
    }

    @Test
    void fanTriangulatesQuadsAndNgons() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                v -1 0.5 0
                g quad
                f 1 2 3 4
                g pentagon
                f 1 2 3 4 5
                """, "t");
        assertEquals(2, m.groupsByName().get("quad").triangleCount(), "quad -> 2 triangles");
        assertEquals(3, m.groupsByName().get("pentagon").triangleCount(), "pentagon -> 3 triangles");
        // fan is (0, i, i+1)
        int[] a = m.groupsByName().get("quad").triangle(0);
        int[] b = m.groupsByName().get("quad").triangle(1);
        assertEquals(0, a[0]);
        assertEquals(1, a[3]);
        assertEquals(2, a[6]);
        assertEquals(0, b[0]);
        assertEquals(2, b[3]);
        assertEquals(3, b[6]);
    }

    @Test
    void ignoresMtllibAndUsemtl() {
        ObjMesh m = ObjMesh.parse("""
                mtllib nothing.mtl
                usemtl Material
                s off
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """, "t");
        assertTrue(m.errors().isEmpty(), () -> "mtllib/usemtl/s must not be errors: " + m.errors());
        assertEquals(1, m.triangleCount());
    }

    @Test
    void oAndGBothOpenGroups() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                o fromO
                f 1 2 3
                g fromG
                f 1 2 3
                """, "t");
        assertEquals(2, m.groups().size());
        assertEquals("fromO", m.groups().get(0).name());
        assertEquals("fromG", m.groups().get(1).name());
        assertSame(m.groups().get(0), m.groupsByName().get("fromO"));
    }

    @Test
    void toleratesGarbageAndKeepsGoing() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v -+.1875++ +.5625++ 6.5625++
                v 1 0 0
                v 0 1 0
                f 1 3 4
                f 1 2
                """, "t");
        assertEquals(3, m.vertexCount(), "the unparseable v line is dropped, not fatal");
        assertEquals(1, m.triangleCount(), "the 2-vertex face is dropped");
        assertEquals(2, m.errors().size(), () -> String.valueOf(m.errors()));
        assertTrue(m.errors().get(0).contains("line 2"));
    }

    @Test
    void boundsCoverOnlyReferencedVertices() {
        ObjMesh m = ObjMesh.parse("""
                v 0 0 0
                v 2 0 0
                v 0 4 0
                v 100 100 100
                f 1 2 3
                """, "t");
        float[] b = m.bounds();
        assertEquals(0f, b[0], 1e-6);
        assertEquals(0f, b[1], 1e-6);
        assertEquals(2f, b[3], 1e-6, "the unreferenced v 100 100 100 must not widen the box");
        assertEquals(4f, b[4], 1e-6);
    }

    @Test
    void parsesTheRealMinigunObjWithTheCountsTheRenderMapRecorded() throws Exception {
        Path p = Paths.get("research/out/legacy/hbm-assets/assets/hbm/models/weapons/minigun.obj");
        if (!Files.isRegularFile(p)) return;   // asset tree absent: nothing to assert
        ObjMesh m = ObjMesh.parse(p);
        // numbers straight out of research/out/legacy/rendermap/hbm-render-map.json ["models"]
        assertEquals(1620, m.vertexCount());
        assertEquals(2528, m.uvCount());
        assertEquals(183, m.normalCount());
        assertEquals(2928, m.triangleCount());
        assertEquals(4, m.groups().size());
        assertEquals("GunDual", m.groups().get(0).name());
        assertEquals("Grip", m.groups().get(1).name());
        assertEquals("Barrels", m.groups().get(2).name());
        assertEquals("Gun", m.groups().get(3).name());
        assertEquals(594, m.groupsByName().get("GunDual").triangleCount());
        assertEquals(52, m.groupsByName().get("Grip").triangleCount());
        assertEquals(1688, m.groupsByName().get("Barrels").triangleCount());
        assertEquals(594, m.groupsByName().get("Gun").triangleCount());
        float[] b = m.bounds();
        assertEquals(-2.875f, b[0], 1e-4);
        assertEquals(-4.0f, b[1], 1e-4);
        assertEquals(-12.0f, b[2], 1e-4);
        assertEquals(5.0f, b[3], 1e-4);
        assertEquals(4.0f, b[4], 1e-4);
        assertEquals(12.0f, b[5], 1e-4);
        assertTrue(m.hasUvs());
        assertTrue(m.errors().isEmpty(), () -> String.valueOf(m.errors()));
    }

    @Test
    void everyShippedObjParsesWithoutAnExplosion() throws Exception {
        Path root = Paths.get("research/out/legacy/hbm-assets/assets/hbm/models");
        if (!Files.isDirectory(root)) return;
        int files = 0, tris = 0, withErrors = 0;
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().toLowerCase().endsWith(".obj")).toList()) {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (name.equals(".obj")) continue;   // the known corrupt orphan
                ObjMesh m = ObjMesh.parse(p);
                assertNotNull(m);
                files++;
                tris += m.triangleCount();
                if (!m.errors().isEmpty()) withErrors++;
            }
        }
        assertEquals(506, files, "507 OBJs ship, one is the corrupt models/weapons/.obj orphan");
        // the render map counted 408315 faces of which 62 are quads -> 408253 + 62*2 triangles,
        // minus the 25 unparseable faces of the corrupt orphan we skip
        assertTrue(tris > 400_000, "expected >400k triangles across the mod, got " + tris);
        assertEquals(0, withErrors, "no shipped OBJ should produce a parse error");
        assertFalse(files == 0);
    }
}
