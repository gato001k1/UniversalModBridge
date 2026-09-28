package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Real-data smoke over the runtime-fetched Mojang corpus (spec: runtime-fetch-only,
 * never committed — so this test SKIPS silently when the file is absent). It pins
 * three load-bearing facts against the published 1.20.1 corpora:
 *
 * <ol>
 *   <li>ProGuardReader parses the real 7.6 MB client.txt end-to-end;</li>
 *   <li>namespace identity: Level's obf name in client.txt is the SAME string
 *       Fabric's intermediary file carries in its "official" column ({@code cmm});</li>
 *   <li>the composition works: mojang →(client.txt) official →(intermediary) maps
 *       {@code net/minecraft/world/level/Level} to {@code net/minecraft/class_1937}
 *       and the composed roundtrip audit is clean at published confidence.</li>
 * </ol>
 */
class ProGuardSmokeTest {

    private static final String CLIENT_TXT = "research/mappings/1.20.1-client.txt";
    private static final String INTERMEDIARY =
            "research/mappings/_extracted-1.20.1/mappings/mappings.tiny";

    @Test
    void realClientTxtComposesWithIntermediaryThroughSharedOfficialNode() throws IOException {
        Path clientTxt = locate(CLIENT_TXT);
        assumeTrue(clientTxt != null, "runtime-fetched 1.20.1 client.txt absent — fetch first");
        Path tiny = locate(INTERMEDIARY);
        assumeTrue(tiny != null, "extracted 1.20.1 intermediary tiny absent — extract first");

        // 1. the real file parses
        TinyV2Reader.TinyFile proguard = ProGuardReader.read(clientTxt);
        assertEquals(List.of(ProGuardReader.NS_MOJANG, ProGuardReader.NS_OFFICIAL),
                proguard.namespaces());
        assertTrue(proguard.classes().size() > 7400,
                "1.20.1 client.txt carries 7,436 classes (near the intermediary file's 7,423), "
                        + "got " + proguard.classes().size());

        TinyV2Reader.TinyFile inter = TinyV2Reader.read(tiny);

        // 2. the shared "official" node really is the obfuscated runtime names:
        //    Level is cmm in BOTH corpora.
        TinyV2Reader.ClassEntry level = byOfficial(proguard, "net/minecraft/world/level/Level");
        assertEquals("cmm", level.names()[1],
                "client.txt must map Level to the obfuscated runtime name cmm");
        TinyV2Reader.ClassEntry interLevel = byOfficial(inter, "cmm");
        assertEquals("net/minecraft/class_1937", interLevel.names()[1],
                "the intermediary file must carry the same cmm through to class_1937");

        // 3. compose and prove the 2-hop chain
        Node mojang = new Node("1.20.1", "mojang");
        Node official = new Node("1.20.1", "official");
        Node intermediary = new Node("1.20.1", "intermediary");
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(proguard, mojang, 0, official, 1, Provenance.PUBLISHED, "1.20.1-client.txt");
        g.addTinyFile(inter, official, 0, intermediary, 1, Provenance.PUBLISHED, "mappings.tiny");

        Symbol levelSymbol = new Symbol(mojang, SymbolKind.CLASS, null,
                "net/minecraft/world/level/Level", null);
        Optional<List<MappingEdge>> chain = g.translate(levelSymbol, intermediary);
        assertTrue(chain.isPresent(), "mojang name must reach intermediary across two hops");
        assertEquals("net/minecraft/class_1937", walk(chain.get(), levelSymbol).name());

        RoundtripAudit.AuditResult r =
                RoundtripAudit.audit(g, mojang, intermediary, SymbolKind.CLASS, 25);
        assertEquals(25, r.tested(), "pool far exceeds the limit, so the audit must saturate");
        assertEquals(0, r.contradictions(), () -> "samples: " + r.samples());
        assertEquals(Provenance.PUBLISHED * Provenance.PUBLISHED, r.minPathConfidence(), 0.0,
                "two published hops compose to full confidence");
    }

    /** Upward search from the working directory (TinyFixtures pattern) for a repo-relative file; null when absent. */
    private static Path locate(String moduleRel) {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(moduleRel);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static TinyV2Reader.ClassEntry byOfficial(TinyV2Reader.TinyFile f, String officialName) {
        return f.classes().stream()
                .filter(c -> c.names()[0].equals(officialName))
                .findFirst()
                .orElseThrow();
    }

    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }
}
