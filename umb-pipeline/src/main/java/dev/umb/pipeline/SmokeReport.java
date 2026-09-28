package dev.umb.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Result of a headless smoke run (compatibility ladder stage 3: "classes load under
 * host classloader shape"). Every {@code *.class} entry of the mod jar is either
 * LOADED (defined by the JVM and every class it statically references exists in the
 * host-shaped loader universe) or FAILED with the linkage root cause; nothing is
 * silently swallowed — every entry lands in exactly one bucket.
 *
 * <p>Determinism: the sample maps are {@link TreeMap}s, so iteration (and therefore
 * both human and JSON rendering) is ordered by key regardless of jar entry order.
 * {@link #missingSample(int)} / {@link #failureSample(int)} apply the presentation
 * cap; the underlying maps always hold the complete picture.
 *
 * @param totalClasses      every {@code *.class} entry in the mod jar
 * @param skipped           module-info / package-info entries (excluded from loading)
 * @param parseableEntries  entries taken to the loader ({@code totalClasses - skipped})
 * @param loaded            defined + every CP class reference resolved under the host shape
 * @param failed            parseable entries that did not load ({@code parseableEntries - loaded})
 * @param missingSymbols    absent class (binary name) -> number of FAILED classes citing it
 * @param failures          FAILED class (binary name) -> linkage root-cause text
 */
public record SmokeReport(
        int totalClasses,
        int skipped,
        int parseableEntries,
        int loaded,
        int failed,
        Map<String, Integer> missingSymbols,
        Map<String, String> failures
) {
    /** True iff every parseable entry loaded — the only green verdict. */
    public boolean clean() {
        return failed == 0;
    }

    /** Capped, key-sorted missing-symbol sample (class -> citing FAILED classes). */
    public List<Map.Entry<String, Integer>> missingSample(int limit) {
        return capped(missingSymbols.entrySet(), limit);
    }

    /** Capped, key-sorted per-failure sample (FAILED class -> reason). */
    public List<Map.Entry<String, String>> failureSample(int limit) {
        return capped(failures.entrySet(), limit);
    }

    private static <K, V> List<Map.Entry<K, V>> capped(java.util.Set<Map.Entry<K, V>> ordered, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var out = new ArrayList<Map.Entry<K, V>>(Math.min(limit, ordered.size()));
        for (var e : ordered) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }
}