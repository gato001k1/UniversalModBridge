package dev.umb.objbridge.bake;

/**
 * One baked-but-not-yet-Minecraft quad: 4 positions in 0..1 block-local space and 4 UV pairs already
 * flipped into Minecraft's top-down V convention but still normalised 0..1 within the sprite.
 *
 * <p>Triangles arrive here as degenerate quads (corner 2 repeated in slot 3) because
 * {@code BakedQuad} has exactly 4 hardcoded vertex slots ({@code VERTEX_COUNT=4}, javap-verified) and
 * offers no triangle-native constructor.
 *
 * <p>Pure Java on purpose: no JOML, no net.minecraft, so the geometry can be asserted headless.
 *
 * @param pos    12 floats, {@code x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3}
 * @param uv     8 floats, {@code u0,v0, u1,v1, u2,v2, u3,v3}
 * @param normal 3 floats, the un-normalised face normal in OBJ space
 */
public record QuadGeom(float[] pos, float[] uv, float[] normal) {

    public QuadGeom {
        if (pos.length != 12) throw new IllegalArgumentException("pos must be 12 floats, got " + pos.length);
        if (uv.length != 8) throw new IllegalArgumentException("uv must be 8 floats, got " + uv.length);
        if (normal.length != 3) throw new IllegalArgumentException("normal must be 3 floats");
    }

    public float x(int i) { return pos[i * 3]; }
    public float y(int i) { return pos[i * 3 + 1]; }
    public float z(int i) { return pos[i * 3 + 2]; }
    public float u(int i) { return uv[i * 2]; }
    public float v(int i) { return uv[i * 2 + 1]; }
}
