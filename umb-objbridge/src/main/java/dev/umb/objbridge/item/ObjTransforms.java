package dev.umb.objbridge.item;

import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.transform.ItemPerspectiveRatio;
import dev.umb.objbridge.transform.RendererTransforms;
import dev.umb.objbridge.transform.ItemDisplayTransforms;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The vanilla block-item display transforms, hand-built from the shipped
 * {@code assets/minecraft/models/block/block.json} in {@code research/jars/26.2/client.jar}.
 *
 * <p>Reproduces {@code ItemTransforms$Deserializer.deserialize} exactly (javap-verified):
 * <ul>
 *   <li>ctor order is {@code (tpLeft, tpRight, fpLeft, fpRight, head, gui, ground, fixed, onShelf)},</li>
 *   <li>an absent {@code thirdperson_lefthand}/{@code firstperson_lefthand} falls back to the
 *       corresponding right-hand entry (the deserializer does exactly that
 *       {@code if_acmpne NO_TRANSFORM} check),</li>
 *   <li>translations are multiplied by {@code 0.0625} and clamped to +-5,</li>
 *   <li>scales are clamped to +-4.</li>
 * </ul>
 *
 * <p>block.json's display block, verbatim:
 * <pre>
 * gui                   rotation [30,225,0]  translation [0,0,0]    scale 0.625
 * ground                rotation [0,0,0]     translation [0,3,0]    scale 0.25
 * fixed                 rotation [0,0,0]     translation [0,0,0]    scale 0.5
 * on_shelf              rotation [0,180,0]   translation [0,0,0]    scale 1
 * thirdperson_righthand rotation [75,45,0]   translation [0,2.5,0]  scale 0.375
 * firstperson_righthand rotation [0,45,0]    translation [0,0,0]    scale 0.40
 * firstperson_lefthand  rotation [0,225,0]   translation [0,0,0]    scale 0.40
 * (no head, no thirdperson_lefthand)
 * </pre>
 *
 * <h2>1.7.10 {@code ItemRenderType} to 26.2 {@code ItemDisplayContext}, javap-verified</h2>
 * {@code javap -p net.minecraft.world.item.ItemDisplayContext} against {@code research/jars/26.2/
 * client.jar} lists exactly: {@code NONE, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND,
 * FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, HEAD, GUI, GROUND, FIXED, ON_SHELF} - the same nine
 * (plus {@code NONE}) {@code ItemTransforms} keys used above, confirming
 * {@code ItemTransforms.getTransform(ItemDisplayContext)} is a straight 1:1 lookup (also javap-verified:
 * a single {@code lookupswitch} on {@code ItemDisplayContext.ordinal()}, no other logic). So
 * {@link dev.umb.objbridge.transform.PathClass} maps onto it as:
 * <ul>
 *   <li>{@code PathClass.INVENTORY} ({@code renderInventory}/{@code setupInv}/{@code setupModTable}) -
 *       {@code GUI},</li>
 *   <li>{@code PathClass.FIRST_PERSON} ({@code setupFirstPerson}/{@code renderFirstPerson}) -
 *       {@code FIRST_PERSON_LEFT_HAND}/{@code FIRST_PERSON_RIGHT_HAND},</li>
 *   <li>{@code PathClass.THIRD_PERSON} ({@code setupThirdPerson}) -
 *       {@code THIRD_PERSON_LEFT_HAND}/{@code THIRD_PERSON_RIGHT_HAND},</li>
 *   <li>{@code PathClass.COMMON} ({@code renderCommon}/{@code renderCommonWithStack}) - no single
 *       display context; 1.7.10 called it for several {@code ItemRenderType}s a given renderer does not
 *       specially differentiate, so it is used here as the EFFECTIVE value for whichever of
 *       GUI/first-person/third-person the renderer has no more specific op for (see {@link #forItem}),</li>
 *   <li>{@code PathClass.WORLD} ({@code func_147500_a}/{@code doRender}) - no {@code ItemDisplayContext}
 *       equivalent at all; it is the TESR/entity in-world draw, block-local space, handled entirely by
 *       {@code dev.umb.objbridge.transform.RenderFit}/{@code MeshBaker} for the placed block (and, via
 *       {@link dev.umb.objbridge.ObjBridge#itemGeometryFit}, for shaping a block's OWN held/inventory
 *       geometry - see the block-item-in-slot rule there) - never for a perspective SCALE here,</li>
 *   <li>{@code GROUND}/{@code FIXED}/{@code ON_SHELF}/{@code HEAD} have no 1.7.10 {@code IItemRenderer}
 *       counterpart at all (dropped-on-ground, item-frame, armor-stand-head items were not
 *       renderer-hookable in 1.7.10) - always the flat vanilla {@link #GROUND}/{@link #FIXED}/
 *       {@link #ON_SHELF}/{@code NO_TRANSFORM}.</li>
 * </ul>
 *
 * <h2>Vanilla's own oversized-item mechanism, javap-verified</h2>
 * 26.2 ships a real, sanctioned way for a GUI item icon to overflow its 16x16 slot instead of being
 * clipped or squashed: {@code ItemStackRenderState.setOversizedInGui(boolean)} /
 * {@code isOversizedInGui()} (javap-verified fields/accessors), read by
 * {@code GuiItemRenderState}'s constructor (javap {@code -c}: {@code if (itemStackRenderState()
 * .isOversizedInGui()) oversizedItemBounds = calculateOversizedItemBounds(); else oversizedItemBounds =
 * null;}), which routes the model through {@code net.minecraft.client.gui.render.pip
 * .OversizedItemRenderer} - a picture-in-picture renderer that draws the item into its own texture sized
 * from {@code TrackingItemStackRenderState.getModelBoundingBox()} instead of the fixed 16x16 icon quad.
 * Critically (javap {@code -c} of {@code GuiItemRenderState.calculateOversizedItemBounds}), when the
 * model's actual bbox rounds to <=16x16 pixels in BOTH dimensions even though the flag is set, this
 * method returns {@code null} and the item falls straight through to the ordinary fast path - so setting
 * the flag generously is safe; it only changes anything for models that truly overflow.
 * {@code isOversizedInGui()} is set from {@code ClientItem$Properties.oversizedInGui()} (javap {@code -c}
 * of {@code ItemModelResolver.appendItemLayers}: {@code getItemProperties(id).oversizedInGui()} feeds
 * {@code ItemStackRenderState.setOversizedInGui} directly, BEFORE the {@code ItemModel} is even baked) -
 * i.e. it is a DATA-DRIVEN field of the top-level client-item JSON ({@code "oversized_in_gui": true},
 * sibling of {@code "model"}, codec-default {@code false} - javap of {@code ClientItem$Properties}'s
 * static codec initialiser names the JSON key literally), not something this class or
 * {@code ObjItemModel} can set at bake time. See {@code dev.umb.objbridge.gen.ObjPackGen#itemDef} for
 * where the overlay pack actually emits it, computed from this class's own derived GUI scale.
 */
public final class ObjTransforms {

    private ObjTransforms() { }

    private static ItemTransform t(float rx, float ry, float rz,
                                   float tx, float ty, float tz, float s) {
        return new ItemTransform(
                new Vector3f(rx, ry, rz),
                new Vector3f(tx * 0.0625f, ty * 0.0625f, tz * 0.0625f),
                new Vector3f(s, s, s));
    }

    private static final ItemTransform GUI = t(30, 225, 0, 0, 0, 0, 0.625f);
    /** Initial GUI scale; fitGuiToSlot raises this to the vanilla block-item footprint per mesh. */
    private static final ItemTransform GUI_FIT_FALLBACK = t(30, 225, 0, 0, 0, 0, 0.5f);
    private static final float GUI_TARGET_FRACTION = 0.85f;
    private static final ItemTransform GROUND = t(0, 0, 0, 0, 3, 0, 0.25f);
    private static final ItemTransform FIXED = t(0, 0, 0, 0, 0, 0, 0.5f);
    private static final ItemTransform ON_SHELF = t(0, 180, 0, 0, 0, 0, 1.0f);
    private static final ItemTransform TP_RIGHT = t(75, 45, 0, 0, 2.5f, 0, 0.375f);
    private static final ItemTransform FP_RIGHT = t(0, 45, 0, 0, 0, 0, 0.40f);
    private static final ItemTransform FP_LEFT = t(0, 225, 0, 0, 0, 0, 0.40f);

    /** Exactly what {@code minecraft:block/block} produces after deserialization. */
    public static final ItemTransforms BLOCK_ITEM = new ItemTransforms(
            TP_RIGHT,                      // thirdPersonLeftHand  <- absent in JSON, falls back to right
            TP_RIGHT,                      // thirdPersonRightHand
            FP_LEFT,                       // firstPersonLeftHand
            FP_RIGHT,                      // firstPersonRightHand
            ItemTransform.NO_TRANSFORM,    // head                 <- absent in JSON
            GUI,
            GROUND,
            FIXED,
            ON_SHELF                       // fixedFromBottom == the "on_shelf" display key
    );

    private static final ItemTransforms GUI_FIT_ITEM = new ItemTransforms(
            TP_RIGHT, TP_RIGHT, FP_LEFT, FP_RIGHT, ItemTransform.NO_TRANSFORM,
            GUI_FIT_FALLBACK, GROUND, FIXED, ON_SHELF);

    // ------------------------------------------------------------------ per-item scale wiring

    /**
     * Replays the legacy renderer's absolute scale in each supported perspective. The OBJ mesh is
     * first converted from the legacy renderer's 1/16 model-unit space by
     * {@link ObjBridge#itemGeometryFit}; the extracted {@code glScaled} values therefore remain
     * meaningful instead of being normalized away by the old one-block auto-fit. Missing paths use
     * the common scale, and unresolved renderers retain the vanilla fallback. Rotation and
     * translation stay at the vanilla block-item framing; only scale is supplied by legacy data.
     */
    private static final AtomicInteger RESOLVED = new AtomicInteger();
    private static final AtomicInteger FALLBACK = new AtomicInteger();

    public static int resolvedCount() { return RESOLVED.get(); }
    public static int fallbackCount() { return FALLBACK.get(); }

    /**
     * Fits the actual baked mesh to a useful vanilla-sized GUI footprint.  The old fixed 0.5
     * fallback was safe but visibly too small; vanilla block/block.json uses GUI scale 0.625.
     * We target 0.85 of the slot's largest axis (near the vanilla block-item footprint), while
     * preserving a hard maximum of 1.0.  This must run after baking because OBJ aspect ratios and
     * the GUI rotation determine the projected bounding box.
     */
    public static ItemTransforms fitGuiToSlot(ItemTransforms transforms, List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) return transforms;
        ItemTransform gui = transforms.gui();
        float current = transformedExtent(quads, gui);
        return fitGuiToSlot(transforms, current);
    }

    /** Same fit operation for the headless audit, which measures QuadGeom before atlas baking. */
    public static ItemTransforms fitGuiToSlot(ItemTransforms transforms, float current) {
        if (!(current > 1.0e-6f) || !Float.isFinite(current)) return transforms;
        ItemTransform gui = transforms.gui();
        float factor = GUI_TARGET_FRACTION / current;
        float safeFactor = Math.min(factor, 1.0f / current);
        Vector3f scale = new Vector3f(gui.scale()).mul(safeFactor);
        ItemTransform fitted = new ItemTransform(gui.rotation(), gui.translation(), scale);
        return new ItemTransforms(transforms.thirdPersonLeftHand(), transforms.thirdPersonRightHand(),
                transforms.firstPersonLeftHand(), transforms.firstPersonRightHand(), transforms.head(),
                fitted, transforms.ground(), transforms.fixed(), transforms.fixedFromBottom());
    }

    private static float transformedExtent(List<BakedQuad> quads, ItemTransform gui) {
        Vector3f r = new Vector3f(gui.rotation());
        Matrix4f rot = new Matrix4f().rotateXYZ((float) Math.toRadians(r.x()),
                (float) Math.toRadians(r.y()), (float) Math.toRadians(r.z()));
        Vector3f scale = new Vector3f(gui.scale());
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (BakedQuad q : quads) for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            Vector3fc v = q.position(i);
            Vector3f p = new Vector3f(v);
            rot.transformPosition(p).mul(scale);
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    public static ItemTransforms forItem(String modelPath, String spriteId) {
        String rendererClass = ObjBridge.rendererClassForItem(modelPath, spriteId);
        RendererTransforms rt = ObjBridge.rendererTransforms();
        if (rendererClass == null || !rt.hasClass(rendererClass)) {
            FALLBACK.incrementAndGet();
            return GUI_FIT_ITEM;
        }
        return forItem(rt, ObjBridge.itemDisplayTransforms(), rendererClass);
    }

    /**
     * Split out so it can be unit-tested against a hand-built {@link RendererTransforms} directly. The
     * bucket/ratio math itself lives in {@link ItemPerspectiveRatio} (pure, {@code net.minecraft}-free)
     * so {@code dev.umb.objbridge.gen.ObjPackGen} - which decides the pack's data-driven
     * {@code "oversized_in_gui"} flag at generation time, see its {@code itemDef} - computes EXACTLY the
     * same GUI scale this method will produce at bake time, without needing {@code ItemTransform} (and
     * therefore {@code client.jar}) on its classpath.
     */
    public static ItemTransforms forItem(RendererTransforms rt, String rendererClass) {
        return forItem(rt, ItemDisplayTransforms.empty(), rendererClass);
    }

    public static ItemTransforms forItem(RendererTransforms rt, ItemDisplayTransforms displays,
                                         String rendererClass) {
        ItemPerspectiveRatio.Buckets b = ItemPerspectiveRatio.buckets(rt, rendererClass);
        Float guiBranchScale = displays.composedScale(rendererClass, "gui");
        Float fpBranchScale = displays.composedScale(rendererClass, "firstperson_righthand");
        Float tpBranchScale = displays.composedScale(rendererClass, "thirdperson_righthand");
        // A coarse setupInv/renderCommon reading is not proof that the renderer changes GUI size:
        // it may be shared code, a held-only helper, or code reached before the ItemRenderType
        // dispatch.  More importantly, even a literal GUI branch is not allowed to enlarge an
        // auto-fit OBJ beyond the slot unless a legacy overflow was hand-verified.  The current
        // corpus has no such verified exception, so extracted GUI data is retained for audit and
        // counting while the emitted GUI transform is always the conservative fit transform.
        if (fpBranchScale != null || tpBranchScale != null)
            b = new ItemPerspectiveRatio.Buckets(guiBranchScale, fpBranchScale != null ? fpBranchScale : b.fp(),
                    tpBranchScale != null ? tpBranchScale : b.tp(), b.common());
        if (b.found() == 0) {
            FALLBACK.incrementAndGet();
            return GUI_FIT_ITEM;
        }
        RESOLVED.incrementAndGet();
        float fpScale = ItemPerspectiveRatio.absoluteScale(b.fp(), b.common(), FP_RIGHT.scale().x());
        float tpScale = ItemPerspectiveRatio.absoluteScale(b.tp(), b.common(), TP_RIGHT.scale().x());
        float invScale = ItemPerspectiveRatio.absoluteScale(b.inv(), b.common(), GUI_FIT_FALLBACK.scale().x());

        // COMMON is "a step applied across several ItemRenderType helpers" (PathClass docs) - i.e. for
        // whichever of INVENTORY/FIRST_PERSON/THIRD_PERSON a renderer does NOT specially differentiate,
        // COMMON is literally the value that path uses too. Substituting it in as each missing bucket's
        // EFFECTIVE value (not as an extra data point folded into `baseline` above, which would double
        // count it) means a renderer that differentiates ONLY inventory (e.g. setupInv present, nothing
        // else) still shapes first-person/third-person by its common step instead of silently staying at
        // flat vanilla scale for those two perspectives - measured against real armor/gun classes in
        // renderer-transforms.json that carry a `renderCommon` scale but no per-type override at all for
        // one or two of the three (laneInv-progress.md has the worked radar-item numbers).
        // The GUI value is also absolute legacy renderer scale. fitGuiToSlot() remains the final
        // slot-safety step after baking, so an unusually large authored mesh cannot clip the icon.
        ItemTransform gui = scaled(GUI_FIT_FALLBACK, invScale);
        ItemTransform tpRight = scaled(TP_RIGHT, tpScale);
        ItemTransform fpRight = scaled(FP_RIGHT, fpScale);
        ItemTransform fpLeft = scaled(FP_LEFT, fpScale);

        return new ItemTransforms(tpRight, tpRight, fpLeft, fpRight, ItemTransform.NO_TRANSFORM,
                gui, GROUND, FIXED, ON_SHELF);
    }

    private static ItemTransform scaled(ItemTransform vanilla, float scaleValue) {
        if (!Float.isFinite(scaleValue) || scaleValue <= 1.0e-4f) return vanilla;
        Vector3f scale = new Vector3f(scaleValue, scaleValue, scaleValue);
        return new ItemTransform(vanilla.rotation(), vanilla.translation(), scale);
    }
}
