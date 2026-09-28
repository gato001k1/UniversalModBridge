package dev.umb.mappings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Minimal tiny v2 mappings EMITTER (the module previously had only a reader).
 * Writes the shape {@link TinyV2Reader} parses back: header
 * {@code tiny<TAB>2<TAB>0<TAB><ns0><TAB><ns1>...}; class rows {@code c} at
 * indent 0; member rows {@code \tf}/{@code \tm} at indent 1 with the descriptor
 * in namespace[0] form, exactly the convention
 * {@link DefaultMappingGraph#addTinyFile} documents. Names are emitted in
 * INTERNAL slash form; producers from this module (the DeriveMatcher bridge)
 * only ever hold internal-form names.
 *
 * <p>Three contract points worth naming because the reader is stricter than we
 * are:
 * <ul>
 *   <li>Every emitted token is tiny-v2-escaped ({@code \t \n \r \\ \.}) on
 *       output even though our names never contain those characters — the
 *       writer must not be the first component in a pipeline to assume the
 *       invariant.</li>
 *   <li>Class rows carry one name per header namespace; member rows likewise.
 *       A mismatch with the declared namespaces would make the READER reject
 *       the file (row-arity errors) or a by-name column resolution collide,
 *       so arity is validated before a byte is written.</li>
 *   <li>The header namespace labels must be DISTINCT — {@code GraphAuditCommand}
 *       resolves columns with {@code namespaces.indexOf(label)}, and duplicate
 *       labels silently collapse both columns to column 0. The {@code umb match}
 *       command therefore emits {@code ns@version} labels
 *       ({@code mojang@1.21.1}, {@code mojang@26.2}) which stay distinct even
 *       when both sides share a namespace name.</li>
 * </ul>
 *
 * <p>Output is published atomically (temp file in the target directory, then a
 * move that replaces — mirroring GraphJarRemapper's publish discipline) so a
 * failed or interrupted run never leaves a partial file at the target path.
 */
public final class TinyV2Writer {

    private TinyV2Writer() {}

    /**
     * Writes one tiny v2 file.
     *
     * @throws IOException              when the file cannot be written
     * @throws IllegalArgumentException when the structure is not emissible
     *                                  (fewer than two namespaces, or a row whose
     *                                  name token count differs from the header)
     */
    public static void write(Path file, List<String> namespaces,
                             List<TinyV2Reader.ClassEntry> classes) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(namespaces, "namespaces");
        Objects.requireNonNull(classes, "classes");
        if (namespaces.size() < 2) {
            throw new IllegalArgumentException(
                    "tiny v2 writer: need at least two namespaces, got " + namespaces.size());
        }
        for (TinyV2Reader.ClassEntry c : classes) {
            if (c.names().length != namespaces.size()) {
                throw new IllegalArgumentException("tiny v2 writer: class row '"
                        + c.names()[0] + "' has " + c.names().length + " names, want "
                        + namespaces.size());
            }
            for (TinyV2Reader.FieldEntry f : c.fields()) {
                checkNames(f.names(), namespaces.size(), c.names()[0]);
            }
            for (TinyV2Reader.MethodEntry m : c.methods()) {
                checkNames(m.names(), namespaces.size(), c.names()[0]);
            }
        }

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path staging = file.resolveSibling(
                file.getFileName() + ".staging-" + Long.toUnsignedString(System.nanoTime()));
        try (BufferedWriter w = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
            w.write("tiny\t2\t0");
            for (String ns : namespaces) {
                w.write('\t');
                w.write(escape(ns));
            }
            w.newLine();
            for (TinyV2Reader.ClassEntry c : classes) {
                w.write("c");
                writeNames(w, c.names());
                w.newLine();
                for (TinyV2Reader.FieldEntry f : c.fields()) {
                    w.write("\tf\t");
                    w.write(escape(f.descriptor()));
                    writeNames(w, f.names());
                    w.newLine();
                }
                for (TinyV2Reader.MethodEntry m : c.methods()) {
                    w.write("\tm\t");
                    w.write(escape(m.descriptor()));
                    writeNames(w, m.names());
                    w.newLine();
                }
            }
        } catch (IOException e) {
            // A failed write must not leave the staging debris behind any more
            // than a partial file.
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // best effort; the target was never touched
            }
            throw e;
        }
        // Atomic-ish publish: replace the target only after the whole file was
        // written without I/O failure (same discipline GraphJarRemapper uses).
        Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void checkNames(String[] names, int want, String className) {
        if (names.length != want) {
            throw new IllegalArgumentException("tiny v2 writer: member under '" + className
                    + "' has " + names.length + " names, want " + want);
        }
    }

    /** Writes one name token per column, each tab-separated. */
    private static void writeNames(BufferedWriter w, String[] names) throws IOException {
        for (String n : names) {
            w.write('\t');
            w.write(escape(n));
        }
    }

    /**
     * Tiny v2 name-token escapes per the format contract ({@code \t \r \n \\ \.}),
     * matching {@link TinyV2Reader}'s unescape table exactly so a written file
     * round-trips. Backslash first so a literal backslash cannot re-escape the
     * escape that follows it.
     *
     * <p>A null token is the FD "no type" marker (srg field rows carry none, D4:
     * never invented): it serializes as an EMPTY token, which the reader
     * normalizes back to null — the write/read pair preserves the module's null
     * convention instead of silently inventing a "" dialect.
     */
    private static String escape(String token) {
        if (token == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '.' -> sb.append("\\.");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}