package dev.umb.objbridge;

import dev.umb.objbridge.patch.ItemModelsPatcher;
import dev.umb.objbridge.patch.ModelManagerPatcher;
import dev.umb.objbridge.patch.EntityRenderersPatcher;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SECOND -javaagent. Deliberately independent of {@code umb-hostagent}: it touches no class the
 * host agent touches, shares no state with it, and can be attached before or after it.
 *
 * <p>Args, {@code ';'}-separated {@code k=v}:
 * <pre>
 *   rendermap=research\out\legacy\rendermap\hbm-render-map.json
 *   assets=research\out\legacy\hbm-assets
 *   transforms=research\out\legacy\rendermap\renderer-transforms.json   (optional, has a default)
 *   log=...\logs\objbridge.log
 * </pre>
 *
 * <p>{@code transforms} defaults to {@code research/out/legacy/rendermap/renderer-transforms.json}
 * (relative to the process cwd, which is always the repo root for every launcher this lane ships) when
 * not given, so existing launch scripts that predate this argument keep working unchanged.
 *
 * <p>Two transformers, two hooks; everything else happens on the game's own threads:
 * {@code ItemModels.bootstrap()} -> {@link ObjBridge#registerItemModelType()} and
 * {@code ModelManager.apply(..)} -> {@link ObjBridge#onModelsApplied}.
 */
public final class ObjBridgeAgent {

    private ObjBridgeAgent() { }

    public static void premain(String args, Instrumentation inst) {
        Map<String, String> kv = parse(args);
        Path logPath = Paths.get(kv.getOrDefault("log", "umb-objbridge.log"));
        ObjLog.open(logPath);
        ObjLog.line("premain args=" + args);

        if (kv.containsKey("manifest")) {
            Path manifest = path(kv.get("manifest"));
            try {
                ObjBridge.configureManifest(manifest);
                ObjLog.line("manifest=" + manifest);
            } catch (Throwable t) {
                ObjLog.loud("MANIFEST-FAILED " + t);
                ObjLog.error("manifest", t, 8);
                throw new IllegalStateException("OBJ bridge manifest rejected: " + manifest, t);
            }
        } else {
            Path assets = path(kv.get("assets"));
            Path map = path(kv.get("rendermap"));
            Path transforms = kv.containsKey("transforms") ? path(kv.get("transforms"))
                    : Paths.get("research/out/legacy/rendermap/renderer-transforms.json");
            ObjBridge.configure(assets, map, transforms);
            ObjLog.line("assets=" + assets + " rendermap=" + map + " transforms=" + transforms);
        }

        try {
            inst.addTransformer(new ItemModelsPatcher());
            inst.addTransformer(new ModelManagerPatcher());
            inst.addTransformer(new EntityRenderersPatcher());
            ObjLog.line("transformers installed (ItemModels, ModelManager, EntityRenderers)");
        } catch (Throwable t) {
            ObjLog.loud("PATCH-FAILED addTransformer: " + t);
            ObjLog.error("premain.addTransformer", t, 5);
        }
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static Path path(String s) {
        return (s == null || s.isEmpty()) ? null : Paths.get(s);
    }

    static Map<String, String> parse(String args) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) return kv;
        for (String part : args.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) kv.put(p, "");
            else kv.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return kv;
    }
}
