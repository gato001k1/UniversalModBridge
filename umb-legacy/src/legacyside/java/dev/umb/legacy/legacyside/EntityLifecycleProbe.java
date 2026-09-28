package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.item.Item;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.InputData;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.input.LegacyInputDispatcher;

/**
 * Real-loader entity lifecycle probe. Runtime-specific names are supplied as properties so this
 * source remains universal: the probe only discovers an item class and drives the normal bridge
 * item/input paths, then observes the generic EntityHandle/HostLevel seam.
 */
public final class EntityLifecycleProbe {
    private EntityLifecycleProbe() {}

    public static String run() throws Exception {
        String aircraftClassName = required("umb.probe.vehicleItemClass");
        String aircraftItemId = System.getProperty("umb.probe.vehicleItemId");
        String gunItemId = required("umb.probe.bulletItemId");
        String gunKey = required("umb.probe.bulletInputKey");
        StringBuilder out = new StringBuilder();
        ProbeWorld world = new ProbeWorld();
        LegacyBridge bridge = new LegacyBridgeImpl();
        bridge.boot(world);
        out.append("boot: ok\n");

        FMLControlledNamespacedRegistry<Item> registry = GameData.getItemRegistry();
        String aircraftId = null;
        int aircraftCandidates = 0;
        for (Item item : registry.typeSafeIterable()) {
            String itemId = registry.func_148750_c(item);
            if ((aircraftItemId != null && aircraftItemId.equals(itemId))
                    || (aircraftItemId == null && isInstanceNamed(item, aircraftClassName))) {
                aircraftCandidates++;
                aircraftId = itemId;
                break;
            }
        }
        out.append("vehicleItemCandidates=").append(aircraftCandidates).append('\n');
        if (aircraftId == null) {
            throw new IllegalStateException("no item instance for " + aircraftClassName);
        }
        ProbePlayer vehiclePlayer = new ProbePlayer(new StackData(aircraftId, 1, 0, null));
        bridge.useItemRightClick(aircraftId, vehiclePlayer);
        int vehicleSpawns = world.spawnCalls;
        out.append("vehicleItemId=").append(aircraftId).append(" spawnCalls=").append(vehicleSpawns).append('\n');
        if (vehicleSpawns == 0) {
            throw new IllegalStateException("vehicle item path produced no entity spawn");
        }
        EntityHandle vehicle = world.spawned.get(0);
        world.vehicleAnchor = vehicle;
        out.append("vehicleOwner=").append(vehicle.ownerNamespace())
                .append(" dimensions=").append(vehicle.getWidth()).append('x').append(vehicle.getHeight()).append('\n');
        if (vehicle.ownerNamespace() == null || vehicle.ownerNamespace().isEmpty()) {
            throw new IllegalStateException("vehicle entity has no authoritative FML owner namespace");
        }
        if (!(vehicle.getWidth() > 0.0F) || !(vehicle.getHeight() > 0.0F)
                || (Math.abs(vehicle.getWidth() - 0.5F) < 0.0001F
                && Math.abs(vehicle.getHeight() - 0.5F) < 0.0001F)) {
            throw new IllegalStateException("vehicle dimensions were not read from the legacy entity: "
                    + vehicle.getWidth() + "x" + vehicle.getHeight());
        }
        if (vehicle instanceof EntityHandleImpl) {
            ((EntityHandleImpl) vehicle).setHostRider(vehiclePlayer);
            out.append("legacyRiderAfterHostMount=").append(vehicle.riderName()).append('\n');
            if (!vehiclePlayer.getName().equals(vehicle.riderName())) {
                throw new IllegalStateException("host rider did not reach legacy riddenByEntity");
            }
        }

        // The real gun path has its own client-key/network prerequisite. Keep a truthful
        // vehicle-only lifecycle mode so the entity seam can still be proved headlessly without
        // treating that separate 's known no-shot result as a bridge failure
        if (Boolean.getBoolean("umb.probe.vehicleOnly")) {
            for (int i = 0; i < 40; i++) {
                bridge.tickEntities();
            }
            out.append("vehicleOnlyTicks=40 syncCalls=").append(world.syncCalls)
                    .append(" riderAfter40=").append(vehicle.riderName()).append('\n');
            if (world.syncCalls == 0) {
                throw new IllegalStateException("vehicle-only lifecycle did not tick/sync the entity");
            }
            if (!vehiclePlayer.getName().equals(vehicle.riderName())) {
                throw new IllegalStateException("legacy mount did not survive 40 entity ticks");
            }
            ((EntityHandleImpl) vehicle).setHostRider(null);
            world.removeEntity(vehicle);
            if (vehicle.isValid()) {
                throw new IllegalStateException("vehicle-only host removal did not invalidate entity");
            }
            out.append("vehicleHostRemovalCalls=").append(world.removeCalls).append(" hostRemoval: ok\n");
            return "ENTITY-OK\n" + out;
        }

        // Keep the projectile target unambiguous: the aircraft spawn/rider proof is complete, so
        // remove that probe vehicle before firing. The player still aims at the ProbeWorld's
        // stone pad; this makes the subsequent legacy raytrace a block-impact proof rather than
        // an accidental hit on the earlier vehicle.
        if (vehicle instanceof EntityHandleImpl) {
            ((EntityHandleImpl) vehicle).raw().field_70128_L = true;
            bridge.tickEntities();
            out.append("vehicleRemovedBeforeGun=true\n");
        }

        StackData ammo = discoverDefaultAmmo(registry, gunItemId);
        out.append("defaultAmmo=").append(ammo.legacyId).append("\n");
        String reloadKey = required("umb.probe.reloadInputKey");
        ProbePlayer gunPlayer = new ProbePlayer(new StackData(gunItemId, 1, 0, null), ammo);
        bridge.useItemRightClick(gunItemId, gunPlayer);
        for (int i = 0; i < 20; i++) {
            bridge.acceptInput(gunPlayer, new InputData(i, false, false, false, false, false, 0,
                    0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null,
                    Collections.<String, Boolean>emptyMap()));
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
            bridge.tickEntities();
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);
        }

        // A real magazine is not loaded merely because a matching ammo stack is in the
        // inventory.  Its fire receiver reads magtype/magcount from the gun stack and constructs
        // the bullet only after the normal reload key path has populated those fields.  Keep this
        // property-driven: the probe knows no gun or mod names and exercises the same generic
        // input bridge used by the live host.
        LegacyInputDispatcher.clearEdgesForTest();
        Map<String, Boolean> reload = Collections.singletonMap(reloadKey, Boolean.TRUE);
        InputData reloadPress = new InputData(20, false, false, false, false, false, 0,
                0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null, reload);
        bridge.acceptInput(gunPlayer, reloadPress);
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
        bridge.tickEntities();
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);

        // Release the reload edge without clearing dispatcher state, then allow the real reload
        // animation/state machine to finish before the primary fire edge.
        InputData reloadRelease = new InputData(21, false, false, false, false, false, 0,
                0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null,
                Collections.<String, Boolean>emptyMap());
        bridge.acceptInput(gunPlayer, reloadRelease);
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
        bridge.tickEntities();
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);
        for (int i = 0; i < 80; i++) {
            bridge.acceptInput(gunPlayer, new InputData(22 + i, false, false, false, false,
                    false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null,
                    Collections.<String, Boolean>emptyMap()));
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
            bridge.tickEntities();
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);
        }

        LegacyInputDispatcher.clearEdgesForTest();
        Map<String, Boolean> keys = Collections.singletonMap(gunKey, Boolean.TRUE);
        for (int i = 0; i < 20; i++) {
            InputData input = new InputData(i, true, false, false, i == 0, false, 0,
                    0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null, keys);
            bridge.acceptInput(gunPlayer, input);
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
            bridge.tickEntities();
            bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);
        }
        InputData fireRelease = new InputData(20, false, false, false, false, false, 0,
                0.0F, 0.0F, 0.0D, 0.0D, 1.0D, gunItemId, 1, 0, null,
                Collections.<String, Boolean>emptyMap());
        bridge.acceptInput(gunPlayer, fireRelease);
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, false);
        bridge.tickEntities();
        bridge.tickEvents(world, new HostPlayer[] {gunPlayer}, true);
        int totalSpawns = world.spawnCalls;
        int bulletSpawns = totalSpawns - vehicleSpawns;
        out.append("bulletItemId=").append(gunItemId).append(" spawnCalls=").append(bulletSpawns)
                .append(" ticks=100 reloadKey=").append(reloadKey)
                .append(" syncCalls=").append(world.syncCalls)
                .append(" bulletRemovals=").append(world.bulletRemovalCalls)
                .append(" blockReads=").append(world.blockReads).append('\n');
        if (bulletSpawns == 0) {
            throw new IllegalStateException("bullet input/item path produced no entity spawn; vehicleSpawns="
                    + vehicleSpawns + " totalSpawns=" + totalSpawns + " vehicleItemId=" + aircraftId
                    + " vehicleCandidates=" + aircraftCandidates + " defaultAmmo=" + ammo.legacyId);
        }

        boolean moved = false;
        for (EntityHandle h : world.spawned) {
            double before = world.beforeX.containsKey(h) ? world.beforeX.get(h) : h.getX();
            if (Math.abs(h.getX() - before) > 1.0E-7D || Math.abs(h.getY() - world.beforeY.get(h)) > 1.0E-7D
                    || Math.abs(h.getZ() - world.beforeZ.get(h)) > 1.0E-7D) {
                moved = true;
            }
        }
        out.append("syncCalls=").append(world.syncCalls).append(" moved=").append(moved).append('\n');
        if (!moved) {
            throw new IllegalStateException("no live legacy entity moved during 20 ticks");
        }
        if (world.bulletRemovalCalls == 0) {
            throw new IllegalStateException("bullet never reached a legacy removal/impact path");
        }

        EntityHandle hostRemoval = world.spawned.get(0);
        world.removeEntity(hostRemoval);
        if (hostRemoval.isValid()) {
            throw new IllegalStateException("host removal did not invalidate legacy entity");
        }
        out.append("hostRemovalCalls=").append(world.removeCalls).append(" hostRemoval: ok\n");

        EntityHandle legacyRemoval = null;
        for (EntityHandle candidate : world.spawned) {
            if (candidate != vehicle && candidate.isValid()) {
                legacyRemoval = candidate;
                break;
            }
        }
        if (legacyRemoval instanceof EntityHandleImpl) {
            try {
                ((EntityHandleImpl) legacyRemoval).raw().func_70106_y();
            } catch (Throwable t) {
                // Some legacy projectile overrides send a server tracker packet after marking
                // themselves dead; the synthetic headless WorldServer has no tracker map. The
                // bridge must still observe the dead flag and remove the twin on the next tick.
                out.append("legacyRemovalOverrideException=").append(t.getClass().getName()).append('\n');
            }
        }
        bridge.tickEntities();
        out.append("legacyRemovalCalls=").append(world.removeCalls).append(" legacyRemoval: ok\n");
        if (world.removeCalls < 2) {
            throw new IllegalStateException("legacy death did not request host twin removal");
        }
        return "ENTITY-OK\n" + out;
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.length() == 0) throw new IllegalStateException("missing -D" + key);
        return value;
    }

    private static boolean isInstanceNamed(Object value, String name) {
        Class<?> c = value == null ? null : value.getClass();
        while (c != null) {
            if (name.equals(c.getName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static StackData discoverDefaultAmmo(FMLControlledNamespacedRegistry<Item> registry,
            String itemId) {
        try {
            Item gun = null;
            for (Item item : registry.typeSafeIterable()) {
                if (itemId.equals(registry.func_148750_c(item))) { gun = item; break; }
            }
            if (gun == null) return StackData.EMPTY;
            java.lang.reflect.Field field = gun.getClass().getField("defaultAmmo");
            Object stack = field.get(gun);
            if (stack == null) return StackData.EMPTY;
            Item ammo = (Item) stack.getClass().getMethod("func_77973_b").invoke(stack);
            int damage = ((Integer) stack.getClass().getMethod("func_77960_j").invoke(stack)).intValue();
            return new StackData(registry.func_148750_c(ammo), 64, damage, null);
        } catch (Throwable ignored) {
            return StackData.EMPTY;
        }
    }

    static final class ProbeWorld implements HostWorld, HostLevel {
        final List<EntityHandle> spawned = new ArrayList<EntityHandle>();
        final Map<EntityHandle, Double> beforeX = new HashMap<EntityHandle, Double>();
        final Map<EntityHandle, Double> beforeY = new HashMap<EntityHandle, Double>();
        final Map<EntityHandle, Double> beforeZ = new HashMap<EntityHandle, Double>();
        EntityHandle vehicleAnchor;
        int spawnCalls;
        int syncCalls;
        int removeCalls;
        int bulletRemovalCalls;
        int blockReads;

        @Override public boolean spawnEntity(EntityHandle h) {
            spawnCalls++;
            spawned.add(h);
            beforeX.put(h, h.getX()); beforeY.put(h, h.getY()); beforeZ.put(h, h.getZ());
            return true;
        }
        @Override public void syncEntity(EntityHandle h) { syncCalls++; }
        @Override public void removeEntity(EntityHandle h) {
            removeCalls++;
            if (h != vehicleAnchor) bulletRemovalCalls++;
            h.hostRemoved();
        }
        @Override public boolean isRemote() { return false; }
        @Override public long getTotalTime() { return 0L; }
        @Override public String getBlockId(int x, int y, int z) {
            blockReads++;
            return y <= 63 ? "minecraft:stone" : "minecraft:air";
        }
        @Override public int getMeta(int x, int y, int z) { return 0; }
        @Override public void setBlock(int x, int y, int z, String id, int meta, int flags) {}
        @Override public void setMeta(int x, int y, int z, int meta, int flags) {}
        @Override public void removeBlock(int x, int y, int z) {}
        @Override public void markBlockDirty(int x, int y, int z) {}
        @Override public void scheduleTick(int x, int y, int z, int delay) {}
        @Override public long randomSeed() { return 1L; }
        @Override public void log(String msg) { System.out.println("[EntityLifecycleProbe] " + msg); }
    }

    static final class ProbePlayer implements HostPlayer {
        final StackData held;
        final StackData ammo;
        ProbePlayer(StackData held) { this(held, StackData.EMPTY); }
        ProbePlayer(StackData held, StackData ammo) { this.held = held; this.ammo = ammo; }
        @Override public double getMotionX() { return 0.0D; }
        @Override public double getMotionY() { return 0.0D; }
        @Override public double getMotionZ() { return 0.0D; }
        @Override public void setMotion(double x, double y, double z) {}
        @Override public void hurt(String type, float amount) {}
        @Override public String getName() { return "EntityLifecycleProbe-" + held.legacyId; }
        @Override public boolean isSneaking() { return false; }
        @Override public double getX() { return 100.5D; }
        @Override public double getY() { return 65.0D; }
        @Override public double getZ() { return 100.5D; }
        @Override public float getYaw() { return 0.0F; }
        @Override public float getPitch() { return 90.0F; }
        @Override public StackData getHeldItem() { return held; }
        @Override public void setHeldItem(StackData s) {}
        @Override public void sendMessage(String text) { System.out.println(text); }
        @Override public StackData getInventorySlot(int i) {
            if (i == 0) return held;
            if (i == 1) return ammo;
            return StackData.EMPTY;
        }
        @Override public void setInventorySlot(int i, StackData s) {}
        @Override public int getInventorySize() { return 36; }
    }
}
