package dev.umb.pipeline;

import dev.umb.core.ModAnalysis;

import java.nio.file.Path;

/**
 * One stage of the translation pipeline (spec §25). Passes must be independently
 * runnable and testable; a pass reads inputs from its working directory and writes
 * outputs for the next pass. Never mutates the original jar (spec §24).
 */
public interface TranslationPass {
    /** Stable pass id, e.g. "P03-namespace". Ordering is defined by {@link Pipeline}. */
    String id();

    /**
     * @param analysis result of mod analysis
     * @param input    jar/state entering this pass (jar on disk or intermediate dir)
     * @param output   location this pass must write its result to
     * @return report of what happened; failures carry structured diagnostics
     */
    PassReport run(ModAnalysis analysis, Path input, Path output) throws Exception;
}
