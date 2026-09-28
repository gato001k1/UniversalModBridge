package dev.umb.rendermap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Universality ratchet: fails if any file under a covered module's {@code src/main} contains
 * a legacy-mod identifier outside comments. Resolution logic must key on bytecode type flow
 * (Forge/vanilla API descriptors, interface shapes, field descriptors), never on a mod's
 * names — a literal can only ever resolve the mod it names.
 *
 * <p>Covered modules ({@link #MODULES}): umb-rendermap (extractor mandate #4),
 * umb-legacy (has no src/main — vacuous pass today; its scenario fixtures live in
 * legacyside/boot/test, out of this scope), umb-objbridge and umb-hostagent (burned down
 * by the universality follow-up lane: probe defaults moved to driver scripts, report
 * headers derived from data, E2E scenarios read from JSON data files, load-bearing
 * namespace/jar defaults replaced by derive-or-fail). Rollout remainder (not covered,
 * different ownership): umb-console (default mod dirs + default ns fallback in
 * ConsoleServer).
 */
class ModIdentifierGateTest {

    static final List<String> MODULES =
            List.of("umb-rendermap", "umb-legacy", "umb-objbridge", "umb-hostagent");

    static final List<String> IDENTIFIERS = List.of(
            "hbm", "mcheli", "com/hbm", "com.hbm", "ironchest", "progwml6");

    @Test
    void noModIdentifiersOutsideComments() throws Exception {
        Path repo = repoRoot();
        List<String> violations = new ArrayList<>();
        Pattern id = Pattern.compile(String.join("|",
                IDENTIFIERS.stream().map(Pattern::quote).toList()), Pattern.CASE_INSENSITIVE);
        for (String module : MODULES) {
            Path src = repo.resolve(module).resolve("src").resolve("main");
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                    Matcher m = id.matcher(code);
                    while (m.find()) {
                        int line = 1;
                        for (int i = 0; i < m.start(); i++) if (code.charAt(i) == '\n') line++;
                        violations.add(repo.relativize(p) + ":" + line + ": " + m.group());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "mod identifiers in main sources outside comments (" + violations.size() + "):\n"
                + String.join("\n", violations.subList(0, Math.min(40, violations.size()))));
    }

    /**
     * Strips {@code //} and {@code /*} {@code *}{@code /} comments, string/char-literal aware
     * (a {@code //} inside {@code "http://..."} or a regex is code, not a comment).
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        char inStr = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (inStr != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(src.charAt(i + 1)); i += 2; continue; }
                if (c == inStr) inStr = 0;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = c; out.append(c); i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static Path repoRoot() {
        String p = System.getProperty("umb.repo");
        if (p != null) return Path.of(p);
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve("umb-rendermap")) && Files.isDirectory(cur.resolve("research")))
                return cur;
            cur = cur.getParent();
        }
        throw new IllegalStateException("cannot locate repo root; pass -Dumb.repo=<path>");
    }
}
