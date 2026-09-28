package dev.umb.legacy.legacyside;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

/**
 * The only place in this package that names Forge event classes in executable code.
 * <p>Why this class exists : linking {@code LegacyBridgeImpl} - which happens at the very first {@code new LegacyBridgeImpl()}, BEFORE {@code LegacyDriver.boot} registers any...
 */
public final class LegacyEventPoster {

    private LegacyEventPoster() {
    }

    /**
     * Forge's {@code WorldEvent.Load} is normally posted by the real server's world loader. The
     * synthetic {@code UmbWorld} has no vanilla load path, so the bridge posts the same event
     * exactly once after the facade is fully seeded and before serverStarting. Generic for
     * every mod.
     */
    public static void postWorldLoad(UmbWorld umbWorld) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(umbWorld));
    }

    /** Notifies mods that a (possibly respawned) player object joined the synthetic world. */
    public static void postEntityJoin(UmbPlayer player, UmbWorld umbWorld) {
        MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(player, umbWorld));
    }

    /**
 * The per-entity half of the vanilla add-paths: vanilla 1.7.10 posts {@code EntityJoinWorldEvent} inside both {@code World.spawnEntityInWorld} and {@code World.addLoadedEntities} , and vehicle mods build their child entities from it (an MCHeli aircraft has...
 */
    public static boolean postEntityJoinWorld(net.minecraft.entity.Entity entity, UmbWorld umbWorld) {
        return MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entity, umbWorld));
    }
}
