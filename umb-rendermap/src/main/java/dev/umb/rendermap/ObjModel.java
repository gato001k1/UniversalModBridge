package dev.umb.rendermap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed inventory record for one Wavefront .obj file. */
public class ObjModel {
    public String key;            // "hbm:models/turrets/turret_chekhov.obj"
    public String path;           // "assets/hbm/models/turrets/turret_chekhov.obj"
    public long bytes;
    public int vertices;
    public int uvs;
    public int normals;
    public int faces;
    public int triangles;
    public int quads;
    public int ngons;
    public boolean hasUvs;
    public boolean hasNormals;
    public List<Group> groups = new ArrayList<>();
    public double[] bboxMin;      // null if no vertices
    public double[] bboxMax;
    public List<String> mtllibs = new ArrayList<>();
    public List<String> usemtls = new ArrayList<>();
    public List<String> parseErrors = new ArrayList<>();

    public static class Group {
        public String name;
        public int faces;
        public int order;
        public Group(String name, int order) { this.name = name; this.order = order; }
    }

    /** Group face counts keyed by name, preserving declaration order. */
    public Map<String, Integer> groupFaceCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Group g : groups) m.merge(g.name, g.faces, Integer::sum);
        return m;
    }
}
