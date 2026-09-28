package dev.umb.legacy1122.test;

import java.io.File;

/**
 * Finds the repo root for tests that need real on-disk artifacts (the fetched 1.12.2 jars, the
 * sibling {@code umb-legacy} module's bridge-api). Prefers {@code -Dumb.repo=...} (set by
 * {@code run-tests.ps1}); falls back to walking up from the working directory looking for
 * {@code settings.gradle}, which only exists at the repo root.
 */
final class TestRepo {
    private TestRepo() {
    }

    static File find() {
        String prop = System.getProperty("umb.repo");
        if (prop != null) {
            return new File(prop).getAbsoluteFile();
        }
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "settings.gradle").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(
                "cannot find repo root: pass -Dumb.repo=<path> or run from inside the repo tree");
    }
}
