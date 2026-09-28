package dev.umb.legacy1165.legacyside;

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
 * Round-4 headless vertical, driven against the REAL booted universe (same role as 1.7.10's
 * {@code M1Probe}): boots {@link Legacy1165BridgeImpl} (full ModLoader lifecycle inside),
 * creates the iron chest's tile ({@code ironchest:iron_chest}), activates it (genuine
 * {@code Block.use}), asserts the resulting {@code ContainerHandle} has the 54 machine slots
 * an iron chest builds, round-trips an item through slot 0 (put 3 diamonds, take 1, expect 2
 * left), ticks the tile, round-trips its NBT, and closes. Returns a report string prefixed
 * {@code M1165-OK} or throws (the caller writes {@code M1165-FAIL}).
 *
 * <p>Called reflectively from inside the booted {@code Legacy1165Loader}, exactly the way
 * 1.7.10's {@code Bootstrap.Run} calls its driver.</p>
 */
public final class M1165Probe {

    private M1165Probe() {
    }

    public static String run() throws Exception {
        StringBuilder report = new StringBuilder();
        LegacyBridge bridge = new Legacy1165BridgeImpl();
        FakeHostWorld world = new FakeHostWorld();

        long t0 = System.nanoTime();
        bridge.boot(world);
        report.append("boot: ok (").append((System.nanoTime() - t0) / 1000000L).append(" ms)\n");
        if (!bridge.isBooted()) {
            throw new IllegalStateException("isBooted() false after boot() returned");
        }

        String blockId = "ironchest:iron_chest";
        int x = 100, y = 64, z = 100;

        TileHandle tile = bridge.createTile(blockId, x, y, z);
        if (tile == null) {
            throw new IllegalStateException("createTile(" + blockId + ") returned null");
        }
        report.append("createTile: ok, class=").append(tile.getClass().getName()).append('\n');

        ActivationResult activation = bridge.activate(blockId, x, y, z, new FakeHostPlayer(),
                1, 0.5f, 0.5f, 0.5f);
        if (activation == null || !activation.handled) {
            throw new IllegalStateException("activate(" + blockId + ") did not report handled=true");
        }
        ContainerHandle handle = activation.container;
        if (handle == null) {
            throw new IllegalStateException("activate(" + blockId + ") returned null - no GUI opened");
        }
        report.append("activate: ok, title=").append(handle.title())
                .append(" slotCount=").append(handle.slotCount()).append('\n');

        if (handle.slotCount() != 54) {
            throw new IllegalStateException("expected 54 machine slots (iron chest 9x6), got "
                    + handle.slotCount());
        }
        SlotData[] slots = handle.slots();
        if (slots.length != 54) {
            throw new IllegalStateException("slots().length != slotCount()");
        }
        // First machine slot sits at a positive GUI position (the mod lays out a real grid).
        if (slots[0].x < 0 || slots[0].y < 0) {
            throw new IllegalStateException("slot 0 has impossible coordinates ("
                    + slots[0].x + "," + slots[0].y + ")");
        }
        report.append("slots: ok, 54 machine slots, slot0 at (")
                .append(slots[0].x).append(',').append(slots[0].y).append(")\n");

        StackData diamonds = new StackData("minecraft:diamond", 3, 0, null);
        if (!handle.canPlace(0, diamonds)) {
            throw new IllegalStateException("canPlace(0, diamond) declined - chest takes anything");
        }
        handle.setSlot(0, diamonds);
        StackData back = handle.slots()[0].stack;
        if (back == null || back.count != 3 || !"minecraft:diamond".equals(back.legacyId)) {
            throw new IllegalStateException("put failed: got "
                    + (back == null ? "null" : back.legacyId + " x" + back.count));
        }
        StackData taken = handle.takeSlot(0, 1);
        if (taken == null || taken.count != 1 || !"minecraft:diamond".equals(taken.legacyId)) {
            throw new IllegalStateException("take failed: got "
                    + (taken == null ? "null" : taken.legacyId + " x" + taken.count));
        }
        StackData rest = handle.slots()[0].stack;
        if (rest == null || rest.count != 2) {
            throw new IllegalStateException("expected 2 diamonds left, got "
                    + (rest == null ? "null" : "x" + rest.count));
        }
        report.append("put/take: ok, 3 in, 1 taken, 2 left\n");

        for (int i = 0; i < 5; i++) {
            bridge.tickTile(tile);
        }
        if (!tile.isValid()) {
            throw new IllegalStateException("tile poisoned during 5 ticks: "
                    + (tile instanceof TileHandle1165 ? ((TileHandle1165) tile).poisonReason()
                            : "?"));
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

        bridge.shutdown();
        return "M1165-OK\n" + report;
    }

    // ---------------------------------------------------------------- fakes

    static final class FakeHostWorld implements HostWorld {
        final Map<Long, Integer> metas = new HashMap<Long, Integer>();
        final Map<Long, String> ids = new HashMap<Long, String>();

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
            String id = ids.get(Long.valueOf(pack(x, y, z)));
            return id == null ? "minecraft:air" : id;
        }

        @Override
        public int getMeta(int x, int y, int z) {
            Integer m = metas.get(Long.valueOf(pack(x, y, z)));
            return m == null ? 0 : m.intValue();
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
            ids.put(Long.valueOf(pack(x, y, z)), legacyId);
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
            metas.put(Long.valueOf(pack(x, y, z)), Integer.valueOf(meta));
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            metas.remove(Long.valueOf(pack(x, y, z)));
            ids.remove(Long.valueOf(pack(x, y, z)));
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
            System.out.println("[M1165Probe/FakeHostWorld] " + msg);
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        final StackData[] inventory = new StackData[36];

        FakeHostPlayer() {
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
            return "M1165Probe";
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
            System.out.println("[M1165Probe/FakeHostPlayer] message: " + text);
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
