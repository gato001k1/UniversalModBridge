package dev.umb.objbridge.transform;

import java.util.Locale;

/**
 * Which legacy render path a {@link TransformOp}'s {@code method} belongs to, classified by name.
 *
 * <p>The 1.7.10 conventions this is built from (all confirmed against the actual renderer classes
 * named in this lane's progress notes):
 * <ul>
 *   <li>{@code WORLD} - {@code TileEntitySpecialRenderer.renderTileEntityAt} (obfuscated
 *       {@code func_147500_a}) and {@code Render.doRender} (entity in-world draw) - a block/entity
 *       drawn where it stands, one Minecraft unit = one block,</li>
 *   <li>{@code INVENTORY} - {@code IItemRenderer} {@code ItemRenderType.INVENTORY} helpers
 *       ({@code renderInventory}, {@code setupInv}) and the weapon mod-table preview
 *       ({@code setupModTable}, itself a GUI-like display context),</li>
 *   <li>{@code FIRST_PERSON} - {@code ItemRenderType.EQUIPPED_FIRST_PERSON} helpers
 *       ({@code setupFirstPerson}, {@code renderFirstPerson}),</li>
 *   <li>{@code THIRD_PERSON} - {@code ItemRenderType.EQUIPPED} as seen by other players
 *       ({@code setupThirdPerson}),</li>
 *   <li>{@code COMMON} - a shared step applied across several {@code ItemRenderType}s
 *       ({@code renderCommon}/{@code renderCommonWithStack}), typically the dropped/equipped case,</li>
 *   <li>{@code OTHER} - anything else ({@code renderOther}, unnamed helpers).</li>
 * </ul>
 *
 * <p>See {@code dev.umb.objbridge.item.ObjTransforms}'s class docs for the javap-verified mapping of
 * these onto 26.2's real {@code net.minecraft.world.item.ItemDisplayContext} constants (confirmed via
 * {@code javap -p} against {@code research/jars/26.2/client.jar}: {@code GUI}, {@code
 * FIRST_PERSON_LEFT_HAND}/{@code FIRST_PERSON_RIGHT_HAND}, {@code THIRD_PERSON_LEFT_HAND}/{@code
 * THIRD_PERSON_RIGHT_HAND}, plus {@code GROUND}/{@code FIXED}/{@code ON_SHELF}/{@code HEAD}/{@code NONE}
 * which have no 1.7.10 {@code IItemRenderer} counterpart at all) - {@code WORLD} has no display-context
 * equivalent since it is the in-world TESR/entity draw, not an item perspective.
 */
public enum PathClass {
    WORLD, INVENTORY, FIRST_PERSON, THIRD_PERSON, COMMON, OTHER;

    public static PathClass classify(String method) {
        if (method == null || method.isEmpty()) return OTHER;
        String m = method.toLowerCase(Locale.ROOT);
        if (m.equals("func_147500_a") || m.contains("tileentityat") || m.contains("dorender")) return WORLD;
        if (m.contains("firstperson")) return FIRST_PERSON;
        if (m.contains("thirdperson")) return THIRD_PERSON;
        if (m.contains("inventory") || m.contains("setupinv") || m.contains("modtable")) return INVENTORY;
        if (m.contains("common")) return COMMON;
        return OTHER;
    }
}
