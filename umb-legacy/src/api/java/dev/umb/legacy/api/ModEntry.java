package dev.umb.legacy.api;

/** One loaded ModContainer as FML sees it after postInit. */
public final class ModEntry {

    private final String modid;
    private final String name;
    private final String version;
    private final String sourceJarName;
    private final String state;

    public ModEntry(String modid, String name, String version, String sourceJarName, String state) {
        this.modid = modid;
        this.name = name;
        this.version = version;
        this.sourceJarName = sourceJarName;
        this.state = state;
    }

    public String modid() { return modid; }
    public String name() { return name; }
    public String version() { return version; }
    public String sourceJarName() { return sourceJarName; }
    public String state() { return state; }
}
