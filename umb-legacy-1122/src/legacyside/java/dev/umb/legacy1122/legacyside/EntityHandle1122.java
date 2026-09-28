package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.bridge.api.HostPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * {@code dev.umb.bridge.api.EntityHandle} over a raw 1.12.2 {@code Entity}.
 * Mirrors 1.7.10 {@code EntityHandleImpl}'s contract one method at a time.
 *
 * <p>Compile discipline for this module (see build.ps1: legacyside compiles against
 * bridge-api only): NO {@code net.minecraft} imports anywhere below - every legacy
 * member is resolved by dual MCP/SRG name against joined-1.12.2.srg at runtime, with
 * try/catch honesty (a failed probe returns the interface default, never throws).
 * Rider mounting/attack/interact stay on the interface defaults: 1.12.2 riding uses a
 * different passenger API whose entry points are not SRG-grounded yet, and mobs (the
 * v1 scope) need none of them.
 */
final class EntityHandle1122 implements EntityHandle {
    private final Object entity;
    private final Object world;
    private volatile boolean poisoned;
    private volatile String poisonReason;

    private static final int MAX_COLLISION_BOXES = 24;
    private static final double MAX_COLLISION_BOX_SPAN = 128.0D;

    EntityHandle1122(Object entity, Object world) {
        if (entity == null) throw new IllegalArgumentException("entity");
        this.entity = entity;
        this.world = world;
    }

    Object raw() {
        return entity;
    }

    @Override
    public void tick() {
        if (poisoned) return;
        try {
            invoke(entity, names("onUpdate", "func_70071_h_"));
        } catch (Throwable t) {
            poisoned = true;
            poisonReason = t.getClass().getName() + ": " + t.getMessage();
            System.err.println("[UMB-ENTITY-1122] " + entity.getClass().getName()
                    + " poisoned on tick: " + poisonReason);
        }
    }

    @Override
    public byte[] saveNbt() {
        try {
            Object tag = newTag();
            if (tag == null) return null;
            // func_70039_c (writeToNBTOptional): vanilla's own "should this be saved at
            // all" gate, which also writes the "id" tag createEntityFromNBT needs.
            Object wrote = invoke(entity, names("writeToNBTOptional", "func_70039_c"), tag);
            if (!Boolean.TRUE.equals(wrote)) return null;
            ClassLoader loader = entity.getClass().getClassLoader();
            Class<?> tools = Class.forName("net.minecraft.nbt.CompressedStreamTools", true, loader);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            invokeStatic(tools, names("writeCompressed", "func_74799_a"), tag, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY-1122] saveNbt failed for "
                    + entity.getClass().getName() + ": " + t);
            return null;
        }
    }

    @Override
    public boolean isValid() {
        try {
            return !poisoned && !fieldBoolean(entity, names("isDead", "field_70128_L"), false);
        } catch (Throwable t) {
            return !poisoned;
        }
    }

    @Override public double getX() { return fieldDouble(entity, names("posX", "field_70165_t"), 0.0D); }
    @Override public double getY() { return fieldDouble(entity, names("posY", "field_70163_u"), 0.0D); }
    @Override public double getZ() { return fieldDouble(entity, names("posZ", "field_70161_v"), 0.0D); }
    @Override public double getMotionX() { return fieldDouble(entity, names("motionX", "field_70159_w"), 0.0D); }
    @Override public double getMotionY() { return fieldDouble(entity, names("motionY", "field_70181_x"), 0.0D); }
    @Override public double getMotionZ() { return fieldDouble(entity, names("motionZ", "field_70179_y"), 0.0D); }
    @Override public float getYaw() { return fieldFloat(entity, names("rotationYaw", "field_70177_z"), 0.0F); }
    @Override public float getPitch() { return fieldFloat(entity, names("rotationPitch", "field_70125_A"), 0.0F); }

    @Override
    public float getWidth() {
        float w = fieldFloat(entity, names("width", "field_70130_N"), 0.5F);
        return Float.isFinite(w) && w > 0.0F ? w : 0.5F;
    }

    @Override
    public float getHeight() {
        float h = fieldFloat(entity, names("height", "field_70131_O"), 0.5F);
        return Float.isFinite(h) && h > 0.0F ? h : 0.5F;
    }

    @Override
    public boolean canBeCollidedWith() {
        try {
            Object out = invoke(entity, names("canBeCollidedWith", "func_70067_L"));
            return Boolean.TRUE.equals(out);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public double[] getBoundingBox() {
        try {
            Object box = invoke(entity, names("getBoundingBox", "func_70046_E"));
            return boxToArray(box);
        } catch (Throwable t) {
            return null;
        }
    }

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
        Object parts;
        try {
            parts = invoke(entity, names("getParts", "func_70021_al"));
        } catch (Throwable ignored) {
            return;
        }
        if (!(parts instanceof Object[])) return;
        Object[] array = (Object[]) parts;
        int limit = Math.min(array.length, MAX_COLLISION_BOXES);
        for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
            Object part = array[i];
            if (part == null || part == entity) continue;
            try {
                double[] box = boxToArray(invoke(part, names("getBoundingBox", "func_70046_E")));
                if (box == null) box = boxFields(part);
                addBox(out, box);
            } catch (Throwable ignored) {
                // One unreadable part must never hide the others.
            }
        }
    }

    private void collectHelperBoxes(java.util.List<double[]> out) {
        collectHelperBoxesFrom(entity, out,
                Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), 0);
    }

    private static void collectHelperBoxesFrom(Object owner, java.util.List<double[]> out,
            Set<Object> seen, int depth) {
        if (owner == null || depth > 1 || out.size() >= MAX_COLLISION_BOXES
                || !seen.add(owner)) {
            return;
        }
        String boxClass = "net.minecraft.util.math.AxisAlignedBB";
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
                if (out.size() >= MAX_COLLISION_BOXES) return;
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Class<?> type = field.getType();
                    if (type.getName().equals(boxClass)) {
                        addBox(out, boxToArray(field.get(owner)));
                    } else if (type.isArray() && type.getComponentType().getName().equals(boxClass)) {
                        Object[] boxes = (Object[]) field.get(owner);
                        if (boxes != null) {
                            int limit = Math.min(boxes.length, MAX_COLLISION_BOXES);
                            for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
                                addBox(out, boxToArray(boxes[i]));
                            }
                        }
                    } else if (depth == 0 && java.util.List.class.isAssignableFrom(type)) {
                        Object value = field.get(owner);
                        if (value instanceof java.util.List<?>) {
                            java.util.List<?> items = (java.util.List<?>) value;
                            int limit = Math.min(items.size(), MAX_COLLISION_BOXES);
                            for (int i = 0; i < limit && out.size() < MAX_COLLISION_BOXES; i++) {
                                Object item = items.get(i);
                                if (item != null && item.getClass().getName().equals(boxClass)) {
                                    addBox(out, boxToArray(item));
                                } else if (depth == 0) {
                                    collectHelperBoxesFrom(item, out, seen, depth + 1);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Optional helper state; never abort the scan.
                }
            }
        }
    }

    private static void addBox(java.util.List<double[]> out, double[] candidate) {
        if (candidate == null) return;
        for (double[] existing : out) {
            if (java.util.Arrays.equals(existing, candidate)) return;
        }
        out.add(candidate);
    }

    private static double[] boxFields(Object boxOwner) {
        try {
            Class<?> boxType = Class.forName("net.minecraft.util.math.AxisAlignedBB", true,
                    boxOwner.getClass().getClassLoader());
            java.lang.reflect.Field f = boxType.getDeclaredField("field_70121_D");
            f.setAccessible(true);
            return boxToArray(f.get(boxOwner));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double[] boxToArray(Object box) {
        if (box == null) return null;
        try {
            Class<?> t = box.getClass();
            double minX = dbl(t, box, "minX", "field_72340_a");
            double minY = dbl(t, box, "minY", "field_72338_b");
            double minZ = dbl(t, box, "minZ", "field_72339_c");
            double maxX = dbl(t, box, "maxX", "field_72336_d");
            double maxY = dbl(t, box, "maxY", "field_72337_e");
            double maxZ = dbl(t, box, "maxZ", "field_72334_f");
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
                return null;
            }
            if (maxX < minX || maxY < minY || maxZ < minZ) return null;
            if (maxX - minX > MAX_COLLISION_BOX_SPAN || maxY - minY > MAX_COLLISION_BOX_SPAN
                    || maxZ - minZ > MAX_COLLISION_BOX_SPAN) {
                return null;
            }
            return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String legacyEntityId() {
        try {
            ClassLoader loader = entity.getClass().getClassLoader();
            Class<?> list = Class.forName("net.minecraft.entity.EntityList", true, loader);
            Object id = invokeStatic(list, names("getEntityString", "func_75621_b"), entity);
            if (id != null) return String.valueOf(id);
        } catch (Throwable ignored) {
            // Fall through to the runtime class identity.
        }
        return entity.getClass().getName();
    }

    @Override
    public String legacyEntityClassName() {
        return entity.getClass().getName();
    }

    @Override
    public String ownerNamespace() {
        String id = legacyEntityId();
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : null;
    }

    @Override
    public boolean attack(HostPlayer attacker, String damageType, float amount) {
        try {
            ClassLoader loader = entity.getClass().getClassLoader();
            Class<?> dsType = Class.forName("net.minecraft.util.DamageSource", true, loader);
            java.lang.reflect.Constructor<?> ctor = dsType.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object source = ctor.newInstance(damageType == null ? "generic" : damageType);
            Object out = invoke(entity, names("attackEntityFrom", "func_70097_a"), source, amount);
            return Boolean.TRUE.equals(out);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY-1122] attack failed for "
                    + entity.getClass().getName() + ": " + t);
            return false;
        }
    }

    @Override
    public EntityRenderCapture renderCapture(float partialTick) {
        try {
            return Legacy1122EntityRenderCapture.capture(entity, world, partialTick);
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY-1122] render capture failed for "
                    + entity.getClass().getName() + ": " + t);
            return EntityRenderCapture.empty(entity.getClass().getName(), legacyEntityId());
        }
    }

    @Override
    public void hostRemoved() {
        try {
            if (!fieldBoolean(entity, names("isDead", "field_70128_L"), false)) {
                invoke(entity, names("setDead", "func_70106_y"));
            }
        } catch (Throwable ignored) {
            // Removal is best effort and must never cross the bridge boundary.
        }
    }

    String poisonReason() {
        return poisonReason;
    }

    // ---- minimal reflection helpers (this module compiles against bridge-api only) ----

    private static String[] names(String... candidates) {
        return candidates;
    }

    private static Object invoke(Object receiver, String[] names, Object... args) throws Exception {
        NoSuchMethodException missing = null;
        for (String name : names) {
            for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)
                            || m.getParameterTypes().length != args.length) continue;
                    try {
                        m.setAccessible(true);
                        return m.invoke(receiver, args);
                    } catch (NoSuchMethodError e) {
                        missing = new NoSuchMethodException(name);
                    }
                }
            }
            if (missing == null) missing = new NoSuchMethodException(names[0]);
        }
        throw missing == null ? new NoSuchMethodException(names[0]) : missing;
    }

    private static Object invokeStatic(Class<?> type, String[] names, Object... args) throws Exception {
        NoSuchMethodException missing = null;
        for (String name : names) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                if (!m.getName().equals(name)
                        || m.getParameterTypes().length != args.length
                        || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                } catch (NoSuchMethodError e) {
                    missing = new NoSuchMethodException(name);
                }
            }
            if (missing == null) missing = new NoSuchMethodException(names[0]);
        }
        throw missing == null ? new NoSuchMethodException(names[0]) : missing;
    }

    private Object newTag() throws Exception {
        Class<?> tag = Class.forName("net.minecraft.nbt.NBTTagCompound", true,
                entity.getClass().getClassLoader());
        return tag.getDeclaredConstructor().newInstance();
    }

    private static double fieldDouble(Object o, String[] names, double fallback) {
        try {
            return fieldNumber(o, names, Double.valueOf(fallback)).doubleValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float fieldFloat(Object o, String[] names, float fallback) {
        try {
            return fieldNumber(o, names, Float.valueOf(fallback)).floatValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean fieldBoolean(Object o, String[] names, boolean fallback) {
        try {
            for (String name : names) {
                for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(name);
                        if (f.getType() != Boolean.TYPE) continue;
                        f.setAccessible(true);
                        return f.getBoolean(o);
                    } catch (NoSuchFieldException e) {
                        // Try the superclass.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the fallback.
        }
        return fallback;
    }

    private static Number fieldNumber(Object o, String[] names, Number fallback) throws Exception {
        for (String name : names) {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Number) return (Number) v;
                } catch (NoSuchFieldException e) {
                    // Try the superclass.
                }
            }
        }
        return fallback;
    }

    private static double dbl(Class<?> t, Object o, String mcp, String srg) throws Exception {
        NoSuchFieldException missing = null;
        for (String name : new String[] {mcp, srg}) {
            try {
                Field f = t.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (NoSuchFieldException e) {
                missing = e;
            }
        }
        throw missing == null ? new NoSuchFieldException(mcp) : missing;
    }

    private static Object field(Class<?> owner, String[] names, Object receiver) throws Exception {
        NoSuchFieldException missing = null;
        for (String name : names) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(receiver);
                } catch (NoSuchFieldException e) {
                    missing = e;
                }
            }
        }
        throw missing == null ? new NoSuchFieldException(names[0]) : missing;
    }
}
