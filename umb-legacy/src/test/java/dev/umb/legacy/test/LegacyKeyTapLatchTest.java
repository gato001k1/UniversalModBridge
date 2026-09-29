package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.umb.legacy.legacyside.input.LegacyLwjglState;
import org.junit.jupiter.api.Test;

/**
 * A key tapped and released between two legacy client ticks must still be seen by exactly one
 * tick's level poll (Keyboard.isKeyDown / Mouse.isButtonDown), e.g. a quick R for a mod GUI key
 * that the mod edge-detects once per client tick.
 */
class LegacyKeyTapLatchTest {
    private static final String P = "latch-test-player";

    private static boolean keyInTick(int code) {
        LegacyLwjglState.begin(P);
        try {
            return LegacyLwjglState.isKeyDown(code);
        } finally {
            LegacyLwjglState.end();
        }
    }

    @Test
    void tapBetweenTicksIsSeenByExactlyOneTick() {
        LegacyLwjglState.clearPlayer(P);
        LegacyLwjglState.setDown(P, 19, true);
        LegacyLwjglState.setDown(P, 19, false); // released before any client tick ran
        assertTrue(keyInTick(19), "the next tick sees the tap");
        LegacyLwjglState.clientTickDone(P);
        assertFalse(keyInTick(19), "the tap is consumed after that tick");
        LegacyLwjglState.clearPlayer(P);
    }

    @Test
    void heldKeyStaysDownAcrossTicksAndReleaseEndsIt() {
        LegacyLwjglState.clearPlayer(P);
        LegacyLwjglState.setDown(P, 17, true);
        assertTrue(keyInTick(17));
        LegacyLwjglState.clientTickDone(P);
        assertTrue(keyInTick(17), "a held key is a level, not a latch");
        LegacyLwjglState.setDown(P, 17, false);
        LegacyLwjglState.clientTickDone(P);
        assertFalse(keyInTick(17));
        LegacyLwjglState.clearPlayer(P);
    }

    @Test
    void untouchedKeyIsUpAndMouseTapLatchesToo() {
        LegacyLwjglState.clearPlayer(P);
        assertFalse(keyInTick(20));
        LegacyLwjglState.setDown(P, -100, true);
        LegacyLwjglState.setDown(P, -100, false);
        LegacyLwjglState.begin(P);
        try {
            assertTrue(LegacyLwjglState.isButtonDown(0), "a click between ticks is seen once");
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.clientTickDone(P);
        LegacyLwjglState.begin(P);
        try {
            assertFalse(LegacyLwjglState.isButtonDown(0));
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.clearPlayer(P);
    }
}
