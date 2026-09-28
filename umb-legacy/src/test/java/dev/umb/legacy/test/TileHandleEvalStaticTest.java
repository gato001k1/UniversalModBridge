package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileHandle;
import dev.umb.legacy.legacyside.TileHandleImpl;

/**
 * gate: {@link TileHandleImpl#evalStatic} - the reflective static-evaluator call that lets the server evaluate a renderer's animation-track function (the door's {@code getRelevantTransformation}) against the live tile without reimplementing it natively....
 */
class TileHandleEvalStaticTest {

    /** Stands in for the animation runtime object (the door's {@code currentAnimation}). */
    static final class FakeAnim {
        double progress = 0.5;
    }

    static final class FakeTile extends TileEntity {
        FakeAnim currentAnimation = new FakeAnim();
    }

    /** Stands in for the renderer's pure track evaluator. */
    static final class FakeTracks {
        public static double[] track(String name, FakeAnim anim) {
            if (!"DOOR".equals(name) || anim == null) return null;
            return new double[]{anim.progress, anim.progress * 3.0};
        }

        public static double[] nullMeansRest(String name, FakeAnim anim) {
            if (anim == null) return new double[]{0.0, 0.0};
            return new double[]{anim.progress, anim.progress * 3.0};
        }

        public static double[] throwing(String name, FakeAnim anim) {
            throw new IllegalStateException("boom");
        }

        public static String notAnArray(String name, FakeAnim anim) {
            return "nope";
        }
    }

    private static FieldPath hops(String key, String... names) {
        String[] kinds = new String[names.length];
        for (int i = 0; i < kinds.length; i++) kinds[i] = "field";
        return new FieldPath(key, names, kinds);
    }

    @Test
    void trackEvaluatesOffATeRootedObject() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        double[] got = h.evalStatic(FakeTracks.class.getName(), "track", "DOOR",
                hops("FakeTracks.track.DOOR", "currentAnimation"));
        assertArrayEquals(new double[]{0.5, 1.5}, got, 1e-12);
    }

    @Test
    void nullPathEvaluatesAgainstTheTileItself() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        // FakeTracks has no (String, FakeTile) overload, so this must be null, not a throw.
        assertNull(h.evalStatic(FakeTracks.class.getName(), "track", "DOOR", null));
    }

    @Test
    void missingMethodMissingClassAndBadHopsAreNull() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        FieldPath obj = hops("k", "currentAnimation");
        assertNull(h.evalStatic(FakeTracks.class.getName(), "noSuchMethod", "DOOR", obj));
        assertNull(h.evalStatic("does.not.Exist", "track", "DOOR", obj));
        assertNull(h.evalStatic(FakeTracks.class.getName(), "track", "DOOR",
                hops("k", "noSuchField")));
        assertNull(h.evalStatic(null, "track", "DOOR", obj));
        assertNull(h.evalStatic(FakeTracks.class.getName(), null, "DOOR", obj));
    }

    @Test
    void throwingEvaluatorAndNonArrayResultAreNull() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        FieldPath obj = hops("k", "currentAnimation");
        assertNull(h.evalStatic(FakeTracks.class.getName(), "throwing", "DOOR", obj));
        assertNull(h.evalStatic(FakeTracks.class.getName(), "notAnArray", "DOOR", obj));
    }

    @Test
    void nullRuntimeStillReachesTheMethod() {
        // a door's currentAnimation is null while idle - only the mod's own
        // evaluator knows the at-rest pose, so null is passed through (a throw inside still
        // comes back null, as does a method that itself returns null for null).
        FakeTile te = new FakeTile();
        te.currentAnimation = null;
        TileHandle h = new TileHandleImpl(te);
        FieldPath obj = hops("k", "currentAnimation");
        assertArrayEquals(new double[]{0.0, 0.0},
                h.evalStatic(FakeTracks.class.getName(), "nullMeansRest", "DOOR", obj), 1e-12);
        assertNull(h.evalStatic(FakeTracks.class.getName(), "track", "DOOR", obj));
        assertNull(h.evalStatic(FakeTracks.class.getName(), "throwing", "DOOR", obj));
    }
}
