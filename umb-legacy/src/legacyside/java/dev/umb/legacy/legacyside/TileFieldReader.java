package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Generic, mod-free reflection walk used by {@link TileHandleImpl#snapshotFields} - reads a named
 * field or invokes a named zero-arg method off a live object, searching up the class hierarchy so
 * an inherited field/method (a shared turret base class, {@code java.lang.Enum#ordinal()}, a vanilla
 * {@code ArrayList#size()}) resolves the same way a direct one does. This is exactly the bounded
 * "at most one extra hop past the tile entity" shape umb-guimap's TILE-FIELD-REQUIREMENTS.md already
 * proved is everything a live snapshot needs - no mod-specific name is ever hardcoded here, the
 * names come from the caller's own {@code dev.umb.bridge.api.FieldPath}, itself built from
 * gui-profile.json's DATA (see {@code GuiProfile.TileFieldRef} on the host side).
 */
final class TileFieldReader {
    private TileFieldReader() {
    }

    /** One hop: a plain field read, or a zero-arg method call when {@code accessor}. Returns null
     *  (never throws) the instant the field/method can't be found or the call itself fails - the
     *  caller reports that whole path absent rather than propagating a partial/guessed result. */
    static Object readHop(Object cur, String name, boolean accessor) {
        if (cur == null || name == null) {
            return null;
        }
        try {
            if (accessor) {
                Method m = findMethod(cur.getClass(), name);
                if (m == null) {
                    return null;
                }
                m.setAccessible(true);
                return m.invoke(cur);
            }
            Field f = findField(cur.getClass(), name);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            return f.get(cur);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> c, String name) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getName().equals(name)) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Widens the final scalar to a double, or null if it isn't one - the walk always terminates at
     *  a primitive by construction (every leaf umb-guimap's TILE-FIELD-REQUIREMENTS.md extracted is
     *  a scalar: I/J/Z/S/B/F/D), so anything else here means the wrong hop count was supplied and
     *  the path must be reported absent, never guessed at. */
    static Double toDouble(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? 1.0 : 0.0;
        }
        if (v instanceof Character) {
            return (double) ((Character) v).charValue();
        }
        return null;
    }
}
