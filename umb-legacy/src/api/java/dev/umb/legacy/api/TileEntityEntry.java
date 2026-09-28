package dev.umb.legacy.api;

/** One entry of the live TileEntity.nameToClassMap. */
public final class TileEntityEntry {

    private final String name;
    private final String className;
    private final String packagePrefix;

    /**
 * GENERALITY fix : {@code packagePrefix} replaces what used to be a {@code boolean hbm} field.
 * {@code TileEntity.field_145855_i}'s registration name is a bare string with no modid/namespace convention at all in 1.7.10 (unlike blocks/items) - see {@code...
 */
    public TileEntityEntry(String name, String className, String packagePrefix) {
        this.name = name;
        this.className = className;
        this.packagePrefix = packagePrefix;
    }

    public String name() { return name; }
    public String className() { return className; }
    public String packagePrefix() { return packagePrefix; }
}
