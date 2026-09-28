package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Graph behavior on the three-namespace fixture (spec §8, §12, §16): column
 * selection, forward AND reverse translation across a composed two-file graph,
 * deterministic chains, unreachable targets, and confidence propagation into
 * the roundtrip audit's weakest-link metric.
 */
class DefaultMappingGraphTest {

    private static final Node OFFICIAL = new Node("1.20.1", "official");
    private static final Node INTERMEDIARY = new Node("1.20.1", "intermediary");
    private static final Node NAMED = new Node("1.20.1", "named");

    private static final Symbol MINECRAFT_SERVER =
            new Symbol(OFFICIAL, SymbolKind.CLASS, null, "net/minecraft/server/MinecraftServer", null);

    /**
     * One file loaded twice as two edge sets: official->intermediary on columns
     * 0/1, intermediary->named on columns 1/2. Composing them yields a graph
     * where official symbols reach named ones in exactly two hops.
     */
    private static DefaultMappingGraph composedGraph(double oiConfidence, double inConfidence)
            throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(f, OFFICIAL, 0, INTERMEDIARY, 1, oiConfidence, "tiny-o-i");
        g.addTinyFile(f, INTERMEDIARY, 1, NAMED, 2, inConfidence, "tiny-i-n");
        return g;
    }

    @Test
    void columnSelectionPicksDifferentColumns() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());

        DefaultMappingGraph toIntermediary = new DefaultMappingGraph();
        toIntermediary.addTinyFile(f, OFFICIAL, 0, INTERMEDIARY, 1, Provenance.PUBLISHED, "sel-int");
        DefaultMappingGraph toNamed = new DefaultMappingGraph();
        toNamed.addTinyFile(f, OFFICIAL, 0, NAMED, 2, Provenance.PUBLISHED, "sel-named");

        Optional<List<MappingEdge>> viaInt = toIntermediary.translate(MINECRAFT_SERVER, INTERMEDIARY);
        assertTrue(viaInt.isPresent());
        assertEquals("net/minecraft/class_2966", walk(viaInt.get(), MINECRAFT_SERVER).name(),
                "column 1 is the intermediary name");

        Optional<List<MappingEdge>> viaNamed = toNamed.translate(MINECRAFT_SERVER, NAMED);
        assertTrue(viaNamed.isPresent());
        assertEquals("com/example/server/Server", walk(viaNamed.get(), MINECRAFT_SERVER).name(),
                "column 2 is the named name — same input symbol, different column pair");

        // Descriptors are rewritten from namespace[0] toward the loaded column:
        // cross-class references follow the same class mapping.
        Symbol loadWorld = new Symbol(OFFICIAL, SymbolKind.METHOD,
                "net/minecraft/server/MinecraftServer", "loadWorld",
                "(Lnet/minecraft/world/World;)V");
        assertEquals("(Lnet/minecraft/class_111;)V",
                walk(toIntermediary.translate(loadWorld, INTERMEDIARY).orElseThrow(), loadWorld).descriptor());
        assertEquals("(Lcom/example/world/Level;)V",
                walk(toNamed.translate(loadWorld, NAMED).orElseThrow(), loadWorld).descriptor());
    }

    @Test
    void translatesForwardAndReverseAcrossComposedGraph() throws IOException {
        DefaultMappingGraph g = composedGraph(Provenance.PUBLISHED, Provenance.PUBLISHED);

        // Forward: official -> intermediary -> named.
        List<MappingEdge> fwd = g.translate(MINECRAFT_SERVER, NAMED).orElseThrow();
        assertEquals(2, fwd.size(), "two loaded edge sets => a two-hop chain");
        assertEquals("com/example/server/Server", walk(fwd, MINECRAFT_SERVER).name());

        // Reverse: named -> ... -> official, traversing both edges backwards.
        Symbol serverNamed =
                new Symbol(NAMED, SymbolKind.CLASS, null, "com/example/server/Server", null);
        List<MappingEdge> rev = g.translate(serverNamed, OFFICIAL).orElseThrow();
        assertEquals(2, rev.size());
        assertEquals("net/minecraft/server/MinecraftServer", walk(rev, serverNamed).name());

        // Single-hop reverse through one file's edges alone.
        Symbol worldInt =
                new Symbol(INTERMEDIARY, SymbolKind.CLASS, null, "net/minecraft/class_111", null);
        assertEquals("net/minecraft/world/World",
                walk(g.translate(worldInt, OFFICIAL).orElseThrow(), worldInt).name());
    }

    @Test
    void repeatedCallsAndIndependentGraphsGiveIdenticalChains() throws IOException {
        DefaultMappingGraph g1 = composedGraph(Provenance.PUBLISHED, Provenance.PUBLISHED);
        DefaultMappingGraph g2 = composedGraph(Provenance.PUBLISHED, Provenance.PUBLISHED);

        List<MappingEdge> first = g1.translate(MINECRAFT_SERVER, NAMED).orElseThrow();
        List<MappingEdge> second = g1.translate(MINECRAFT_SERVER, NAMED).orElseThrow();
        assertEquals(first, second, "same instance, same inputs -> byte-identical chain");

        List<MappingEdge> freshInstance = g2.translate(MINECRAFT_SERVER, NAMED).orElseThrow();
        assertEquals(first, freshInstance,
                "no HashMap iteration order may leak into the chosen chain across instances");
    }

    @Test
    void unreachableTargetYieldsEmptyOptional() throws IOException {
        DefaultMappingGraph g = composedGraph(Provenance.PUBLISHED, Provenance.PUBLISHED);

        assertTrue(g.translate(MINECRAFT_SERVER, new Node("9.9.9", "void")).isEmpty(),
                "a node with no symbols at all is unreachable");

        Optional<List<MappingEdge>> sameNode = g.translate(MINECRAFT_SERVER, OFFICIAL);
        assertTrue(sameNode.isPresent() && sameNode.get().isEmpty(),
                "asking for the source node itself yields an empty (but present) chain");
    }

    @Test
    void confidencePropagatesThroughTwoHopsIntoAudit() throws IOException {
        DefaultMappingGraph g = composedGraph(Provenance.PUBLISHED, Provenance.DERIVED_MATCH);

        List<MappingEdge> chain = g.translate(MINECRAFT_SERVER, NAMED).orElseThrow();
        assertEquals(2, chain.size());
        assertEquals("tiny-o-i", chain.get(0).source());
        assertEquals(Provenance.PUBLISHED, chain.get(0).confidence(), 0.0);
        assertEquals("tiny-i-n", chain.get(1).source());
        assertEquals(Provenance.DERIVED_MATCH, chain.get(1).confidence(), 0.0);

        // The audit multiplies per direction and across the full round trip:
        // (1.0 * 0.7) out and (0.7 * 1.0) back => 0.7^2 for every probe.
        RoundtripAudit.AuditResult r =
                RoundtripAudit.audit(g, OFFICIAL, NAMED, SymbolKind.CLASS, 100);
        assertEquals(4, r.tested());
        assertEquals(4, r.ok());
        assertEquals(0, r.contradictions(), () -> "samples: " + r.samples());
        assertEquals(Provenance.DERIVED_MATCH * Provenance.DERIVED_MATCH,
                r.minPathConfidence(), 1e-12,
                "weakest-link must reflect the product along the round trip");
    }

    /**
     * The contract's tie-break across a mixed-confidence multi-hop graph: two
     * parallel X->Y prefixes (PUBLISHED "zzz-late", DERIVED_MATCH "aaa-early")
     * followed by an INFERRED bridge Y->Z. Both full chains bottleneck at 0.4, so
     * the lex-smaller source sequence ["aaa-early", "bridge"] must win even though
     * its FIRST edge is weaker — a DP keeping one dominant label per state prunes
     * it prematurely because the pruning happens before the equalizing weak edge.
     */
    @Test
    void paretoTieBreakKeepsLexSmallerPrefixThroughBottleneckEqualizer() {
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addEdges(List.of(
                new MappingEdge(x(), y(), Provenance.PUBLISHED, "zzz-late"),
                new MappingEdge(x(), y(), Provenance.DERIVED_MATCH, "aaa-early"),
                new MappingEdge(y(), z(), Provenance.INFERRED, "bridge")));

        List<MappingEdge> chain = g.translate(X, TARGET_Z).orElseThrow();
        assertEquals(2, chain.size());
        assertEquals("aaa-early", chain.get(0).source(),
                "the weaker-but-lex-smaller prefix must survive to be overtaken by the bridge");
        assertEquals("bridge", chain.get(1).source());
        assertEquals(Provenance.DERIVED_MATCH * Provenance.INFERRED,
                chain.get(0).confidence() * chain.get(1).confidence(), 0.0);
    }

    /** Same graph, opposite ingestion order: the documented optimum may not depend on insertion order. */
    @Test
    void paretoTieBreakIsInsertionOrderIndependent() {
        DefaultMappingGraph reversed = new DefaultMappingGraph();
        reversed.addEdges(List.of(
                new MappingEdge(y(), z(), Provenance.INFERRED, "bridge"),
                new MappingEdge(x(), y(), Provenance.DERIVED_MATCH, "aaa-early"),
                new MappingEdge(x(), y(), Provenance.PUBLISHED, "zzz-late")));

        assertTrue(new DefaultMappingGraph().translate(X, TARGET_Z).isEmpty(),
                "sanity: an empty graph finds nothing");
        List<MappingEdge> chain = reversed.translate(X, TARGET_Z).orElseThrow();
        assertEquals("aaa-early", chain.get(0).source());
        assertEquals("bridge", chain.get(1).source());
    }

    /**
     * Two contradictory duplicates under ONE source tag tie completely (length,
     * bottleneck, sources). Neither label dominates, so the winner must come from
     * the total comparator's final key — terminal symbol order — never from
     * HashSet iteration layout.
     */
    @Test
    void completeTiesResolveByTerminalSymbolNotHashOrder() {
        Symbol alpha = new Symbol(INTERMEDIARY, SymbolKind.CLASS, null, "net/minecraft/alpha", null);
        Symbol beta = new Symbol(INTERMEDIARY, SymbolKind.CLASS, null, "net/minecraft/beta", null);

        DefaultMappingGraph betaFirst = new DefaultMappingGraph();
        betaFirst.addEdges(List.of(new MappingEdge(MINECRAFT_SERVER, beta, Provenance.PUBLISHED, "same-tag")));
        betaFirst.addEdges(List.of(new MappingEdge(MINECRAFT_SERVER, alpha, Provenance.PUBLISHED, "same-tag")));

        DefaultMappingGraph alphaFirst = new DefaultMappingGraph();
        alphaFirst.addEdges(List.of(new MappingEdge(MINECRAFT_SERVER, alpha, Provenance.PUBLISHED, "same-tag")));
        alphaFirst.addEdges(List.of(new MappingEdge(MINECRAFT_SERVER, beta, Provenance.PUBLISHED, "same-tag")));

        for (DefaultMappingGraph g : List.of(betaFirst, alphaFirst)) {
            List<MappingEdge> chain = g.translate(MINECRAFT_SERVER, INTERMEDIARY).orElseThrow();
            assertEquals("net/minecraft/alpha", chain.get(0).to().name(),
                    "terminal-name tie-break beats bucket order regardless of ingestion");
        }
    }

    /**
     * Confidence validation at the boundary (spec §16 magnitudes): NaN, infinities
     * and out-of-range values would poison both the translate() tie-break and the
     * audit's weakest-link product, so they are refused where the edges enter.
     */
    @Test
    void addEdgesRejectsConfidenceOutsideUnitRange() {
        DefaultMappingGraph g = new DefaultMappingGraph();
        assertThrows(IllegalArgumentException.class,
                () -> g.addEdges(List.of(edge(Double.NaN))));
        assertThrows(IllegalArgumentException.class,
                () -> g.addEdges(List.of(edge(Double.POSITIVE_INFINITY))));
        assertThrows(IllegalArgumentException.class,
                () -> g.addEdges(List.of(edge(-0.5))));
        assertThrows(IllegalArgumentException.class,
                () -> g.addEdges(List.of(edge(1.0000001))));
        // bounds are inclusive
        g.addEdges(List.of(edge(0.0), edge(1.0)));
    }

    private static MappingEdge edge(double confidence) {
        return new MappingEdge(
                new Symbol(OFFICIAL, SymbolKind.CLASS, null, "a/A", null),
                new Symbol(INTERMEDIARY, SymbolKind.CLASS, null, "net/minecraft/class_A", null),
                confidence, "probe");
    }

    private static final Node TARGET_Z = new Node("1.20.1", "named");
    private static final Symbol X =
            new Symbol(OFFICIAL, SymbolKind.CLASS, null, "com/example/X", null);

    private static Symbol x() {
        return X;
    }

    private static Symbol y() {
        return new Symbol(INTERMEDIARY, SymbolKind.CLASS, null, "net/minecraft/class_Y", null);
    }

    private static Symbol z() {
        return new Symbol(TARGET_Z, SymbolKind.CLASS, null, "com/example/Z", null);
    }

    /** Same chain-walking rule RoundtripAudit uses: forward hops land on to(), reverse hops on from(). */
    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }
}
