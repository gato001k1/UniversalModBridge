package dev.umb.rendermap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal, tolerant Wavefront OBJ reader used only to build an inventory
 * (counts, group structure, bounding box, material/texture references).
 * It never throws on malformed input: every problem is recorded in
 * {@link ObjModel#parseErrors} and parsing continues.
 */
public final class ObjParser {

    private ObjParser() {}

    public static ObjModel parse(String key, String path, byte[] data) {
        ObjModel m = new ObjModel();
        m.key = key;
        m.path = path;
        m.bytes = data.length;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        ObjModel.Group current = null;
        int lineNo = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tok = line.split("\\s+");
                String kw = tok[0];
                switch (kw) {
                    case "v": {
                        m.vertices++;
                        if (tok.length < 4) { err(m, lineNo, "v with " + (tok.length - 1) + " components"); break; }
                        try {
                            double x = Double.parseDouble(tok[1]);
                            double y = Double.parseDouble(tok[2]);
                            double z = Double.parseDouble(tok[3]);
                            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z;
                            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z;
                        } catch (NumberFormatException e) {
                            err(m, lineNo, "unparseable vertex: " + line);
                        }
                        break;
                    }
                    case "vt": m.uvs++; break;
                    case "vn": m.normals++; break;
                    case "f": {
                        int n = tok.length - 1;
                        if (n < 3) { err(m, lineNo, "face with " + n + " vertices"); break; }
                        m.faces++;
                        if (n == 3) m.triangles++;
                        else if (n == 4) m.quads++;
                        else m.ngons++;
                        // detect whether the face references uv / normal indices
                        for (int i = 1; i < tok.length; i++) {
                            String[] parts = tok[i].split("/", -1);
                            if (parts.length >= 2 && !parts[1].isEmpty()) m.hasUvs = true;
                            if (parts.length >= 3 && !parts[2].isEmpty()) m.hasNormals = true;
                        }
                        if (current == null) {
                            current = new ObjModel.Group("<default>", m.groups.size());
                            m.groups.add(current);
                        }
                        current.faces++;
                        break;
                    }
                    case "g":
                    case "o": {
                        String name = tok.length > 1 ? join(tok, 1) : "<unnamed>";
                        current = new ObjModel.Group(name, m.groups.size());
                        m.groups.add(current);
                        break;
                    }
                    case "usemtl": {
                        String name = tok.length > 1 ? join(tok, 1) : "<none>";
                        if (!m.usemtls.contains(name)) m.usemtls.add(name);
                        break;
                    }
                    case "mtllib": {
                        for (int i = 1; i < tok.length; i++)
                            if (!m.mtllibs.contains(tok[i])) m.mtllibs.add(tok[i]);
                        break;
                    }
                    case "s":
                    case "vp":
                    case "l":
                    case "p":
                        break; // benign, ignored
                    default:
                        err(m, lineNo, "unknown keyword '" + kw + "'");
                }
            }
        } catch (IOException e) {
            err(m, lineNo, "IOException: " + e);
        }
        if (m.uvs > 0) m.hasUvs = true;
        if (m.normals > 0) m.hasNormals = true;
        if (m.vertices > 0 && minX != Double.POSITIVE_INFINITY) {
            m.bboxMin = new double[]{minX, minY, minZ};
            m.bboxMax = new double[]{maxX, maxY, maxZ};
        }
        return m;
    }

    public static ObjModel parse(String key, String path, InputStream in) throws IOException {
        return parse(key, path, in.readAllBytes());
    }

    private static String join(String[] tok, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tok.length; i++) { if (i > from) sb.append(' '); sb.append(tok[i]); }
        return sb.toString();
    }

    private static void err(ObjModel m, int line, String msg) {
        if (m.parseErrors.size() < 25) m.parseErrors.add("line " + line + ": " + msg);
    }
}
