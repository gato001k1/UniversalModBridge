package dev.umb.objbridge.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Picks ONE model + ONE "primary" texture out of a render-map row.
 *
 * <p>The 1.7.10 renderers bound several textures per draw (body + muzzle flash + laser beam + ...) and
 * 494 of the 754 item rows are flagged {@code dynamic} because the renderer chose at draw time. v1 has
 * no draw-time state, so it takes the FIRST model and the primary texture, in this order:
 *
 * <ol>
 *   <li>{@link Reason#BASENAME} - the texture whose basename equals the OBJ's basename
 *       ({@code minigun.obj} -> {@code minigun.png}),</li>
 *   <li>{@link Reason#FAMILY} - the first texture in the same directory family as the model
 *       ({@code models/weapons/x.obj} -> anything under {@code textures/models/weapons/}),</li>
 *   <li>{@link Reason#MIRROR} - the on-disk mirror {@code textures/models/&lt;same relative path&gt;.png}
 *       even when the renderer never named it (238 of the 507 OBJs have one),</li>
 *   <li>{@link Reason#FIRST} - the first non-effect texture the row lists at all.</li>
 * </ol>
 *
 * <p>Effect textures are excluded from every rule: a name containing plume, flash, muzzle, glow, lens,
 * beam or laser is a particle/overlay sheet, never the body skin.
 */
public final class TexturePick {

    /** Substrings that mark a texture as a draw-time effect sheet rather than the body skin. */
    static final String[] EFFECT = {"plume", "flash", "muzzle", "glow", "lens", "beam", "laser"};

    /** Sub-directory of {@code textures/models/} that textures from elsewhere are relocated into. */
    public static final String RELOCATED = "_umb";

    public enum Reason { BASENAME, FAMILY, MIRROR, FIRST, NONE }

    /**
     * @param model        the chosen OBJ, e.g. {@code hbm:models/weapons/minigun.obj} (null = no model)
     * @param texture      the chosen PNG, e.g. {@code hbm:textures/models/weapons/minigun.png}
     * @param reason       which rule fired
     * @param modelCount   how many models the row listed (>1 means we discarded alternatives)
     * @param textureCount how many textures the row listed
     */
    public record Pick(RenderMap.Asset model, RenderMap.Asset texture, Reason reason,
                       int modelCount, int textureCount) {

        public boolean resolved() { return model != null && texture != null; }

        /**
         * Sprite name for the chosen texture as it will exist in the overlay pack:
         * {@code hbm:textures/models/weapons/minigun.png} -> {@code hbm:models/weapons/minigun},
         * lowercased because {@code Identifier} paths only allow {@code [a-z0-9/._-]}.
         *
         * <p>A handful of renderers skin their OBJ with a sheet from OUTSIDE {@code textures/models/}
         * ({@code textures/armor/}, {@code textures/blocks/}, {@code textures/particle/}). Those are
         * relocated under {@code models/} + {@link #RELOCATED} so the overlay pack needs exactly ONE
         * {@code minecraft:directory} atlas source, {@code models/}. Without the relocation a source
         * like {@code particle} would also list vanilla's own {@code assets/minecraft/textures/particle/**}
         * into the block atlas and log "Duplicate sprite" for every one of them.
         */
        public String spriteId() {
            if (texture == null) return null;
            String bare = texture.bare();                 // textures/models/weapons/minigun.png
            String pfx = "textures/";
            if (bare.startsWith(pfx)) bare = bare.substring(pfx.length());
            if (bare.toLowerCase(Locale.ROOT).endsWith(".png")) bare = bare.substring(0, bare.length() - 4);
            if (!bare.startsWith("models/")) bare = "models/" + RELOCATED + "/" + bare;
            return texture.namespace() + ":" + sanitize(bare);
        }
    }

    private TexturePick() { }

    public static Pick choose(List<RenderMap.Asset> models, List<RenderMap.Asset> textures, Path assetsRoot) {
        RenderMap.Asset model = null;
        for (RenderMap.Asset m : models) {
            if (m.path() != null && m.path().toLowerCase(Locale.ROOT).endsWith(".obj")
                    && !isCorrupt(m.path())) {
                model = m;
                break;
            }
        }
        int mc = models.size(), tc = textures.size();
        if (model == null) return new Pick(null, null, Reason.NONE, mc, tc);

        String base = model.baseName().toLowerCase(Locale.ROOT);
        String family = "textures/" + model.dir();              // textures/models/weapons

        // 1) basename match
        for (RenderMap.Asset t : textures) {
            if (!usable(t, assetsRoot)) continue;
            if (t.baseName().toLowerCase(Locale.ROOT).equals(base)) {
                return new Pick(model, t, Reason.BASENAME, mc, tc);
            }
        }
        // 2) same directory family
        for (RenderMap.Asset t : textures) {
            if (!usable(t, assetsRoot)) continue;
            String tdir = t.dir();                              // textures/models/weapons
            if (tdir.equals(family) || tdir.startsWith(family + "/")) {
                return new Pick(model, t, Reason.FAMILY, mc, tc);
            }
        }
        // 3) on-disk mirror of the model path
        RenderMap.Asset mirror = mirrorOf(model, assetsRoot);
        if (mirror != null) return new Pick(model, mirror, Reason.MIRROR, mc, tc);

        // 4) first non-effect texture
        for (RenderMap.Asset t : textures) {
            if (!usable(t, assetsRoot)) continue;
            return new Pick(model, t, Reason.FIRST, mc, tc);
        }
        return new Pick(model, null, Reason.NONE, mc, tc);
    }

    /**
     * {@code hbm:models/weapons/minigun.obj} -> {@code hbm:textures/models/weapons/minigun.png} when
     * that file exists under {@code assetsRoot}.
     */
    public static RenderMap.Asset mirrorOf(RenderMap.Asset model, Path assetsRoot) {
        if (model == null || assetsRoot == null) return null;
        String bare = model.bare();                                  // models/weapons/minigun.obj
        if (!bare.toLowerCase(Locale.ROOT).endsWith(".obj")) return null;
        String rel = "textures/" + bare.substring(0, bare.length() - 4) + ".png";
        String assetPath = "assets/" + model.namespace() + "/" + rel;
        Path p = assetsRoot.resolve(assetPath);
        return Files.isRegularFile(p)
                ? new RenderMap.Asset(model.namespace() + ":" + rel, assetPath)
                : null;
    }

    /**
     * A candidate is usable only if it is not an effect sheet AND the file really exists under
     * {@code assetsRoot}. Some renderers bind vanilla textures ({@code minecraft:textures/environment/
     * end_sky.png}) that were never extracted from the mod jar; picking one would produce a def whose
     * sprite can never exist. Passing {@code null} for the root skips the existence check.
     */
    public static boolean usable(RenderMap.Asset t, Path assetsRoot) {
        if (t == null || isEffect(t.path())) return false;
        if (assetsRoot == null) return true;
        String rel = t.assetPath() != null && !t.assetPath().isEmpty()
                ? t.assetPath()
                : "assets/" + t.namespace() + "/" + t.bare();
        return Files.isRegularFile(assetsRoot.resolve(rel));
    }

    public static boolean isEffect(String path) {
        if (path == null) return true;
        String l = path.toLowerCase(Locale.ROOT);
        for (String k : EFFECT) if (l.contains(k)) return true;
        return false;
    }

    /** {@code hbm:models/weapons/.obj} is a corrupt orphan in the jar - 25 parse errors, no name. */
    public static boolean isCorrupt(String path) {
        if (path == null) return true;
        int slash = path.lastIndexOf('/');
        String f = slash >= 0 ? path.substring(slash + 1) : path;
        return f.equals(".obj") || f.isEmpty();
    }

    private static final String PATH_OK = "abcdefghijklmnopqrstuvwxyz0123456789/._-";

    /**
     * Same rule as {@code dev.umb.hostagent.content.LegacyIds.sanitizePath} (lowercase, then any
     * character outside {@code [a-z0-9/._-]} becomes {@code _}). Duplicated rather than called so the
     * pack generator and the agent can both run without the hostagent jar on the classpath; a unit
     * test asserts the two agree on every real HBM path.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append(PATH_OK.indexOf(c) >= 0 ? c : '_');
        }
        return sb.toString();
    }
}
