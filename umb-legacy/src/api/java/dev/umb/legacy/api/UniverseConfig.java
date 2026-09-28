package dev.umb.legacy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plain-data boot parameters. Immutable; java.* only. */
public final class UniverseConfig {

    private final String gameDir;
    private final String modsDir;
    private final String assetsDir;
    private final String forgeJar;
    private final boolean sideTransformer;
    private final List<String> transformers;

    public UniverseConfig(String gameDir, String modsDir, String assetsDir, String forgeJar,
                          boolean sideTransformer, List<String> transformers) {
        this.gameDir = gameDir;
        this.modsDir = modsDir;
        this.assetsDir = assetsDir;
        this.forgeJar = forgeJar;
        this.sideTransformer = sideTransformer;
        this.transformers = Collections.unmodifiableList(new ArrayList<String>(transformers));
    }

    public String gameDir() { return gameDir; }
    public String modsDir() { return modsDir; }
    public String assetsDir() { return assetsDir; }
    public String forgeJar() { return forgeJar; }
    public boolean sideTransformer() { return sideTransformer; }
    public List<String> transformers() { return transformers; }
}
