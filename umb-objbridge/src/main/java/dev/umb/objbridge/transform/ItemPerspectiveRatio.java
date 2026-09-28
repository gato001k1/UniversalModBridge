package dev.umb.objbridge.transform;

import java.util.List;

/**
 * The per-perspective GUI/first-person/third-person scale-RATIO math, factored out of
 * {@code dev.umb.objbridge.item.ObjTransforms} so it can be shared with
 * {@code dev.umb.objbridge.gen.ObjPackGen} without dragging {@code net.minecraft} (specifically
 * {@code ItemTransform}/{@code ItemTransforms}) onto the pack generator's classpath - {@code ObjPackGen}
 * is a standalone tool invoked with just {@code build\objbridge\classes} plus gson (see
 * {@code umb-objbridge/README.md}), and must decide the {@code "oversized_in_gui"} flag (see
 * {@code ObjPackGen#itemDef}) using the SAME numbers {@code ObjTransforms.forItem} will use at bake
 * time, or the two could disagree. This class needs only {@link RendererTransforms}/
 * {@link TransformComposer}/{@link ModelScale} (JOML, no {@code net.minecraft}).
 */
public final class ItemPerspectiveRatio {

    private ItemPerspectiveRatio() { }

    public static final float RATIO_MIN = 0.2f, RATIO_MAX = 5.0f;

    /** One renderer class's raw, un-combined per-{@link PathClass} uniform-scale readings. */
    public record Buckets(Float inv, Float fp, Float tp, Float common) {
        public int found() {
            return (inv != null ? 1 : 0) + (fp != null ? 1 : 0) + (tp != null ? 1 : 0) + (common != null ? 1 : 0);
        }

        /** COMMON substitutes for whichever of inv/fp/tp has no data of its own - see class docs on
         *  {@code ObjTransforms.forItem} for why this is not double-counted into {@link #baseline()}. */
        public Float invEffective() { return inv != null ? inv : common; }
        public Float fpEffective() { return fp != null ? fp : common; }
        public Float tpEffective() { return tp != null ? tp : common; }

        public float baseline() { return geometricMean(inv, fp, tp, common); }
    }

    public static Buckets buckets(RendererTransforms rt, String rendererClass) {
        return new Buckets(
                bucketScale(rt, rendererClass, PathClass.INVENTORY),
                bucketScale(rt, rendererClass, PathClass.FIRST_PERSON),
                bucketScale(rt, rendererClass, PathClass.THIRD_PERSON),
                bucketScale(rt, rendererClass, PathClass.COMMON));
    }

    private static Float bucketScale(RendererTransforms rt, String rendererClass, PathClass path) {
        List<TransformOp> ops = TransformComposer.filter(rt.forClass(rendererClass), path);
        if (ops.isEmpty()) return null;
        TransformComposer.Result r = TransformComposer.compose(ops);
        float s = ModelScale.uniformScale(r.representative());
        return (Float.isFinite(s) && s > 1.0e-4f) ? s : null;
    }

    public static float geometricMean(Float... vals) {
        double sumLog = 0;
        int n = 0;
        for (Float v : vals) {
            if (v == null) continue;
            sumLog += Math.log(v);
            n++;
        }
        return n == 0 ? 1.0f : (float) Math.exp(sumLog / n);
    }

    /** {@code bucket / baseline}, clamped to {@code [RATIO_MIN, RATIO_MAX]}; {@code 1.0} (no-op) when
     *  {@code bucket} is absent or {@code baseline} is non-positive/non-finite math would result. */
    public static float ratioMultiplier(Float bucket, float baseline) {
        if (bucket == null || !(baseline > 0.0f)) return 1.0f;
        float mult = bucket / baseline;
        if (!Float.isFinite(mult)) return 1.0f;
        return Math.max(RATIO_MIN, Math.min(RATIO_MAX, mult));
    }

    /** Absolute renderer scale for a perspective; absent paths use the supplied vanilla fallback. */
    public static float absoluteScale(Float bucket, Float common, float fallback) {
        Float value = bucket != null ? bucket : common;
        return value != null && Float.isFinite(value) && value > 1.0e-4f ? value : fallback;
    }
}
