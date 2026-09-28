package dev.umb.hostagent.input;

import dev.umb.hostagent.AgentLog;

import java.util.concurrent.ConcurrentHashMap;

/** One-time / rate-limited live diagnostics for the input lane. Never throws. */
public final class LegacyInputDiag {
    private static final ConcurrentHashMap<String, Long> LAST = new ConcurrentHashMap<>();

    private LegacyInputDiag() { }

    /**
     * Returns true when {@code key} should be logged now: first occurrence always, then at
     * most once per {@code nanos} (0 = first occurrence only).
     */
    public static boolean oncePer(String key, long nanos) {
        if (key == null) return false;
        long now = System.nanoTime();
        Long prev = LAST.get(key);
        if (prev != null && (nanos <= 0 || now - prev < nanos)) return false;
        LAST.put(key, now);
        return true;
    }

    /** Hostagent log only. */
    public static void line(String message) {
        AgentLog.line("[UMB-INPUT] " + message);
    }

    /** Hostagent log and stdout (captured into latest.log). */
    public static void loud(String message) {
        AgentLog.loud("[UMB-INPUT] " + message);
    }
}
