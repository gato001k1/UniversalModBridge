package dev.umb.legacy.api;

/** A CreativeTabs slot from the live CreativeTabs.creativeTabArray. */
public final class TabEntry {

    private final int index;
    private final String label;
    private final String className;
    private final String translatedLabel;
    private final String packagePrefix;

    /**
 * GENERALITY fix : {@code packagePrefix} replaces what used to be a {@code boolean hbm} field.
 * Forge 1.7.10 creative-tab registration carries no per-entry modid at all (unlike blocks/items, which get a real namespaced registry name) - see {@code...
 */
    public TabEntry(int index, String label, String className, String translatedLabel, String packagePrefix) {
        this.index = index;
        this.label = label;
        this.className = className;
        this.translatedLabel = translatedLabel;
        this.packagePrefix = packagePrefix;
    }

    public int index() { return index; }
    public String label() { return label; }
    public String className() { return className; }
    public String translatedLabel() { return translatedLabel; }
    public String packagePrefix() { return packagePrefix; }
}
