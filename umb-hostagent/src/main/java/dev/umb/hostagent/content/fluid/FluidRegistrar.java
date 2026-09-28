package dev.umb.hostagent.content.fluid;

import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.content.LegacyIds;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import java.nio.file.Path;
import java.util.List;

/** Registers every namespace-owned Forge fluid as a native 26.2 source/flowing pair. */
public final class FluidRegistrar {
    public static volatile int snapshotCount, registeredCount, skippedUnownedCount;
    public static volatile int gaseousRegistered, missingTextureCount, classicBlockLinkedCount;
    private FluidRegistrar() {}

    public static void register(Path snapshot, String rawNamespace) {
        snapshotCount = registeredCount = skippedUnownedCount = gaseousRegistered = 0;
        missingTextureCount = classicBlockLinkedCount = 0;
        if (snapshot == null) return;
        try {
            List<FluidEntry> all = FluidSnapshotReader.loadAll(snapshot);
            snapshotCount = all.size();
            String ns = LegacyIds.sanitizeNamespace(rawNamespace);
            for (FluidEntry f : all) {
                try {
                    if (!f.belongsTo(ns)) { skippedUnownedCount++; continue; }
                    String path = LegacyIds.sanitizePath(f.name);
                    if (path == null || path.isEmpty()) { skippedUnownedCount++; continue; }
                    if (f.gaseous || f.density < 0) gaseousRegistered++;
                    if (f.iconName == null) missingTextureCount++;
                    if (f.blockId != null) classicBlockLinkedCount++;
                    String base = "fluid_" + path;
                    Identifier sourceId = Identifier.fromNamespaceAndPath(ns, base);
                    Identifier flowingId = Identifier.fromNamespaceAndPath(ns, base + "_flowing");
                    Identifier blockId = Identifier.fromNamespaceAndPath(ns, base + "_block");
                    Identifier bucketId = Identifier.fromNamespaceAndPath(ns, base + "_bucket");
                    // Register each fluid IMMEDIATELY after constructing it: a Fluid creates an intrusive
                    // registry holder in its constructor, and any constructed-but-unregistered one makes
                    // BuiltInRegistries.freeze() crash the whole client, not just skip this fluid.
                    GeneratedFluid source = GeneratedFluid.create(f, true);
                    Registry.register(BuiltInRegistries.FLUID, ResourceKey.create(Registries.FLUID, sourceId), source);
                    GeneratedFluid flowing = GeneratedFluid.create(f, false);
                    Registry.register(BuiltInRegistries.FLUID, ResourceKey.create(Registries.FLUID, flowingId), flowing);
                    source.pair(flowing); flowing.pair(source);
                    ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, blockId);
                    Block liquid = new GeneratedLiquidBlock(source, BlockBehaviour.Properties.of()
                            .setId(blockKey).noCollision().noOcclusion()
                            .lightLevel(state -> Math.max(0, Math.min(15, f.luminosity))));
                    Registry.register(BuiltInRegistries.BLOCK, blockKey, liquid);
                    source.legacyBlock(liquid); flowing.legacyBlock(liquid);
                    ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, bucketId);
                    Item bucket = new BucketItem(source, new Item.Properties().setId(itemKey).stacksTo(1));
                    Registry.register(BuiltInRegistries.ITEM, itemKey, bucket);
                    source.bucket(bucket); flowing.bucket(bucket);
                    registeredCount++;
                } catch (Throwable t) {
                    AgentLog.error("fluid " + f.name, t, 3);
                }
            }
            AgentLog.loud("UMB-FLUIDS snapshot=" + snapshotCount + " registered=" + registeredCount
                    + " skippedUnowned=" + skippedUnownedCount + " gaseousRegistered=" + gaseousRegistered
                    + " missingTexture=" + missingTextureCount);
        } catch (Throwable t) {
            AgentLog.error("FluidRegistrar.load", t, 5);
        }
    }
}
