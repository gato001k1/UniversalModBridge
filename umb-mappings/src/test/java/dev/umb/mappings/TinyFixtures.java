package dev.umb.mappings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the checked-in tiny v2 fixtures and materializes ad-hoc snippets
 * into scratch files for malformed-input cases. The test harness runs JUnit
 * straight off a classes directory (tools/windows/run-tests.ps1) with no resource
 * copying, so classpath lookups cannot find src/test/resources — instead the
 * module root is found by searching upward from the working directory, which
 * works from any ancestor of the repo.
 */
final class TinyFixtures {

    /** Module-relative directory holding the checked-in fixtures. */
    private static final String MODULE_REL = "umb-mappings/src/test/resources/tiny";

    private TinyFixtures() {}

    /** The three-namespace official/intermediary/named fixture. */
    static Path sampleOfficialIntermediary() {
        String leaf = "sample-official-intermediary.tiny";
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(MODULE_REL).resolve(leaf);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("fixture " + leaf + " not found searching upward from " + cwd);
    }

    /** Writes {@code content} into {@code dir} as UTF-8 and returns its path. */
    static Path write(Path dir, String fileName, String content) {
        try {
            Path p = dir.resolve(fileName);
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("scratch fixture write failed", e);
        }
    }
}
