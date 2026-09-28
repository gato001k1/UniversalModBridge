package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.umb.legacy.api.EntityEntry;
import dev.umb.legacy.api.FluidEntry;
import dev.umb.legacy.api.ModEntry;
import dev.umb.legacy.api.NamedEntry;
import dev.umb.legacy.api.RegistrySnapshot;
import dev.umb.legacy.api.TabEntry;
import dev.umb.legacy.api.TileEntityEntry;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameData;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;

import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Reads the LIVE legacy registries after postInit into plain data.
 *
 * <p>Every read goes through the real registry object, not a mod-supplied side channel:</p>
 * <ul>
 *   <li>blocks/items: {@code GameData.getBlockRegistry()} / {@code getItemRegistry()} - the same
 *       {@code FMLControlledNamespacedRegistry} instances that {@code Block.field_149771_c} and
 *       {@code Item.field_150901_e} point at.</li>
 *   <li>creative tabs: {@code CreativeTabs.field_78032_a} (public static array).</li>
 *   <li>tile entities: {@code TileEntity.field_145855_i} (private static nameToClassMap).</li>
 *   <li>ore dictionary: {@code OreDictionary.getOreNames()}.</li>
 *   <li>fluids: {@code FluidRegistry.getRegisteredFluids()}.</li>
 * </ul>
 */
final class RegistrySnapshotter {

    private RegistrySnapshotter() {
    }

    static RegistrySnapshot take(String side) {
        List<ModEntry> mods = new ArrayList<ModEntry>();
        for (ModContainer mc : Loader.instance().getModList()) {
            mods.add(new ModEntry(mc.getModId(), mc.getName(), mc.getVersion(),
                    mc.getSource() == null ? null : mc.getSource().getName(),
                    Loader.isModLoaded(mc.getModId()) ? "LOADED" : "PRESENT"));
        }

        List<NamedEntry> blocks = new ArrayList<NamedEntry>();
        FMLControlledNamespacedRegistry<Block> blockReg = GameData.getBlockRegistry();
        for (Block b : blockReg.typeSafeIterable()) {
            String name = blockReg.func_148750_c(b);
            blocks.add(new NamedEntry(name, blockReg.getId(b), b.getClass().getName(),
                    safeUnlocalized(b), tabLabel(safeBlockTab(b)), blockHasTileEntity(b), null));
        }

        List<NamedEntry> items = new ArrayList<NamedEntry>();
        FMLControlledNamespacedRegistry<Item> itemReg = GameData.getItemRegistry();
        for (Item it : itemReg.typeSafeIterable()) {
            String name = itemReg.func_148750_c(it);
            items.add(new NamedEntry(name, itemReg.getId(it), it.getClass().getName(),
                    safeUnlocalized(it), tabLabel(safeItemTab(it))));
        }

        List<TabEntry> tabs = new ArrayList<TabEntry>();
        CreativeTabs[] arr = CreativeTabs.field_78032_a;
        if (arr != null) {
            for (int i = 0; i < arr.length; i++) {
                CreativeTabs t = arr[i];
                if (t == null) {
                    continue;
                }
                String cls = t.getClass().getName();
                tabs.add(new TabEntry(i, t.func_78013_b(), cls, t.func_78024_c(), packagePrefixOf(cls)));
            }
        }

        List<TileEntityEntry> tes = new ArrayList<TileEntityEntry>();
        @SuppressWarnings("unchecked")
        Map<String, Class<?>> teMap = (Map<String, Class<?>>) Statics.get(TileEntity.class, "field_145855_i");
        if (teMap != null) {
            // TreeMap so the snapshot is stable across runs
            for (Map.Entry<String, Class<?>> e : new TreeMap<String, Class<?>>(teMap).entrySet()) {
                String cls = e.getValue() == null ? null : e.getValue().getName();
                tes.add(new TileEntityEntry(e.getKey(), cls, packagePrefixOf(cls)));
            }
        }

        List<String> ores = new ArrayList<String>();
        String[] oreNames = OreDictionary.getOreNames();
        if (oreNames != null) {
            ores.addAll(Arrays.asList(oreNames));
        }

        List<FluidEntry> fluids = new ArrayList<FluidEntry>();
        Map<String, Fluid> fluidMap = FluidRegistry.getRegisteredFluids();
        if (fluidMap != null) {
            for (Map.Entry<String, Fluid> e : new TreeMap<String, Fluid>(fluidMap).entrySet()) {
                Fluid f = e.getValue();
                fluids.add(new FluidEntry(e.getKey(), f.getClass().getName(), f.getUnlocalizedName(),
                        f.getLuminosity(), f.getDensity(), f.getTemperature(), f.getViscosity(),
                        f.isGaseous()));
            }
        }

        List<EntityEntry> entities = entities();

        // GENERALITY fix : this used to compute exactly one namespace's
        // "tabsHostingHbm" set (which creative tabs at least one hbm: block/item lives in,
        // including four VANILLA tabs HBM content sits inside). Generalized to every namespace
        // actually observed in blocks/items, not just one hardcoded literal - a mod hosting its
        // content in a vanilla tab is a completely ordinary thing to do, not an HBM peculiarity.
        Map<String, Set<String>> tabLabelsByNamespace = new TreeMap<String, Set<String>>();
        tallyTabLabelsByNamespace(tabLabelsByNamespace, blocks);
        tallyTabLabelsByNamespace(tabLabelsByNamespace, items);
        Set<String> knownLabels = new java.util.HashSet<String>();
        for (int i = 0; i < tabs.size(); i++) {
            knownLabels.add(tabs.get(i).label());
        }
        for (Set<String> labels : tabLabelsByNamespace.values()) {
            labels.retainAll(knownLabels);
        }

        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("blocksTotal", blocks.size());
        tallyByNamespace(counts, "blocksByNs", namespacesOf(blocks));
        counts.put("itemsTotal", items.size());
        tallyByNamespace(counts, "itemsByNs", namespacesOf(items));
        counts.put("tabsTotal", tabs.size());
        tallyByKeys(counts, "tabsByPackage", tabPackagePrefixes(tabs));
        for (Map.Entry<String, Set<String>> e : tabLabelsByNamespace.entrySet()) {
            counts.put("tabsHosting:" + e.getKey(), e.getValue().size());
        }
        counts.put("tileEntitiesTotal", tes.size());
        tallyByKeys(counts, "tileEntitiesByPackage", tePackagePrefixes(tes));
        counts.put("oreDictTotal", ores.size());
        tallyOresByNamespace(counts, ores);
        counts.put("fluidsTotal", fluids.size());
        tallyByKeys(counts, "fluidsByPackage", fluidPackagePrefixes(fluids));
        counts.put("entitiesTotal", entities.size());
        tallyEntitiesByModOrPackage(counts, entities);
        counts.put("modsLoaded", mods.size());

        return new RegistrySnapshot("1.7.10", ForgeVersion.getVersion(),
                Loader.instance().getFMLVersionString(), side,
                mods, blocks, items, tabs, tes, ores, fluids, entities, counts);
    }

    // GENERALITY
    //
    // Everything below replaces what used to be a family of countHbm*(...) helpers, each testing
    // exactly one hardcoded namespace/class-prefix literal ("hbm:"/"com.hbm."/"api.hbm.") and
    // folding the result into a single counter that was permanently 0 for any other mod. Every
    // helper here instead tallies by whatever identity the DATA itself actually carries (registry
    // namespace where one exists, live modid where FML tracks one, or a disclosed best-effort
    // package-prefix guess where 1.7.10 genuinely has no per-entry identity at all - see
    // packagePrefixOf's own javadoc) - the resulting counts map has one key per namespace/prefix
    // ACTUALLY PRESENT in the data, so a single-mod run still reads exactly like a single "total"
    // plus one per-identity bucket (e.g. "blocksByNs:hbm"), just never hardcoded to that one name.

    private static void tallyByNamespace(Map<String, Integer> counts, String category, List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            bump(counts, category + ":" + (keys.get(i) == null ? "unknown" : keys.get(i)));
        }
    }

    private static void tallyByKeys(Map<String, Integer> counts, String category, List<String> keys) {
        tallyByNamespace(counts, category, keys);
    }

    private static void bump(Map<String, Integer> counts, String key) {
        Integer cur = counts.get(key);
        counts.put(key, (cur == null ? 0 : cur.intValue()) + 1);
    }

    private static List<String> namespacesOf(List<NamedEntry> l) {
        List<String> out = new ArrayList<String>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(l.get(i).namespace());
        }
        return out;
    }

    private static List<String> tabPackagePrefixes(List<TabEntry> l) {
        List<String> out = new ArrayList<String>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(l.get(i).packagePrefix());
        }
        return out;
    }

    private static List<String> tePackagePrefixes(List<TileEntityEntry> l) {
        List<String> out = new ArrayList<String>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(l.get(i).packagePrefix());
        }
        return out;
    }

    private static List<String> fluidPackagePrefixes(List<FluidEntry> l) {
        List<String> out = new ArrayList<String>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(packagePrefixOf(l.get(i).className()));
        }
        return out;
    }

    private static void tallyTabLabelsByNamespace(Map<String, Set<String>> byNamespace, List<NamedEntry> l) {
        for (int i = 0; i < l.size(); i++) {
            String ns = l.get(i).namespace();
            String tab = l.get(i).creativeTab();
            if (ns == null || tab == null) {
                continue;
            }
            Set<String> labels = byNamespace.get(ns);
            if (labels == null) {
                labels = new java.util.TreeSet<String>();
                byNamespace.put(ns, labels);
            }
            labels.add(tab);
        }
    }

    /**
     * Tallies, per namespace, how many ore-dictionary NAMES have at least one registered stack in
     * that namespace - reproducing the original "count of ore names associated with hbm" metric
     * generically, by actually resolving each ore entry's stacks rather than assuming one mod.
     */
    private static void tallyOresByNamespace(Map<String, Integer> counts, List<String> ores) {
        for (int i = 0; i < ores.size(); i++) {
            String ore = ores.get(i);
            Set<String> namespaces = new java.util.HashSet<String>();
            try {
                Iterator<?> it = OreDictionary.getOres(ore).iterator();
                while (it.hasNext()) {
                    Object stack = it.next();
                    Item item = itemOf(stack);
                    if (item != null) {
                        String name = GameData.getItemRegistry().func_148750_c(item);
                        if (name != null) {
                            int colon = name.indexOf(':');
                            namespaces.add(colon < 0 ? "unknown" : name.substring(0, colon));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // a broken ore entry must not sink the snapshot
            }
            for (String ns : namespaces) {
                bump(counts, "oreDictByNs:" + ns);
            }
        }
    }

    private static Item itemOf(Object stack) {
        if (stack instanceof net.minecraft.item.ItemStack) {
            // ItemStack.getItem()
            return ((net.minecraft.item.ItemStack) stack).func_77973_b();
        }
        return null;
    }

    /**
     * The live FML mod-entity table: {@code EntityRegistry.entityRegistrations}, a private
     * ListMultimap<ModContainer, EntityRegistration>. There is no public accessor for the whole
     * table in 1.7.10, only per-class lookups, so it is read reflectively; every field of each
     * EntityRegistration is then read through its public getters.
     */
    private static List<EntityEntry> entities() {
        List<EntityEntry> out = new ArrayList<EntityEntry>();
        try {
            Object reg = EntityRegistry.instance();
            Object multimap = Statics.getInstance(reg, EntityRegistry.class, "entityRegistrations");
            if (multimap == null) {
                return out;
            }
            java.lang.reflect.Method entries = multimap.getClass().getMethod("entries");
            entries.setAccessible(true);
            java.util.Collection<?> coll = (java.util.Collection<?>) entries.invoke(multimap);
            for (Object e : coll) {
                java.lang.reflect.Method getKey = e.getClass().getMethod("getKey");
                java.lang.reflect.Method getValue = e.getClass().getMethod("getValue");
                getKey.setAccessible(true);
                getValue.setAccessible(true);
                ModContainer mc = (ModContainer) getKey.invoke(e);
                EntityRegistry.EntityRegistration er = (EntityRegistry.EntityRegistration) getValue.invoke(e);
                out.add(new EntityEntry(mc == null ? null : mc.getModId(), er.getEntityName(),
                        er.getEntityClass() == null ? null : er.getEntityClass().getName(),
                        er.getModEntityId(), er.getTrackingRange(), er.getUpdateFrequency(),
                        er.sendsVelocityUpdates()));
            }
        } catch (Throwable t) {
            // a missing entity table must not sink the whole snapshot
        }
        return out;
    }

    /** Prefer the live FML modid (a real registered identity); fall back to the package-prefix
     *  guess only when FML's own entity table left it null (an already-existing edge case, not
     *  introduced by this fix - the original code OR'd the two together for the same reason). */
    private static void tallyEntitiesByModOrPackage(Map<String, Integer> counts, List<EntityEntry> l) {
        for (int i = 0; i < l.size(); i++) {
            String modid = l.get(i).modid();
            String key = (modid != null && !modid.isEmpty()) ? modid : packagePrefixOf(l.get(i).className());
            bump(counts, "entitiesByModOrPackage:" + (key == null ? "unknown" : key));
        }
    }

    /**
     * Best-effort mod-identity guess from a class's OWN package - the second dot-separated
     * segment, e.g. {@code com.hbm.foo.Bar} / {@code api.hbm.Baz} -&gt; {@code "hbm"}. This is a
     * heuristic, not a registry lookup: Forge 1.7.10 creative-tab/tile-entity/fluid registration
     * carries no per-entry modid anywhere (unlike blocks/items, which DO get a real namespaced
     * registry name - see {@link NamedEntry#namespace()}), and there is no public, generic
     * "which mod owns this class" table to query without walking FML's per-classloader ownership
     * internals. {@code com.<modid>.*}/{@code api.<modid>.*} happens to be HBM's own convention
     * (and a fairly common Forge 1.7.10 one), but this is disclosed as a guess in every count key
     * it feeds ({@code *ByPackage}), never presented as an authoritative modid the way
     * {@link EntityEntry#modid()} is.
     */
    private static String packagePrefixOf(String cls) {
        if (cls == null) {
            return null;
        }
        int firstDot = cls.indexOf('.');
        if (firstDot < 0) {
            return null;
        }
        int secondDot = cls.indexOf('.', firstDot + 1);
        return secondDot < 0 ? null : cls.substring(firstDot + 1, secondDot);
    }

    /** Forge's binpatched Block API exposes this hook, but the unpatched compile jar may not. */
    private static boolean blockHasTileEntity(Block block) {
        try {
            java.lang.reflect.Method m = Block.class.getMethod("hasTileEntity", int.class);
            return Boolean.TRUE.equals(m.invoke(block, Integer.valueOf(0)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String tabLabel(CreativeTabs t) {
        return t == null ? null : t.func_78013_b();
    }

    private static CreativeTabs safeBlockTab(Block b) {
        try {
            return b.func_149708_J();
        } catch (Throwable t) {
            return null;
        }
    }

    private static CreativeTabs safeItemTab(Item i) {
        try {
            return i.func_77640_w();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeUnlocalized(Block b) {
        try {
            return b.func_149739_a();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeUnlocalized(Item i) {
        try {
            return i.func_77658_a();
        } catch (Throwable t) {
            return null;
        }
    }
}
