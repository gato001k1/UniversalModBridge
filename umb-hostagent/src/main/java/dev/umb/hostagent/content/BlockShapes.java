package dev.umb.hostagent.content;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a real 26.2 {@link VoxelShape} from a {@link BlockShapeProfile.MetaGroup} (Task A of
 * laneConsume-progress.md: "you place a big machine and see one block with a tiny model inside").
 *
 * <p><b>GENERIC out-of-cell decision (multiblock-notes lane, superseding the earlier blanket
 * clamp).</b> javap on {@code BlockBehaviour}'s default bodies shows {@code getCollisionShape}/
 * {@code getVisualShape}/{@code getOcclusionShape(state)} ALL delegate back to
 * {@code getShape(state, level, pos, context)} (via {@code BlockState.getShape}) - there is
 * exactly ONE override point, so whatever this returns also drives light occlusion and face
 * culling, not just collision. The earlier version of this class always clamped every box into
 * [0,1] to keep that one shape safely inside its own cell, guarding against
 * {@code Registrar.primeBlockStateCaches}'s "occlusionShapesByFace is null" crash class - but that
 * crash is actually caused by a block STATE never having {@code initCache()} called on it at all
 * (fixed unconditionally, for every twin, by {@code primeBlockStateCaches} regardless of shape
 * geometry), not by the shape's own extent. Vanilla itself already ships single-BlockState shapes
 * that extend past [0,1] on ONE cell - {@code minecraft:fence}/{@code minecraft:cobblestone_wall}
 * declare a 1.5-tall collision box in the real 1.7.10 data this project extracts
 * (research/out/legacy/block-shapes.json), and 26.2's own fence/wall blocks do the exact same
 * thing today (a fence post you cannot jump over) - real, load-bearing proof that one BlockState's
 * VoxelShape reaching outside its own cell is a normal, correctly-handled vanilla pattern, not an
 * engine limit.</p>
 *
 * <p><b>The GENERIC rule this class now applies, per {@link BlockShapeProfile.MetaGroup}:</b> a
 * group whose own declared boxes (collisionBoxes, else collisionAabb, else rawBounds - the same
 * fallback ladder {@link #build} already used) extend past [0,1] in any dimension is built
 * UNCLAMPED, exactly as declared - see {@link #declaresOutOfCellShape}. No block-name or
 * mod-namespace check anywhere: this is purely a function of the extracted numbers, so it applies
 * equally to a real multiblock controller (e.g. a BlockDummyable-shaped machine's own
 * {@code func_149743_a}-declared appendage) and to any ordinary block that happens to declare
 * bounds outside its cell, with no special-casing either way.</p>
 *
 * <p><b>The one safety gate: {@link #isUnclamped} distrusts geometry near the extractor's own
 * query window.</b> {@code BlockShapeProbe} (umb-legacy) queries every block's
 * {@code func_149743_a} with a fixed, generous-but-finite +-4/+5-block mask ({@link
 * #PROBE_WINDOW_MIN}/{@link #PROBE_WINDOW_MAX} in the SAME local/offset coordinates
 * block-shapes.json stores) and only ADDS a box when it intersects that mask - javap-confirmed the
 * block's own code never clips the box itself, it is recorded exactly as declared. But a structure
 * whose true geometry reaches FURTHER than that window would have any additional, farther-out box
 * silently never attempted (not truncated - genuinely missing from the data), so a box sitting AT
 * or past the window's own edge cannot be trusted as this block's COMPLETE declared shape. Applied
 * to the real hbm data (see MULTIBLOCK-LANE.md): 10 of 13 out-of-cell HBM blocks are comfortably
 * inside the window and get the new unclamped shape; {@code hbm:tile.launch_pad_large},
 * {@code hbm:tile.rbmk_autoloader} and {@code hbm:tile.machine_flare} each have at least one box
 * sitting exactly at the window edge and conservatively KEEP the old clamped behaviour (also why
 * the existing {@code launchPadLargeMeta12...} test below is unchanged by this lane - it already
 * covered exactly this "stays clamped" case, for what turns out to be the RIGHT reason once this
 * gate exists).</p>
 *
 * <p><b>26.2 engine note (the one real limitation, not a shortcut):</b> a single BlockState's
 * VoxelShape is relative to its own cell, and 26.2's collision/occlusion queries only consult a
 * block position that is actually visited in the query's own search range - an unclamped shape on
 * ONE controller cell is correctly picked up whenever a query's range includes that cell (exactly
 * like vanilla fences/walls above), but it cannot substitute for a REAL multi-block footprint made
 * of several separately-placed twins each answering for their own cell. BlockDummyable-family
 * multiblocks (confirmed by javap, see MULTIBLOCK-LANE.md) do not need that: every footprint cell
 * is the SAME legacy block id at a DIFFERENT metadata value, already a separately-registered twin
 * per {@link Registrar}'s existing per-meta variant plan, so each cell already gets its own
 * correctly-extracted (and now correctly-unclamped) shape with no new mechanism required.</p>
 *
 * <p>Honest limit (per the lead's brief): {@code hbm:tile.nuke_tsar} and
 * {@code hbm:tile.machine_radar_large} report {@code isFullCube=true} in the data - their bigness
 * is TESR-drawn, not block-bounds-driven, and this class does NOT invent a bigger shape for them;
 * {@link #build} returns {@code Shapes.block()} for any group with {@code isFullCube=true}.</p>
 */
public final class BlockShapes {

    /** Tolerance for "is this coordinate actually outside [0,1]" - keeps float->double rounding
     *  noise (e.g. 1.0000001) from falsely tripping {@link #declaresOutOfCellShape}. */
    private static final double EPS = 1.0e-4;

    /**
     * {@code BlockShapeProbe}'s own synthetic query window (umb-legacy, {@code SYNTH_Y=4} and a
     * {@code +-4/+5} mask around it), in the SAME local/offset coordinates block-shapes.json
     * stores every box in. See the class javadoc's "one safety gate" section for why a box at or
     * past this edge is not trusted as complete.
     */
    private static final double PROBE_WINDOW_MIN = -4.0;
    private static final double PROBE_WINDOW_MAX = 5.0;
    private static final double PROBE_WINDOW_EDGE_TOLERANCE = 0.01;

    private BlockShapes() {
    }

    /**
     * Fallback ladder: collisionBoxes (unioned) -&gt; collisionAabb -&gt; rawBounds -&gt; the
     * default full cube. Each box is clamped into the block's own 1x1x1 cell UNLESS
     * {@link #isUnclamped} trusts this group's own declared geometry to extend past it - see the
     * class javadoc. Never null, never throws.
     */
    public static VoxelShape build(BlockShapeProfile.MetaGroup g) {
        if (g == null || g.isFullCube) return Shapes.block();
        boolean unclamp = isUnclamped(g);

        VoxelShape fromBoxes = fromBoxes(g.collisionBoxes, unclamp);
        if (fromBoxes != null) return fromBoxes;

        VoxelShape fromAabb = fromBox(g.collisionAabb, unclamp);
        if (fromAabb != null) return fromAabb;

        VoxelShape fromRaw = fromBox(g.rawBounds, unclamp);
        if (fromRaw != null) return fromRaw;

        return Shapes.block();
    }

    /**
     * True when this group RECORDED "no collision" in the 1.7.10 data - see
     * {@link BlockShapeProfile.MetaGroup#hasNoCollision} for the exact predicate (explicit JSON
     * null {@code collisionAabb}, empty {@code collisionBoxes}, not a full cube). 276 of the
     * corpus's 1900 meta-groups (LIVE-GAP-ANALYSIS.md section 4). Null-safe like every other
     * predicate here.
     */
    public static boolean hasNoCollision(BlockShapeProfile.MetaGroup g) {
        return g != null && g.hasNoCollision;
    }

    /**
     * The COLLISION shape for this group, as opposed to {@link #build}'s outline/selection shape.
     * For a {@link #hasNoCollision} group this is {@code Shapes.empty()} - javap on 26.2's
     * {@code BlockBehaviour.getCollisionShape} shows {@code hasCollision ? state.getShape(...) :
     * Shapes.empty()}, i.e. an empty VoxelShape is vanilla's own native "walk-through" collision
     * value (air, torches, every noCollission() block), so returning it here is the exact 26.2
     * translation of 1.7.10's null {@code func_149668_a}. For every other group this is
     * identical to {@link #build} - one shape served both roles before this method existed, and
     * that stays true for the 1624 groups that do declare collision. The outline stays
     * {@link #build}'s non-empty shape so the block remains clickable/selectable
     * ({@code getShape} drives outline picking, {@code getDestroyProgress} and occlusion).
     */
    public static VoxelShape buildCollision(BlockShapeProfile.MetaGroup g) {
        return hasNoCollision(g) ? Shapes.empty() : build(g);
    }

    /**
     * How many boxes actually survive and contribute to {@link #build}'s result for this
     * group - 0 when {@code build} fell through to the default full cube, 1 when it used
     * collisionAabb/rawBounds (or a collisionBoxes list where only one box survives), 2+
     * for a genuine multi-box shape. Used purely for the lead's "how many were multi-box"
     * reporting - {@link #build} does not call this (kept as one pure pass each so a caller that
     * only wants the shape never pays for the count, and vice versa).
     */
    public static int contributingBoxCount(BlockShapeProfile.MetaGroup g) {
        if (g == null || g.isFullCube) return 0;
        boolean unclamp = isUnclamped(g);
        if (g.collisionBoxes != null && !g.collisionBoxes.isEmpty()) {
            int n = 0;
            for (double[] b : g.collisionBoxes) {
                if (fromBox(b, unclamp) != null) n++;
            }
            if (n > 0) return n;
        }
        if (fromBox(g.collisionAabb, unclamp) != null) return 1;
        if (fromBox(g.rawBounds, unclamp) != null) return 1;
        return 0;
    }

    /** True when {@link #build} would return something other than the plain default full cube. */
    public static boolean isNonDefault(BlockShapeProfile.MetaGroup g) {
        return contributingBoxCount(g) > 0;
    }

    /**
     * True when this group's own declared boxes (collisionBoxes, else collisionAabb, else
     * rawBounds) extend past the block's own 1x1x1 cell in any dimension, before any clamping -
     * i.e. this block GENERICALLY declares an out-of-cell shape, regardless of whether
     * {@link #isUnclamped} ultimately trusts it enough to build it unclamped. Exposed for
     * {@link Registrar}'s reporting counters.
     */
    public static boolean declaresOutOfCellShape(BlockShapeProfile.MetaGroup g) {
        if (g == null || g.isFullCube) return false;
        if (anyExtendsPastCell(g.collisionBoxes)) return true;
        if (extendsPastCell(g.collisionAabb)) return true;
        return extendsPastCell(g.rawBounds);
    }

    /**
     * True when {@link #build} will actually produce a shape extending past this group's own cell
     * for real - i.e. it {@link #declaresOutOfCellShape} AND none of that geometry sits at or past
     * the extractor's own query-window edge (see the class javadoc's "one safety gate" section).
     * Exposed for {@link Registrar}'s reporting counters and for tests.
     */
    public static boolean isUnclamped(BlockShapeProfile.MetaGroup g) {
        if (!declaresOutOfCellShape(g)) return false;
        if (anyNearProbeWindowEdge(g.collisionBoxes)) return false;
        if (nearProbeWindowEdge(g.collisionAabb)) return false;
        return !nearProbeWindowEdge(g.rawBounds);
    }

    private static VoxelShape fromBoxes(List<double[]> boxes, boolean unclamp) {
        if (boxes == null || boxes.isEmpty()) return null;
        List<VoxelShape> parts = new ArrayList<>();
        for (double[] b : boxes) {
            VoxelShape s = fromBox(b, unclamp);
            if (s != null) parts.add(s);
        }
        if (parts.isEmpty()) return null;
        VoxelShape result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = Shapes.or(result, parts.get(i));
        }
        return result;
    }

    /**
     * One [minX,minY,minZ,maxX,maxY,maxZ] box (min/max order not assumed). When {@code unclamp}
     * is false (the default, and every ordinary in-cell block), each coordinate is clamped into
     * the block's own 1x1x1 cell exactly like before this lane. When {@code unclamp} is true (only
     * reachable when {@link #isUnclamped} already trusted this group), each coordinate is instead
     * sanitized (NaN -&gt; 0, otherwise bounded to the same probe query window {@link #isUnclamped}
     * already verified it stays within) rather than forced into [0,1]. Returns null when the
     * resulting box has no volume (entirely clamped away, or malformed input).
     */
    private static VoxelShape fromBox(double[] b, boolean unclamp) {
        if (b == null || b.length != 6) return null;
        double minX = Math.min(b[0], b[3]);
        double minY = Math.min(b[1], b[4]);
        double minZ = Math.min(b[2], b[5]);
        double maxX = Math.max(b[0], b[3]);
        double maxY = Math.max(b[1], b[4]);
        double maxZ = Math.max(b[2], b[5]);
        if (unclamp) {
            minX = sanitize(minX);
            minY = sanitize(minY);
            minZ = sanitize(minZ);
            maxX = sanitize(maxX);
            maxY = sanitize(maxY);
            maxZ = sanitize(maxZ);
        } else {
            minX = clamp01(minX);
            minY = clamp01(minY);
            minZ = clamp01(minZ);
            maxX = clamp01(maxX);
            maxY = clamp01(maxY);
            maxZ = clamp01(maxZ);
        }
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) return null;
        return Shapes.box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Defensive belt-and-suspenders only: {@link #isUnclamped} already guarantees every
     *  coordinate reaching this path is strictly inside the probe window, so this bound should
     *  never actually bind - it exists so a future bug here degrades to a bounded shape rather
     *  than an unbounded/NaN one. */
    private static double sanitize(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(PROBE_WINDOW_MIN, Math.min(PROBE_WINDOW_MAX, v));
    }

    private static boolean anyExtendsPastCell(List<double[]> boxes) {
        if (boxes == null) return false;
        for (double[] b : boxes) {
            if (extendsPastCell(b)) return true;
        }
        return false;
    }

    private static boolean extendsPastCell(double[] b) {
        if (b == null || b.length != 6) return false;
        for (double v : b) {
            if (Double.isNaN(v)) continue;
            if (v < -EPS || v > 1.0 + EPS) return true;
        }
        return false;
    }

    private static boolean anyNearProbeWindowEdge(List<double[]> boxes) {
        if (boxes == null) return false;
        for (double[] b : boxes) {
            if (nearProbeWindowEdge(b)) return true;
        }
        return false;
    }

    private static boolean nearProbeWindowEdge(double[] b) {
        if (b == null || b.length != 6) return false;
        for (double v : b) {
            if (Double.isNaN(v)) continue;
            if (v <= PROBE_WINDOW_MIN + PROBE_WINDOW_EDGE_TOLERANCE
                    || v >= PROBE_WINDOW_MAX - PROBE_WINDOW_EDGE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
