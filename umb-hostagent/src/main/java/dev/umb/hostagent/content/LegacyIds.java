package dev.umb.hostagent.content;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ONE shared identifier sanitizer used by both the host agent (registration side)
 * and dev.umb.packgen.PackGen (resource side). Both sides MUST agree byte for byte or
 * generated models/textures will not line up with registered objects.
 *
 * Rules (matching net.minecraft.resources.Identifier.isValidNamespace / isValidPath):
 *   namespace: [a-z0-9_.-]
 *   path:      [a-z0-9/._-]
 * Everything is lowercased first; any other character becomes '_'.
 * Duplicate results are disambiguated by appending _2, _3, ... in first-seen order.
 */
public final class LegacyIds {

    private static final String NS_OK = "abcdefghijklmnopqrstuvwxyz0123456789_.-";
    private static final String PATH_OK = "abcdefghijklmnopqrstuvwxyz0123456789/._-";

    /** legacy id -> sanitized path, in insertion order. */
    private final Map<String, String> assigned = new LinkedHashMap<>();
    /** sanitized path -> how many times handed out. */
    private final Map<String, Integer> used = new HashMap<>();
    /** legacy id -> sanitized path, only for entries that actually changed. */
    private final Map<String, String> renames = new LinkedHashMap<>();

    public static String sanitizeNamespace(String raw) {
        return filter(raw, NS_OK);
    }

    public static String sanitizePath(String raw) {
        return filter(raw, PATH_OK);
    }

    private static String filter(String raw, String allowed) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append(allowed.indexOf(c) >= 0 ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Split a legacy id "ns:path" (or bare "path") into its path part.
     * Returns null when there is nothing usable.
     */
    public static String pathOf(String legacyId) {
        if (legacyId == null) return null;
        String s = legacyId.trim();
        if (s.isEmpty()) return null;
        int c = s.indexOf(':');
        String p = (c >= 0) ? s.substring(c + 1) : s;
        if (p.isEmpty()) return null;
        return p;
    }

    public static String namespaceOf(String legacyId, String fallback) {
        if (legacyId == null) return fallback;
        int c = legacyId.indexOf(':');
        if (c <= 0) return fallback;
        return legacyId.substring(0, c);
    }

    /**
     * Sanitize + de-duplicate a legacy id into a registry path.
     * The same legacy id always maps to the same path. Returns null for unusable ids.
     */
    public String pathFor(String legacyId) {
        String existing = assigned.get(legacyId);
        if (existing != null) return existing;

        String raw = pathOf(legacyId);
        if (raw == null) return null;
        String base = sanitizePath(raw);
        if (base.isEmpty()) return null;

        String candidate = base;
        Integer n = used.get(base);
        if (n != null) {
            int i = n + 1;
            while (used.containsKey(base + "_" + i)) i++;
            candidate = base + "_" + i;
            used.put(base, i);
        }
        used.put(candidate, 1);
        assigned.put(legacyId, candidate);
        if (!candidate.equals(raw)) renames.put(legacyId, candidate);
        return candidate;
    }

    /** Sanitize a texture/icon path with the SAME rules but no de-duplication. */
    public static String texturePath(String raw) {
        String p = pathOf(raw);
        if (p == null) return null;
        String s = sanitizePath(p);
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ variants

    /**
     * The deterministic 1.7.10-style id of one metadata variant. STATIC and pure: given the same
     * base record and the same {@link SubRec} it always returns the same string, on the agent side
     * and inside PackGen alike.
     *
     * <pre>
     *   metaZeroIsBase && sub.meta == 0        -&gt; the base id             (blocks: keeps every v0 id valid)
     *   readable                               -&gt; "&lt;ns&gt;:&lt;sub.unlocalizedName&gt;"
     *                                               e.g. item.drillbit + item.drillbit_steel
     *                                                    -&gt; hbm:item.drillbit_steel
     *   sub.meta == 0                          -&gt; the base id
     *   otherwise                              -&gt; "&lt;baseId&gt;_&lt;meta&gt;"
     * </pre>
     *
     * {@code readable} is a decision about the WHOLE variant group, not about one sub-item -
     * see {@link #readableIdsUsable}. Deciding per sub-item would be wrong: three HBM items
     * (item.bolt, item.pipe, item.shell) give every one of their sub-items the SAME
     * unlocalizedName, so a per-sub-item rule hands out one id for up to seven variants.
     */
    public static String variantId(String baseLegacyId, String baseUnlocalizedName, SubRec sub,
                                   boolean readable, boolean metaZeroIsBase) {
        if (sub == null) return baseLegacyId;
        if (metaZeroIsBase && sub.meta == 0) return baseLegacyId;
        if (readable) {
            return namespaceOf(baseLegacyId, "minecraft") + ":" + sub.unlocalizedName;
        }
        if (sub.meta == 0) return baseLegacyId;
        return baseLegacyId + "_" + sub.meta;
    }

    /**
     * True when the group's sub-items all carry a distinct unlocalizedName that also differs from
     * the base's, i.e. when readable ids like {@code hbm:item.drillbit_steel} are safe to use.
     * All-or-nothing on purpose, so a group never mixes readable and numeric suffixes.
     */
    public static boolean readableIdsUsable(String baseUnlocalizedName, java.util.List<SubRec> subs) {
        if (subs == null || subs.isEmpty()) return false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SubRec s : subs) {
            if (s == null || s.unlocalizedName == null || s.unlocalizedName.isEmpty()) return false;
            if (s.unlocalizedName.equals(baseUnlocalizedName)) return false;
            if (!seen.add(s.unlocalizedName)) return false;
        }
        return true;
    }

    /**
     * Variant ids for a whole group, in the group's own order. Convenience wrapper that makes the
     * readable/numeric decision once - this is what every caller should use.
     */
    public static java.util.List<String> variantIds(String baseLegacyId, String baseUnlocalizedName,
                                                    java.util.List<SubRec> subs, boolean metaZeroIsBase) {
        java.util.List<SubRec> distinct = SubRec.distinctByMeta(subs);
        boolean readable = readableIdsUsable(baseUnlocalizedName, distinct);
        java.util.List<String> out = new java.util.ArrayList<>(distinct.size());
        for (SubRec s : distinct) {
            out.add(variantId(baseLegacyId, baseUnlocalizedName, s, readable, metaZeroIsBase));
        }
        return out;
    }

    public Map<String, String> renames() {
        return renames;
    }

    public int renameCount() {
        return renames.size();
    }

    public Map<String, String> assigned() {
        return assigned;
    }
}
