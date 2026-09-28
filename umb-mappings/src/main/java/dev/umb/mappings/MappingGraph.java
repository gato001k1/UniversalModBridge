package dev.umb.mappings;

import java.util.List;
import java.util.Optional;

/**
 * Canonical mapping graph over versions/namespaces (spec §8, §12). Nodes are
 * (version, namespace) pairs; edges carry symbol mappings with confidence (spec §16).
 */
public interface MappingGraph {

    record Node(String version, String ns) {}

    enum SymbolKind { CLASS, FIELD, METHOD }

    record Symbol(Node node, SymbolKind kind, String owner, String name, String descriptor) {}

    record MappingEdge(Symbol from, Symbol to, double confidence, String source) {}

    /**
     * Find a translation path for a symbol from source to target.
     * Returns the chain of edges taken so contradictions can be audited (spec §128).
     */
    Optional<List<MappingEdge>> translate(Symbol from, Node target);

    /** Register an edge set (e.g., loaded from a tiny file). */
    void addEdges(List<MappingEdge> edges);
}
