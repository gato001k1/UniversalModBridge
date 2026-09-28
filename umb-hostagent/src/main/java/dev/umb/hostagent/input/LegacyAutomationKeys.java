package dev.umb.hostagent.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automation-injected legacy key holds, merged into the per-tick sampled levels.
 *
 * <p>{@code press_legacy_key} cannot reach the client's runTick, so it queues a hold here;
 * the server-tick input apply ({@code LegacyServerTickHook}) merges live holds into every
 * player's frame before {@code acceptInput}. A hold lasts {@link #HOLD_NANOS_DEFAULT}
 * (~5 server ticks): long enough to survive tick alignment, short enough that the legacy
 * mirror derives a clean press/release edge pair. Expired holds are dropped on read.</p>
 */
public final class LegacyAutomationKeys {
    static final long HOLD_NANOS_DEFAULT = 250_000_000L;

    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();

    private LegacyAutomationKeys() { }

    /** Queues a hold for one press/release cycle. Unknown ids are kept; the mirror ignores them. */
    public static void press(String stableId) {
        press(stableId, HOLD_NANOS_DEFAULT);
    }

    static void press(String stableId, long holdNanos) {
        if (stableId == null || stableId.isBlank()) return;
        PENDING.put(stableId, System.nanoTime() + holdNanos);
    }

    /** True when at least one hold is live (purging expired ones). */
    public static boolean hasLive() {
        long now = System.nanoTime();
        boolean live = false;
        for (var it = PENDING.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (e.getValue() < now) {
                it.remove();
            } else {
                live = true;
            }
        }
        return live;
    }

    /** Copy of the sampled levels with every live automation hold forced down. */
    public static Map<String, Boolean> merged(Map<String, Boolean> sampled) {
        long now = System.nanoTime();
        Map<String, Boolean> out = new LinkedHashMap<>(
                sampled == null ? Map.of() : sampled);
        for (var it = PENDING.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (e.getValue() < now) {
                it.remove();
                continue;
            }
            out.put(e.getKey(), Boolean.TRUE);
        }
        return Collections.unmodifiableMap(out);
    }

    static void clearForTest() {
        PENDING.clear();
    }
}
