package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;

import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;

import dev.umb.bridge.api.HostWorld;
import dev.umb.legacy.api.BlockMetaShape;
import dev.umb.legacy.api.BlockShapeEntry;

/** Legacy compatibility behavior. */
public final class BlockShapeProbe {

    private static final int META_COUNT = 16;
    private static final int SYNTH_X = 0, SYNTH_Y = 4, SYNTH_Z = 0;
    private static final double EPS = 1.0e-4;

    private BlockShapeProbe() {
    }

    public static List<BlockShapeEntry> run() throws Exception {
        LegacyBridgeImpl bridge = new LegacyBridgeImpl();
        MutableHostWorld host = new MutableHostWorld();
        bridge.boot(host);
        UmbWorld world = bridge.debugWorld();

        List<BlockShapeEntry> out = new ArrayList<BlockShapeEntry>();
        FMLControlledNamespacedRegistry<Block> reg = GameData.getBlockRegistry();
        for (Block b : reg.typeSafeIterable()) {
            String id;
            int regId;
            String className;
            try {
                id = reg.func_148750_c(b);
                regId = reg.getId(b);
                className = b.getClass().getName();
            } catch (Throwable t) {
                // cannot even name this entry - nothing usable to report
                continue;
            }
            try {
                out.add(probeBlock(world, host, b, id, regId, className));
            } catch (Throwable t) {
                out.add(new BlockShapeEntry(id, regId, className, describe(t),
                        Collections.<BlockMetaShape>emptyList()));
            }
        }
        return out;
    }

    private static BlockShapeEntry probeBlock(UmbWorld world, MutableHostWorld host, Block b,
                                               String id, int regId, String className) {
        host.blockId = id;
        // LinkedHashMap: preserves first-seen order of distinct shapes, so meta 0's shape (the
        // common case) is always group 0 in the output.
        Map<String, GroupBuilder> groups = new LinkedHashMap<String, GroupBuilder>();
        for (int meta = 0; meta < META_COUNT; meta++) {
            host.meta = meta;
            Shape shape = probeMeta(world, b);
            String sig = shape.signature();
            GroupBuilder gb = groups.get(sig);
            if (gb == null) {
                gb = new GroupBuilder(shape);
                groups.put(sig, gb);
            }
            gb.metas.add(Integer.valueOf(meta));
        }
        List<BlockMetaShape> metaShapes = new ArrayList<BlockMetaShape>(groups.size());
        for (GroupBuilder gb : groups.values()) {
            metaShapes.add(gb.build());
        }
        return new BlockShapeEntry(id, regId, className, null, metaShapes);
    }

    /** Never throws: every legacy call is individually guarded so a partial failure (e.g. the
     *  collision call throws but bounds worked fine) still yields the data that DID resolve. */
    private static Shape probeMeta(UmbWorld world, Block b) {
        try {
            try {
                b.func_149719_a(world, SYNTH_X, SYNTH_Y, SYNTH_Z);
            } catch (Throwable ignored) {
                // some blocks NPE here without a tile entity present at the probed position;
                // the raw bounds fields still hold whatever the block's own constructor set.
            }

            double[] rawBounds;
            try {
                rawBounds = new double[]{
                        b.func_149704_x(), b.func_149665_z(), b.func_149706_B(),
                        b.func_149753_y(), b.func_149669_A(), b.func_149693_C()
                };
            } catch (Throwable t) {
                rawBounds = null;
            }

            AxisAlignedBB colAabb = null;
            try {
                colAabb = b.func_149668_a(world, SYNTH_X, SYNTH_Y, SYNTH_Z);
            } catch (Throwable ignored) {
            }
            AxisAlignedBB selAabb = null;
            try {
                selAabb = b.func_149633_g(world, SYNTH_X, SYNTH_Y, SYNTH_Z);
            } catch (Throwable ignored) {
            }

            List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
            try {
                AxisAlignedBB mask = AxisAlignedBB.func_72330_a(
                        SYNTH_X - 4, SYNTH_Y - 4, SYNTH_Z - 4,
                        SYNTH_X + 5, SYNTH_Y + 5, SYNTH_Z + 5);
                b.func_149743_a(world, SYNTH_X, SYNTH_Y, SYNTH_Z, mask, boxes, null);
            } catch (Throwable ignored) {
            }

            boolean opaque = false;
            try {
                opaque = b.func_149662_c();
            } catch (Throwable ignored) {
            }
            boolean renderNormal = false;
            try {
                renderNormal = b.func_149686_d();
            } catch (Throwable ignored) {
            }

            double[] colLocal = toLocal(colAabb);
            double[] selLocal = toLocal(selAabb);
            double[][] boxesLocal = new double[boxes.size()][];
            for (int i = 0; i < boxes.size(); i++) {
                boxesLocal[i] = toLocal(boxes.get(i));
            }

            boolean fullCube = isFullCube(boxesLocal);
            return Shape.ok(rawBounds, colLocal, selLocal, boxesLocal, opaque, renderNormal, fullCube);
        } catch (Throwable t) {
            return Shape.error(describe(t));
        }
    }

    private static boolean isFullCube(double[][] boxesLocal) {
        if (boxesLocal.length != 1) {
            return false;
        }
        double[] box = boxesLocal[0];
        return box != null
                && approx(box[0], 0.0) && approx(box[1], 0.0) && approx(box[2], 0.0)
                && approx(box[3], 1.0) && approx(box[4], 1.0) && approx(box[5], 1.0);
    }

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < EPS;
    }

    private static double[] toLocal(AxisAlignedBB box) {
        if (box == null) {
            return null;
        }
        return new double[]{
                box.field_72340_a - SYNTH_X, box.field_72338_b - SYNTH_Y, box.field_72339_c - SYNTH_Z,
                box.field_72336_d - SYNTH_X, box.field_72337_e - SYNTH_Y, box.field_72334_f - SYNTH_Z
        };
    }

    private static String describe(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        String frame0 = frames.length > 0 ? frames[0].toString() : "(no stack trace)";
        String msg = t.getClass().getName() + ": " + t.getMessage() + " at " + frame0;
        return msg.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ---------------------------------------------------------------- internal shape accumulator

    /** One meta's raw probe result, before it is grouped/deduped. */
    private static final class Shape {
        final double[] rawBounds;
        final double[] collisionAabb;
        final double[] selectionAabb;
        final double[][] collisionBoxes;
        final boolean isOpaqueCube;
        final boolean renderAsNormalBlock;
        final boolean isFullCube;
        final String error;

        private Shape(double[] rawBounds, double[] collisionAabb, double[] selectionAabb,
                      double[][] collisionBoxes, boolean isOpaqueCube, boolean renderAsNormalBlock,
                      boolean isFullCube, String error) {
            this.rawBounds = rawBounds;
            this.collisionAabb = collisionAabb;
            this.selectionAabb = selectionAabb;
            this.collisionBoxes = collisionBoxes;
            this.isOpaqueCube = isOpaqueCube;
            this.renderAsNormalBlock = renderAsNormalBlock;
            this.isFullCube = isFullCube;
            this.error = error;
        }

        static Shape ok(double[] rawBounds, double[] collisionAabb, double[] selectionAabb,
                        double[][] collisionBoxes, boolean isOpaqueCube, boolean renderAsNormalBlock,
                        boolean isFullCube) {
            return new Shape(rawBounds, collisionAabb, selectionAabb, collisionBoxes,
                    isOpaqueCube, renderAsNormalBlock, isFullCube, null);
        }

        static Shape error(String message) {
            return new Shape(null, null, null, new double[0][], false, false, false, message);
        }

        String signature() {
            StringBuilder b = new StringBuilder(128);
            b.append(fmt(rawBounds)).append('|').append(fmt(collisionAabb)).append('|')
                    .append(fmt(selectionAabb)).append('|');
            for (int i = 0; i < collisionBoxes.length; i++) {
                if (i > 0) {
                    b.append(';');
                }
                b.append(fmt(collisionBoxes[i]));
            }
            b.append('|').append(isOpaqueCube).append('|').append(renderAsNormalBlock)
                    .append('|').append(error == null ? "" : "ERR:" + error);
            return b.toString();
        }

        private static String fmt(double[] a) {
            if (a == null) {
                return "null";
            }
            StringBuilder b = new StringBuilder(a.length * 8);
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(String.format(Locale.ROOT, "%.5f", Double.valueOf(a[i])));
            }
            return b.toString();
        }
    }

    private static final class GroupBuilder {
        final Shape shape;
        final List<Integer> metas = new ArrayList<Integer>();

        GroupBuilder(Shape shape) {
            this.shape = shape;
        }

        BlockMetaShape build() {
            int[] arr = new int[metas.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = metas.get(i).intValue();
            }
            return new BlockMetaShape(arr, shape.rawBounds, shape.collisionAabb, shape.selectionAabb,
                    shape.collisionBoxes, shape.isOpaqueCube, shape.renderAsNormalBlock,
                    shape.isFullCube, shape.error);
        }
    }

    // ---------------------------------------------------------------- the synthetic host

    /**
     * A single mutable synthetic position: whatever {@link #blockId}/{@link #meta} are currently
     * set to is what the probed block "is", everywhere else reports plain air. This deliberately
     * isolates each block from its neighbours (no adjacency-driven connections like fences/glass
     * panes forming) so the reported shape is the block's OWN declared default state, not an
     * artifact of whatever else happens to sit at the synthetic coordinates.
     */
    static final class MutableHostWorld implements HostWorld {
        volatile String blockId = "minecraft:air";
        volatile int meta = 0;

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
            if (x == SYNTH_X && y == SYNTH_Y && z == SYNTH_Z) {
                return blockId;
            }
            return "minecraft:air";
        }

        @Override
        public int getMeta(int x, int y, int z) {
            if (x == SYNTH_X && y == SYNTH_Y && z == SYNTH_Z) {
                return meta;
            }
            return 0;
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int m, int flags) {
            // not asserted on - this probe never round-trips through setBlock
        }

        @Override
        public void setMeta(int x, int y, int z, int m, int flags) {
            // not asserted on
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            // not asserted on
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
            return 7L;
        }

        @Override
        public void log(String msg) {
            // swallowed: ~1000-block run would otherwise flood stdout
        }
    }
}
