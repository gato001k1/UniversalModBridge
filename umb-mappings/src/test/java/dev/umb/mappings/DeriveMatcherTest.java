package dev.umb.mappings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeriveMatcher vertical: synthetic two-version jar pairs built with ASM and
 * matched through every pass the algorithm promises — exact-name classes,
 * fingerprint-renamed classes, honest unmatched, ambiguity collisions, member
 * M1/M2, determinism, and the emitted file's round trip through TinyV2Reader
 * into DefaultMappingGraph translating BOTH directions at DERIVED_MATCH
 * confidence.
 */
class DeriveMatcherTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ helpers

    private static byte[] clazz(String name, MemberSpec... members) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        for (MemberSpec ms : members) {
            if (ms.kind() == Kind.FIELD) {
                cw.visitField(Opcodes.ACC_PUBLIC, ms.name(), ms.desc(), null, null).visitEnd();
            } else {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, ms.name(), ms.desc(),
                        null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private record MemberSpec(Kind kind, String name, String desc) {}

    private enum Kind { FIELD, METHOD }

    private static MemberSpec f(String name, String desc) {
        return new MemberSpec(Kind.FIELD, name, desc);
    }

    private static MemberSpec m(String name, String desc) {
        return new MemberSpec(Kind.METHOD, name, desc);
    }

    /**
     * Writes a jar whose entries are the given classes. Each spec is
     * {@code <name>!<k>|<memberName>|<desc>[!<k>|<memberName>|<desc>...]} where
     * k is f or m. {@code jar(tmp, "in.jar", "a/b/C!f|x|I")} yields one class
     * {@code a/b/C} with field {@code x}.
     */
    private static Path jar(Path dir, String name, String... specs) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String s : specs) {
            String[] parts = s.split("!", -1);
            String className = parts[0];
            List<MemberSpec> members = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] mp = parts[i].split("\\|", -1);
                members.add("f".equals(mp[0]) ? f(mp[1], mp[2]) : m(mp[1], mp[2]));
            }
            entries.put(className + ".class",
                    clazz(className, members.toArray(new MemberSpec[0])));
        }
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static Path corruptJar(Path dir) throws IOException {
        Path p = dir.resolve("corrupt.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("broken/NotAClass.class"));
            out.write("this is not a classfile".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/ex/Same.class"));
            out.write(clazz("com/ex/Same", f("a", "I")));
            out.closeEntry();
        }
        return p;
    }

    private static Path emptyJar(Path dir) throws IOException {
        Path p = dir.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            // manifest-only entry; no classes
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
        }
        return p;
    }

    /** Writes the match result as a tiny file under the given header labels. */
    private static Path writeTiny(Path dir, String name, DeriveMatcher.MatchResult r,
                                  String labelA, String labelB) throws IOException {
        List<TinyV2Reader.ClassEntry> classes = new ArrayList<>();
        for (DeriveMatcher.ClassMatch cm : r.classes()) {
            List<TinyV2Reader.FieldEntry> fields = new ArrayList<>();
            List<TinyV2Reader.MethodEntry> methods = new ArrayList<>();
            for (DeriveMatcher.MemberMatch mm : cm.members()) {
                String[] names = { mm.srcName(), mm.dstName() };
                if (mm.kind() == SymbolKind.FIELD) {
                    fields.add(new TinyV2Reader.FieldEntry(mm.descriptor(), names));
                } else {
                    methods.add(new TinyV2Reader.MethodEntry(mm.descriptor(), names));
                }
            }
            classes.add(new TinyV2Reader.ClassEntry(
                    new String[] { cm.srcName(), cm.dstName() }, fields, methods));
        }
        Path p = dir.resolve(name);
        TinyV2Writer.write(p, List.of(labelA, labelB), classes);
        return p;
    }

    /**
     * Terminal position after a translate chain (spec §128 contract, same walk
     * RoundtripAudit uses): a forward hop matches edge.from and lands on
     * edge.to, a reverse hop matches edge.to and lands on edge.from.
     */
    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }

    // ------------------------------------------------------------------ class passes

    @Test
    void exactClassNamesAndStableMembersMatch() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Same!f|a|I!m|b|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/Same!f|a|I!m|b|(I)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(1, r.parseableSrcClasses(), () -> "one parseable class per side");
        assertEquals(1, r.parseableDstClasses(), () -> "one parseable class per side");
        assertEquals(1, r.classesExact(), () -> "identical FQN is pass 1");
        assertEquals(0, r.classesFingerprint(), () -> "pass 1 took the pair");
        assertEquals(0, r.classCollisions(), () -> "no ambiguity");
        assertEquals(0, r.unmatchedSrcClasses(), () -> "everyone matched");
        assertEquals(0, r.unmatchedDstClasses(), () -> "everyone matched");
        assertEquals(2, r.membersExact(), () -> "field and method both unchanged -> M1");
        assertEquals(0, r.membersUniqueDesc(), () -> "nothing renamed");
        assertEquals(3, r.totalEdges(), () -> "class + field + method");
    }

    /** A renamed class whose member signatures are stable lands on the fingerprint pass. */
    @Test
    void renamedClassWithStableMembersMatchesByFingerprint() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Old!f|a|I!m|c|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/New!f|a|I!m|d|(I)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(0, r.classesExact(), () -> "names differ, so not pass 1");
        assertEquals(1, r.classesFingerprint(), () -> "structure-identical renamed class");
        assertEquals(1, r.membersExact(), () -> "field keeps its name across the rename");
        assertEquals(1, r.membersUniqueDesc(), () -> "method (I)V unique + renamed -> M2");
        assertEquals(0, r.unmatchedSrcClasses());
        assertEquals(0, r.unmatchedDstClasses());
        assertEquals(3, r.totalEdges(), () -> "class + field + method");
    }

    /** A restructured class that exists under a different name in both versions stays unmatched. */
    @Test
    void restructuredClassInBothVersionsStaysUnmatched() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Restructured!m|x|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/Shuffled!m|x|(I)V!f|n|I");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(0, r.classesExact(), () -> "no FQN identity");
        assertEquals(0, r.classesFingerprint(), () -> "fingerprints differ (extra field on dst)");
        assertEquals(0, r.classCollisions(), () -> "disjoint fingerprints are not collisions");
        assertEquals(1, r.unmatchedSrcClasses(), () -> "honest: never a guessed edge");
        assertEquals(1, r.unmatchedDstClasses(), () -> "honest: never a guessed edge");
        assertEquals(0, r.totalEdges(), () -> "no class edge, hence nothing member-matched");
    }

    /** Two structurally identical classes on each side: the fingerprint is ambiguous. */
    @Test
    void fingerprintCollisionDropsAllClaimantsOnBothSides() throws IOException {
        Path src = jar(tmp, "src.jar",
                "com/ex/SameA!m|t|()V",
                "com/ex/SameB!m|t|()V");
        Path dst = jar(tmp, "dst.jar",
                "com/ex/XY!m|u|()V",
                "com/ex/ZW!m|v|()V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(2, r.parseableSrcClasses());
        assertEquals(2, r.parseableDstClasses());
        assertEquals(0, r.classesExact());
        assertEquals(0, r.classesFingerprint(), () -> "ambiguity forbids the edge");
        assertEquals(1, r.classCollisions(), () -> "one ambiguous fingerprint group");
        assertEquals(2, r.unmatchedSrcClasses(), () -> "drop ALL claimants, never guess");
        assertEquals(2, r.unmatchedDstClasses(), () -> "drop ALL claimants, never guess");
        assertEquals(0, r.totalEdges());
    }

    /** One-side duplicate still drops every claimant — uniqueness is judged per side. */
    @Test
    void sourceSideCardinalityDropsTheWholeCollisionGroup() throws IOException {
        Path src = jar(tmp, "src.jar",
                "com/ex/Twin!m|t|()V",
                "com/ex/Copy!m|t|()V");
        Path dst = jar(tmp, "dst.jar",
                "com/ex/Solo!m|t|()V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(0, r.classesFingerprint(), () -> "claimed by 2+ source classes");
        assertEquals(1, r.classCollisions());
        assertEquals(2, r.unmatchedSrcClasses());
        assertEquals(1, r.unmatchedDstClasses());
        assertEquals(0, r.totalEdges(), () -> "even the solo dst twin is not guessed");
    }

    // ------------------------------------------------------------------ member pass

    /** Same-descriptor overloads stay unmatched after a rename — M2 demands uniqueness. */
    @Test
    void overloadedSameDescriptorMembersAreNotMatchedAfterRename() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/OL!m|a|(I)V!m|b|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/OL!m|a|(I)V!m|c|(I)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(1, r.membersExact(), () -> "the name-stable 'a' pairs via M1");
        assertEquals(0, r.membersUniqueDesc(), () -> "(I)V is duplicated -> M2 must not fire");
        assertEquals(2, r.totalEdges(), () -> "class + one M1 member; the renamed overload hangs");
    }

    /** One matrix run where every reported counter is NONZERO (D8: pin the nonzero case). */
    @Test
    void singleMatrixRunPinsEveryCounterNonzero() throws IOException {
        Path src = jar(tmp, "src.jar",
                "com/ex/Same!f|a|I",                              // pass 1 exact, M1 field
                "com/ex/Old!m|c|(I)V",                            // pass 2 fingerprint, M2 method
                "com/ex/AmbA!m|p|()V",                            // collision group (2v2)
                "com/ex/AmbB!m|q|()V");
        Path dst = jar(tmp, "dst.jar",
                "com/ex/Same!f|a|I",
                "com/ex/New!m|d|(I)V",
                "com/ex/AmbX!m|p|()V",
                "com/ex/AmbY!m|q|()V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(4, r.parseableSrcClasses(), () -> "4 classes each side");
        assertEquals(4, r.parseableDstClasses(), () -> "4 classes each side");
        assertEquals(1, r.classesExact(), () -> "Same");
        assertEquals(1, r.classesFingerprint(), () -> "Old -> New");
        assertEquals(1, r.classCollisions(), () -> "the Amb group");
        assertEquals(1, r.membersExact(), () -> "field a -> a");
        assertEquals(1, r.membersUniqueDesc(), () -> "method (I)V c -> d");
        assertEquals(2, r.unmatchedSrcClasses(), () -> "AmbA + AmbB");
        assertEquals(2, r.unmatchedDstClasses(), () -> "AmbX + AmbY");
        assertEquals(4, r.totalEdges(), () -> "2 class edges + 2 member edges");
    }

    // ------------------------------------------- B3: descriptor canonicalization

    /** A member whose descriptor references a renamed class now M2-matches the hop. */
    @Test
    void renamedDescriptorReferenceMatchesAfterClassCanonicalization() throws IOException {
        Path src = jar(tmp, "src.jar",
                "a/Block!m|x|()V",                     // stable marker -> fingerprint-pairs to b/Block
                "r/S!m|run|(La/Block;)V");             // descriptor references the renamed class
        Path dst = jar(tmp, "dst.jar",
                "b/Block!m|x|()V",
                "r/S!m|driv|(Lb/Block;)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(1, r.classesFingerprint(), () -> "a/Block -> b/Block by stable fingerprint");
        assertEquals(1, r.classesExact(), () -> "r/S pairs by FQN");
        assertEquals(0, r.classCollisions(), () -> "no ambiguity anywhere");
        assertEquals(1, r.membersExact(), () -> "the marker keeps its name -> M1 across the pair");
        assertEquals(1, r.membersUniqueDesc(),
                () -> "(La/Block;)V and (Lb/Block;)V agree once canonicalized -> M2 despite renamed type");
        assertEquals(4, r.totalEdges(), () -> "2 class edges + marker edge + the canonicalized M2 edge");
    }

    /** The fixpoint: an OWNER whose member references a renamed type pairs once that type pairs. */
    @Test
    void fingerprintFixpointPairsOwnerThroughRenamedDescriptorType() throws IOException {
        Path src = jar(tmp, "src.jar",
                "a/Block!m|x|()V",
                "r/User!m|v|(La/Block;)V");
        Path dst = jar(tmp, "dst.jar",
                "b/Block!m|x|()V",
                "r/User2!m|w|(Lb/Block;)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(2, r.classesFingerprint(),
                () -> "round 1 pairs a/Block; round 2 pairs r/User once (La/Block;)V canonicalizes");
        assertEquals(0, r.classesExact(), () -> "no FQN identity at all");
        assertEquals(0, r.classCollisions(), () -> "no ambiguity anywhere");
        assertEquals(1, r.membersExact(), () -> "marker x keeps its name -> M1");
        assertEquals(1, r.membersUniqueDesc(), () -> "renamed owner member M2s through the pair");
        assertEquals(4, r.totalEdges(), () -> "2 class edges + the marker edge + the M2 edge");
    }

    /**
     * B3 soundness: canonicalization must NEVER fuse distinct overloads, and a
     * group it draws together must drop EVERYONE on BOTH sides, never pick a
     * winner — the same semantics as {@link #fingerprintCollisionDropsAllClaimantsOnBothSides}
     * at the class level. The two src overloads differ in their raw descriptors
     * (one references the paired type {@code a/Block}, the other an unpaired type
     * whose spelling equals the dst name {@code b/Block}), so canonicalization
     * draws them into ONE canonical key. That group is ambiguous: NEITHER src
     * overload may emit an edge, and dst's single {@code foo} is not guessed either
     * (M1 min-count pairing must not extend into a collided group). Reversing the
     * two overloads' declaration order changes nothing — the drop is wholesale, so
     * the outcome is insertion-order independent (the determinism pins demand it).
     */
    @Test
    void canonicalizedOverloadCollisionDropsBothSides() throws IOException {
        Path src = jar(tmp, "src.jar",
                "a/Block!m|x|()V",                             // pairs to b/Block
                "r/S!m|foo|(La/Block;)V!m|foo|(Lb/Block;)V");  // both overloads in one class
        Path srcReversed = jar(tmp, "src-reversed.jar",
                "a/Block!m|x|()V",
                "r/S!m|foo|(Lb/Block;)V!m|foo|(La/Block;)V");  // same class, reversed order
        Path dst = jar(tmp, "dst.jar",
                "b/Block!m|x|()V",
                "r/S!m|foo|(Lb/Block;)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);
        DeriveMatcher.MatchResult rReversed = DeriveMatcher.match(srcReversed, dst);

        assertEquals(1, r.classesFingerprint(), () -> "a/Block -> b/Block by stable fingerprint");
        assertEquals(1, r.classesExact(), () -> "r/S pairs by FQN");
        assertEquals(0, r.classCollisions(), () -> "no class-level ambiguity anywhere");
        assertEquals(r, rReversed, () -> "reversed overload declaration order changes nothing");

        assertEquals(1, r.membersExact(),
                () -> "marker x keeps its name -> M1; the collided foo group yields NOTHING");
        assertEquals(0, r.membersUniqueDesc(),
                () -> "(Lb/Block;)V not unique in canonical form on src -> M2 must refuse both");
        assertEquals(3, r.totalEdges(), () -> "2 class edges + the marker edge only");
        Optional<DeriveMatcher.ClassMatch> cmOpt = r.classes().stream()
                .filter(c -> c.srcName().equals("r/S")).findFirst();
        assertTrue(cmOpt.isPresent(), () -> "r/S is a matched pair");
        DeriveMatcher.ClassMatch cm = cmOpt.get();
        assertEquals(0, cm.members().size(),
                () -> "NEITHER overload wins: both src foo edges dropped, dst's sole foo unguessed");
    }

    // ------------------------------------------------------------------ determinism + emissions

    @Test
    void matchAndWriteAreDeterministicByteIdentical() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Old!f|a|I!m|c|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/New!f|a|I!m|d|(I)V");

        DeriveMatcher.MatchResult r1 = DeriveMatcher.match(src, dst);
        DeriveMatcher.MatchResult r2 = DeriveMatcher.match(src, dst);
        assertEquals(r1, r2, () -> "two match runs must agree bit for bit");

        Path t1 = writeTiny(tmp, "one.tiny", r1, "mojang@1.21.1", "mojang@26.2");
        Path t2 = writeTiny(tmp, "two.tiny", r2, "mojang@1.21.1", "mojang@26.2");
        assertArrayEquals(Files.readAllBytes(t1), Files.readAllBytes(t2),
                () -> "two emitted files must be byte-identical");
    }

    /** The @-version namespace labels survive the writer and keep columns distinct. */
    @Test
    void duplicateNamespaceNamesStayDistinctThroughVersionSuffix() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Same");
        Path dst = jar(tmp, "dst.jar", "com/ex/Same");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);
        Path tiny = writeTiny(tmp, "labelled.tiny", r, "mojang@1.21.1", "mojang@26.2");

        TinyV2Reader.TinyFile f = TinyV2Reader.read(tiny);
        assertEquals(List.of("mojang@1.21.1", "mojang@26.2"), f.namespaces(),
                () -> "labels stay distinct despite both reading 'mojang'");
        // The CLI resolves columns with namespaces.indexOf(label); the version
        // suffix exists precisely so neither label collapses to column 0.
        assertEquals(0, f.namespaces().indexOf("mojang@1.21.1"),
                () -> "src label resolves to column 0");
        assertEquals(1, f.namespaces().indexOf("mojang@26.2"),
                () -> "dst label resolves to column 1");
    }

    // ------------------------------------------------------------------ graph round trip

    @Test
    void emittedFileRoundTripsThroughReaderIntoGraphBothDirections() throws IOException {
        Path src = jar(tmp, "src.jar", "com/ex/Old!f|a|I!m|c|(I)V");
        Path dst = jar(tmp, "dst.jar", "com/ex/New!f|a|I!m|d|(I)V");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);
        Path tiny = writeTiny(tmp, "mv.tiny", r, "mojang@1.21.1", "mojang@26.2");

        TinyV2Reader.TinyFile f = TinyV2Reader.read(tiny);
        Node v121 = new Node("1.21.1", "mojang");
        Node v262 = new Node("26.2", "mojang");
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(f, v121, 0, v262, 1, Provenance.DERIVED_MATCH, "mv.tiny");

        // class forward + backward
        Symbol cls = new Symbol(v121, SymbolKind.CLASS, null, "com/ex/Old", null);
        Optional<List<MappingEdge>> fwd = g.translate(cls, v262);
        assertTrue(fwd.isPresent(), () -> "forward class must translate");
        assertEquals(Provenance.DERIVED_MATCH, fwd.get().get(0).confidence(), 0.0,
                () -> "derived edges load at 0.7");
        assertEquals("com/ex/New", walk(fwd.get(), cls).name());

        Symbol clsBack = new Symbol(v262, SymbolKind.CLASS, null, "com/ex/New", null);
        Optional<List<MappingEdge>> back = g.translate(clsBack, v121);
        assertTrue(back.isPresent(), () -> "backward class must translate");
        assertEquals("com/ex/Old", walk(back.get(), clsBack).name(),
                () -> "reverse hop lands on edge.from");

        // method renamed across the version hop, both directions
        Symbol meth = new Symbol(v121, SymbolKind.METHOD, "com/ex/Old", "c", "(I)V");
        Optional<List<MappingEdge>> mfwd = g.translate(meth, v262);
        assertTrue(mfwd.isPresent(), () -> "forward method must translate");
        assertEquals("d", walk(mfwd.get(), meth).name());

        Symbol methBack = new Symbol(v262, SymbolKind.METHOD, "com/ex/New", "d", "(I)V");
        Optional<List<MappingEdge>> mbwd = g.translate(methBack, v121);
        assertTrue(mbwd.isPresent(), () -> "backward method must translate");
        assertEquals("c", walk(mbwd.get(), methBack).name());

        // field keeps its name — M1 edge present in the graph
        Symbol field = new Symbol(v121, SymbolKind.FIELD, "com/ex/Old", "a", "I");
        Optional<List<MappingEdge>> ffwd = g.translate(field, v262);
        assertTrue(ffwd.isPresent(), () -> "field must translate");
        assertEquals("a", walk(ffwd.get(), field).name());

        // a symbol absent from the derived bridge stays untranslatable — never guessed
        Symbol ghost = new Symbol(v121, SymbolKind.CLASS, null, "com/ex/Ghost", null);
        assertTrue(g.translate(ghost, v262).isEmpty(), () -> "absent symbol -> empty");

        // round-trip audit at the derived pair reports the two-hop product
        RoundtripAudit.AuditResult ar =
                RoundtripAudit.audit(g, v121, v262, SymbolKind.CLASS, 10);
        assertEquals(1, ar.tested(), () -> "one probed class");
        assertEquals(0, ar.contradictions(), () -> "Old -> New -> Old restores identity");
        assertEquals(Provenance.DERIVED_MATCH * Provenance.DERIVED_MATCH,
                ar.minPathConfidence(), 0.0000001,
                () -> "fwd+back each derived: confidence product 0.49");
    }

    // ------------------------------------------------------------------ hostile inputs

    @Test
    void corruptEntryIsWarnedAndSkippedWhileGoodClassesStillMatch() throws IOException {
        Path src = corruptJar(tmp);
        Path dst = jar(tmp, "dst.jar", "com/ex/Same!f|a|I");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(1, r.parseableSrcClasses(), () -> "the good class still counted");
        assertEquals(1, r.parseableDstClasses(), () -> "the good class still counted");
        assertEquals(1, r.classesExact(), () -> "good pair still matches");
        assertEquals(1, r.membersExact(), () -> "its field still matches");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("unparsable class skipped")),
                () -> "corrupt entry reported, never silently dropped: " + r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("broken/NotAClass.class")),
                () -> "the offending entry is named: " + r.warnings());
    }

    /** A jar with no class entries must report zero parseable classes — never a fake match. */
    @Test
    void emptyJarReportsZeroParseableClassesHonestly() throws IOException {
        Path src = emptyJar(tmp);
        Path dst = jar(tmp, "dst.jar", "com/ex/Same");
        DeriveMatcher.MatchResult r = DeriveMatcher.match(src, dst);

        assertEquals(0, r.parseableSrcClasses(), () -> "no class files in the source jar");
        assertEquals(1, r.parseableDstClasses(), () -> "dst still parses normally");
        assertEquals(0, r.classesExact());
        assertEquals(0, r.classesFingerprint());
        assertEquals(0, r.matchedClasses(), () -> "zero-matched must refuse downstream");
        assertFalse(r.classes().size() > 0, () -> "no made-up edges on an empty side");
    }
}