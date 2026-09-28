package dev.umb.legacy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The whole plain-data view of the live legacy registries after postInit. */
public final class RegistrySnapshot {

    private final String mcVersion;
    private final String forgeVersion;
    private final String fmlVersion;
    private final String side;
    private final List<ModEntry> mods;
    private final List<NamedEntry> blocks;
    private final List<NamedEntry> items;
    private final List<TabEntry> tabs;
    private final List<TileEntityEntry> tileEntities;
    private final List<String> oreNames;
    private final List<FluidEntry> fluids;
    private final List<EntityEntry> entities;
    private final Map<String, Integer> counts;

    public RegistrySnapshot(String mcVersion, String forgeVersion, String fmlVersion, String side,
                            List<ModEntry> mods, List<NamedEntry> blocks, List<NamedEntry> items,
                            List<TabEntry> tabs, List<TileEntityEntry> tileEntities,
                            List<String> oreNames, List<FluidEntry> fluids, List<EntityEntry> entities,
                            Map<String, Integer> counts) {
        this.mcVersion = mcVersion;
        this.forgeVersion = forgeVersion;
        this.fmlVersion = fmlVersion;
        this.side = side;
        this.mods = ro(mods);
        this.blocks = ro(blocks);
        this.items = ro(items);
        this.tabs = ro(tabs);
        this.tileEntities = ro(tileEntities);
        this.oreNames = ro(oreNames);
        this.fluids = ro(fluids);
        this.entities = ro(entities);
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(counts));
    }

    private static <T> List<T> ro(List<T> in) {
        return Collections.unmodifiableList(new ArrayList<T>(in));
    }

    public String mcVersion() { return mcVersion; }
    public String forgeVersion() { return forgeVersion; }
    public String fmlVersion() { return fmlVersion; }
    public String side() { return side; }
    public List<ModEntry> mods() { return mods; }
    public List<NamedEntry> blocks() { return blocks; }
    public List<NamedEntry> items() { return items; }
    public List<TabEntry> tabs() { return tabs; }
    public List<TileEntityEntry> tileEntities() { return tileEntities; }
    public List<String> oreNames() { return oreNames; }
    public List<FluidEntry> fluids() { return fluids; }
    public List<EntityEntry> entities() { return entities; }
    public Map<String, Integer> counts() { return counts; }
}
