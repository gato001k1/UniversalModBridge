package dev.umb.hostagent;

import dev.umb.hostagent.content.LegacyIds;
import net.minecraft.resources.Identifier;

/** Normalizes legacy GL-EMU bind strings to host-resolvable identifiers. */
final class LegacyGuiTextureResolver {
    private LegacyGuiTextureResolver() {
    }

    /**
     * Era tag for the private legacy namespaces. Each legacy era module exports its own
     * vanilla sheets and stitched atlases; the painter derives the era per mesh (mod
     * namespace routing, 1.7.10 default) so one shared replay path serves every era.
     */
    static String eraTag(String era) {
        if ("1.12.2".equals(era)) return "1122";
        if ("1.16.5".equals(era)) return "1165";
        return "1710";
    }

    static Identifier resolve(String raw) {
        return resolve(raw, "1.7.10");
    }

    static Identifier resolve(String raw, String era) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String value = raw.trim().replace('\\', '/');
        int colon = value.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : value.substring(0, colon);
        String path = colon < 0 ? value : value.substring(colon + 1);
        if (path.startsWith("/")) path = path.substring(1);
        String tag = eraTag(era);
        if (path.equals("textures/atlas/items.png")) {
            // Stitched legacy atlas, exported beside the game by the legacy side and served
            // as a host dynamic texture. Falls back to the bounded color fill when absent.
            return Identifier.tryParse("umbatlas:" + tag + "/items.png");
        }
        if (path.equals("textures/atlas/blocks.png")) {
            return Identifier.tryParse("umbatlas:" + tag + "/blocks.png");
        }
        if (path.startsWith("textures/atlas/")) return null;
        if ("minecraft".equals(LegacyIds.sanitizeNamespace(namespace))
                && path.startsWith("textures/gui/")) {
            // Legacy vanilla GUI sheets (buttons, containers, icons): 26.2 replaced them
            // with sprites, so the minecraft-namespace id would 404. The legacy side
            // exports the era's own pixels beside the game; serve those instead.
            return Identifier.tryParse("umbvanilla" + tag + ":"
                    + LegacyIds.sanitizePath(path));
        }
        return Identifier.tryParse(LegacyIds.sanitizeNamespace(namespace) + ":"
                + LegacyIds.sanitizePath(path));
    }
}
