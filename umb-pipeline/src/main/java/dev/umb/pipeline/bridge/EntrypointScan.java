package dev.umb.pipeline.bridge;

import java.util.List;

/**
 * What a mod jar declares as entrypoints — read honestly from its metadata (Bridge v2
 * discovery). Empty results are information, not failure: {@code entrypoints} carries no
 * class unless the metadata actually named it, {@code sourcesSeen} names every metadata
 * source that was READ (even when it declared nothing), and {@code sourcesLookedFor}
 * names every source that was probed. A jar with no discoverable entrypoints reports
 * {@code sourcesSeen=[]} with {@code sourcesLookedFor} intact — the caller sees exactly
 * what was looked for and what was found, never a guessed class name.
 *
 * @param entrypoints   every declared entrypoint, sorted deterministically by
 *                      {@code (source, className)} so iteration order never depends on
 *                      jar or JSON entry order
 * @param sourcesSeen   metadata sources successfully read and scanned (e.g.
 *                      {@code fabric.mod.json}, {@code forge:@Mod}); a source that
 *                      declared nothing is still listed
 * @param sourcesLookedFor every metadata source this scan probes (present or not)
 */
public record EntrypointScan(
        List<Entrypoint> entrypoints,
        List<String> sourcesSeen,
        List<String> sourcesLookedFor) {

    public boolean hasEntrypoints() {
        return !entrypoints.isEmpty();
    }

    /**
     * One declared entrypoint.
     *
     * @param source    the declaring source, e.g. {@code fabric:main}, {@code fabric:client},
     *                  {@code forge:@Mod}
     * @param className dotted binary name of the entrypoint class
     */
    public record Entrypoint(String source, String className) {
    }
}