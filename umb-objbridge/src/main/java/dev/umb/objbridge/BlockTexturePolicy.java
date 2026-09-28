package dev.umb.objbridge;

import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Block-only texture guard: block OBJ sprites must come from the extracted model atlas. */
public final class BlockTexturePolicy {
    private BlockTexturePolicy() {}

    public static TexturePick.Pick choose(List<RenderMap.Asset> models,
                                          List<RenderMap.Asset> textures,
                                          Path assetsRoot) {
        List<RenderMap.Asset> modelTextures = new ArrayList<>();
        for (RenderMap.Asset a : textures) {
            if (a != null && a.bare() != null) {
                String p = a.bare().toLowerCase(java.util.Locale.ROOT);
                if (p.startsWith("textures/models/") || p.startsWith("textures/blocks/"))
                    modelTextures.add(a);
            }
        }
        // Do not promote GUI, particle, armor, or inventory sheets into the block atlas as a
        // FIRST fallback. If there is no model texture, the row is intentionally skipped.
        return TexturePick.choose(models, modelTextures, assetsRoot);
    }
}
