package dev.umb.pipeline.bridge;

import java.lang.reflect.Method;

/**
 * Outcome of driving an entrypoint's lifecycle method (Bridge v1). A completed run has
 * {@code completed=true} and a null cause; a lifecycle method that THREW is reported with
 * {@code completed=false} and the entrypoint's REAL exception (the reflection
 * {@code InvocationTargetException} wrapper is unwrapped). Structural failures — no
 * no-arg constructor, no matching lifecycle method, missing entrypoint class — are raised
 * as {@link MaterializationException} instead and never reach this record.
 *
 * <p>A completed lifecycle returning a value carries it in {@code returnValue}: M7 interop
 * has providers PUBLISH by returning the object from their lifecycle, and the bridge
 * captures it here to register it. A void lifecycle (the common case) and every
 * non-completed run carry {@code null}.
 *
 * @param entrypointClass the loaded entrypoint class
 * @param lifecycleMethod the lifecycle method that was found and invoked
 * @param completed       true iff the lifecycle method returned normally
 * @param cause           the entrypoint's real exception when {@code completed} is false
 * @param returnValue     the lifecycle method's return value, null for void or a
 *                        non-completed run
 */
public record DriverResult(
        Class<?> entrypointClass,
        Method lifecycleMethod,
        boolean completed,
        Throwable cause,
        Object returnValue) {

    public DriverResult {
        if (completed && cause != null) {
            throw new IllegalArgumentException("completed run cannot carry a cause");
        }
    }

    public static DriverResult ok(Class<?> entrypointClass, Method lifecycleMethod) {
        return new DriverResult(entrypointClass, lifecycleMethod, true, null, null);
    }

    public static DriverResult ok(Class<?> entrypointClass, Method lifecycleMethod, Object returnValue) {
        return new DriverResult(entrypointClass, lifecycleMethod, true, null, returnValue);
    }

    public static DriverResult failed(Class<?> entrypointClass, Method lifecycleMethod, Throwable cause) {
        return new DriverResult(entrypointClass, lifecycleMethod, false, cause, null);
    }
}