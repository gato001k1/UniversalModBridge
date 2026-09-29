package dev.umb.objbridge.probe;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.bake.Fit;
import dev.umb.objbridge.bake.MeshBaker;
import dev.umb.objbridge.item.ObjItemModel;
import dev.umb.objbridge.obj.ObjMesh;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless proof that the agent's item-model registration really happens on the real client jar.
 *
 * <p>Run it with BOTH agents attached (see {@code tools/windows/probe-objbridge.ps1}); it then
 * <ol>
 *   <li>runs {@code net.minecraft.server.Bootstrap.bootStrap()} so the registries and DFU codecs
 *       exist,</li>
 *   <li>calls {@code net.minecraft.client.renderer.item.ItemModels.bootstrap()} - the method the
 *       agent patched, so this both loads the patched class and fires the hook,</li>
 *   <li>asserts {@code ItemModels.ID_MAPPER} now contains our {@code MapCodec} (via the mapper's own
 *       public {@code values()}),</li>
 *   <li>decodes a REAL generated {@code assets/hbm/items/*.json} through the real
 *       {@code ItemModels.CODEC} and asserts it dispatches to {@link ObjItemModel.Unbaked} with the
 *       right model/texture - end-to-end proof of the {@code "type": "umb:obj"} wiring,</li>
 *   <li>parses and geometry-bakes a real HBM OBJ so the mesh path is exercised too (the final
 *       {@code BakedQuad} hop needs a stitched atlas sprite, so it stays for the windowed run).</li>
 * </ol>
 *
 * <p>Exits 0 on {@code PROBE-OK}, 1 on {@code PROBE-FAIL}.
 */
public final class ObjBridgeProbe {

    private ObjBridgeProbe() { }

    public static void main(String[] args) {
        List<String> fail = new ArrayList<>();
        if (args.length < 2) {
            say("PROBE-FAIL usage: ObjBridgeProbe <pack-items-dir> <obj-file>");
            System.exit(2);
            return;
        }
        Path packItems = Paths.get(args[0]);
        Path objFile = Paths.get(args[1]);

        say("probe start; objbridge stats=" + ObjBridge.stats());

        // 1 ------------------------------------------------------------------ registries
        // SharedConstants.tryDetectVersion() MUST come first: Bootstrap's own static init reads the
        // detected version and throws ExceptionInInitializerError without it.
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            say("SharedConstants.tryDetectVersion() + Bootstrap.bootStrap() OK");
        } catch (Throwable t) {
            fail.add("Bootstrap.bootStrap(): " + t
                    + (t.getCause() != null ? " caused by " + t.getCause() : ""));
        }

        // 2 ------------------------------------------------------------------ the patched method
        try {
            ItemModels.bootstrap();
            say("ItemModels.bootstrap() OK");
        } catch (Throwable t) {
            fail.add("ItemModels.bootstrap(): " + t);
        }

        // 3 ------------------------------------------------------------------ is the type there?
        boolean present = ObjBridge.itemModelTypePresent();
        say("ID_MAPPER contains " + ObjBridge.TYPE_NAMESPACE + ":" + ObjBridge.TYPE_PATH + " -> " + present);
        if (!present) fail.add("ItemModels.ID_MAPPER does not contain umb:obj");

        // 4 ------------------------------------------------------------------ decode a real def
        try {
            Path def = pickDef(packItems);
            if (def == null) {
                say("no generated item def found under " + packItems + " - skipping codec decode");
            } else {
                String text = Files.readString(def);
                JsonObject root = new Gson().fromJson(text, JsonObject.class);
                JsonElement model = root.get("model");
                ItemModel.Unbaked unbaked = ItemModels.CODEC.parse(JsonOps.INSTANCE, model).getOrThrow();
                say("decoded " + def.getFileName() + " -> " + unbaked.getClass().getName());
                if (!(unbaked instanceof ObjItemModel.Unbaked u)) {
                    fail.add("codec produced " + unbaked.getClass().getName() + ", expected ObjItemModel$Unbaked");
                } else {
                    say("  model=" + u.model() + " texture=" + u.texture()
                            + " fit=" + u.fit() + " groups=" + u.groups());
                    if (u.model() == null || u.model().isEmpty()) fail.add("decoded model path is empty");
                    if (u.texture() == null) fail.add("decoded texture is null");
                    if (u.type() != ObjItemModel.Unbaked.MAP_CODEC) fail.add("type() is not MAP_CODEC");
                }
            }
        } catch (Throwable t) {
            fail.add("codec decode: " + t);
        }

        // 5 ------------------------------------------------------------------ real mesh geometry
        try {
            if (!Files.isRegularFile(objFile)) {
                say("no OBJ at " + objFile + " - skipping mesh bake");
            } else {
                ObjMesh mesh = ObjMesh.parse(objFile);
                MeshBaker.Result item = MeshBaker.bake(mesh, Fit.ITEM);
                MeshBaker.Result block = MeshBaker.bake(mesh, Fit.BLOCK);
                say("mesh " + objFile.getFileName() + ": verts=" + mesh.vertexCount()
                        + " uvs=" + mesh.uvCount() + " tris=" + mesh.triangleCount()
                        + " groups=" + mesh.groups().size() + " errors=" + mesh.errors().size());
                say("  item fit : quads=" + item.quads().size() + " scale=" + item.scale());
                say("  block fit: quads=" + block.quads().size() + " scale=" + block.scale());
                if (item.quads().isEmpty()) fail.add("mesh baked to 0 quads");
                float[] ext = extents(block);
                say("  block-fit bounds: x[" + ext[0] + "," + ext[3] + "] y[" + ext[1] + "," + ext[4]
                        + "] z[" + ext[2] + "," + ext[5] + "]");
                if (ext[1] < -1.0e-4f || ext[1] > 1.0e-4f) {
                    fail.add("block fit does not rest on y=0 (minY=" + ext[1] + ")");
                }
                for (int i = 0; i < 6; i++) {
                    if (ext[i] < -1.0e-4f || ext[i] > 1.0f + 1.0e-4f) {
                        fail.add("block fit escaped the unit cube: component " + i + " = " + ext[i]);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            fail.add("mesh bake: " + t);
        }

        if (fail.isEmpty()) {
            say("PROBE-OK");
            System.exit(0);
        }
        for (String f : fail) say("PROBE-FAIL " + f);
        System.exit(1);
    }

    private static float[] extents(MeshBaker.Result r) {
        float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var q : r.quads()) {
            for (int v = 0; v < 4; v++) {
                float[] p = {q.x(v), q.y(v), q.z(v)};
                for (int a = 0; a < 3; a++) {
                    if (p[a] < e[a]) e[a] = p[a];
                    if (p[a] > e[a + 3]) e[a + 3] = p[a];
                }
            }
        }
        return e;
    }

    private static Path pickDef(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return null;
        Path preferred = dir.resolve("item.gun_minigun.json");
        if (Files.isRegularFile(preferred)) return preferred;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElse(null);
        }
    }

    private static void say(String s) {
        System.out.println("[PROBE] " + s);
        System.out.flush();
    }
}
