package dev.umb.objbridge.entity;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.obj.ObjMesh;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Door-live follow-up (GL-state ops): fixed-function clip planes replayed as per-vertex
 * clipping, because 26.2 has no {@code glClipPlane}. Legacy specifies each plane in the
 * model-view frame in effect at the call; the replay maps it into mesh space and clips
 * baked quads against it with Sutherland-Hodgman (convex quads stay convex, so a triangle
 * fan re-emits them exactly).
 *
 * <p>Frame bookkeeping: baked quad vertices live in the FITTED frame (see
 * {@link MeshBaker#bake}, which applies scale-about-centre plus block offset), while the
 * plane math below runs in mesh-authored units. {@link #fitMap} mirrors the bake's own
 * affine map (scale, centre, offset, linear) from the same public inputs, and a
 * headless test pins it against {@link MeshBaker#bake} output vertex-for-vertex, so any
 * future bake change fails loudly instead of silently mis-clipping.</p>
 *
 * <p>All clip math is pure doubles (planes come from constant extraction; vertices are
 * widened floats). Anything degenerate - singular map, empty polygon - fails OPEN (no
 * clipping), never hides geometry on a guess.</p>
 */
final class ClipPlanes {

    private ClipPlanes() { }

    /** Clipped polygon: positions plus companion payload (atlas UVs), same length. */
    static final class Poly {
        final double[][] pos;
        final double[][] uv;
        Poly(double[][] pos, double[][] uv) {
            this.pos = pos;
            this.uv = uv;
        }
    }

    /** Keep half-space: dot(pos, eq) >= -EPS survives (matches GL's negative-is-clipped). */
    private static final double EPS = 1e-9;

    /**
     * Clips one convex polygon against one plane. Returns 0..n+1 vertices (input order
     * preserved); payloads interpolate linearly along cut edges, which is exact for the
     * affine UVs our bakes emit.
     */
    static Poly clipOne(double[][] pos, double[][] uv, double[] eq) {
        int n = pos.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = eq[0] * pos[i][0] + eq[1] * pos[i][1] + eq[2] * pos[i][2] + eq[3];
        }
        List<double[]> op = new ArrayList<>(n + 1);
        List<double[]> ou = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            boolean ini = d[i] >= -EPS, inj = d[j] >= -EPS;
            if (ini) {
                op.add(pos[i]);
                ou.add(uv[i]);
            }
            if (ini != inj) {
                double t = d[i] / (d[i] - d[j]);
                double[] p = new double[3];
                double[] u = new double[2];
                for (int k = 0; k < 3; k++) p[k] = pos[i][k] + t * (pos[j][k] - pos[i][k]);
                for (int k = 0; k < 2; k++) u[k] = uv[i][k] + t * (uv[j][k] - uv[i][k]);
                op.add(p);
                ou.add(u);
            }
        }
        return new Poly(op.toArray(new double[0][]), ou.toArray(new double[0][]));
    }

    /** Folds a polygon through every plane; empty means fully clipped away. */
    static Poly clip(double[][] pos, double[][] uv, List<double[]> planes) {
        Poly p = new Poly(pos, uv);
        for (double[] eq : planes) {
            if (p.pos.length == 0) return p;
            p = clipOne(p.pos, p.uv, eq);
        }
        return p;
    }

    /**
     * Maps a plane equation from the clip call-site frame into mesh-authored units:
     * with T = pre^-1 * all (both draw-space matrices from the replay walk),
     * local = T^T * spec. Null when either matrix is singular (fail open).
     */
    static double[] toLocal(Matrix4f pre, Matrix4f all, double[] eq) {
        try {
            if (Math.abs(pre.determinant()) < 1e-12 || Math.abs(all.determinant()) < 1e-12) {
                return null;
            }
            Matrix4f t = new Matrix4f(pre).invert().mul(all);
            Vector4f p = new Vector4f((float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
            new Matrix4f(t).transpose().transform(p);
            return new double[]{p.x, p.y, p.z, p.w};
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * The bake's affine map, fitted = offset + scale * linear * (authored - centre),
     * mirrored from {@link MeshBaker#bake} (same inputs, same formulas). Cached per
     * (mesh, groups, fit-identity) exactly like the quad bake it shadows.
     */
    static final class FitMap {
        final float[] lin;
        final double[] centre;
        final double[] offset;
        final double scale;
        FitMap(float[] lin, double[] centre, double[] offset, double scale) {
            this.lin = lin;
            this.centre = centre;
            this.offset = offset;
            this.scale = scale;
        }

        /** Mesh-authored plane equation remapped into fitted (baked-quad) units. */
        double[] mapPlane(double[] eq) {
            // fitted = o + s*(L*(x - c))  =>  x = c + L^-1*((fitted - o)/s).
            // keep {eq . x >= 0}  <=>  keep {q . fitted >= 0} with
            // q[0..2] = (L^-T * eq[0..2]) / s, q[3] = eq[3] + eq[0..2] . (c - L^-1*o/s)... via 4x4 below.
            Matrix4f a = new Matrix4f()
                    .translation((float) offset[0], (float) offset[1], (float) offset[2])
                    .scale((float) scale)
                    .mul(new Matrix4f(
                            lin[0], lin[3], lin[6], 0f,
                            lin[1], lin[4], lin[7], 0f,
                            lin[2], lin[5], lin[8], 0f,
                            0f, 0f, 0f, 1f))
                    .translate((float) -centre[0], (float) -centre[1], (float) -centre[2]);
            if (Math.abs(a.determinant()) < 1e-12) return null;
            Vector4f p = new Vector4f((float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
            new Matrix4f(a).invert().transpose().transform(p);
            return new double[]{p.x, p.y, p.z, p.w};
        }
    }

    private static final Map<String, FitMap> FIT_CACHE = new ConcurrentHashMap<>();

    /**
     * The affine map the quad bake applied for this (mesh, groups, fit). Mirrors
     * {@link MeshBaker#bake}'s scale/centre/offset selection; see that method for the
     * authoritative formulas (referenced line-for-line in the drift-guard test).
     */
    static FitMap fitMap(ObjMesh mesh, List<String> groups, Fit fit) {
        String key = meshName(mesh) + "|" + String.join(",", groups) + "|" + fitKey(fit);
        FitMap got = FIT_CACHE.get(key);
        if (got != null) return got;
        MeshBaker.Result r = MeshBaker.bake(mesh, fit, groups);
        float[] b = r.bounds();
        double[] centre = {(b[0] + b[3]) * 0.5, (b[1] + b[4]) * 0.5, (b[2] + b[5]) * 0.5};
        double dy = b[4] - b[1];
        double[] offset = {0.5, fit.onGround() ? dy * r.scale() * 0.5 : 0.5, 0.5};
        FitMap fm = new FitMap(fit.linear(), centre, offset, r.scale());
        FIT_CACHE.put(key, fm);
        return fm;
    }

    private static String meshName(ObjMesh mesh) {
        try {
            return String.valueOf(mesh);
        } catch (Throwable th) {
            return "mesh@" + System.identityHashCode(mesh);
        }
    }

    private static String fitKey(Fit fit) {
        try {
            float[] lin = fit.linear();
            StringBuilder sb = new StringBuilder();
            sb.append(fit.onGround()).append(',').append(fit.auto()).append(',');
            if (lin != null) for (float f : lin) sb.append(f).append(';');
            return sb.toString();
        } catch (Throwable th) {
            return "fit@" + System.identityHashCode(fit);
        }
    }
}
