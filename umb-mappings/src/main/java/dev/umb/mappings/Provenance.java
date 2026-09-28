package dev.umb.mappings;

/**
 * Canonical confidence weights for mapping-edge provenance (spec §16). Every
 * edge in the graph carries one of these (or a value between them produced by
 * combining evidence); the translate() tie-break and the roundtrip audit both
 * reason over these magnitudes, so they must stay stable across waves.
 */
public final class Provenance {

    /** Official/Fabric-published mapping used directly. */
    public static final double PUBLISHED = 1.0;

    /** Derived by jar-comparison matching. */
    public static final double DERIVED_MATCH = 0.7;

    /** Heuristic inference. */
    public static final double INFERRED = 0.4;

    private Provenance() {}
}
