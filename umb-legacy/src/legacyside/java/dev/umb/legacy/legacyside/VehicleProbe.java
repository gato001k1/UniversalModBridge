package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;

import net.minecraft.item.Item;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;

/**
 * Headless MC-Helicopters vehicle-spawn scenario , driven against a REAL booted universe the way {@link M1Probe} drives the Brick Furnace scenario.
 * Called reflectively from outside this jar (scratch harness); returns a report string prefixed {@code...
 */
public final class VehicleProbe {

    private VehicleProbe() {
    }

    public static String run() throws Exception {
        StringBuilder report = new StringBuilder();
        try {
            return runInner(report);
        } catch (IllegalStateException e) {
            // The ladder built so far names the failing rung - carry it in the failure.
            throw new IllegalStateException("VEHICLE-FAIL\n--- ladder ---\n" + report + "---\n" + e.getMessage(), e);
        }
    }

    private static String runInner(StringBuilder report) throws Exception {
        LegacyBridge bridge = new LegacyBridgeImpl();
        FakeHostLevel world = new FakeHostLevel();

        long t0 = System.nanoTime();
        bridge.boot(world);
        report.append("boot: ok (").append((System.nanoTime() - t0) / 1000000L).append(" ms)\n");
        if (!bridge.isBooted()) {
            throw new IllegalStateException("isBooted() false after boot() returned");
        }
        // Model the live failure boundary explicitly: the legacy facade is booted while the
        // host chunk is already loaded, then the host places the terrain afterward. A cached
        // legacy Chunk/ExtendedBlockStorage read would therefore still see air here; every
        // collision lookup must consult the current HostWorld value instead.
        world.markChunkLoaded();
        world.placeStonePadAfterChunkLoad();
        report.append("hostBlockSetAfterChunkLoad=minecraft:stone\n");

        Class<?> aircraftClass;
        try {
            aircraftClass = Class.forName("mcheli.aircraft.MCH_ItemAircraft");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("mcheli not staged: mcheli.aircraft.MCH_ItemAircraft absent");
        }
        FMLControlledNamespacedRegistry<Item> reg = GameData.getItemRegistry();
        String itemId = null;
        String itemClass = null;
        int candidates = 0;
        int withInfo = 0;
        for (Item it : reg.typeSafeIterable()) {
            if (!aircraftClass.isInstance(it)) {
                continue;
            }
            candidates++;
            Object info = it.getClass().getMethod("getAircraftInfo").invoke(it);
            if (info == null) {
                continue;
            }
            withInfo++;
            if (itemId == null) {
                itemId = reg.func_148750_c(it);
                itemClass = it.getClass().getName();
            }
        }
        report.append("aircraftItems=" + candidates + " withInfo=" + withInfo + "\n");
        if (itemId == null) {
            throw new IllegalStateException("VEHICLE-FAIL: no MCH_ItemAircraft with loaded info (candidates="
                    + candidates + ")");
        }
        report.append("item: ok, id=" + itemId + " class=" + itemClass + "\n");

        FakeHostPlayer player = new FakeHostPlayer(new StackData(itemId, 1, 0, null));
        Map<String, Integer> before = UmbStub.hitReport();
        player.pitch = 60.0F;
        try {
            bridge.useItemRightClick(itemId, player);
        } catch (Throwable t) {
            throw new IllegalStateException("VEHICLE-FAIL: overlapping placement threw: " + t, t);
        }
        if (world.spawnCallCount != 0) {
            throw new IllegalStateException("VEHICLE-FAIL: overlapping placement spawned an aircraft");
        }
        report.append("overlapPlacement: pitch=60 spawnEntityCalls=0 (vanilla collision refusal)\n");

        // The live player stands at y=67. Pitch 60 hits only 0.9 blocks forward, so the 1.8-wide
        // aircraft overlaps the player's box exactly as vanilla 1.7.10 does. Aim farther across
        // the same pad for the actual spawn proof; pitch 25 puts the hit point more than 3 blocks
        // from the player's feet.
        player.pitch = 25.0F;
        StackData out;
        try {
            out = bridge.useItemRightClick(itemId, player);
        } catch (Throwable t) {
            throw new IllegalStateException("VEHICLE-FAIL: useItemRightClick threw: " + t, t);
        }
        Map<String, Integer> after = UmbStub.hitReport();
        report.append("useItemRightClick: returned "
                + (out == null ? "null" : ("id=" + out.legacyId + " count=" + out.count)) + "\n");
        report.append("spawnEntityCalls=" + world.spawnCallCount + "\n");
        for (EntityHandle h : world.spawned) {
            String cls;
            try {
                cls = h.getClass().getName();
            } catch (Throwable t) {
                cls = "?(" + t + ")";
            }
            String eid;
            try {
                eid = h.legacyEntityId();
            } catch (Throwable t) {
                eid = "?(" + t + ")";
            }
            double hx;
            try {
                hx = h.getX();
            } catch (Throwable t) {
                hx = Double.NaN;
            }
            report.append("spawned: handle=" + cls + " legacyEntityId=" + eid + " x=" + hx + "\n");
        }
        StringBuilder stubs = new StringBuilder();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int b = before.containsKey(e.getKey()) ? before.get(e.getKey()).intValue() : 0;
            if (e.getValue().intValue() != b) {
                if (stubs.length() > 0) {
                    stubs.append("; ");
                }
                stubs.append(e.getKey()).append(" x").append(e.getValue().intValue() - b);
            }
        }
        report.append("stubHitsDuringDispatch: " + (stubs.length() == 0 ? "(none)" : stubs.toString()) + "\n");

        if (world.spawnCallCount == 0) {
            throw new IllegalStateException("VEHICLE-FAIL: no entity reached HostLevel.spawnEntity");
        }
        EntityHandle capturedHandle = world.spawned.get(0);
        EntityRenderCapture captured = capturedHandle.renderCapture(0.0F);
        java.util.LinkedHashSet<String> textures = new java.util.LinkedHashSet<String>();
        for (EntityRenderCapture.Draw draw : captured.draws) {
            if (draw.texture != null) textures.add(draw.texture);
        }
        report.append("renderCapture: class=").append(captured.entityClass)
                .append(" state=").append(captured.stateKey)
                .append(" vertices=").append(captured.vertexCount())
                .append(" draws=").append(captured.draws.size())
                .append(" matrixOps=").append(captured.matrixOps)
                .append(" pushes=").append(captured.pushes)
                .append(" pops=").append(captured.pops)
                .append(" animated=").append(captured.animated)
                .append(" textures=").append(textures).append('\n');
        if (captured.vertexCount() <= 0 || textures.isEmpty()) {
            // Rendering is a separate . Keep this server-side terrain probe
            // useful when the headless JVM has no GameProfile/OpenGL render context.
            report.append("renderCaptureNote=headless context produced no draw data\n");
        }

        // MCH_EntityAircraft.func_70091_d uses its own static collision helper, which calls
        // UmbWorld.func_147439_a -> Block.func_149743_a directly. Keep a real aircraft in the
        // same known stone-pad geometry for 200 legacy ticks so this seam cannot regress while
        // the generic World.func_72945_a placement proof remains green.
        if (!(capturedHandle instanceof EntityHandleImpl)) {
            throw new IllegalStateException("VEHICLE-FAIL: probe handle did not expose legacy state");
        }
        EntityHandleImpl aircraft = (EntityHandleImpl) capturedHandle;
        net.minecraft.entity.Entity raw = aircraft.raw();
        aircraft.setHostRider(player);
        if (!player.getName().equals(aircraft.riderName())) {
            throw new IllegalStateException("VEHICLE-FAIL: host rider did not reach legacy vehicle");
        }
        for (int tick = 0; tick < 40; tick++) {
            bridge.tickEntities();
        }
        report.append("mountedTicks=40 riderAfter40=").append(aircraft.riderName()).append('\n');
        if (!player.getName().equals(aircraft.riderName())) {
            throw new IllegalStateException("VEHICLE-FAIL: rider did not survive 40 legacy entity ticks");
        }
        aircraft.setHostRider(null);
        final double padTop = 67.0D;
        double initialY = raw.field_70163_u;
        int initialLegacyAircraftCollisionBoxes = collisionBoxCount(raw,
                raw.field_70121_D.func_72321_a(0.0D, -0.1D, 0.0D));
        report.append("postLoadAircraftCollisionBoxes=")
                .append(initialLegacyAircraftCollisionBoxes).append('\n');
        if (initialLegacyAircraftCollisionBoxes <= 0) {
            throw new IllegalStateException("VEHICLE-FAIL: legacy aircraft collision helper did not see "
                    + "the host block placed after chunk load");
        }
        double minRestingBox = Double.POSITIVE_INFINITY;
        double maxRestingBox = Double.NEGATIVE_INFINITY;
        int groundedTicks = 0;
        for (int tick = 0; tick < 200; tick++) {
            bridge.tickEntities();
            if (raw.field_70122_E) groundedTicks++;
            if (tick == 0 || tick == 1 || tick == 2 || tick == 20 || tick == 199) {
                report.append("terrainTick=").append(tick + 1)
                        .append(" y=").append(raw.field_70163_u)
                        .append(" box=").append(raw.field_70121_D)
                        .append(" motionY=").append(raw.field_70181_x)
                        .append(" onGround=").append(raw.field_70122_E).append('\n');
            }
            if (tick >= 40) {
                minRestingBox = Math.min(minRestingBox, raw.field_70121_D.field_72338_b);
                maxRestingBox = Math.max(maxRestingBox, raw.field_70121_D.field_72338_b);
            }
        }
        report.append("terrainTicks=200 syncCalls=").append(world.syncCalls)
                .append(" initialY=").append(initialY)
                .append(" restingBoxMinY=").append(minRestingBox)
                .append(" restingBoxMaxY=").append(maxRestingBox)
                .append(" groundedTicks=").append(groundedTicks).append(" padTop=").append(padTop)
                .append('\n');
        if (world.syncCalls < 200 || !Double.isFinite(minRestingBox)
                || minRestingBox < padTop - 0.0001D
                || maxRestingBox - minRestingBox > 0.0001D) {
            throw new IllegalStateException("VEHICLE-FAIL: aircraft did not rest on vanilla stone collision: "
                    + "syncCalls=" + world.syncCalls + " boxMin=" + minRestingBox
                    + " boxMax=" + maxRestingBox + " groundedTicks=" + groundedTicks);
        }
        return "VEHICLE-OK\n" + report;
    }

    /**
     * Calls the mod's own static collision helper reflectively. The production bridge stays
     * universal; this probe records that the exact MC Heli movement helper, not only
     * World.func_72945_a, sees a host block written after facade boot.
     */
    @SuppressWarnings("rawtypes")
    private static int collisionBoxCount(net.minecraft.entity.Entity entity,
                                         net.minecraft.util.AxisAlignedBB box) throws Exception {
        Class<?> aircraft = Class.forName("mcheli.aircraft.MCH_EntityAircraft");
        java.lang.reflect.Method method = aircraft.getMethod("getCollidingBoundingBoxes",
                net.minecraft.entity.Entity.class, net.minecraft.util.AxisAlignedBB.class);
        java.util.List boxes = (java.util.List) method.invoke(null, entity, box);
        return boxes == null ? 0 : boxes.size();
    }

    // ---------------------------------------------------------------- fakes

    static final class FakeHostLevel implements HostWorld, HostLevel {
        final List<EntityHandle> spawned = new ArrayList<EntityHandle>();
        int spawnCallCount;
        int syncCalls;
        boolean chunkLoaded;
        boolean stonePadPlaced;

        void markChunkLoaded() {
            chunkLoaded = true;
        }

        void placeStonePadAfterChunkLoad() {
            if (!chunkLoaded) {
                throw new IllegalStateException("probe placed terrain before its host chunk was loaded");
            }
            stonePadPlaced = true;
        }

        @Override
        public boolean spawnEntity(EntityHandle handle) {
            spawnCallCount++;
            spawned.add(handle);
            return true;
        }

        @Override
        public void syncEntity(EntityHandle handle) {
            syncCalls++;
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public long getTotalTime() {
            return 0L;
        }

        @Override
        public String getBlockId(int x, int y, int z) {
            // Mirror the live round: a 21x21 stone pad at y=66, air at y=67..75, and the
            // player at (135.5,67,-149.5) looking down at pitch 60 degrees. The pad is only
            // visible after placeStonePadAfterChunkLoad(), proving this is not a boot snapshot.
            return stonePadPlaced && y == 66 && x >= 125 && x <= 145 && z >= -159 && z <= -139
                    ? "minecraft:stone" : "minecraft:air";
        }

        @Override
        public int getMeta(int x, int y, int z) {
            return 0;
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
        }

        @Override
        public void removeBlock(int x, int y, int z) {
        }

        @Override
        public void markBlockDirty(int x, int y, int z) {
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delay) {
        }

        @Override
        public long randomSeed() {
            return 1L;
        }

        @Override
        public void log(String msg) {
            System.out.println("[VehicleProbe/FakeHostLevel] " + msg);
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        final StackData[] inventory = new StackData[36];
        final StackData held;
        float pitch;

        FakeHostPlayer(StackData held) {
            this.held = held;
            for (int i = 0; i < inventory.length; i++) {
                inventory[i] = StackData.EMPTY;
            }
        }

        @Override
        public double getMotionX() {
            return 0.0D;
        }

        @Override
        public double getMotionY() {
            return 0.0D;
        }

        @Override
        public double getMotionZ() {
            return 0.0D;
        }

        @Override
        public void setMotion(double mx, double my, double mz) {
        }

        @Override
        public void hurt(String legacyDamageType, float amount) {
        }

        @Override
        public String getName() {
            return "VehicleProbe";
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public double getX() {
            return 135.5;
        }

        @Override
        public double getY() {
            return 67.0;
        }

        @Override
        public double getZ() {
            return -149.5;
        }

        // the live-geometry collision case uses pitch 60; the successful
        // placement case uses pitch 25 so the hit point is at least three blocks away. Needs
        // HostPlayer.getYaw/getPitch
        @Override
        public float getYaw() {
            return 0.0F;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public StackData getHeldItem() {
            return held;
        }

        @Override
        public void setHeldItem(StackData s) {
        }

        @Override
        public void sendMessage(String text) {
            System.out.println("[VehicleProbe/FakeHostPlayer] message: " + text);
        }

        @Override
        public StackData getInventorySlot(int i) {
            return inventory[i];
        }

        @Override
        public void setInventorySlot(int i, StackData s) {
            inventory[i] = s;
        }

        @Override
        public int getInventorySize() {
            return inventory.length;
        }
    }
}
