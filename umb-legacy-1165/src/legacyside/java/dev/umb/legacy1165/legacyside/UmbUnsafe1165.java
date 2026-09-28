package dev.umb.legacy1165.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small Unsafe seam for the headless 1.16.5 server facade. */
final class UmbUnsafe1165 {
    private static final Object UNSAFE;
    private static final Method ALLOCATE;
    private static final Method OBJECT_OFFSET;
    private static final Method PUT_OBJECT;
    private static final Method STATIC_BASE;
    private static final Method STATIC_OFFSET;

    static {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field field = type.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = field.get(null);
            ALLOCATE = type.getMethod("allocateInstance", Class.class);
            OBJECT_OFFSET = type.getMethod("objectFieldOffset", Field.class);
            PUT_OBJECT = type.getMethod("putObject", Object.class, long.class, Object.class);
            STATIC_BASE = type.getMethod("staticFieldBase", Field.class);
            STATIC_OFFSET = type.getMethod("staticFieldOffset", Field.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError("sun.misc.Unsafe unavailable: " + t);
        }
    }

    private UmbUnsafe1165() {
    }

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> type) {
        try {
            return (T) ALLOCATE.invoke(UNSAFE, type);
        } catch (Exception e) {
            throw new RuntimeException("cannot allocate " + type.getName(), e);
        }
    }

    static void setField(Object target, Field field, Object value) {
        try {
            long offset = ((Long) OBJECT_OFFSET.invoke(UNSAFE, field)).longValue();
            PUT_OBJECT.invoke(UNSAFE, target, Long.valueOf(offset), value);
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setStaticField(Field field, Object value) {
        try {
            Object base = STATIC_BASE.invoke(UNSAFE, field);
            long offset = ((Long) STATIC_OFFSET.invoke(UNSAFE, field)).longValue();
            PUT_OBJECT.invoke(UNSAFE, base, Long.valueOf(offset), value);
        } catch (Exception e) {
            throw new RuntimeException("cannot write static field " + field, e);
        }
    }

    static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("no such field " + owner.getName() + "." + name, e);
        }
    }
}
