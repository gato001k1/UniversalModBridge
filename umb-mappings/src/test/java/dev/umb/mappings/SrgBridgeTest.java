package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * M9-2 cross-era bridge derivation against srg-rooted tiny files built directly under
 * the {@code [srg, official]} shape SrgReader always produces. Pins the measured
 * design: classes pair on shared SRG names — never literal-obf identity, which the
 * real 1.7.10/1.12.2 data shows lands on the same srg class only 18/1659 times —
 * fields pair on srg name alone with the type never invented (D4), methods pair on
 * (srg name, srg descriptor) exactly with the name-only ambiguity band counted but
 * never emitted, unjoined classes stay unmapped (D4), and repeated runs are
 * byte-deterministic (the property the umb bridge sha256 pin re-verifies on real
 * data).
 */
class SrgBridgeTest {

    private static final TinyV2Reader.TinyFile OLD = file(
            cls("net/minecraft/Server",
                    List.of(field("field_1_a", "a"), field("field_2_old", "b")),
                    List.of(method("func_1", "()V", "q"), method("func_2", "(I)V", "r"),
                            method("func_3", "()I", "s"))),
            cls("only/ol/Left"));

    private static final TinyV2Reader.TinyFile NEW = file(
            cls("net/minecraft/Server",
                    List.of(field("field_1_a", "a"), field("field_9_new", "x")),
                    List.of(method("func_1", "()V", "q"), method("func_2", "(Z)V", "r"))),
            cls("only/nw/Right"));

    // ------------------------------------------------------------------ pairing

    @Test
    void classesPairBySharedSrgName() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(2, r.oldClasses());
        assertEquals(2, r.newClasses());
        assertEquals(1, r.classesPaired());
        assertEquals(1, r.classesUnpaired(), "only/ol/Left has no same-named srg class in NEW");
        assertEquals(1, r.output().size(), "only the paired class is emitted");
        assertArrayEquals(new String[] { "net/minecraft/Server", "net/minecraft/Server" },
                r.output().get(0).names(),
                "the emitted row carries the shared srg name in both columns");
    }

    @Test
    void unjoinedClassesStayOutOfOutput() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(List.of("net/minecraft/Server"),
                r.output().stream().map(c -> c.names()[0]).toList());
    }

    @Test
    void fieldsPairBySrgNameWithNullDescriptor() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(1, r.fieldsPaired(),
                "field_1_a pairs; field_2_old is absent on the partner and is silently unmapped");
        assertEquals(1, r.output().get(0).fields().size());
        assertArrayEquals(new String[] { "field_1_a", "field_1_a" },
                r.output().get(0).fields().get(0).names());
        assertNull(r.output().get(0).fields().get(0).descriptor(),
                "FD rows carry no type; the field descriptor stays null on both sides (D4)");
    }

    @Test
    void methodsPairByNameAndDescriptor() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(1, r.methodsPaired(),
                "only func_1 ()V has a (name, descriptor) twin in NEW");
        assertEquals(1, r.output().get(0).methods().size());
        assertArrayEquals(new String[] { "func_1", "func_1" },
                r.output().get(0).methods().get(0).names());
        assertEquals("()V", r.output().get(0).methods().get(0).descriptor(),
                "the emitted descriptor is the srg-dialect namespace[0] form");
    }

    @Test
    void nameOnlyAmbiguousMethodsAreCountedNotEmitted() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(1, r.methodsNameOnlyDropped(),
                "func_2's name recurs on the partner but (I)V != (Z)V — ambiguous, dropped");
        assertEquals(List.of("func_1"),
                r.output().get(0).methods().stream().map(m -> m.names()[0]).toList(),
                "func_2 must NOT be emitted at guessed identity (D4); func_3's name is absent and stays silent");
    }

    @Test
    void edgesWrittenCountsAllThreeKinds() {
        CrossEraSrgBridge.BridgeResult r = CrossEraSrgBridge.bridge(OLD, NEW);
        assertEquals(3, r.totalEdges(), "1 class + 1 field + 1 method");
    }

    // ------------------------------------------------------------------ guards

    @Test
    void nonSrgRootNamespaceIsRejected() {
        TinyV2Reader.TinyFile rootless = new TinyV2Reader.TinyFile(
                List.of("official", "srg"),
                List.of(cls("net/minecraft/Server",
                        List.of(), List.of(method("func_1", "()V", "q")))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CrossEraSrgBridge.bridge(rootless, NEW));
        assertTrue(e.getMessage().contains("namespace[0] must be 'srg'"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("official, srg"), () -> e.getMessage());
    }

    @Test
    void degenerateDuplicateSrgClassNamesAreRejected() {
        TinyV2Reader.TinyFile dup = new TinyV2Reader.TinyFile(
                List.of("srg", "official"),
                List.of(cls("net/minecraft/Server"), cls("net/minecraft/Server")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CrossEraSrgBridge.bridge(dup, NEW));
        assertTrue(e.getMessage().contains("degenerate oldFile"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("net/minecraft/Server"), () -> e.getMessage());
    }

    // ------------------------------------------------------------ determinism

    @Test
    void repeatedBridgesAreDeterministic() {
        assertEquals(canon(CrossEraSrgBridge.bridge(OLD, NEW).output()),
                canon(CrossEraSrgBridge.bridge(OLD, NEW).output()),
                "identical inputs -> identical outputs, the property the umb bridge sha256 pins");
    }

    @Test
    void memberRowsAreSortedDeterministically() {
        // Same srg members as OLD, scrambled field/method input order; the partner's
        // EXTRA func_3 (Z)V is ignored because members project from the old side.
        TinyV2Reader.TinyFile messyNew = file(
                cls("net/minecraft/Server",
                        List.of(field("field_2_old", "b"), field("field_1_a", "a")),
                        List.of(method("func_3", "()I", "s"), method("func_1", "()V", "q"),
                                method("func_3", "(Z)V", "t"), method("func_2", "(I)V", "r"))));
        var r = CrossEraSrgBridge.bridge(OLD, messyNew);
        assertEquals(2, r.fieldsPaired());
        assertEquals(3, r.methodsPaired(),
                "func_3 (Z)V is a partner-side extra; only OLD's three methods project");
        List<TinyV2Reader.FieldEntry> fields = r.output().get(0).fields();
        List<TinyV2Reader.MethodEntry> methods = r.output().get(0).methods();
        assertEquals(List.of("field_1_a", "field_2_old"),
                fields.stream().map(f -> f.names()[0]).toList(),
                "fields are re-sorted by name regardless of input order");
        assertEquals(List.of("func_1()V", "func_2(I)V", "func_3()I"),
                methods.stream().map(m -> m.names()[0] + m.descriptor()).toList(),
                "fields first, then methods by name, then by descriptor");
    }

    private static TinyV2Reader.TinyFile file(TinyV2Reader.ClassEntry... classes) {
        return new TinyV2Reader.TinyFile(List.of("srg", "official"), List.of(classes));
    }

    private static TinyV2Reader.ClassEntry cls(String srgName,
                                               List<TinyV2Reader.FieldEntry> fields,
                                               List<TinyV2Reader.MethodEntry> methods) {
        return new TinyV2Reader.ClassEntry(new String[] { srgName, "obf" },
                fields, methods);
    }

    private static TinyV2Reader.ClassEntry cls(String srgName) {
        return cls(srgName, List.of(), List.of());
    }

    private static TinyV2Reader.FieldEntry field(String srgName, String obfName) {
        return new TinyV2Reader.FieldEntry(null, new String[] { srgName, obfName });
    }

    private static TinyV2Reader.MethodEntry method(String srgName, String desc, String obfName) {
        return new TinyV2Reader.MethodEntry(desc, new String[] { srgName, obfName });
    }

    /** Deterministic structural fingerprint: namespaces, then each class by srg name. */
    private static String canon(List<TinyV2Reader.ClassEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (TinyV2Reader.ClassEntry c : entries) {
            sb.append('c').append(String.join(",", c.names())).append('\n');
            for (TinyV2Reader.FieldEntry f : c.fields()) {
                sb.append("f").append(f.descriptor()).append('|')
                        .append(String.join(",", f.names())).append('\n');
            }
            for (TinyV2Reader.MethodEntry m : c.methods()) {
                sb.append("m").append(m.descriptor()).append('|')
                        .append(String.join(",", m.names())).append('\n');
            }
        }
        return sb.toString();
    }
}