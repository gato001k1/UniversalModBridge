package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Small {@code sun.misc.Unsafe}-based helpers for the G2 facades : allocating {@code World}/{@code EntityPlayer} subclass instances without running their unusable constructors, and seeding (possibly {@code final}) instance fields on them afterwards....
 */
final class UmbUnsafe {

    private static Object unsafe;
    private static Method allocateInstanceM;
    private static Method objectFieldOffsetM;
    private static Method putObjectM;
    private static Method putIntM;
    private static Method putBooleanM;
    private static Method putFloatM;
    private static Method putDoubleM;
    private static Method putLongM;
    private static Method putShortM;
    private static Method putByteM;
    private static Method putCharM;

    static {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            allocateInstanceM = uc.getMethod("allocateInstance", Class.class);
            objectFieldOffsetM = uc.getMethod("objectFieldOffset", Field.class);
            putObjectM = uc.getMethod("putObject", Object.class, long.class, Object.class);
            putIntM = uc.getMethod("putInt", Object.class, long.class, int.class);
            putBooleanM = uc.getMethod("putBoolean", Object.class, long.class, boolean.class);
            putFloatM = uc.getMethod("putFloat", Object.class, long.class, float.class);
            putDoubleM = uc.getMethod("putDouble", Object.class, long.class, double.class);
            putLongM = uc.getMethod("putLong", Object.class, long.class, long.class);
            putShortM = uc.getMethod("putShort", Object.class, long.class, short.class);
            putByteM = uc.getMethod("putByte", Object.class, long.class, byte.class);
            putCharM = uc.getMethod("putChar", Object.class, long.class, char.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError("sun.misc.Unsafe unavailable for UmbUnsafe: " + t);
        }
    }

    private UmbUnsafe() {
    }

    /** Allocates an instance of {@code type} WITHOUT running any constructor; all fields start zero/null. */
    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> type) {
        try {
            return (T) allocateInstanceM.invoke(unsafe, type);
        } catch (Exception e) {
            throw new RuntimeException("cannot allocate " + type, e);
        }
    }

    /** Writes a value into an instance field (final or not), preserving its primitive width. */
    static void setField(Object target, Field field, Object value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            // Do not use putObject for boxed primitive values.  Facades are seeded through this
            // single helper, and writing an Integer into an int field corrupts adjacent object
            // state on Unsafe-backed instances (the old display/timer divide-by-zero exposed it).
            Class<?> type = field.getType();
            if (type == int.class) {
                putIntM.invoke(unsafe, target, Long.valueOf(off), Integer.valueOf(value == null ? 0 : ((Number) value).intValue()));
            } else if (type == boolean.class) {
                putBooleanM.invoke(unsafe, target, Long.valueOf(off), Boolean.valueOf(value != null && ((Boolean) value).booleanValue()));
            } else if (type == float.class) {
                putFloatM.invoke(unsafe, target, Long.valueOf(off), Float.valueOf(value == null ? 0.0f : ((Number) value).floatValue()));
            } else if (type == double.class) {
                putDoubleM.invoke(unsafe, target, Long.valueOf(off), Double.valueOf(value == null ? 0.0d : ((Number) value).doubleValue()));
            } else if (type == long.class) {
                putLongM.invoke(unsafe, target, Long.valueOf(off), Long.valueOf(value == null ? 0L : ((Number) value).longValue()));
            } else if (type == short.class) {
                putShortM.invoke(unsafe, target, Long.valueOf(off), Short.valueOf(value == null ? 0 : ((Number) value).shortValue()));
            } else if (type == byte.class) {
                putByteM.invoke(unsafe, target, Long.valueOf(off), Byte.valueOf(value == null ? 0 : ((Number) value).byteValue()));
            } else if (type == char.class) {
                putCharM.invoke(unsafe, target, Long.valueOf(off), Character.valueOf(value == null ? 0 : ((Character) value).charValue()));
            } else {
                putObjectM.invoke(unsafe, target, Long.valueOf(off), value);
            }
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setInt(Object target, Field field, int value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            putIntM.invoke(unsafe, target, Long.valueOf(off), Integer.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setBoolean(Object target, Field field, boolean value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            putBooleanM.invoke(unsafe, target, Long.valueOf(off), Boolean.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setFloat(Object target, Field field, float value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            putFloatM.invoke(unsafe, target, Long.valueOf(off), Float.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setDouble(Object target, Field field, double value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            putDoubleM.invoke(unsafe, target, Long.valueOf(off), Double.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    static void setLong(Object target, Field field, long value) {
        try {
            long off = ((Long) objectFieldOffsetM.invoke(unsafe, field)).longValue();
            putLongM.invoke(unsafe, target, Long.valueOf(off), Long.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException("cannot write field " + field, e);
        }
    }

    /** A declared field of {@code owner}, made accessible. Never invents a name it has not seen. */
    static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("no such field " + owner.getName() + "." + name, e);
        }
    }
}
