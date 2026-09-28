package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;
import dev.umb.legacy.legacyside.TileHandleImpl;

/**
 * gate: {@link TileHandleImpl#snapshotFields} - the bounded, mod-free reflection walk that reads named fields off a live legacy tile entity for a GUI's gauge/guard snapshot (TILE-FIELD-REQUIREMENTS..
 * Fixture classes ({@code FakeTank}, {@code FakeTile}) use...
 */
class TileHandleSnapshotFieldsTest {

    /** A plain embedded object, standing in for the real corpus's dominant "fluid tank" shape
     *  (TILE-FIELD-REQUIREMENTS.md ??5: 8 of 17 bounded-extra-hop fields are exactly this). */
    static final class FakeTank {
        int fluid = 750;
    }

    static final class FakeTile extends TileEntity {
        long power = 4200L;
        boolean redstone = true;
        FakeTank tank = new FakeTank();

        public long getMaxPower() {
            return 10000L;
        }
    }

    private static FieldPath field(String key, String name) {
        return new FieldPath(key, new String[]{name}, new String[]{"field"});
    }

    private static FieldPath accessor(String key, String name) {
        return new FieldPath(key, new String[]{name}, new String[]{"accessor"});
    }

    private static FieldPath twoHop(String key, String h1, String h1Kind, String h2, String h2Kind) {
        return new FieldPath(key, new String[]{h1, h2}, new String[]{h1Kind, h2Kind});
    }

    @Test
    void plainFieldOneHopOffTheTileEntityResolves() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{field("power", "power")});
        assertEquals(1, s.keys.length);
        assertEquals("power", s.keys[0]);
        assertTrue(s.present[0]);
        assertEquals(4200.0, s.values[0]);
    }

    @Test
    void booleanFieldWidensToOneOrZero() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{field("redstone", "redstone")});
        assertTrue(s.present[0]);
        assertEquals(1.0, s.values[0]);

        te.redstone = false;
        s = h.snapshotFields(new FieldPath[]{field("redstone", "redstone")});
        assertTrue(s.present[0]);
        assertEquals(0.0, s.values[0]);
    }

    @Test
    void boundedOneExtraHopThroughAnEmbeddedObjectResolves() {
        // The exact "tank.fluid" shape TILE-FIELD-REQUIREMENTS.md names as the dominant
        // bounded-extra-hop case.
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{twoHop("tank.fluid", "tank", "field", "fluid", "field")});
        assertTrue(s.present[0]);
        assertEquals(750.0, s.values[0]);
    }

    @Test
    void zeroArgAccessorCallResolves() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{accessor("getMaxPower()", "getMaxPower")});
        assertTrue(s.present[0]);
        assertEquals(10000.0, s.values[0]);
    }

    @Test
    void unknownFieldNameComesBackAbsentNeverZero() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{field("nope", "thisFieldDoesNotExist")});
        assertFalse(s.present[0], "an absent field must never be reported present with a fabricated 0");
    }

    @Test
    void invalidTileReportsEveryPathAbsentNeverAStaleValue() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        // sanity: resolves while valid
        assertTrue(h.snapshotFields(new FieldPath[]{field("power", "power")}).present[0]);

        te.func_145843_s(); // invalidate() - the tile is gone (e.g. broken while its GUI was open)
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{field("power", "power")});
        assertFalse(s.present[0], "a removed tile must report every field absent, never its last-known value");
    }

    @Test
    void multipleRequestedPathsAreIndependentAndOrderPreserving() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[]{
                field("power", "power"),
                field("nope", "doesNotExist"),
                twoHop("tank.fluid", "tank", "field", "fluid", "field")
        });
        assertEquals(3, s.keys.length);
        assertTrue(s.present[0]);
        assertEquals(4200.0, s.values[0]);
        assertFalse(s.present[1]);
        assertTrue(s.present[2]);
        assertEquals(750.0, s.values[2]);
    }

    @Test
    void emptyRequestReturnsTheSharedEmptySnapshot() {
        FakeTile te = new FakeTile();
        TileHandle h = new TileHandleImpl(te);
        TileFieldSnapshot s = h.snapshotFields(new FieldPath[0]);
        assertEquals(0, s.keys.length);
    }
}
