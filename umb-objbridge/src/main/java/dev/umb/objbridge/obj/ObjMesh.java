package dev.umb.objbridge.obj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pure-Java Wavefront OBJ mesh. No JOML, no net.minecraft - unit-testable off any classpath.
 *
 * <p>Everything is stored flat. Positions/uvs/normals are the file-order {@code v}/{@code vt}/{@code vn}
 * tables; each {@link Group} carries triangle index triples into those tables. n-gons are fan
 * triangulated at parse time, so a {@link Group} only ever holds triangles.
 *
 * <p>{@code mtllib}/{@code usemtl} are ignored on purpose: the HBM jar ships no {@code .mtl} files at
 * all, every texture came from the renderer's {@code bindTexture} call.
 */
public final class ObjMesh {

    /** One {@code g}/{@code o} group. Indices are 0-based into the mesh tables; -1 means "absent". */
    public static final class Group {
        private final String name;
        private final int order;
        /** 3 entries per triangle. */
        final List<int[]> tris = new ArrayList<>();

        Group(String name, int order) {
            this.name = name;
            this.order = order;
        }

        public String name() { return name; }
        public int order() { return order; }
        public int triangleCount() { return tris.size(); }

        /** {@code {v0,t0,n0, v1,t1,n1, v2,t2,n2}} for triangle {@code i}, 0-based, -1 for absent. */
        public int[] triangle(int i) { return tris.get(i); }

        public List<int[]> triangles() { return Collections.unmodifiableList(tris); }
    }

    private final float[] positions;   // 3 per vertex
    private final float[] uvs;         // 2 per uv
    private final float[] normals;     // 3 per normal
    private final List<Group> groups;
    private final Map<String, Group> byName;
    private final List<String> errors;
    private final String debugName;

    private ObjMesh(float[] positions, float[] uvs, float[] normals,
                    List<Group> groups, List<String> errors, String debugName) {
        this.positions = positions;
        this.uvs = uvs;
        this.normals = normals;
        this.groups = List.copyOf(groups);
        this.errors = List.copyOf(errors);
        this.debugName = debugName;
        Map<String, Group> m = new LinkedHashMap<>();
        for (Group g : this.groups) m.putIfAbsent(g.name(), g);
        this.byName = Collections.unmodifiableMap(m);
    }

    public int vertexCount() { return positions.length / 3; }
    public int uvCount() { return uvs.length / 2; }
    public int normalCount() { return normals.length / 3; }
    public List<Group> groups() { return groups; }
    public Map<String, Group> groupsByName() { return byName; }
    public List<String> errors() { return errors; }
    public String debugName() { return debugName; }
    public boolean hasUvs() { return uvs.length > 0; }

    public float px(int i) { return positions[i * 3]; }
    public float py(int i) { return positions[i * 3 + 1]; }
    public float pz(int i) { return positions[i * 3 + 2]; }
    public float u(int i) { return uvs[i * 2]; }
    public float v(int i) { return uvs[i * 2 + 1]; }
    public float nx(int i) { return normals[i * 3]; }
    public float ny(int i) { return normals[i * 3 + 1]; }
    public float nz(int i) { return normals[i * 3 + 2]; }

    public int triangleCount() {
        int n = 0;
        for (Group g : groups) n += g.triangleCount();
        return n;
    }

    /**
     * Axis-aligned bounds over the vertices actually referenced by {@code selected}, as
     * {@code {minX,minY,minZ,maxX,maxY,maxZ}}. Returns a zero box when nothing is referenced.
     */
    public float[] bounds(List<Group> selected) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (Group g : selected) {
            for (int[] t : g.tris) {
                for (int k = 0; k < 9; k += 3) {
                    int vi = t[k];
                    if (vi < 0 || vi >= vertexCount()) continue;
                    float x = px(vi), y = py(vi), z = pz(vi);
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (z > maxZ) maxZ = z;
                    any = true;
                }
            }
        }
        if (!any) return new float[] {0, 0, 0, 0, 0, 0};
        return new float[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    public float[] bounds() { return bounds(groups); }

    // ---------------------------------------------------------------- parsing

    public static ObjMesh parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.getFileName() == null ? file.toString() : file.getFileName().toString());
        }
    }

    public static ObjMesh parse(InputStream in, String debugName) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(r, debugName);
        }
    }

    public static ObjMesh parse(String text, String debugName) {
        try {
            return parse(new BufferedReader(new java.io.StringReader(text)), debugName);
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringReader cannot fail
        }
    }

    private static ObjMesh parse(BufferedReader r, String debugName) throws IOException {
        FloatList pos = new FloatList();
        FloatList uv = new FloatList();
        FloatList nrm = new FloatList();
        List<Group> groups = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Group current = null;
        int lineNo = 0;
        String line;
        while ((line = r.readLine()) != null) {
            lineNo++;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;

            // fast keyword split
            int sp = firstSpace(line);
            String kw = sp < 0 ? line : line.substring(0, sp);
            String rest = sp < 0 ? "" : line.substring(sp + 1).trim();

            try {
                switch (kw) {
                    case "v" -> {
                        float[] f = floats(rest, 3);
                        pos.add(f[0]); pos.add(f[1]); pos.add(f[2]);
                    }
                    case "vt" -> {
                        float[] f = floats(rest, 2);
                        uv.add(f[0]); uv.add(f[1]);
                    }
                    case "vn" -> {
                        float[] f = floats(rest, 3);
                        nrm.add(f[0]); nrm.add(f[1]); nrm.add(f[2]);
                    }
                    case "g", "o" -> {
                        String name = rest;
                        current = new Group(name, groups.size());
                        groups.add(current);
                    }
                    case "f" -> {
                        if (current == null) {
                            current = new Group("", groups.size());
                            groups.add(current);
                        }
                        addFace(current, rest, pos.size() / 3, uv.size() / 2, nrm.size() / 3);
                    }
                    // deliberately ignored
                    case "mtllib", "usemtl", "s", "vp", "l", "p", "cstype", "deg", "bmat", "step",
                         "curv", "curv2", "surf", "parm", "trim", "hole", "scrv", "sp", "end",
                         "con", "mg", "bevel", "c_interp", "d_interp", "lod", "shadow_obj",
                         "trace_obj", "ctech", "stech" -> { }
                    default -> {
                        if (errors.size() < 32) errors.add("line " + lineNo + ": unknown keyword '" + kw + "'");
                    }
                }
            } catch (RuntimeException e) {
                if (errors.size() < 32) {
                    errors.add("line " + lineNo + ": " + e.getMessage() + " [" + line + "]");
                }
            }
        }
        return new ObjMesh(pos.toArray(), uv.toArray(), nrm.toArray(), groups, errors, debugName);
    }

    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private static float[] floats(String rest, int want) {
        String[] parts = split(rest);
        if (parts.length < want) throw new IllegalArgumentException("expected " + want + " numbers");
        float[] out = new float[want];
        for (int i = 0; i < want; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }

    private static String[] split(String s) {
        List<String> out = new ArrayList<>(4);
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            int start = i;
            while (i < n && s.charAt(i) != ' ' && s.charAt(i) != '\t') i++;
            if (i > start) out.add(s.substring(start, i));
        }
        return out.toArray(new String[0]);
    }

    /** Parses one {@code f} line and fan-triangulates it into {@code g}. */
    private static void addFace(Group g, String rest, int vCount, int tCount, int nCount) {
        String[] verts = split(rest);
        if (verts.length < 3) throw new IllegalArgumentException("face with " + verts.length + " vertices");
        int[][] corners = new int[verts.length][];
        for (int i = 0; i < verts.length; i++) {
            corners[i] = corner(verts[i], vCount, tCount, nCount);
        }
        // fan: (0, i, i+1)
        for (int i = 1; i + 1 < corners.length; i++) {
            int[] t = new int[9];
            System.arraycopy(corners[0], 0, t, 0, 3);
            System.arraycopy(corners[i], 0, t, 3, 3);
            System.arraycopy(corners[i + 1], 0, t, 6, 3);
            g.tris.add(t);
        }
    }

    /** {@code v}, {@code v/vt}, {@code v//vn} or {@code v/vt/vn} -> 0-based {v,t,n}, -1 when absent. */
    private static int[] corner(String tok, int vCount, int tCount, int nCount) {
        int s1 = tok.indexOf('/');
        String vs, ts = "", ns = "";
        if (s1 < 0) {
            vs = tok;
        } else {
            vs = tok.substring(0, s1);
            int s2 = tok.indexOf('/', s1 + 1);
            if (s2 < 0) {
                ts = tok.substring(s1 + 1);
            } else {
                ts = tok.substring(s1 + 1, s2);
                ns = tok.substring(s2 + 1);
            }
        }
        return new int[] {
                resolve(vs, vCount, "vertex"),
                ts.isEmpty() ? -1 : resolve(ts, tCount, "uv"),
                ns.isEmpty() ? -1 : resolve(ns, nCount, "normal")
        };
    }

    /** 1-based positive, or negative-from-end (-1 == last). Out of range -> -1 (tolerated). */
    private static int resolve(String s, int count, String what) {
        int raw;
        try {
            raw = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad " + what + " index '" + s + "'");
        }
        int idx;
        if (raw > 0) idx = raw - 1;
        else if (raw < 0) idx = count + raw;
        else throw new IllegalArgumentException(what + " index 0 is not legal in OBJ");
        return (idx >= 0 && idx < count) ? idx : -1;
    }

    /** Trivial growable float array; avoids boxing 400k floats through ArrayList&lt;Float&gt;. */
    private static final class FloatList {
        private float[] a = new float[64];
        private int n;

        void add(float f) {
            if (n == a.length) {
                float[] b = new float[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = f;
        }

        int size() { return n; }

        float[] toArray() {
            float[] out = new float[n];
            System.arraycopy(a, 0, out, 0, n);
            return out;
        }
    }
}
