package dev.umb.objbridge.itemeffects;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classloader-safe effects seam. Hostagent may call {@link #publish(String, String, long)} by
 * reflection without objbridge becoming a compile-time hostagent dependency.
 */
public final class HeldItemAnimationRegistry implements HeldItemAnimationSource {
    private static final HeldItemAnimationRegistry INSTANCE = new HeldItemAnimationRegistry();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private HeldItemAnimationRegistry() { }
    public static HeldItemAnimationRegistry instance() { return INSTANCE; }

    public static void publish(String itemId, String track, long startTick) {
        if (itemId != null) INSTANCE.states.put(itemId, new State(track, startTick));
    }

    public static void clear(String itemId) { if (itemId != null) INSTANCE.states.remove(itemId); }
    public String currentTrack(String itemId) { State s = states.get(itemId); return s == null ? null : s.track; }
    public long startTick(String itemId) { State s = states.get(itemId); return s == null ? 0L : s.startTick; }
    private record State(String track, long startTick) { }
}
