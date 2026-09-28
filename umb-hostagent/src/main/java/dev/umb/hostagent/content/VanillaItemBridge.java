package dev.umb.hostagent.content;

import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.HostAgent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges 1.7.10 VANILLA ({@code minecraft:*}) item ids &lt;-&gt; 26.2 native {@link Item}s.
 *
 * Unlike HBM content (see {@link Registrar}), vanilla items are never newly registered here --
 * they already exist in {@code BuiltInRegistries.ITEM} under their own 26.2 name. Two cases:
 * <ol>
 *   <li><b>identity</b>: the 1.7.10 id string is STILL the 26.2 id verbatim
 *       (e.g. {@code minecraft:coal}, {@code minecraft:iron_ore}) -- checked live via
 *       {@code BuiltInRegistries.ITEM.containsKey(Identifier.parse(id))}, never assumed.</li>
 *   <li><b>renamed</b>: 26.2 flattened 1.7.10's per-damage metadata into separate ids
 *       ({@code minecraft:wool@4} -&gt; {@code minecraft:yellow_wool}) or renamed the id outright
 *       ({@code minecraft:reeds} -&gt; {@code minecraft:sugar_cane}). {@link #RENAME_TABLE} below is
 *       a small, EVIDENCE-ONLY table: every row was checked against a live dump of
 *       {@code BuiltInRegistries.ITEM.keySet()} taken from the real
 *       {@code research/jars/26.2/client.jar} (see research/out/legacy/g2-integration-progress.md
 *       for the exact verification method/log) -- nothing here is guessed from memory of "modern
 *       Minecraft" naming, which would be unsound: this codebase targets 26.2, a version newer
 *       than training data, and several 1.13-era flattening names have drifted further since
 *       (e.g. the plain grass-tuft item is {@code minecraft:short_grass} here, not
 *       {@code minecraft:grass}).</li>
 * </ol>
 *
 * Three rows are CORRECTIONS, not simple renames: 1.7.10's {@code minecraft:nether_brick} (the
 * Nether Brick BLOCK's ItemBlock), {@code minecraft:melon} (the eaten melon SLICE food item) and
 * {@code minecraft:snow} (the SOLID snow block's ItemBlock) each happen to string-match a 26.2
 * item id that names a DIFFERENT object (26.2's {@code nether_brick} is the crafting material,
 * {@code melon} is now the block, {@code snow} is now the thin layer) -- a naive identity check
 * would silently cross-wire these three. Found by cross-checking every {@link #RENAME_TABLE}
 * target against the raw identity set; the table wins over identity for exactly these three keys
 * (see {@link #mapOne} -- the table is always consulted before falling back to raw identity).
 *
 * Left deliberately UNMAPPED (never guessed): {@code minecraft:bed} (1.7.10's single bed had no
 * colour metadata at all; nothing in this sandbox's evidence -- the live registry only proves
 * candidate ids like {@code minecraft:red_bed}/{@code minecraft:white_bed} individually EXIST, not
 * which one is the historically-correct default -- settles which colour it became, so it stays
 * unmapped rather than guess), {@code minecraft:spawn_egg} (its subItems mix real vanilla mobs
 * with HBM's own custom mobs in one damage-indexed list -- ambiguous, ombined with the fact 26.2
 * has no single generic spawn-egg id), and block-only/pseudo items with no obtainable ItemStack
 * form in either version ({@code fire}, {@code water}, {@code lava}, {@code portal},
 * {@code end_portal}, {@code lit_furnace}, {@code carrots}, {@code potatoes}, {@code cocoa},
 * {@code double_wooden_slab}).
 */
final class VanillaItemBridge {

    private VanillaItemBridge() {
    }

    /**
     * {legacy base id, damage (as a string), verified 26.2 id}. See the class javadoc: every row
     * was checked with {@code Identifier.parse(...)} + {@code BuiltInRegistries.ITEM.containsKey}
     * against the real 26.2 registry dump, never guessed from memory.
     */
    private static final String[][] RENAME_TABLE = {
        {"minecraft:coal", "1", "minecraft:charcoal"},
        {"minecraft:log", "0", "minecraft:oak_log"},
        {"minecraft:log", "1", "minecraft:spruce_log"},
        {"minecraft:log", "2", "minecraft:birch_log"},
        {"minecraft:log", "3", "minecraft:jungle_log"},
        {"minecraft:log2", "0", "minecraft:acacia_log"},
        {"minecraft:log2", "1", "minecraft:dark_oak_log"},
        {"minecraft:planks", "0", "minecraft:oak_planks"},
        {"minecraft:planks", "1", "minecraft:spruce_planks"},
        {"minecraft:planks", "2", "minecraft:birch_planks"},
        {"minecraft:planks", "3", "minecraft:jungle_planks"},
        {"minecraft:planks", "4", "minecraft:acacia_planks"},
        {"minecraft:planks", "5", "minecraft:dark_oak_planks"},
        {"minecraft:sapling", "0", "minecraft:oak_sapling"},
        {"minecraft:sapling", "1", "minecraft:spruce_sapling"},
        {"minecraft:sapling", "2", "minecraft:birch_sapling"},
        {"minecraft:sapling", "3", "minecraft:jungle_sapling"},
        {"minecraft:sapling", "4", "minecraft:acacia_sapling"},
        {"minecraft:sapling", "5", "minecraft:dark_oak_sapling"},
        {"minecraft:leaves", "0", "minecraft:oak_leaves"},
        {"minecraft:leaves", "1", "minecraft:spruce_leaves"},
        {"minecraft:leaves", "2", "minecraft:birch_leaves"},
        {"minecraft:leaves", "3", "minecraft:jungle_leaves"},
        {"minecraft:leaves2", "0", "minecraft:acacia_leaves"},
        {"minecraft:leaves2", "1", "minecraft:dark_oak_leaves"},
        {"minecraft:wool", "0", "minecraft:white_wool"},
        {"minecraft:wool", "1", "minecraft:orange_wool"},
        {"minecraft:wool", "2", "minecraft:magenta_wool"},
        {"minecraft:wool", "3", "minecraft:light_blue_wool"},
        {"minecraft:wool", "4", "minecraft:yellow_wool"},
        {"minecraft:wool", "5", "minecraft:lime_wool"},
        {"minecraft:wool", "6", "minecraft:pink_wool"},
        {"minecraft:wool", "7", "minecraft:gray_wool"},
        {"minecraft:wool", "8", "minecraft:light_gray_wool"},
        {"minecraft:wool", "9", "minecraft:cyan_wool"},
        {"minecraft:wool", "10", "minecraft:purple_wool"},
        {"minecraft:wool", "11", "minecraft:blue_wool"},
        {"minecraft:wool", "12", "minecraft:brown_wool"},
        {"minecraft:wool", "13", "minecraft:green_wool"},
        {"minecraft:wool", "14", "minecraft:red_wool"},
        {"minecraft:wool", "15", "minecraft:black_wool"},
        {"minecraft:carpet", "0", "minecraft:white_carpet"},
        {"minecraft:carpet", "1", "minecraft:orange_carpet"},
        {"minecraft:carpet", "2", "minecraft:magenta_carpet"},
        {"minecraft:carpet", "3", "minecraft:light_blue_carpet"},
        {"minecraft:carpet", "4", "minecraft:yellow_carpet"},
        {"minecraft:carpet", "5", "minecraft:lime_carpet"},
        {"minecraft:carpet", "6", "minecraft:pink_carpet"},
        {"minecraft:carpet", "7", "minecraft:gray_carpet"},
        {"minecraft:carpet", "8", "minecraft:light_gray_carpet"},
        {"minecraft:carpet", "9", "minecraft:cyan_carpet"},
        {"minecraft:carpet", "10", "minecraft:purple_carpet"},
        {"minecraft:carpet", "11", "minecraft:blue_carpet"},
        {"minecraft:carpet", "12", "minecraft:brown_carpet"},
        {"minecraft:carpet", "13", "minecraft:green_carpet"},
        {"minecraft:carpet", "14", "minecraft:red_carpet"},
        {"minecraft:carpet", "15", "minecraft:black_carpet"},
        {"minecraft:stained_glass", "0", "minecraft:white_stained_glass"},
        {"minecraft:stained_glass", "1", "minecraft:orange_stained_glass"},
        {"minecraft:stained_glass", "2", "minecraft:magenta_stained_glass"},
        {"minecraft:stained_glass", "3", "minecraft:light_blue_stained_glass"},
        {"minecraft:stained_glass", "4", "minecraft:yellow_stained_glass"},
        {"minecraft:stained_glass", "5", "minecraft:lime_stained_glass"},
        {"minecraft:stained_glass", "6", "minecraft:pink_stained_glass"},
        {"minecraft:stained_glass", "7", "minecraft:gray_stained_glass"},
        {"minecraft:stained_glass", "8", "minecraft:light_gray_stained_glass"},
        {"minecraft:stained_glass", "9", "minecraft:cyan_stained_glass"},
        {"minecraft:stained_glass", "10", "minecraft:purple_stained_glass"},
        {"minecraft:stained_glass", "11", "minecraft:blue_stained_glass"},
        {"minecraft:stained_glass", "12", "minecraft:brown_stained_glass"},
        {"minecraft:stained_glass", "13", "minecraft:green_stained_glass"},
        {"minecraft:stained_glass", "14", "minecraft:red_stained_glass"},
        {"minecraft:stained_glass", "15", "minecraft:black_stained_glass"},
        {"minecraft:stained_glass_pane", "0", "minecraft:white_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "1", "minecraft:orange_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "2", "minecraft:magenta_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "3", "minecraft:light_blue_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "4", "minecraft:yellow_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "5", "minecraft:lime_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "6", "minecraft:pink_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "7", "minecraft:gray_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "8", "minecraft:light_gray_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "9", "minecraft:cyan_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "10", "minecraft:purple_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "11", "minecraft:blue_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "12", "minecraft:brown_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "13", "minecraft:green_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "14", "minecraft:red_stained_glass_pane"},
        {"minecraft:stained_glass_pane", "15", "minecraft:black_stained_glass_pane"},
        {"minecraft:stained_hardened_clay", "0", "minecraft:white_terracotta"},
        {"minecraft:stained_hardened_clay", "1", "minecraft:orange_terracotta"},
        {"minecraft:stained_hardened_clay", "2", "minecraft:magenta_terracotta"},
        {"minecraft:stained_hardened_clay", "3", "minecraft:light_blue_terracotta"},
        {"minecraft:stained_hardened_clay", "4", "minecraft:yellow_terracotta"},
        {"minecraft:stained_hardened_clay", "5", "minecraft:lime_terracotta"},
        {"minecraft:stained_hardened_clay", "6", "minecraft:pink_terracotta"},
        {"minecraft:stained_hardened_clay", "7", "minecraft:gray_terracotta"},
        {"minecraft:stained_hardened_clay", "8", "minecraft:light_gray_terracotta"},
        {"minecraft:stained_hardened_clay", "9", "minecraft:cyan_terracotta"},
        {"minecraft:stained_hardened_clay", "10", "minecraft:purple_terracotta"},
        {"minecraft:stained_hardened_clay", "11", "minecraft:blue_terracotta"},
        {"minecraft:stained_hardened_clay", "12", "minecraft:brown_terracotta"},
        {"minecraft:stained_hardened_clay", "13", "minecraft:green_terracotta"},
        {"minecraft:stained_hardened_clay", "14", "minecraft:red_terracotta"},
        {"minecraft:stained_hardened_clay", "15", "minecraft:black_terracotta"},
        {"minecraft:dye", "0", "minecraft:ink_sac"},
        {"minecraft:dye", "1", "minecraft:red_dye"},
        {"minecraft:dye", "2", "minecraft:green_dye"},
        {"minecraft:dye", "3", "minecraft:cocoa_beans"},
        {"minecraft:dye", "4", "minecraft:lapis_lazuli"},
        {"minecraft:dye", "5", "minecraft:purple_dye"},
        {"minecraft:dye", "6", "minecraft:cyan_dye"},
        {"minecraft:dye", "7", "minecraft:light_gray_dye"},
        {"minecraft:dye", "8", "minecraft:gray_dye"},
        {"minecraft:dye", "9", "minecraft:pink_dye"},
        {"minecraft:dye", "10", "minecraft:lime_dye"},
        {"minecraft:dye", "11", "minecraft:yellow_dye"},
        {"minecraft:dye", "12", "minecraft:light_blue_dye"},
        {"minecraft:dye", "13", "minecraft:magenta_dye"},
        {"minecraft:dye", "14", "minecraft:orange_dye"},
        {"minecraft:dye", "15", "minecraft:bone_meal"},
        {"minecraft:cobblestone_wall", "1", "minecraft:mossy_cobblestone_wall"},
        {"minecraft:stonebrick", "0", "minecraft:stone_bricks"},
        {"minecraft:stonebrick", "1", "minecraft:mossy_stone_bricks"},
        {"minecraft:stonebrick", "2", "minecraft:cracked_stone_bricks"},
        {"minecraft:stonebrick", "3", "minecraft:chiseled_stone_bricks"},
        {"minecraft:sand", "1", "minecraft:red_sand"},
        {"minecraft:sandstone", "1", "minecraft:chiseled_sandstone"},
        {"minecraft:sandstone", "2", "minecraft:smooth_sandstone"},
        {"minecraft:quartz_block", "1", "minecraft:chiseled_quartz_block"},
        {"minecraft:quartz_block", "2", "minecraft:quartz_pillar"},
        {"minecraft:red_flower", "0", "minecraft:poppy"},
        {"minecraft:red_flower", "1", "minecraft:blue_orchid"},
        {"minecraft:red_flower", "2", "minecraft:allium"},
        {"minecraft:red_flower", "3", "minecraft:azure_bluet"},
        {"minecraft:red_flower", "4", "minecraft:red_tulip"},
        {"minecraft:red_flower", "5", "minecraft:orange_tulip"},
        {"minecraft:red_flower", "6", "minecraft:white_tulip"},
        {"minecraft:red_flower", "7", "minecraft:pink_tulip"},
        {"minecraft:red_flower", "8", "minecraft:oxeye_daisy"},
        {"minecraft:yellow_flower", "0", "minecraft:dandelion"},
        {"minecraft:double_plant", "0", "minecraft:sunflower"},
        {"minecraft:double_plant", "1", "minecraft:lilac"},
        {"minecraft:double_plant", "2", "minecraft:tall_grass"},
        {"minecraft:double_plant", "3", "minecraft:large_fern"},
        {"minecraft:double_plant", "4", "minecraft:rose_bush"},
        {"minecraft:double_plant", "5", "minecraft:peony"},
        {"minecraft:tallgrass", "0", "minecraft:short_grass"},
        {"minecraft:tallgrass", "1", "minecraft:fern"},
        {"minecraft:monster_egg", "0", "minecraft:infested_stone"},
        {"minecraft:monster_egg", "1", "minecraft:infested_cobblestone"},
        {"minecraft:monster_egg", "2", "minecraft:infested_stone_bricks"},
        {"minecraft:monster_egg", "3", "minecraft:infested_mossy_stone_bricks"},
        {"minecraft:monster_egg", "4", "minecraft:infested_cracked_stone_bricks"},
        {"minecraft:monster_egg", "5", "minecraft:infested_chiseled_stone_bricks"},
        {"minecraft:anvil", "1", "minecraft:chipped_anvil"},
        {"minecraft:anvil", "2", "minecraft:damaged_anvil"},
        {"minecraft:skull", "0", "minecraft:skeleton_skull"},
        {"minecraft:skull", "1", "minecraft:wither_skeleton_skull"},
        {"minecraft:skull", "2", "minecraft:zombie_head"},
        {"minecraft:skull", "3", "minecraft:player_head"},
        {"minecraft:skull", "4", "minecraft:creeper_head"},
        {"minecraft:fish", "0", "minecraft:cod"},
        {"minecraft:fish", "1", "minecraft:salmon"},
        {"minecraft:fish", "2", "minecraft:tropical_fish"},
        {"minecraft:fish", "3", "minecraft:pufferfish"},
        {"minecraft:cooked_fished", "0", "minecraft:cooked_cod"},
        {"minecraft:cooked_fished", "1", "minecraft:cooked_salmon"},
        {"minecraft:golden_apple", "1", "minecraft:enchanted_golden_apple"},
        {"minecraft:wooden_slab", "0", "minecraft:oak_slab"},
        {"minecraft:wooden_slab", "1", "minecraft:spruce_slab"},
        {"minecraft:wooden_slab", "2", "minecraft:birch_slab"},
        {"minecraft:wooden_slab", "3", "minecraft:jungle_slab"},
        {"minecraft:wooden_slab", "4", "minecraft:acacia_slab"},
        {"minecraft:wooden_slab", "5", "minecraft:dark_oak_slab"},
        {"minecraft:stone_slab", "1", "minecraft:sandstone_slab"},
        {"minecraft:stone_slab", "2", "minecraft:cobblestone_slab"},
        {"minecraft:stone_slab", "3", "minecraft:brick_slab"},
        {"minecraft:stone_slab", "4", "minecraft:stone_brick_slab"},
        {"minecraft:stone_slab", "5", "minecraft:nether_brick_slab"},
        {"minecraft:stone_slab", "6", "minecraft:quartz_slab"},
        {"minecraft:fence", "0", "minecraft:oak_fence"},
        {"minecraft:fence_gate", "0", "minecraft:oak_fence_gate"},
        {"minecraft:trapdoor", "0", "minecraft:oak_trapdoor"},
        {"minecraft:wooden_door", "0", "minecraft:oak_door"},
        {"minecraft:wooden_button", "0", "minecraft:oak_button"},
        {"minecraft:wooden_pressure_plate", "0", "minecraft:oak_pressure_plate"},
        {"minecraft:sign", "0", "minecraft:oak_sign"},
        {"minecraft:boat", "0", "minecraft:oak_boat"},
        {"minecraft:golden_rail", "0", "minecraft:powered_rail"},
        {"minecraft:netherbrick", "0", "minecraft:nether_brick"},
        {"minecraft:quartz_ore", "0", "minecraft:nether_quartz_ore"},
        {"minecraft:melon_block", "0", "minecraft:melon"},
        {"minecraft:brick_block", "0", "minecraft:bricks"},
        {"minecraft:noteblock", "0", "minecraft:note_block"},
        {"minecraft:mob_spawner", "0", "minecraft:spawner"},
        {"minecraft:reeds", "0", "minecraft:sugar_cane"},
        {"minecraft:waterlily", "0", "minecraft:lily_pad"},
        {"minecraft:web", "0", "minecraft:cobweb"},
        {"minecraft:snow_layer", "0", "minecraft:snow"},
        {"minecraft:deadbush", "0", "minecraft:dead_bush"},
        {"minecraft:grass", "0", "minecraft:grass_block"},
        {"minecraft:speckled_melon", "0", "minecraft:glistering_melon_slice"},
        {"minecraft:fireworks", "0", "minecraft:firework_rocket"},
        {"minecraft:firework_charge", "0", "minecraft:firework_star"},
        {"minecraft:hardened_clay", "0", "minecraft:terracotta"},
        {"minecraft:lit_pumpkin", "0", "minecraft:jack_o_lantern"},
        {"minecraft:double_stone_slab", "0", "minecraft:smooth_stone"},
        {"minecraft:record_13", "0", "minecraft:music_disc_13"},
        {"minecraft:record_cat", "0", "minecraft:music_disc_cat"},
        {"minecraft:record_blocks", "0", "minecraft:music_disc_blocks"},
        {"minecraft:record_chirp", "0", "minecraft:music_disc_chirp"},
        {"minecraft:record_far", "0", "minecraft:music_disc_far"},
        {"minecraft:record_mall", "0", "minecraft:music_disc_mall"},
        {"minecraft:record_mellohi", "0", "minecraft:music_disc_mellohi"},
        {"minecraft:record_stal", "0", "minecraft:music_disc_stal"},
        {"minecraft:record_strad", "0", "minecraft:music_disc_strad"},
        {"minecraft:record_ward", "0", "minecraft:music_disc_ward"},
        {"minecraft:record_11", "0", "minecraft:music_disc_11"},
        {"minecraft:record_wait", "0", "minecraft:music_disc_wait"},
        {"minecraft:nether_brick", "0", "minecraft:nether_bricks"},
        {"minecraft:melon", "0", "minecraft:melon_slice"},
        {"minecraft:snow", "0", "minecraft:snow_block"},
        {"minecraft:dirt", "2", "minecraft:podzol"},
    };

    private static volatile boolean built = false;
    private static final Object BUILD_LOCK = new Object();

    private static final Map<String, Item> LEGACY_TO_NATIVE = new LinkedHashMap<>();
    private static final Map<Item, String> NATIVE_TO_LEGACY = new HashMap<>();
    private static final Set<String> LOGGED_UNMAPPED = ConcurrentHashMap.newKeySet();

    /** -1 until {@link #ensureBuilt()} has actually run once. */
    static volatile int identityCount = -1;
    static volatile int renamedCount = -1;
    static volatile int unmappedCount = -1;
    static volatile int totalCount = -1;

    /**
     * Test-only: forces a fresh {@link #build()} on the next {@link #ensureBuilt()} call,
     * regardless of whatever state an earlier test class (or a real agent init) already left
     * behind in this JVM -- JUnit's {@code --scan-class-path} runs every test class in ONE JVM, so
     * without this a test that runs after another one that already latched {@code built=true}
     * (with an empty map, e.g. because {@code HostAgent.snapshotPath()} was null at that point)
     * would silently see stale/empty state instead of the real snapshot it just configured.
     */
    static void resetForTest() {
        synchronized (BUILD_LOCK) {
            LEGACY_TO_NATIVE.clear();
            NATIVE_TO_LEGACY.clear();
            LOGGED_UNMAPPED.clear();
            identityCount = renamedCount = unmappedCount = totalCount = -1;
            built = false;
        }
    }

    /** Idempotent; safe to call from any thread, any number of times (agent init or first use). */
    static void ensureBuilt() {
        if (built) return;
        synchronized (BUILD_LOCK) {
            if (built) return;
            try {
                build();
            } catch (Throwable t) {
                AgentLog.error("VanillaItemBridge.ensureBuilt", t, 4);
            }
            built = true;
        }
    }

    private static void build() {
        Path snapshotFile = HostAgent.snapshotPath();
        if (snapshotFile == null) {
            AgentLog.line("VanillaItemBridge: no snapshot configured -- vanilla item mapping stays empty");
            return;
        }
        String ns = HostAgent.namespace();
        LegacySnapshot snap;
        try {
            // ns may be null when nothing was ever configured: LegacySnapshot.matches is
            // null-safe (matches nothing), and vanillaItems populate regardless of namespace,
            // which is all build() reads - so no fallback literal is needed here.
            snap = LegacySnapshot.load(snapshotFile, ns);
        } catch (Exception e) {
            AgentLog.error("VanillaItemBridge.build: cannot read snapshot " + snapshotFile, e, 4);
            return;
        }
        buildFrom(snap.vanillaItems);
    }

    /**
     * Package-visible so tests can drive this straight from a parsed record list, without needing
     * a real -javaagent attach / HostAgent.configure() call.
     */
    static void buildFrom(List<ItemRec> vanillaItems) {
        Map<String, String> overrides = new HashMap<>();
        for (String[] row : RENAME_TABLE) {
            overrides.put(row[0] + "@" + row[1], row[2]);
        }

        int identity = 0, renamed = 0, unmapped = 0;
        for (ItemRec rec : vanillaItems) {
            List<SubRec> variants = rec.subItems.isEmpty() ? null : SubRec.distinctByMeta(rec.subItems);
            if (variants == null || variants.isEmpty()) {
                mapOne(rec.id, 0, overrides);
            } else {
                for (SubRec s : variants) {
                    mapOne(rec.id, s.meta, overrides);
                }
            }

            SubRec primary = rec.primarySub();
            int primaryDamage = primary != null ? primary.meta : 0;
            String primaryKey = rec.id + "@" + primaryDamage;
            if (LEGACY_TO_NATIVE.containsKey(primaryKey)) {
                if (overrides.containsKey(primaryKey)) renamed++; else identity++;
            } else {
                unmapped++;
            }
        }
        identityCount = identity;
        renamedCount = renamed;
        unmappedCount = unmapped;
        totalCount = vanillaItems.size();
        AgentLog.loud("UMB-VANILLA identity=" + identity + "/" + totalCount
                + " renamed=" + renamed + " unmapped=" + unmapped);
    }

    /** Maps one (legacyId, damage) pair if a native Item can be found for it; else leaves it unmapped. */
    private static void mapOne(String legacyId, int damage, Map<String, String> overrides) {
        String key = legacyId + "@" + damage;
        String nativeId = overrides.get(key);
        Item item = null;
        if (nativeId != null) {
            item = lookup(nativeId);
            if (item == null) {
                AgentLog.line("VanillaItemBridge: RENAME_TABLE entry " + key + " -> " + nativeId
                        + " does not exist in this registry (skipping)");
            }
        }
        if (item == null) {
            // no override (or the override's target vanished) -- fall back to raw identity.
            item = lookup(legacyId);
        }
        if (item == null) return;
        LEGACY_TO_NATIVE.put(key, item);
        NATIVE_TO_LEGACY.putIfAbsent(item, key);
    }

    private static Item lookup(String id) {
        try {
            Identifier rl = Identifier.parse(id);
            if (!BuiltInRegistries.ITEM.containsKey(rl)) return null;
            return BuiltInRegistries.ITEM.getValue(rl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** "minecraft:xxx@damage" -> native Item, or null if this exact 1.7.10 vanilla id is not known. */
    static Item legacyToNative(String legacyId, int damage) {
        ensureBuilt();
        return LEGACY_TO_NATIVE.get(legacyId + "@" + damage);
    }

    /** native Item -> "minecraft:xxx@damage", or null if this item has no known 1.7.10 vanilla id. */
    static String nativeToLegacyKey(Item item) {
        ensureBuilt();
        return NATIVE_TO_LEGACY.get(item);
    }

    /** Logs an unmapped native item exactly once per process, however many times it is hit. */
    static void logUnmappedNativeOnce(String itemDescription) {
        if (LOGGED_UNMAPPED.add("native:" + itemDescription)) {
            AgentLog.error("LegacyStackConv.toLegacy: no legacy id for native item " + itemDescription,
                    new IllegalArgumentException("not a legacy-registered item (mod or vanilla)"), 1);
        }
    }

    /** Logs an unmapped legacy id exactly once per process, however many times it is hit. */
    static void logUnmappedLegacyOnce(String legacyKeyDescription) {
        if (LOGGED_UNMAPPED.add("legacy:" + legacyKeyDescription)) {
            AgentLog.error("LegacyStackConv.toNative: unknown legacy item " + legacyKeyDescription,
                    new IllegalArgumentException("no registered native item"), 1);
        }
    }
}
