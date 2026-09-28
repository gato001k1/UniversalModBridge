package dev.umb.objbridge.entity;

import dev.umb.objbridge.ObjBridgeManifest;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.map.TexturePick;
import java.nio.file.Path;
import java.util.*;

/** One legacy block's TESR visual, resolved only through the rendermap's exact linkage. */
public record LegacyBlockVisual(String blockId, String rendererClass, String model, String texture, List<String> groups) {
    private static volatile Map<String, LegacyBlockVisual> CACHE;

    /**
     * Resolve a visual for the native block id, with the legacy id as an explicit alias.
     *
     * <p>Hostagent shape-only twins have their own 26.2 registry id (for example
     * {@code hbm:tile.machine_flare_13}) but deliberately retain the base legacy id on the
     * block instance.  The rendermap has one row for the base legacy block, not one guessed row
     * per synthetic twin, so this fallback is the only data-backed lookup that can be correct.
     * A missing alias remains a counted miss at the renderer call site.</p>
     */
    static LegacyBlockVisual find(Map<String, LegacyBlockVisual> visuals,
                                  String nativeId, String legacyId) {
        if (visuals == null) return null;
        LegacyBlockVisual direct = nativeId == null ? null : visuals.get(nativeId);
        if (direct != null) return direct;
        return legacyId == null ? null : visuals.get(legacyId);
    }

    public static Map<String, LegacyBlockVisual> all() {
        if (CACHE != null) return CACHE;
        Map<String, LegacyBlockVisual> out = new LinkedHashMap<>(); int rows=0,resolved=0;
        for (ObjBridgeManifest.Loaded mod : dev.umb.objbridge.ObjBridge.loadedMods()) {
                RenderMap map=mod.renderMap(); Path assets=mod.mod().assetsRoot();
            for (RenderMap.BlockRow row: map.blocks()) {
                if(row.id()==null || !row.id().startsWith(mod.mod().namespace()+":")) continue; rows++;
                // Exact block -> TE linkage only. A wrong model is worse than an honest skip.
                RenderMap.TeRow te=map.tileEntityFor(row.id());
                if (te == null && row.tileEntityClass() != null) {
                    // The block row's exact tileEntityClass is authoritative even when the
                    // reverse blockIds list was absent from an older/partial extraction.
                    te = map.tileEntityForClass(row.tileEntityClass());
                }
                List<RenderMap.Asset> ms=new ArrayList<>(row.models()), ts=new ArrayList<>(row.textures()); List<String> gs=new ArrayList<>(row.groups()); String rc=row.tesrClass();
                if(te!=null){if(ms.isEmpty())ms.addAll(te.models());if(ts.isEmpty())ts.addAll(te.textures());if(gs.isEmpty())gs.addAll(te.groups());if(rc==null)rc=te.rendererClass();}
                TexturePick.Pick p=TexturePick.choose(ms,ts,assets); // unresolved rows are skipped and counted
                if(p.resolved()){out.put(row.id(),new LegacyBlockVisual(row.id(),rc,p.model().path(),p.texture().path(),List.copyOf(gs)));resolved++;}
            }
        }
        System.out.println("[UMB-OBJBRIDGE] BLOCK-ENTITY-RENDERER visuals rows="+rows+" resolved="+resolved+" skipped="+(rows-resolved));
        return CACHE=Map.copyOf(out);
    }
}
