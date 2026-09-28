package dev.umb.objbridge.transform;

import org.joml.Matrix4f;

/**
 * {@code Matrix4f} -&gt; plain-float extraction, so the geometry layer ({@code dev.umb.objbridge.bake})
 * can stay JOML-free (deliberate: {@link dev.umb.objbridge.bake.MeshBaker}/{@link
 * dev.umb.objbridge.bake.Fit} are pure Java so the whole geometry pass is unit-testable with no
 * classpath - see their class docs).
 */
public final class ModelScale {

    private ModelScale() { }

    /**
     * The matrix's upper-left 3x3 (rotation+scale, no translation) as 9 ROW-MAJOR floats:
     * {@code out = lin * in}, i.e. {@code outX = lin[0]*x + lin[1]*y + lin[2]*z}, etc.
     *
     * <p>JOML stores {@code Matrix4f} column-major ({@code m<col><row>}), so row {@code r}, column
     * {@code c} is {@code m<c><r>()}.
     */
    public static float[] linear3x3(Matrix4f m) {
        return new float[] {
                m.m00(), m.m10(), m.m20(),
                m.m01(), m.m11(), m.m21(),
                m.m02(), m.m12(), m.m22()
        };
    }

    /**
     * A single representative scale factor for the matrix's linear part: the cube root of the
     * (unsigned) volume scale factor, i.e. {@code cbrt(|det3x3|)}. Rotation-invariant (a pure rotation
     * has determinant +-1, contributing nothing), which matters because raw operation lists
     * can include several MUTUALLY EXCLUSIVE facing rotations (legacy switch/if branches on block
     * facing, flattened into one straight-line list by the bytecode scanner) that end up composed
     * together; since they are all proper rotations they never change the volume factor, so this
     * number stays correct regardless of that artifact. Returns 1.0 for a degenerate/non-finite result
     * (never invents a shrink or a blow-up).
     */
    public static float uniformScale(Matrix4f m) {
        double det = m.m00() * (double) (m.m11() * m.m22() - m.m12() * m.m21())
                - m.m01() * (double) (m.m10() * m.m22() - m.m12() * m.m20())
                + m.m02() * (double) (m.m10() * m.m21() - m.m11() * m.m20());
        double g = Math.cbrt(Math.abs(det));
        return (Double.isFinite(g) && g > 1.0e-6) ? (float) g : 1.0f;
    }
}
