package dev.umb.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reader for the three MCP CSV namespace files (fields.csv / methods.csv /
 * params.csv) that pair with joined.srg. These carry the HUMAN-READABLE deobf
 * names that the searge tokens translate to — the layer between a (version, srg)
 * node and a future (version, mcp) node. They carry NO owner-class context
 * (fields.csv columns are searge,name,side; a member symbol needs its owner, which
 * only joined.srg provides), so joining them to the srg owner map is a separate
 * materialization step reserved for that wave; this reader only parses and
 * validates each file's shape so a bad row cannot hide in a future join.
 *
 * <p>Columns, matching the published MCPConfig layout:
 * <ul>
 *   <li>fields.csv: {@code searge,name,side} — searge is {@code field_&lt;digits&gt;},
 *       side ∈ {0 client, 1 server, 2 both}.</li>
 *   <li>methods.csv: {@code searge,name,side,descriptor} — searge is
 *       {@code func_&lt;digits&gt;}, descriptor is the bytecode-form method descriptor
 *       ({@code ()F}); it is the only MCP file other than the srg MD rows that
 *       carries a descriptor, and usable for cross-source identity checks.</li>
 *   <li>params.csv: {@code param,searge,side} — searge is {@code p_&lt;digits&gt;_&lt;digits&gt;},
 *       param is the deobf param name; params are DEFERRED (schema pending), so
 *       this reader validates the tokens and drops the values.</li>
 * </ul>
 * No header rows in MCP output; plain comma-separated, UTF-8, CRLF- and
 * BOM-tolerant. Malformed rows throw with line context, never silently skipped
 * (D4), mirroring the srg and tiny readers.
 */
public final class McpCsvReader {

    public record FieldRow(String searge, String name, int side) {}

    public record MethodRow(String searge, String name, int side, String descriptor) {}

    public record ParamRow(String param, String searge, int side) {}

    private static final Pattern FIELD_SEARGE = Pattern.compile("field_\\d+.*");
    private static final Pattern METHOD_SEARGE = Pattern.compile("func_\\d+.*");
    private static final Pattern PARAM_SEARGE = Pattern.compile("p_\\d+_.*");

    private McpCsvReader() {}

    /** Parses fields.csv. */
    public static List<FieldRow> readFields(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<FieldRow> out = new ArrayList<>();
        lineReader(file, (lineNo, cells) -> {
            requireArity(file, lineNo, cells, 3);
            String searge = cells[0].trim();
            String name = cells[1].trim();
            int side = side(cells[2], file, lineNo);
            if (!FIELD_SEARGE.matcher(searge).matches()) {
                throw csv(file, lineNo, "field searge '" + searge + "' does not look like field_<digits>");
            }
            if (name.isEmpty()) {
                throw csv(file, lineNo, "field row has an empty name");
            }
            out.add(new FieldRow(searge, name, side));
        });
        return List.copyOf(out);
    }

    /** Parses methods.csv. */
    public static List<MethodRow> readMethods(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<MethodRow> out = new ArrayList<>();
        lineReader(file, (lineNo, cells) -> {
            requireArity(file, lineNo, cells, 4);
            String searge = cells[0].trim();
            String name = cells[1].trim();
            int side = side(cells[2], file, lineNo);
            String desc = cells[3].trim();
            if (!METHOD_SEARGE.matcher(searge).matches()) {
                throw csv(file, lineNo, "method searge '" + searge + "' does not look like func_<digits>");
            }
            if (name.isEmpty()) {
                throw csv(file, lineNo, "method row has an empty name");
            }
            if (desc.isEmpty() || !desc.startsWith("(")) {
                throw csv(file, lineNo, "method descriptor must begin with '(': '" + desc + "'");
            }
            out.add(new MethodRow(searge, name, side, desc));
        });
        return List.copyOf(out);
    }

    /** Parses params.csv. */
    public static List<ParamRow> readParams(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        List<ParamRow> out = new ArrayList<>();
        lineReader(file, (lineNo, cells) -> {
            requireArity(file, lineNo, cells, 3);
            String param = cells[0].trim();
            String searge = cells[1].trim();
            int side = side(cells[2], file, lineNo);
            if (!PARAM_SEARGE.matcher(searge).matches()) {
                throw csv(file, lineNo, "param searge '" + searge + "' does not look like p_<digits>_<digits>");
            }
            out.add(new ParamRow(param, searge, side));
        });
        return List.copyOf(out);
    }

    @FunctionalInterface
    private interface RowSink {
        void accept(int lineNo, String[] cells);
    }

    /** Streams one CSV file, stripping BOM on line 1 and trailing CRs everywhere. */
    private static void lineReader(Path file, RowSink sink) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty()) {
                    continue;
                }
                // -1 keeps trailing empty cells so arity checks see real structure.
                sink.accept(lineNo, line.split(",", -1));
            }
        }
    }

    private static void requireArity(Path file, int lineNo, String[] cells, int want) {
        if (cells.length != want) {
            throw csv(file, lineNo, "expected " + want + " columns, got " + cells.length);
        }
    }

    /** Side codes: 0 client, 1 server, 2 both. Anything else is a D4 violation. */
    private static int side(String cell, Path file, int lineNo) {
        int v;
        try {
            v = Integer.parseInt(cell.trim());
        } catch (NumberFormatException e) {
            throw csv(file, lineNo, "side must be an integer, got '" + cell + "'");
        }
        if (v < 0 || v > 2) {
            throw csv(file, lineNo, "side must be 0 (client), 1 (server) or 2 (both), got " + v);
        }
        return v;
    }

    private static IllegalArgumentException csv(Path file, int lineNo, String msg) {
        return new IllegalArgumentException("mcpcsv: " + file.getFileName() + " line " + lineNo + ": " + msg);
    }
}