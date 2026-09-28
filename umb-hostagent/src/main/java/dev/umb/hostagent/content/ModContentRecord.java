package dev.umb.hostagent.content;

import java.nio.file.Path;

/** Immutable build-time content inputs for one legacy mod namespace. */
public record ModContentRecord(String namespace, Path snapshot, Path lang, Path blockShapes,
                               Path guiProfile, Path jar, Path recipes, String era,
                               Path basePack) {
    /**
     * Pre-era records (and tests) keep working: no era stated means the original 1.7.10
     * universe. Only an explicit non-1.7.10 era routes anywhere else (see BridgeRouter).
     */
    public ModContentRecord(String namespace, Path snapshot, Path lang, Path blockShapes,
                             Path guiProfile, Path jar) {
        this(namespace, snapshot, lang, blockShapes, guiProfile, jar, null, "1.7.10");
    }
    public ModContentRecord(String namespace, Path snapshot, Path lang, Path blockShapes,
                            Path guiProfile, Path jar, Path recipes) {
        this(namespace, snapshot, lang, blockShapes, guiProfile, jar, recipes, "1.7.10");
    }
    public ModContentRecord(String namespace, Path snapshot, Path lang, Path blockShapes,
                            Path guiProfile, Path jar, Path recipes, String era) {
        this(namespace, snapshot, lang, blockShapes, guiProfile, jar, recipes, era, null);
    }
    public ModContentRecord {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("missing namespace");
        if (snapshot == null) throw new IllegalArgumentException("missing snapshot for " + namespace);
        if (era == null || era.isBlank()) throw new IllegalArgumentException("missing era for " + namespace);
    }
}
