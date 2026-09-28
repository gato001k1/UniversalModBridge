package dev.umb.objbridge.transform;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Composes a list of {@link TransformOp}s, in recorded order, into {@code Matrix4f}s, honouring
 * OpenGL fixed-function semantics.
 *
 * <p>Each call RIGHT-multiplies the current matrix ({@code M' = M x op}), exactly like the legacy
 * {@code glTranslate}/{@code glScale}/{@code glRotate} did to the top of the {@code GL_MODELVIEW}
 * stack - {@code Matrix4f.translate/scale/rotate} do the same thing, which is why they are used
 * unmodified here rather than hand-rolled. {@code glRotate(angle, x, y, z)} takes degrees and an axis.
 *
 * <p>{@code glPushMatrix}/{@code glPopMatrix} push and pop a snapshot of the CURRENT matrix, so a
 * transform recorded between a push and its matching pop applies only until that pop - after the pop,
 * later ops see the matrix as it was just before the push, as if the scoped ops never happened.
 *
 * <p>An op with any dynamic argument (JSON {@code null}, decoded as {@link Float#NaN} by
 * {@link RendererTransforms}) is skipped ENTIRELY rather than guessing a value - counted in
 * {@link Result#dynamicSkipped()}. Push/pop never carry args and are never skipped. A pop with no
 * matching push (stack empty) is a no-op past depth 0, counted in {@link Result#unbalancedPops()} -
 * this has not been observed in the shipped data but is handled rather than throwing.
 */
public final class TransformComposer {

    private TransformComposer() { }

    /**
     * @param matrix         the literal composition result: every op applied in order, respecting
     *                       push/pop scoping, ending at whatever matrix is current after the LAST op
     *                       in the list (so a fully balanced push/pop sequence around the whole list
     *                       ends back at what it was before the first push - by design, this is the
     *                       precise, unambiguous "run the recorded instructions" result).
     * @param representative the matrix in effect at the point this lane infers the actual draw call
     *                       happened: the snapshot taken immediately before the LAST {@code glPopMatrix}
     *                       processed (the innermost-at-that-point scope), or {@code matrix} itself
     *                       when the op list contains no pop at all. The legacy pattern this lane's
     *                       data is drawn from is overwhelmingly "push, position/orient/scale, draw the
     *                       model, pop" - the op stream never records the draw call itself, so the
     *                       state just before the pop that closes the outermost scope is the best
     *                       available proxy for "what applied to the model."
     * @param dynamicSkipped count of ops skipped because they needed a runtime-only argument
     * @param pushes         glPushMatrix count
     * @param pops           glPopMatrix count
     * @param unbalancedPops pops seen with an empty stack (ignored rather than thrown)
     */
    public record Result(Matrix4f matrix, Matrix4f representative, int dynamicSkipped, int pushes,
                         int pops, int unbalancedPops) {
        public boolean balanced() { return pushes == pops; }
    }

    public static Result compose(List<TransformOp> ops) {
        Matrix4f cur = new Matrix4f();
        Deque<Matrix4f> stack = new ArrayDeque<>();
        Matrix4f lastPrePop = null;
        int dynamicSkipped = 0, pushes = 0, pops = 0, unbalancedPops = 0;

        for (TransformOp op : ops) {
            if (op.isPush()) {
                stack.push(new Matrix4f(cur));
                pushes++;
                continue;
            }
            if (op.isPop()) {
                pops++;
                lastPrePop = new Matrix4f(cur);
                if (!stack.isEmpty()) {
                    cur = stack.pop();
                } else {
                    unbalancedPops++;
                }
                continue;
            }
            if (op.hasUnresolvedArg()) {
                dynamicSkipped++;
                continue;
            }
            float[] a = op.args();
            if (op.isTranslate() && a.length >= 3) {
                cur.translate(a[0], a[1], a[2]);
            } else if (op.isScale() && a.length >= 3) {
                cur.scale(a[0], a[1], a[2]);
            } else if (op.isRotate() && a.length >= 4) {
                cur.rotate((float) Math.toRadians(a[0]), new Vector3f(a[1], a[2], a[3]));
            }
            // any other/malformed op (never observed in the shipped data) is ignored, not counted -
            // it carries no transform either way.
        }
        Matrix4f representative = (lastPrePop != null) ? lastPrePop : cur;
        return new Result(cur, representative, dynamicSkipped, pushes, pops, unbalancedPops);
    }

    /** Only the ops whose {@code method} classifies to {@code path}, in original recorded order. */
    public static List<TransformOp> filter(List<TransformOp> ops, PathClass path) {
        return ops.stream().filter(o -> PathClass.classify(o.method()) == path).toList();
    }
}
