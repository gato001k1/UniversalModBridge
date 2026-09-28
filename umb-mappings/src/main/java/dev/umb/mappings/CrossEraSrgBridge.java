package dev.umb.mappings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * M9-2 cross-era class-pairing bridge over the MCP/Forge SRG namespace
 * (measure-first; the author re-measured against the published 1.7.10 and 1.12.2
 * joined.srg artifacts before this class was trusted).
 *
 * <p>The measurements dictate the shape. Class pairing key MUST be the shared
 * SRG className: 1419/1836 (77.3%) of the 1.7.10 classes have a same-named srg
 * class in 1.12.2, whereas bare obf-name identity (90.4% of obf strings recur)
 * lands on the SAME srg class only 18 times out of 1659 — the obfuscators
 * reassigned the alphabet, so literal-obf identity is a lie nearly everywhere.
 * Within the 1419 paired classes the srg member names do the member join,
 * class-scoped because func_/field_ names repeat across classes (never global).
 *
 * <p>Fields pair on the srg field name alone — FD rows carry no type, so the
 * descriptor is null on both sides and a type-check would be impossible
 * (D4: the descriptor is never invented). Methods pair on (srg name, srg
 * descriptor) exactly — measured 60.7% continuity; a method whose NAME recurs
 * on the partner class but whose descriptor does not is counted and dropped,
 * never emitted at guessed identity (D4 rules out the guess; the 746-name
 * ambiguity band is the honest report).
 *
 * <p>The bridge is a pure derivation on two srg-rooted tiny files: pairs are
 * identical srg names both sides, so the emitted rows carry the same name in
 * each column and only the (version, namespace) NODES the file is loaded at
 * differ — exactly what {@code --derived-tiny} needs to bind (1.7.10, srg) to
 * (1.12.2, srg). Deterministic by construction: sorted iteration everywhere,
 * descriptor comparisons on the raw namespace[0] form, no hash-order leakage —
 * repeated calls on identical inputs emit identical bytes (pinned by the
 * {@code umb bridge} sha256 printout on real data).
 */
public final class CrossEraSrgBridge {

    /**
     * Outcome of one bridge run. Counters are independent positive observables so
     * a test can pin each; {@code output} feeds {@link TinyV2Writer} directly.
     */
    public record BridgeResult(
            int oldClasses,
            int newClasses,
            int classesPaired,
            int fieldsPaired,
            int methodsPaired,
            int methodsNameOnlyDropped,
            List<TinyV2Reader.ClassEntry> output) {

        public int classesUnpaired() {
            return oldClasses - classesPaired;
        }

        /** Class edges + field edges + method edges the output file will carry. */
        public int totalEdges() {
            return classesPaired + fieldsPaired + methodsPaired;
        }
    }

    private CrossEraSrgBridge() {}

    /**
     * Pairs the srg-rooted tiny files {@code oldFile} (older era) and
     * {@code newFile} (newer era).
     *
     * @throws IllegalArgumentException when either file's namespace[0] is not
     *                                  {@link SrgReader#NS_SRG} (the descriptor
     *                                  dialect this derivation compares), or when
     *                                  a file has two classes under one srg name
     *                                  (degenerate input, caught before trusting it)
     */
    public static BridgeResult bridge(TinyV2Reader.TinyFile oldFile,
                                      TinyV2Reader.TinyFile newFile) {
        Objects.requireNonNull(oldFile, "oldFile");
        Objects.requireNonNull(newFile, "newFile");
        requireSrgRoot(oldFile, "oldFile");
        requireSrgRoot(newFile, "newFile");

        TreeMap<String, TinyV2Reader.ClassEntry> oldBySrg = indexBySrg(oldFile, "oldFile");
        TreeMap<String, TinyV2Reader.ClassEntry> newBySrg = indexBySrg(newFile, "newFile");

        List<TinyV2Reader.ClassEntry> output = new ArrayList<>();
        int classesPaired = 0;
        int fieldsPaired = 0;
        int methodsPaired = 0;
        int methodsNameOnly = 0;

        for (var en : oldBySrg.entrySet()) {
            String srgName = en.getKey();
            TinyV2Reader.ClassEntry other = newBySrg.get(srgName);
            if (other == null) {
                continue; // no same-named srg class in the newer era: unjoined, stays unmapped (D4)
            }
            classesPaired++;

            List<TinyV2Reader.FieldEntry> fields = new ArrayList<>();
            List<TinyV2Reader.MethodEntry> methods = new ArrayList<>();
            TreeSet<String> partnerFieldNames = new TreeSet<>();
            for (TinyV2Reader.FieldEntry f : other.fields()) {
                partnerFieldNames.add(f.names()[0]);
            }

            for (TinyV2Reader.FieldEntry f : en.getValue().fields()) {
                String name = f.names()[0];
                if (!partnerFieldNames.contains(name)) {
                    continue; // field renamed or removed; no type to fall back on (D4)
                }
                fields.add(new TinyV2Reader.FieldEntry(null,
                        new String[] { name, name }));
                fieldsPaired++;
            }

            TreeSet<String> partnerMethodKeys = new TreeSet<>();
            TreeSet<String> partnerMethodNames = new TreeSet<>();
            for (TinyV2Reader.MethodEntry m : other.methods()) {
                partnerMethodKeys.add(key(m.names()[0], m.descriptor()));
                partnerMethodNames.add(m.names()[0]);
            }
            for (TinyV2Reader.MethodEntry m : en.getValue().methods()) {
                String name = m.names()[0];
                if (partnerMethodKeys.contains(key(name, m.descriptor()))) {
                    methods.add(new TinyV2Reader.MethodEntry(m.descriptor(),
                            new String[] { name, name }));
                    methodsPaired++;
                } else if (partnerMethodNames.contains(name)) {
                    // Name recurs on the partner but no descriptor agrees: the method
                    // may have genuinely changed signature OR be a different method
                    // that inherited the name. Emitting an edge would be a guess (D4).
                    methodsNameOnly++;
                }
            }

            // Deterministic member order: fields before methods, names then descriptors.
            fields.sort((a, b) -> a.names()[0].compareTo(b.names()[0]));
            methods.sort((a, b) -> {
                int c = a.names()[0].compareTo(b.names()[0]);
                if (c != 0) {
                    return c;
                }
                return Objects.compare(a.descriptor(), b.descriptor(), String::compareTo);
            });
            output.add(new TinyV2Reader.ClassEntry(
                    new String[] { srgName, srgName }, fields, methods));
        }

        return new BridgeResult(oldBySrg.size(), newBySrg.size(),
                classesPaired, fieldsPaired, methodsPaired, methodsNameOnly,
                List.copyOf(output));
    }

    private static void requireSrgRoot(TinyV2Reader.TinyFile f, String which) {
        if (f.namespaces().isEmpty() || !SrgReader.NS_SRG.equals(f.namespaces().get(0))) {
            throw new IllegalArgumentException(
                    which + " namespace[0] must be '" + SrgReader.NS_SRG + "' for a bridge "
                            + "(descriptors are compared in that dialect), got "
                            + String.join(", ", f.namespaces()));
        }
    }

    private static TreeMap<String, TinyV2Reader.ClassEntry> indexBySrg(
            TinyV2Reader.TinyFile f, String which) {
        TreeMap<String, TinyV2Reader.ClassEntry> bySrg = new TreeMap<>();
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            String name = c.names()[0];
            if (bySrg.putIfAbsent(name, c) != null) {
                throw new IllegalArgumentException("degenerate " + which
                        + " input: two classes share srg name '" + name + "'");
            }
        }
        return bySrg;
    }

    private static String key(String name, String descriptor) {
        return name + "|" + descriptor;
    }
}