package dev.umb.pipeline.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge v1 entrypoint driver — the M7 vertical. Given a loaded entrypoint class, finds
 * its lifecycle method (default {@code onInitialize}) by (name, parameter types), builds
 * a new instance (no-arg constructor required), and invokes the method with the supplied
 * arguments. Mod code RUNS here, against whatever the host universe handed it.
 *
 * <p>Failures are named {@link MaterializationException} kinds, never silent:
 * <ul>
 *   <li>entrypoint class absent in the mod loader → {@code MISSING_ENTRYPOINT_CLASS}
 *       (raised by the {@link ModLoader}-taking overloads, which load then drive);</li>
 *   <li>no no-arg constructor → {@code CONSTRUCTOR_MISMATCH};</li>
 *   <li>no method matching (name, parameter types) → {@code NO_LIFECYCLE_METHOD};</li>
 *   <li>the lifecycle method itself throws → reported as
 *       {@link DriverResult#cause()} with the reflection {@code InvocationTargetException}
 *       unwrapped so the entrypoint's real exception surfaces; the
 *       {@link #driveThrowing} variants raise {@code ENTRYPOINT_THREW} with that same
 *       unwrapped cause.</li>
 * </ul>
 * Method lookup is deterministic: public members first (whole class hierarchy), then any
 * declared member made accessible; the (name, parameter types) key leaves no ambiguity.
 */
public final class LifecycleDriver {

    public static final String DEFAULT_LIFECYCLE = "onInitialize";

    private LifecycleDriver() {
    }

    // ---------------- load-then-drive (MISSING_ENTRYPOINT_CLASS)

    public static DriverResult drive(ModLoader modLoader, String entrypointClassName,
                                     List<Class<?>> argTypes, List<Object> args) {
        return drive(modLoader, entrypointClassName, DEFAULT_LIFECYCLE, argTypes, args);
    }

    public static DriverResult drive(ModLoader modLoader, String entrypointClassName, String method,
                                     List<Class<?>> argTypes, List<Object> args) {
        return drive(loadEntrypoint(modLoader, entrypointClassName), method, argTypes, args);
    }

    // ---------------- drive a loaded class

    public static DriverResult drive(Class<?> entrypoint, List<Class<?>> argTypes, List<Object> args) {
        return drive(entrypoint, DEFAULT_LIFECYCLE, argTypes, args);
    }

    public static DriverResult drive(Class<?> entrypoint, String method,
                                     List<Class<?>> argTypes, List<Object> args) {
        if (argTypes.size() != args.size()) {
            throw new IllegalArgumentException("lifecycle arity mismatch: " + argTypes.size()
                    + " parameter types but " + args.size() + " arguments");
        }
        Object instance = newInstance(entrypoint);
        Method lifecycle = findLifecycle(entrypoint, method, argTypes);
        try {
            // M7 interop: a provider publishes by RETURNING its object from the lifecycle;
            // capture the return value (null for void) so the bridge can register it.
            Object returned = lifecycle.invoke(instance, args.toArray());
            return DriverResult.ok(entrypoint, lifecycle, returned);
        } catch (InvocationTargetException e) {
            // The lifecycle method THREW; surface the entrypoint's real exception.
            return DriverResult.failed(entrypoint, lifecycle, e.getCause());
        } catch (IllegalAccessException e) {
            // setAccessible was applied during lookup; this is a reflection invariant break.
            throw new IllegalStateException("lifecycle method " + lifecycle + " not invokable", e);
        }
    }

    /**
     * Finds the lifecycle method by NAME AND ARITY (parameter count) — the consuming-entry
     * path, where the parameter types cannot be known until the plan's consume directives
     * are resolved but their COUNT fixes the arity. Same deterministic discipline as
     * {@link #findLifecycle}: public members (whole hierarchy) first, then any declared
     * member made accessible. When several candidates share (name, arity) — an overload
     * the plan cannot distinguish — that is a {@code CONSUME_MISMATCH}, never a guess.
     *
     * @param entrypoint the loaded entrypoint class
     * @param name       the lifecycle method name
     * @param arity      the number of parameters the lifecycle must accept
     * @return the unique (name, arity) lifecycle method, made accessible
     * @throws MaterializationException NO_LIFECYCLE_METHOD if nothing matches, or
     *                                  CONSUME_MISMATCH if (name, arity) is ambiguous
     */
    public static Method findLifecycleMethod(Class<?> entrypoint, String name, int arity) {
        List<Method> matches = new ArrayList<>();
        for (Method cand : entrypoint.getMethods()) {
            if (cand.getName().equals(name) && cand.getParameterCount() == arity && !containsMethod(matches, cand)) {
                matches.add(cand);
            }
        }
        for (Method cand : entrypoint.getDeclaredMethods()) {
            if (cand.getName().equals(name) && cand.getParameterCount() == arity && !containsMethod(matches, cand)) {
                matches.add(cand);
            }
        }
        if (matches.isEmpty()) {
            throw new MaterializationException(MaterializationException.Kind.NO_LIFECYCLE_METHOD,
                    "no lifecycle method " + name + " with " + arity + " parameter"
                            + (arity == 1 ? "" : "s") + " on entrypoint class " + entrypoint.getName());
        }
        if (matches.size() > 1) {
            throw new MaterializationException(MaterializationException.Kind.CONSUME_MISMATCH,
                    "lifecycle method " + name + " with " + arity + " parameter"
                            + (arity == 1 ? "" : "s") + " on entrypoint class " + entrypoint.getName()
                            + " is ambiguous (the plan cannot pick an overload): "
                            + matches.stream().map(Method::toString).collect(java.util.stream.Collectors.joining(", ")));
        }
        Method m = matches.get(0);
        m.setAccessible(true);
        return m;
    }

    private static boolean containsMethod(List<Method> methods, Method cand) {
        for (Method m : methods) {
            if (m.equals(cand)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- driveThrowing (ENTRYPOINT_THREW on a throwing lifecycle)

    public static DriverResult driveThrowing(ModLoader modLoader, String entrypointClassName, String method,
                                             List<Class<?>> argTypes, List<Object> args) {
        return driveThrowing(loadEntrypoint(modLoader, entrypointClassName), method, argTypes, args);
    }

    public static DriverResult driveThrowing(Class<?> entrypoint, String method,
                                             List<Class<?>> argTypes, List<Object> args) {
        DriverResult result = drive(entrypoint, method, argTypes, args);
        if (!result.completed()) {
            throw new MaterializationException(MaterializationException.Kind.ENTRYPOINT_THREW,
                    "entrypoint method " + result.lifecycleMethod().getName() + " on "
                            + entrypoint.getName() + " threw", result.cause());
        }
        return result;
    }

    // ---------------- internals

    private static Class<?> loadEntrypoint(ModLoader loader, String name) {
        try {
            return loader.entrypointClass(name);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new MaterializationException(MaterializationException.Kind.MISSING_ENTRYPOINT_CLASS,
                    "entrypoint class " + name + " not found or unloadable in mod loader", e);
        }
    }

    private static Object newInstance(Class<?> entrypoint) {
        Constructor<?> ctor;
        try {
            ctor = entrypoint.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTOR_MISMATCH,
                    "no no-arg constructor on entrypoint class " + entrypoint.getName(), e);
        }
        try {
            ctor.setAccessible(true); // entrypoint constructors may be non-public
            return ctor.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTION_FAILED,
                    "no-arg constructor on " + entrypoint.getName() + " could not be invoked: " + e, e);
        } catch (InvocationTargetException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTION_FAILED,
                    "no-arg constructor on " + entrypoint.getName() + " threw: " + e.getCause(), e);
        }
    }

    private static Method findLifecycle(Class<?> entrypoint, String name, List<Class<?>> argTypes) {
        Class<?>[] params = argTypes.toArray(new Class<?>[0]);
        Method m;
        try {
            m = entrypoint.getMethod(name, params); // public, inherited chain
        } catch (NoSuchMethodException e) {
            m = null;
        }
        if (m == null) {
            try {
                m = entrypoint.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                m = null;
            }
        }
        if (m == null) {
            throw new MaterializationException(MaterializationException.Kind.NO_LIFECYCLE_METHOD,
                    "no lifecycle method " + display(name, argTypes) + " on entrypoint class "
                            + entrypoint.getName());
        }
        m.setAccessible(true);
        return m;
    }

    private static String display(String name, List<Class<?>> types) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types.get(i).getName());
        }
        return sb.append(')').toString();
    }
}