// UMB Snapshot — reflection helpers for SRG-named runtime members.
// SPDX-License-Identifier: CC0-1.0
package net.umb.snapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Why reflection at all?
 *
 * The compile-time vanilla jar (research/out/1.7.10-client-srg.jar) carries SRG
 * METHOD names but still-obfuscated FIELD names, and it is the UNPATCHED vanilla
 * jar, so it has none of Forge's binpatched additions (hasTileEntity, createTileEntity,
 * getHarvestTool, getHarvestLevel, ...). At runtime under production Forge both
 * fields and methods are SRG-named and Forge's additions are present. So:
 *   - vanilla METHODS  -> called directly (SRG names match compile jar and runtime),
 *   - vanilla FIELDS   -> read here by SRG name,
 *   - Forge additions  -> invoked here by their plain (unobfuscated) name.
 *
 * Every lookup accepts a list of candidate names so a dev-mapped (MCP) runtime
 * would also resolve; production only ever hits the first (SRG) name.
 */
public final class Refl {

    private Refl() {
    }

    public static Field findField(Class<?> owner, String... names) {
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    /** Instance/static field read; {@code target} may be null for statics. */
    public static Object get(Class<?> owner, Object target, String... names) throws Exception {
        Field f = findField(owner, names);
        if (f == null) {
            throw new NoSuchFieldException(join(names) + " on " + owner.getName());
        }
        return f.get(target);
    }

    public static Object getOrNull(Class<?> owner, Object target, String... names) {
        try {
            return get(owner, target, names);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Method findMethod(Class<?> owner, Class<?>[] sig, String... names) {
        for (Class<?> k = owner; k != null; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = k.getDeclaredMethod(n, sig);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // try next
                }
            }
        }
        // Fall back to the public view (picks up interface default-ish / inherited public methods).
        for (String n : names) {
            try {
                Method m = owner.getMethod(n, sig);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // try next
            }
        }
        return null;
    }

    public static Object call(Method m, Object target, Object... args) throws Exception {
        if (m == null) {
            throw new NoSuchMethodException("(unresolved)");
        }
        return m.invoke(target, args);
    }

    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable c = t;
        if (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getName() + (msg == null ? "" : ": " + msg);
    }

    private static String join(String[] names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(names[i]);
        }
        return sb.toString();
    }
}
