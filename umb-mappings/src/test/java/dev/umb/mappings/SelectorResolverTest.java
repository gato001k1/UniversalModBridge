package dev.umb.mappings;

import dev.umb.core.AtSelector;
import dev.umb.core.MemberSelector;
import dev.umb.core.Refmap;
import dev.umb.core.ResolvedSelector;
import org.junit.jupiter.api.Test;

import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-2: SelectorResolver via refmap + graph. Synthetic tiny fixtures only (D4).
 */
class SelectorResolverTest {

    private static DefaultMappingGraph graphWith(Node a, Node b, String... classNames) {
        // Build tiny file in-memory via graph edges directly
        DefaultMappingGraph g = new DefaultMappingGraph();
        java.util.List<MappingGraph.MappingEdge> edges = new java.util.ArrayList<>();
        for (String name : classNames) {
            String[] parts = name.split("=");
            edges.add(new MappingGraph.MappingEdge(
                    new Symbol(a, SymbolKind.CLASS, null, parts[0], null),
                    new Symbol(b, SymbolKind.CLASS, null, parts[1], null),
                    Provenance.PUBLISHED, "test"));
            // also add a method edge for the first class
            if (parts.length == 2) {
                edges.add(new MappingGraph.MappingEdge(
                        new Symbol(a, SymbolKind.METHOD, parts[0], "method_1234_foo", "()V"),
                        new Symbol(b, SymbolKind.METHOD, parts[1], "method_5678_bar", "()V"),
                        Provenance.PUBLISHED, "test"));
            }
        }
        g.addEdges(edges);
        return g;
    }

    @Test
    void refmapRemapsBeforeGraph() {
        Node src = new Node("1.12.2", "srg");
        Node dst = new Node("1.12.2", "intermediary");
        DefaultMappingGraph g = graphWith(src, dst, "net/minecraft/world/World=net/minecraft/class_123");
        String refmapJson = """
                {"mappings":{"com/example/Mixin":{"func_1234_a":"method_1234_foo"}}}
                """;
        Refmap ref = Refmap.loadFromJson(refmapJson);
        SelectorResolver r = new SelectorResolver(ref, g, src, dst);

        MemberSelector sel = MemberSelector.parse("func_1234_a");
        ResolvedSelector out = r.resolve(sel, "com/example/Mixin");
        assertTrue(out.isResolved(), () -> "unresolved: " + out.reason());
        assertEquals(ResolvedSelector.ResolutionKind.REMAPPED_VIA_REFMAP, out.kind());
        assertEquals("method_1234_foo", out.resolved().name());
    }

    @Test
    void graphTranslatesWhenNoRefmap() {
        Node src = new Node("1.12.2", "intermediary");
        Node dst = new Node("1.14.4", "intermediary");
        DefaultMappingGraph g = graphWith(src, dst, "net/minecraft/class_123=net/minecraft/class_123");
        SelectorResolver r = new SelectorResolver(Refmap.EMPTY, g, src, dst);

        MemberSelector sel = MemberSelector.parse("net/minecraft/class_123.method_1234_foo()V");
        ResolvedSelector out = r.resolve(sel, null);
        assertTrue(out.isResolved());
        assertEquals("method_5678_bar", out.resolved().name());
    }

    @Test
    void unresolvedReportsReason() {
        Node src = new Node("1.12.2", "srg");
        Node dst = new Node("26.2", "mojang");
        DefaultMappingGraph g = new DefaultMappingGraph(); // empty
        SelectorResolver r = new SelectorResolver(Refmap.EMPTY, g, src, dst);
        MemberSelector sel = MemberSelector.parse("func_999999_z()V");
        ResolvedSelector out = r.resolve(sel, null);
        assertFalse(out.isResolved());
        assertEquals(ResolvedSelector.ResolutionKind.UNRESOLVABLE, out.kind());
        assertNotNull(out.reason());
    }

    @Test
    void passthroughWhenNoGraph() {
        Node n = new Node("26.2", "mojang");
        SelectorResolver r = new SelectorResolver(Refmap.EMPTY, null, n, n);
        MemberSelector sel = MemberSelector.parse("net/minecraft/world/Level.getBlockState()V");
        ResolvedSelector out = r.resolve(sel, null);
        assertEquals(ResolvedSelector.ResolutionKind.PASSTHROUGH, out.kind());
    }

    @Test
    void resolveClassViaRefmapAndGraph() {
        Node src = new Node("1.7.10", "official");
        Node dst = new Node("1.7.10", "srg");
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addEdges(List.of(new MappingGraph.MappingEdge(
                new Symbol(src, SymbolKind.CLASS, null, "net/minecraft/world/World", null),
                new Symbol(dst, SymbolKind.CLASS, null, "ahb", null),
                Provenance.PUBLISHED, "test")));
        Refmap ref = Refmap.loadFromJson("{\"mappings\":{\"com/example/Mixin\":{\"net/minecraft/world/World\":\"ahb\"}}}");
        SelectorResolver r = new SelectorResolver(ref, g, src, dst);
        assertEquals("ahb", r.resolveClass("net/minecraft/world/World", "com/example/Mixin"));
        // graph provides global class mapping even when refmap misses the mixin; passthrough only for unmapped classes
        assertEquals("ahb", r.resolveClass("net/minecraft/world/World", "other/Mixin"));
        assertEquals("net/minecraft/other/Nope", r.resolveClass("net/minecraft/other/Nope", "other/Mixin"));
    }

    @Test
    void resolveAtTarget() {
        Node src = new Node("1.12.2", "srg");
        Node dst = new Node("1.12.2", "intermediary");
        DefaultMappingGraph g = graphWith(src, dst, "net/minecraft/world/World=net/minecraft/class_456");
        SelectorResolver r = new SelectorResolver(Refmap.EMPTY, g, src, dst);
        AtSelector at = AtSelector.parse("INVOKE", "net/minecraft/world/World.method_1234_foo()V", null, null, null, null, null, null, null, null, null);
        assertTrue(at.valid(), at.error());
        AtSelector out = r.resolveAt(at, null);
        assertTrue(out.valid());
        assertNotNull(out.target());
        assertEquals("method_5678_bar", out.target().name());
    }

    @Test
    void invalidSelectorStaysUnresolvable() {
        Node n = new Node("26.2", "mojang");
        SelectorResolver r = new SelectorResolver(Refmap.EMPTY, null, n, n);
        MemberSelector bad = MemberSelector.parse("   ");
        assertFalse(bad.valid());
        ResolvedSelector out = r.resolve(bad, null);
        assertEquals(ResolvedSelector.ResolutionKind.UNRESOLVABLE, out.kind());
    }
}
