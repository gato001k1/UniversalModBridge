package dev.umb.pipeline;

import java.util.List;

/**
 * Ordered composition of passes. Initial order follows spec §25; passes may report
 * SKIP and the pipeline continues unless a REQUIRED pass fails.
 */
public interface Pipeline {
    List<TranslationPass> passes();

    default List<String> ids() {
        return passes().stream().map(TranslationPass::id).toList();
    }
}
