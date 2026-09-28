package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Smoke test on a 12-line synthetic tiny v2 file: header order, one full
 * class/field/method mapping round-tripping through the graph in BOTH
 * directions, zero audit contradictions (spec §128).
 */
class TinyV2SmokeTest {

    private static final String TINY = String.join("\n",
            "tiny\t2\t0\tofficial\tintermediary",
            "# synthetic fixture for the smoke test",
            "c\tcom/example/Legacy\tnet/minecraft/class_100",
            "\tf\tI\thealth\tfield_1001",
            "\tm\t(I)V\ttick\tmethod_2001",
            "\t\tp\t0\thitpoints", // parameter row must be skipped gracefully
            "c\tcom/example/Other\tnet/minecraft/class_200",
            "\tf\tLcom/example/Legacy;\tinner\tfield_2001",
            "\tm\t()Lcom/example/Legacy;\tmake\tmethod_2002",
            "c\tcom/example/Third\tnet/minecraft/class_300",
            "\tm\t()V\trun\tmethod_3001",
            "# end");

    @Test
    void parsesRoundTripsAndAuditsClean() throws IOException {
        Path dir = Files.createTempDirectory("umb-tiny-smoke");
        try {
            Path file = dir.resolve("mappings.tiny");
            Files.writeString(file, TINY + "\n", StandardCharsets.UTF_8);

            TinyV2Reader.TinyFile f = TinyV2Reader.read(file);

            // Header namespace order survives the parse.
            assertEquals(List.of("official", "intermediary"), f.namespaces());
            assertEquals(3, f.classes().size());

            TinyV2Reader.ClassEntry legacy = byNames(f, "com/example/Legacy");
            assertEquals("net/minecraft/class_100", legacy.names()[1]);
            assertEquals(1, legacy.fields().size());
            assertEquals(1, legacy.methods().size()); // parameter row added nothing

            Node official = new Node("1.20.1", "official");
            Node intermediary = new Node("1.20.1", "intermediary");
            DefaultMappingGraph g = new DefaultMappingGraph();
            g.addTinyFile(f, official, 0, intermediary, 1,
                    Provenance.PUBLISHED, "smoke.tiny");

            // CLASS forward...
            Optional<List<MappingEdge>> fwd = g.translate(
                    new Symbol(official, SymbolKind.CLASS, null, "com/example/Legacy", null),
                    intermediary);
            assertTrue(fwd.isPresent(), "class must translate forward");
            assertEquals(1, fwd.get().size());
            assertEquals("net/minecraft/class_100", fwd.get().get(0).to().name());

            // ...and REVERSE: a reverse hop matches edge.to and lands on
            // edge.from, so the arrival is tracked by walking the chain.
            Symbol backStart =
                    new Symbol(intermediary, SymbolKind.CLASS, null, "net/minecraft/class_100", null);
            Optional<List<MappingEdge>> rev = g.translate(backStart, official);
            assertTrue(rev.isPresent(), "class must translate in reverse");
            assertEquals("com/example/Legacy", walk(rev.get(), backStart).name());

            // METHOD round trip, primitive descriptor untouched.
            Optional<List<MappingEdge>> mfwd = g.translate(
                    new Symbol(official, SymbolKind.METHOD, "com/example/Legacy", "tick", "(I)V"),
                    intermediary);
            assertTrue(mfwd.isPresent());
            assertEquals("method_2001", mfwd.get().get(0).to().name());
            assertEquals("(I)V", mfwd.get().get(0).to().descriptor());
            Optional<List<MappingEdge>> mrev = g.translate(
                    new Symbol(intermediary, SymbolKind.METHOD, "net/minecraft/class_100", "method_2001", "(I)V"),
                    official);
            assertTrue(mrev.isPresent());
            assertEquals("tick", walk(mrev.get(),
                    new Symbol(intermediary, SymbolKind.METHOD, "net/minecraft/class_100", "method_2001", "(I)V")).name());

            // FIELD whose mapped-class descriptor is rewritten per side.
            Optional<List<MappingEdge>> ffwd = g.translate(
                    new Symbol(official, SymbolKind.FIELD, "com/example/Other", "inner", "Lcom/example/Legacy;"),
                    intermediary);
            assertTrue(ffwd.isPresent(), "field must translate forward");
            assertEquals("field_2001", ffwd.get().get(0).to().name());
            assertEquals("Lnet/minecraft/class_100;", ffwd.get().get(0).to().descriptor(),
                    "descriptor must follow the class mapping");

            // Audit: everything present at 'official' comes back unchanged.
            RoundtripAudit.AuditResult r =
                    RoundtripAudit.audit(g, official, intermediary, SymbolKind.CLASS, 10);
            assertEquals(3, r.tested());
            assertEquals(0, r.contradictions(), () -> "samples: " + r.samples());
            assertEquals(3, r.ok());
            assertTrue(r.samples().isEmpty());
            assertEquals(Provenance.PUBLISHED * Provenance.PUBLISHED, r.minPathConfidence(), 0.0);

            // Determinism: identical inputs give byte-identical chains.
            Symbol probe = new Symbol(official, SymbolKind.CLASS, null, "com/example/Legacy", null);
            List<MappingEdge> first = g.translate(probe, intermediary).orElseThrow();
            List<MappingEdge> second = g.translate(probe, intermediary).orElseThrow();
            assertEquals(first, second);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static TinyV2Reader.ClassEntry byNames(TinyV2Reader.TinyFile f, String officialName) {
        return f.classes().stream()
                .filter(c -> c.names()[0].equals(officialName))
                .findFirst()
                .orElseThrow();
    }

    /** Same chain-walking rule RoundtripAudit uses: forward hops land on to(), reverse hops on from(). */
    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of a scratch directory
                }
            });
        } catch (IOException ignored) {
            // same
        }
    }
}
