package dev.umb.rendermap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObjParserTest {

    private static ObjModel parse(String text) {
        return ObjParser.parse("hbm:models/test.obj", "assets/hbm/models/test.obj",
                text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void countsVerticesUvsNormalsAndFaceArity() {
        ObjModel m = parse(String.join("\n",
                "# a comment",
                "mtllib test.mtl",
                "v 0 0 0", "v 1 0 0", "v 1 1 0", "v 0 1 0", "v 0 0 1",
                "vt 0 0", "vt 1 0", "vt 1 1",
                "vn 0 0 1",
                "usemtl steel",
                "g Body",
                "f 1/1/1 2/2/1 3/3/1",              // triangle
                "f 1/1/1 2/2/1 3/3/1 4/3/1",        // quad
                "f 1 2 3 4 5"));                    // n-gon, no uv/normal indices
        assertEquals(5, m.vertices);
        assertEquals(3, m.uvs);
        assertEquals(1, m.normals);
        assertEquals(3, m.faces);
        assertEquals(1, m.triangles);
        assertEquals(1, m.quads);
        assertEquals(1, m.ngons);
        assertTrue(m.hasUvs);
        assertTrue(m.hasNormals);
        assertEquals(java.util.List.of("test.mtl"), m.mtllibs);
        assertEquals(java.util.List.of("steel"), m.usemtls);
        assertTrue(m.parseErrors.isEmpty(), () -> "unexpected errors: " + m.parseErrors);
    }

    @Test
    void recordsGroupsInDeclarationOrderWithFaceCounts() {
        ObjModel m = parse(String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 1 1 0",
                "g Gun",
                "f 1 2 3",
                "f 1 2 3",
                "g Magazine",
                "f 1 2 3",
                "o Bullet",
                "f 1 2 3",
                "f 1 2 3",
                "f 1 2 3"));
        assertEquals(3, m.groups.size());
        assertEquals("Gun", m.groups.get(0).name);
        assertEquals("Magazine", m.groups.get(1).name);
        assertEquals("Bullet", m.groups.get(2).name);
        assertEquals(0, m.groups.get(0).order);
        assertEquals(2, m.groups.get(2).order);
        Map<String, Integer> counts = m.groupFaceCounts();
        assertEquals(2, counts.get("Gun"));
        assertEquals(1, counts.get("Magazine"));
        assertEquals(3, counts.get("Bullet"));
    }

    @Test
    void facesBeforeAnyGroupLandInADefaultGroup() {
        ObjModel m = parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nf 1 2 3\n");
        assertEquals(1, m.groups.size());
        assertEquals("<default>", m.groups.get(0).name);
        assertEquals(1, m.groups.get(0).faces);
    }

    @Test
    void computesBoundingBox() {
        ObjModel m = parse("v -1 -2 -3\nv 4 5 6\nv 0 0 0\n");
        assertArrayEquals(new double[]{-1, -2, -3}, m.bboxMin, 1e-9);
        assertArrayEquals(new double[]{4, 5, 6}, m.bboxMax, 1e-9);
    }

    @Test
    void noBoundingBoxWhenThereAreNoVertices() {
        ObjModel m = parse("g Empty\n");
        assertNull(m.bboxMin);
        assertNull(m.bboxMax);
        assertEquals(0, m.vertices);
    }

    @Test
    void detectsMissingUvsFromFaceIndices() {
        ObjModel m = parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nvn 0 0 1\nf 1//1 2//1 3//1\n");
        assertFalse(m.hasUvs);
        assertTrue(m.hasNormals);
    }

    @Test
    void malformedLinesAreRecordedNotThrown() {
        ObjModel m = parse(String.join("\n",
                "v 0 0",              // too few components
                "v x y z",            // unparseable
                "f 1 2",              // degenerate face
                "wibble 1 2 3"));     // unknown keyword
        assertEquals(4, m.parseErrors.size(), () -> String.valueOf(m.parseErrors));
        assertTrue(m.parseErrors.get(0).contains("v with 2 components"));
        assertTrue(m.parseErrors.get(1).contains("unparseable vertex"));
        assertTrue(m.parseErrors.get(2).contains("face with 2 vertices"));
        assertTrue(m.parseErrors.get(3).contains("unknown keyword 'wibble'"));
        assertEquals(0, m.faces);
    }

    @Test
    void toAssetPathSplitsTheNamespace() {
        assertEquals("assets/hbm/models/weapons/darter.obj",
                ResRef.toAssetPath("hbm:models/weapons/darter.obj"));
        assertEquals("assets/minecraft/textures/x.png",
                ResRef.toAssetPath("textures/x.png"));
    }
}
