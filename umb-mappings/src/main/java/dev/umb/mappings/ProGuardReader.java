package dev.umb.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reader for the ProGuard mapping format Mojang publishes as {@code client.txt}
 * for every release with obfuscated runtime names (1.14.4 through 1.21.x; 26.x
 * ships deobfuscated and has no client mappings, D3). The output is a
 * {@link TinyV2Reader.TinyFile} with the fixed namespace pair
 * {@link #NS_MOJANG} ↔ {@link #NS_OFFICIAL}, so {@code
 * DefaultMappingGraph#addTinyFile} ingests it unchanged.
 *
 * <p>Namespace identity is the load-bearing decision here. client.txt's left
 * column holds Mojang's deobfuscated names and its right column holds the
 * obfuscated runtime names — and those runtime names are exactly what Fabric's
 * published intermediary files label "official" in their first column (verified
 * against 1.20.1: class {@code cmm} is Level's runtime name in both corpora).
 * Naming the columns {@code mojang} and {@code official} therefore makes a
 * client.txt set and an intermediary set compose into one connected graph:
 * mojang →(client.txt) official →(intermediary tiny) intermediary.
 *
 * <p>Column values are stored in INTERNAL form so every consumer of the graph
 * speaks one dialect: the mojang column converts dots to slashes (nested-class
 * {@code $} survives unchanged). The obfuscated column is normalized the same
 * way — short obf names contain no dots and pass through unchanged, but 1.20.1
 * client.txt also carries dotted right-hand names: the 25 identity rows for
 * classes Mojang never obfuscated ({@code com.mojang.blaze3d.platform.GlStateManager
 * -> com.mojang.blaze3d.platform.GlStateManager:}) and nested classes of
 * unobfuscated parents ({@code ...GlStateManager$BlendState -> ...GlStateManager$a:}).
 * Slash-normalizing those is what makes them join Fabric's intermediary file,
 * whose official column is slash-form; the composed audit proved the need — 28
 * 2-hop paths broke on the dotted form before the fix, 13 (the identity classes
 * the intermediary file genuinely does not carry) after.
 * Member descriptors are converted from the java signatures into internal
 * descriptor form in the ns[0] (mojang) namespace — which is precisely the
 * convention {@code addTinyFile} documents, and client.txt's member signatures
 * reference mojang names, so descriptors rewritten per side come out correct on
 * both columns.
 *
 * <p>Accepted grammar (pinned against the real 1.20.1 file, 2026-08-29):
 * {@code #} comments; class rows {@code <mojang.Name> -> <obf>:} at indent 0;
 * member rows indented — fields {@code <type> <name> -> <obf>}, methods
 * {@code [<line>:<line>:]*<ret> <name>(<args>) -> <obf>} where the leading
 * line-number ranges (negative components included, several after inlining) are
 * stripped, constructors may omit the {@code void} return type, and per-class
 * SourceFile rows (target ends with ':') carry no symbols and are skipped.
 * Malformed rows throw with line context — never silently truncated, mirroring
 * {@link TinyV2Reader}.
 */
public final class ProGuardReader {

    /** Mojang's deobfuscated names — client.txt's left column. */
    public static final String NS_MOJANG = "mojang";

    /**
     * The obfuscated runtime names — client.txt's right column, and by deliberate
     * shared identity the first column of Fabric's intermediary files. The shared
     * label is what lets the two corpora compose into one graph node.
     */
    public static final String NS_OFFICIAL = "official";

    private ProGuardReader() {}

    /**
     * Parses a ProGuard-format mapping file (Mojang client.txt).
     *
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException on malformed structure (bad class row,
     *                                  member row before any class, signature
     *                                  that does not parse)
     */
    public static TinyV2Reader.TinyFile read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<TinyV2Reader.ClassEntry> classes = new ArrayList<>();
        String[] pendingNames = null;
        List<TinyV2Reader.FieldEntry> pendingFields = new ArrayList<>();
        List<TinyV2Reader.MethodEntry> pendingMethods = new ArrayList<>();

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = stripTrailingJunk(lineNo == 1 ? stripBom(line) : line);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (Character.isWhitespace(line.charAt(0))) {
                    if (pendingNames == null) {
                        throw malformed(lineNo, "member row before any class row");
                    }
                    parseMember(line, pendingNames, pendingFields, pendingMethods, lineNo);
                } else {
                    if (!line.endsWith(":")) {
                        throw malformed(lineNo, "class row must end with ':'");
                    }
                    int arrow = line.lastIndexOf(" -> ");
                    if (arrow <= 0) {
                        throw malformed(lineNo, "class row must be '<mapped> -> <obf>:'");
                    }
                    String mapped = line.substring(0, arrow).trim();
                    String obf = line.substring(arrow + 4, line.length() - 1).trim();
                    if (mapped.isEmpty() || obf.isEmpty()) {
                        throw malformed(lineNo, "class row has an empty name");
                    }
                    if (pendingNames != null) {
                        classes.add(new TinyV2Reader.ClassEntry(pendingNames,
                                List.copyOf(pendingFields), List.copyOf(pendingMethods)));
                        pendingFields = new ArrayList<>();
                        pendingMethods = new ArrayList<>();
                    }
                    pendingNames = new String[] { internal(mapped), internal(obf) };
                }
            }
        }
        if (pendingNames != null) {
            classes.add(new TinyV2Reader.ClassEntry(pendingNames,
                    List.copyOf(pendingFields), List.copyOf(pendingMethods)));
        }
        return new TinyV2Reader.TinyFile(List.of(NS_MOJANG, NS_OFFICIAL), List.copyOf(classes));
    }

    private static IllegalArgumentException malformed(int lineNo, String msg) {
        return new IllegalArgumentException("proguard: line " + lineNo + ": " + msg);
    }

    /**
     * One indented row. Method rows may carry leading line-number ranges and,
     * on ancient files, per-class SourceFile rows (whose target ends with ':')
     * — neither carries symbol mappings.
     */
    private static void parseMember(String line, String[] pendingNames,
                                    List<TinyV2Reader.FieldEntry> fields,
                                    List<TinyV2Reader.MethodEntry> methods,
                                    int lineNo) {
        String body = line.trim();
        if (body.contains(" -> ") && body.endsWith(":")) {
            // SourceFile row ("<Foo.java> -> <obf>:") — per-class debug metadata.
            // Absent from current client.txt files but legal ProGuard output. The
            // "<file> -> <obf>" shape is required: a bare ':' or lone line range
            // must fall through to validation with line context, never be dropped.
            return;
        }
        body = stripLineNumbers(body, lineNo);
        int arrow = body.lastIndexOf(" -> ");
        if (arrow <= 0) {
            throw malformed(lineNo, "member row must be '<decl> -> <obf>'");
        }
        String decl = body.substring(0, arrow).trim();
        String obf = body.substring(arrow + 4).trim();
        if (decl.isEmpty() || obf.isEmpty()) {
            throw malformed(lineNo, "member row has an empty declaration or target");
        }
        int paren = decl.indexOf('(');
        if (paren >= 0) {
            parseMethod(decl, paren, obf, pendingNames, methods, lineNo);
        } else {
            parseField(decl, obf, fields, lineNo);
        }
    }

    private static void parseMethod(String decl, int paren, String obf, String[] pendingNames,
                                    List<TinyV2Reader.MethodEntry> methods, int lineNo) {
        // The signature may carry parameter-position markers after the ')'
        // ("...:0:0") in some ProGuard dialects; tolerate and ignore them.
        int close = decl.lastIndexOf(')');
        String afterArgs = close + 1 < decl.length() ? decl.substring(close + 1) : "";
        if (!afterArgs.isEmpty() && !afterArgs.matches("(:\\d+:\\d+)+")) {
            throw malformed(lineNo, "unexpected text after method signature: '" + afterArgs + "'");
        }
        String head = decl.substring(0, paren).trim();
        String args = decl.substring(paren + 1, close).trim();
        String ret;
        String name;
        // Types never contain spaces, so the LAST space separates return type and
        // name — array-typed returns with spaced dims ("java.lang.String [][] m")
        // fold into the first-space split, land in the name, and get silently
        // dropped from the descriptor. Mirrors parseField's lastIndexOf split.
        int space = head.lastIndexOf(' ');
        if (space < 0) {
            // Constructors and <clinit> may print without a return type.
            if ("<init>".equals(head) || "<clinit>".equals(head)) {
                ret = "void";
                name = head;
            } else {
                throw malformed(lineNo, "method row has no return type: '" + head + "'");
            }
        } else {
            ret = head.substring(0, space).trim();
            name = head.substring(space + 1).trim();
        }
        if (name.isEmpty()) {
            throw malformed(lineNo, "method row has an empty name");
        }
        methods.add(new TinyV2Reader.MethodEntry(
                descriptor(ret, args, lineNo), new String[] { name, obf }));
    }

    private static void parseField(String decl, String obf,
                                   List<TinyV2Reader.FieldEntry> fields, int lineNo) {
        // Types never contain spaces, so the LAST space separates type and name.
        int space = decl.lastIndexOf(' ');
        if (space < 0) {
            throw malformed(lineNo, "field row must be '<type> <name> -> <obf>'");
        }
        String type = decl.substring(0, space).trim();
        String name = decl.substring(space + 1).trim();
        if (type.isEmpty() || name.isEmpty()) {
            throw malformed(lineNo, "field row has an empty type or name");
        }
        // Fields carry a BARE type descriptor — no parentheses; the paren-wrapping
        // descriptor() overload is for methods only.
        fields.add(new TinyV2Reader.FieldEntry(descOf(type, lineNo), new String[] { name, obf }));
    }

    /**
     * Strips leading line-number ranges ({@code 10:10:}, {@code 12:-1:22:24:})
     * that ProGuard prefixes to method rows after inlining. A signature never
     * begins with a digit, so an unterminated range is malformed input, not a
     * valid member, and gets a line-context error.
     */
    private static String stripLineNumbers(String s, int lineNo) {
        int i = 0;
        int n = s.length();
        while (true) {
            int start = i;
            if (i < n && s.charAt(i) == '-') { // a range component may be negative (inlining)
                i++;
            }
            int runStart = i;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i == runStart) {
                if (start != i) {
                    throw malformed(lineNo, "line-number range has '-' without a number");
                }
                break; // signature begins — never with a digit or '-' followed by one
            }
            if (i < n && s.charAt(i) == ':') {
                i++;
            } else {
                throw malformed(lineNo, "line-number range '"
                        + s.substring(start, i) + "' not followed by ':'");
            }
        }
        return i == 0 ? s : s.substring(i);
    }

    /**
     * One METHOD signature: {@code ret(args)} in java types, in one internal
     * descriptor string. An empty args string yields {@code ()}.
     */
    private static String descriptor(String retType, String args, int lineNo) {
        StringBuilder sb = new StringBuilder("(");
        if (args != null && !args.isEmpty()) {
            for (String a : args.split(",")) {
                sb.append(descOf(a.trim(), lineNo));
            }
        }
        sb.append(')').append(descOf(retType, lineNo));
        return sb.toString();
    }

    /**
     * One java type → internal descriptor form. Trailing {@code []} dims stack;
     * varargs {@code ...} counts as one array dimension (the compiled form).
     */
    private static String descOf(String type, int lineNo) {
        String t = type;
        int dims = 0;
        while (true) {
            if (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2);
            } else if (t.endsWith("...")) {
                dims++;
                t = t.substring(0, t.length() - 3);
            } else {
                break;
            }
        }
        // The dims loop leaves a trailing gap when a spaced spelling was used
        // ("java.lang.String [][]"); trim before judging the residual. Anything
        // that still carries brackets or whitespace (a folded-in parameter name,
        // say) cannot form a descriptor core — building an 'L...;' from it would
        // silently corrupt the row, so it gets a line-context error instead.
        t = t.trim();
        if (t.isEmpty()) {
            throw malformed(lineNo, "empty type in signature");
        }
        for (int k = 0; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch == '[' || ch == ']' || Character.isWhitespace(ch)) {
                throw malformed(lineNo, "stray brackets or spaces in type: '" + t + "'");
            }
        }
        String base = switch (t) {
            case "void" -> "V";
            case "int" -> "I";
            case "long" -> "J";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "float" -> "F";
            case "double" -> "D";
            default -> null;
        };
        String core;
        if (base != null) {
            core = base;
        } else {
            core = "L" + internal(t) + ";";
        }
        return "[".repeat(dims) + core;
    }

    /** Dotted binary name → internal name; nested-class {@code $} already correct. */
    private static String internal(String dotted) {
        return dotted.replace('.', '/');
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

    /** First-line UTF-8 BOM tolerance; real client.txt files carry none. */
    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
