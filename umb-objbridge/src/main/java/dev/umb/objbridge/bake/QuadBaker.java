package dev.umb.objbridge.bake;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link QuadGeom} -> {@code BakedQuad}. The only class in the geometry path that touches
 * net.minecraft.
 *
 * <p>javap-verified shapes this relies on (all from {@code research/jars/26.2/client.jar}):
 * <pre>
 * BakedQuad(Vector3fc,Vector3fc,Vector3fc,Vector3fc, long,long,long,long, Direction, MaterialInfo)
 * BakedQuad$MaterialInfo(TextureAtlasSprite, ChunkSectionLayer, RenderType, int tintIndex,
 *                        boolean shade, int lightEmission)
 * TextureAtlasSprite.getU(float f) = u0 + (u1-u0)*f          // f is 0..1 within the sprite
 * Sheets.cutoutBlockItemSheet() : RenderType                 // what MaterialInfo.of picks for a
 *                                                            // blocks-atlas, non-translucent sprite
 * </pre>
 */
public final class QuadBaker {

    private QuadBaker() { }

    /**
     * Bakes with tintIndex -1 (untinted), shade true, lightEmission 0 and the CUTOUT layer - which is
     * what {@code MaterialInfo.of(baked, Transparency.TRANSPARENT, -1, true, 0)} would produce for a
     * blocks-atlas sprite, without having to build a {@code Material$Baked} first.
     */
    public static List<BakedQuad> bake(List<QuadGeom> geoms, TextureAtlasSprite sprite) {
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(
                sprite,
                ChunkSectionLayer.CUTOUT,
                Sheets.cutoutBlockItemSheet(),
                -1,      // tintIndex: untinted
                true,    // shade
                0        // lightEmission
        );
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        // Intern positions the way FaceBakery does through ModelBaker$Interner.vector(): an OBJ
        // shares each vertex across ~5 triangles, so this cuts Vector3f allocation ~5x on a
        // 10k-triangle mesh. JOML's Vector3f has value equals/hashCode, so a plain HashMap works.
        Map<Vector3f, Vector3fc> interner = new HashMap<>();

        List<BakedQuad> out = new ArrayList<>(geoms.size());
        for (QuadGeom g : geoms) {
            Vector3fc p0 = intern(interner, g.x(0), g.y(0), g.z(0));
            Vector3fc p1 = intern(interner, g.x(1), g.y(1), g.z(1));
            Vector3fc p2 = intern(interner, g.x(2), g.y(2), g.z(2));
            Vector3fc p3 = p2;   // degenerate 4th vertex; MeshBaker already made them equal
            out.add(new BakedQuad(
                    p0, p1, p2, p3,
                    pack(g.u(0), g.v(0), u0, u1, v0, v1),
                    pack(g.u(1), g.v(1), u0, u1, v0, v1),
                    pack(g.u(2), g.v(2), u0, u1, v0, v1),
                    pack(g.u(3), g.v(3), u0, u1, v0, v1),
                    nearest(g.normal()),
                    material
            ));
        }
        return out;
    }

    /**
     * Maps a 0..1 sprite-local UV into atlas space exactly the way {@code TextureAtlasSprite.getU}/
     * {@code getV} do, then packs it the way {@code UVPair.pack} does. Pure, so it is unit-testable
     * without a real atlas.
     */
    public static long pack(float u, float v, float u0, float u1, float v0, float v1) {
        float au = u0 + (u1 - u0) * clamp01(u);
        float av = v0 + (v1 - v0) * clamp01(v);
        return MeshBaker.packUv(au, av);
    }

    private static float clamp01(float f) {
        if (Float.isNaN(f)) return 0.0f;
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }

    private static Vector3fc intern(Map<Vector3f, Vector3fc> interner, float x, float y, float z) {
        Vector3f probe = new Vector3f(x, y, z);
        Vector3fc got = interner.get(probe);
        if (got != null) return got;
        interner.put(probe, probe);
        return probe;
    }

    /** Face normal -> the nearest of the 6 {@code Direction}s. */
    public static Direction nearest(float[] n) {
        return Direction.getApproximateNearest(n[0], n[1], n[2]);
    }
}
