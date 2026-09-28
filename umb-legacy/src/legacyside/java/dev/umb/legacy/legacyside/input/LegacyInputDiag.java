package dev.umb.legacy.legacyside.input;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time / rate-limited live diagnostics for the .
 * <p>Lines go to stdout AND to an optional bridge sink.
 */
public final class LegacyInputDiag {
    /** Bridge-provided log target; null until the bridge sets it. */
    public interface Sink {
        void log(String message);
    }

    private static final ConcurrentHashMap<String, Long> LAST =
            new ConcurrentHashMap<String, Long>();
    private static volatile Sink sink;

    private LegacyInputDiag() { }

    /** Called once by the bridge entry point; cheap enough to call per tick. */
    public static void setSink(Sink s) {
        sink = s;
    }

    /**
     * Returns true when {@code key} should be logged now: first occurrence always, then at
     * most once per {@code nanos} (0 = first occurrence only).
     */
    public static boolean oncePer(String key, long nanos) {
        if (key == null) {
            return false;
        }
        long now = System.nanoTime();
        Long prev = LAST.get(key);
        if (prev != null && (nanos <= 0 || now - prev.longValue() < nanos)) {
            return false;
        }
        LAST.put(key, Long.valueOf(now));
        return true;
    }

    public static void log(String message) {
        System.out.println("[UMB-INPUT] " + message);
        System.out.flush();
        Sink s = sink;
        if (s != null) {
            try {
                s.log("[UMB-INPUT] " + message);
            } catch (Throwable ignored) {
                // Diagnostics must never break the input path.
            }
        }
    }

    /** Test-only reset. */
    public static void clearForTest() {
        LAST.clear();
        sink = null;
    }
}
