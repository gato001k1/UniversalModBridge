package dev.umb.hostagent.probe;

import dev.umb.hostagent.CreativePaging;
import dev.umb.hostagent.HostAgent;
import dev.umb.hostagent.content.BlockShapeProfile;
import dev.umb.hostagent.content.LangTable;
import dev.umb.hostagent.content.LegacyIds;
import dev.umb.hostagent.content.LegacySnapshot;
import dev.umb.hostagent.content.Registrar;
import dev.umb.hostagent.content.VariantPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless proof that the agent really registers legacy content into the live 26.2 registries
 * before they freeze. No window, no GL - just vanilla's own bootstrap.
 *
 * Run under -javaagent:umb-hostagent.jar=snapshot=...;log=... so the transformer is installed.
 * Exits 0 on PROBE-OK, 1 on PROBE-FAIL.
 *
 * It rebuilds the SAME {@link VariantPlan} the agent used and demands every entry of it, so the
 * 1.13-style metadata flattening (2163 item variants + 273 block variants for HBM) is gated here
 * rather than discovered in a window.
 */
public final class BootstrapProbe {

    private static final double THRESHOLD = 0.95;

    public static void main(String[] args) {
        // Snapshot path comes from the caller (tools/probe-hostagent.ps1 passes it explicitly);
        // no mod-specific default lives here. The -Dumb.snapshot system property (highest
        // precedence) exists for one-off manual runs.
        String cliSnap = args.length > 0 ? args[0] : null;
        String snapArg = System.getProperty("umb.snapshot", cliSnap);
        if (snapArg == null || snapArg.isEmpty()) {
            System.out.println("PROBE-FAIL usage: BootstrapProbe <snapshot.json> "
                    + "(-Dumb.snapshot=... also accepted)");
            System.exit(2);
            return;
        }
        // GENERALITY fix (laneCasing, Bug 1): `rawNs` is whatever the legacy modid actually looks
        // like (used to filter the snapshot's legacy ids - LegacySnapshot.matches is now
        // case-insensitive, but the ids it RETURNS stay verbatim), while `ns` is the sanitized,
        // Identifier-safe form Registrar actually registered blocks/items/tabs under. The two used
        // to be the same variable, which only worked because "hbm" is already lowercase.
        String rawNs = System.getProperty("umb.ns", HostAgent.namespace());
        String ns = LegacyIds.sanitizeNamespace(rawNs);

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();

        Path snapshot = HostAgent.snapshotPath() != null ? HostAgent.snapshotPath() : Paths.get(snapArg);
        LegacySnapshot snap;
        try {
            snap = LegacySnapshot.load(snapshot, rawNs);
        } catch (Exception e) {
            System.out.println("PROBE-FAIL cannot read snapshot " + snapshot + ": " + e);
            System.exit(1);
            return;
        }
        LangTable lang = LangTable.load(HostAgent.langPath());
        // shape-variant lane: rebuild the SAME plan Registrar.run used (block-shapes.json
        // included), or a shape-only twin registered live would never appear in this rebuilt
        // plan at all and every one of its checks below would silently no-op for it.
        BlockShapeProfile shapes = BlockShapeProfile.load(HostAgent.blockShapesPath());
        VariantPlan plan = VariantPlan.build(snap, lang, shapes);

        List<String> misses = new ArrayList<>();
        List<String> dupes = plan.duplicatePaths();

        int blockTotal = 0, blockHit = 0;
        for (VariantPlan.BlockEntry e : plan.blocks) {
            blockTotal++;
            if (BuiltInRegistries.BLOCK.containsKey(Identifier.fromNamespaceAndPath(ns, e.path))) blockHit++;
            else misses.add("BLOCK " + e.legacyId + " -> " + ns + ":" + e.path);
        }

        // Every state must have had initCache() run on it. Vanilla only does that inside
        // Blocks.<clinit>, which has already finished by the time our hook fires, so a block we
        // registered afterwards renders fine in the inventory and then kills chunk meshing with
        // NPE "occlusionShapesByFace is null" the moment it is placed next to anything.
        // Touching getFaceOcclusionShape here turns that windowed crash into a headless failure.
        int stateTotal = 0, stateHit = 0;
        for (VariantPlan.BlockEntry e : plan.blocks) {
            Identifier rl = Identifier.fromNamespaceAndPath(ns, e.path);
            if (!BuiltInRegistries.BLOCK.containsKey(rl)) continue;
            Block block = BuiltInRegistries.BLOCK.getValue(rl);
            if (block == null) continue;
            for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                stateTotal++;
                try {
                    st.getFaceOcclusionShape(Direction.NORTH);
                    stateHit++;
                } catch (Throwable t) {
                    if (misses.size() < 200) misses.add("STATE " + e.legacyId + " -> " + t);
                }
            }
        }

        int itemTotal = 0, itemHit = 0;
        for (VariantPlan.ItemEntry e : plan.items) {
            itemTotal++;
            if (BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath(ns, e.path))) itemHit++;
            else misses.add("ITEM " + e.legacyId + " -> " + ns + ":" + e.path);
        }

        // the (id, damage) -> registered object map later phases depend on. A shape-only twin
        // (VariantPlan.BlockEntry#shapeOnly) deliberately has no BlockItem, so it never lands in
        // LEGACY_VARIANT_ITEMS the way every other block/item variant's own key does - check
        // LEGACY_VARIANT_BLOCKS for exactly those keys instead of treating the missing item as a
        // probe failure.
        java.util.Set<String> shapeOnlyKeys = plan.shapeOnlyKeys();
        int variantKeys = 0, variantHit = 0, shapeOnlyVariantKeys = 0;
        for (String key : plan.legacyKeyToPath().keySet()) {
            variantKeys++;
            boolean isShapeOnly = shapeOnlyKeys.contains(key);
            if (isShapeOnly) shapeOnlyVariantKeys++;
            boolean hit = isShapeOnly
                    ? Registrar.LEGACY_VARIANT_BLOCKS.containsKey(key)
                    : Registrar.LEGACY_VARIANT_ITEMS.containsKey(key);
            if (hit) variantHit++;
            else if (misses.size() < 200) misses.add("VARIANTKEY " + key);
        }

        int tabTotal = 0, tabHit = 0;
        for (var t : snap.tabs) {
            if (t.isVanillaLabel()) continue;
            tabTotal++;
            String path = LegacyIds.sanitizePath(t.label);
            if (BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(
                    Identifier.fromNamespaceAndPath(ns, path))) tabHit++;
            else misses.add("TAB " + t.label + " -> " + ns + ":" + path);
        }

        // also report the extra catch-all tab and every ns tab actually present, with its page
        int nsTabs = 0;
        StringBuilder tabList = new StringBuilder();
        for (Identifier id : BuiltInRegistries.CREATIVE_MODE_TAB.keySet()) {
            if (!ns.equals(id.getNamespace())) continue;
            nsTabs++;
            CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id);
            tabList.append("\n    ").append(id)
                    .append(" row=").append(tab.row()).append(" col=").append(tab.column())
                    .append(" page=").append(CreativePaging.pageOfColumn(tab.column()))
                    .append(" drawCol=").append(CreativePaging.drawColumn(tab.column()))
                    .append(" title=\"").append(tab.getDisplayName().getString()).append('"');
        }

        System.out.println("PROBE agent-summary: " + Registrar.summary);
        System.out.println("PROBE plan: " + plan.stats());
        System.out.println("PROBE registry sizes: BLOCK=" + BuiltInRegistries.BLOCK.keySet().size()
                + " ITEM=" + BuiltInRegistries.ITEM.keySet().size()
                + " CREATIVE_MODE_TAB=" + BuiltInRegistries.CREATIVE_MODE_TAB.keySet().size());
        System.out.println("PROBE " + ns + " tabs registered=" + nsTabs
                + " pages=1.." + CreativePaging.lastPage() + tabList);

        boolean okBlocks = blockTotal > 0 && (double) blockHit / blockTotal >= THRESHOLD;
        boolean okItems = itemTotal > 0 && (double) itemHit / itemTotal >= THRESHOLD;
        boolean okTabs = tabTotal > 0 && tabHit == tabTotal;
        boolean okStates = stateTotal > 0 && stateHit == stateTotal;
        boolean okVariants = variantKeys > 0 && variantHit == variantKeys;
        boolean okUnique = dupes.isEmpty();
        boolean okNames = plan.rawKeyNames.isEmpty();

        String counts = "blocks=" + blockHit + "/" + blockTotal
                + " items=" + itemHit + "/" + itemTotal
                + " tabs=" + tabHit + "/" + tabTotal
                + " blockStates=" + stateHit + "/" + stateTotal
                + " variantKeys=" + variantHit + "/" + variantKeys
                + " shapeOnlyVariantKeys=" + shapeOnlyVariantKeys
                + " dupIds=" + dupes.size()
                + " rawKeyNames=" + plan.rawKeyNames.size();

        if (okBlocks && okItems && okTabs && okStates && okVariants && okUnique && okNames) {
            System.out.println("PROBE-OK " + counts);
            System.exit(0);
        } else {
            System.out.println("PROBE-FAIL " + counts);
            for (int i = 0; i < Math.min(10, dupes.size()); i++) System.out.println("  DUP " + dupes.get(i));
            for (int i = 0; i < Math.min(10, plan.rawKeyNames.size()); i++) {
                System.out.println("  RAWNAME " + plan.rawKeyNames.get(i));
            }
            int n = Math.min(20, misses.size());
            for (int i = 0; i < n; i++) System.out.println("  MISS " + misses.get(i));
            if (misses.size() > n) System.out.println("  ... " + (misses.size() - n) + " more");
            System.exit(1);
        }
    }
}
