package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection onto the package-private FML statics that LaunchWrapper's tweakers would normally set.
 *
 * <p>Every target here is a plain 1.7.10 application class in the same (unnamed) module, so
 * {@code setAccessible} needs no {@code --add-opens}. Nothing in this class touches JDK internals.</p>
 */
final class Statics {

    private Statics() {
    }

    static void set(Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot set " + owner.getName() + "." + field, e);
        }
    }

    static Object get(Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + owner.getName() + "." + field, e);
        }
    }

    static Object getInstance(Object target, Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + owner.getName() + "." + field, e);
        }
    }

    static Object call(Class<?> owner, String method, Class<?>[] sig, Object[] args) {
        try {
            Method m = owner.getDeclaredMethod(method, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new IllegalStateException("cannot call " + owner.getName() + "." + method, e);
        }
    }
}
