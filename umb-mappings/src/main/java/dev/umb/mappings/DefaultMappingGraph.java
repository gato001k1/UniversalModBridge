package dev.umb.mappings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * In-memory {@link MappingGraph} (spec §8, §12): edges indexed both ways so
 * translation can hop forward across a published tiny file and backward across
 * derived ones without caring which side a file happened to be written from.
 *
 * <p>translate() runs a bidirectional BFS: hop-distance fields are computed
 * from BOTH the start symbol and the goal set, then the best chain is selected
 * by a dynamic pass over the induced shortest-path DAG. Among equal-length
 * chains the one with the highest MINIMUM edge confidence wins; exact ties go
 * to the lexicographically smallest sequence of edge source strings, which is
 * what makes repeated calls on identical inputs return identical chains.
 *
 * <p>Why the DP keeps Pareto SETS of labels per state instead of one dominant
 * label: bottleneck only decreases along a chain, so a prefix that currently
 * has the higher weakest link can TIE after a later weak edge — at which point
 * the lexicographically smaller prefix wins. A single label per state would
 * discard it; keeping incomparable labels (higher bottleneck vs lex-smaller
 * sources) preserves the documented optimum at bounded cost, because
 * incomparable prefixes require confidence inversions along parallel shortest
 * paths. Whatever survives reaches the goal sweep, where a total comparator
 * (bottleneck, then sources, then per-edge confidences, then terminal symbol)
 * arbitrates without consulting hash iteration order.
 *
 * <p>Per-translate cost scales with the reachable component (two BFS passes
 * plus the DP over its shortest-path states); the merged adjacency lists,
 * per-node goal sets and per-node backward hop fields are cached until the
 * next {@link #addEdges} batch so whole-pool audits do not rebuild them per
 * probe.
 */
public final class DefaultMappingGraph implements MappingGraph {

    private final Map<Symbol, List<MappingEdge>> out = new HashMap<>();
    private final Map<Symbol, List<MappingEdge>> in = new HashMap<>();
    /** Merged forward+reverse adjacency per symbol, rebuilt lazily after each addEdges batch. */
    private final Map<Symbol, List<MappingEdge>> adj = new HashMap<>();
    /** Symbols reachable-and-resident per target node, invalidated together with adj. */
    private final Map<Node, Set<Symbol>> goalCache = new HashMap<>();
    /**
     * Backward hop field per target node, invalidated together with adj. A
     * whole-pool audit probes many start symbols toward the SAME target; the
     * goal-side flood is identical for every probe, and recomputing it per probe
     * made each translate cost a full component walk (minutes at MC mapping
     * scale) instead of the start-side's few nodes.
     */
    private final Map<Node, Map<Symbol, Integer>> distBCache = new HashMap<>();

    public DefaultMappingGraph() {}

    /**
     * @throws IllegalArgumentException when an edge is null-shaped or carries a
     *                                  confidence outside [0, 1] — NaN, infinite
     *                                  and negative confidences would poison both
     *                                  the translate() tie-break and the audit's
     *                                  weakest-link metric downstream
     */
    @Override
    public void addEdges(List<MappingEdge> edges) {
        Objects.requireNonNull(edges, "edges");
        adj.clear();
        goalCache.clear();
        distBCache.clear();
        for (MappingEdge e : edges) {
            Objects.requireNonNull(e, "edge");
            Objects.requireNonNull(e.from(), "edge.from");
            Objects.requireNonNull(e.to(), "edge.to");
            double c = e.confidence();
            // One comparison rejects NaN and both infinities alongside range violations.
            if (!(c >= 0.0 && c <= 1.0)) {
                throw new IllegalArgumentException("edge confidence outside [0,1]: " + c);
            }
            out.computeIfAbsent(e.from(), k -> new ArrayList<>(2)).add(e);
            in.computeIfAbsent(e.to(), k -> new ArrayList<>(2)).add(e);
        }
    }

    /**
     * Convenience loader (spec §13): converts one parsed tiny file into edges
     * between Node(a) and Node(b) using columns colA/colB, all carrying the
     * given confidence and source tag. Class edges map bare names; field/method
     * edges carry the owner class of their side plus a descriptor rewritten
     * through the same file's class mapping when it references a mapped class
     * (primitives, arrays and unmapped references pass through verbatim).
     *
     * <p>Two consequences worth naming because they are silent: descriptors are
     * assumed to be in namespace[0] form (the module-local convention; a third
     * party column-dialect file would produce member symbols that match nothing
     * downstream), and rewriting only consults THIS file's class rows, so when
     * partial files compose, a member edge whose descriptor names a class absent
     * from its own file keeps that reference verbatim on both sides — the two
     * sides then never compare equal across the hop and translation for that
     * member quietly returns empty while classes still translate. Cross-file
     * descriptor resolution is reserved for a later wave. A member whose
     * descriptor is null — the srg field rows carry no type — is preserved as
     * null on BOTH sides: an unknown descriptor, never an invented one (D4),
     * and it only matches members that also carry no descriptor on the other
     * side.
     */
    public void addTinyFile(TinyV2Reader.TinyFile f, Node a, int colA, Node b, int colB,
                            double confidence, String source) {
        Objects.requireNonNull(f, "file");
        Objects.requireNonNull(a, "node a");
        Objects.requireNonNull(b, "node b");
        // Two passes: the descriptor rewriter needs the full class map before the
        // first member edge is emitted (forward references inside one file).
        // Tiny descriptors are in namespace[0] form, so EACH side gets them
        // rewritten from namespace 0 into its own column's terms.
        Map<String, String> zeroToA = new HashMap<>();
        Map<String, String> zeroToB = new HashMap<>();
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            zeroToA.put(name(c.names(), 0), name(c.names(), colA));
            zeroToB.put(name(c.names(), 0), name(c.names(), colB));
        }
        List<MappingEdge> edges = new ArrayList<>();
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            String ca = name(c.names(), colA);
            String cb = name(c.names(), colB);
            edges.add(new MappingEdge(
                    new Symbol(a, SymbolKind.CLASS, null, ca, null),
                    new Symbol(b, SymbolKind.CLASS, null, cb, null),
                    confidence, source));
            for (TinyV2Reader.FieldEntry fl : c.fields()) {
                String da = fl.descriptor() == null ? null : remap(fl.descriptor(), zeroToA);
                String db = fl.descriptor() == null ? null : remap(fl.descriptor(), zeroToB);
                edges.add(new MappingEdge(
                        new Symbol(a, SymbolKind.FIELD, ca, name(fl.names(), colA), da),
                        new Symbol(b, SymbolKind.FIELD, cb, name(fl.names(), colB), db),
                        confidence, source));
            }
            for (TinyV2Reader.MethodEntry m : c.methods()) {
                String da = m.descriptor() == null ? null : remap(m.descriptor(), zeroToA);
                String db = m.descriptor() == null ? null : remap(m.descriptor(), zeroToB);
                edges.add(new MappingEdge(
                        new Symbol(a, SymbolKind.METHOD, ca, name(m.names(), colA), da),
                        new Symbol(b, SymbolKind.METHOD, cb, name(m.names(), colB), db),
                        confidence, source));
            }
        }
        addEdges(edges);
    }

    private static String name(String[] names, int col) {
        if (col >= names.length || names[col] == null || names[col].isEmpty()) {
            throw new IllegalArgumentException("tiny entry missing name for column " + col);
        }
        return names[col];
    }

    /**
     * Best-effort descriptor rewrite: every {@code L...;} internal name found in
     * the class map is replaced; primitives ({@code I}, {@code V}, ...), arrays
     * and unmapped references stay verbatim.
     */
    private static String remap(String descriptor, Map<String, String> cls) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.contains("L")) {
            return descriptor;
        }
        StringBuilder sb = new StringBuilder(descriptor.length());
        int i = 0;
        while (i < descriptor.length()) {
            char ch = descriptor.charAt(i);
            if (ch == 'L') {
                int semi = descriptor.indexOf(';', i);
                if (semi < 0) {
                    sb.append(descriptor, i, descriptor.length());
                    break;
                }
                String inner = descriptor.substring(i + 1, semi);
                sb.append('L').append(cls.getOrDefault(inner, inner)).append(';');
                i = semi + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    @Override
    public Optional<List<MappingEdge>> translate(Symbol from, Node target) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(target, "target");
        if (from.node().equals(target)) {
            return Optional.of(List.of());
        }
        // Phase 1: undirected hop distances grown from BOTH ends — the start
        // field and the goal field live on the same move graph because every
        // hop may traverse its edge in either direction (a return journey is
        // often all-backward).
        Map<Symbol, Integer> distF = hopField(Set.of(from));
        Set<Symbol> goals = collectGoals(target);
        if (goals.isEmpty()) {
            return Optional.empty();
        }
        // Memoized: identical for every probe sharing this target node.
        Map<Symbol, Integer> distB = distBCache.computeIfAbsent(target,
                t -> hopField(collectGoals(t)));

        int d = Integer.MAX_VALUE;
        for (Map.Entry<Symbol, Integer> en : distF.entrySet()) {
            Integer db = distB.get(en.getKey());
            if (db != null && en.getValue() + db < d) {
                d = en.getValue() + db;
            }
        }
        if (d == Integer.MAX_VALUE) {
            return Optional.empty();
        }

        // Phase 2: Pareto-set DP over states that lie on SOME shortest path
        // (distF[s] + distB[s] == d). Each state keeps every prefix that no other
        // same-length prefix to the same state forecloses (see insertPareto), so a
        // lex-smaller prefix with the lower bottleneck survives to be overtaken by
        // a later weak edge. Levels are processed in sorted state order; whatever
        // hash iteration exists downstream of this loop only ever feeds
        // order-insensitive set operations — repeated calls give identical chains.
        Map<Symbol, List<Label>> best = new HashMap<>();
        best.put(from, List.of(new Label(List.of(), Double.POSITIVE_INFINITY, from)));
        List<Symbol> cur = List.of(from);
        for (int level = 0; level < d && !cur.isEmpty(); level++) {
            List<Symbol> sorted = new ArrayList<>(cur);
            sorted.sort(SYMBOL_ORDER);
            List<Symbol> next = new ArrayList<>();
            Map<Symbol, List<Label>> nextBest = new HashMap<>();
            for (Symbol u : sorted) {
                for (Label lu : best.get(u)) {
                    for (MappingEdge e : neighbours(u)) {
                        Symbol v = e.from().equals(u) ? e.to() : e.from();
                        Integer df = distF.get(v);
                        if (df == null || df != level + 1) {
                            continue;
                        }
                        Integer db = distB.get(v);
                        if (db == null || df + db != d) {
                            continue;
                        }
                        List<MappingEdge> path = new ArrayList<>(lu.path().size() + 1);
                        path.addAll(lu.path());
                        path.add(e);
                        Label cand = new Label(
                                List.copyOf(path), minConf(lu.bottleneck(), e.confidence()), v);
                        List<Label> bucket = nextBest.get(v);
                        if (bucket == null) {
                            bucket = new ArrayList<>(2);
                            bucket.add(cand);
                            nextBest.put(v, bucket);
                            next.add(v);
                        } else {
                            insertPareto(bucket, cand);
                        }
                    }
                }
            }
            best.putAll(nextBest);
            cur = next;
        }

        // Goal sweep driven from distF rather than the goal set: candidates are
        // exactly the states at hop distance d that are resident at the target,
        // and distF is small (a matching-shaped published file yields ~2 entries
        // per probe), whereas scanning/sorting the whole goal set per translate
        // made whole-pool audits quadratic. Determinism is unaffected because
        // compareLabels is a total order whose final key is the terminal symbol:
        // the strict minimum is unique no matter the iteration order.
        Label win = null;
        for (Map.Entry<Symbol, Integer> en : distF.entrySet()) {
            if (en.getValue() != d || !goals.contains(en.getKey())) {
                continue;
            }
            for (Label l : best.getOrDefault(en.getKey(), List.of())) {
                if (win == null || compareLabels(l, win) < 0) {
                    win = l;
                }
            }
        }
        return win == null ? Optional.empty() : Optional.of(win.path());
    }

    /**
     * Merged forward+reverse adjacency, cached until the next {@link #addEdges}
     * batch: BFS and the DP touch every reachable symbol several times per
     * translate, and rebuilding this merge on every visit was the hottest
     * allocation in whole-pool audits.
     */
    private List<MappingEdge> neighbours(Symbol s) {
        List<MappingEdge> cached = adj.get(s);
        if (cached != null) {
            return cached;
        }
        List<MappingEdge> o = out.get(s);
        List<MappingEdge> i = in.get(s);
        List<MappingEdge> merged;
        if (o == null) {
            merged = i == null ? List.of() : List.copyOf(i);
        } else if (i == null) {
            merged = List.copyOf(o);
        } else {
            List<MappingEdge> both = new ArrayList<>(o.size() + i.size());
            both.addAll(o);
            both.addAll(i);
            merged = List.copyOf(both);
        }
        adj.put(s, merged);
        return merged;
    }

    /**
     * BFS hop field over the undirected move graph (forward or reverse across
     * any edge whose endpoint matches the current state), seeded with the given
     * states at distance 0. BFS guarantees each state keeps its minimum distance,
     * so the result never depends on iteration order.
     */
    private Map<Symbol, Integer> hopField(Set<Symbol> seeds) {
        Map<Symbol, Integer> dist = new HashMap<>();
        ArrayDeque<Symbol> queue = new ArrayDeque<>();
        for (Symbol s : seeds) {
            dist.put(s, 0);
            queue.add(s);
        }
        while (!queue.isEmpty()) {
            Symbol u = queue.poll();
            int du = dist.get(u);
            for (MappingEdge e : neighbours(u)) {
                Symbol v = e.from().equals(u) ? e.to() : e.from();
                if (!dist.containsKey(v)) {
                    dist.put(v, du + 1);
                    queue.add(v);
                }
            }
        }
        return dist;
    }

    /**
     * Symbols resident at the target node, cached per node until the next
     * {@link #addEdges} batch: whole-pool audits probe hundreds of start symbols
     * toward the SAME target node, and rescanning both indexes per probe made
     * the audit quadratic in the graph size.
     */
    private Set<Symbol> collectGoals(Node target) {
        return goalCache.computeIfAbsent(target, t -> {
            Set<Symbol> goals = new HashSet<>();
            for (Symbol s : out.keySet()) {
                if (s.node().equals(t)) {
                    goals.add(s);
                }
            }
            for (Symbol s : in.keySet()) {
                if (s.node().equals(t)) {
                    goals.add(s);
                }
            }
            return Set.copyOf(goals);
        });
    }

    /** Chain label kept per DP state: the chain, its weakest link, where it lands. */
    private record Label(List<MappingEdge> path, double bottleneck, Symbol end) {}

    /**
     * Keeps the Pareto frontier of labels reaching one state. A candidate is
     * dropped only when an existing label forecloses every completion of it
     * ({@link #dominates}); labels it forecloses in turn are evicted. Identical
     * chains dedupe so fully-tied parallel routes cannot multiply.
     */
    private static void insertPareto(List<Label> bucket, Label cand) {
        for (Label kept : bucket) {
            if (kept.path().equals(cand.path()) || dominates(kept, cand)) {
                return;
            }
        }
        bucket.removeIf(kept -> dominates(cand, kept));
        bucket.add(cand);
    }

    private static double minConf(double a, double b) {
        double ea = eff(a);
        double eb = eff(b);
        return ea <= eb ? ea : eb;
    }

    /** NaN confidences sort below everything so a poisoned edge cannot win a tie-break. */
    private static double eff(double c) {
        return Double.isNaN(c) ? Double.NEGATIVE_INFINITY : c;
    }

    /**
     * True when x's completion can never lose to y's: x has the >= bottleneck AND
     * the <= source sequence (strictly better somewhere), so appending any common
     * suffix keeps x ahead or tied under {@link #compareLabels}. Bottleneck only
     * decreases along a chain, which is what makes the prefix comparison sound.
     */
    private static boolean dominates(Label x, Label y) {
        double ex = eff(x.bottleneck());
        double ey = eff(y.bottleneck());
        if (ex < ey) {
            return false;
        }
        int srcCmp = compareSources(x.path(), y.path());
        return ex > ey ? srcCmp <= 0 : srcCmp < 0;
    }

    /**
     * Total order over equally-long candidate chains: highest MINIMUM edge
     * confidence first, then lexicographically smallest source sequence (the
     * contract), then higher per-edge confidences, finally terminal symbol order —
     * the tail keys exist so contradictory duplicates carrying one tag resolve by
     * content rather than by hash layout.
     */
    private static int compareLabels(Label x, Label y) {
        double ex = eff(x.bottleneck());
        double ey = eff(y.bottleneck());
        if (ex != ey) {
            return ex > ey ? -1 : 1;
        }
        int c = compareSources(x.path(), y.path());
        if (c != 0) {
            return c;
        }
        c = compareConfidences(x.path(), y.path());
        if (c != 0) {
            return c;
        }
        return SYMBOL_ORDER.compare(x.end(), y.end());
    }

    /** Element-wise confidence comparison; prefers the more trustworthy interior. */
    private static int compareConfidences(List<MappingEdge> x, List<MappingEdge> y) {
        for (int i = 0; i < x.size(); i++) {
            double cx = x.get(i).confidence();
            double cy = y.get(i).confidence();
            if (cx != cy) {
                return cx > cy ? -1 : 1;
            }
        }
        return 0;
    }

    private static int compareSources(List<MappingEdge> x, List<MappingEdge> y) {
        int n = Math.min(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            String sx = x.get(i).source();
            String sy = y.get(i).source();
            int c = Objects.toString(sx, "").compareTo(Objects.toString(sy, ""));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.size(), y.size());
    }

    /**
     * Total ordering over symbols used wherever iteration could otherwise leak
     * HashMap order into results. Nulls sort first (class edges have no owner
     * or descriptor), matching the natural reading of sparse records.
     */
    private static final Comparator<Symbol> SYMBOL_ORDER = Comparator
            .comparing((Symbol s) -> s.node().version())
            .thenComparing(s -> s.node().ns())
            .thenComparing(s -> s.kind().name())
            .thenComparing(s -> s.owner(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(s -> s.name(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(s -> s.descriptor(), Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * Package-private enumeration for {@link RoundtripAudit} (spec §128): the
     * frozen MappingGraph interface has no edge accessor, and audit lives in
     * this package precisely so no extra public API surface is needed.
     */
    List<Symbol> symbolsAt(Node node, SymbolKind kind) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(kind, "kind");
        Set<Symbol> seen = new LinkedHashSet<>();
        for (Map.Entry<Symbol, List<MappingEdge>> en : out.entrySet()) {
            if (matches(en.getKey(), node, kind)) {
                seen.add(en.getKey());
            }
        }
        for (Symbol s : in.keySet()) {
            if (matches(s, node, kind)) {
                seen.add(s);
            }
        }
        List<Symbol> result = new ArrayList<>(seen);
        result.sort(SYMBOL_ORDER);
        return List.copyOf(result);
    }

    private static boolean matches(Symbol s, Node node, SymbolKind kind) {
        return s.kind() == kind && s.node().equals(node);
    }
}
