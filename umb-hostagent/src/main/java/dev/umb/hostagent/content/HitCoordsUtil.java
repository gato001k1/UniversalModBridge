package dev.umb.hostagent.content;

/**
 * FM-1: 26.2 hit locations ({@code BlockHitResult.getLocation()} / {@code UseOnContext.getClickLocation()})
 * are absolute world coordinates; 1.7.10's block/item interaction callbacks want them block-relative
 * floats in 0..1. Shared by {@link UmbLegacyBlock} (onBlockActivated) and {@link UmbLegacyItem}
 * (onItemUse) so the exact same clamp-at-face-boundary rounding applies to both.
 */
final class HitCoordsUtil {

    private HitCoordsUtil() {
    }

    static float relative(double absoluteCoord, int blockCoord) {
        float r = (float) (absoluteCoord - blockCoord);
        if (r < 0f) return 0f;
        if (r > 1f) return 1f;
        return r;
    }
}
