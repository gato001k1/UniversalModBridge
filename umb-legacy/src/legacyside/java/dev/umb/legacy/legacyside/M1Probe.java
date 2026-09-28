package dev.umb.legacy.legacyside;

import java.util.HashMap;
import java.util.Map;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;

/**
 * A step 6's headless scenario, driven against a REAL booted universe (unlike {@code UmbFacadeTest}, which deliberately stays boot-free - see its javadoc for why real Block/ Item registry resolution needs a real FML {@code Loader} run).
 * Called reflectively...
 */
public final class M1Probe {

    private M1Probe() {
    }

    public static String run() throws Exception {
        StringBuilder report = new StringBuilder();
        LegacyBridge bridge = new LegacyBridgeImpl();
        FakeHostWorld world = new FakeHostWorld();

        long t0 = System.nanoTime();
        bridge.boot(world);
        report.append("boot: ok (").append((System.nanoTime() - t0) / 1000000L).append(" ms)\n");
        if (!bridge.isBooted()) {
            throw new IllegalStateException("isBooted() false after boot() returned");
        }

        String blockId = "hbm:tile.machine_furnace_brick_off";
        int x = 100, y = 64, z = 100;

        TileHandle tile = bridge.createTile(blockId, x, y, z);
        if (tile == null) {
            throw new IllegalStateException("createTile(" + blockId + ") returned null");
        }
        report.append("createTile: ok, class=").append(tile.getClass().getName()).append('\n');

        FakeHostPlayer player = new FakeHostPlayer();
        ActivationResult activation = bridge.activate(blockId, x, y, z, player, 0, 0.5f, 0.5f, 0.5f);
        if (activation == null || !activation.handled) {
            throw new IllegalStateException("activate(" + blockId + ") did not report handled=true");
        }
        ContainerHandle handle = activation.container;
        if (handle == null) {
            throw new IllegalStateException("activate(" + blockId + ") returned null - no GUI opened");
        }
        report.append("activate: ok, title=").append(handle.title())
                .append(" slotCount=").append(handle.slotCount()).append('\n');

        if (handle.slotCount() != 4) {
            throw new IllegalStateException("expected 4 machine slots (ContainerFurnaceBrick), got "
                    + handle.slotCount());
        }
        SlotData[] slots = handle.slots();
        int[][] expectedXY = {{62, 35}, {35, 17}, {116, 35}, {35, 53}};
        for (int i = 0; i < expectedXY.length; i++) {
            if (slots[i].x != expectedXY[i][0] || slots[i].y != expectedXY[i][1]) {
                throw new IllegalStateException("slot " + i + " expected (" + expectedXY[i][0] + ","
                        + expectedXY[i][1] + ") got (" + slots[i].x + "," + slots[i].y + ")");
            }
        }
        report.append("slot coordinates: ok, match ContainerFurnaceBrick's javap-verified layout\n");

        for (int i = 0; i < 5; i++) {
            bridge.tickTile(tile);
        }
        if (!tile.isValid()) {
            throw new IllegalStateException("tile poisoned during 5 ticks: "
                    + (tile instanceof TileHandleImpl ? ((TileHandleImpl) tile).poisonReason() : "?"));
        }
        report.append("tick: ok, 5 ticks, tile still valid\n");

        int[] syncData = handle.syncData();
        report.append("syncData: ok, length=").append(syncData.length).append('\n');

        byte[] nbt = tile.saveNbt();
        if (nbt == null || nbt.length == 0) {
            throw new IllegalStateException("saveNbt() returned null/empty");
        }
        tile.loadNbt(nbt);
        report.append("saveNbt/loadNbt: ok, ").append(nbt.length).append(" bytes\n");

        handle.close();
        report.append("close: ok\n");

        // Prove hasItemRenderer against a real mod item from this same staged HBM jar.
        // Item-renderer registration is client-proxy init code, which never runs on a bare
        // boot; a few client ticks give the discovery pass the same chance to run it that
        // a live game's first rendered frames have. Armor boots carry a real IItemRenderer
        // (checked for INVENTORY, the render type every custom-rendered item needs); a
        // plain stick must never report one.
        FakeHostWorld clientWorld = new FakeHostWorld();
        UmbWorld tickWorld = UmbWorld.create(clientWorld, 0);
        UmbPlayer tickPlayer = UmbPlayer.create(tickWorld, new FakeHostPlayer());
        for (int i = 0; i < 5; i++) {
            LegacyClientTickDispatcher.tick(tickPlayer, tickWorld);
        }
        boolean armorHasRenderer = dev.umb.legacy.legacyside.render.LegacyRenderCapture
                .hasItemRenderer("hbm:item.ajro_boots", 0, "INVENTORY");
        boolean stickHasRenderer = dev.umb.legacy.legacyside.render.LegacyRenderCapture
                .hasItemRenderer("minecraft:stick", 0, "INVENTORY");
        if (!armorHasRenderer) {
            throw new IllegalStateException("hasItemRenderer(hbm:item.ajro_boots) was false - "
                    + "a real, registered legacy IItemRenderer was not detected");
        }
        if (stickHasRenderer) {
            throw new IllegalStateException("hasItemRenderer(minecraft:stick) was true - a plain "
                    + "vanilla item must never be reported as having a custom renderer");
        }
        report.append("hasItemRenderer: ok, armor=").append(armorHasRenderer)
                .append(" stick=").append(stickHasRenderer).append('\n');

        bridge.shutdown();
        return "M1-OK\n" + report;
    }

    // ---------------------------------------------------------------- fakes

    static final class FakeHostWorld implements HostWorld {
        final Map<Long, Integer> metas = new HashMap<Long, Integer>();

        private static long pack(int x, int y, int z) {
            return ((long) x << 32) ^ ((long) y << 16) ^ (long) z;
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
            return "minecraft:air";
        }

        @Override
        public int getMeta(int x, int y, int z) {
            Integer m = metas.get(Long.valueOf(pack(x, y, z)));
            return m == null ? 0 : m.intValue();
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            metas.remove(Long.valueOf(pack(x, y, z)));
        }

        @Override
        public void markBlockDirty(int x, int y, int z) {
            // not asserted on
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delay) {
            // not asserted on
        }

        @Override
        public long randomSeed() {
            return 1L;
        }

        @Override
        public void log(String msg) {
            System.out.println("[M1Probe/FakeHostWorld] " + msg);
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        final StackData[] inventory = new StackData[36];

        FakeHostPlayer() {
            for (int i = 0; i < inventory.length; i++) {
                inventory[i] = StackData.EMPTY;
            }
        }

        // TICK/ additions: a probe player is stationary and unhurtable
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
            return "M1Probe";
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public double getX() {
            return 100.5;
        }

        @Override
        public double getY() {
            return 64.0;
        }

        @Override
        public double getZ() {
            return 100.5;
        }

        @Override
        public StackData getHeldItem() {
            return StackData.EMPTY;
        }

        @Override
        public void setHeldItem(StackData s) {
            // not asserted on
        }

        @Override
        public void sendMessage(String text) {
            System.out.println("[M1Probe/FakeHostPlayer] message: " + text);
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
