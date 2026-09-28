package dev.umb.pipeline.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge v0 native-object materializer. Constructs a REAL instance of a host class —
 * looked up by binary name through a {@link HostUniverse}, built via its constructor
 * ({@link java.lang.reflect.Constructor#setAccessible} as needed) — then wraps it in a
 * {@link java.lang.reflect.Proxy} implementing a legacy interface so legacy code sees its
 * own API while every call lands on the modern host object. This is the "content
 * materializes as host-native objects, not sandbox fakes" property of the mission
 * (docs/ARCHITECTURE.md, §2).
 *
 * <p>No silent fakes (D4 discipline): every failure lands as a named
 * {@link MaterializationException} identifying exactly what was absent — host class,
 * host method binding target, host constructor, or legacy method binding. Nothing is ever
 * stubbed, no-op-ed, or replaced with a mock; if the host method threw, the cause chain
 * carries the host's real exception (InvocationTargetException is unwrapped).
 *
 * <p>Determinism: dispatch is keyed by {@link MethodSignature} (name + parameter types),
 * no runtime randomness. Bindings are validated in the MAP'S iteration order, so pass a
 * {@link java.util.LinkedHashMap} to make which binding fails first reproducible.
 */
public final class Materializer {

    private Materializer() {
    }

    /**
     * @param universe        the host universe to load the host class from
     * @param hostClassName   dotted binary name of the host class to construct
     * @param ctorArgTypes    constructor parameter types, matched against the host class
     * @param ctorArgs        constructor arguments, same size and order as {@code ctorArgTypes}
     * @param legacyInterface the legacy API to expose; must be an interface
     * @param bindings        legacy {@link MethodSignature} -> {@link HostCall}; validation
     *                        order for host-method targets is the map's iteration order
     * @return the materialized wrapper (proxy + real host instance + host class)
     * @throws MaterializationException anything required was absent or failed
     */
    public static Materialized materialize(
            HostUniverse universe,
            String hostClassName,
            List<Class<?>> ctorArgTypes,
            List<Object> ctorArgs,
            Class<?> legacyInterface,
            Map<MethodSignature, HostCall> bindings) {

        if (!legacyInterface.isInterface()) {
            throw new IllegalArgumentException("legacyInterface must be an interface: " + legacyInterface.getName());
        }
        Class<?> hostClass = loadHostClass(universe, hostClassName);
        Object hostInstance = construct(hostClass, ctorArgTypes, ctorArgs, hostClassName);
        validateBindings(hostClass, bindings, hostClassName);

        Object proxy = proxyFor(legacyInterface, hostInstance, hostClass, bindings);
        return new Materialized(proxy, hostInstance, hostClass);
    }

    // ---------------- materialization steps

    private static Class<?> loadHostClass(HostUniverse universe, String name) {
        try {
            return universe.hostClass(name);
        } catch (ClassNotFoundException | LinkageError e) {
            // LinkageError (NoClassDefFoundError on an unloadable supertype chain) still
            // means the named host class is not usable: name it, keep the cause.
            throw new MaterializationException(MaterializationException.Kind.MISSING_HOST_CLASS,
                    "host class " + name + " not found or unloadable in host universe", e);
        }
    }

    private static Object construct(Class<?> hostClass, List<Class<?>> argTypes, List<Object> args,
                                    String hostClassName) {
        if (argTypes.size() != args.size()) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTOR_MISMATCH,
                    "constructor arity mismatch on " + hostClassName + ": "
                            + argTypes.size() + " parameter types but " + args.size() + " arguments");
        }
        Constructor<?> ctor;
        try {
            ctor = hostClass.getDeclaredConstructor(argTypes.toArray(new Class[0]));
        } catch (NoSuchMethodException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTOR_MISMATCH,
                    "no constructor (" + typeList(argTypes) + ") on " + hostClassName, e);
        }
        try {
            ctor.setAccessible(true); // host constructors may be non-public
            return ctor.newInstance(args.toArray());
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTION_FAILED,
                    "constructor (" + typeList(argTypes) + ") on " + hostClassName
                            + " could not be invoked: " + e, e);
        } catch (InvocationTargetException e) {
            throw new MaterializationException(MaterializationException.Kind.CONSTRUCTION_FAILED,
                    "constructor (" + typeList(argTypes) + ") on " + hostClassName + " threw: " + e.getCause(), e);
        }
    }

    /**
     * Every binding must name a method the host class actually exposes; the first one in
     * iteration order that does not fails the materialization. Iterating the caller's map
     * (a LinkedHashMap for reproducible order) means the same inputs always produce the
     * same failure ordering.
     */
    static void validateBindings(Class<?> hostClass, Map<MethodSignature, HostCall> bindings,
                                 String hostClassName) {
        for (Map.Entry<MethodSignature, HostCall> e : bindings.entrySet()) {
            MethodSignature sig = e.getKey();
            if (findHostMethod(hostClass, sig) == null) {
                throw new MaterializationException(MaterializationException.Kind.MISSING_HOST_METHOD,
                        "host method " + sig.display() + " not found on " + hostClassName);
            }
        }
    }

    /** Public (inherited included), falling back to any declared member with setAccessible. */
    private static Method findHostMethod(Class<?> hostClass, MethodSignature sig) {
        Class<?>[] params = sig.parameterTypes().toArray(new Class[0]);
        Method m;
        try {
            m = hostClass.getMethod(sig.name(), params);
        } catch (NoSuchMethodException e) {
            m = null;
        }
        if (m == null) {
            try {
                m = hostClass.getDeclaredMethod(sig.name(), params);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        m.setAccessible(true);
        return m;
    }

    /** A HostCall that reflectively invokes {@code hostMethod} on the live host instance. */
    private static HostCall direct(Method hostMethod) {
        return (host, args) -> hostMethod.invoke(host, args);
    }

    private static String typeList(List<Class<?>> types) {
        List<String> names = new ArrayList<>(types.size());
        for (Class<?> t : types) {
            names.add(t.getName());
        }
        return String.join(", ", names);
    }

    // ---------------- interop publish/recover + name-based bindings

    /**
     * The publish accessor: recovers the HOST INSTANCE behind a materialized proxy —
     * whichever legacy interface it exposes. Returns null ONLY for foreign objects: a
     * proxy that is not one of UMB's (its handler is not the {@link MaterializingHandler})
     * or a non-proxy value (including a raw host object — the published unit is recovered
     * from the proxy that stands in front of it, which is what a provider hands back).
     *
     * @param proxy a value an entrypoint lifecycle returned
     * @return the host instance behind a UMB proxy, else null (nothing to publish)
     */
    public static Object hostInstanceOf(Object proxy) {
        if (proxy != null && Proxy.isProxyClass(proxy.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(proxy);
            if (h instanceof MaterializingHandler mh) {
                return mh.host;
            }
        }
        return null;
    }

    /**
     * Recovers a full {@link Materialized} from a lifecycle RETURN value for publishing
     * (the CLI interop path). Three cases, honestly distinguished and never thrown from:
     * <ul>
     *   <li>a materialized/view PROXY (handler is the {@link MaterializingHandler}) → its
     *       proxy, host instance and host class are recovered — the value was received by
     *       one entry and re-published by another;</li>
     *   <li>a RAW instance of a host-universe class → the object is its own host instance
     *       (the mod CONSTRUCTED the real host object itself — the host world is on its
     *       parent chain), so no proxy round-trip is needed;</li>
     *   <li>anything else — a void return, a String, a mod- or foreign-class instance →
     *       null: nothing was declared, nothing is published, and a consumer later names
     *       NOT_PUBLISHED.</li>
     * </ul>
     *
     * @param value    the lifecycle's return value (null for void)
     * @param universe the host universe the lifecycle's host world loads against
     * @return a publishable {@link Materialized}, or null when the value is foreign
     */
    public static Materialized recover(Object value, HostUniverse universe) {
        if (value == null) {
            return null;
        }
        Class<?> cls = value.getClass();
        if (Proxy.isProxyClass(cls)) {
            InvocationHandler h = Proxy.getInvocationHandler(value);
            if (h instanceof MaterializingHandler mh) {
                return new Materialized(value, mh.host, mh.hostClass);
            }
            return null;
        }
        if (cls.getClassLoader() == universe.loader()) {
            return new Materialized(value, value, cls);
        }
        return null;
    }

    /**
     * Builds bindings by NAME AND DESCRIPTOR alone: every public instance method the
     * legacy interface exposes — except the infrastructure Object methods, which stay
     * identity-bridged by the handler — is bound to the host method with the same name and
     * parameter types, when one exists. Methods the host does not expose are simply NOT
     * bound: they stay {@code UNBOUND_LEGACY_METHOD} at call time (D4 — never guessed,
     * never stubbed). Deterministic: candidates are sorted by (name, parameter types)
     * before any binding is created, so which binding validates/fails first is
     * reproducible.
     *
     * @param legacyInterface the consumer's OWN parameter interface (any loader)
     * @param hostClass       the host class the consumer views against
     * @return signature -> host call, insertion order = sorted candidate order
     */
    public static Map<MethodSignature, HostCall> bindByName(Class<?> legacyInterface, Class<?> hostClass) {
        if (!legacyInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "legacyInterface must be an interface: " + legacyInterface.getName());
        }
        List<Method> candidates = new ArrayList<>();
        for (Method m : legacyInterface.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue; // equals/hashCode/toString bridge host identity, never a binding
            }
            candidates.add(m);
        }
        candidates.sort(Comparator.comparing(Method::getName)
                .thenComparing(m -> parameterKey(m.getParameterTypes())));
        Map<MethodSignature, HostCall> bindings = new LinkedHashMap<>();
        for (Method m : candidates) {
            Method target = findHostMethod(hostClass, MethodSignature.of(m));
            if (target != null) {
                bindings.put(MethodSignature.of(m), direct(target));
            }
        }
        return bindings;
    }

    private static String parameterKey(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> t : types) {
            sb.append(t.getName()).append(';');
        }
        return sb.toString();
    }

    /**
     * Creates a proxy implementing {@code legacyInterface} over the given host instance,
     * dispatching through the shared {@link MaterializingHandler}. Package-private so the
     * {@link InteropRegistry} can re-proxy ONE host instance behind a consumer's own
     * interface with exactly the same dispatch and identity semantics as an original
     * materialization — a view and its materialized source compare equal.
     */
    static Object proxyFor(Class<?> legacyInterface, Object hostInstance, Class<?> hostClass,
                           Map<MethodSignature, HostCall> bindings) {
        ClassLoader proxyLoader = legacyInterface.getClassLoader();
        if (proxyLoader == null) {
            proxyLoader = Materializer.class.getClassLoader();
        }
        return Proxy.newProxyInstance(proxyLoader, new Class<?>[]{legacyInterface},
                new MaterializingHandler(hostInstance, hostClass, legacyInterface.getName(), bindings));
    }

    // ---------------- proxy dispatch

    /**
     * Per-proxy invocation handler, shared by materializations and interop views. Dispatch
     * order: bindings first (the legacy method's exact signature), then the infrastructure
     * Object methods (so printing, hashing and comparing a proxy do not surprise), then the
     * D4 failure — an invoked legacy method with no binding raises
     * {@code UNBOUND_LEGACY_METHOD} naming it. The Object-method defaults (esp. equals and
     * hashCode) bridge to the HOST instance identity, so a view proxy and its materialized
     * source — different interface class in a different loader — compare EQUAL iff they
     * stand in front of the same host instance: the bridge-identity invariant (mission
     * §29-30).
     */
    static final class MaterializingHandler implements InvocationHandler {

        private static final String TO_STRING = "toString";
        private static final String HASH_CODE = "hashCode";
        private static final String EQUALS = "equals";

        private final Object host;
        private final Class<?> hostClass;
        private final String legacyInterfaceName;
        private final Map<MethodSignature, HostCall> bindings;

        MaterializingHandler(Object host, Class<?> hostClass, String legacyInterfaceName,
                             Map<MethodSignature, HostCall> bindings) {
            this.host = host;
            this.hostClass = hostClass;
            this.legacyInterfaceName = legacyInterfaceName;
            this.bindings = bindings;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // Proxy passes null for a no-arg method; normalize once so both the
            // Object-method defaults and HostCall dispatch see a real array.
            Object[] actualArgs = args == null ? new Object[0] : args;
            MethodSignature sig = MethodSignature.of(method);
            HostCall call = bindings.get(sig);
            if (call == null) {
                Object defaulted = defaultObjectMethod(method, proxy, actualArgs);
                if (defaulted != null) {
                    return defaulted;
                }
                throw new MaterializationException(MaterializationException.Kind.UNBOUND_LEGACY_METHOD,
                        "no binding for legacy method " + sig.display() + " on " + legacyInterfaceName);
            }
            try {
                return call.call(host, actualArgs);
            } catch (InvocationTargetException e) {
                // The HostCall invoked reflectively and the HOST method threw; surface the
                // host's reasoning directly instead of the reflection wrapper.
                throw hostMethodFailure(sig, e.getCause());
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw hostMethodFailure(sig, e);
            }
        }

        private RuntimeException hostMethodFailure(MethodSignature sig, Throwable cause) {
            return new MaterializationException(MaterializationException.Kind.HOST_METHOD_FAILED,
                    "host invocation for legacy method " + sig.display() + " on "
                            + hostClass.getName() + " failed", cause);
        }

        /**
         * Bridges the infrastructure {@link Object} methods to the live host instance so a
         * proxy behaves like the object it stands for (identity is preserved — the mission's
         * bridge-identity invariant), including a diagnostic toString. Returns null when the
         * method is NOT an Object-method slice: those fall through to the unbound check.
         */
        private Object defaultObjectMethod(Method m, Object proxy, Object[] args) {
            switch (m.getName()) {
                case TO_STRING -> {
                    if (args.length == 0) {
                        return "Materialized[" + legacyInterfaceName + " -> " + hostClass.getName()
                                + "@" + Integer.toHexString(System.identityHashCode(host)) + "]";
                    }
                }
                case HASH_CODE -> {
                    if (args.length == 0) {
                        return System.identityHashCode(host);
                    }
                }
                case EQUALS -> {
                    if (args.length == 1) {
                        Object other = args[0];
                        if (other == proxy) {
                            return true;
                        }
                        if (other == null || !Proxy.isProxyClass(other.getClass())) {
                            return false;
                        }
                        InvocationHandler h = Proxy.getInvocationHandler(other);
                        return h instanceof MaterializingHandler mh && mh.host == host;
                    }
                }
                default -> {
                    return null;
                }
            }
            return null;
        }
    }
}