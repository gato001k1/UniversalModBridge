package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Round-trip audit contract (spec §128): a clean graph contradicts nothing,
 * one poisoned mapping surfaces as exactly one contradiction with a readable
 * sample, limits truncate deterministically, and non-CLASS kinds stay an
 * explicit extension point.
 */
class RoundtripAuditTest {

    private static final Node OFFICIAL = new Node("1.20.1", "official");
    private static final Node INTERMEDIARY = new Node("1.20.1", "intermediary");

    @Test
    void cleanGraphHasZeroContradictions() throws IOException {
        DefaultMappingGraph g = cleanGraph();

        RoundtripAudit.AuditResult r =
                RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, 100);
        assertEquals(4, r.tested(), "every fixture class is probed");
        assertEquals(4, r.ok());
        assertEquals(0, r.contradictions(), () -> "samples: " + r.samples());
        assertTrue(r.samples().isEmpty());
        assertEquals(Provenance.PUBLISHED, r.minPathConfidence(), 0.0,
                "all-published edges round-trip at confidence 1");
    }

    /**
     * A second edge set maps World to a different intermediary name at the SAME
     * confidence. The tie-break must resolve deterministically, so the wedge is
     * built to lose no coin flips: its forward edge carries the lexicographically
     * smaller source tag (beats "tiny-clean" forward), and its landing edge
     * carries the smallest of all (beats the wedge's own reverse on the way
     * back) — World therefore returns as Impostor instead of itself.
     */
    @Test
    void conflictingEdgeSetSurfacesAsContradictions() throws IOException {
        DefaultMappingGraph g = cleanGraph();
        Symbol world = new Symbol(OFFICIAL, SymbolKind.CLASS,
                null, "net/minecraft/world/World", null);
        Symbol ghost = new Symbol(INTERMEDIARY, SymbolKind.CLASS,
                null, "net/minecraft/class_6666", null);
        Symbol impostor = new Symbol(OFFICIAL, SymbolKind.CLASS,
                null, "net/minecraft/world/Impostor", null);
        g.addEdges(List.of(
                new MappingEdge(world, ghost, Provenance.PUBLISHED, "aa-corrupt-wedge"),
                new MappingEdge(ghost, impostor, Provenance.PUBLISHED, "aa-a-impostor")));

        RoundtripAudit.AuditResult r =
                RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, 100);

        // The impostor symbol joins the pool through its own incoming edge and
        // round-trips as itself, hence tested=5 / ok=4 / contradictions=1.
        assertEquals(5, r.tested());
        assertEquals(r.ok() + r.contradictions(), r.tested());
        assertEquals(1, r.contradictions(), () -> "samples: " + r.samples());
        assertTrue(!r.samples().isEmpty(), "a contradiction must carry a human-readable sample");
        assertTrue(r.samples().get(0).contains("Impostor"),
                () -> "sample should name the wrong arrival: " + r.samples().get(0));
    }

    @Test
    void limitTruncatesThePoolDeterministically() throws IOException {
        DefaultMappingGraph g = cleanGraph();

        RoundtripAudit.AuditResult two =
                RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, 2);
        assertEquals(2, two.tested(), "limit bounds the probe count");
        assertEquals(2, two.ok());
        assertEquals(0, two.contradictions());

        RoundtripAudit.AuditResult again =
                RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, 2);
        assertEquals(two, again, "truncation runs over sorted symbols, so it repeats exactly");

        assertEquals(4, RoundtripAudit
                .audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, Integer.MAX_VALUE).tested(),
                "a limit above the pool size audits everything");

        RoundtripAudit.AuditResult none =
                RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.CLASS, 0);
        assertEquals(0, none.tested());
        assertTrue(Double.isNaN(none.minPathConfidence()),
                "nothing tested means no path confidence was observed");
    }

    @Test
    void nonClassKindsRemainAnExplicitExtensionPoint() throws IOException {
        DefaultMappingGraph g = cleanGraph();
        assertThrows(UnsupportedOperationException.class,
                () -> RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.FIELD, 10));
        assertThrows(UnsupportedOperationException.class,
                () -> RoundtripAudit.audit(g, OFFICIAL, INTERMEDIARY, SymbolKind.METHOD, 10));
    }

    private static DefaultMappingGraph cleanGraph() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(f, OFFICIAL, 0, INTERMEDIARY, 1, Provenance.PUBLISHED, "tiny-clean");
        return g;
    }
}
