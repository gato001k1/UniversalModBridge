package dev.umb.pipeline.bridge;

/**
 * The result of a materialization, exposing the D4 native-object property: the proxy is
 * backed by a REAL host instance of the host class. {@code proxy instanceof hostClass} is
 * false in general — the proxy implements a legacy interface in the legacy loader while
 * the host class lives in the {@link HostUniverse}'s loader — so the invariant is asserted
 * as {@code hostClass().isInstance(hostInstance())}, and the components are kept separate
 * so tests and the pipeline can verify that directly.
 *
 * @param proxy       the {@link java.lang.reflect.Proxy} implementing the legacy interface
 * @param hostInstance the real host-class instance the proxy delegates to
 * @param hostClass   the host class (as loaded by the host universe) of {@code hostInstance}
 */
public record Materialized(Object proxy, Object hostInstance, Class<?> hostClass) {
}