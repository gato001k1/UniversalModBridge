package dev.umb.objbridge.item;

import com.google.gson.JsonParser;
import dev.umb.objbridge.transform.RendererTransforms;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjTransformsTest {

    private static RendererTransforms rt(String json) {
        return RendererTransforms.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void unknownClassFallsBackToVanillaBlockItemTransforms() {
        RendererTransforms transforms = rt("{ \"renderers\": {} }");
        ItemTransforms t = ObjTransforms.forItem(transforms, "com.example.Unknown");
        assertTrue(t != ObjTransforms.BLOCK_ITEM);
        assertEquals(0.5f, t.gui().scale().x(), 1e-6f);
    }

    @Test
    void inventoryScaledUpRelativeToFirstPersonDoesNotBorrowCoarseGuiScaleWithoutExactBranch() {
        // com.hbm.render.item.weapon.sedna.ItemRenderCoilgun's real shape: setupInv=4.0 (up),
        // renderFirstPerson=0.75 (down), setupThirdPerson=3.0 (up) - see laneScale-progress.md.
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Coilgun": [
                    {"method":"renderFirstPerson","op":"glScaled","args":[0.75,0.75,0.75],"dynamic":false},
                    {"method":"setupThirdPerson","op":"glScaled","args":[3,3,3],"dynamic":false},
                    {"method":"setupInv","op":"glScaled","args":[4,4,4],"dynamic":false}
                ]}}
                """);
        ItemTransforms t = ObjTransforms.forItem(transforms, "com.example.Coilgun");
        assertTrue(t != ObjTransforms.BLOCK_ITEM, "must NOT be the plain fallback - a transform resolved");

        float guiScale = t.gui().scale().x();
        float fpScale = t.firstPersonRightHand().scale().x();
        float tpScale = t.thirdPersonRightHand().scale().x();

        assertEquals(4.0f, guiScale, 1e-6f,
                "the resolved legacy inventory scale is absolute, before the post-bake slot fit");
        assertTrue(guiScale > fpScale, "the renderer still scales the GUI above its tiny first-person scale - "
                + "gui=" + guiScale + " fp=" + fpScale);
        assertTrue(tpScale > fpScale, "third person is also scaled up relative to first person - "
                + "tp=" + tpScale + " fp=" + fpScale);

        assertEquals(0.75f, fpScale, 1e-6f);
        assertEquals(3.0f, tpScale, 1e-6f);
    }

    @Test
    void classWithNoUsablePerspectiveBucketFallsBackToVanilla() {
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.WorldOnly": [
                    {"method":"func_147500_a","op":"glRotatef","args":[90,0,1,0],"dynamic":false}
                ]}}
                """);
        ItemTransforms t = ObjTransforms.forItem(transforms, "com.example.WorldOnly");
        assertTrue(t != ObjTransforms.BLOCK_ITEM);
        assertEquals(0.5f, t.gui().scale().x(), 1e-6f);
    }

    @Test
    void thirdPersonFallsBackToCommonWhenNotSpecificallyDifferentiated() {
        // com.hbm.render.tileentity.RenderRadarLarge$1's real shape (laneInv-progress.md): renderInventory
        // scales up (3.0), renderCommonWithStack scales down (0.5), no setupThirdPerson/setupFirstPerson
        // at all. Before this lane's common-fallback fix, third-person stayed at flat vanilla (0.375)
        // even though the renderer's own common step clearly says "this should look smaller overall."
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.RadarItem": [
                    {"method":"renderInventory","op":"glScaled","args":[3.0,3.0,3.0],"dynamic":false},
                    {"method":"renderCommonWithStack","op":"glScaled","args":[0.5,0.5,0.5],"dynamic":false}
                ]}}
                """);
        ItemTransforms t = ObjTransforms.forItem(transforms, "com.example.RadarItem");
        assertTrue(t != ObjTransforms.BLOCK_ITEM);
        float tpScale = t.thirdPersonRightHand().scale().x();
        assertEquals(0.5f, tpScale, 1e-6f,
                "a common legacy scale is used directly when no perspective override exists");
        assertEquals(0.5f, t.firstPersonRightHand().scale().x(), 1e-6f);
    }

    @Test
    void rotationAndTranslationAreKeptAtTheVanillaBlockItemFraming() {
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Thing": [
                    {"method":"setupInv","op":"glScaled","args":[4,4,4],"dynamic":false},
                    {"method":"renderFirstPerson","op":"glScaled","args":[0.5,0.5,0.5],"dynamic":false}
                ]}}
                """);
        ItemTransforms t = ObjTransforms.forItem(transforms, "com.example.Thing");
        assertEquals(ObjTransforms.BLOCK_ITEM.gui().rotation(), t.gui().rotation());
        assertEquals(ObjTransforms.BLOCK_ITEM.gui().translation(), t.gui().translation());
    }

    /** Tiny reflective peek at the vanilla GUI scale constant, just for the sanity-clamp bound check. */
    private static final class ObjTransformsAccess {
        static float GUI_SCALE() {
            Vector3fc s = ObjTransforms.BLOCK_ITEM.gui().scale();
            return s.x();
        }
    }
}
