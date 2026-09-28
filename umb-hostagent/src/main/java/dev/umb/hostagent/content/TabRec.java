package dev.umb.hostagent.content;

import com.google.gson.JsonObject;

/** One 1.7.10 CreativeTabs entry. */
public final class TabRec {

    public int index;
    public String label;
    public String translatedLabel;
    public String iconItemId;
    public int iconMeta;
    public String iconName;

    public static TabRec from(JsonObject o) {
        TabRec t = new TabRec();
        t.index = LegacySnapshot.i(o, "index", -1);
        t.label = LegacySnapshot.str(o, "label");
        if (t.label == null) return null;
        t.translatedLabel = LegacySnapshot.str(o, "translatedLabel");
        t.iconItemId = LegacySnapshot.str(o, "iconItemId");
        t.iconMeta = LegacySnapshot.i(o, "iconMeta", 0);
        t.iconName = LegacySnapshot.str(o, "iconName");
        return t;
    }

    public boolean isVanillaLabel() {
        return LegacySnapshot.VANILLA_TAB_LABELS.contains(label);
    }
}
