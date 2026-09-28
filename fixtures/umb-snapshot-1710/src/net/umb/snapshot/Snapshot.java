// UMB Snapshot — registry + icon snapshot of a running 1.7.10 Forge client.
// SPDX-License-Identifier: CC0-1.0
package net.umb.snapshot;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.RegistryNamespaced;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Walks the fully-initialised client registries and writes one deterministic
 * JSON document. Runs on the client thread once the main menu is up, so the
 * texture atlas is stitched and every IIcon has its final name.
 *
 * Every per-object read is individually guarded: a single hostile block or item
 * records an "error" string in its own entry instead of aborting the dump.
 */
public final class Snapshot {

    // UNIVERSALITY fix (harness-purge, audit findings 1+2): these used to be hardcoded literals,
    // which meant isModId/isModClass below - and everything they gate - answered "is this HBM's own
    // content", not "is this the target mod's own content", for every mod this fixture is ever run
    // against. They are now read from system properties the launcher already has the convention for
    // (umbsnap.out/umbsnap.exit/umbsnap.delay/umbsnap.icons - see UmbSnapshotMod's javadoc), so the
    // identity is DERIVED from what the caller is actually launching rather than baked in here.
    // Defaults are kept as the literal HBM values so that any invocation which omits the new
    // properties - including every existing recorded run - reproduces today's output byte-for-byte.
    private static final String MOD_PREFIX = System.getProperty("umbsnap.modPrefix", "hbm:");
    private static final String MOD_PACKAGE = System.getProperty("umbsnap.modPackage", "com.hbm");
    private static final int COLLECT_CAP = 4096;
    // Item.getSubItems is the authoritative 1.7.10 metadata inventory.  A fixed 64-entry
    // ceiling silently discarded real late-enum variants (HBM ammo_standard.g40_he is one),
    // leaving the bridge with no host id to register.  COLLECT_CAP is already the defensive
    // bound for extractor output; use it here so every collected variant survives while the
    // existing truncation marker still protects against pathological mods.
    private static final int SUBITEM_CAP = COLLECT_CAP;
    private static final int SUBBLOCK_CAP = 256;

    private Snapshot() {
    }

    // ---------------------------------------------------------------- helpers

    /** ArrayList that silently stops growing — a hostile getSubItems cannot OOM the dump. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class BoundedList extends ArrayList {
        private final int cap;

        BoundedList(int cap) {
            this.cap = cap;
        }

        @Override
        public boolean add(Object o) {
            if (size() >= cap) {
                return false;
            }
            return super.add(o);
        }
    }

    private static String tr(String key) {
        try {
            return StatCollector.func_74838_a(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Every icon name this snapshot referenced, in first-seen order. EVERY icon name that
     * reaches the JSON passes through {@link #iconName}, so this set is exactly the set of
     * sprite names lane A3's {@link IconDump} has to capture from the stitched atlas.
     * Collecting here rather than re-walking the registries keeps the two in lockstep.
     */
    static final LinkedHashSet<String> REFERENCED_ICONS = new LinkedHashSet<String>();

    private static String iconName(IIcon icon) {
        if (icon == null) {
            return null;
        }
        try {
            String n = icon.func_94215_i();
            if (n != null && n.length() > 0) {
                REFERENCED_ICONS.add(n);
            }
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isModId(String id) {
        // MOD_PREFIX.length() > 0 guard: an empty prefix must not make startsWith("") vacuously
        // true for every id - a caller that explicitly sets umbsnap.modPrefix="" wants "no mod
        // identity available", not "everything is the mod".
        //
        // Case-INSENSITIVE on purpose, matching the same laneCasing/Bug-1 precedent already
        // established downstream in umb-hostagent's LegacySnapshot.matches(): 1.7.10 registry
        // namespaces are free-form (some mods, e.g. IronChest, register verbatim as "IronChest:"
        // rather than lowercased), and the harness derives MOD_PREFIX from whatever raw casing the
        // mod's own jar/mcmod.info happens to use. A case-sensitive comparison here would silently
        // reproduce this exact class of bug for any mod whose registered id casing does not happen
        // to match its own analyzed modid casing byte-for-byte.
        return id != null && MOD_PREFIX.length() > 0
                && id.regionMatches(true, 0, MOD_PREFIX, 0, MOD_PREFIX.length());
    }

    private static boolean isModClass(Class<?> c) {
        if (c == null || MOD_PACKAGE.length() == 0) {
            return false;
        }
        String name = c.getName();
        return name.regionMatches(true, 0, MOD_PACKAGE, 0, MOD_PACKAGE.length());
    }

    /**
     * GENERALITY fix (laneCasing, second-mod report finding 4 / Bug 2): every vanilla 1.7.10 tile
     * entity class lives under this package - a fixed, version-generic fact, true for ANY mod's
     * client, not something specific to the mod under test. Used to decide which entries of
     * {@code TileEntity.field_145855_i} (nameToClassMap) are "somebody's content" versus vanilla,
     * WITHOUT hardcoding which somebody. The old check here was {@code cls.startsWith("com.hbm") ||
     * name.contains("hbm")} - literally HBM-only, so it silently dropped every other mod's tile
     * entities (confirmed: Iron Chests registers 8 TEs as "IronChest.WOOD" etc. under
     * cpw.mods.ironchest, matching neither clause, so 0/8 ever reached the JSON despite correct,
     * exception-free registration calls in the bytecode).
     */
    private static final String VANILLA_PACKAGE = "net.minecraft.";

    private static boolean isVanillaTileEntityClass(String className) {
        return className != null && className.startsWith(VANILLA_PACKAGE);
    }

    // ------------------------------------------------------------------ entry

    public static void dump(File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        RegistryNamespaced blockReg =
                (RegistryNamespaced) Refl.get(Block.class, null, "field_149771_c", "blockRegistry");
        RegistryNamespaced itemReg =
                (RegistryNamespaced) Refl.get(Item.class, null, "field_150901_e", "itemRegistry");

        // Deterministic ordering: id -> object, sorted lexicographically.
        TreeMap<String, Block> blocks = new TreeMap<String, Block>();
        TreeMap<String, Item> items = new TreeMap<String, Item>();

        for (Iterator<?> it = blockReg.iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof Block)) {
                continue;
            }
            String id;
            try {
                id = blockReg.func_148750_c(o);
            } catch (Throwable t) {
                id = "?unnamed?" + System.identityHashCode(o);
            }
            if (id == null) {
                id = "?null?" + System.identityHashCode(o);
            }
            blocks.put(id, (Block) o);
        }
        for (Iterator<?> it = itemReg.iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof Item)) {
                continue;
            }
            String id;
            try {
                id = itemReg.func_148750_c(o);
            } catch (Throwable t) {
                id = "?unnamed?" + System.identityHashCode(o);
            }
            if (id == null) {
                id = "?null?" + System.identityHashCode(o);
            }
            items.put(id, (Item) o);
        }

        // Pre-pass: which creative tabs are actually used by mod content? The tab
        // classes themselves are frequently anonymous vanilla CreativeTabs
        // subclasses declared inside the mod, so ownership is decided by
        // (declaring package) OR (at least one mod block/item lives on the tab).
        Set<CreativeTabs> modTabs =
                Collections.newSetFromMap(new IdentityHashMap<CreativeTabs, Boolean>());
        for (Map.Entry<String, Block> e : blocks.entrySet()) {
            if (!isModId(e.getKey())) {
                continue;
            }
            try {
                CreativeTabs t = e.getValue().func_149708_J();
                if (t != null) {
                    modTabs.add(t);
                }
            } catch (Throwable ignored) {
                // no tab recorded for this block
            }
        }
        for (Map.Entry<String, Item> e : items.entrySet()) {
            if (!isModId(e.getKey())) {
                continue;
            }
            try {
                CreativeTabs t = e.getValue().func_77640_w();
                if (t != null) {
                    modTabs.add(t);
                }
            } catch (Throwable ignored) {
                // no tab recorded for this item
            }
        }

        // Forge-added Block members (absent from the unpatched compile-time jar).
        Method mHasTile = Refl.findMethod(Block.class, new Class<?>[]{int.class}, "hasTileEntity");
        Method mCreateTile =
                Refl.findMethod(Block.class, new Class<?>[]{World.class, int.class}, "createTileEntity");
        Method mHarvestTool = Refl.findMethod(Block.class, new Class<?>[]{int.class}, "getHarvestTool");
        Method mHarvestLevel = Refl.findMethod(Block.class, new Class<?>[]{int.class}, "getHarvestLevel");

        int blocksHbm = 0;
        int itemsHbm = 0;
        int iconRows = 0;
        int oreDictHbm = 0;
        int fluidCount = 0;
        int tabsHbm = 0;
        List<String> unpopulated = new ArrayList<String>();

        Writer raw = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        BufferedWriter bw = new BufferedWriter(raw, 1 << 16);
        Jw j = new Jw(bw);
        try {
            j.beginObject();

            // ------------------------------------------------------------ source
            j.name("source").beginObject();
            j.prop("snapshotMod", UmbSnapshotMod.MODID + " " + UmbSnapshotMod.VERSION);
            j.prop("mc", "1.7.10");
            String forgeVersion;
            try {
                forgeVersion = ForgeVersion.getVersion();
            } catch (Throwable t) {
                forgeVersion = "?" + Refl.describe(t);
            }
            j.prop("forge", forgeVersion);
            try {
                j.prop("fml", Loader.instance().getFMLVersionString());
            } catch (Throwable t) {
                j.prop("fml", null);
            }
            j.prop("dumpedAtUtc", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .format(new java.util.Date()));
            j.name("mods").beginArray();
            try {
                TreeMap<String, ModContainer> mods = new TreeMap<String, ModContainer>();
                for (ModContainer mc : Loader.instance().getModList()) {
                    mods.put(mc.getModId(), mc);
                }
                for (Map.Entry<String, ModContainer> e : mods.entrySet()) {
                    ModContainer mc = e.getValue();
                    j.beginObject();
                    j.prop("modid", mc.getModId());
                    j.prop("name", mc.getName());
                    j.prop("version", mc.getVersion());
                    File src = null;
                    try {
                        src = mc.getSource();
                    } catch (Throwable ignored) {
                        // source unavailable for injected containers
                    }
                    j.prop("sourceJarName", src == null ? null : src.getName());
                    j.endObject();
                }
            } catch (Throwable t) {
                j.beginObject().prop("error", Refl.describe(t)).endObject();
            }
            j.endArray();
            j.endObject();

            // ------------------------------------------------------ creativeTabs
            j.name("creativeTabs").beginArray();
            CreativeTabs[] tabs;
            try {
                tabs = (CreativeTabs[]) Refl.get(CreativeTabs.class, null, "field_78032_a", "creativeTabArray");
            } catch (Throwable t) {
                tabs = new CreativeTabs[0];
                unpopulated.add("creativeTabs: " + Refl.describe(t));
            }
            int tabsTotal = 0;
            for (int i = 0; i < tabs.length; i++) {
                CreativeTabs t = tabs[i];
                if (t == null) {
                    continue;
                }
                tabsTotal++;
                boolean hbm = isModClass(t.getClass()) || modTabs.contains(t);
                if (hbm) {
                    tabsHbm++;
                }
                j.beginObject();
                StringBuilder err = new StringBuilder();
                try {
                    j.prop("index", t.func_78021_a());
                } catch (Throwable e) {
                    j.propNull("index");
                    err.append("index=").append(Refl.describe(e)).append("; ");
                }
                String label = null;
                try {
                    label = t.func_78013_b();
                } catch (Throwable e) {
                    err.append("label=").append(Refl.describe(e)).append("; ");
                }
                j.prop("label", label);
                j.prop("className", t.getClass().getName());
                try {
                    j.prop("translatedLabel", t.func_78024_c());
                } catch (Throwable e) {
                    j.prop("translatedLabel", label == null ? null : tr("itemGroup." + label));
                }
                String iconItemId = null;
                try {
                    Item ico = t.func_78016_d();
                    if (ico != null) {
                        iconItemId = itemReg.func_148750_c(ico);
                    }
                } catch (Throwable e) {
                    err.append("iconItem=").append(Refl.describe(e)).append("; ");
                }
                j.prop("iconItemId", iconItemId);
                try {
                    j.prop("iconMeta", t.func_151243_f());
                } catch (Throwable e) {
                    j.propNull("iconMeta");
                }
                String icoName = null;
                try {
                    ItemStack st = t.func_151244_d();
                    if (st != null) {
                        icoName = iconName(st.func_77954_c());
                    }
                } catch (Throwable e) {
                    err.append("iconName=").append(Refl.describe(e)).append("; ");
                }
                j.prop("iconName", icoName);
                j.prop("hbm", hbm);
                if (err.length() > 0) {
                    j.prop("error", err.toString());
                }
                j.endObject();
            }
            j.endArray();

            // ------------------------------------------------------------ blocks
            j.name("blocks").beginArray();
            for (Map.Entry<String, Block> e : blocks.entrySet()) {
                String id = e.getKey();
                Block b = e.getValue();
                boolean hbm = isModId(id);
                if (hbm) {
                    blocksHbm++;
                }
                StringBuilder err = new StringBuilder();
                j.beginObject();
                j.prop("id", id);
                try {
                    j.prop("numericId", blockReg.func_148757_b(b));
                } catch (Throwable x) {
                    j.propNull("numericId");
                }
                j.prop("className", b.getClass().getName());
                String unloc = null;
                try {
                    unloc = b.func_149739_a();
                } catch (Throwable x) {
                    err.append("unlocalizedName=").append(Refl.describe(x)).append("; ");
                }
                j.prop("unlocalizedName", unloc);
                j.prop("displayName", unloc == null ? null : tr(unloc + ".name"));
                try {
                    Object mat = b.func_149688_o();
                    j.prop("material", mat == null ? null : mat.getClass().getName());
                } catch (Throwable x) {
                    j.propNull("material");
                    err.append("material=").append(Refl.describe(x)).append("; ");
                }
                try {
                    Object mc = b.func_149728_f(0);
                    Object idx = mc == null ? null : Refl.getOrNull(mc.getClass(), mc, "field_76290_q", "colorIndex");
                    if (idx instanceof Integer) {
                        j.prop("mapColor", ((Integer) idx).intValue());
                    } else {
                        j.propNull("mapColor");
                    }
                } catch (Throwable x) {
                    j.propNull("mapColor");
                }
                emitFloat(j, "hardness", Refl.getOrNull(Block.class, b, "field_149782_v", "blockHardness"));
                emitFloat(j, "resistance", Refl.getOrNull(Block.class, b, "field_149781_w", "blockResistance"));
                try {
                    j.prop("lightValue", b.func_149750_m());
                } catch (Throwable x) {
                    j.propNull("lightValue");
                }
                try {
                    j.prop("lightOpacity", b.func_149717_k());
                } catch (Throwable x) {
                    j.propNull("lightOpacity");
                }
                try {
                    j.prop("opaqueCube", b.func_149662_c());
                } catch (Throwable x) {
                    j.propNull("opaqueCube");
                }
                try {
                    j.prop("renderAsNormalBlock", b.func_149686_d());
                } catch (Throwable x) {
                    j.propNull("renderAsNormalBlock");
                }
                try {
                    j.prop("renderType", b.func_149645_b());
                } catch (Throwable x) {
                    j.propNull("renderType");
                }
                // func_149653_t = getTickRandomly (methods.csv): whether 1.7.10 gives this block
                // random updateTick calls - the host needs it to opt the twin into 26.2's own
                // Properties.randomTicks() so fluids/fire/gas actually receive ticks.
                try {
                    j.prop("tickRandomly", b.func_149653_t());
                } catch (Throwable x) {
                    j.propNull("tickRandomly");
                }
                try {
                    CreativeTabs t = b.func_149708_J();
                    j.prop("creativeTab", t == null ? null : t.func_78013_b());
                } catch (Throwable x) {
                    j.propNull("creativeTab");
                }
                boolean hasTile = false;
                try {
                    Object r = Refl.call(mHasTile, b, Integer.valueOf(0));
                    hasTile = Boolean.TRUE.equals(r);
                    j.prop("hasTileEntity", hasTile);
                } catch (Throwable x) {
                    j.propNull("hasTileEntity");
                    err.append("hasTileEntity=").append(Refl.describe(x)).append("; ");
                }
                if (hasTile) {
                    try {
                        Object te = Refl.call(mCreateTile, b, null, Integer.valueOf(0));
                        j.prop("tileEntityClass", te == null ? null : te.getClass().getName());
                    } catch (Throwable x) {
                        j.propNull("tileEntityClass");
                        err.append("tileEntityClass=").append(Refl.describe(x)).append("; ");
                    }
                } else {
                    j.propNull("tileEntityClass");
                }
                try {
                    Object ht = Refl.call(mHarvestTool, b, Integer.valueOf(0));
                    j.prop("harvestTool", ht == null ? null : String.valueOf(ht));
                } catch (Throwable x) {
                    j.propNull("harvestTool");
                }
                try {
                    Object hl = Refl.call(mHarvestLevel, b, Integer.valueOf(0));
                    if (hl instanceof Integer) {
                        j.prop("harvestLevel", ((Integer) hl).intValue());
                    } else {
                        j.propNull("harvestLevel");
                    }
                } catch (Throwable x) {
                    j.propNull("harvestLevel");
                }
                try {
                    Object st = Refl.getOrNull(Block.class, b, "field_149762_H", "stepSound");
                    Object sn = st == null ? null : Refl.getOrNull(st.getClass(), st, "field_150501_a", "soundName");
                    j.prop("stepSound", sn == null ? null : String.valueOf(sn));
                } catch (Throwable x) {
                    j.propNull("stepSound");
                }
                emitFloat(j, "slipperiness", Refl.getOrNull(Block.class, b, "field_149765_K", "slipperiness"));
                Object tex = Refl.getOrNull(Block.class, b, "field_149768_d", "textureName");
                j.prop("textureName", tex == null ? null : String.valueOf(tex));

                // icons: 16 metas x 6 sides, identical rows collapsed
                j.name("icons").beginArray();
                LinkedHashSet<String> seen = new LinkedHashSet<String>();
                List<String[]> rows = new ArrayList<String[]>();
                List<List<Integer>> rowMetas = new ArrayList<List<Integer>>();
                List<Integer> rowFirstMeta = new ArrayList<Integer>();
                for (int meta = 0; meta < 16; meta++) {
                    String[] sides = new String[6];
                    for (int side = 0; side < 6; side++) {
                        try {
                            sides[side] = iconName(b.func_149691_a(side, meta));
                        } catch (Throwable x) {
                            sides[side] = null;
                        }
                    }
                    StringBuilder sig = new StringBuilder();
                    for (int s = 0; s < 6; s++) {
                        sig.append(sides[s]).append((char) 1);
                    }
                    String key = sig.toString();
                    int idx = indexOfKey(seen, key);
                    if (idx < 0) {
                        seen.add(key);
                        rows.add(sides);
                        rowFirstMeta.add(Integer.valueOf(meta));
                        List<Integer> ms = new ArrayList<Integer>();
                        ms.add(Integer.valueOf(meta));
                        rowMetas.add(ms);
                    } else {
                        rowMetas.get(idx).add(Integer.valueOf(meta));
                    }
                }
                for (int r = 0; r < rows.size(); r++) {
                    iconRows++;
                    j.beginObject();
                    j.prop("meta", rowFirstMeta.get(r).intValue());
                    j.name("metas").beginArray();
                    for (Integer m : rowMetas.get(r)) {
                        j.value(m.intValue());
                    }
                    j.endArray();
                    j.name("sides").beginArray();
                    String[] sides = rows.get(r);
                    for (int s = 0; s < 6; s++) {
                        j.value(sides[s]);
                    }
                    j.endArray();
                    j.endObject();
                }
                j.endArray();

                // itemBlock + subBlocks
                Item ib = null;
                try {
                    ib = Item.func_150898_a(b);
                } catch (Throwable x) {
                    err.append("itemFromBlock=").append(Refl.describe(x)).append("; ");
                }
                j.prop("itemBlockClass", ib == null ? null : ib.getClass().getName());
                j.name("subBlocks").beginArray();
                if (ib != null) {
                    try {
                        BoundedList list = new BoundedList(COLLECT_CAP);
                        CreativeTabs tab = null;
                        try {
                            tab = ib.func_77640_w();
                        } catch (Throwable ignored) {
                            // some items have no tab
                        }
                        ib.func_150895_a(ib, tab, list);
                        int n = Math.min(list.size(), SUBBLOCK_CAP);
                        for (int k = 0; k < n; k++) {
                            Object o = list.get(k);
                            if (!(o instanceof ItemStack)) {
                                continue;
                            }
                            ItemStack st = (ItemStack) o;
                            j.beginObject();
                            try {
                                j.prop("meta", st.func_77960_j());
                            } catch (Throwable x) {
                                j.propNull("meta");
                            }
                            try {
                                j.prop("unlocalizedName", st.func_77977_a());
                            } catch (Throwable x) {
                                j.propNull("unlocalizedName");
                            }
                            try {
                                j.prop("displayName", st.func_82833_r());
                            } catch (Throwable x) {
                                j.propNull("displayName");
                            }
                            j.endObject();
                        }
                        if (list.size() > SUBBLOCK_CAP) {
                            j.beginObject().prop("truncated", true).prop("total", list.size()).endObject();
                        }
                    } catch (Throwable x) {
                        j.beginObject().prop("error", Refl.describe(x)).endObject();
                    }
                }
                j.endArray();
                if (err.length() > 0) {
                    j.prop("error", err.toString());
                }
                j.endObject();
            }
            j.endArray();

            // ------------------------------------------------------------- items
            j.name("items").beginArray();
            for (Map.Entry<String, Item> e : items.entrySet()) {
                String id = e.getKey();
                Item it = e.getValue();
                if (isModId(id)) {
                    itemsHbm++;
                }
                StringBuilder err = new StringBuilder();
                j.beginObject();
                j.prop("id", id);
                try {
                    j.prop("numericId", itemReg.func_148757_b(it));
                } catch (Throwable x) {
                    j.propNull("numericId");
                }
                j.prop("className", it.getClass().getName());
                String unloc = null;
                try {
                    unloc = it.func_77658_a();
                } catch (Throwable x) {
                    err.append("unlocalizedName=").append(Refl.describe(x)).append("; ");
                }
                j.prop("unlocalizedName", unloc);
                j.prop("displayName", unloc == null ? null : tr(unloc + ".name"));
                try {
                    j.prop("maxStackSize", it.func_77639_j());
                } catch (Throwable x) {
                    j.propNull("maxStackSize");
                }
                try {
                    j.prop("maxDamage", it.func_77612_l());
                } catch (Throwable x) {
                    j.propNull("maxDamage");
                }
                try {
                    j.prop("hasSubtypes", it.func_77614_k());
                } catch (Throwable x) {
                    j.propNull("hasSubtypes");
                }
                try {
                    CreativeTabs t = it.func_77640_w();
                    j.prop("creativeTab", t == null ? null : t.func_78013_b());
                } catch (Throwable x) {
                    j.propNull("creativeTab");
                }
                Object tex = Refl.getOrNull(Item.class, it, "field_111218_cA", "iconString");
                j.prop("textureName", tex == null ? null : String.valueOf(tex));
                try {
                    j.prop("iconName", iconName(it.func_77617_a(0)));
                } catch (Throwable x) {
                    j.propNull("iconName");
                }
                boolean isBlockItem = it instanceof ItemBlock;
                if (isBlockItem) {
                    String blockId = null;
                    try {
                        Block b = Block.func_149634_a(it);
                        if (b != null) {
                            blockId = blockReg.func_148750_c(b);
                        }
                    } catch (Throwable x) {
                        err.append("isBlockItem=").append(Refl.describe(x)).append("; ");
                    }
                    j.prop("isBlockItem", blockId);
                } else {
                    j.propNull("isBlockItem");
                }
                try {
                    j.prop("isFood", it instanceof ItemFood);
                } catch (Throwable x) {
                    j.propNull("isFood");
                }
                j.name("subItems").beginArray();
                int total = -1;
                try {
                    BoundedList list = new BoundedList(COLLECT_CAP);
                    CreativeTabs tab = null;
                    try {
                        tab = it.func_77640_w();
                    } catch (Throwable ignored) {
                        // no tab
                    }
                    it.func_150895_a(it, tab, list);
                    total = list.size();
                    int n = Math.min(total, SUBITEM_CAP);
                    for (int k = 0; k < n; k++) {
                        Object o = list.get(k);
                        if (!(o instanceof ItemStack)) {
                            continue;
                        }
                        ItemStack st = (ItemStack) o;
                        j.beginObject();
                        int dmg = 0;
                        try {
                            dmg = st.func_77960_j();
                            j.prop("damage", dmg);
                        } catch (Throwable x) {
                            j.propNull("damage");
                        }
                        try {
                            j.prop("unlocalizedName", st.func_77977_a());
                        } catch (Throwable x) {
                            j.propNull("unlocalizedName");
                        }
                        try {
                            j.prop("displayName", st.func_82833_r());
                        } catch (Throwable x) {
                            j.propNull("displayName");
                        }
                        String sIcon = null;
                        try {
                            sIcon = iconName(it.func_77650_f(st));
                        } catch (Throwable x) {
                            sIcon = null;
                        }
                        if (sIcon == null) {
                            try {
                                sIcon = iconName(it.func_77617_a(dmg));
                            } catch (Throwable x) {
                                sIcon = null;
                            }
                        }
                        j.prop("iconName", sIcon);
                        j.endObject();
                    }
                } catch (Throwable x) {
                    j.beginObject().prop("error", Refl.describe(x)).endObject();
                }
                j.endArray();
                if (total > SUBITEM_CAP) {
                    j.prop("truncated", true);
                    j.prop("subItemsTotal", total);
                } else {
                    j.prop("truncated", false);
                }
                if (err.length() > 0) {
                    j.prop("error", err.toString());
                }
                j.endObject();
            }
            j.endArray();

            // ------------------------------------------------------ tileEntities
            j.name("tileEntities").beginArray();
            try {
                Class<?> teClass = Class.forName("net.minecraft.tileentity.TileEntity");
                Object m = Refl.get(teClass, null, "field_145855_i", "nameToClassMap");
                @SuppressWarnings("unchecked")
                Map<Object, Object> map = (Map<Object, Object>) m;
                TreeMap<String, String> sorted = new TreeMap<String, String>();
                for (Map.Entry<Object, Object> en : map.entrySet()) {
                    String name = String.valueOf(en.getKey());
                    Object v = en.getValue();
                    String cls = (v instanceof Class) ? ((Class<?>) v).getName() : String.valueOf(v);
                    // ANY non-vanilla tile entity, from ANY mod that happens to be loaded - not just
                    // the one this snapshot targets. Downstream consumers already filter by their
                    // own id/namespace where that matters (see LegacySnapshot.matches); this array
                    // exists to answer "what got registered", so it should hold everything that did.
                    if (!isVanillaTileEntityClass(cls)) {
                        sorted.put(name, cls);
                    }
                }
                for (Map.Entry<String, String> en : sorted.entrySet()) {
                    j.beginObject().prop("name", en.getKey()).prop("className", en.getValue()).endObject();
                }
            } catch (Throwable t) {
                j.beginObject().prop("error", Refl.describe(t)).endObject();
                unpopulated.add("tileEntities: " + Refl.describe(t));
            }
            j.endArray();

            // ---------------------------------------------------------- entities
            j.name("entities").beginArray();
            try {
                Class<?> erClass = Class.forName("cpw.mods.fml.common.registry.EntityRegistry");
                Object er = erClass.getMethod("instance").invoke(null);
                Object multimap = Refl.get(erClass, er, "entityRegistrations");
                Object entries = multimap.getClass().getMethod("entries").invoke(multimap);
                TreeMap<String, String[]> sorted = new TreeMap<String, String[]>();
                for (Object o : (Collection<?>) entries) {
                    Map.Entry<?, ?> en = (Map.Entry<?, ?>) o;
                    Object reg = en.getValue();
                    Class<?> rc = reg.getClass();
                    String name = String.valueOf(rc.getMethod("getEntityName").invoke(reg));
                    Object cls = rc.getMethod("getEntityClass").invoke(reg);
                    Object modEntityId = rc.getMethod("getModEntityId").invoke(reg);
                    Object cont = en.getKey();
                    String modid = String.valueOf(cont.getClass().getMethod("getModId").invoke(cont));
                    sorted.put(modid + "/" + name, new String[]{
                            modid, name,
                            (cls instanceof Class) ? ((Class<?>) cls).getName() : String.valueOf(cls),
                            String.valueOf(modEntityId)});
                }
                for (Map.Entry<String, String[]> en : sorted.entrySet()) {
                    String[] v = en.getValue();
                    j.beginObject()
                            .prop("modid", v[0])
                            .prop("name", v[1])
                            .prop("className", v[2])
                            .prop("modEntityId", v[3])
                            .endObject();
                }
            } catch (Throwable t) {
                j.beginObject().prop("error", Refl.describe(t)).endObject();
                unpopulated.add("entities: " + Refl.describe(t));
            }
            j.endArray();

            // ----------------------------------------------------------- oreDict
            j.name("oreDict").beginObject();
            try {
                String[] names = OreDictionary.getOreNames();
                java.util.Arrays.sort(names);
                for (String n : names) {
                    if (n == null) {
                        continue;
                    }
                    List<String> entries = new ArrayList<String>();
                    boolean anyMod = false;
                    try {
                        List<?> ores = OreDictionary.getOres(n);
                        for (Object o : ores) {
                            if (!(o instanceof ItemStack)) {
                                continue;
                            }
                            ItemStack st = (ItemStack) o;
                            String sid;
                            try {
                                Item si = st.func_77973_b();
                                sid = si == null ? "?null?" : itemReg.func_148750_c(si);
                            } catch (Throwable x) {
                                sid = "?err?";
                            }
                            if (isModId(sid)) {
                                anyMod = true;
                            }
                            int dmg;
                            try {
                                dmg = st.func_77960_j();
                            } catch (Throwable x) {
                                dmg = -1;
                            }
                            entries.add(sid + "@" + dmg);
                        }
                    } catch (Throwable x) {
                        entries.add("?error? " + Refl.describe(x));
                    }
                    // UNIVERSALITY fix (harness-purge, audit finding 1 - the worst one in this file):
                    // this used to be `if (!anyMod) continue;`, which discarded the ENTIRE oreDict
                    // entry - not merely a mis-count - unless at least one contributing item id
                    // matched MOD_PREFIX. For any run where the target mod's own items never
                    // co-register under the same ore name as an already-mod-tagged item (or simply
                    // for any other mod entirely), every oreDict entry vanished from the JSON with no
                    // error anywhere. `entries` is already the complete, correct "what does this ore
                    // name resolve to" answer; an empty list is the only legitimate reason to skip a
                    // name. `anyMod` is kept as a pure counter (oreDictHbm below), same as every other
                    // *Hbm counter in this file - it no longer gates output.
                    if (entries.isEmpty()) {
                        continue;
                    }
                    if (anyMod) {
                        oreDictHbm++;
                    }
                    Collections.sort(entries);
                    j.name(n).beginArray();
                    for (String s : entries) {
                        j.value(s);
                    }
                    j.endArray();
                }
            } catch (Throwable t) {
                j.prop("error", Refl.describe(t));
                unpopulated.add("oreDict: " + Refl.describe(t));
            }
            j.endObject();

            // ------------------------------------------------------------ fluids
            j.name("fluids").beginArray();
            try {
                Map<String, Fluid> fl = FluidRegistry.getRegisteredFluids();
                TreeMap<String, Fluid> sorted = new TreeMap<String, Fluid>(fl);
                Method mGetIcon = Refl.findMethod(Fluid.class, new Class<?>[0], "getIcon");
                Method mGetBlock = Refl.findMethod(Fluid.class, new Class<?>[0], "getBlock");
                for (Map.Entry<String, Fluid> en : sorted.entrySet()) {
                    fluidCount++;
                    Fluid f = en.getValue();
                    j.beginObject();
                    j.prop("name", en.getKey());
                    try {
                        j.prop("unlocalizedName", f.getUnlocalizedName());
                    } catch (Throwable x) {
                        j.propNull("unlocalizedName");
                    }
                    try {
                        j.prop("localizedName", f.getLocalizedName());
                    } catch (Throwable x) {
                        j.propNull("localizedName");
                    }
                    try {
                        j.prop("density", f.getDensity());
                    } catch (Throwable x) {
                        j.propNull("density");
                    }
                    try {
                        j.prop("temperature", f.getTemperature());
                    } catch (Throwable x) {
                        j.propNull("temperature");
                    }
                    try {
                        j.prop("viscosity", f.getViscosity());
                    } catch (Throwable x) {
                        j.propNull("viscosity");
                    }
                    try {
                        j.prop("luminosity", f.getLuminosity());
                    } catch (Throwable x) {
                        j.propNull("luminosity");
                    }
                    try {
                        j.prop("gaseous", f.isGaseous());
                    } catch (Throwable x) {
                        j.propNull("gaseous");
                    }
                    String fi = null;
                    try {
                        Object icon = Refl.call(mGetIcon, f);
                        fi = (icon instanceof IIcon) ? iconName((IIcon) icon) : null;
                    } catch (Throwable x) {
                        fi = null;
                    }
                    j.prop("iconName", fi);
                    String fb = null;
                    try {
                        Object blk = Refl.call(mGetBlock, f);
                        if (blk instanceof Block) {
                            fb = blockReg.func_148750_c(blk);
                        }
                    } catch (Throwable x) {
                        fb = null;
                    }
                    j.prop("blockId", fb);
                    j.prop("className", f.getClass().getName());
                    j.endObject();
                }
            } catch (Throwable t) {
                j.beginObject().prop("error", Refl.describe(t)).endObject();
                unpopulated.add("fluids: " + Refl.describe(t));
            }
            j.endArray();

            // ------------------------------------------------------------ recipes
            // Additive recipe census.  The extraction lane may add metadata fields around this
            // call; this method is deliberately self-contained and uses only the verified
            // CraftingManager names below.  Unknown recipe subclasses are counted, never guessed.
            writeRecipeSection(j, unpopulated);

            // ------------------------------------------------------------ counts
            j.name("counts").beginObject();
            j.prop("blocksTotal", blocks.size());
            j.prop("blocksHbm", blocksHbm);
            j.prop("itemsTotal", items.size());
            j.prop("itemsHbm", itemsHbm);
            j.prop("tabsTotal", tabsTotal);
            j.prop("tabsHbm", tabsHbm);
            j.prop("iconRowsBlocks", iconRows);
            j.prop("fluids", fluidCount);
            j.prop("oreDictHbm", oreDictHbm);
            j.endObject();

            j.name("unpopulated").beginArray();
            for (String s : unpopulated) {
                j.value(s);
            }
            j.endArray();

            j.endObject();
            j.finish();
        } finally {
            bw.flush();
            bw.close();
        }
    }

    /**
     * Serializes the 1.7.10 recipe data needed by the host.  The entry points and method names are
     * the SRG names in fml/conf/methods.csv: CraftingManager.func_77594_a/func_77592_b,
     * IRecipe.func_77571_b and FurnaceRecipes.func_77602_a.  The field names are the SRG names
     * in fields.csv: FurnaceRecipes.field_77604_b (smeltingList) and field_77605_c
     * (experienceList).  Recipe implementation fields are read by their deobfuscated names first
     * and their known SRG aliases second; an absent field makes that recipe an unknown-class record,
     * never a guessed recipe.
     */
    private static void writeRecipeSection(Jw j, List<String> unpopulated) throws Exception {
        j.name("recipes").beginObject();
        int crafting = 0, smelting = 0, unknown = 0, unresolved = 0;
        TreeMap<String, Integer> classes = new TreeMap<String, Integer>();
        final RegistryNamespaced recipeItemReg = (RegistryNamespaced) Refl.get(Item.class, null, "field_150901_e", "itemRegistry");
        try {
            final Class<?> cm = Class.forName("net.minecraft.item.crafting.CraftingManager");
            Object mgr = cm.getMethod("func_77594_a").invoke(null);
            Object list = cm.getMethod("func_77592_b").invoke(mgr);
            if (list instanceof Iterable<?>) {
                j.name("crafting").beginArray();
                for (Object r : (Iterable<?>) list) {
                    crafting++;
                    String cn = r == null ? "null" : r.getClass().getName();
                    boolean supported = r != null && (cn.equals("net.minecraft.item.crafting.ShapedRecipes")
                            || cn.equals("net.minecraft.item.crafting.ShapelessRecipes")
                            || cn.equals("net.minecraftforge.oredict.ShapedOreRecipe")
                            || cn.equals("net.minecraftforge.oredict.ShapelessOreRecipe"));
                    if (!supported) {
                        Integer n = classes.get(cn); classes.put(cn, n == null ? 1 : n + 1); unknown++; continue;
                    }
                    try {
                        j.beginObject().prop("className", cn);
                        boolean shaped = cn.indexOf("Shaped") >= 0;
                        j.prop("type", shaped ? "shaped" : "shapeless");
                        if (shaped) {
                            int w = -1, h = -1;
                            for (String fn : new String[]{"recipeWidth", "field_77576_b"}) try {
                                java.lang.reflect.Field f = r.getClass().getDeclaredField(fn); f.setAccessible(true); w = f.getInt(r); break;
                            } catch (NoSuchFieldException ignored) { }
                            for (String fn : new String[]{"recipeHeight", "field_77575_c"}) try {
                                java.lang.reflect.Field f = r.getClass().getDeclaredField(fn); f.setAccessible(true); h = f.getInt(r); break;
                            } catch (NoSuchFieldException ignored) { }
                            j.prop("width", w).prop("height", h);
                        }
                        j.name("items").beginArray();
                        Object ingredients = null;
                        for (String fn : new String[]{"recipeItems", "field_77577_b", "input"}) try {
                            java.lang.reflect.Field f = r.getClass().getDeclaredField(fn); f.setAccessible(true); ingredients = f.get(r); break;
                        } catch (NoSuchFieldException ignored) { }
                        if (ingredients instanceof Iterable<?>) for (Object in : (Iterable<?>) ingredients) {
                            if (in instanceof String) j.beginObject().prop("ore", (String) in).endObject();
                            else if (in == null) j.nul();
                            else if (in instanceof ItemStack) {
                                ItemStack st = (ItemStack) in;
                                j.beginObject().prop("item", recipeItemReg.func_148750_c(st.func_77973_b()))
                                        .prop("meta", st.func_77960_j()).prop("count", ((Number) Refl.get(ItemStack.class, st, "field_77994_a", "stackSize")).intValue()).endObject();
                            } else j.beginObject().prop("className", in.getClass().getName()).endObject();
                        }
                        j.endArray();
                        ItemStack out = (ItemStack) r.getClass().getMethod("func_77571_b").invoke(r);
                        if (out == null || out.func_77973_b() == null) { j.propNull("output"); unresolved++; }
                        else j.name("output").beginObject().prop("item", recipeItemReg.func_148750_c(out.func_77973_b()))
                                .prop("meta", out.func_77960_j()).prop("count", ((Number) Refl.get(ItemStack.class, out, "field_77994_a", "stackSize")).intValue()).endObject();
                        j.endObject();
                    } catch (Throwable bad) { j.endObject(); unresolved++; }
                }
                j.endArray();
            }
        } catch (Throwable t) { unpopulated.add("recipes.crafting: " + Refl.describe(t)); }
        try {
            Class<?> fr = Class.forName("net.minecraft.item.crafting.FurnaceRecipes");
            Object inst = fr.getMethod("func_77602_a").invoke(null);
            java.lang.reflect.Field sm = null, xp = null;
            for (String n : new String[]{"smeltingList", "field_77604_b"}) try { sm = fr.getDeclaredField(n); sm.setAccessible(true); break; } catch (NoSuchFieldException ignored) { }
            for (String n : new String[]{"experienceList", "field_77605_c"}) try { xp = fr.getDeclaredField(n); xp.setAccessible(true); break; } catch (NoSuchFieldException ignored) { }
            Object map = sm == null ? null : sm.get(inst), xmap = xp == null ? null : xp.get(inst);
            j.name("smelting").beginArray();
            if (map instanceof Map<?, ?>) for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                smelting++; j.beginObject();
                if (e.getKey() instanceof ItemStack) { ItemStack in = (ItemStack)e.getKey(); j.name("input").beginObject().prop("item", recipeItemReg.func_148750_c(in.func_77973_b())).prop("meta", in.func_77960_j()).endObject(); }
                Object val = e.getValue();
                if (val instanceof ItemStack) { ItemStack out = (ItemStack)val; j.name("output").beginObject().prop("item", recipeItemReg.func_148750_c(out.func_77973_b())).prop("meta", out.func_77960_j()).prop("count", ((Number) Refl.get(ItemStack.class, out, "field_77994_a", "stackSize")).intValue()).endObject(); }
                else { j.propNull("output"); unresolved++; }
                j.prop("xp", xmap instanceof Map<?, ?> && ((Map<?, ?>)xmap).get(e.getKey()) instanceof Number ? ((Number)((Map<?, ?>)xmap).get(e.getKey())).floatValue() : 0.0f).endObject();
            }
            j.endArray();
        } catch (Throwable t) { unpopulated.add("recipes.smelting: " + Refl.describe(t)); }
        j.prop("craftingTotal", crafting).prop("smeltingTotal", smelting).prop("unresolvableItemTotal", unresolved);
        j.name("unknownClasses").beginArray();
        for (Map.Entry<String, Integer> e : classes.entrySet()) j.beginObject().prop("className", e.getKey()).prop("count", e.getValue()).endObject();
        j.endArray().prop("unknownClassTotal", unknown).endObject();
    }

    private static void emitFloat(Jw j, String name, Object v) throws Exception {
        if (v instanceof Float) {
            j.prop(name, ((Float) v).floatValue());
        } else if (v instanceof Number) {
            j.prop(name, ((Number) v).floatValue());
        } else {
            j.propNull(name);
        }
    }

    private static int indexOfKey(LinkedHashSet<String> seen, String key) {
        int i = 0;
        for (String s : seen) {
            if (s.equals(key)) {
                return i;
            }
            i++;
        }
        return -1;
    }
}
