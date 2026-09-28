package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileHandle;
import dev.umb.legacy.legacyside.TileHandleImpl;

/**
 * Door-live follow-up gate: {@link TileHandleImpl#dispatchRenderer} and
 * {@link TileHandleImpl#evalAnim} - the reflective render-dispatch and animation-synthesis
 * calls. Same idiom as {@link TileHandleEvalStaticTest}: real
 * {@code net.minecraft.tileentity.TileEntity} subclass fixtures, no boot, never mocked.
 * Entry/elapsed runs on the server wall clock; the fake mod clock is only the backdate
 * anchor, so elapsed assertions use real sleeps with generous bounds.
 */
class TileHandleAnimDispatchTest {

    static final class FakeAnim {
        long start;
    }

    static final class FakeHelper {
    }

    static final class FakeDecl {
        FakeAnim makeAnim(byte state, byte skin) {
            if (state == 2 || state == 3) {
                FakeAnim a = new FakeAnim();
                a.start = -1;
                return a;
            }
            return null;
        }

        public Object renderer() {
            return new FakeHelper();
        }

        public String boom() {
            throw new IllegalStateException("boom");
        }
    }

    static final class FakeClock {
        static long now = 100000L;

        public static long tick() {
            return now;
        }
    }

    /** Returns the elapsed the evaluator itself observes (clock minus backdated start). */
    static final class FakeTracks {
        public static double[] track(String name, FakeAnim anim) {
            if (anim == null) return null;
            return new double[]{(double) (FakeClock.tick() - anim.start), 7.0};
        }
    }

    static final class FakeTile extends TileEntity {
        FakeDecl decl = new FakeDecl();
        byte state;
        byte skin = 1;
    }

    private static FieldPath hops(String key, String... names) {
        String[] kinds = new String[names.length];
        for (int i = 0; i < kinds.length; i++) kinds[i] = "field";
        return new FieldPath(key, names, kinds);
    }

    private static final String DECL = FakeDecl.class.getName();
    private static final String TRACKS = FakeTracks.class.getName();
    private static final String CLOCK = FakeClock.class.getName();

    private double[] evalAnim(TileHandle h) {
        return h.evalAnim(DECL, "makeAnim", hops("recv", "decl"),
                new FieldPath[]{hops("state", "state"), hops("skin", "skin")},
                CLOCK, "tick", "start", TRACKS, "track", "DOOR");
    }

    @Test
    void dispatchRendererReturnsHelperClassName() {        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        assertEquals(FakeHelper.class.getName(),
                h.dispatchRenderer(DECL, "renderer", hops("recv", "decl")));
        assertNull(h.dispatchRenderer(DECL, "noSuchMethod", hops("recv", "decl")));
        assertNull(h.dispatchRenderer(DECL, "boom", hops("recv", "decl")));
        assertNull(h.dispatchRenderer(DECL, "renderer", hops("recv", "noSuchField")));
        assertNull(h.dispatchRenderer(null, "renderer", hops("recv", "decl")));
    }

    @Test
    void evalAnimTracksElapsedFromEntry() throws Exception {
        FakeTile te = new FakeTile();
        te.state = 3;
        TileHandle h = new TileHandleImpl(te);
        // Entry: elapsed 0 on the server wall clock.
        assertArrayEquals(new double[]{0.0, 7.0}, evalAnim(h), 1e-9);
        // ~120ms later the evaluator observes wall transit age (bounds generous: CI
        // timers are coarse, but elapsed must clearly advance yet stay sane).
        Thread.sleep(120L);
        double elapsed = evalAnim(h)[0];
        assertTrue(elapsed >= 50.0, "elapsed advanced, was " + elapsed);
        assertTrue(elapsed < 60000.0, "elapsed sane, was " + elapsed);
    }

    @Test
    void evalAnimAdvancesWhileModClockFrozen() throws Exception {
        FakeTile te = new FakeTile();
        te.state = 3;
        TileHandle h = new TileHandleImpl(te);
        // The mod clock never moves (stalled client): entry still lands at wall 0 ...
        FakeClock.now = 100000L;
        assertArrayEquals(new double[]{0.0, 7.0}, evalAnim(h), 1e-9);
        // ... and wall transit age still accrues, so the evaluator's backdated clip
        // reads wall elapsed even though the mod clock is frozen solid.
        Thread.sleep(120L);
        double elapsed = evalAnim(h)[0];
        assertTrue(elapsed >= 50.0, "elapsed advanced on frozen mod clock, was " + elapsed);
        assertTrue(elapsed < 60000.0, "elapsed sane, was " + elapsed);
    }

    @Test
    void evalAnimPreservesTransitEntryAtRest() throws Exception {
        FakeTile te = new FakeTile();
        te.state = 3;
        TileHandle h = new TileHandleImpl(te);
        double entryAge = evalAnim(h)[0];
        Thread.sleep(120L);
        // Rest-open: the provider yields no clip for state 1, so the transit entry is
        // preserved and the end holds (vanilla holds its last packet clip the same way).
        te.state = 1;
        double[] rest = evalAnim(h);
        assertTrue(rest != null);
        assertTrue(rest[0] >= entryAge, "rest holds transit end, was " + rest[0]);
    }

    @Test
    void evalAnimNullWithNoEntry() {
        FakeTile te = new FakeTile();
        te.state = 0;
        TileHandle h = new TileHandleImpl(te);
        // Rest-closed with no transit ever observed: provider null, no entry, honest null.
        assertNull(evalAnim(h));
    }

    @Test
    void evalAnimRejectsBadShapes() {
        FakeTile te = new FakeTile();
        te.state = 3;
        TileHandle h = new TileHandleImpl(te);
        FieldPath recv = hops("recv", "decl");
        FieldPath[] args = new FieldPath[]{hops("state", "state"), hops("skin", "skin")};
        assertNull(h.evalAnim("does.not.Exist", "makeAnim", recv, args,
                CLOCK, "tick", "start", TRACKS, "track", "DOOR"));
        assertNull(h.evalAnim(DECL, "makeAnim", recv, args,
                CLOCK, "noClock", "start", TRACKS, "track", "DOOR"));
        assertNull(h.evalAnim(DECL, "makeAnim", recv, args,
                CLOCK, "tick", "noField", TRACKS, "track", "DOOR"));
        assertNull(h.evalAnim(DECL, "makeAnim", recv, args,
                CLOCK, "tick", "start", TRACKS, "noTrack", "DOOR"));
        assertTrue(evalAnim(h) != null);
    }

    static class FakeBase extends TileEntity {
    }

    static final class FakeSub extends FakeBase {
    }

    static final class FakeOther extends TileEntity {
    }

    @Test
    void rendersAsMirrorsVanillaDispatch() {
        TileHandle h = new TileHandleImpl(new FakeSub());
        // Exact class and bound superclass both render (vanilla walks up); a sibling,
        // the vanilla base itself, and null never do.
        assertTrue(h.rendersAs(FakeSub.class.getName()));
        assertTrue(h.rendersAs(FakeBase.class.getName()));
        assertFalse(h.rendersAs(FakeOther.class.getName()));
        assertFalse(h.rendersAs(TileEntity.class.getName()));
        assertTrue(h.rendersAs(null));
    }
}
