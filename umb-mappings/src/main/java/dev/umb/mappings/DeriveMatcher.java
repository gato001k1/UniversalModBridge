package dev.umb.mappings;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Structural class/member matcher across two jars that are ALREADY IN THE SAME
 * NAMESPACE but different versions (the 1.21.x → 26.x name-continuity bridge,
 * M4: Mojang re-renamed symbols between 1.21.x and 26.x and no published mapping
 * exists for the hop, so identity must be DERIVED_MATCH-derived, D6). Both inputs
 * are the deobfuscated client jar of their version: 1.21.x remapped to mojang
 * names via client.txt, 26.x as shipped (D3: modern ships mojang-named).
 *
 *
 * <p>Algorithm v1, deterministic by construction (sorted iteration everywhere,
 * no hash-order dependence — repeated calls on identical inputs return identical
 * results, and the emitted tiny files round-trip byte-identical):
 *
 * <ul>
 *   <li>CLASS PASS 1 (exact): identical internal name on both sides → class edge.</li>
 *   <li>CLASS PASS 2 (fingerprint): fingerprint = sorted member signatures
 *       (kind + descriptor, NAMES EXCLUDED so renames don't hurt; descriptors
 *       canonicalized through the pairings established so far, B3) + sorted
 *       direct-interface names + superclass name; exact fingerprint equality, unique
 *       on BOTH sides → class edge. A fingerprint claimed by 2+ source or 2+ target
 *       classes drops ALL claimants (ambiguous) and counts as a collision — a
 *       guessed edge is never emitted (D4). Rounds iterate to a fixpoint because
 *       each round's new pairs seed the next round's canonicalization.</li>
 *   <li>MEMBER PASS (only within matched class pairs):
 *       {@code M1}: (kind, name, canonical descriptor) identical on both sides → edge; a
 *       duplicated signature appearing once on one side pairs by min-count. Min-count
 *       applies only to interchangeable duplicates (identical RAW descriptors): a
 *       canonical key claimed by DISTINCT raw descriptors on either side is a canonical
 *       collision and drops EVERY member carrying it on both sides — the same
 *       all-claimants rule as a claimed fingerprint (D4), never a winner picked.
 *       {@code M2}: a member whose (kind, canonical descriptor) is UNIQUE within its class
 *       on BOTH sides → edge even if renamed or descriptor-referenced classes changed.
 *       Same-canonical-descriptor duplicate members stay unmatched unless their names
 *       also match via M1.</li>
 * </ul>
 *
 * <p>Classfiles are read with {@code SKIP_CODE | SKIP_FRAMES} — only the
 * signature table is touched, no method bodies — so a 39 MB client.jar parses
 * comfortably at {@code -Xmx768m}. Corrupt entries are skipped with a warning
 * carried in the result (spec §131 tolerance mirroring GraphJarRemapper); a
 * wholly-unparsable input jar is the caller's D4 refusal decision, never a
 * vacuous success here.
 *
 * <p>Descriptor canonicalization (B3): member descriptors are NOT compared
 * verbatim. Before the fingerprint pass and before each member key is built, every
 * class type referenced by the descriptor on BOTH sides is rewritten through the
 * class pairings established so far (src name {@code ->} dst name). A member whose
 * descriptor references a class MOJANG renamed between the two versions (the 1.13
 * flattening and the later renaming waves) therefore matches across the hop
 * instead of silently losing. Types outside the pairings stay VERBATIM — a name is
 * never invented — and the whole descriptor INCLUDING the return type canonicalizes.
 * Canonicalization is a comparison device only: the emitted {@link MemberMatch}
 * carries the src-version (namespace[0]) descriptor so
 * {@link DefaultMappingGraph#addTinyFile} rewrites it per loaded column.
 *
 * <p>Because the pairings that seed canonicalization GROW as fingerprint rounds
 * resolve, the class pass iterates to a fixpoint: each round canonicalizes through
 * the pairs established so far (PASS 1, then earlier fingerprint rounds), pairs the
 * classes whose canonical fingerprints agree, and stops the round that adds no
 * new pair. Uniqueness (fingerprint claimants; M2 same-descriptor duplicates) is
 * judged in canonical form over the whole member list, and M1 refuses a canonical
 * key claimed by distinct raw descriptors — in every pass a group canonicalization
 * draws together is DROPPED wholesale on both sides, never fused and never won
 * (D4).
 */
public final class DeriveMatcher {

    private DeriveMatcher() {}

    /** One matched member pair. {@code descriptor} is in src-version internal form. */
    public record MemberMatch(SymbolKind kind, String srcName, String dstName,
                              String descriptor) {}

    /** One matched class pair plus its member matches. */
    public record ClassMatch(String srcName, String dstName, List<MemberMatch> members) {}

    /**
     * Outcome of one match run. Every counter is a positive observable so the
     * D8 rule (pin each field's NONZERO case) has something to assert.
     */
    public record MatchResult(
            int parseableSrcClasses,
            int parseableDstClasses,
            int classesExact,
            int classesFingerprint,
            int classCollisions,
            int membersExact,
            int membersUniqueDesc,
            List<String> warnings,
            List<ClassMatch> classes) {

        public int matchedClasses() {
            return classesExact + classesFingerprint;
        }

        /** Collision and fingerprint-pass-PASS-2 claimants alike count as unmatched — honest. */
        public int unmatchedSrcClasses() {
            return parseableSrcClasses - matchedClasses();
        }

        public int unmatchedDstClasses() {
            return parseableDstClasses - matchedClasses();
        }

        /** Class edges + member edges actually emitted. */
        public int totalEdges() {
            return matchedClasses() + membersExact + membersUniqueDesc;
        }
    }

    private record MembersResult(List<MemberMatch> matches, int exact, int uniqueDesc) {}

    /** One classfile's signature table. */
    private static final class ClassInfo {
        String name;
        String superName;
        List<String> interfaces = List.of();
        final List<Member> members = new ArrayList<>();

        void memberAdd(SymbolKind kind, String name, String descriptor) {
            members.add(new Member(kind, name, descriptor));
        }

        /**
         * Fingerprint whose member-descriptor entries are canonicalized through
         * {@code srcToDst} (the class pairings established so far, B3); super and
         * direct-interface entries stay verbatim. Recomputed per fingerprint round,
         * never cached: each round's new pairings bind types a later round may then
         * canonicalize (the fixpoint iteration).
         */
        List<String> fingerprint(Map<String, String> srcToDst) {
            List<String> fp = new ArrayList<>();
            fp.add("s:" + (superName == null ? "" : superName));
            List<String> ifaces = new ArrayList<>(interfaces);
            ifaces.sort(null);
            for (String i : ifaces) {
                fp.add("i:" + i);
            }
            List<Member> ms = new ArrayList<>(members);
            ms.sort(MEMBER_ORDER);
            for (Member m : ms) {
                fp.add((m.kind() == SymbolKind.FIELD ? "f:" : "m:")
                        + canonicalDescriptor(m.descriptor(), srcToDst));
            }
            return List.copyOf(fp);
        }
    }

    private record Member(SymbolKind kind, String name, String descriptor) {}

    /**
     * Fingerprint bucket key. A raw {@code List<String>} cannot be a TreeMap
     * key (List is not Comparable), and a hash map would leak iteration order;
     * this wrapper gives the group-by deterministic order element by element.
     */
    private record FingerprintKey(List<String> parts) implements Comparable<FingerprintKey> {
        @Override
        public int compareTo(FingerprintKey o) {
            List<String> a = parts();
            List<String> b = o.parts();
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                int c = a.get(i).compareTo(b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.size(), b.size());
        }
    }

    private static final Comparator<Member> MEMBER_ORDER = Comparator
            .comparing((Member m) -> m.kind().name())
            .thenComparing(Member::name)
            .thenComparing(Member::descriptor);

    /**
     * Matches {@code srcJar} against {@code dstJar}.
     *
     * @throws IOException when an input jar cannot be opened/read
     */
    public static MatchResult match(Path srcJar, Path dstJar) throws IOException {
        Objects.requireNonNull(srcJar, "srcJar");
        Objects.requireNonNull(dstJar, "dstJar");
        List<String> warnings = new ArrayList<>();
        Map<String, ClassInfo> allSrc = parse(srcJar, warnings);
        Map<String, ClassInfo> allDst = parse(dstJar, warnings);

        // Deterministic output order: pairs keyed by src name in a TreeMap.
        TreeMap<String, String> pairs = new TreeMap<>();
        TreeSet<String> srcUnmatched = new TreeSet<>(allSrc.keySet());
        TreeSet<String> dstUnmatched = new TreeSet<>(allDst.keySet());
        int classesExact = 0;
        int classesFingerprint = 0;
        int collisions = 0;

        // CLASS PASS 1 — identical FQN.
        for (String name : new ArrayList<>(srcUnmatched)) {
            if (dstUnmatched.contains(name)) {
                pairs.put(name, name);
                srcUnmatched.remove(name);
                dstUnmatched.remove(name);
                classesExact++;
            }
        }

        // CLASS PASS 2 — fingerprint, unique on BOTH sides or dropped as ambiguous.
        // B3: each round canonicalizes member descriptors through the pairings
        // established SO FAR (PASS 1, then earlier fingerprint rounds), because a
        // member referencing a renamed class no longer blocks its owner's pairing.
        // The round that adds no pair has converged (pairs only grow, so the
        // fixpoint terminates deterministically); only that round's ambiguous
        // groups count as collisions — a group ambiguous in an earlier round may
        // resolve once new canonical bonds split it, and must then not count.
        while (true) {
            TreeMap<FingerprintKey, List<ClassInfo>> sByFp =
                    groupByFingerprint(allSrc, srcUnmatched, pairs);
            TreeMap<FingerprintKey, List<ClassInfo>> dByFp =
                    groupByFingerprint(allDst, dstUnmatched, pairs);
            boolean pairedThisRound = false;
            int roundCollisions = 0;
            for (Map.Entry<FingerprintKey, List<ClassInfo>> en : sByFp.entrySet()) {
                List<ClassInfo> dList = dByFp.get(en.getKey());
                if (dList == null) {
                    continue; // fingerprint exists on one side only — stays unmatched
                }
                List<ClassInfo> sList = en.getValue();
                if (sList.size() == 1 && dList.size() == 1) {
                    ClassInfo s = sList.get(0);
                    ClassInfo d = dList.get(0);
                    pairs.put(s.name, d.name);
                    srcUnmatched.remove(s.name);
                    dstUnmatched.remove(d.name);
                    classesFingerprint++;
                    pairedThisRound = true;
                } else {
                    // Multiple claimants on one or both sides: the fingerprint no
                    // longer distinguishes classes. Leave every claimant in play —
                    // a later round's canonicals may split the group; if none ever
                    // does, the converged round counts it once as a collision (D4:
                    // ALL claimants dropped, a guessed edge is never emitted).
                    roundCollisions++;
                }
            }
            if (!pairedThisRound) {
                // Converged: nothing new paired, so what the last scan saw IS the
                // final state and the surviving ambiguous groups are the honest total.
                collisions = roundCollisions;
                break;
            }
        }

        // MEMBER PASS — only within matched class pairs.
        List<ClassMatch> matches = new ArrayList<>();
        int membersExact = 0;
        int membersUniqueDesc = 0;
        for (Map.Entry<String, String> p : pairs.entrySet()) {
            MembersResult mr = matchMembers(allSrc.get(p.getKey()), allDst.get(p.getValue()),
                    pairs);
            matches.add(new ClassMatch(p.getKey(), p.getValue(), mr.matches()));
            membersExact += mr.exact();
            membersUniqueDesc += mr.uniqueDesc();
        }

        return new MatchResult(allSrc.size(), allDst.size(),
                classesExact, classesFingerprint, collisions,
                membersExact, membersUniqueDesc,
                List.copyOf(warnings), List.copyOf(matches));
    }

    /** Groups the given classes' canonicalized fingerprints into sorted buckets. */
    private static TreeMap<FingerprintKey, List<ClassInfo>> groupByFingerprint(
            Map<String, ClassInfo> all, TreeSet<String> keys,
            Map<String, String> srcToDst) {
        TreeMap<FingerprintKey, List<ClassInfo>> byFp = new TreeMap<>();
        for (String k : keys) {
            byFp.computeIfAbsent(new FingerprintKey(all.get(k).fingerprint(srcToDst)),
                    x -> new ArrayList<>()).add(all.get(k));
        }
        return byFp;
    }

    /**
     * Parses one jar's classfiles into their signature tables. Class identity is
     * the bytecode's own {@code this_class}, not the zip entry path, so a
     * multi-release overlay duplicating an internal name keeps the first
     * occurrence (warned). Non-class entries are ignored.
     */
    private static Map<String, ClassInfo> parse(Path jar, List<String> warnings) throws IOException {
        Map<String, ClassInfo> out = new TreeMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    ClassInfo ci = readClass(in);
                    if (out.containsKey(ci.name)) {
                        warnings.add("duplicate class entry skipped (kept first): " + ci.name
                                + " at " + e.getName());
                        continue;
                    }
                    out.put(ci.name, ci);
                } catch (IOException | RuntimeException badClass) {
                    // Untrusted input tolerance (spec §131): one broken member must
                    // not lose the other classes' signal. The message rides along
                    // because a silently-swallowed cause once masked a real bug (D4).
                    warnings.add("unparsable class skipped: " + e.getName() + " (" + badClass + ")");
                }
            }
        }
        return out;
    }

    private static ClassInfo readClass(InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);
        Collector c = new Collector();
        cr.accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return c.info;
    }

    /** Signature-table-only visitor: no bodies, no frames — fast on 39 MB jars. */
    private static final class Collector extends ClassVisitor {
        final ClassInfo info = new ClassInfo();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            info.name = name;
            info.superName = superName;
            info.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            info.memberAdd(SymbolKind.FIELD, name, descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            info.memberAdd(SymbolKind.METHOD, name, descriptor);
            return null;
        }
    }

    private static MembersResult matchMembers(ClassInfo s, ClassInfo d,
                                              Map<String, String> srcToDst) {
        List<Member> sm = new ArrayList<>(s.members);
        List<Member> dm = new ArrayList<>(d.members);
        sm.sort(MEMBER_ORDER);
        dm.sort(MEMBER_ORDER);
        List<MemberMatch> out = new ArrayList<>();
        boolean[] sClaimed = new boolean[sm.size()];
        boolean[] dClaimed = new boolean[dm.size()];

        // M1 — exact (kind, name, canonical descriptor). Duplicate signatures pair by
        // min-count: a signature present twice on one side and once on the other
        // yields exactly one M1 edge and one left unmatched.
        //
        // B3 soundness — min-count must never pick a winner from a canonical
        // collision. Only members whose RAW descriptors are identical are
        // interchangeable duplicates. A canonical key claimed by 2+ DISTINCT raw
        // descriptors on either side means canonicalization drew two real overloads
        // together; the whole key group is ambiguous and EVERY member carrying it on
        // BOTH sides drops — the same all-claimants rule as a claimed fingerprint
        // (D4). A 1:1 canonical key is NOT a collision even when its two raw
        // descriptors differ: matching a renamed descriptor reference across the hop
        // is the B3 feature itself.
        int exact = 0;
        TreeMap<MemberKey, List<Integer>> sByKey = new TreeMap<>();
        TreeMap<MemberKey, List<Integer>> dByKey = new TreeMap<>();
        for (int i = 0; i < sm.size(); i++) {
            sByKey.computeIfAbsent(key(sm.get(i), srcToDst), k -> new ArrayList<>()).add(i);
        }
        for (int j = 0; j < dm.size(); j++) {
            dByKey.computeIfAbsent(key(dm.get(j), srcToDst), k -> new ArrayList<>()).add(j);
        }
        TreeSet<MemberKey> ambiguous = new TreeSet<>();
        for (Map.Entry<MemberKey, List<Integer>> en : sByKey.entrySet()) {
            if (distinctRawDescriptors(sm, en.getValue())) {
                ambiguous.add(en.getKey());
            }
        }
        for (Map.Entry<MemberKey, List<Integer>> en : dByKey.entrySet()) {
            if (distinctRawDescriptors(dm, en.getValue())) {
                ambiguous.add(en.getKey());
            }
        }
        for (int i = 0; i < sm.size(); i++) {
            MemberKey k = key(sm.get(i), srcToDst);
            if (ambiguous.contains(k)) {
                continue; // collided group — dropped on both sides, never guessed
            }
            List<Integer> bucket = dByKey.get(k);
            if (bucket == null) {
                continue;
            }
            for (int j : bucket) {
                if (!dClaimed[j]) {
                    sClaimed[i] = true;
                    dClaimed[j] = true;
                    out.add(new MemberMatch(sm.get(i).kind(), sm.get(i).name(),
                            dm.get(j).name(), sm.get(i).descriptor()));
                    exact++;
                    break;
                }
            }
        }

        // M2 — unique (kind, canonical descriptor) within the class on BOTH sides,
        // even if renamed or descriptor-referenced classes were renamed (B3).
        // Uniqueness is judged over the WHOLE member list (claimed or not) IN
        // CANONICAL FORM, so overloads canonicalization would draw together stay
        // unmatched unless M1 already got them — never fused.
        int uniqueDesc = 0;
        TreeMap<KindDescKey, List<Integer>> sByKd = new TreeMap<>();
        TreeMap<KindDescKey, List<Integer>> dByKd = new TreeMap<>();
        for (int i = 0; i < sm.size(); i++) {
            sByKd.computeIfAbsent(kdOf(sm.get(i), srcToDst), k -> new ArrayList<>()).add(i);
        }
        for (int j = 0; j < dm.size(); j++) {
            dByKd.computeIfAbsent(kdOf(dm.get(j), srcToDst), k -> new ArrayList<>()).add(j);
        }
        for (int i = 0; i < sm.size(); i++) {
            if (sClaimed[i]) {
                continue;
            }
            if (sByKd.get(kdOf(sm.get(i), srcToDst)).size() != 1) {
                continue;
            }
            List<Integer> db = dByKd.get(kdOf(sm.get(i), srcToDst));
            if (db == null || db.size() != 1 || dClaimed[db.get(0)]) {
                continue;
            }
            int j = db.get(0);
            sClaimed[i] = true;
            dClaimed[j] = true;
            out.add(new MemberMatch(sm.get(i).kind(), sm.get(i).name(),
                    dm.get(j).name(), sm.get(i).descriptor()));
            uniqueDesc++;
        }
        return new MembersResult(List.copyOf(out), exact, uniqueDesc);
    }

    /** M1 identity: kind + name + descriptor. */
    private record MemberKey(SymbolKind kind, String name, String descriptor)
            implements Comparable<MemberKey> {
        @Override
        public int compareTo(MemberKey o) {
            int c = kind().name().compareTo(o.kind().name());
            if (c != 0) {
                return c;
            }
            c = name().compareTo(o.name());
            if (c != 0) {
                return c;
            }
            return descriptor().compareTo(o.descriptor());
        }
    }

    /** M2 within-class uniqueness key: kind + descriptor. */
    private record KindDescKey(SymbolKind kind, String descriptor)
            implements Comparable<KindDescKey> {
        @Override
        public int compareTo(KindDescKey o) {
            int c = kind().name().compareTo(o.kind().name());
            if (c != 0) {
                return c;
            }
            return descriptor().compareTo(o.descriptor());
        }
    }

    private static MemberKey key(Member m, Map<String, String> srcToDst) {
        return new MemberKey(m.kind(), m.name(),
                canonicalDescriptor(m.descriptor(), srcToDst));
    }

    private static KindDescKey kdOf(Member m, Map<String, String> srcToDst) {
        return new KindDescKey(m.kind(), canonicalDescriptor(m.descriptor(), srcToDst));
    }

    /**
     * True when the given member indexes into {@code ms} (one side of a
     * canonical-key bucket) reference DIFFERENT raw descriptors — canonicalization
     * drew distinct members together, so the bucket is ambiguous and every member
     * in it must drop. A bucket whose members share one raw descriptor holds
     * interchangeable duplicates, NOT a collision: min-count still pairs them.
     */
    private static boolean distinctRawDescriptors(List<Member> ms, List<Integer> idxs) {
        String first = ms.get(idxs.get(0)).descriptor();
        for (int i = 1; i < idxs.size(); i++) {
            if (!ms.get(idxs.get(i)).descriptor().equals(first)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites every class type a JVM descriptor references through {@code srcToDst}
     * (the class pairings established so far, src name -> dst name). The whole
     * descriptor INCLUDING the return type is canonicalized (B3). A type absent
     * from the pairings stays VERBATIM — a name is never invented — and a
     * descriptor with no class references (primitives, arrays thereof) is returned
     * unchanged. Pairings are injective by construction (a dst class pairs once),
     * so two members of one side with different raw descriptors can never draw
     * together unless one referenced the paired-away type directly; in that case
     * the M2 uniqueness predicate over canonical keys drops both (D4).
     */
    private static String canonicalDescriptor(String desc, Map<String, String> srcToDst) {
        if (desc.indexOf('L') < 0 || srcToDst.isEmpty()) {
            return desc;
        }
        StringBuilder sb = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String internal = desc.substring(i + 1, end);
                String mapped = srcToDst.get(internal);
                sb.append('L').append(mapped == null ? internal : mapped).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}