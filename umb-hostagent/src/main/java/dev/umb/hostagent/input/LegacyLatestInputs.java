package dev.umb.hostagent.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Latest input frame per player, written on payload receipt, read on the server tick. */
public final class LegacyLatestInputs {
    private static final Map<String, LegacyInputFrame> LATEST = new ConcurrentHashMap<>();

    private LegacyLatestInputs() { }

    public static void put(LegacyInputFrame frame) {
        if (frame != null) {
            LATEST.put(frame.playerId(), frame);
        }
    }

    public static LegacyInputFrame get(String playerId) {
        return playerId == null ? null : LATEST.get(playerId);
    }

    public static void remove(String playerId) {
        if (playerId != null) {
            LATEST.remove(playerId);
        }
    }
}
