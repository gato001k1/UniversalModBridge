package dev.umb.rendermap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A read-only port of {@code dev.umb.hostagent.content.LegacyIds.variantId} /
 * {@code readableIdsUsable}, restricted to the item-side rule ({@code metaZeroIsBase = false}).
 *
 * umb-rendermap must not depend on umb-hostagent (different lane, different owner), so this
 * mirrors the exact algorithm read from
 * {@code umb-hostagent/src/main/java/dev/umb/hostagent/content/LegacyIds.java} (read-only) rather
 * than importing it. Any drift between the two must be caught by
 * {@code TexturePickTest}-style cross-checks in umb-objbridge, not by this lane editing hostagent.
 *
 * <pre>
 *   readable = every sub-item's unlocalizedName is non-empty, distinct from every other
 *              sub-item's, AND distinct from the base's own unlocalizedName
 *   readable  -&gt; "&lt;ns&gt;:&lt;sub.unlocalizedName&gt;"           (ALL metas, including 0)
 *   !readable &amp;&amp; meta==0 -&gt; the base id unchanged
 *   !readable &amp;&amp; meta!=0 -&gt; "&lt;baseId&gt;_&lt;meta&gt;"
 * </pre>
 */
public final class VariantIdRule {
    private VariantIdRule() {}

    public record Sub(int meta, String unlocalizedName) {}

    public static boolean readableIdsUsable(String baseUnlocalizedName, List<Sub> subs) {
        if (subs == null || subs.isEmpty()) return false;
        Set<String> seen = new LinkedHashSet<>();
        for (Sub s : subs) {
            if (s.unlocalizedName() == null || s.unlocalizedName().isEmpty()) return false;
            if (s.unlocalizedName().equals(baseUnlocalizedName)) return false;
            if (!seen.add(s.unlocalizedName())) return false;
        }
        return true;
    }

    public static String namespaceOf(String legacyId, String fallback) {
        if (legacyId == null) return fallback;
        int c = legacyId.indexOf(':');
        return c <= 0 ? fallback : legacyId.substring(0, c);
    }

    /** Computes the id every sub-item in {@code subs} will get, in the given order. */
    public static java.util.List<String> variantIds(String baseLegacyId, String baseUnlocalizedName,
                                                      List<Sub> subs) {
        boolean readable = readableIdsUsable(baseUnlocalizedName, subs);
        java.util.List<String> out = new java.util.ArrayList<>(subs.size());
        String ns = namespaceOf(baseLegacyId, "minecraft");
        for (Sub s : subs) {
            if (readable) out.add(ns + ":" + s.unlocalizedName());
            else out.add(s.meta() == 0 ? baseLegacyId : baseLegacyId + "_" + s.meta());
        }
        return out;
    }
}
