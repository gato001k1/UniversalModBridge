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
 * Parser for tiny v2 mappings text (spec §13: published provenance ingested from
 * official/Fabric tiny files). Streaming line-by-line so a multi-MB real-world
 * file (e.g. the MC 1.20.1 merged mappings, ~45k rows) parses comfortably under
 * a 256m heap — the file is never slurped into one string and per-row garbage
 * is limited to one token array.
 *
 * <p>Grammar accepted: header {@code tiny<TAB>2<TAB>0<TAB><ns0><TAB><ns1>...};
 * property rows between header and first class row; class rows {@code c} at
 * indent 0; member rows {@code f}/{@code m} at indent 1; deeper rows (e.g.
 * parameter rows at indent 2) are skipped gracefully because they carry no
 * symbol-mapping payload for this module.
 *
 * <p>Comments per the tiny v2 grammar are rows typed {@code c} nested under
 * their element ({@code \tc\t<javadoc>} under a class, two tabs under a member);
 * yarn distributions embed javadoc this way, so they are skipped wherever they
 * appear. Lines starting with {@code #} are NOT part of v2 but are tolerated as
 * out-of-spec leniency for hand-edited files. Property rows ({@code
 * \tescaped-names}, {@code \tmissing-lvt-indices}, unknown keys alike) are
 * skipped without error as the spec mandates; {@code escaped-names} is not
 * consulted before unescaping because conf-safe names never contain a
 * backslash, so gating could only change behavior on ill-formed input.
 *
 * <p>Name tokens carry tiny-v2 escapes ({@code \t \r \n \\ \.}) which are
 * unescaped AFTER tab-splitting — an escaped tab is data, a raw tab is always a
 * separator. Trailing spaces (and stray carriage returns) are stripped from
 * every line before splitting so editor sloppiness cannot glue into name
 * tokens; trailing TABS survive because a trailing empty name column is legal,
 * while empty namespace tokens in the header are dropped so a stray tab there
 * cannot fabricate a phantom column.
 */
public final class TinyV2Reader {

    private TinyV2Reader() {}

    /** One parsed tiny v2 file: header namespace order plus class rows in file order. */
    public record TinyFile(List<String> namespaces, List<ClassEntry> classes) {}

    /**
     * One {@code c} row plus its members.
     * names array is parallel to the namespaces list order from the header.
     */
    public record ClassEntry(String[] names, List<FieldEntry> fields, List<MethodEntry> methods) {}

    /** One {@code f} row. descriptor is in namespace[0] form. */
    public record FieldEntry(String descriptor, String[] names) {}

    /** One {@code m} row. Same descriptor rule. */
    public record MethodEntry(String descriptor, String[] names) {}

    /**
     * Parses a tiny v2 file.
     *
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException on malformed structure (bad header, wrong
     *                                  row arity, orphaned member rows, unknown
     *                                  row types after the classes began)
     */
    public static TinyFile read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<String> namespaces = null;
        List<ClassEntry> classes = new ArrayList<>();
        String[] pendingNames = null;
        List<FieldEntry> pendingFields = new ArrayList<>();
        List<MethodEntry> pendingMethods = new ArrayList<>();

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = stripTrailingJunk(line);
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == '\t') {
                    indent++;
                }
                if (indent == line.length()) {
                    continue; // blank (or whitespace/tab-only) line
                }
                // -1 limit keeps trailing empty tokens so arity checks see real structure.
                String[] t = line.substring(indent).split("\t", -1);
                if (t[0].startsWith("#")) {
                    continue; // comment row at any depth
                }
                if (namespaces == null) {
                    if (!"tiny".equals(t[0])) {
                        throw malformed(lineNo, "expected tiny v2 header, got row '" + t[0] + "'");
                    }
                    if (t.length < 4 || !"2".equals(t[1])) {
                        throw malformed(lineNo, "unsupported tiny header (want tiny<TAB>2<TAB>0<TAB>ns...)");
                    }
                    // Empty tokens are dropped: a trailing tab on the header is
                    // editor sloppiness, and keeping it would fabricate a phantom
                    // namespace that mispoints every later arity error.
                    List<String> ns = new ArrayList<>(t.length - 3);
                    for (int i = 3; i < t.length; i++) {
                        String n = unescape(t[i]);
                        if (!n.isEmpty()) {
                            ns.add(n);
                        }
                    }
                    if (ns.isEmpty()) {
                        throw malformed(lineNo, "header declares no namespaces");
                    }
                    namespaces = List.copyOf(ns);
                    continue;
                }
                switch (indent) {
                    case 0 -> {
                        if (!"c".equals(t[0])) {
                            throw malformed(lineNo, "unknown top-level row '" + t[0] + "'");
                        }
                        if (t.length != namespaces.size() + 1) {
                            throw malformed(lineNo, "class row arity " + t.length + ", want " + (namespaces.size() + 1));
                        }
                        if (pendingNames != null) {
                            classes.add(new ClassEntry(pendingNames,
                                    List.copyOf(pendingFields), List.copyOf(pendingMethods)));
                            pendingFields = new ArrayList<>();
                            pendingMethods = new ArrayList<>();
                        }
                        pendingNames = unescapeAll(t, 1);
                    }
                    case 1 -> {
                        String type = t[0];
                        if ("c".equals(type)) {
                            // tiny v2 comment row (class javadoc lives here in
                            // yarn-style files): no symbol-mapping payload.
                            break;
                        }
                        if (pendingNames == null) {
                            if ("f".equals(type) || "m".equals(type)) {
                                throw malformed(lineNo, "'" + type + "' row before any class row");
                            }
                            // Between header and first class the grammar allows
                            // only property rows; unknown keys are skipped
                            // without an error as the spec mandates.
                            break;
                        }
                        if (t.length != namespaces.size() + 2) {
                            throw malformed(lineNo, "member row arity " + t.length + ", want " + (namespaces.size() + 2));
                        }
                        switch (type) {
                            case "f" -> pendingFields.add(new FieldEntry(maybeDescriptor(unescape(t[1])), unescapeAll(t, 2)));
                            case "m" -> pendingMethods.add(new MethodEntry(maybeDescriptor(unescape(t[1])), unescapeAll(t, 2)));
                            default -> throw malformed(lineNo, "unknown class-member row '" + type + "'");
                        }
                    }
                    default -> {
                        // Deeper indents carry no mapping payload for this module:
                        // parameter rows ("\t\tp ...") and member-level v2 comment
                        // rows ("\t\tc ...") are skipped gracefully.
                    }
                }
            }
        }
        if (namespaces == null) {
            throw new IllegalArgumentException("tiny v2: file has no header row");
        }
        if (pendingNames != null) {
            classes.add(new ClassEntry(pendingNames,
                    List.copyOf(pendingFields), List.copyOf(pendingMethods)));
        }
        return new TinyFile(namespaces, List.copyOf(classes));
    }

    /**
     * An EMPTY descriptor token is the tiny-v2 "no type" marker (intermediary
     * field rows carry an empty type token; {@link TinyV2Writer} emits one for a
     * null descriptor). It normalizes to null so the module's descriptor
     * convention — an untyped field stays null, never an invented "" (D4) —
     * survives the write/read round trip and graph ingestion treats it exactly
     * like SrgReader's null FD rows.
     */
    private static String maybeDescriptor(String desc) {
        return desc.isEmpty() ? null : desc;
    }

    private static IllegalArgumentException malformed(int lineNo, String msg) {
        return new IllegalArgumentException("tiny v2: line " + lineNo + ": " + msg);
    }

    /**
     * Trailing spaces (and stray carriage returns) never belong to a token —
     * conf-safe names contain neither — so they are removed before splitting;
     * left in place they silently corrupted the last name of a row. Trailing
     * TABS are kept because a trailing empty name column is legal v2 and must
     * stay visible to the arity checks.
     */
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

    private static String[] unescapeAll(String[] t, int from) {
        String[] out = new String[t.length - from];
        for (int i = from; i < t.length; i++) {
            out[i - from] = unescape(t[i]);
        }
        return out;
    }

    /**
     * Tiny v2 name-token escapes per the format contract: {@code \t \r \n \\ \.}.
     * Unknown escapes keep their backslash so future extensions survive a
     * round-trip instead of being silently eaten.
     */
    private static String unescape(String tok) {
        if (tok.indexOf('\\') < 0) {
            return tok;
        }
        StringBuilder sb = new StringBuilder(tok.length());
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c == '\\' && i + 1 < tok.length()) {
                char n = tok.charAt(++i);
                switch (n) {
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'n' -> sb.append('\n');
                    case '\\' -> sb.append('\\');
                    case '.' -> sb.append('.');
                    default -> sb.append(c).append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
