package dev.umb.objbridge.transform;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformComposerTest {

    private static TransformOp translate(float x, float y, float z) {
        return new TransformOp("m", "glTranslated", new float[] {x, y, z}, false, null);
    }

    private static TransformOp scale(float x, float y, float z) {
        return new TransformOp("m", "glScaled", new float[] {x, y, z}, false, null);
    }

    private static TransformOp rotate(float deg, float x, float y, float z) {
        return new TransformOp("m", "glRotated", new float[] {deg, x, y, z}, false, null);
    }

    private static TransformOp push() {
        return new TransformOp("m", "glPushMatrix", new float[0], false, null);
    }

    private static TransformOp pop() {
        return new TransformOp("m", "glPopMatrix", new float[0], false, null);
    }

    private static TransformOp dynamicTranslate() {
        return new TransformOp("m", "glTranslated", new float[] {Float.NaN, 0f, 0f}, true, "arithmetic");
    }

    /** The brief's own example: translate, then scale 3.0, then a push/pop-scoped scale. */
    @Test
    void translateThenScaleThenPushPopScopedScale() {
        List<TransformOp> ops = List.of(
                translate(1, 2, 3),
                scale(3, 3, 3),
                push(),
                scale(2, 2, 2),
                pop()
        );
        TransformComposer.Result r = TransformComposer.compose(ops);

        assertEquals(1, r.pushes());
        assertEquals(1, r.pops());
        assertTrue(r.balanced());
        assertEquals(0, r.dynamicSkipped());

        // matrix(): the literal end-of-list result. The inner push/pop-scoped scale(2) must NOT be
        // visible here - a transform recorded inside a push/pop applies only until the matching pop.
        Vector3f unit = new Vector3f(1, 1, 1);
        Vector3f viaMatrix = r.matrix().transformPosition(new Vector3f(unit));
        // translate(1,2,3) . scale(3,3,3) applied to (1,1,1) -> scale first -> (3,3,3) -> translate -> (4,5,6)
        assertEquals(4f, viaMatrix.x, 1e-5f);
        assertEquals(5f, viaMatrix.y, 1e-5f);
        assertEquals(6f, viaMatrix.z, 1e-5f);

        // representative(): the snapshot taken just before the pop DOES see the scoped scale(2), so
        // the net scale is 3*2=6.
        Vector3f viaRepresentative = r.representative().transformPosition(new Vector3f(unit));
        assertEquals(7f, viaRepresentative.x, 1e-5f);
        assertEquals(8f, viaRepresentative.y, 1e-5f);
        assertEquals(9f, viaRepresentative.z, 1e-5f);

        // and matrix() != representative() here precisely because the pop discarded the inner scope
        assertFalse(r.matrix().equals(r.representative()));
    }

    @Test
    void noPopAtAllMakesRepresentativeEqualMatrix() {
        List<TransformOp> ops = List.of(translate(1, 0, 0), scale(2, 2, 2));
        TransformComposer.Result r = TransformComposer.compose(ops);
        assertEquals(0, r.pushes());
        assertEquals(0, r.pops());
        assertEquals(r.matrix(), r.representative());
    }

    @Test
    void dynamicArgsAreSkippedAndCounted() {
        List<TransformOp> ops = List.of(
                dynamicTranslate(),   // must be skipped entirely - never guess the 0.0
                scale(2, 2, 2)
        );
        TransformComposer.Result r = TransformComposer.compose(ops);
        assertEquals(1, r.dynamicSkipped());
        // only the scale applied - if the dynamic translate had been guessed as 0 this would still
        // pass, so also check the matrix carries no translation component at all (m30==m31==m32==0)
        Matrix4f m = r.matrix();
        assertEquals(0f, m.m30(), 0f);
        assertEquals(0f, m.m31(), 0f);
        assertEquals(0f, m.m32(), 0f);
        Vector3f p = m.transformPosition(new Vector3f(1, 1, 1));
        assertEquals(2f, p.x, 1e-5f);
        assertEquals(2f, p.y, 1e-5f);
        assertEquals(2f, p.z, 1e-5f);
    }

    @Test
    void unbalancedPopPastDepthZeroIsANoOpNotAThrow() {
        List<TransformOp> ops = List.of(pop(), pop(), scale(2, 2, 2));
        TransformComposer.Result r = TransformComposer.compose(ops);
        assertEquals(0, r.pushes());
        assertEquals(2, r.pops());
        assertEquals(2, r.unbalancedPops());
        assertFalse(r.balanced());
        // the scale still applied fine afterward
        Vector3f p = r.matrix().transformPosition(new Vector3f(1, 1, 1));
        assertEquals(2f, p.x, 1e-5f);
    }

    @Test
    void rotationsPreserveLengthRegardlessOfHowManyMutuallyExclusiveBranchesGetComposed() {
        // The extractor flattens legacy switch/if branches on block facing into one
        // straight-line op list (e.g. RenderNukeTsar: glRotatef 90, then 180, then 270, then 0, all
        // constant, all really mutually exclusive at runtime). Composing all of them together must not
        // change the LENGTH of a transformed vector - only scale ops may do that - which is exactly why
        // ModelScale.uniformScale (determinant-based) is used for sizing rather than basis lengths that
        // could be thrown off by an accumulated rotation.
        List<TransformOp> ops = List.of(rotate(90, 0, 1, 0), rotate(180, 0, 1, 0), rotate(270, 0, 1, 0), rotate(0, 0, 1, 0));
        TransformComposer.Result r = TransformComposer.compose(ops);
        float scale = ModelScale.uniformScale(r.matrix());
        assertEquals(1.0f, scale, 1e-4f);
    }

    @Test
    void filterKeepsOnlyOpsClassifiedToThePathInOriginalOrder() {
        List<TransformOp> ops = List.of(
                new TransformOp("renderInventory", "glScaled", new float[] {3, 3, 3}, false, null),
                new TransformOp("func_147500_a", "glTranslated", new float[] {0, 0, 0}, false, null),
                new TransformOp("renderInventory", "glTranslated", new float[] {0, -5, 0}, false, null)
        );
        List<TransformOp> inv = TransformComposer.filter(ops, PathClass.INVENTORY);
        assertEquals(2, inv.size());
        assertEquals("glScaled", inv.get(0).op());
        assertEquals("glTranslated", inv.get(1).op());
        List<TransformOp> world = TransformComposer.filter(ops, PathClass.WORLD);
        assertEquals(1, world.size());
    }
}
