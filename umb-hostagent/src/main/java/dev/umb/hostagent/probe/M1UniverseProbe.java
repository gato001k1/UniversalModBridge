package dev.umb.hostagent.probe;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.HostAgent;
import dev.umb.hostagent.content.UmbUniverse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G2 task 4: the headless end-to-end gate for the REAL {@link UmbUniverse} -- not
 * {@code FakeLegacyBridge}, not {@code M1Probe} run in-process inside {@code umb-legacy}'s own
 * test JVM (that only proves {@code LegacyBridgeImpl} works when something else has already built
 * the loader hierarchy). This is the one gate that proves the actual embedding this task adds:
 * {@code UmbUniverse.boot()} building the three-tier classloader stack from inside a plain JVM
 * (standing in for "inside the running 26.2 client") and reaching a REAL, working
 * {@code LegacyBridgeImpl} through it.
 *
 * <p>Per the task brief, this does NOT need a live {@code ServerLevel}: {@link FakeHostWorld}/
 * {@link FakeHostPlayer} below are the host-side twins of {@code M1Probe}'s own fakes (legacy
 * side) -- plain in-memory implementations of the SAME {@code dev.umb.bridge.api} interfaces
 * {@code HostWorldImpl}/{@code HostPlayerImpl} implement, so the real 26.2 game classpath is not
 * needed at all here; only {@code UmbUniverse} and the legacy build artifacts are.</p>
 *
 * <p>Run via {@code tools/run-hostagent-e2e-m1.ps1}, which supplies the exact legacy JVM flags
 * (`--sun-misc-unsafe-memory-access=allow` + 8 `--add-opens`) {@link UmbUniverse#boot} checks for
 * before doing anything else. Exits 0 and prints {@code M1-OK} on success, exits 1 and prints
 * {@code M1-FAIL: <reason>} otherwise.</p>
 */
public final class M1UniverseProbe {

    private M1UniverseProbe() {
    }

    public static void main(String[] args) {
        StringBuilder report = new StringBuilder();
        try {
            if (args.length < 1 || args[0] == null || args[0].isEmpty()) {
                System.out.println("M1-FAIL: usage: M1UniverseProbe <scenario.json>"
                        + " (driven with one by tools/run-hostagent-e2e-m1.ps1)");
                System.exit(2);
                return;
            }
            report.append(run(Scenario.load(args[0])));
            System.out.println("M1-OK\n" + report);
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("M1-FAIL: " + t + "\n" + report);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /**
     * End-to-end scenario, read as DATA (see {@code research/out/legacy/m1-scenario-hbm.json}).
     * Every mod-specific value (block/item ids, slot layout, fuel, ticks) arrives here from
     * the file; the probe asserts only generic furnace behaviors (container opens, layout
     * matches, fuel+ticks move persisted state, a progress register goes live). The HBM
     * values in the shipped scenario were each javap-verified when first recorded (slot
     * coordinates, NBT fields, register semantics) - that provenance lives in comments and
     * the scenario file, never as literals in this code.
     */
    static final class Scenario {
        String namespace;
        List<Path> modJars = new ArrayList<>();
        String furnaceBlock;
        int fx, fy, fz;
        int furnaceSlots;
        List<int[]> furnaceSlotXY = new ArrayList<>();
        String furnaceInputItem;
        int furnaceInputCount;
        String furnaceFuelItem;
        int furnaceFuelCount;
        int furnaceTicks;
        String rtgBlock;
        int rx, ry, rz;
        int rtgSyncLen;
        String rtgInputItem;
        int rtgInputCount;
        int rtgInputSlot;
        String rtgFuelItem;
        int rtgFuelCount;
        int rtgFuelSlot;
        int rtgProgressIndex;
        int rtgTicks;

        static Scenario load(String path) throws Exception {
            String text = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            Scenario s = new Scenario();
            s.namespace = req(root, "namespace").getAsString();
            Path repo = Paths.get(System.getProperty("umb.repo", "."));
            for (var e : root.getAsJsonArray("modJars"))
                s.modJars.add(repo.resolve(e.getAsString()).toAbsolutePath().normalize());
            com.google.gson.JsonObject f = root.getAsJsonObject("furnace");
            s.furnaceBlock = req(f, "blockId").getAsString();
            s.fx = f.get("x").getAsInt();
            s.fy = f.get("y").getAsInt();
            s.fz = f.get("z").getAsInt();
            s.furnaceSlots = f.get("slotCount").getAsInt();
            for (var e : f.getAsJsonArray("slotXY"))
                s.furnaceSlotXY.add(new int[]{e.getAsJsonArray().get(0).getAsInt(),
                        e.getAsJsonArray().get(1).getAsInt()});
            s.furnaceInputItem = req(f, "inputItem").getAsString();
            s.furnaceInputCount = f.get("inputCount").getAsInt();
            s.furnaceFuelItem = req(f, "fuelItem").getAsString();
            s.furnaceFuelCount = f.get("fuelCount").getAsInt();
            s.furnaceTicks = f.get("ticks").getAsInt();
            com.google.gson.JsonObject r = root.getAsJsonObject("rtg");
            s.rtgBlock = req(r, "blockId").getAsString();
            s.rx = r.get("x").getAsInt();
            s.ry = r.get("y").getAsInt();
            s.rz = r.get("z").getAsInt();
            s.rtgSyncLen = r.get("syncLen").getAsInt();
            s.rtgInputItem = req(r, "inputItem").getAsString();
            s.rtgInputCount = r.get("inputCount").getAsInt();
            s.rtgInputSlot = r.get("inputSlot").getAsInt();
            s.rtgFuelItem = req(r, "fuelItem").getAsString();
            s.rtgFuelCount = r.get("fuelCount").getAsInt();
            s.rtgFuelSlot = r.get("fuelSlot").getAsInt();
            s.rtgProgressIndex = r.get("progressIndex").getAsInt();
            s.rtgTicks = r.get("ticks").getAsInt();
            return s;
        }

        private static com.google.gson.JsonElement req(com.google.gson.JsonObject o, String k) {
            if (!o.has(k) || o.get(k).isJsonNull())
                throw new IllegalArgumentException("scenario is missing required key: " + k);
            return o.get(k);
        }
    }

    static String run(Scenario sc) throws Exception {
        StringBuilder report = new StringBuilder();

        // The universe boots whatever jars the scenario names - no default jar lives in code.
        HostAgent.configure(null, null, sc.namespace, sc.modJars);
        UmbUniverse universe = new UmbUniverse();
        FakeHostWorld world = new FakeHostWorld();

        long t0 = System.nanoTime();
        universe.boot(world);
        long bootMs = (System.nanoTime() - t0) / 1_000_000L;
        if (!universe.isBooted()) {
            throw new IllegalStateException("isBooted() false after boot() returned");
        }
        report.append("boot: ok (").append(bootMs).append(" ms, via real UmbUniverse - not a fake)\n");

        String blockId = sc.furnaceBlock;
        int x = sc.fx, y = sc.fy, z = sc.fz;

        TileHandle tile = universe.createTile(blockId, x, y, z);
        if (tile == null) {
            throw new IllegalStateException("createTile(" + blockId + ") returned null");
        }
        report.append("createTile: ok, class=").append(tile.getClass().getName()).append('\n');

        FakeHostPlayer player = new FakeHostPlayer();
        ActivationResult activation = universe.activate(blockId, x, y, z, player, 0, 0.5f, 0.5f, 0.5f);
        if (activation == null || !activation.handled) {
            throw new IllegalStateException("activate(" + blockId + ") did not report handled=true");
        }
        ContainerHandle handle = activation.container;
        if (handle == null) {
            throw new IllegalStateException("activate(" + blockId + ") returned null - no GUI opened");
        }
        report.append("activate: ok, title=").append(handle.title())
                .append(" slotCount=").append(handle.slotCount()).append('\n');

        if (handle.slotCount() != sc.furnaceSlots) {
            throw new IllegalStateException("expected " + sc.furnaceSlots + " machine slots, got "
                    + handle.slotCount());
        }
        SlotData[] slots = handle.slots();
        for (int i = 0; i < sc.furnaceSlotXY.size(); i++) {
            int[] expected = sc.furnaceSlotXY.get(i);
            if (slots[i].x != expected[0] || slots[i].y != expected[1]) {
                throw new IllegalStateException("slot " + i + " expected (" + expected[0] + ","
                        + expected[1] + ") got (" + slots[i].x + "," + slots[i].y + ")");
            }
        }
        report.append("slot coordinates: ok, match the scenario's slot layout\n");

        int[] syncBefore = handle.syncData();
        int syncLen = syncBefore.length;
        report.append("syncData: ok, stable length=").append(syncLen).append('\n');
        byte[] nbtBefore = tile.saveNbt();

        // TileEntityFurnaceBrick.canSmelt() (javap-verified) requires BOTH a valid smelting input
        // in slot 0 (net.minecraft.item.crafting.FurnaceRecipes.getSmeltingResult(slots[0]) != null
        // - vanilla iron_ore -> iron_ingot is a real vanilla recipe, R7's exact hardcoded pair) AND
        // burn time in slot 1 (net.minecraft.tileentity.TileEntityFurnace.getItemBurnTime, vanilla
        // coal = 1600) before progress moves at all.
        StackData ironOre = new StackData(sc.furnaceInputItem, sc.furnaceInputCount, 0, null);
        StackData coal = new StackData(sc.furnaceFuelItem, sc.furnaceFuelCount, 0, null);
        handle.setSlot(0, ironOre);
        handle.setSlot(1, coal);
        for (int i = 0; i < sc.furnaceTicks; i++) {
            universe.tickTile(tile);
        }
        if (!tile.isValid()) {
            throw new IllegalStateException("tile poisoned during ticking with fuel");
        }
        int[] syncAfter = handle.syncData();
        if (syncAfter.length != syncLen) {
            throw new IllegalStateException("syncData() length changed: " + syncLen + " -> " + syncAfter.length);
        }
        // DEVIATION, javap-verified (not guessed): com.hbm.inventory.container.ContainerFurnaceBrick
        // does NOT override Container.detectAndSendChanges() / ICrafting progress push at all - HBM
        // syncs THIS machine's progress/burnTime to its GUI through its own custom networking
        // (com.hbm.main.NetworkHandler / PacketThreading), which DESIGN.md's Lane A step 5
        // deliberately stubs to no-ops (so TE sync code cannot explode without a real
        // MinecraftServer/ServerConfigurationManager) - so syncData() legitimately stays all-zero
        // for this specific tile, and the task brief's "ticking changes syncData()" does not hold
        // for THIS machine. What DOES observably change is the tile's own persisted state
        // (TileEntityFurnaceBrick.writeToNBT javap-verified to serialize burnTime/maxBurnTime/
        // progress/ash levels), so that is what this gate asserts instead. syncData()'s CONTRACT
        // obligation (stable length, never throwing) is still fully exercised and asserted above.
        byte[] nbtAfter = tile.saveNbt();
        boolean nbtChanged = !Arrays.equals(nbtBefore, nbtAfter);
        report.append("tick-with-fuel: 20 ticks, syncData unchanged (expected - see deviation note; ")
                .append("ContainerFurnaceBrick has no ICrafting progress push, javap-verified), ")
                .append("persisted NBT ").append(nbtChanged ? "CHANGED" : "unchanged")
                .append(" (").append(nbtBefore.length).append(" -> ").append(nbtAfter.length).append(" bytes)\n");
        if (!nbtChanged) {
            throw new IllegalStateException("tile's persisted NBT did not change after loading fuel+input and "
                    + "ticking 20x - expected burn time/progress to move");
        }

        tile.loadNbt(nbtAfter);
        report.append("saveNbt/loadNbt: ok, ").append(nbtAfter.length).append(" bytes, round-tripped\n");
        handle.close();

        // SYNC-BINDING lane: the syncData()-liveness proof the lead's brief demanded BEFORE any
        // binding work - see research/out/legacy/guimap-notes/SYNC-BINDING.md. ContainerFurnaceBrick
        // above is the WRONG machine to prove this with (its Container never overrides
        // detectAndSendChanges at all - the deviation note above already caught that, independently,
        // via javap). com.hbm.inventory.container.ContainerRtgFurnace DOES: its detectAndSendChanges
        // compares this.dualCookTime to diFurnace.dualCookTime and, on a change, calls
        // ICrafting.func_71112_a(this, 0, diFurnace.dualCookTime) - id 0, exactly the mapping
        // ContainerSyncScanner extracted from the same bytecode (both the server func_71112_a route
        // and the client func_75137_b route agree - see gui-profile.json's container.syncBindings
        // for com.hbm.inventory.container.ContainerRtgFurnace).
        report.append(runRtgFurnaceSyncLivenessProof(universe, sc));

        universe.shutdown();
        report.append("shutdown: ok\n");
        return report.toString();
    }

    /**
     * Real run, real registered HBM block/item, real ticking: proves {@code ContainerHandle.syncData()}
     * index 0 tracks {@code TileEntityRtgFurnace.dualCookTime} live, through the UNMODIFIED
     * production path (UmbGui.openGui's crafter registration -&gt; ContainerHandleImpl.syncData()
     * -&gt; Container.func_75142_b() -&gt; ICrafting.func_71112_a -&gt; UmbPlayer.syncData()). The RTG
     * pellet item is a real fuel input this specific machine needs to make ANY progress at all
     * (com.hbm.util.RTGUtil.updateRTGs only counts {@code ItemRTGPellet} instances, javap-verified)
     * -- naming it here is test setup for a real E2E scenario, the same convention already used for
     * {@code hbm:tile.machine_furnace_brick_off}/{@code minecraft:iron_ore} above, not production
     * binding code (which stays fully generic - see GuiProfile/ContainerSyncScanner).
     */
    private static String runRtgFurnaceSyncLivenessProof(UmbUniverse universe, Scenario sc) throws Exception {
        StringBuilder r = new StringBuilder();
        String blockId = sc.rtgBlock;
        int x = sc.rx, y = sc.ry, z = sc.rz;

        TileHandle tile = universe.createTile(blockId, x, y, z);
        if (tile == null) throw new IllegalStateException("createTile(" + blockId + ") returned null");

        FakeHostPlayer player = new FakeHostPlayer();
        ActivationResult activation = universe.activate(blockId, x, y, z, player, 0, 0.5f, 0.5f, 0.5f);
        if (activation == null || !activation.handled || activation.container == null) {
            throw new IllegalStateException("activate(" + blockId + ") did not open a container");
        }
        ContainerHandle handle = activation.container;
        r.append("rtgFurnace activate: ok, title=").append(handle.title())
                .append(" slotCount=").append(handle.slotCount()).append('\n');

        int[] before = handle.syncData();
        if (before.length != sc.rtgSyncLen) {
            throw new IllegalStateException("syncData() register bank length changed from the scenario's "
                    + sc.rtgSyncLen + ": " + before.length);
        }
        if (before[sc.rtgProgressIndex] != 0) {
            throw new IllegalStateException("expected progress register " + sc.rtgProgressIndex
                    + " to start at 0, got " + before[sc.rtgProgressIndex]);
        }
        r.append("rtgFurnace syncData before fuel: index").append(sc.rtgProgressIndex)
                .append("=").append(before[sc.rtgProgressIndex]).append(" (all-zero baseline)\n");

        // input slot = smelting input (a real vanilla smelting recipe, same one the furnace
        // scenario above already relies on); fuel slot = one of the fuel slots.
        handle.setSlot(sc.rtgInputSlot, new StackData(sc.rtgInputItem, sc.rtgInputCount, 0, null));
        handle.setSlot(sc.rtgFuelSlot, new StackData(sc.rtgFuelItem, sc.rtgFuelCount, 0, null));

        int[] afterEachTick = new int[sc.rtgTicks];
        for (int i = 0; i < afterEachTick.length; i++) {
            universe.tickTile(tile);
            afterEachTick[i] = handle.syncData()[sc.rtgProgressIndex];
        }
        if (!tile.isValid()) {
            throw new IllegalStateException("RTG furnace tile poisoned while ticking with fuel");
        }
        int finalValue = afterEachTick[afterEachTick.length - 1];
        r.append("rtgFurnace syncData per tick (index").append(sc.rtgProgressIndex).append("/progress): ")
                .append(Arrays.toString(afterEachTick)).append('\n');
        if (finalValue <= 0) {
            throw new IllegalStateException("syncData()[" + sc.rtgProgressIndex + "] (progress) is still <= 0 after "
                    + afterEachTick.length + " real ticks with fuel loaded - the liveness claim does not hold: "
                    + Arrays.toString(afterEachTick));
        }
        // Register isolation: every OTHER index must remain exactly 0 - this container only ever
        // sends the progress id, so nothing should have corrupted a neighbouring slot (see
        // UmbPlayer.func_71112_a's own bounds check for the out-of-range/overflow half of this claim).
        int[] afterAll = handle.syncData();
        for (int i = 0; i < afterAll.length; i++) {
            if (i != sc.rtgProgressIndex && afterAll[i] != 0) {
                throw new IllegalStateException("register " + i + " was written to (" + afterAll[i]
                        + ") but this container only ever sends id " + sc.rtgProgressIndex
                        + " - neighbouring-register corruption");
            }
        }
        r.append("rtgFurnace syncData liveness: PROVEN - index").append(sc.rtgProgressIndex)
                .append(" went 0 -> ").append(finalValue)
                .append(" over ").append(afterEachTick.length).append(" real ticks; all ")
                .append(afterAll.length - 1).append(" other registers stayed 0\n");

        handle.close();
        return r.toString();
    }

    // ---------------------------------------------------------------- host-side fakes
    // (twins of dev.umb.legacy.legacyside.M1Probe's own fakes, but on the HOST side of the
    // boundary -- these implement the SAME dev.umb.bridge.api.HostWorld/HostPlayer interfaces
    // HostWorldImpl/HostPlayerImpl implement over a real ServerLevel/ServerPlayer; per the task
    // brief a live ServerLevel is not needed to prove UmbUniverse's embedding works)

    static final class FakeHostWorld implements HostWorld {
        final Map<Long, Integer> metas = new HashMap<>();

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
            return metas.getOrDefault(pack(x, y, z), 0);
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
            metas.put(pack(x, y, z), meta);
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
            metas.put(pack(x, y, z), meta);
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            metas.remove(pack(x, y, z));
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
            System.out.println("[M1UniverseProbe/FakeHostWorld] " + msg);
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        final StackData[] inventory = new StackData[36];

        FakeHostPlayer() {
            Arrays.fill(inventory, StackData.EMPTY);
        }

        // TICK/CONTACT lane additions: a probe player is stationary and unhurtable.
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
            return "M1UniverseProbe";
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public double getX() {
            return 200.5;
        }

        @Override
        public double getY() {
            return 64.0;
        }

        @Override
        public double getZ() {
            return 200.5;
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
            System.out.println("[M1UniverseProbe/FakeHostPlayer] message: " + text);
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
