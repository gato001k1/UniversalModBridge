package dev.umb.hostagent.content;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** One 1.7.10 Item as captured by the snapshot. Pure data - no net.minecraft types. */
public final class ItemRec {

    public String id;
    public String className;
    public String unlocalizedName;
    public String displayName;
    public int maxStackSize = 64;
    public int maxDamage;
    public boolean hasSubtypes;
    public String creativeTab;
    public String textureName;
    public String iconName;
    /** non-null when this Item is the ItemBlock of the named legacy block id. */
    public String isBlockItem;
    public boolean isFood;
    /** 1.7.10 metadata variants (Item.getSubItems), keyed by ItemStack damage. Never null. */
    public List<SubRec> subItems = new ArrayList<>();
    /** true when the snapshot capped the subItems list. */
    public boolean truncated;
    /** Number of entries the legacy snapshot observed before applying its capture cap. */
    public int subItemsTotal;

    public static ItemRec from(JsonObject o) {
        ItemRec it = new ItemRec();
        it.id = LegacySnapshot.str(o, "id");
        if (it.id == null) return null;
        it.className = LegacySnapshot.str(o, "className");
        it.unlocalizedName = LegacySnapshot.str(o, "unlocalizedName");
        it.displayName = LegacySnapshot.str(o, "displayName");
        it.maxStackSize = LegacySnapshot.i(o, "maxStackSize", 64);
        it.maxDamage = LegacySnapshot.i(o, "maxDamage", 0);
        it.hasSubtypes = LegacySnapshot.bool(o, "hasSubtypes", false);
        it.creativeTab = LegacySnapshot.str(o, "creativeTab");
        it.textureName = LegacySnapshot.str(o, "textureName");
        it.iconName = LegacySnapshot.str(o, "iconName");
        it.isBlockItem = LegacySnapshot.str(o, "isBlockItem");
        it.isFood = LegacySnapshot.bool(o, "isFood", false);
        it.subItems = SubRec.readAll(o, "subItems", "damage");
        it.truncated = LegacySnapshot.bool(o, "truncated", false);
        it.subItemsTotal = LegacySnapshot.i(o, "subItemsTotal", it.subItems.size());
        return it;
    }

    /** Best available 1.7.10 icon name for this item. */
    public String icon() {
        return iconName != null ? iconName : textureName;
    }

    /**
     * The variant group as it should be registered: distinct damage values, snapshot order.
     * A group of 0 or 1 entries is NOT flattened - the record keeps its v0 id and only borrows
     * the sub-item's display name and icon.
     */
    public List<SubRec> variantGroup() {
        List<SubRec> d = SubRec.distinctByMeta(subItems);
        if (truncated && hasSubtypes && subItemsTotal > d.size()) {
            java.util.Set<Integer> present = new java.util.HashSet<>();
            for (SubRec s : d) present.add(s.meta);
            // The snapshot producer retains the legacy iteration order and tells us the total
            // cardinality when it caps a list.  Fill only the omitted numeric damage slots; the
            // concrete legacy item remains the authority for their names/icons at runtime.
            for (int meta = 0; meta < subItemsTotal; meta++) {
                if (present.contains(meta)) continue;
                SubRec synthetic = new SubRec();
                synthetic.meta = meta;
                synthetic.unlocalizedName = meta == 0 ? unlocalizedName
                        : (unlocalizedName == null ? null : unlocalizedName + "_" + meta);
                d.add(synthetic);
            }
        }
        return d.size() > 1 ? d : List.of();
    }

    /** The sub-item that describes the base record itself (damage 0, else the first). */
    public SubRec primarySub() {
        SubRec first = null;
        for (SubRec s : subItems) {
            if (first == null) first = s;
            if (s.meta == 0) return s;
        }
        return first;
    }

    /** 1..99, matching Item.Properties.stacksTo bounds on 26.2. */
    public int clampedStackSize() {
        int n = maxStackSize;
        if (n < 1) n = 1;
        if (n > 99) n = 99;
        return n;
    }
}
