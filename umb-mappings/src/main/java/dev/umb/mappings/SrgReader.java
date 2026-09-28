package dev.umb.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reader for the MCPConfig SRG namespace files - the func_/field_ searge names of
 * the pre-1.14.4 MCP renamer (the joined.srg shape was produced through 1.12.2;
 * at 1.7.10 the runtime obfuscated members are short names and the srg file is
 * what renames them, while at 1.12.2 the runtime names already ARE the searge
 * names, so the joined.srg rows are largely identities). Output is a
 * {@link TinyV2Reader.TinyFile} with the fixed namespace pair {@link #NS_SRG}
 * and {@link #NS_OFFICIAL} in that column order, so {@link
 * DefaultMappingGraph#addTinyFile} ingests it unchanged - with one deliberate
 * gap: joined.srg's FD rows carry NO field types, so field entries leave their
 * descriptor null and the graph carries the member without a descriptor (a name
 * is never invented, D4). Method rows carry a full descriptor on EACH column,
 * in that column's own class dialect (the obf side names the obfuscated classes,
 * the srg side the readable ones - MCP deobfuscates the references too). The
 * stored descriptor is the SRG-side form, the namespace[0] of the emitted column
 * pair, per the addTinyFile convention that descriptors sit in namespace[0] form
 * and both consumer sides are rewritten through the same file's class pairings.
 * The two descriptor columns are verified consistent by rewriting the obf-side
 * references through the same CL rows and requiring the result to equal the
 * srg-side descriptor (external references - java/lang, io/netty, ... - pass
 * through unchanged, exactly like DefaultMappingGraph's descriptor remap).
 *
 * <p>Grammar for {@link #read(Path)} (joined.srg) - the REAL published shape,
 * validated against the fetch-only mcp-1.7.10-srg.zip and mcp-1.12.2-srg.zip
 * artifacts before the fixtures were trusted:
 * {@code CL: <obf-class> <srg-class>} /
 * {@code FD: <obf-class>/<obf-field> <srg-class>/<srg-field>} /
 * {@code MD: <obf-class>/<obf-method> <descriptor> <srg-class>/<srg-method> <descriptor>}
 * - each MD row is five whitespace-separated tokens with the descriptor its own
 * middle column per side, in that column's class dialect. {@code PK:}
 * package-rename rows are accepted and skipped (class rows already carry full
 * paths). The GLUED synthetic variant ({@code MD: <name><descriptor>
 * <srg-name><descriptor>}, three tokens) is also tolerated for compatibility.
 * Tokens separate on runs of whitespace (spaces or tabs), so a descriptor token
 * cannot contain whitespace; {@code #} full-line comments and blank lines are
 * skipped, and CRLF/BOM are tolerated. Row ORDER is arbitrary - class rows are
 * collected on a first pass and member rows attach to their owner class in a
 * second pass, so a member row may legally precede its own CL row. A member
 * whose owner class never appears in a CL row is malformed input (the file
 * cannot attribute it), not a silent skip.
 *
 * <p>Grammar for {@link #readTsrg(Path)} (TSrg v1, the obf->srg variant SRG
 * files are converted to): class rows at indent 0 ({@code <obf-class> <srg-class>});
 * field rows at indent 1 ({@code <obf-field> <srg-field>}); method rows at indent
 * 1 ({@code <obf-method> <descriptor> <srg-method>} with the descriptor its own
 * middle column); param rows at indent 2 are skipped gracefully (params are a
 * deferred schema, this wave reads no names from them). Both dialects are pinned
 * against fixtures shaped like the real published MCPConfig output (the reader
 * was validated against real fetched joined.srg files before trust); the real
 * files themselves stay fetch-only, same posture as client.txt.
 */
public final class SrgReader {

    /** MCP searge names - func_/field_, readable-class owners. */
    public static final String NS_SRG = "srg";

    /**
     * Obfuscated runtime names. The same token as {@link ProGuardReader#NS_OFFICIAL}
     * by design: pre-1.14.4 there is no mojang namespace (D13), so "official" at
     * 1.7.10 names the obfuscated jar classes, and reusing the one string keeps
     * the graph model's namespace vocabulary consistent across versions.
     */
    public static final String NS_OFFICIAL = ProGuardReader.NS_OFFICIAL;

    private SrgReader() {}

    /**
     * Parses one joined.srg file into the {@link #NS_SRG} and {@link #NS_OFFICIAL}
     * column pair. Class rows are collected on a first pass, member rows attach
     * to their obfuscated owner in a second pass (a member row may legally
     * precede its own CL row), so a member whose owner never appears in a CL row
     * is malformed input, not a silent skip (D4). FD field descriptors stay null
     * (FD rows carry no types). MD descriptors are verified across the two
     * columns by rewriting the obf-side class references through the CL rows.
     */
    public static TinyV2Reader.TinyFile read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Map<String, String> obfToSrg = new LinkedHashMap<>();
        List<MemberRow> members = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] t = line.split("\\s+");
                switch (t[0]) {
                    case "CL:" -> {
                        if (t.length != 3) {
                            throw malformed(lineNo, "CL row must be '<obf-class> <srg-class>' (2 tokens), got "
                                    + (t.length - 1) + " tokens");
                        }
                        obfToSrg.put(t[1], t[2]);
                    }
                    case "FD:" -> {
                        if (t.length != 3) {
                            throw malformed(lineNo, "FD row must be '<obf-class>/<obf-field> <srg-class>/<srg-field>' "
                                    + "(2 tokens), got " + (t.length - 1) + " tokens");
                        }
                        members.add(splitMember(t[1], t[2], lineNo));
                    }
                    case "MD:" -> members.add(splitMethod(t, lineNo));
                    case "PK:" -> {
                        // Package-rename rows carry no symbol payload here; the
                        // full paths live on the CL rows.
                    }
                    default -> throw malformed(lineNo, "unknown row prefix '" + t[0] + "'");
                }
            }
        }
        return attach(obfToSrg, members);
    }

    /**
     * Parses a TSrg v1 file (block shape: class at indent 0, fields and methods
     * interleaved at indent 1, params at indent 2 skipped). Same namespace pair
     * and same null-field-descriptor gap as {@link #read(Path)}.
     */
    public static TinyV2Reader.TinyFile readTsrg(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<TinyV2Reader.ClassEntry> classes = new ArrayList<>();
        ClassBuilder current = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                line = stripTrailingJunk(line);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == '\t') {
                    indent++;
                }
                String[] t = line.substring(indent).strip().split("\\s+");
                switch (indent) {
                    case 0 -> {
                        if (t.length != 2) {
                            throw malformed(lineNo, "class row must be '<obf-class> <srg-class>' (2 tokens), got "
                                    + t.length + " tokens");
                        }
                        if (current != null) {
                            classes.add(current.build());
                        }
                        current = new ClassBuilder(t[1], t[0]);
                    }
                    case 1 -> {
                        if (current == null) {
                            throw malformed(lineNo, "member row before any class row");
                        }
                        if (t.length == 2) {
                            current.addField(t[0], t[1]);
                        } else if (t.length == 3) {
                            current.addMethod(t[0], t[2], t[1]);
                        } else {
                            throw malformed(lineNo, "member row must be '<obf-member> <srg-member>' (field) or "
                                    + "'<obf-member> <descriptor> <srg-member>' (method), got " + t.length + " tokens");
                        }
                    }
                    default -> {
                        // Deeper rows - SRG v1 parameter mappings - carry no
                        // symbol payload for this module; skipped gracefully.
                    }
                }
            }
        }
        if (current != null) {
            classes.add(current.build());
        }
        return new TinyV2Reader.TinyFile(List.of(NS_SRG, NS_OFFICIAL), classes);
    }

    /** One FD row or one MD row, pre-validation. */
    private record MemberRow(String obfOwner, String obfName, String srgName,
                             String obfDesc, String srgDesc, boolean glued, int lineNo) {}

    private record OwnerName(String owner, String name) {}

    private record GluedMember(String owner, String name, String descriptor) {}

    /**
     * Attaches collected member rows to their owner classes. Two passes are what
     * let a member row legally precede its own CL row; an owner missing from the
     * class map is malformed input (D4).
     */
    private static TinyV2Reader.TinyFile attach(Map<String, String> obfToSrg, List<MemberRow> members) {
        Map<String, ClassBuilder> builders = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : obfToSrg.entrySet()) {
            builders.put(e.getKey(), new ClassBuilder(e.getValue(), e.getKey()));
        }
        for (MemberRow m : members) {
            ClassBuilder b = builders.get(m.obfOwner());
            if (b == null) {
                throw new IllegalArgumentException("srg: line " + m.lineNo() + ": member '" + m.obfOwner() + "/"
                        + m.obfName() + "' has no CL row");
            }
            if (m.obfDesc() == null) {
                b.addField(m.obfName(), m.srgName());
            } else if (m.glued()) {
                // Glued 3-token rows already proved raw descriptor equality in
                // splitMethod; no cross-column rewrite applies to them.
                b.addMethod(m.obfName(), m.srgName(), m.srgDesc());
            } else {
                // Real 5-token rows: the obf-side descriptor rewritten through
                // this file's own CL rows must reproduce the srg-side column
                // (measured invariant - MCP rewrites class refs per dialect).
                String rewritten = rewriteRefs(m.obfDesc(), obfToSrg);
                if (!rewritten.equals(m.srgDesc())) {
                    throw malformed(m.lineNo(), "MD descriptors differ (obf side '" + m.obfDesc()
                            + "' rewritten through CL rows to '" + rewritten + "' != srg side '" + m.srgDesc() + "')");
                }
                b.addMethod(m.obfName(), m.srgName(), m.srgDesc());
            }
        }
        List<TinyV2Reader.ClassEntry> out = new ArrayList<>(builders.size());
        for (ClassBuilder b : builders.values()) {
            out.add(new TinyV2Reader.ClassEntry(new String[] { b.srgName, b.obfName },
                    List.copyOf(b.fields), List.copyOf(b.methods)));
        }
        return new TinyV2Reader.TinyFile(List.of(NS_SRG, NS_OFFICIAL), out);
    }

    /** FD row: both member tokens are {@code <class>/<member>}. */
    private static MemberRow splitMember(String obfTok, String srgTok, int lineNo) {
        OwnerName obf = splitMemberToken(obfTok, lineNo);
        OwnerName srg = splitMemberToken(srgTok, lineNo);
        return new MemberRow(obf.owner(), obf.name(), srg.name(), null, null, false, lineNo);
    }

    /**
     * MD row in the real five-token shape (descriptor its own middle column per
     * side) or the tolerated glued three-token shape. Descriptor paren balance is
     * checked here; the cross-column class-dialect equality is verified in
     * {@link #attach} because it needs the full CL map.
     */
    private static MemberRow splitMethod(String[] t, int lineNo) {
        if (t.length == 5) {
            OwnerName obf = splitMemberToken(t[1], lineNo);
            OwnerName srg = splitMemberToken(t[3], lineNo);
            validateDescriptor(t[2], lineNo);
            validateDescriptor(t[4], lineNo);
            return new MemberRow(obf.owner(), obf.name(), srg.name(), t[2], t[4], false, lineNo);
        }
        if (t.length == 3) {
            GluedMember obf = splitGluedMember(t[1], lineNo);
            GluedMember srg = splitGluedMember(t[2], lineNo);
            validateDescriptor(obf.descriptor(), lineNo);
            validateDescriptor(srg.descriptor(), lineNo);
            if (!obf.descriptor().equals(srg.descriptor())) {
                throw malformed(lineNo, "MD descriptors differ ('" + obf.descriptor()
                        + "' != '" + srg.descriptor() + "')");
            }
            return new MemberRow(obf.owner(), obf.name(), srg.name(),
                    obf.descriptor(), srg.descriptor(), true, lineNo);
        }
        throw malformed(lineNo, "MD row must be '<obf-class>/<obf-method> <descriptor> <srg-class>/<srg-method> "
                + "<descriptor>' (4 tokens) or '<obf-class>/<obf-method><descriptor> "
                + "<srg-class>/<srg-method><descriptor>' (2 tokens), got " + (t.length - 1) + " tokens");
    }

    /** Member token {@code <class>/<member>}; the owner may be readable. */
    private static OwnerName splitMemberToken(String tok, int lineNo) {
        int slash = tok.lastIndexOf('/');
        if (slash < 0) {
            throw malformed(lineNo, "member token must be '<class>/<member>': '" + tok + "'");
        }
        return new OwnerName(tok.substring(0, slash), tok.substring(slash + 1));
    }

    /** Glued member token {@code <class>/<member>(<descriptor>)}. */
    private static GluedMember splitGluedMember(String tok, int lineNo) {
        int slash = tok.lastIndexOf('/');
        if (slash < 0) {
            throw malformed(lineNo, "member token must be '<class>/<member>(<descriptor>)': '" + tok + "'");
        }
        String rest = tok.substring(slash + 1);
        int open = rest.indexOf('(');
        if (open < 0) {
            throw malformed(lineNo, "method token has no descriptor: '" + tok + "'");
        }
        return new GluedMember(tok.substring(0, slash), rest.substring(0, open), rest.substring(open));
    }

    /**
     * Descriptor sanity before any dialect reasoning: starts with {@code (},
     * parens balance with no negative depth, and no whitespace (structural for
     * the tokenized real shape, defended anyway for the glued overlap).
     */
    private static void validateDescriptor(String d, int lineNo) {
        if (d.isEmpty() || d.charAt(0) != '(') {
            throw malformed(lineNo, "method descriptor must start with '(': '" + d + "'");
        }
        if (d.indexOf(' ') >= 0 || d.indexOf('\t') >= 0) {
            throw malformed(lineNo, "method descriptor token contains whitespace: '" + d + "'");
        }
        int depth = 0;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw malformed(lineNo, "unbalanced descriptor parentheses: '" + d + "'");
                }
            }
        }
        if (depth != 0) {
            throw malformed(lineNo, "unbalanced descriptor parentheses: '" + d + "'");
        }
    }

    /**
     * Rewrites every {@code L<ref>;} in the obf-side descriptor through the
     * file's own CL pairs; references absent from the map (external JDK/library
     * classes) pass through verbatim. Mirrors DefaultMappingGraph's descriptor
     * remap exactly, which is what keeps the stored srg-side descriptor and the
     * two consumer columns all consistent.
     */
    private static String rewriteRefs(String descriptor, Map<String, String> obfToSrg) {
        if (!descriptor.contains("L")) {
            return descriptor;
        }
        StringBuilder sb = new StringBuilder(descriptor.length());
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                if (semi < 0) {
                    sb.append(descriptor, i, descriptor.length());
                    break;
                }
                String ref = descriptor.substring(i + 1, semi);
                sb.append('L').append(obfToSrg.getOrDefault(ref, ref)).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** One class under construction, in {@code {srg, obf}} column order. */
    private static final class ClassBuilder {
        private final String srgName;
        private final String obfName;
        private final List<TinyV2Reader.FieldEntry> fields = new ArrayList<>();
        private final List<TinyV2Reader.MethodEntry> methods = new ArrayList<>();

        private ClassBuilder(String srgName, String obfName) {
            this.srgName = srgName;
            this.obfName = obfName;
        }

        private void addField(String obfName, String srgName) {
            // FD rows carry no type information; the descriptor stays null and a
            // type is never invented (D4).
            fields.add(new TinyV2Reader.FieldEntry(null, new String[] { srgName, obfName }));
        }

        private void addMethod(String obfName, String srgName, String descriptor) {
            // Stored in namespace[0] (srg) form - the srg-side descriptor column.
            methods.add(new TinyV2Reader.MethodEntry(descriptor, new String[] { srgName, obfName }));
        }

        private TinyV2Reader.ClassEntry build() {
            return new TinyV2Reader.ClassEntry(new String[] { srgName, obfName },
                    List.copyOf(fields), List.copyOf(methods));
        }
    }

    private static IllegalArgumentException malformed(int lineNo, String msg) {
        return new IllegalArgumentException("srg: line " + lineNo + ": " + msg);
    }

    /** Trailing spaces and stray carriage returns never belong to a token. */
    private static String stripTrailingJunk(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c != ' ' && c != '\r') {
                break;
            }
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }
}