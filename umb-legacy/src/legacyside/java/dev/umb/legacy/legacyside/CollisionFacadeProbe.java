package dev.umb.legacy.legacyside;

import java.util.List;

import net.minecraft.util.AxisAlignedBB;

import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;

/** Headless proof that a host block reaches the 1.7.10 collision list through UmbWorld. */
public final class CollisionFacadeProbe {
    private CollisionFacadeProbe() {
    }

    public static String run() throws Exception {
        CollisionWorld host = new CollisionWorld();
        LegacyBridge bridge = new LegacyBridgeImpl();
        bridge.boot(host);
        UmbWorld world = UmbWorld.create(host, 0);
        AxisAlignedBB box = AxisAlignedBB.func_72330_a(0.2D, 0.25D, 0.2D,
                0.8D, 1.25D, 0.8D);
        List<?> collisions = world.func_72945_a(null, box);
        if (!world.func_72899_e(0, 0, 0) || collisions.isEmpty()) {
            throw new IllegalStateException("collision facade did not expose host stone: loaded="
                    + world.func_72899_e(0, 0, 0) + " collisions=" + collisions.size());
        }
        Object first = collisions.get(0);
        if (!(first instanceof AxisAlignedBB)) {
            throw new IllegalStateException("collision result was not an AABB: " + first);
        }
        return "COLLISION-OK loaded=true hostBlock=minecraft:stone result=" + collisions.size()
                + " shape=" + first;
    }

    private static final class CollisionWorld implements HostWorld {
        @Override public boolean isRemote() { return false; }
        @Override public long getTotalTime() { return 0L; }
        @Override public long randomSeed() { return 1L; }
        @Override public String getBlockId(int x, int y, int z) {
            return y == 0 ? "minecraft:stone" : "minecraft:air";
        }
        @Override public int getMeta(int x, int y, int z) { return 0; }
        @Override public void setBlock(int x, int y, int z, String id, int meta, int flags) { }
        @Override public void setMeta(int x, int y, int z, int meta, int flags) { }
        @Override public void removeBlock(int x, int y, int z) { }
        @Override public void markBlockDirty(int x, int y, int z) { }
        @Override public void scheduleTick(int x, int y, int z, int delay) { }
        @Override public void log(String message) { }
    }
}
