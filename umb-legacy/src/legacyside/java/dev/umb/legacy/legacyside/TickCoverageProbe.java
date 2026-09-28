package dev.umb.legacy.legacyside;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;

/**
 * E, step 3: the mass-tick harness.
 * Boots {@link LegacyBridgeImpl} exactly like {@link M1Probe} does, then for EVERY tile-entity class the just-booted FML actually registered (read LIVE off {@code TileEntity.field_145855_i} - see {@link #readClassList}) -...
 */
public final class TickCoverageProbe {

    private static final int TICKS = 20;
    private static final int SYNTH_X = 0, SYNTH_Y = 4, SYNTH_Z = 0;

    private TickCoverageProbe() {
    }

    public static String run() throws Exception {
        LegacyBridgeImpl bridge = new LegacyBridgeImpl();
        bridge.boot(new FakeHostWorld());
        UmbWorld world = bridge.debugWorld();

        List<String> classNames = readClassList();
        StringBuilder out = new StringBuilder(classNames.size() * 64);
        out.append("CLASS-COUNT\t").append(classNames.size()).append('\n');

        ClassLoader cl = TickCoverageProbe.class.getClassLoader();
        for (String className : classNames) {
            String rawOutcome;
            String detail = "";
            List<String> stubHits;
            Map<String, Integer> before = UmbStub.hitReport();
            try {
                Object instance = instantiate(className, cl);
                if (!(instance instanceof TileEntity)) {
                    rawOutcome = "THREW";
                    detail = "NotATileEntity: " + (instance == null ? "null" : instance.getClass().getName());
                } else {
                    TileEntity te = (TileEntity) instance;
                    te.field_145851_c = SYNTH_X;
                    te.field_145848_d = SYNTH_Y;
                    te.field_145849_e = SYNTH_Z;
                    te.func_145834_a(world);
                    world.putTile(SYNTH_X, SYNTH_Y, SYNTH_Z, te);
                    try {
                        te.func_145829_t();
                        for (int i = 0; i < TICKS; i++) {
                            te.func_145845_h();
                        }
                        rawOutcome = "OK";
                    } finally {
                        world.removeTileAt(SYNTH_X, SYNTH_Y, SYNTH_Z);
                    }
                }
            } catch (Throwable t) {
                rawOutcome = "THREW";
                detail = describe(t);
            }
            Map<String, Integer> after = UmbStub.hitReport();
            stubHits = newKeys(before, after);

            out.append(className).append('\t').append(rawOutcome).append('\t')
                    .append(sanitize(detail)).append('\t')
                    .append(join(stubHits)).append('\n');
        }
        return out.toString();
    }

    private static Object instantiate(String className, ClassLoader cl) throws Exception {
        Class<?> c = Class.forName(className, true, cl);
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static List<String> newKeys(Map<String, Integer> before, Map<String, Integer> after) {
        List<String> added = new ArrayList<String>();
        for (String k : after.keySet()) {
            Integer b = before.get(k);
            Integer a = after.get(k);
            if (b == null || !b.equals(a)) {
                added.add(k);
            }
        }
        return added;
    }

    private static String describe(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        String frame0 = frames.length > 0 ? frames[0].toString() : "(no stack trace)";
        return t.getClass().getName() + ": " + t.getMessage() + " at " + frame0;
    }

    private static String sanitize(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(items.get(i));
        }
        return b.toString();
    }

    /**
     * The distinct tile-entity class names FML has actually registered by boot time, read off the
     * same live {@code TileEntity.field_145855_i} map {@link RegistrySnapshotter} already reads
     * (see its own javadoc) - not a bundled, mod-specific static list. Several string ids can
     * legitimately map to the same class (e.g. renamed/legacy ids); each class is only tested once.
     */
    private static List<String> readClassList() {
        @SuppressWarnings("unchecked")
        Map<String, Class<?>> teMap = (Map<String, Class<?>>) Statics.get(TileEntity.class, "field_145855_i");
        Set<String> names = new LinkedHashSet<String>();
        if (teMap != null) {
            for (Map.Entry<String, Class<?>> e : new TreeMap<String, Class<?>>(teMap).entrySet()) {
                if (e.getValue() != null) {
                    names.add(e.getValue().getName());
                }
            }
        }
        return new ArrayList<String>(names);
    }

    // ---------------------------------------------------------------- minimal fakes
    // Deliberately identical in spirit to M1Probe's fakes (constant "minecraft:air", no scheduling
    // side effects asserted on) - this harness measures the FACADE's own robustness, not host game
    // logic, so a trivial always-air/no-op HostWorld is the right synthetic environment.

    static final class FakeHostWorld implements HostWorld {
        private final Map<Long, Integer> metas = new HashMap<Long, Integer>();

        private static long pack(int x, int y, int z) {
            return ((long) x << 32) ^ ((long) y << 16) ^ (long) z;
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public long getTotalTime() {
            return 100L;
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
            return 42L;
        }

        @Override
        public void log(String msg) {
            // swallowed: 372-class run would otherwise flood stdout
        }
    }

    static final class FakeHostPlayer implements HostPlayer {
        private final StackData[] inventory = new StackData[36];

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
            return "TickCoverageProbe";
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public double getX() {
            return SYNTH_X + 0.5;
        }

        @Override
        public double getY() {
            return SYNTH_Y;
        }

        @Override
        public double getZ() {
            return SYNTH_Z + 0.5;
        }

        @Override
        public StackData getHeldItem() {
            return StackData.EMPTY;
        }

        @Override
        public void setHeldItem(StackData s) {
        }

        @Override
        public void sendMessage(String text) {
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
