package dev.umb.objbridge.bake;

/**
 * How a raw OBJ mesh is placed into the 0..1 block-local cube {@code BakedQuad} positions live in.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>auto-fit</b> ({@code auto=true}, the original v1 behaviour, kept as the fallback for models
 *       where no legacy transform resolves): one uniform scale that makes the largest bbox extent equal
 *       {@code size}. This is what DESTROYED authored scale for the Tsar Bomba / large radar / etc:
 *       every mesh, however big or small it was authored, ends up exactly one block along its longest
 *       axis.</li>
 *   <li><b>transformed</b> ({@code auto=false}): the OBJ's authored coordinates are treated as model
 *       space and {@code linear} - the legacy renderer's own composed transform for the relevant render
 *       path (rotation+scale only; see {@code dev.umb.objbridge.transform}), as a row-major 3x3 - is
 *       applied directly, because in 1.7.10 a TESR draws in block-local space where 1.0 = one block. No
 *       extra normalisation is layered on top UNLESS the result is degenerate (falls back to auto-fit)
 *       or exceeds {@code maxExtent} in any dimension (uniformly clamped down to fit - see
 *       {@link MeshBaker}, which owns both of those decisions and reports them).</li>
 * </ul>
 *
 * @param onGround  {@code true} = rest the mesh on y=0 and centre x/z at 0.5 (blocks);
 *                  {@code false} = centre all three axes at 0.5 (items - {@code ItemTransform.apply}
 *                  ends with {@code translate(-0.5,-0.5,-0.5)}, javap-verified, so item models rotate
 *                  about the cube centre)
 * @param size      auto-fit only: the block-space extent the largest mesh axis is scaled to
 * @param auto      {@code true} = legacy auto-fit; {@code false} = authored-coordinates x {@code linear}
 * @param linear    row-major 3x3 (9 floats): {@code outX = linear[0]*x + linear[1]*y + linear[2]*z},
 *                  etc. Ignored when {@code auto} is true. {@link #IDENTITY} when there is no renderer
 *                  transform to apply (e.g. the renderer's world-path scan produced only translate ops,
 *                  which do not affect size, and/or rotation ops, which do not need to be resolved
 *                  here - see {@code dev.umb.objbridge.transform.TransformComposer}).
 * @param maxExtent transformed only: the sanity clamp - see {@link MeshBaker}
 * @param normalize transformed only (ignored when {@code auto} is true): {@code false} (default,
 *                  {@link #transformed}) = authored coordinates x {@code linear} ARE block units
 *                  already, so the only adjustment is the {@code maxExtent} sanity clamp - this is the
 *                  WORLD/TESR-splice mode the M9-ish scale fix uses. {@code true} ({@link
 *                  #itemFromWorldTransform}) = {@code linear} is used ONLY to get the mesh's correct
 *                  RELATIVE proportions (so a block whose world transform stretches it 6x10x5 keeps
 *                  that 6:10:5 shape), then the transformed bbox is uniformly normalised so its
 *                  largest axis equals {@code size} - exactly like plain auto-fit, except the bbox it
 *                  measures is the transformed one instead of the raw authored one. This is the
 *                  block-item-in-slot rule (laneInv-progress.md): a block's held/inventory form must
 *                  look like a small version of its (now correctly-proportioned, possibly many-block)
 *                  in-world shape, not the raw OBJ file's own, generally unrelated, aspect ratio.
 */
public record Fit(boolean onGround, float size, boolean auto, float[] linear, float maxExtent,
                  boolean normalize) {

    /** Row-major 3x3 identity: "no transform", authored coordinates pass through unchanged. */
    public static final float[] IDENTITY = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};

    /** Sanity clamp used by {@link #transformed} when the caller does not need a different one. */
    public static final float DEFAULT_MAX_EXTENT = 16.0f;

    public static final Fit BLOCK = new Fit(true, 1.0f);
    public static final Fit ITEM = new Fit(false, 1.0f);

    public Fit {
        if (!(size > 0.0f) || !Float.isFinite(size)) {
            throw new IllegalArgumentException("fit size must be finite and > 0, got " + size);
        }
        if (linear == null) linear = IDENTITY;
        if (linear.length != 9) {
            throw new IllegalArgumentException("linear must be 9 floats (row-major 3x3), got " + linear.length);
        }
        if (!(maxExtent > 0.0f) || !Float.isFinite(maxExtent)) {
            throw new IllegalArgumentException("maxExtent must be finite and > 0, got " + maxExtent);
        }
    }

    /**
     * Pre-existing 5-arg canonical shape, kept so every call site written before {@code normalize} was
     * added (both production and the whole {@code MeshBakerTest}/{@code RenderFitTest} suite) still
     * compiles unchanged. {@code normalize} defaults to {@code false} - i.e. this is always the
     * WORLD/TESR-splice meaning of "transformed", never the item-from-world meaning.
     */
    public Fit(boolean onGround, float size, boolean auto, float[] linear, float maxExtent) {
        this(onGround, size, auto, linear, maxExtent, false);
    }

    /** Legacy auto-fit at the given size, identity orientation - the original (and still default) v1 behaviour. */
    public Fit(boolean onGround, float size) {
        this(onGround, size, true, IDENTITY, DEFAULT_MAX_EXTENT, false);
    }

    /**
     * Authored-coordinates x {@code linear} mode. {@code linear} is typically
     * {@code dev.umb.objbridge.transform.ModelScale.linear3x3(composedMatrix)} for the renderer's
     * {@code WORLD} (block/TESR) path, or {@link #IDENTITY} when no such transform was found (which
     * itself is meaningful: it means "draw the authored coordinates unscaled," the common case for the
     * very models this fix targets - see {@code MeshBaker} class docs for measured examples).
     */
    public static Fit transformed(boolean onGround, float[] linear, float maxExtent) {
        return new Fit(onGround, 1.0f, false, linear, maxExtent, false);
    }

    public static Fit transformed(boolean onGround, float[] linear) {
        return transformed(onGround, linear, DEFAULT_MAX_EXTENT);
    }

    /**
     * Block-item-in-slot mode: {@code linear} shapes the mesh (the same WORLD-path linear map a block's
     * TESR/ISBRH class resolves to), but the transformed bbox is then normalised to {@code size} like
     * auto-fit, instead of being left at native world scale. {@code onGround} is always {@code false}
     * (items centre on 0.5, per {@code ItemTransform.apply}'s trailing {@code translate(-0.5,-0.5,-0.5)}
     * - see {@code dev.umb.objbridge.item.ObjTransforms}).
     */
    public static Fit itemFromWorldTransform(float[] linear, float size, float maxExtent) {
        return new Fit(false, size, false, linear, maxExtent, true);
    }

    public static Fit itemFromWorldTransform(float[] linear, float size) {
        return itemFromWorldTransform(linear, size, DEFAULT_MAX_EXTENT);
    }

    /** Preserve the 1.7.10 OBJ convention of sixteen authored model units per block. */
    public static Fit itemFromLegacyModelUnits(float size) {
        float unit = size / 16.0f;
        return transformed(false, new float[] {
                unit, 0.0f, 0.0f,
                0.0f, unit, 0.0f,
                0.0f, 0.0f, unit
        }, DEFAULT_MAX_EXTENT);
    }

    public Fit withSize(float s) { return new Fit(onGround, s, auto, linear, maxExtent, normalize); }
}
