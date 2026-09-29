package dev.umb.legacy.legacyside;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/**
 * {@code dev.umb.bridge.api.EntityHandle} over a raw legacy {@code Entity} (ENTITY-BRIDGE
 * milestone 1/2/3). Twin of {@link TileHandleImpl}; NBT crosses as {@code byte[]} via legacy
 * {@code CompressedStreamTools}, exactly like the tile-entity bridge.
 *
 * <p>Crash isolation (same discipline as {@link TileHandleImpl}): the first throw from
 * {@link #tick()} POISONS this one handle - ticking stops for it forever, nothing propagates to
 * the caller.</p>
 */
public final class EntityHandleImpl implements EntityHandle {

    private final Entity entity;
    private boolean poisoned;
    private String poisonReason;
    private static volatile int hostNullRiderDiagCount;

    private static final int MAX_HOST_NULL_RIDER_DIAG = 16;

    public EntityHandleImpl(Entity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity");
        }
        this.entity = entity;
    }

    /** The raw legacy Entity - package-private, for UmbWorld's local entity-tracking list only. */
    Entity raw() {
        return entity;
    }

    @Override
    public void tick() {
        if (poisoned) {
            return;
        }
        try {
            entity.func_70071_h_();
        } catch (Throwable t) {
            poisoned = true;
            poisonReason = t.getClass().getName() + ": " + t.getMessage();
            System.err.println("[UMB-ENTITY] " + entity.getClass().getName() + " poisoned on tick: "
                    + poisonReason);
        }
    }

    @Override
    public byte[] saveNbt() {
        try {
            NBTTagCompound tag = new NBTTagCompound();
            // func_70039_c (writeToNBTOptional): vanilla's own "should this be saved at all" gate
            // (dead / unnamed / currently riding another entity all return false) - it also writes
            // the "id" tag EntityList.createEntityFromNBT needs to reconstruct the right subclass.
            boolean wrote = entity.func_70039_c(tag);
            if (!wrote) {
                return null;
            }
            return CompressedStreamTools.func_74798_a(tag);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] saveNbt failed for " + entity.getClass().getName() + ": " + t);
            return null;
        }
    }

    @Override
    public boolean isValid() {
        return !poisoned && !entity.field_70128_L;
    }

    @Override
    public double getX() {
        return entity.field_70165_t;
    }

    @Override
    public double getY() {
        return entity.field_70163_u;
    }

    @Override
    public double getZ() {
        return entity.field_70161_v;
    }

    @Override
    public double getMotionX() {
        return entity.field_70159_w;
    }

    @Override
    public double getMotionY() {
        return entity.field_70181_x;
    }

    @Override
    public double getMotionZ() {
        return entity.field_70179_y;
    }

    @Override
    public float getYaw() {
        return entity.field_70177_z;
    }

    @Override
    public float getPitch() {
        return entity.field_70125_A;
    }

    @Override
    public String legacyEntityId() {
        try {
            String s = EntityList.func_75621_b(entity);
            return s == null ? "unknown" : s;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String legacyEntityClassName() {
        return entity.getClass().getName();
    }

    @Override
    public String ownerNamespace() {
        try {
            cpw.mods.fml.common.registry.EntityRegistry.EntityRegistration registration =
                    cpw.mods.fml.common.registry.EntityRegistry.instance()
                            .lookupModSpawn(entity.getClass(), false);
            if (registration != null && registration.getContainer() != null) {
                return registration.getContainer().getModId();
            }
        } catch (Throwable ignored) {
            // An entity without an authoritative FML registration has no owner to invent.
        }
        return null;
    }

    @Override
    public String legacyEntityIdentity() {
        return identityOf(entity);
    }

    @Override
    public String legacyVehicleIdentity() {
        Entity vehicle = entity.field_70154_o;
        if (vehicle == null) {
            vehicle = reflectedParentEntity(entity);
        }
        return identityOf(vehicle);
    }

    @Override
    public String legacyPassengerIdentity() {
        Entity passenger = entity.field_70153_n;
        return passenger instanceof net.minecraft.entity.player.EntityPlayer
                ? null : identityOf(passenger);
    }

    private static String identityOf(Entity value) {
        return value == null ? null : Integer.toHexString(System.identityHashCode(value));
    }

    /**
     * Some legacy vehicle mods keep a child seat/hitbox's parent in a private Entity field instead
     * of vanilla's riding link.  Discover only fields whose semantic name is parent/vehicle; this
     * remains mod-neutral and avoids treating projectile owners or arbitrary entity references as
     * passenger edges.
     */
    private static Entity reflectedParentEntity(Entity child) {
        for (Class<?> type = child.getClass(); type != null && Entity.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if ((!name.contains("parent") && !name.contains("vehicle"))
                        || !Entity.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(child);
                    if (value instanceof Entity && value != child) {
                        return (Entity) value;
                    }
                } catch (Throwable ignored) {
                    // A mod-private field may be inaccessible; the vanilla riding link remains
                    // authoritative and this optional relation must never poison the handle.
                }
            }
        }
        return null;
    }

    @Override
    public float getWidth() {
        return entity.field_70130_N;
    }

    @Override
    public float getHeight() {
        return entity.field_70131_O;
    }

    @Override
    public boolean canBeCollidedWith() {
        try {
            return entity.func_70067_L();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public double[] getBoundingBox() {
        try {
            AxisAlignedBB box = entity.func_70046_E();
            if (box == null) {
                return null;
            }
            return new double[] { box.field_72340_a, box.field_72338_b, box.field_72339_c,
                    box.field_72336_d, box.field_72337_e, box.field_72334_f };
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bounded fan-out for the generic multipart collision scan below. */
    private static final int MAX_COLLISION_BOXES = 24;
    /** Corrupt-guard: no single legacy part box may span more than this (blocks per axis). */
    private static final double MAX_COLLISION_BOX_SPAN = 128.0D;

    /**
     * Extra world-space collision boxes beyond {@link #getBoundingBox}, found by type only.
     * 1.7.10 SRG runtime: "the Entity parts making up this Entity"), each part's
     * {@code func_70046_E}; (b) helper-owned world-space {@code AxisAlignedBB} state kept on the
     * entity or one level deep in a helper object/array/list.  Never names a mod class or field.
     * Null when nothing extra is known.  Never throws.
     */
    @Override
    public java.util.List<double[]> getCollisionBoxes() {
        try {
            java.util.List<double[]> out = new java.util.ArrayList<double[]>();
            collectPartBoxes(out);
            collectHelperBoxes(out);
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void collectPartBoxes(java.util.List<double[]> out) {
        net.minecraft.entity.Entity[] parts;
        try {
            parts = entity.func_70021_al();
        } catch (Throwable ignored) {
            return;
        }
        if (parts == null) {
            return;
        }
        int limit = Math.min(parts.length, MAX_COLLISION_BOXES);
        for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
            net.minecraft.entity.Entity part = parts[i];
            if (part == null || part == entity) {
                continue;
            }
            try {
                AxisAlignedBB box = part.func_70046_E();
                if (box == null) {
                    // aconst_null/areturn); only overrides expose a box through it. Fall back
                    // to the part's own field_70121_D (boundingBox), which setPosition keeps
                    // current for every entity whether it overrides the accessor or not.
                    box = partBoundingBoxField(part);
                }
                addBox(out, box);
            } catch (Throwable ignored) {
                // One unreadable part must never hide the others.
            }
        }
    }

    private static AxisAlignedBB partBoundingBoxField(net.minecraft.entity.Entity part) {
        for (Class<?> cursor = part.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                java.lang.reflect.Field field = cursor.getDeclaredField("field_70121_D");
                if (field.getType() != AxisAlignedBB.class
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(part);
                if (value instanceof AxisAlignedBB) {
                    return (AxisAlignedBB) value;
                }
                return null;
            } catch (NoSuchFieldException missing) {
                // Try the superclass.
            } catch (Throwable unreadable) {
                return null;
            }
        }
        return null;
    }

    private void collectHelperBoxes(java.util.List<double[]> out) {
        java.util.IdentityHashMap<Object, Boolean> seen =
                new java.util.IdentityHashMap<Object, Boolean>();
        collectHelperBoxesFrom(entity, out, seen, 0);
    }

    private static void collectHelperBoxesFrom(Object owner, java.util.List<double[]> out,
            java.util.IdentityHashMap<Object, Boolean> seen, int depth) {
        if (owner == null || depth > 1 || out.size() >= MAX_COLLISION_BOXES
                || seen.put(owner, Boolean.TRUE) != null) {
            return;
        }
        for (Class<?> cursor = owner.getClass(); cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            String className = cursor.getName();
            if (className.startsWith("net.minecraft.") || className.startsWith("java.")
                    || className.startsWith("dev.umb.")) {
                continue;
            }
            java.lang.reflect.Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (java.lang.reflect.Field field : fields) {
                if (out.size() >= MAX_COLLISION_BOXES) {
                    return;
                }
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                try {
                    field.setAccessible(true);
                    if (type == AxisAlignedBB.class) {
                        addBox(out, (AxisAlignedBB) field.get(owner));
                    } else if (type.isArray() && type.getComponentType() == AxisAlignedBB.class) {
                        AxisAlignedBB[] boxes = (AxisAlignedBB[]) field.get(owner);
                        if (boxes != null) {
                            int limit = Math.min(boxes.length, MAX_COLLISION_BOXES);
                            for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
                                addBox(out, boxes[i]);
                            }
                        }
                    } else if (java.util.List.class.isAssignableFrom(type)) {
                        Object value = field.get(owner);
                        if (value instanceof java.util.List<?>) {
                            java.util.List<?> items = (java.util.List<?>) value;
                            int limit = Math.min(items.size(), MAX_COLLISION_BOXES);
                            for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
                                Object item = items.get(i);
                                if (item instanceof AxisAlignedBB) {
                                    addBox(out, (AxisAlignedBB) item);
                                } else if (depth == 0) {
                                    collectHelperBoxesFrom(item, out, seen, depth + 1);
                                }
                            }
                        }
                    } else if (depth == 0 && !type.isPrimitive() && type != String.class
                            && !type.isEnum() && !type.isArray()
                            && !net.minecraft.entity.Entity.class.isAssignableFrom(type)
                            && !net.minecraft.world.World.class.isAssignableFrom(type)) {
                        collectHelperBoxesFrom(field.get(owner), out, seen, depth + 1);
                    } else if (depth == 0 && type.isArray()
                            && !type.getComponentType().isPrimitive()
                            && type.getComponentType() != String.class
                            && !net.minecraft.entity.Entity.class
                                    .isAssignableFrom(type.getComponentType())) {
                        Object array = field.get(owner);
                        if (array != null) {
                            int limit = Math.min(java.lang.reflect.Array.getLength(array), 32);
                            for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
                                collectHelperBoxesFrom(
                                        java.lang.reflect.Array.get(array, i), out, seen, depth + 1);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Optional helper state; never abort the scan.
                }
            }
        }
    }

    private static void addBox(java.util.List<double[]> out, AxisAlignedBB box) {
        double[] candidate = boxToArray(box);
        if (candidate == null) {
            return;
        }
        for (double[] existing : out) {
            if (java.util.Arrays.equals(existing, candidate)) {
                return;
            }
        }
        out.add(candidate);
    }

    private static double[] boxToArray(AxisAlignedBB box) {
        if (box == null) {
            return null;
        }
        double minX = box.field_72340_a;
        double minY = box.field_72338_b;
        double minZ = box.field_72339_c;
        double maxX = box.field_72336_d;
        double maxY = box.field_72337_e;
        double maxZ = box.field_72334_f;
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            return null;
        }
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return null;
        }
        if (maxX - minX > MAX_COLLISION_BOX_SPAN || maxY - minY > MAX_COLLISION_BOX_SPAN
                || maxZ - minZ > MAX_COLLISION_BOX_SPAN) {
            return null;
        }
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    @Override
    public String riderName() {
        try {
            return nestedRiderName(entity, 0,
                    Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>()));
        } catch (Throwable ignored) {
            // Diagnostics and passenger synchronization must never break entity ticking.
        }
        return null;
    }

    @Override
    public double[] riderOffset() {
        try {
            Entity passenger = entity.field_70153_n;
            if (!(passenger instanceof UmbPlayer)) return null;
            // The facade rider is not ticked through World.updateEntity, so nothing else runs
            // the vehicle's seat placement for it; run it here, then read the seated position.
            entity.func_70043_V();
            double dx = passenger.field_70165_t - entity.field_70165_t;
            double dy = passenger.field_70163_u - entity.field_70163_u;
            double dz = passenger.field_70161_v - entity.field_70161_v;
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                    || dx * dx + dy * dy + dz * dz > 64.0 * 64.0) {
                return null;
            }
            return new double[] {dx, dy, dz};
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String riderIdentity() {
        try {
            return nestedRiderIdentity(entity, 0,
                    Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * MC-style vehicles may expose a seat/helper entity as their direct passenger. Walk only the
     * passenger chain, with identity tracking and a small depth bound, so host rider mirroring
     * sees the real player without knowing any vehicle or seat class.
     */
    private static String nestedRiderName(Entity vehicle, int depth, Set<Entity> seen) {
        if (vehicle == null || depth >= 8 || !seen.add(vehicle)) {
            return null;
        }
        Entity passenger = vehicle.field_70153_n;
        if (passenger instanceof net.minecraft.entity.player.EntityPlayer) {
            return ((net.minecraft.entity.player.EntityPlayer) passenger).func_70005_c_();
        }
        return nestedRiderName(passenger, depth + 1, seen);
    }

    private static String nestedRiderIdentity(Entity vehicle, int depth, Set<Entity> seen) {
        if (vehicle == null || depth >= 8 || !seen.add(vehicle)) {
            return null;
        }
        Entity passenger = vehicle.field_70153_n;
        if (passenger instanceof net.minecraft.entity.player.EntityPlayer) {
            try {
                return ((net.minecraft.entity.player.EntityPlayer) passenger)
                        .func_110124_au().toString();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return nestedRiderIdentity(passenger, depth + 1, seen);
    }

    @Override
    public void setHostRider(dev.umb.bridge.api.HostPlayer player) {
        try {
            net.minecraft.entity.Entity rider = null;
            if (player != null && !(entity.field_70170_p instanceof UmbWorld)) {
                // The client facade scope temporarily swaps entity worlds to its isRemote view
                // while legacy client handlers run. A host rider cannot be resolved during that
                // window, and treating it as "no rider" dismounted the pilot every few ticks
                return;
            }
            if (player != null) {
                rider = UmbPlayer.create((UmbWorld) entity.field_70170_p, player);
            }
            if (entity.field_70153_n != rider) {
                if (entity.field_70153_n != null) {
                    traceHostNullRider(entity.field_70153_n);
                    try {
                        entity.field_70153_n.func_70078_a(null);
                    } catch (Throwable ignored) {
                        // A headless UmbPlayer has no NetHandlerPlayServer; repair the fields below.
                    }
                    entity.field_70153_n.field_70154_o = null;
                }
                if (rider != null) {
                    // mountEntity is invoked on the rider in 1.7.10; invoking it on the
                    // vehicle makes the vehicle ride the player and leaves riddenByEntity null.
                    try {
                        rider.func_70078_a(entity);
                    } catch (Throwable ignored) {
                        // The synthetic player may not have a network connection in a probe.
                    }
                    // Keep both vanilla links authoritative even when the network notification
                    // above cannot run (the same fields are what MCHeli reads on its tick path).
                    rider.field_70154_o = entity;
                    entity.field_70153_n = rider;
                } else {
                    entity.field_70153_n = null;
                }
            }
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] setHostRider failed for " + entity.getClass().getName()
                    + ": " + t);
        }
    }

    /** Records the host-to-legacy null-rider boundary with its caller chain, at a bounded rate. */
    private void traceHostNullRider(net.minecraft.entity.Entity previousRider) {
        if (hostNullRiderDiagCount >= MAX_HOST_NULL_RIDER_DIAG) {
            return;
        }
        hostNullRiderDiagCount++;
        StringBuilder line = new StringBuilder("[UMB-ENTITY] setHostRider(null)")
                .append(" entity=").append(entity.getClass().getName()).append('@')
                .append(System.identityHashCode(entity))
                .append(" rider=").append(previousRider.getClass().getName()).append('@')
                .append(System.identityHashCode(previousRider)).append(" stack=");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int frames = 0;
        for (StackTraceElement frame : stack) {
            String ownerName = frame.getClassName();
            if (ownerName.equals(EntityHandleImpl.class.getName())
                    || ownerName.equals(Throwable.class.getName())) {
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
        if (entity.field_70170_p instanceof UmbWorld) {
            ((UmbWorld) entity.field_70170_p).host().log(line.toString());
        } else {
            System.err.println(line.toString());
        }
    }

    @Override
    public boolean attack(dev.umb.bridge.api.HostPlayer attacker, String damageType, float amount) {
        try {
            net.minecraft.entity.player.EntityPlayer legacyAttacker = legacyPlayer(attacker);
            net.minecraft.util.DamageSource source = legacyAttacker == null
                    ? new net.minecraft.util.DamageSource(damageType == null ? "generic" : damageType)
                    : net.minecraft.util.DamageSource.func_76365_a(legacyAttacker);
            return entity.func_70097_a(source, amount);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] attack failed for " + entity.getClass().getName() + ": " + t);
            return false;
        }
    }

    @Override
    public boolean interact(dev.umb.bridge.api.HostPlayer player) {
        try {
            net.minecraft.entity.player.EntityPlayer legacyPlayer = legacyPlayer(player);
            LegacyInteractionDiag.before(entity, legacyPlayer);
            boolean accepted = legacyPlayer != null && entity.func_130002_c(legacyPlayer);
            LegacyInteractionDiag.completed(entity, legacyPlayer, accepted);
            return accepted;
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] interact failed for " + entity.getClass().getName() + ": " + t);
            return false;
        }
    }

    private net.minecraft.entity.player.EntityPlayer legacyPlayer(dev.umb.bridge.api.HostPlayer player) {
        if (player == null || !(entity.field_70170_p instanceof UmbWorld)) {
            return null;
        }
        return UmbPlayer.create((UmbWorld) entity.field_70170_p, player);
    }

    @Override
    public void hostRemoved() {
        try {
            // Release shared legacy capture results before the entity becomes unreachable.  The
            // renderer cache is class/model keyed, so this also prevents stale per-entity state
            // from keeping the capture graph alive until LRU pressure arrives.
            LegacyRenderCapture.invalidateEntityCaptures(entity);
            if (!entity.field_70128_L) {
                entity.func_70106_y();
            }
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] hostRemoved failed for " + entity.getClass().getName() + ": " + t);
        }
    }

    public String poisonReason() {
        return poisonReason;
    }

    @Override
    public EntityRenderCapture renderCapture(float partialTick) {
        try {
            return LegacyRenderCapture.capture(entity, partialTick);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] render capture failed for " + entity.getClass().getName() + ": " + t);
            return EntityRenderCapture.empty(entity.getClass().getName(), legacyEntityId());
        }
    }

    @Override
    public EntityRenderCapture renderCapture(float partialTick, int riderCameraMode) {
        try {
            return LegacyRenderCapture.capture(entity, partialTick, riderCameraMode);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY] render capture failed for " + entity.getClass().getName() + ": " + t);
            return EntityRenderCapture.empty(entity.getClass().getName(), legacyEntityId());
        }
    }
}
