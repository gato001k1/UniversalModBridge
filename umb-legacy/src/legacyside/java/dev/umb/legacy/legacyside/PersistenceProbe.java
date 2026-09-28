package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;
import cpw.mods.fml.common.registry.EntityRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;

/**
 * Real-loader persistence probe. Runtime ids are supplied as properties so the probe has no
 * dependency on a particular mod: {@code -Dumb.persistence.tileId=<legacy tile id>} and
 * {@code -Dumb.persistence.entityItemId=<legacy spawn item id>}. It serializes a live tile and a
 * live entity, replaces the facade with a fresh world instance, restores both, and restores the
 * entity twice to prove UUID-based duplicate suppression.
 */
public final class PersistenceProbe {
    private PersistenceProbe() {}

    public static String run() throws Exception {
        String tileId = required("umb.persistence.tileId");
        String requestedEntityItemId = System.getProperty("umb.persistence.entityItemId");
        String entityItemId = requestedEntityItemId == null ? null : resolveItemId(requestedEntityItemId,
                System.getProperty("umb.persistence.entityItemClass"));
        StringBuilder report = new StringBuilder();
        LegacyBridgeImpl bridge = new LegacyBridgeImpl();
        ProbeWorld firstWorld = new ProbeWorld();
        bridge.boot(firstWorld);

        TileHandle tile = bridge.createTile(tileId, 100, 64, 100);
        if (tile == null) throw new IllegalStateException("createTile returned null for " + tileId);
        for (int i = 0; i < 5; i++) bridge.tickTile(tile);
        byte[] tileBlob = requireBlob("tile", tile.saveNbt());
        report.append("tile: class=").append(tile.getClass().getName())
                .append(" blobBytes=").append(tileBlob.length).append(" ticks=5\n");

        EntityHandle entity;
        if (entityItemId != null) {
            ProbePlayer player = new ProbePlayer(new StackData(entityItemId, 1, 0, null));
            player.pitch = 25.0F;
            bridge.useItemRightClick(entityItemId, player);
            entity = firstWorld.spawned.isEmpty() ? null : firstWorld.spawned.get(0);
        } else {
            entity = spawnFixture(bridge);
            report.append("entityPath=genericFixture\n");
        }
        if (entity == null) {
            throw new IllegalStateException("entity item path produced no spawn for " + requestedEntityItemId);
        }
        byte[] entityBlob = requireBlob("entity", entity.saveNbt());
        report.append("entityItem: requested=").append(requestedEntityItemId)
                .append(" resolved=").append(entityItemId).append('\n')
                .append("entity: id=").append(entity.legacyEntityId())
                .append(" class=").append(entity.legacyEntityClassName())
                .append(" blobBytes=").append(entityBlob.length)
                .append(" pos=").append(entity.getX()).append(',').append(entity.getY()).append(',')
                .append(entity.getZ()).append('\n');

        ProbeWorld reloadedWorld = new ProbeWorld();
        bridge.rebindWorldForPersistenceProbe(reloadedWorld);
        TileHandle restoredTile = bridge.createTile(tileId, 100, 64, 100);
        if (restoredTile == null) throw new IllegalStateException("restored createTile returned null");
        restoredTile.loadNbt(tileBlob);
        byte[] tileAgain = requireBlob("restored tile", restoredTile.saveNbt());
        if (!sameNbt(tileBlob, tileAgain)) {
            throw new IllegalStateException("tile NBT changed across fresh-world restore before="
                    + nbtText(tileBlob) + " after=" + nbtText(tileAgain));
        }
        report.append("tileRestore: ok sameNbt=true contentsAndEnergyOpaquePreserved=true\n");

        EntityHandle restored = bridge.restoreEntity(entityBlob);
        if (restored == null) throw new IllegalStateException("restoreEntity returned null");
        if (!entity.legacyEntityId().equals(restored.legacyEntityId())
                || !entity.legacyEntityClassName().equals(restored.legacyEntityClassName())) {
            throw new IllegalStateException("restored entity identity changed");
        }
        byte[] restoredBlob = requireBlob("restored entity", restored.saveNbt());
        if (!sameNbt(entityBlob, restoredBlob)) {
            throw new IllegalStateException("entity NBT changed across fresh-world restore before="
                    + nbtText(entityBlob) + " after=" + nbtText(restoredBlob));
        }
        EntityHandle duplicate = bridge.restoreEntity(entityBlob);
        if (duplicate != restored) throw new IllegalStateException("same entity UUID created a duplicate");
        report.append("entityRestore: ok sameNbt=true duplicateRestoreReused=true id=")
                .append(restored.legacyEntityId()).append(" pos=")
                .append(restored.getX()).append(',').append(restored.getY()).append(',')
                .append(restored.getZ()).append('\n');
        return "PERSISTENCE-OK\n" + report;
    }

    private static boolean sameNbt(byte[] a, byte[] b) throws Exception {
        return nbtText(a).equals(nbtText(b));
    }

    private static String nbtText(byte[] bytes) throws Exception {
        NBTTagCompound tag = CompressedStreamTools.func_152457_a(bytes, NBTSizeTracker.field_152451_a);
        // Some legacy TileEntity constructors materialize an absent optional custom-name tag as
        // name="" on the first load. Empty-vs-absent is not machine contents/energy state; keep
        // the comparison strict for every other key.
        if (tag.func_74779_i("name").length() == 0) tag.func_82580_o("name");
        return tag.toString();
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.length() == 0) throw new IllegalStateException("missing -D" + key);
        return value;
    }

    private static byte[] requireBlob(String kind, byte[] blob) {
        if (blob == null || blob.length == 0) throw new IllegalStateException(kind + " saveNbt returned empty");
        return blob;
    }

    private static String resolveItemId(String requested, String className) throws Exception {
        FMLControlledNamespacedRegistry<Item> registry = GameData.getItemRegistry();
        if (registry.get(requested) != null) return requested;
        if (className == null || className.length() == 0) return null;
        String simple = className.substring(className.lastIndexOf('.') + 1);
        int candidates = 0;
        for (Item item : registry.typeSafeIterable()) {
            String runtimeName = item.getClass().getName();
            if (!className.equals(runtimeName) && !runtimeName.endsWith("." + simple)) continue;
            candidates++;
            String id = registry.func_148750_c(item);
            if (id != null) return id;
        }
        return null;
    }

    private static EntityHandle spawnFixture(LegacyBridgeImpl bridge) {
        int id = EntityRegistry.findGlobalUniqueEntityId();
        EntityList.func_75618_a(PersistenceFixture.class, "umb.persistence.fixture", id);
        PersistenceFixture fixture = new PersistenceFixture(bridge.debugWorld());
        fixture.field_70165_t = 135.5D;
        fixture.field_70163_u = 67.35D;
        fixture.field_70161_v = -149.5D;
        fixture.field_70159_w = 0.25D;
        if (!bridge.debugWorld().func_72838_d(fixture)) return null;
        bridge.tickEntities();
        return new EntityHandleImpl(fixture);
    }

    /**
 * Small registered legacy entity whose real NBT and onUpdate make the round-trip observable
 */
    public static final class PersistenceFixture extends Entity {
        int ticks;
        public PersistenceFixture(World world) { super(world); }
        @Override protected void func_70088_a() {}
        @Override protected void func_70037_a(NBTTagCompound tag) { ticks = tag.func_74762_e("ticks"); }
        @Override protected void func_70014_b(NBTTagCompound tag) { tag.func_74768_a("ticks", ticks); }
        @Override public void func_70071_h_() { ticks++; field_70165_t += field_70159_w; }
    }

    static final class ProbeWorld implements HostWorld, HostLevel {
        final List<EntityHandle> spawned = new ArrayList<EntityHandle>();
        @Override public boolean spawnEntity(EntityHandle h) { spawned.add(h); return true; }
        @Override public void syncEntity(EntityHandle h) {}
        @Override public void removeEntity(EntityHandle h) { h.hostRemoved(); }
        @Override public boolean isRemote() { return false; }
        @Override public long getTotalTime() { return 0L; }
        @Override public String getBlockId(int x, int y, int z) { return y == 66 ? "minecraft:stone" : "minecraft:air"; }
        @Override public int getMeta(int x, int y, int z) { return 0; }
        @Override public void setBlock(int x, int y, int z, String id, int meta, int flags) {}
        @Override public void setMeta(int x, int y, int z, int meta, int flags) {}
        @Override public void removeBlock(int x, int y, int z) {}
        @Override public void markBlockDirty(int x, int y, int z) {}
        @Override public void scheduleTick(int x, int y, int z, int delay) {}
        @Override public long randomSeed() { return 1L; }
        @Override public void log(String msg) { System.out.println("[PersistenceProbe] " + msg); }
    }

    static final class ProbePlayer implements HostPlayer {
        final StackData held;
        float pitch;
        ProbePlayer(StackData held) { this.held = held; }
        @Override public double getMotionX() { return 0; }
        @Override public double getMotionY() { return 0; }
        @Override public double getMotionZ() { return 0; }
        @Override public void setMotion(double x, double y, double z) {}
        @Override public void hurt(String type, float amount) {}
        @Override public String getName() { return "PersistenceProbe"; }
        @Override public boolean isSneaking() { return false; }
        @Override public double getX() { return 135.5; }
        @Override public double getY() { return 67.0; }
        @Override public double getZ() { return -149.5; }
        @Override public float getYaw() { return 0; }
        @Override public float getPitch() { return pitch; }
        @Override public StackData getHeldItem() { return held; }
        @Override public void setHeldItem(StackData s) {}
        @Override public void sendMessage(String text) { System.out.println(text); }
        @Override public StackData getInventorySlot(int i) { return i == 0 ? held : StackData.EMPTY; }
        @Override public void setInventorySlot(int i, StackData s) {}
        @Override public int getInventorySize() { return 36; }
    }
}
