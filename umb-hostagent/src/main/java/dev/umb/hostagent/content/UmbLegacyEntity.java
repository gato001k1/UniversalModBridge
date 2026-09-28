package dev.umb.hostagent.content;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbThread;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Base64;

/**
 * ONE generic native entity for every legacy twin, not 149 subclasses (ENTITY-BRIDGE.md §3.1,
 * mirroring {@link UmbLegacyBlockEntity}'s "one generic type" shape for tile entities): an
 * {@link EntityType} is only an identity + factory, and the legacy class identity travels inside
 * the opaque NBT blob {@link EntityHandle#saveNbt()} produces, exactly like the tile bridge.
 *
 * <h2>Threading</h2>
 * <p>Vanilla's {@code Entity.tick() -> baseTick()} chain remains responsible for native tracking
 * and housekeeping. The legacy object is ticked by the universe-owned bridge pass, exactly once
 * per server tick; this twin only ensures a reloaded handle exists and receives synchronized state.</p>
 *
 * <h2>Position/motion authority (ENTITY-BRIDGE.md §3.4)</h2>
 * <p>The legacy {@code Entity} is authoritative for one host tick's worth of motion:
 * {@link EntityHandle#tick()} runs in the universe pass first, THEN this twin reads
 * the now-updated fields via the handle's getters and applies them with
 * {@code setPos}/{@code setDeltaMovement}/{@code setYRot}/{@code setXRot} - "legacy computes it,
 * host displays it", the same split the tile bridge uses for block/container state. Host-side
 * collision/physics is NOT pushed back into legacy (a documented, honest gap).</p>
 *
 * <h2>Removal, both directions</h2>
 * <p>Legacy-initiated: {@link #baseTick()} notices {@link EntityHandle#isValid()}{@code ==false}
 * and calls {@link #discard()} itself (setting {@link #legacyInitiatedRemoval} first so the
 * {@link #onRemoval} override below does not try to propagate a "removal" back to an entity that
 * is already dead). Host-initiated: {@link #onRemoval} propagates a genuine host-side kill/discard
 * (not a chunk-unload save, not a dimension change - see the reason check) back to legacy via
 * {@link EntityHandle#hostRemoved()}, so the two halves never diverge.</p>
 */
public final class UmbLegacyEntity extends Entity {

    /** String identity synchronized to the client renderer. */
    private static final EntityDataAccessor<String> LEGACY_CLASS_ID =
            SynchedEntityData.defineId(UmbLegacyEntity.class, EntityDataSerializers.STRING);
    /** Legacy seat offset (updateRiderPosition result relative to the entity), synced so the
     *  client seats - and therefore places the camera of - the rider exactly where the server does. */
    private static final EntityDataAccessor<Boolean> HAS_RIDER_OFFSET =
            SynchedEntityData.defineId(UmbLegacyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<org.joml.Vector3fc> RIDER_OFFSET =
            SynchedEntityData.defineId(UmbLegacyEntity.class, EntityDataSerializers.VECTOR3);
    /**
     * Client aim-box sync: the client twin never holds a legacy handle, so its
     * box used to stay the 0.5-block builder default while the server twin carried the real
     * legacy box - and 26.2 aim picking ({@code ProjectileUtil.getEntityHitResult}) tests the
     * CLIENT box. The server publishes the base box as position-relative data every sync: the
     * minimum-corner offset plus the size. Offsets stay small (a few blocks) so float precision
     * is plenty, while the corner itself rides vanilla's full-precision position tracking.
     */
    private static final EntityDataAccessor<org.joml.Vector3fc> BOX_MIN_OFFSET =
            SynchedEntityData.defineId(UmbLegacyEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<org.joml.Vector3fc> BOX_SIZE =
            SynchedEntityData.defineId(UmbLegacyEntity.class, EntityDataSerializers.VECTOR3);

    private static final String NBT_KEY = "umb_legacy_nbt";

    private volatile EntityHandle handle;
    private volatile HostWorldImpl owner;
    private volatile boolean poisoned;
    /** Set by readAdditionalSaveData before a handle exists yet (world reload); replayed once one
     *  is reconstructed via {@link LegacyBridge#restoreEntity(byte[])}. */
    private volatile byte[] pendingNbt;
    /** True for exactly the duration of a self-initiated discard() - see class javadoc. */
    private boolean legacyInitiatedRemoval;
    private String lastRiderSync;
    private int passengerDiagCount;
    private int riderStopDiagCount;
    /** Last reconciled part-collider count, for change-only diagnostics below. */
    private int lastPartCount = -1;
    /** One-shot client box/pickability report per twin lifetime. */
    private boolean clientDiagLogged;

    /** One-shot client state per twin: does the client see a real box, and pickable? */
    private void logClientBoxOnce() {
        if (clientDiagLogged) {
            return;
        }
        clientDiagLogged = true;
        try {
            AABB box = getBoundingBox();
            AgentLog.line("ENTITY-DIAG twin-client twin=" + getUUID()
                    + " pos=" + position()
                    + " box=" + (box == null ? "null"
                            : box.minX + "," + box.minY + "," + box.minZ
                            + "->" + box.maxX + "," + box.maxY + "," + box.maxZ)
                    + " classId=" + legacyClassId()
                    + " pickable=" + isPickable());
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.logClientBoxOnce", t, 2);
        }
    }

    private static final int MAX_PASSENGER_DIAG = 12;
    private static final int MAX_RIDER_STOP_DIAG = 8;
    /**
     * Client glide state for server-driven movement. Vanilla {@code LivingEntity} owns one of
     * these and pumps it every client tick; a raw {@code Entity} has none
     * ({@code getInterpolation()} returns null), so every
     * server move packet snaps the client twin to the new position with no glide in between.
     * With the default 3-tick tracking interval that is a ~6.7 Hz teleport stepping the whole
     * rendered vehicle behind its real position. Lazily created (covers Unsafe-allocated
     * headless instances too); all client packet/tick traffic runs on the one render thread,
     * so the benign publication race cannot split a glide across two handlers.
     */
    private volatile InterpolationHandler clientInterpolation;

    public UmbLegacyEntity(EntityType<?> type, Level level) {
        super(type, level);
        // The legacy entity owns movement.  The generic host twin must not apply 26.2's native
        // gravity between bridge syncs, or aircraft and other legacy movers fall even while the
        // legacy side is stationary.
        setNoGravity(true);
    }

    /** Called once, synchronously, by {@code HostWorldImpl.spawnEntity} right after construction -
     *  never by anything else, and never twice (a twin is always paired with exactly one handle for
     *  its whole lifetime). Not part of {@code dev.umb.bridge.api}. */
    void bindHandle(EntityHandle h) {
        this.handle = h;
        if (h != null) setLegacyClassId(h.legacyEntityId());
    }

    void bindHostWorld(HostWorldImpl world) {
        this.owner = world;
    }

    /**
     * Opts the generic twin into 26.2's client interpolation: {@code moveOrInterpolateTo}
     * (the single path every server move packet funnels through) glides via
     * {@code InterpolationHandler.interpolateTo} when this is non-null and snaps when it is
     * null. The default 3 glide steps match this type's tracking update interval, exactly how
     * vanilla entities smooth 20 Hz server motion into per-frame motion with no extra
     * bandwidth. Universal: every legacy vehicle shares this one twin class.
     */
    @Override
    public InterpolationHandler getInterpolation() {
        InterpolationHandler handler = clientInterpolation;
        if (handler == null) {
            handler = new InterpolationHandler(this);
            clientInterpolation = handler;
        }
        return handler;
    }

    /**
     * The native Entity implementation removes a passenger only through this family of methods.
     * Keep the bounded trace on the generic twin so it works for every legacy vehicle and exposes
     * the real caller (ServerLevel passenger validation, player stopRiding, or a legacy callback).
     */
    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        logPassengerMutation("addPassenger", passenger);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        logPassengerMutation("removePassenger", passenger);
        super.removePassenger(passenger);
        propagateHostDismountToLegacy(passenger);
    }

    @Override
    public void ejectPassengers() {
        logPassengerMutation("ejectPassengers", null);
        super.ejectPassengers();
    }

    /**
     * Host-initiated dismount propagation (universal, no mod knowledge). 26.2's own rideTick
     * sneak-dismount calls stopRiding -> removePassenger on this twin without touching the legacy
     * entity; without propagation the legacy rider stays set and the next legacy->host rider sync
     * re-mounts the player. Running the legacy entity's own dismount logic here - rider
     * mountEntity(null), 1.7.10 Entity.func_70078_a clears both riding links and seats the rider
     * on top of the ex-vehicle (UmbPlayer.mountVanillaEntity is the same body without the
     * EntityPlayerMP network notification) - clears the legacy rider, so the next sync observes
     * desired=null and stays dismounted. ejectPassengers needs no separate handling: vanilla
     * funnels every passenger through removePassenger. Server-side only: the client twin mirrors
     * removals from SetPassengers packets and owns no legacy state; a twin being discarded keeps
     * its legacy lifecycle on the onRemoval/hostRemoved path.
     */
    private void propagateHostDismountToLegacy(Entity passenger) {
        EntityHandle h = handle;
        HostWorldImpl w = owner;
        if (!shouldPropagateHostDismount(level().isClientSide(), passenger, isRemoved(), h, w)) {
            return;
        }
        try {
            w.noteHostDismount(h);
            h.setHostRider(null);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.propagateHostDismountToLegacy", t, 2);
        }
    }

    /**
     * Pure decision extracted so it is directly unit-testable without a live {@code Level}.
     * True only for a genuine host-side player removal from a live server twin that has a
     * legacy half to propagate to.
     */
    static boolean shouldPropagateHostDismount(boolean clientSide, Entity passenger,
            boolean twinRemoved, EntityHandle handle, HostWorldImpl world) {
        return !clientSide
                && passenger instanceof net.minecraft.server.level.ServerPlayer
                && !twinRemoved
                && handle != null
                && world != null;
    }

    private void logPassengerMutation(String operation, Entity passenger) {
        if (passengerDiagCount >= MAX_PASSENGER_DIAG) {
            return;
        }
        passengerDiagCount++;
        StringBuilder line = new StringBuilder("ENTITY-DIAG passenger-")
                .append(operation)
                .append(" twin=").append(getUUID())
                .append(" passenger=").append(passenger == null ? "<all>" : passenger.getClass().getName())
                .append(" passengerUuid=").append(passenger == null ? "null" : passenger.getUUID())
                .append(" passengerVehicle=").append(passenger == null || passenger.getVehicle() == null
                        ? "null" : passenger.getVehicle().getUUID())
                .append(" passengerShift=").append(passenger instanceof net.minecraft.server.level.ServerPlayer
                        && ((net.minecraft.server.level.ServerPlayer) passenger).isShiftKeyDown())
                .append(" vehicleMatch=").append(passenger != null && passenger.getVehicle() == this)
                .append(" hasPassenger=").append(passenger != null && hasPassenger(passenger))
                .append(" client=").append(level().isClientSide())
                .append(" vehicleRemoved=").append(isRemoved())
                .append(" passengerRemoved=").append(passenger != null && passenger.isRemoved())
                .append(" sameLevel=").append(passenger != null && passenger.level() == level())
                .append(" twinTickList=").append(hostTickListMembership())
                .append(" count=").append(getPassengers().size())
                .append(" stack=");
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int frames = 0;
        for (StackTraceElement frame : stack) {
            String ownerName = frame.getClassName();
            if (ownerName.equals(Thread.class.getName())
                    || ownerName.equals(UmbLegacyEntity.class.getName())) {
                continue;
            }
            if (frames++ != 0) {
                line.append(" <- ");
            }
            line.append(ownerName).append('#').append(frame.getMethodName()).append(':')
                    .append(frame.getLineNumber());
            if (frames >= 12) {
                break;
            }
        }
        AgentLog.line(line.toString());
    }

    /**
     * 26.2 ServerLevel field: the native passenger tick path only ticks a non-player
     * passenger when it is in this list, and the entity loop validates vehicle identity separately.
     * The field is private, so this is diagnostics-only reflection; a failure is rendered as an
     * explicit unknown value and can never affect riding semantics.
     */
    private String hostTickListMembership() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return "not-server";
        }
        try {
            java.lang.reflect.Field field = ServerLevel.class.getDeclaredField("entityTickList");
            field.setAccessible(true);
            Object tickList = field.get(serverLevel);
            java.lang.reflect.Method contains = tickList.getClass().getMethod("contains", Entity.class);
            return String.valueOf(contains.invoke(tickList, this));
        } catch (Throwable t) {
            return "unknown:" + t.getClass().getSimpleName();
        }
    }

    /** For tests only. */
    EntityHandle handleForTest() {
        return handle;
    }

    void setHandleForTest(EntityHandle h) {
        this.handle = h;
    }

    boolean isPoisoned() {
        return poisoned;
    }

    byte[] pendingNbtForTest() {
        return pendingNbt;
    }

    /** For tests only: {@link #ensureHandle()} is private and normally only reached from
     *  {@link #baseTick()}, which needs a live {@code Level} the test cannot construct.
     *  {@code ensureHandle} itself only does an {@code instanceof} check (never throws on a
     *  null field), so it is safe to call directly - this exposes exactly that call. */
    void ensureHandleForTest() {
        ensureHandle();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LEGACY_CLASS_ID, "");
        builder.define(HAS_RIDER_OFFSET, Boolean.FALSE);
        builder.define(RIDER_OFFSET, new org.joml.Vector3f());
        builder.define(BOX_MIN_OFFSET, new org.joml.Vector3f());
        builder.define(BOX_SIZE, new org.joml.Vector3f());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BOX_MIN_OFFSET.equals(key) || BOX_SIZE.equals(key)) {
            retainClientBox();
        }
    }

    /**
     * Rebuilds the client box from the synced position-relative data. Skips an unsynced twin
     * (zero size) and any corrupt entry - the vanilla default box stays until real data lands.
     */
    private void retainClientBox() {
        if (entityData == null) {
            return;
        }
        org.joml.Vector3fc minOffset;
        org.joml.Vector3fc size;
        try {
            minOffset = entityData.get(BOX_MIN_OFFSET);
            size = entityData.get(BOX_SIZE);
        } catch (Throwable ignored) {
            return;
        }
        double[] box = rebuildBox(getX(), getY(), getZ(), minOffset, size);
        if (box != null) {
            setBoundingBox(new AABB(box[0], box[1], box[2], box[3], box[4], box[5]));
        }
    }

    /**
     * Position-relative box encoding shared by the server publish path; directly unit-testable.
     * Returns {@code {minOffX, minOffY, minOffZ, sizeX, sizeY, sizeZ}} or null when the base box
     * is unusable - the client then keeps whatever box it already has (honest absence).
     */
    static float[] boxOffsets(double[] base, double px, double py, double pz) {
        if (base == null || base.length != 6) {
            return null;
        }
        for (double value : base) {
            if (!Double.isFinite(value)) {
                return null;
            }
        }
        if (base[3] <= base[0] || base[4] <= base[1] || base[5] <= base[2]) {
            return null;
        }
        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
            return null;
        }
        return new float[] {(float) (base[0] - px), (float) (base[1] - py), (float) (base[2] - pz),
                (float) (base[3] - base[0]), (float) (base[4] - base[1]), (float) (base[5] - base[2])};
    }

    /** Decodes {@link #boxOffsets} back into a world-space box; null when undecodable. */
    static double[] rebuildBox(double px, double py, double pz,
            org.joml.Vector3fc minOffset, org.joml.Vector3fc size) {
        if (minOffset == null || size == null) {
            return null;
        }
        float ox = minOffset.x();
        float oy = minOffset.y();
        float oz = minOffset.z();
        float dx = size.x();
        float dy = size.y();
        float dz = size.z();
        if (!(dx > 0.0F) || !(dy > 0.0F) || !(dz > 0.0F)
                || !Float.isFinite(ox) || !Float.isFinite(oy) || !Float.isFinite(oz)
                || !Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz)
                || !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
            return null;
        }
        return new double[] {px + ox, py + oy, pz + oz, px + ox + dx, py + oy + dy, pz + oz + dz};
    }

    private void setLegacyClassId(String id) {
        entityData.set(LEGACY_CLASS_ID, id == null ? "" : id);
    }

    /** Client-visible identity consumed by the generic ObjBridge renderer. */
    public String legacyClassId() {
        return entityData.get(LEGACY_CLASS_ID);
    }

    public String legacyClassName() {
        EntityHandle h = handle;
        return h == null ? "" : h.legacyEntityClassName();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        EntityDimensions dimensions = dimensionsFor(handle);
        return dimensions != null ? dimensions : super.getDimensions(pose);
    }

    static EntityDimensions dimensionsFor(EntityHandle h) {
        if (h == null) return null;
        float width = h.getWidth();
        float height = h.getHeight();
        if (!(width > 0.0F) || !(height > 0.0F)
                || !Float.isFinite(width) || !Float.isFinite(height)) {
            return null;
        }
        return EntityDimensions.scalable(width, height);
    }

    /**
     * Publishes the base box for client aim picking (see {@link #BOX_MIN_OFFSET}). Server only
     * in practice; the entityData null-guard keeps headless tests honest. A null encoding (no
     * usable base box) leaves the last published values alone rather than clearing a good box
     * with a transiently bad tick.
     */
    private void publishClientBox(EntityHandle h) {
        if (entityData == null) {
            return;
        }
        try {
            float[] encoded = h == null ? null
                    : boxOffsets(h.getBoundingBox(), h.getX(), h.getY(), h.getZ());
            if (encoded == null) {
                return;
            }
            entityData.set(BOX_MIN_OFFSET, new org.joml.Vector3f(encoded[0], encoded[1], encoded[2]));
            entityData.set(BOX_SIZE, new org.joml.Vector3f(encoded[3], encoded[4], encoded[5]));
        } catch (Throwable ignored) {
            // Optional presentation state must never break the tick.
        }
    }
    /** Validates the legacy world-space collision box before it reaches the native Entity. */
    static AABB boundsFor(EntityHandle h) {
        if (h == null) return null;
        double[] b = h.getBoundingBox();
        if (b == null || b.length != 6) return null;
        for (double value : b) {
            if (!Double.isFinite(value)) return null;
        }
        if (b[3] < b[0] || b[4] < b[1] || b[5] < b[2]) return null;
        return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** Applies the exact legacy base box after position/dimension updates on every bridge sync. */
    void applyLegacyBounds(EntityHandle h) {
        AABB bounds = boundsFor(h);
        if (bounds != null) {
            setBoundingBox(bounds);
        }
    }

    /**
     * Extra multipart/helper boxes live on dedicated {@link UmbLegacyPartTwin} children (one box
     * per child), never unioned into this twin: 26.2 aim picking takes the NEAREST clipped box,
     * so a union would swallow the aim ray for interior seat twins, while 1.7.10 aims every
     * world entity by its own small box. Standing still works - movement collision collects one
     * box per entity across parent, seats and part children alike.
     */
    private java.util.List<UmbLegacyPartTwin> partTwins;

    /**
     * The part-collider children, healed on access: Unsafe-allocated headless instances (tests)
     * skip field initializers, and every path below must degrade to "no children" instead of
     * throwing.
     */
    private java.util.List<UmbLegacyPartTwin> partTwinList() {
        if (partTwins == null) {
            partTwins = new java.util.ArrayList<>();
        }
        return partTwins;
    }

    /** For tests only. */
    int partTwinCountForTest() {
        return partTwinList().size();
    }

    /**
     * Reconciles the part-collider children with the handle's current extra boxes, called at the
     * end of every bridge sync (and once at spawn). Server only: client twins arrive through the
     * vanilla spawn/tracking packets with their boxes synced as entity data. Children delegate
     * interaction and damage to this twin, so per-box clicks mount/damage through the legacy
     * entity exactly like body clicks do. Never throws into the tick.
     */
    void syncPartTwins(EntityHandle h) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        try {
            java.util.List<double[]> extras;
            try {
                extras = h == null ? null : h.getCollisionBoxes();
            } catch (Throwable ignored) {
                extras = null;
            }
            if (extras == null) {
                extras = java.util.Collections.emptyList();
            }
            java.util.List<UmbLegacyPartTwin> twins = partTwinList();
            for (java.util.Iterator<UmbLegacyPartTwin> it = twins.iterator(); it.hasNext();) {
                if (it.next().isRemoved()) {
                    it.remove();
                }
            }
            while (twins.size() > extras.size()) {
                UmbLegacyPartTwin surplus = twins.remove(twins.size() - 1);
                if (!surplus.isRemoved()) {
                    surplus.discard();
                }
            }
            for (int i = 0; i < twins.size(); i++) {
                twins.get(i).applyBox(extras.get(i));
            }
            EntityType<UmbLegacyPartTwin> partType = Registrar.legacyPartType();
            if (partType == null) {
                // Registration runs in the pre-freeze window, before any twin can exist; a miss
                // here is a transient boot edge, retried on the next sync.
                return;
            }
            while (twins.size() < extras.size()) {
                UmbLegacyPartTwin twin = new UmbLegacyPartTwin(partType, serverLevel);
                twin.bindParent(this);
                twin.applyBox(extras.get(twins.size()));
                if (serverLevel.addFreshEntity(twin)) {
                    twins.add(twin);
                } else {
                    twin.discard();
                    break;
                }
            }
            if (twins.size() != lastPartCount) {
                lastPartCount = twins.size();
                AgentLog.line("ENTITY-DIAG part-twins twin=" + getUUID()
                        + " class=" + (h == null ? "null" : h.legacyEntityClassName())
                        + " extras=" + extras.size() + " children=" + twins.size());
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.syncPartTwins", t, 3);
        }
    }

    /** Discards every part-collider child; called from {@link #onRemoval} on both sides. */
    private void discardPartTwins() {
        for (UmbLegacyPartTwin twin : partTwinList()) {
            try {
                if (!twin.isRemoved()) {
                    twin.discard();
                }
            } catch (Throwable ignored) {
                // Removal must remain best-effort on both sides.
            }
        }
        partTwinList().clear();
    }

    @Override
    public boolean isPickable() {
        // The client twin never holds a legacy handle (only the server binds one), so a
        // handle-only check made every legacy vehicle unclickable for a real player: the
        // client never aimed at it and never sent the interact packet. The server still
        // decides via the legacy entity when the interaction arrives.
        if (level().isClientSide()) {
            return !isRemoved() && !legacyClassId().isEmpty();
        }
        return legacyCollidable();
    }

    /** Server-side collidability shared with part-collider children (no level access). */
    boolean legacyCollidable() {
        EntityHandle h = handle;
        return !isRemoved() && h != null && h.isValid() && h.canBeCollidedWith();
    }

    @Override
    public boolean isPushable() {
        return legacyCollidable();
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return legacyCollidable();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        EntityHandle h = handle;
        if (h == null) return false;
        HostPlayer attacker = null;
        if (source != null && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer) {
            attacker = new HostPlayerImpl((net.minecraft.server.level.ServerPlayer) source.getEntity());
        }
        return h.attack(attacker, source == null ? "generic" : source.getMsgId(), amount);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hit) {
        EntityHandle h = handle;
        if (h == null || !(player instanceof net.minecraft.server.level.ServerPlayer)) {
            return InteractionResult.PASS;
        }
        try {
            HostPlayer hostPlayer = new HostPlayerImpl((net.minecraft.server.level.ServerPlayer) player);
            String riderBefore = h.riderName();
            boolean accepted = h.interact(hostPlayer);
            if (accepted) {
                // Interaction is a fresh legacy-side decision; it is allowed to reassert a mount
                // even if the previous host observation was in the one-tick absence grace window.
                if (owner != null) {
                    String riderAfter = h.riderName();
                    // Canopy/GUI interactions can be accepted with no rider before or after.
                    // Only a non-null -> null transition is an actual dismount.
                    owner.noteLegacyRiderInteraction(h, riderAfter,
                            riderBefore != null && riderAfter == null);
                }
                if (h.legacyPassengerIdentity() == null) {
                    syncLegacyRider(h, true);
                }
                return InteractionResult.SUCCESS;
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.interact", t, 3);
        }
        return InteractionResult.PASS;
    }

    /**
     * Lazily creates/reconstructs the legacy {@link EntityHandle}. Two paths: a freshly spawned
     * twin already has {@link #handle} set by {@link #bindHandle} before it ever reaches a tick, so
     * this is a no-op for that case; a twin reloaded from disk has only {@link #pendingNbt} and
     * must ask the bridge to reconstruct a live legacy Entity from it (mirrors
     * {@code UmbLegacyBlockEntity.ensureHandle}'s pendingNbt replay).
     */
    private void ensureHandle() {
        if (handle != null || poisoned) {
            return;
        }
        byte[] pending = pendingNbt;
        if (pending == null) {
            return;
        }
        try {
            UmbThread.assertServer();
            if (!(level() instanceof ServerLevel serverLevel)) {
                return;
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                // universe not booted yet (a saved world with entities but no block placement has
                // triggered a boot) - honest "not ready", retried next tick, never a crash.
                return;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            EntityHandle h = bridge.restoreEntity(pending);
            if (h == null) {
                poison("restoreEntity returned null");
                return;
            }
            pendingNbt = null;
            handle = h;
            setLegacyClassId(h.legacyEntityId());
            world.registerRestored(h, this);
        } catch (Throwable t) {
            poison("ensureHandle threw: " + t);
        }
    }

    private void poison(String why) {
        if (!poisoned) {
            poisoned = true;
            AgentLog.loud("UMB-HOSTAGENT entity poisoned (uuid=" + getUUID() + "): " + why);
        }
    }

    @Override
    public void baseTick() {
        super.baseTick();
        if (level().isClientSide()) {
            // Pump the client glide: without this the interpolation targets set by incoming
            // move packets never advance and the twin still snaps (LivingEntity parity -
            // vanilla pumps getInterpolation().interpolate() on its own client tick path).
            // Never throws across the render tick; a failure just skips one glide step.
            try {
                InterpolationHandler interpolation = getInterpolation();
                if (interpolation != null) interpolation.interpolate();
            } catch (Throwable ignored) {
                // Client presentation must never break the render tick.
            }
            // Moving rebuilds the box from the handle-less default dimensions; put the synced
            // legacy box back so aim and collision keep the real size (same as part twins).
            retainClientBox();
            logClientBoxOnce();
            return;
        }
        if (poisoned) {
            return;
        }
        try {
            UmbThread.assertServer();
            ensureHandle();
            EntityHandle h = handle;
            if (h == null) {
                return;
            }
            if (!h.isValid()) {
                legacyInitiatedRemoval = true;
                discard();
            }
        } catch (Throwable t) {
            poison("baseTick threw: " + t);
        }
    }

    /** Called by the universe-owned legacy tick pass after onUpdate returns. */
    void applyLegacyState(EntityHandle h) {
        if (h == null || h != handle || !h.isValid()) {
            if (h != null && h == handle) {
                legacyInitiatedRemoval = true;
                discard();
            }
            return;
        }
        setPos(h.getX(), h.getY(), h.getZ());
        refreshDimensions();
        applyLegacyBounds(h);
        publishClientBox(h);
        setDeltaMovement(h.getMotionX(), h.getMotionY(), h.getMotionZ());
        setYRot(h.getYaw());
        setXRot(h.getPitch());
        // When legacy has a seat/hitbox child, the player is mirrored onto that child twin; do
        // not flatten the graph by mounting the same host player directly on the parent twin.
        if (h.legacyPassengerIdentity() == null) {
            syncLegacyRider(h, false);
        }
        syncRiderOffset(h);
        syncPartTwins(h);
    }

    private void syncRiderOffset(EntityHandle h) {
        double[] offset = getPassengers().isEmpty() ? null : h.riderOffset();
        if (offset == null) {
            if (entityData.get(HAS_RIDER_OFFSET)) entityData.set(HAS_RIDER_OFFSET, Boolean.FALSE);
            return;
        }
        entityData.set(RIDER_OFFSET,
                new org.joml.Vector3f((float) offset[0], (float) offset[1], (float) offset[2]));
        if (!entityData.get(HAS_RIDER_OFFSET)) entityData.set(HAS_RIDER_OFFSET, Boolean.TRUE);
    }

    /** Seats the passenger where the legacy vehicle's own updateRiderPosition put its rider;
     *  the native default attachment (entity origin + height) is inside most legacy models. */
    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        if (hasPassenger(passenger) && entityData.get(HAS_RIDER_OFFSET)) {
            org.joml.Vector3fc offset = entityData.get(RIDER_OFFSET);
            moveFunction.accept(passenger, getX() + offset.x(), getY() + offset.y(),
                    getZ() + offset.z());
            return;
        }
        super.positionRider(passenger, moveFunction);
    }

    private void syncLegacyRider(EntityHandle handle, boolean force) {
        String desiredName = handle == null ? null : handle.riderName();
        String desiredIdentity = handle == null ? null : handle.riderIdentity();
        HostWorldImpl hostWorld = owner;
        if (!force && hostWorld != null && !hostWorld.allowLegacyRiderMirror(handle, desiredName)) {
            // A host rider was already observed and has now disappeared.  Do not re-mount it
            // while HostWorldImpl waits out the empty-passenger grace; this is the real
            // host-dismount direction.  If no host rider has ever been observed, the legacy
            // interaction is still allowed to retry startRiding on the next tick.
            return;
        }
        if (shouldPreserveHostPassenger(force, desiredName, getPassengers().size())) {
            String value = "desired=null preserve-host-passenger passengers=" + getPassengers().size();
            if (!value.equals(lastRiderSync)) {
                lastRiderSync = value;
                AgentLog.line("ENTITY-DIAG rider-sync " + value);
            }
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) return;
        StringBuilder trace = new StringBuilder("desired=").append(desiredName);
        for (net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            if (player == null) continue;
            boolean desired = matchesLegacyRider(desiredIdentity, desiredName,
                    player.getUUID().toString(), player.getScoreboardName());
            if (desired && player.getVehicle() != this) {
                boolean started = player.startRiding(this, true, true);
                trace.append(" player=").append(player.getScoreboardName())
                        .append(" startRiding=").append(started);
            } else if (!desired && player.getVehicle() == this) {
                logLegacyRiderStop(player);
                player.stopRiding();
                trace.append(" player=").append(player.getScoreboardName()).append(" stopRiding=true");
            }
        }
        trace.append(" passengers=").append(getPassengers().size());
        String value = trace.toString();
        if (!value.equals(lastRiderSync)) {
            lastRiderSync = value;
            AgentLog.line("ENTITY-DIAG rider-sync " + value);
        }
    }

    static boolean matchesLegacyRider(String desiredIdentity, String desiredName,
            String hostIdentity, String hostName) {
        // The legacy player facade's UUID is not guaranteed to equal the host player's UUID
        // (legacy GameProfile ids can be name-derived), so an identity miss falls back to name.
        return (desiredIdentity != null && desiredIdentity.equals(hostIdentity))
                || (desiredName != null && desiredName.equals(hostName));
    }

    /**
     * A host passenger is the authoritative native graph while it is present.  A legacy-null
     * observation can be one bridge ordering tick behind that graph; mirroring it as a host
     * stopRiding would create the live six-second eject loop.  A forced interaction remains
     * authoritative for an explicit legacy-side decision.
     */
    static boolean shouldPreserveHostPassenger(boolean force, String desiredName, int hostPassengers) {
        return !force && desiredName == null && hostPassengers > 0;
    }

    /** Records the host-side stopRiding caller at a bounded rate for live mount diagnostics. */
    private void logLegacyRiderStop(net.minecraft.server.level.ServerPlayer player) {
        if (riderStopDiagCount >= MAX_RIDER_STOP_DIAG) {
            return;
        }
        riderStopDiagCount++;
        StringBuilder line = new StringBuilder("ENTITY-DIAG legacy-rider-stop")
                .append(" twin=").append(getUUID())
                .append(" player=").append(player.getScoreboardName())
                .append(" playerVehicle=").append(player.getVehicle() == null
                        ? "null" : player.getVehicle().getUUID())
                .append(" passengers=").append(getPassengers().size())
                .append(" stack=");
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int frames = 0;
        for (StackTraceElement frame : stack) {
            String ownerName = frame.getClassName();
            if (ownerName.equals(Thread.class.getName())
                    || ownerName.equals(UmbLegacyEntity.class.getName())) {
                continue;
            }
            if (frames++ > 0) {
                line.append(" <- ");
            }
            line.append(ownerName).append('#').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (frames >= 10) {
                break;
            }
        }
        AgentLog.line(line.toString());
    }

    void removeFromLegacy() {
        legacyInitiatedRemoval = true;
        discard();
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        discardPartTwins();
        invalidateRenderCapture();
        super.onRemoval(reason);
        HostWorldImpl w = owner;
        if (w != null) w.twinRemoved(this, handle);
        if (!shouldPropagateRemoval(legacyInitiatedRemoval, reason)) {
            // either legacy already dead (nothing to propagate back), or this is
            // UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER / CHANGED_DIMENSION - not a kill, the legacy
            // entity keeps living, and its state is persisted separately via addAdditionalSaveData
            // (called by the normal save pipeline regardless of reason).
            return;
        }
        EntityHandle h = handle;
        if (h != null) {
            try {
                h.hostRemoved();
            } catch (Throwable t) {
                AgentLog.error("UmbLegacyEntity.onRemoval (hostRemoved)", t, 3);
            }
        }
    }

    private void invalidateRenderCapture() {
        try {
            Class<?> renderer = Class.forName("dev.umb.objbridge.entity.LegacyEntityRenderer");
            renderer.getMethod("invalidateEntityCaptures", String.class)
                    .invoke(null, legacyClassId());
        } catch (Throwable ignored) {
            // The hostagent can run without objbridge; removal must remain best-effort.
        }
    }

    /**
     * Pure decision extracted so it is directly unit-testable without a live {@code Level}
     * (constructing/discarding a real {@code Entity} needs one). True only
     * for a genuine host-initiated kill/discard that did NOT originate from this
     * twin noticing the legacy entity was already dead.
     */
    static boolean shouldPropagateRemoval(boolean legacyInitiatedRemoval, RemovalReason reason) {
        if (legacyInitiatedRemoval) {
            return false;
        }
        return reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        try {
            byte[] blob = currentBlob();
            if (blob != null) {
                out.putString(NBT_KEY, Base64.getEncoder().encodeToString(blob));
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.addAdditionalSaveData", t, 3);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        try {
            in.getString(NBT_KEY).ifPresent(this::acceptEncodedBlob);
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyEntity.readAdditionalSaveData", t, 3);
        }
    }

    private byte[] currentBlob() {
        EntityHandle h = handle;
        if (h != null) {
            try {
                return h.saveNbt();
            } catch (Throwable t) {
                AgentLog.error("UmbLegacyEntity.currentBlob (handle.saveNbt)", t, 2);
                return null;
            }
        }
        return pendingNbt;
    }

    private void acceptEncodedBlob(String base64) {
        try {
            byte[] blob = Base64.getDecoder().decode(base64);
            EntityHandle h = handle;
            if (h == null) {
                pendingNbt = blob;
            }
            // if a handle already exists there is nothing to do with a freshly-read blob: a live
            // handle IS the source of truth (see EntityHandle's javadoc - no loadNbt exists on
            // purpose, unlike TileHandle, because an Entity's own constructor already carries all
            // its state; reconstruction only ever happens through restoreEntity into a NEW handle).
        } catch (IllegalArgumentException e) {
            AgentLog.error("UmbLegacyEntity.acceptEncodedBlob: bad base64", e, 2);
        }
    }
}
