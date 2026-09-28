package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One 1.7.10 metadata variant, shared by items ({@code subItems[].damage}) and blocks
 * ({@code subBlocks[].meta}). Pure data - no net.minecraft types.
 *
 * 1.7.10 packed several logical objects into one Item/Block and told them apart by the
 * ItemStack damage value ({@code Item.getSubItems} / {@code Block.getSubBlocks}). 26.2 has no
 * metadata at all, so every one of these has to become its own registry entry - the same
 * flattening Mojang did to vanilla in 1.13.
 */
public final class SubRec {

    /** damage (items) / metadata (blocks). */
    public int meta;
    public String unlocalizedName;
    public String displayName;
    public String iconName;

    public static SubRec from(JsonObject o, String metaKey) {
        SubRec s = new SubRec();
        s.meta = LegacySnapshot.i(o, metaKey, 0);
        s.unlocalizedName = LegacySnapshot.str(o, "unlocalizedName");
        s.displayName = LegacySnapshot.str(o, "displayName");
        s.iconName = LegacySnapshot.str(o, "iconName");
        return s;
    }

    static List<SubRec> readAll(JsonObject owner, String arrayKey, String metaKey) {
        List<SubRec> out = new ArrayList<>();
        JsonArray a = LegacySnapshot.arr(owner, arrayKey);
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            out.add(from(e.getAsJsonObject(), metaKey));
        }
        return out;
    }

    /**
     * First occurrence of each meta wins, snapshot order preserved.
     *
     * The real snapshot contains 7 items whose subItems list repeats a damage value
     * (e.g. hbm:item.battery_pack lists every damage twice, hbm:item.blueprints lists damage 0
     * twelve times), and registering the same id twice would abort the whole run.
     */
    public static List<SubRec> distinctByMeta(List<SubRec> subs) {
        List<SubRec> out = new ArrayList<>();
        if (subs == null) return out;
        Set<Integer> seen = new LinkedHashSet<>();
        for (SubRec s : subs) {
            if (s == null) continue;
            if (!seen.add(s.meta)) continue;
            out.add(s);
        }
        return out;
    }

    public boolean hasMeta(int m) {
        return meta == m;
    }

    @Override
    public String toString() {
        return "SubRec{meta=" + meta + ", unloc=" + unlocalizedName + ", name=" + displayName + "}";
    }
}
