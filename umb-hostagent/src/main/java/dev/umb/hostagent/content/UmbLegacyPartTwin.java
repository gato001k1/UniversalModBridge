package dev.umb.hostagent.content;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One small native collider per legacy extra collision box, not one giant union on the parent.
 *
 * <p>26.2 contributes exactly one box per entity to movement collision and aim picking, so a
 * single union box makes a whole airframe one aim target and per-seat clicks can never resolve
 * to a seat. Splitting the same world-space boxes across child entities restores both: standing
 * works through many collidable boxes, and aiming resolves to the nearest small one.</p>
 *
 * <p>Children are pure host-side colliders: they never tick legacy state, never carry riders,
 * never persist, and render nothing. Interaction and damage delegate to the parent twin, whose
 * legacy entity owns the mount/damage decision, like 1.7.10 where an unspawned hitbox part is
 * not itself aimable.</p>
 *
 * <p>Box sync: the server sets the exact world-space box; vanilla position tracking carries the
 * box-minimum corner at full double precision and a synced float extents vector carries the
 * size, so the client rebuilds the same box without a custom packet. The client re-applies it
 * every tick as a guard against vanilla dimension recomputes.</p>
 */
public final class UmbLegacyPartTwin extends Entity {

    /** Half-open size (dx, dy, dz) of the box whose minimum corner is this twin's position. */
    private static final EntityDataAccessor<org.joml.Vector3fc> EXTENTS =
            SynchedEntityData.defineId(UmbLegacyPartTwin.class, EntityDataSerializers.VECTOR3);

    private UmbLegacyEntity parent;

    public UmbLegacyPartTwin(EntityType<?> type, Level level) {
        super(type, level);
        // Same split as the parent twin: legacy owns movement, the host only displays.
        setNoGravity(true);
    }

    void bindParent(UmbLegacyEntity parent) {
        this.parent = parent;
    }

    /** For tests only. */
    UmbLegacyEntity parentForTest() {
        return parent;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(EXTENTS, new org.joml.Vector3f());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (EXTENTS.equals(key)) {
            retainBox();
        }
    }

    /**
     * Applies one authoritative world-space box ({@code minX,minY,minZ,maxX,maxY,maxZ}).
     * Invalid input is ignored, never thrown - a corrupt legacy box must not unseat the rest.
     */
    void applyBox(double[] box) {
        if (!isValidBox(box)) {
            return;
        }
        setPos(box[0], box[1], box[2]);
        setBoundingBox(new AABB(box[0], box[1], box[2], box[3], box[4], box[5]));
        if (entityData != null) {
            entityData.set(EXTENTS, new org.joml.Vector3f(
                    (float) (box[3] - box[0]), (float) (box[4] - box[1]), (float) (box[5] - box[2])));
        }
    }

    /** Pure validation shared by the server apply path; directly unit-testable. */
    static boolean isValidBox(double[] box) {
        if (box == null || box.length != 6) {
            return false;
        }
        for (double value : box) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return box[3] > box[0] && box[4] > box[1] && box[5] > box[2];
    }

    @Override
    public void baseTick() {
        super.baseTick();
        if (isClientTwin()) {
            // Self-healing client box: vanilla recomputes boxes from dimensions on several
            // paths, which would collapse this box to the square-footprint default below.
            retainBox();
            logClientBoxOnce();
            return;
        }
        UmbLegacyEntity p = parent;
        if (p == null || p.isRemoved()) {
            discard();
        }
    }

    /** One-shot client state per twin: does this twin exist here, with what box, pickable? */
    private boolean clientDiagLogged;
    private boolean clientSyncedDiagLogged;

    private void logClientBoxOnce() {
        if (!clientDiagLogged) {
            clientDiagLogged = true;
            AABB box = null;
            try {
                box = getBoundingBox();
            } catch (Throwable ignored) {
                // Report the failure instead of throwing out of the tick.
            }
            dev.umb.hostagent.AgentLog.line("ENTITY-DIAG part-twin-client twin=" + getUUID()
                    + " pos=" + position()
                    + " box=" + boxText(box)
                    + " extents=" + extentsText()
                    + " pickable=" + isPickable());
        }
        if (!clientSyncedDiagLogged && hasBox()) {
            clientSyncedDiagLogged = true;
            AABB box = null;
            try {
                box = getBoundingBox();
            } catch (Throwable ignored) {
            }
            dev.umb.hostagent.AgentLog.line("ENTITY-DIAG part-twin-client-synced twin=" + getUUID()
                    + " box=" + boxText(box));
        }
    }

    private String extentsText() {
        if (entityData == null) {
            return "no-data";
        }
        try {
            org.joml.Vector3fc extents = entityData.get(EXTENTS);
            return extents == null ? "null"
                    : extents.x() + "," + extents.y() + "," + extents.z();
        } catch (Throwable t) {
            return "unreadable:" + t.getClass().getSimpleName();
        }
    }

    private static String boxText(AABB box) {
        if (box == null) {
            return "null";
        }
        return box.minX + "," + box.minY + "," + box.minZ
                + "->" + box.maxX + "," + box.maxY + "," + box.maxZ;
    }

    /** Rebuilds the box from the synced position + extents; skips a not-yet-synced twin. */
    private void retainBox() {
        if (entityData == null) {
            return;
        }
        org.joml.Vector3fc extents;
        try {
            extents = entityData.get(EXTENTS);
        } catch (Throwable ignored) {
            return;
        }
        if (extents == null || !(extents.x() > 0.0F) || !(extents.y() > 0.0F) || !(extents.z() > 0.0F)
                || !Float.isFinite(extents.x()) || !Float.isFinite(extents.y())
                || !Float.isFinite(extents.z())) {
            return;
        }
        Vec3 pos = position();
        setBoundingBox(new AABB(pos.x, pos.y, pos.z,
                pos.x + extents.x(), pos.y + extents.y(), pos.z + extents.z()));
    }

    /** Null level (headless tests) reads as server, matching vanilla's non-null assumption. */
    private boolean isClientTwin() {
        Level level = level();
        return level != null && level.isClientSide();
    }

    /** True once a real (non-degenerate) box has been applied. Null-safe for construction. */
    private boolean hasBox() {
        AABB box = null;
        try {
            box = getBoundingBox();
        } catch (Throwable ignored) {
            return false;
        }
        return box != null
                && box.getXsize() > 1.0E-6 && box.getYsize() > 1.0E-6 && box.getZsize() > 1.0E-6;
    }

    @Override
    public boolean isPickable() {
        if (isClientTwin()) {
            return !isRemoved() && hasBox();
        }
        return parentCollidable();
    }

    @Override
    public boolean isPushable() {
        if (isClientTwin()) {
            return !isRemoved() && hasBox();
        }
        return parentCollidable();
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return isPushable();
    }

    /**
     * The parent's server collidability without touching its level (headless-safe, unlike the
     * client-branching {@code isPickable} above).
     */
    private boolean parentCollidable() {
        UmbLegacyEntity p = parent;
        return p != null && p.legacyCollidable();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hit) {
        UmbLegacyEntity p = parent;
        if (p == null || p.isRemoved()) {
            return InteractionResult.PASS;
        }
        return p.interact(player, hand, hit);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        UmbLegacyEntity p = parent;
        if (p == null || p.isRemoved()) {
            return false;
        }
        return p.hurtServer(level, source, amount);
    }

    /**
     * Best-effort dimensions for vanilla paths that size from them; the real box always comes
     * from {@link #applyBox}/{@link #retainBox}, never from here.
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        AABB box = null;
        try {
            box = getBoundingBox();
        } catch (Throwable ignored) {
            return super.getDimensions(pose);
        }
        if (box == null) {
            return super.getDimensions(pose);
        }
        double dx = box.getXsize();
        double dy = box.getYsize();
        double dz = box.getZsize();
        if (!(dx > 0.0D) || !(dy > 0.0D) || !(dz > 0.0D)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return super.getDimensions(pose);
        }
        return EntityDimensions.scalable((float) Math.max(dx, dz), (float) dy);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        // Never persisted: the parent re-creates children from live legacy boxes on sync.
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        // See above: part twins are never loaded from disk.
    }
}
