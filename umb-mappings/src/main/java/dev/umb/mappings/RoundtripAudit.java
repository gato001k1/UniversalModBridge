package dev.umb.mappings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Round-trip audit of a mapping graph (spec §128): for a sample of symbols at
 * node a, walk a -> b -> a and count every symbol that fails to return or
 * comes back under a different name. A graph that round-trips cleanly is the
 * precondition for trusting any single-direction translation built on it.
 */
public final class RoundtripAudit {

    private RoundtripAudit() {}

    /**
     * @param tested             symbols actually probed (may be below {@code limit}
     *                           when the pool at node a is smaller)
     * @param ok                 probes that returned under their own name
     * @param contradictions     untranslatable probes, dead returns, name mismatches
     * @param samples            up to 10 human-readable contradiction strings
     * @param minPathConfidence  lowest per-symbol path confidence observed — the
     *                           product of edge confidences along each full round
     *                           trip; {@link Double#NaN} EXCLUSIVELY means no probe
     *                           completed a round trip (or nothing was tested at
     *                           all). DefaultMappingGraph.addEdges rejects non-finite
     *                           and out-of-range confidences, so a completed trip can
     *                           never produce NaN and dilute this sentinel. Note that
     *                           ingesting BOTH directional copies of an edge makes
     *                           undirected traversal reuse the stronger copy in both
     *                           directions, tautologically inflating products toward
     *                           max-edge^2 — feed each physical mapping once.
     */
    public record AuditResult(int tested, int ok, int contradictions,
                              List<String> samples, double minPathConfidence) {}

    /**
     * Audits up to {@code limit} CLASS symbols present at node a through
     * a -> b -> a.
     *
     * @throws UnsupportedOperationException for FIELD/METHOD kinds (extension
     *                                       point reserved for a later wave)
     */
    public static AuditResult audit(MappingGraph g, Node a, Node b, SymbolKind kind, int limit) {
        Objects.requireNonNull(g, "graph");
        Objects.requireNonNull(a, "node a");
        Objects.requireNonNull(b, "node b");
        Objects.requireNonNull(kind, "kind");
        if (kind != SymbolKind.CLASS) {
            throw new UnsupportedOperationException(
                    "RoundtripAudit supports CLASS only this wave; " + kind + " is an extension point");
        }
        if (!(g instanceof DefaultMappingGraph dmg)) {
            throw new UnsupportedOperationException(
                    "RoundtripAudit requires DefaultMappingGraph this wave");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        List<Symbol> pool = dmg.symbolsAt(a, SymbolKind.CLASS);
        if (limit < pool.size()) {
            // Sorted deterministically inside symbolsAt, so a truncated audit is
            // reproducible run to run rather than sampling whatever the hash gave us.
            pool = pool.subList(0, limit);
        }

        int ok = 0;
        int contradictions = 0;
        List<String> samples = new ArrayList<>();
        double minPathConfidence = Double.NaN;
        boolean completedAny = false;

        for (Symbol s : pool) {
            var fwd = dmg.translate(s, b);
            if (fwd.isEmpty()) {
                contradictions++;
                sample(samples, "untranslatable " + s.kind() + " '" + s.name() + "' at " + s.node());
                continue;
            }
            List<MappingEdge> out = fwd.get();
            // Chains may contain backward hops (contract: a reverse hop matches
            // edge.to and lands on edge.from), so arrival is tracked by walking.
            Symbol mid = walk(out, s);
            var back = dmg.translate(mid, a);
            if (back.isEmpty()) {
                contradictions++;
                sample(samples, "no return path: '" + s.name() + "' -> '" + mid.name()
                        + "' cannot come back to " + a);
                continue;
            }
            List<MappingEdge> ret = back.get();
            double pc = product(out) * product(ret);
            // Track completion separately instead of using NaN as the seed: the old
            // Math.min(x, NaN)-then-replace accumulator both lost the running minimum
            // to a poisoned probe and let the NEXT finite probe erase that loss. A
            // NaN product (impossible while addEdges validates confidences, defensive
            // regardless) now simply never displaces an observed finite minimum.
            if (!completedAny || pc < minPathConfidence) {
                minPathConfidence = pc;
                completedAny = true;
            }
            Symbol home = walk(ret, mid);
            if (!Objects.equals(home.name(), s.name())) {
                contradictions++;
                sample(samples, "contradiction: '" + s.name() + "' arrived back as '" + home.name() + "'");
            } else {
                ok++;
            }
        }
        return new AuditResult(pool.size(), ok, contradictions, List.copyOf(samples), minPathConfidence);
    }

    private static void sample(List<String> samples, String line) {
        if (samples.size() < 10) {
            samples.add(line);
        }
    }

    /**
     * Position after walking a chain from {@code start}: a forward hop matches
     * edge.from and lands on edge.to; a reverse hop matches edge.to and lands
     * on edge.from (spec §128 chains are auditable precisely because position
     * is recoverable).
     */
    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }

    private static double product(List<MappingEdge> path) {
        double p = 1.0;
        for (MappingEdge e : path) {
            p *= e.confidence();
        }
        return p;
    }
}
