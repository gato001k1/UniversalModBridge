package dev.umb.objbridge;

import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.bake.QuadGeom;
import dev.umb.objbridge.item.ObjTransforms;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import dev.umb.objbridge.obj.ObjAssets;
import dev.umb.objbridge.obj.ObjMesh;
import dev.umb.objbridge.transform.ItemDisplayTransforms;
import dev.umb.objbridge.transform.RendererTransforms;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-asset proof that fallback GUI transforms cannot recreate the multi-slot complaint. */
class GuiItemFitVerificationTest {
    private static final Path ASSETS = Path.of("research/out/legacy/hbm-assets");
    private static final Path MAP = Path.of("research/out/legacy/rendermap/hbm-render-map.json");
    private static final Path TRANSFORMS = Path.of("research/out/legacy/rendermap/renderer-transforms.json");
    private static final Path DISPLAYS = Path.of("research/out/legacy/rendermap/item-display-transforms.json");

    private record Entry(String id, List<RenderMap.Asset> models, List<RenderMap.Asset> textures) { }
    private record Audit(String id, String renderer, float[] extents, boolean exactGui) { }
    private static final List<String> TARGETS = List.of(
            "hbm:tile.machine_tower_large", "hbm:tile.launch_pad", "hbm:tile.launch_pad_large",
            "hbm:tile.launch_pad_rusted", "hbm:tile.launch_table", "hbm:tile.machine_bigasstank",
            "hbm:tile.machine_chemical_plant", "hbm:tile.machine_deuterium_tower",
            "hbm:tile.machine_fracking_tower", "hbm:tile.machine_fraction_tower",
            "hbm:tile.machine_radar_large", "hbm:tile.nuke_tsar", "hbm:tile.machine_fensu");

    @Test
    void everyObjBackedItemFitsUnlessAnExactLegacyGuiBranchIsPresent() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(ASSETS) && Files.isRegularFile(MAP)
                && Files.isRegularFile(TRANSFORMS) && Files.isRegularFile(DISPLAYS), "real audit data absent");
        RenderMap map = RenderMap.read(MAP);
        RendererTransforms rt = RendererTransforms.read(TRANSFORMS);
        ItemDisplayTransforms displays = ItemDisplayTransforms.read(DISPLAYS);
        ObjBridge.configure(ASSETS, MAP, TRANSFORMS);

        List<Entry> entries = new ArrayList<>();
        for (RenderMap.ItemRow row : map.items()) entries.add(new Entry(row.id(), row.models(), row.textures()));
        for (RenderMap.BlockRow row : map.blocks()) {
            List<RenderMap.Asset> textures = new ArrayList<>(row.textures());
            if (textures.isEmpty()) {
                RenderMap.TeRow te = map.tileEntityFor(row.id());
                if (te != null) textures.addAll(te.textures());
            }
            entries.add(new Entry(row.id(), row.models(), textures));
        }

        int objEntries = 0, audited = 0, fallback = 0, fallbackFit = 0, exactGui = 0, exactOversized = 0;
        int exactFirstPerson = 0, exactThirdPerson = 0;
        List<Audit> examples = new ArrayList<>();
        List<Audit> allAudits = new ArrayList<>();
        List<Audit> oversized = new ArrayList<>();
        for (Entry e : entries) {
            TexturePick.Pick pick = TexturePick.choose(e.models(), e.textures(), ASSETS);
            if (pick.model() == null) continue;
            objEntries++;
            ObjMesh mesh = ObjMesh.parse(ObjAssets.resolve(ASSETS, pick.model().path()));
            Fit fit = pick.spriteId() == null ? Fit.ITEM : ObjBridge.itemGeometryFit(pick.model().path(), pick.spriteId(), 1.0f);
            MeshBaker.Result baked = MeshBaker.bake(mesh, fit);
            String renderer = pick.spriteId() == null ? null
                    : ObjBridge.rendererClassForItem(pick.model().path(), pick.spriteId());
            ItemTransforms transforms = ObjTransforms.forItem(rt, displays, renderer);
            float[] initialBounds = transformedBounds(baked, transforms.gui());
            float initialMax = Math.max(initialBounds[3] - initialBounds[0],
                    Math.max(initialBounds[4] - initialBounds[1], initialBounds[5] - initialBounds[2]));
            transforms = ObjTransforms.fitGuiToSlot(transforms, initialMax);
            float[] bounds = transformedBounds(baked, transforms.gui());
            float max = Math.max(bounds[3] - bounds[0], Math.max(bounds[4] - bounds[1], bounds[5] - bounds[2]));
            boolean exact = renderer != null && displays.composedScale(renderer, "gui") != null;
            if (renderer != null && displays.composedScale(renderer, "firstperson_righthand") != null) exactFirstPerson++;
            if (renderer != null && displays.composedScale(renderer, "thirdperson_righthand") != null) exactThirdPerson++;
            audited++;
            assertTrue(max <= 1.0001f, e.id() + " emitted GUI bbox exceeds one slot: " + max
                    + " guiScale=" + transforms.gui().scale().x());
            if (exact) {
                exactGui++;
                if (max > 1.0001f) {
                    exactOversized++;
                    oversized.add(new Audit(e.id(), renderer, bounds, true));
                }
            } else {
                fallback++;
                fallbackFit++;
            }
            if (TARGETS.contains(e.id())) {
                examples.add(new Audit(e.id(), renderer, bounds, exact));
            }
            allAudits.add(new Audit(e.id(), renderer, bounds, exact));
        }
        System.out.println("[GUI-AUDIT] OBJ entries=" + objEntries + " audited=" + audited
                + " fallback=" + fallback + " fallbackFit=" + fallbackFit + " exactGui=" + exactGui
                + " exactGuiOversized=" + exactOversized + "/" + exactGui
                + " exactFirstPerson=" + exactFirstPerson + " exactThirdPerson=" + exactThirdPerson);
        for (Audit a : examples) {
            float[] b = a.extents();
            System.out.println("[GUI-AUDIT] " + a.id() + " renderer=" + a.renderer() + " exactGui="
                    + a.exactGui() + " bbox=(" + (b[3]-b[0]) + "," + (b[4]-b[1]) + "," + (b[5]-b[2]) + ")");
        }
        oversized.sort(Comparator.comparing(Audit::id).thenComparing(Audit::renderer,
                Comparator.nullsFirst(String::compareTo)));
        Collections.shuffle(allAudits, new Random(0x4755492D464954L));
        for (Audit a : allAudits.stream().limit(10).toList()) {
            float[] b = a.extents();
            System.out.println("[GUI-RANDOM-SAMPLE] " + a.id() + " renderer=" + a.renderer()
                    + " bbox=(" + (b[3]-b[0]) + "," + (b[4]-b[1]) + "," + (b[5]-b[2]) + ")");
        }
        assertTrue(objEntries > 0 && audited == objEntries);
        assertTrue(fallbackFit == fallback, "every non-exact GUI context must fit");
    }

    private static float[] transformedBounds(MeshBaker.Result baked, ItemTransform t) {
        float[] out = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Vector3f r = new Vector3f(t.rotation());
        Matrix4f rot = new Matrix4f().rotateXYZ((float) Math.toRadians(r.x()),
                (float) Math.toRadians(r.y()), (float) Math.toRadians(r.z()));
        Vector3f s = new Vector3f(t.scale());
        for (QuadGeom q : baked.quads()) for (int i = 0; i < 4; i++) {
            Vector3f p = new Vector3f(q.x(i), q.y(i), q.z(i));
            rot.transformPosition(p).mul(s);
            out[0] = Math.min(out[0], p.x); out[1] = Math.min(out[1], p.y); out[2] = Math.min(out[2], p.z);
            out[3] = Math.max(out[3], p.x); out[4] = Math.max(out[4], p.y); out[5] = Math.max(out[5], p.z);
        }
        return out;
    }
}
