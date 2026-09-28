package dev.umb.legacy.api;

/** A block or an item as it exists in the live FMLControlledNamespacedRegistry. */
public final class NamedEntry {

    private final String name;
    private final int id;
    private final String className;
    private final String unlocalizedName;
    private final String creativeTab;
    private final boolean hasTileEntity;
    private final String tileEntityClass;

    public NamedEntry(String name, int id, String className, String unlocalizedName, String creativeTab) {
        this(name, id, className, unlocalizedName, creativeTab, false, null);
    }

    public NamedEntry(String name, int id, String className, String unlocalizedName, String creativeTab,
                      boolean hasTileEntity, String tileEntityClass) {
        this.name = name;
        this.id = id;
        this.className = className;
        this.unlocalizedName = unlocalizedName;
        this.creativeTab = creativeTab;
        this.hasTileEntity = hasTileEntity;
        this.tileEntityClass = tileEntityClass;
    }

    public String name() { return name; }
    public int id() { return id; }
    public String className() { return className; }
    public String unlocalizedName() { return unlocalizedName; }
    public String creativeTab() { return creativeTab; }
    public boolean hasTileEntity() { return hasTileEntity; }
    public String tileEntityClass() { return tileEntityClass; }

    /**
 * GENERALITY fix : this used to be {@code boolean hbm()}, a permanent {@code false} for any mod other than the hardcoded "hbm:" prefix.
 * The registry name IS namespaced (standard {@code FMLControlledNamespacedRegistry} convention - {@code <modid>:<path>}), so...
 */
    public String namespace() {
        if (name == null) return null;
        int i = name.indexOf(':');
        return i < 0 ? null : name.substring(0, i);
    }
}
