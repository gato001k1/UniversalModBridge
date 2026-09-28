package dev.umb.pipeline.bridge;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Bridge M7 interop registry: one authoritative world where mods exchange objects by
 * identifier and every side holds a VIEW onto ONE real host instance — never a copy
 * (mission §29-30).
 *
 * <p>Two mods in SEPARATE {@link ModLoader}s may each define their own legacy interface
 * (same or different names — different Class objects regardless, because loaders differ).
 * To bridge them, identity lives at the HOST-INSTANCE level: {@link #publish} keeps the
 * authoritative {@link Materialized}, {@link #resolve} hands it out unchanged, and
 * {@link #view} re-proxies that SAME host instance behind the consumer's interface. The
 * view is built by the same {@link Materializer} machinery, so a view and the provider's
 * original proxy compare {@code equals}/{@code hashCode} by host instance — two mods
 * talking through different interfaces in different loaders are still seeing one object.
 *
 * <p>Views are cached per {@code (hostInstance, consumerInterface)} in an
 * {@link IdentityHashMap} keyed on the host instance: the same {@code (host, interface)}
 * pair always returns the SAME proxy (`==`), and DISTINCT host instances are never
 * conflated even when the host class has value-based {@code equals} (e.g. real
 * net.minecraft.core.Vec3i compares x/y/z) — the bridge's object identity is host-instance
 * identity, so the cache keys by identity, not {@code equals}. Views therefore PIN their
 * host instance for the registry's LIFETIME: that is honest here, because the registry is
 * per-launch-run and dies with the {@link HostUniverse}, and mission host objects (blocks,
 * entities, registry entries) are long-lived anyway.
 *
 * <p>No silent fakes (D4): resolving an identifier nothing was published under raises
 * {@code NOT_PUBLISHED} naming it; {@link #view} validates its bindings EAGERLY in the
 * map's iteration order against the host class (a LinkedHashMap makes the failure
 * ordering reproducible) and a binding naming no host method fails the view before any
 * proxy exists or is returned; consumer-interface methods left unbound stay
 * {@code UNBOUND_LEGACY_METHOD} at call time. Not thread-safe: the bridge drives mods on
 * one thread.
 */
public final class InteropRegistry {

    private final Map<String, Materialized> published = new HashMap<>();
    /** hostInstance -> (consumerInterface -> view proxy); BOTH keyed by identity (`==`). */
    private final Map<Object, Map<Class<?>, Object>> views = new IdentityHashMap<>();

    /**
     * Registers {@code materialized} as the authoritative object for {@code identifier}.
     * Re-publishing an identifier supersedes the previous binding; consumers that already
     * hold a view keep seeing the host instance it was made from.
     */
    public void publish(String identifier, Materialized materialized) {
        published.put(identifier, materialized);
    }

    /**
     * @return the authoritative materialized object for {@code identifier}
     * @throws MaterializationException NOT_PUBLISHED if nothing has been published under it
     */
    public Materialized resolve(String identifier) {
        Materialized m = published.get(identifier);
        if (m == null) {
            throw new MaterializationException(MaterializationException.Kind.NOT_PUBLISHED,
                    "no object published under identifier \"" + identifier + "\"");
        }
        return m;
    }

    /**
     * Re-proxies {@code materialized}'s host instance behind {@code consumerInterface},
     * so cross-loader consumers see the SAME host object through their own interface.
     * A view is created once per {@code (hostInstance, consumerInterface)}: the first
     * call validates {@code bindings} EAGERLY in map iteration order against the host
     * class and caches the proxy; later calls return the same proxy (`==`).
     *
     * @param materialized     the authoritative object to view (its host instance is reused)
     * @param consumerInterface the consumer's own legacy interface (any loader)
     * @param bindings         legacy signature -> host call, validated before the view exists
     * @return the same proxy for repeated calls with the same {@code consumerInterface}
     * @throws MaterializationException MISSING_HOST_METHOD from the eager validation
     */
    public Object view(Materialized materialized, Class<?> consumerInterface,
                       Map<MethodSignature, HostCall> bindings) {
        if (!consumerInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "consumerInterface must be an interface: " + consumerInterface.getName());
        }
        Map<Class<?>, Object> byInterface = views.computeIfAbsent(
                materialized.hostInstance(), ignored -> new HashMap<>());
        Object existing = byInterface.get(consumerInterface);
        if (existing != null) {
            return existing; // one view per (hostInstance, consumerInterface)
        }
        Materializer.validateBindings(materialized.hostClass(), bindings,
                materialized.hostClass().getName());
        Object view = Materializer.proxyFor(consumerInterface, materialized.hostInstance(),
                materialized.hostClass(), bindings);
        byInterface.put(consumerInterface, view);
        return view;
    }
}