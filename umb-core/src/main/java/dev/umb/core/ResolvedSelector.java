package dev.umb.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * M8-2: result of resolving one selector through refmap + mapping graph.
 * D4 — never guesses: {@code resolved == false} carries a reason.
 */
public final class ResolvedSelector implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum ResolutionKind implements Serializable {
        /** Refmap produced a remapped reference (may also have graph hops). */
        REMAPPED_VIA_REFMAP,
        /** No refmap entry; graph translated the symbol. */
        TRANSLATED_VIA_GRAPH,
        /** Passthrough — selector already named in target namespace (no refmap, no graph). */
        PASSTHROUGH,
        /** No mapping found — contradiction (D4). */
        UNRESOLVABLE
    }

    private final MemberSelector original;
    private final MemberSelector resolved; // null when unresolvable
    private final ResolutionKind kind;
    private final String reason;           // non-null when UNRESOLVABLE
    private final double confidence;       // NaN when unresolvable
    private final java.util.List<String> hops; // graph edge sources when translated

    private ResolvedSelector(MemberSelector original, MemberSelector resolved,
                             ResolutionKind kind, String reason, double confidence,
                             java.util.List<String> hops) {
        this.original = Objects.requireNonNull(original, "original");
        this.resolved = resolved;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reason = reason;
        this.confidence = confidence;
        this.hops = hops == null ? java.util.List.of() : java.util.List.copyOf(hops);
    }

    public MemberSelector original() { return original; }
    public MemberSelector resolved() { return resolved; }
    public ResolutionKind kind() { return kind; }
    public String reason() { return reason; }
    public double confidence() { return confidence; }
    public java.util.List<String> hops() { return hops; }
    public boolean isResolved() { return kind != ResolutionKind.UNRESOLVABLE; }

    public static ResolvedSelector viaRefmap(MemberSelector original, MemberSelector remapped, double confidence, java.util.List<String> hops) {
        return new ResolvedSelector(original, remapped, ResolutionKind.REMAPPED_VIA_REFMAP, null, confidence, hops);
    }

    public static ResolvedSelector viaGraph(MemberSelector original, MemberSelector translated, double confidence, java.util.List<String> hops) {
        return new ResolvedSelector(original, translated, ResolutionKind.TRANSLATED_VIA_GRAPH, null, confidence, hops);
    }

    public static ResolvedSelector passthrough(MemberSelector original) {
        return new ResolvedSelector(original, original, ResolutionKind.PASSTHROUGH, null, 1.0, java.util.List.of());
    }

    public static ResolvedSelector unresolvable(MemberSelector original, String reason) {
        return new ResolvedSelector(original, null, ResolutionKind.UNRESOLVABLE, reason, Double.NaN, java.util.List.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedSelector r)) return false;
        return Double.compare(confidence, r.confidence) == 0 && Objects.equals(original, r.original)
                && Objects.equals(resolved, r.resolved) && kind == r.kind && Objects.equals(reason, r.reason)
                && Objects.equals(hops, r.hops);
    }

    @Override
    public int hashCode() { return Objects.hash(original, resolved, kind, reason, confidence, hops); }

    @Override
    public String toString() {
        if (kind == ResolutionKind.UNRESOLVABLE) return "ResolvedSelector[UNRESOLVABLE " + original.raw() + " reason=" + reason + "]";
        return "ResolvedSelector[" + kind + " " + original.raw() + " -> " + resolved + " conf=" + confidence + "]";
    }
}
