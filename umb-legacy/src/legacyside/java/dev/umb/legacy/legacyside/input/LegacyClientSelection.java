package dev.umb.legacy.legacyside.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last client-selected hotbar slot per player, from input frames (change-gated, so the
 * stored slot always equals the client's current selection once any frame arrived).
 * Server-side selection diverges freely (automation selects without notifying the
 * client and vice versa), so the facade follows the client here: it is what the
 * player sees and uses. Falls back to host matching when no frame ever arrived.
 */
public final class LegacyClientSelection {
    private static final Map<String, Integer> SLOTS = new ConcurrentHashMap<String, Integer>();

    private LegacyClientSelection() { }

    public static void note(String player, int slot) {
        if (player != null && slot >= 0 && slot <= 8) {
            SLOTS.put(player, Integer.valueOf(slot));
        }
    }

    /** Last client slot, or null when no frame ever arrived for {@code player}. */
    public static Integer lastSlotFor(String player) {
        return player == null ? null : SLOTS.get(player);
    }

    /** Test-only reset. */
    public static void clearForTest() {
        SLOTS.clear();
    }
}
