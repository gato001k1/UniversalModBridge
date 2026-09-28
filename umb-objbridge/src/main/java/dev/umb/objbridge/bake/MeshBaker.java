package dev.umb.objbridge.bake;

import dev.umb.objbridge.obj.ObjMesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesh -> unit quads. Pure Java (no JOML, no net.minecraft) so the whole geometry pass is assertable
 * headless; {@link dev.umb.objbridge.bake.QuadBaker} does the final hop into {@code BakedQuad}.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li>picks the groups to draw (all of them by default - the 1.7.10 renderers drew named groups
 *       selectively at draw time and we have no draw-time state, so v1 draws everything),</li>
 *   <li>computes one uniform auto-fit transform from the selected groups' bbox ({@link Fit}),</li>
 *   <li>emits one {@link QuadGeom} per triangle with the 3rd corner repeated,</li>
 *   <li>maps {@code vt} through {@code v' = 1 - v} because OBJ's V axis points up from the bottom of
 *       the image while Minecraft's sprite V axis points down from the top,</li>
 *   <li>for the 8 UV-less OBJs, planar-projects UVs on the two axes perpendicular to the face's
 *       dominant normal axis.</li>
 * </ol>
 */
public final class MeshBaker {

    private MeshBaker() { }

    /** Sentinel bounds meaning "nothing referenced" - see {@link ObjMesh#bounds}. */
    private static final float EPS = 1.0e-6f;

    /**
     * Result of a bake, with the numbers a report wants.
     *
     * @param clamped         true when the transformed extent exceeded the {@link Fit#maxExtent()}
     *                        sanity limit and was uniformly scaled back down to fit it (reported, never
     *                        silent - see {@link #bake(ObjMesh, Fit, List)})
     * @param fellBackToAutoFit true when {@code fit.auto()} was false but the transformed bbox was
     *                        degenerate (zero/non-finite), so this bake fell back to plain auto-fit
     *                        instead of dividing by zero or producing garbage
     */
    public record Result(List<QuadGeom> quads, int trianglesIn, int skipped, boolean projectedUvs,
                         float scale, float[] bounds, boolean clamped, boolean fellBackToAutoFit) { }

    public static Result bake(ObjMesh mesh, Fit fit) {
        return bake(mesh, fit, List.of());
    }

    /**
     * @param groupNames when non-empty, only these {@code g}/{@code o} groups are drawn; names that
     *                   do not exist in the mesh are ignored. Empty = every group.
     */
    public static Result bake(ObjMesh mesh, Fit fit, List<String> groupNames) {
        return bake(mesh, fit, groupNames, groupNames);
    }

    /**
     * Bakes {@code groupNames}, but derives the centering/scale anchor from
     * {@code anchorGroupNames}.  Dynamic TESR parts must use this overload: the
     * legacy renderer transforms each part in one shared model frame, whereas
     * centering each selected OBJ group independently moves every hinge/panel
     * origin and makes the animation look detached or folded.
     */
    public static Result bake(ObjMesh mesh, Fit fit, List<String> groupNames,
                              List<String> anchorGroupNames) {
        List<ObjMesh.Group> selected = select(mesh, groupNames);
        List<ObjMesh.Group> anchor = select(mesh, anchorGroupNames);

        float[] lin = fit.linear();
        boolean clamped = false, fellBack = false;
        float[] b;
        float scale;

        if (fit.auto()) {
            lin = Fit.IDENTITY;
            b = mesh.bounds(anchor);
            float extent = maxExtent(b);
            scale = (extent > EPS) ? (fit.size() / extent) : 1.0f;
        } else {
            b = transformedBounds(mesh, anchor, lin);
            float extent = maxExtent(b);
            if (!(extent > EPS) || !Float.isFinite(extent)) {
                // degenerate transform result (e.g. a renderer whose composed transform collapses the
                // mesh to a point) - fall back to plain auto-fit on the RAW mesh rather than divide by
                // zero or bake garbage. Never invented, always reported.
                fellBack = true;
                lin = Fit.IDENTITY;
                b = mesh.bounds(selected);
                extent = maxExtent(b);
                scale = (extent > EPS) ? (fit.size() / extent) : 1.0f;
            } else if (fit.normalize()) {
                // block-item-in-slot mode (Fit.itemFromWorldTransform): linear only fixes up the
                // PROPORTIONS (e.g. a block that is 6x10x5 blocks in world keeps that 6:10:5 shape), the
                // absolute size is always normalised to fit.size() like plain auto-fit - never left at
                // native world scale (a 10-block-tall radar must not become a 10-block-tall item) and
                // never subject to the maxExtent clamp below (normalising to size() can never overflow).
                scale = (extent > EPS) ? (fit.size() / extent) : 1.0f;
            } else if (extent > fit.maxExtent()) {
                // sanity clamp: never let one bad/mis-scaled model blow out to absurd size. Uniform so
                // proportions are preserved; reported via Result.clamped so the pack report can count it.
                clamped = true;
                scale = fit.maxExtent() / extent;
            } else {
                // authored coordinates x the renderer's own transform ARE block units already - the
                // whole point of this mode - so no extra normalisation.
                scale = 1.0f;
            }
        }

        float cx = (b[0] + b[3]) * 0.5f;
        float cy = (b[1] + b[4]) * 0.5f;
        float cz = (b[2] + b[5]) * 0.5f;
        float dy = b[4] - b[1];

        // block-space offset applied after scaling about the mesh centre
        float ox = 0.5f, oz = 0.5f;
        float oy = fit.onGround() ? (dy * scale * 0.5f) : 0.5f;

        boolean noUvs = !mesh.hasUvs();
        List<QuadGeom> out = new ArrayList<>();
        int trisIn = 0, skipped = 0;

        for (ObjMesh.Group g : selected) {
            for (int[] t : g.triangles()) {
                trisIn++;
                int v0 = t[0], v1 = t[3], v2 = t[6];
                if (v0 < 0 || v1 < 0 || v2 < 0) { skipped++; continue; }

                float[] pos = new float[12];
                float[] raw = new float[9];
                int[] vi = {v0, v1, v2};
                for (int k = 0; k < 3; k++) {
                    float px = mesh.px(vi[k]), py = mesh.py(vi[k]), pz = mesh.pz(vi[k]);
                    float x = lin[0] * px + lin[1] * py + lin[2] * pz;
                    float y = lin[3] * px + lin[4] * py + lin[5] * pz;
                    float z = lin[6] * px + lin[7] * py + lin[8] * pz;
                    raw[k * 3] = x; raw[k * 3 + 1] = y; raw[k * 3 + 2] = z;
                    pos[k * 3] = ox + (x - cx) * scale;
                    pos[k * 3 + 1] = oy + (y - cy) * scale;
                    pos[k * 3 + 2] = oz + (z - cz) * scale;
                }
                // degenerate 4th vertex == 3rd
                pos[9] = pos[6]; pos[10] = pos[7]; pos[11] = pos[8];

                float[] n = faceNormal(raw);

                float[] uv = new float[8];
                boolean haveUv = !noUvs && t[1] >= 0 && t[4] >= 0 && t[7] >= 0;
                if (haveUv) {
                    int[] ti = {t[1], t[4], t[7]};
                    for (int k = 0; k < 3; k++) {
                        uv[k * 2] = mesh.u(ti[k]);
                        uv[k * 2 + 1] = 1.0f - mesh.v(ti[k]);   // OBJ V is bottom-up, sprite V is top-down
                    }
                } else {
                    project(raw, n, uv);
                }
                uv[6] = uv[4]; uv[7] = uv[5];

                out.add(new QuadGeom(pos, uv, n));
            }
        }
        return new Result(out, trisIn, skipped, noUvs, scale, b, clamped, fellBack);
    }

    private static float maxExtent(float[] b) {
        return Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
    }

    /**
     * Same referenced-vertex walk as {@link ObjMesh#bounds(List)}, but with the row-major 3x3
     * {@code lin} applied to each vertex first. Kept as its own pass (rather than folded into the main
     * per-triangle loop) so the scale/clamp decision is made once, up front, from real transformed
     * bounds instead of an estimate.
     */
    static float[] transformedBounds(ObjMesh mesh, List<ObjMesh.Group> selected, float[] lin) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (ObjMesh.Group g : selected) {
            for (int[] t : g.triangles()) {
                for (int k = 0; k < 9; k += 3) {
                    int vi = t[k];
                    if (vi < 0) continue;
                    float px = mesh.px(vi), py = mesh.py(vi), pz = mesh.pz(vi);
                    float x = lin[0] * px + lin[1] * py + lin[2] * pz;
                    float y = lin[3] * px + lin[4] * py + lin[5] * pz;
                    float z = lin[6] * px + lin[7] * py + lin[8] * pz;
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
        return any ? new float[] {minX, minY, minZ, maxX, maxY, maxZ} : new float[] {0, 0, 0, 0, 0, 0};
    }

    static List<ObjMesh.Group> select(ObjMesh mesh, List<String> groupNames) {
        if (groupNames == null || groupNames.isEmpty()) return mesh.groups();
        List<ObjMesh.Group> out = new ArrayList<>();
        for (String n : groupNames) {
            ObjMesh.Group g = mesh.groupsByName().get(n);
            if (g != null) out.add(g);
        }
        return out.isEmpty() ? mesh.groups() : out;
    }

    /** Newell/cross-product face normal of a triangle given as 9 flat floats. */
    public static float[] faceNormal(float[] p) {
        float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        float bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-9f) { nx /= len; ny /= len; nz /= len; }
        return new float[] {nx, ny, nz};
    }

    /**
     * Planar UV projection for the OBJs that ship no {@code vt} at all: drop the dominant normal
     * axis and normalise the other two into 0..1 over the triangle's own extent, then flip V.
     */
    private static void project(float[] p, float[] n, float[] uv) {
        float ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        int a, b;
        if (ay >= ax && ay >= az) { a = 0; b = 2; }        // up/down face -> XZ
        else if (ax >= az) { a = 2; b = 1; }               // east/west   -> ZY
        else { a = 0; b = 1; }                             // north/south -> XY
        float minA = Float.POSITIVE_INFINITY, maxA = Float.NEGATIVE_INFINITY;
        float minB = Float.POSITIVE_INFINITY, maxB = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            float va = p[k * 3 + a], vb = p[k * 3 + b];
            minA = Math.min(minA, va); maxA = Math.max(maxA, va);
            minB = Math.min(minB, vb); maxB = Math.max(maxB, vb);
        }
        float da = maxA - minA, db = maxB - minB;
        for (int k = 0; k < 3; k++) {
            float u = da > 1.0e-6f ? (p[k * 3 + a] - minA) / da : 0.0f;
            float v = db > 1.0e-6f ? (p[k * 3 + b] - minB) / db : 0.0f;
            uv[k * 2] = u;
            uv[k * 2 + 1] = 1.0f - v;
        }
    }

    /**
     * Same bit layout as {@code net.minecraft.client.model.geom.builders.UVPair.pack}, verified from
     * its bytecode: {@code (floatToIntBits(u) & 0xFFFFFFFFL) << 32 | (floatToIntBits(v) & 0xFFFFFFFFL)}.
     * Duplicated here so the geometry layer stays free of net.minecraft; a unit test asserts the two
     * agree bit for bit.
     */
    public static long packUv(float u, float v) {
        long lu = Float.floatToIntBits(u) & 0xFFFFFFFFL;
        long lv = Float.floatToIntBits(v) & 0xFFFFFFFFL;
        return (lu << 32) | lv;
    }
}
