package dev.umb.mappings;
import dev.umb.core.AtSelector;
import dev.umb.core.MemberSelector;
import dev.umb.core.Refmap;
import dev.umb.core.ResolvedSelector;

import dev.umb.mappings.MappingGraph;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * M8-2: refmap → graph resolver for MemberSelector.
 * Resolution order per target symbol:
 *  1. refmap remap (classRef + original reference -> remapped string) — parse remapped as MemberSelector.
 *  2. graph translate on the (post-refmap) symbol toward target node.
 *  3. passthrough when already in target namespace.
 *  4. UNRESOLVABLE otherwise (D4).
 *
 * <p>Class-level selectors (target class names) are remapped by class name only.
 * Member selectors carry owner+name+desc: owner and descriptor class references are
 * remapped through the same path.
 */
public final class SelectorResolver {

    private final Refmap refmap;
    private final MappingGraph graph; // nullable — absentee means refmap-only
    private final Node sourceNode;    // nullable when unknown
    private final Node targetNode;

    public SelectorResolver(Refmap refmap, MappingGraph graph, Node sourceNode, Node targetNode) {
        this.refmap = refmap == null ? Refmap.EMPTY : refmap;
        this.graph = graph;
        this.sourceNode = sourceNode;
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
    }

    /**
     * Resolves one member selector. {@code mixinClassRef} is the owning mixin class
     * internal name (slash form) used as refmap context key — pass null to search all.
     */
    public ResolvedSelector resolve(MemberSelector sel, String mixinClassRef) {
        Objects.requireNonNull(sel, "sel");
        if (!sel.valid()) {
            return ResolvedSelector.unresolvable(sel, "invalid selector: " + sel.error());
        }
        // Step 1: refmap remap — the reference string is sel.raw() minus quantifier? In Mixin,
        // refmap keys are the literal member reference strings. For simplicity we map raw and
        // re-parse; caller can also map structured parts. We try raw first, then canonical.
        String rawKey = sel.raw().trim();
        String rawCanonical = canonical(sel);
        String remappedRaw = null;
        String usedKey = null;
        for (String key : new String[]{rawKey, rawCanonical}) {
            String hit = refmap.remap(mixinClassRef, key);
            if (!hit.equals(key)) { remappedRaw = hit; usedKey = key; break; }
            // also try without context narrowing only when caller didn't scope to a mixin
            if (mixinClassRef == null) {
                String hit2 = refmap.remap(null, key);
                if (!hit2.equals(key)) { remappedRaw = hit2; usedKey = key; break; }
            }
        }
        // Also try name-only key for bare method names
        if (remappedRaw == null && sel.owner() == null && sel.descriptor() == null) {
            String nameKey = sel.name();
            String hit = refmap.remap(mixinClassRef, nameKey);
            if (!hit.equals(nameKey)) { remappedRaw = hit; usedKey = nameKey; }
            else if (mixinClassRef == null) {
                String hit2 = refmap.remap(null, nameKey);
                if (!hit2.equals(nameKey)) { remappedRaw = hit2; usedKey = nameKey; }
            }
        }
        MemberSelector afterRefmap = sel;
        boolean viaRefmap = false;
        if (remappedRaw != null) {
            MemberSelector parsed = MemberSelector.parse(remappedRaw);
            if (parsed.valid()) {
                afterRefmap = parsed;
                viaRefmap = true;
            } else {
                // Remapped string invalid — fall through to graph on original
                viaRefmap = false;
            }
        }

        // Step 2: graph translate if graph present and not already at target
        if (graph != null && sourceNode != null && !sourceNode.equals(targetNode)) {
            // Build symbol to translate. For members, owner+name+desc; for bare names, owner null.
            SymbolKind kind = kindFor(afterRefmap);
            String owner = afterRefmap.owner();
            String name = afterRefmap.name();
            String desc = afterRefmap.descriptor();
            // Descriptor class refs should be remapped through class map where possible — but
            // SelectorResolver does not have a class remap table; graph translate handles descriptor
            // via its own addTinyFile rewriting. We pass descriptor verbatim.
            Symbol sym = new Symbol(sourceNode, kind, owner, name, desc);
            Optional<List<MappingGraph.MappingEdge>> path = graph.translate(sym, targetNode);
            if (path.isPresent() && !path.get().isEmpty()) {
                Symbol terminal = applyPath(sym, path.get());
                MemberSelector translated = selectorFromSymbol(afterRefmap, terminal);
                List<String> hops = path.get().stream().map(MappingGraph.MappingEdge::source).toList();
                double conf = path.get().stream().mapToDouble(MappingGraph.MappingEdge::confidence).reduce(1.0, (a, b) -> a * b);
                // If we already had a refmap hit, combine — still REMAPPED_VIA_REFMAP but with graph hops
                if (viaRefmap) {
                    List<String> combined = hops;
                    return ResolvedSelector.viaRefmap(sel, translated, conf, combined);
                }
                return ResolvedSelector.viaGraph(sel, translated, conf, hops);
            }
            // Same-node (empty path) is PASSTHROUGH-equivalent when symbol exists
            if (path.isPresent() && path.get().isEmpty()) {
                if (viaRefmap) return ResolvedSelector.viaRefmap(sel, afterRefmap, 1.0, List.of());
                return ResolvedSelector.passthrough(sel);
            }
        }

        if (viaRefmap) {
            return ResolvedSelector.viaRefmap(sel, afterRefmap, 1.0, List.of());
        }
        // No graph or unresolvable via graph: if source==target, passthrough
        if (sourceNode != null && sourceNode.equals(targetNode)) {
            return ResolvedSelector.passthrough(sel);
        }
        if (graph == null || sourceNode == null) {
            // No graph to consult — passthrough is honest when no mapping exists; UNRESOLVABLE would be wrong
            // when target is UNKNOWN. Leave as PASSTHROUGH so rewriter can keep string verbatim.
            return ResolvedSelector.passthrough(sel);
        }
        return ResolvedSelector.unresolvable(sel, "no graph path from " + sourceNode + " to " + targetNode + " for " + sel.raw());
    }

    /** Resolves a class name (slash form) via refmap/graph. */
    public String resolveClass(String className, String mixinClassRef) {
        Objects.requireNonNull(className, "className");
        String hit = refmap.remap(mixinClassRef, className);
        if (!hit.equals(className)) return hit;
        if (mixinClassRef == null) {
            hit = refmap.remap(null, className);
            if (!hit.equals(className)) return hit;
        }
        if (graph != null && sourceNode != null && !sourceNode.equals(targetNode)) {
            Symbol sym = new Symbol(sourceNode, SymbolKind.CLASS, null, className, null);
            Optional<List<MappingGraph.MappingEdge>> path = graph.translate(sym, targetNode);
            if (path.isPresent() && !path.get().isEmpty()) {
                Symbol term = applyPath(sym, path.get());
                return term.name();
            }
        }
        return className;
    }

    /** Resolves an AtSelector's target (if any) via the member resolver. */
    public AtSelector resolveAt(AtSelector at, String mixinClassRef) {
        Objects.requireNonNull(at, "at");
        if (at.target() == null) return at;
        ResolvedSelector r = resolve(at.target(), mixinClassRef);
        if (!r.isResolved() || r.kind() == ResolvedSelector.ResolutionKind.PASSTHROUGH) return at;
        // Rebuild At with resolved target string
        String newTarget = canonical(r.resolved());
        return AtSelector.parse(at.rawValue(), newTarget, at.ordinal(), at.opcode(),
                new ArrayList<>(at.args().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()),
                at.slice(), at.id(), at.remap(), at.unsafe(), null, null);
    }

    private static String canonical(MemberSelector m) {
        StringBuilder sb = new StringBuilder();
        if (m.owner() != null) sb.append(m.owner()).append('.');
        sb.append(m.name()).append(m.quantifier().raw() == null ? "" : m.quantifier().raw());
        if (m.descriptor() != null) {
            if (m.isField()) sb.append(':').append(m.descriptor());
            else sb.append(m.descriptor());
        }
        if (m.tail() != null) sb.append(" -> ").append(canonical(m.tail()));
        return sb.toString();
    }

    private static MemberSelector selectorFromSymbol(MemberSelector original, Symbol terminal) {
        // Preserve tail and quantifier from original; take owner/name/desc from terminal
        String owner = terminal.owner();
        String name = terminal.name();
        String desc = terminal.descriptor();
        boolean isField = desc != null && !desc.startsWith("(");
        return newSelector(original, owner, name, desc, isField);
    }

    private static MemberSelector newSelector(MemberSelector original, String owner, String name, String desc, boolean isField) {
        // Reconstruct raw-ish then reparse is simplest to keep invariants; avoid reflection on private ctor.
        StringBuilder raw = new StringBuilder();
        if (owner != null) raw.append(owner).append('.');
        raw.append(name).append(original.quantifier().raw());
        if (desc != null) {
            if (isField) raw.append(':').append(desc);
            else raw.append(desc);
        }
        if (original.tail() != null) raw.append(" -> ").append(original.tail().raw());
        MemberSelector parsed = MemberSelector.parse(raw.toString());
        if (parsed.valid()) return parsed;
        // Fallback — should not happen
        return original;
    }

    private static SymbolKind kindFor(MemberSelector m) {
        if (m.descriptor() == null) return SymbolKind.METHOD; // bare name — try METHOD; graph will miss if FIELD
        return m.isField() ? SymbolKind.FIELD : SymbolKind.METHOD;
    }

    private static Symbol applyPath(Symbol start, List<MappingGraph.MappingEdge> path) {
        Symbol cur = start;
        for (MappingGraph.MappingEdge e : path) {
            // Edge may be forward or reverse; apply by matching endpoint
            if (e.from().equals(cur)) cur = e.to();
            else if (e.to().equals(cur)) cur = e.from();
            else {
                // Descriptor mismatch or intermediate: follow by name propagation
                // This branch is rare; keep cur as terminal of edge that shares kind+name
                cur = e.to();
            }
        }
        return cur;
    }
}
