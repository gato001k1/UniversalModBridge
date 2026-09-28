package dev.umb.legacy1122.legacyside;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Java-25-safe implementations used by the real Forge EnumHelper body rewrite. */
public final class EnumHelperShim {
    private static Object unsafe;
    private static Method staticFieldBase, staticFieldOffset, objectFieldOffset, putObject, privateLookupIn;
    static {
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            Field f = u.getDeclaredField("theUnsafe"); f.setAccessible(true); unsafe = f.get(null);
            staticFieldBase = u.getMethod("staticFieldBase", Field.class);
            staticFieldOffset = u.getMethod("staticFieldOffset", Field.class);
            objectFieldOffset = u.getMethod("objectFieldOffset", Field.class);
            putObject = u.getMethod("putObject", Object.class, long.class, Object.class);
        } catch (Throwable ignored) { unsafe = null; }
        try { privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class); }
        catch (Throwable ignored) { privateLookupIn = null; }
    }
    private EnumHelperShim() { }

    public static void setup() { }

    public static Enum<?> makeEnum(Class<?> type, String name, int ordinal,
                                   Class<?>[] extraTypes, Object[] extraValues) throws Exception {
        if (privateLookupIn == null) throw new IllegalStateException("MethodHandles.privateLookupIn unavailable");
        Class<?>[] params = new Class<?>[extraTypes.length + 2];
        params[0] = String.class; params[1] = int.class;
        System.arraycopy(extraTypes, 0, params, 2, extraTypes.length);
        Object[] values = new Object[extraValues.length + 2];
        values[0] = name; values[1] = Integer.valueOf(ordinal);
        System.arraycopy(extraValues, 0, values, 2, extraValues.length);
        try {
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) privateLookupIn.invoke(null, type, MethodHandles.lookup());
            MethodHandle ctor = lookup.findConstructor(type, MethodType.methodType(void.class, params));
            return (Enum<?>) ctor.invokeWithArguments(values);
        } catch (Throwable t) {
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception("cannot construct enum " + type.getName() + "." + name, t);
        }
    }

    public static void setFailsafeFieldValue(Field field, Object target, Object value) throws Exception {
        field.setAccessible(true);
        if (!Modifier.isFinal(field.getModifiers())) { field.set(target, value); return; }
        if (unsafe == null) throw new IllegalStateException("sun.misc.Unsafe unavailable");
        if (Modifier.isStatic(field.getModifiers())) {
            putObject.invoke(unsafe, staticFieldBase.invoke(unsafe, field),
                    staticFieldOffset.invoke(unsafe, field), value);
        } else {
            putObject.invoke(unsafe, target, objectFieldOffset.invoke(unsafe, field), value);
        }
    }
}
