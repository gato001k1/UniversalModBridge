package dev.umb.pipeline.bridge;

/**
 * One bound legacy-method entry point. A HostCall receives the LIVE host instance and
 * the caller's (legacy-side) arguments and must produce the value by invoking the real
 * host — typically reflectively, which is why it throws {@link Throwable}. The proxy
 * feeds it through the {@link Materializer} handler, which unwraps
 * {@code InvocationTargetException} so the host's real failure surfaces rather than a
 * reflection wrapper.
 *
 * <p>Deterministic by construction: the map key it is bound under
 * ({@link MethodSignature}) fixes the exact legacy method it serves, and the host
 * method target was validated to exist at materialization time.
 */
@FunctionalInterface
public interface HostCall {
    Object call(Object hostInstance, Object[] args) throws Throwable;
}