package dev.umb.hostagent.content;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Collapses duplicate legacy chat deliveries at the host boundary.
 *
 * <p>A legacy message can reach the host through both the server facade and the
 * client-facing facade during one join/tick transition. The two calls have no
 * shared event identity, so the stable identity is (player, exact text) within
 * a short delivery window. Messages with different text, or the same text
 * outside that window, remain independent.</p>
 */
final class ChatDeliveryDeduplicator {
    static final long WINDOW_NANOS = 250_000_000L;
    private static final ConcurrentMap<String, Long> LAST = new ConcurrentHashMap<>();

    private ChatDeliveryDeduplicator() {
    }

    static boolean accept(String playerIdentity, String text, long nowNanos) {
        String key = String.valueOf(playerIdentity) + '\u0000' + String.valueOf(text);
        for (;;) {
            Long previous = LAST.get(key);
            if (previous != null && nowNanos - previous.longValue() < WINDOW_NANOS) {
                return false;
            }
            if (previous == null) {
                if (LAST.putIfAbsent(key, Long.valueOf(nowNanos)) == null) {
                    return true;
                }
            } else if (LAST.replace(key, previous, Long.valueOf(nowNanos))) {
                return true;
            }
        }
    }

    static void clearForTests() {
        LAST.clear();
    }
}
