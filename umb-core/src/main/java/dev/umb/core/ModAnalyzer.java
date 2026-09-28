package dev.umb.core;

import java.io.IOException;
import java.nio.file.Path;

/** Analyzes a mod jar and produces a {@link ModAnalysis}. Spec §17–§18. */
public interface ModAnalyzer {
    ModAnalysis analyze(Path jar) throws IOException;
}
