package dev.umb.legacy.legacyside;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Legacy compatibility behavior. */
public final class UmbStub {

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<String, AtomicInteger>();

    /** Declared (facadeClass -> total member count, implemented count) for the coverage report. */
    private static final Map<String, int[]> DECLARED = new ConcurrentHashMap<String, int[]>();

    private UmbStub() {
    }

    public static void hit(String facadeClass, String member) {
        String key = facadeClass + "#" + member;
        AtomicInteger c = HITS.get(key);
        if (c == null) {
            c = new AtomicInteger();
            AtomicInteger existing = HITS.putIfAbsent(key, c);
            if (existing != null) {
                c = existing;
            }
        }
        c.incrementAndGet();
        if (LOGGED.add(key)) {
            System.out.println("[UMB-STUB] " + key + " called - stub, returning a safe default");
        }
    }

    public static int hitCount(String facadeClass, String member) {
        AtomicInteger c = HITS.get(facadeClass + "#" + member);
        return c == null ? 0 : c.get();
    }

    /** Registers how many members {@code facadeClass} implements for real vs. the total it stubs+implements. */
    public static void declare(String facadeClass, int implemented, int stubbed) {
        DECLARED.put(facadeClass, new int[] {implemented, stubbed});
    }

    /** {@code facadeClass -> "implemented=N stubbed=N total=N"}, sorted, for the coverage report. */
    public static Map<String, String> coverageReport() {
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, int[]> e : DECLARED.entrySet()) {
            int impl = e.getValue()[0];
            int stub = e.getValue()[1];
            out.put(e.getKey(), "implemented=" + impl + " stubbed=" + stub + " total=" + (impl + stub));
        }
        return out;
    }

    /** Every distinct stub actually hit this run, with its hit count - the work-queue list. */
    public static Map<String, Integer> hitReport() {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        for (Map.Entry<String, AtomicInteger> e : HITS.entrySet()) {
            out.put(e.getKey(), Integer.valueOf(e.getValue().get()));
        }
        return out;
    }
}
