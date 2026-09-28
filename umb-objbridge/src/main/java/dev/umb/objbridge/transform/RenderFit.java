package dev.umb.objbridge.transform;

import dev.umb.objbridge.bake.Fit;

import java.util.List;

/**
 * Turns a legacy renderer class + a {@link PathClass} into a {@link Fit}, per the lead's measured root
 * cause: stop auto-fitting every mesh to exactly one block and instead apply the renderer's OWN
 * transform for the relevant path directly to the authored coordinates.
 *
 * <p>"No transform resolves" (brief's own words for when the old behaviour must be kept) means
 * specifically: the renderer class is unknown to {@code renderer-transforms.json} - i.e. this lane has
 * NO data about it at all. That is different from a KNOWN class that simply has zero ops classified
 * into the requested {@link PathClass}: several of the very renderers this fix targets (the Tsar Bomba,
 * the large radar, ISBRH blocks like the anvils) apply no {@code glScale}/{@code glTranslate}/
 * {@code glRotate} at all on their world-draw path - that absence IS the data (it means "draw the
 * authored coordinates unscaled"), not a gap to guess around. Composing an empty op list correctly
 * yields the identity matrix, so this needs no special case - only "class not found at all" is treated
 * as unresolved.
 */
public final class RenderFit {

    private RenderFit() { }

    /**
     * @param fit            the {@link Fit} to bake with
     * @param resolved       true when the renderer class is known to the transforms file at all (an
     *                       identity result because the class has no ops for this path still counts as
     *                       resolved - see class docs)
     * @param opsUsed        how many ops (of {@code path}) fed the composition (0 = identity, either
     *                       because the class is unresolved or because it genuinely applies none)
     * @param dynamicSkipped ops skipped because an argument they needed was runtime-only
     * @param pushes         glPushMatrix count seen while composing
     * @param unbalanced     true when push/pop counts did not match (handled, not thrown - see
     *                       {@link TransformComposer})
     */
    public record Outcome(Fit fit, boolean resolved, int opsUsed, int dynamicSkipped, int pushes,
                          boolean unbalanced) {
        /** Resolved, but the renderer applies no transform on this path at all - authored coords direct. */
        public boolean identity() { return resolved && opsUsed == 0; }
    }

    public static Outcome forPath(RendererTransforms transforms, String rendererClass, PathClass path,
                                  boolean onGround, float maxExtent) {
        boolean known = rendererClass != null && transforms.hasClass(rendererClass);
        if (!known) {
            return new Outcome(new Fit(onGround, 1.0f), false, 0, 0, 0, false);
        }
        List<TransformOp> filtered = TransformComposer.filter(transforms.forClass(rendererClass), path);
        TransformComposer.Result r = TransformComposer.compose(filtered);
        float[] lin = ModelScale.linear3x3(r.representative());
        Fit fit = Fit.transformed(onGround, lin, maxExtent);
        return new Outcome(fit, true, filtered.size(), r.dynamicSkipped(), r.pushes(), !r.balanced());
    }

    public static Outcome forPath(RendererTransforms transforms, String rendererClass, PathClass path,
                                  boolean onGround) {
        return forPath(transforms, rendererClass, path, onGround, Fit.DEFAULT_MAX_EXTENT);
    }
}
