package dev.umb.pipeline.bridge;

/**
 * Named, typed failure for a materialization — the D4 "no silent fakes" verdict of the
 * Bridge stage. Every path that cannot find what it needs raises this with a message
 * naming exactly what was absent: the host class, a target host method, a host
 * constructor, or a legacy binding. It is a RuntimeException because it flows through
 * {@link java.lang.reflect.Proxy} dispatch, which only lets unchecked exceptions reach
 * the legacy caller unchanged; the subtype is what lets callers catch materialization
 * failures distinctly from host runtime failures.
 */
public final class MaterializationException extends RuntimeException {

    public enum Kind {
        /** The named host class does not exist (or cannot link) in the host universe. */
        MISSING_HOST_CLASS,
        /** A binding names a method the host class does not expose. */
        MISSING_HOST_METHOD,
        /** No constructor matches the requested parameter types. */
        CONSTRUCTOR_MISMATCH,
        /** The matching constructor exists but failed to produce an instance. */
        CONSTRUCTION_FAILED,
        /** A legacy method was invoked that has no binding. */
        UNBOUND_LEGACY_METHOD,
        /** A bound host invocation threw; cause carries the host's real exception. */
        HOST_METHOD_FAILED,
        /** The named entrypoint class does not exist (or cannot link) in the mod loader. */
        MISSING_ENTRYPOINT_CLASS,
        /** The entrypoint class has no lifecycle method with the requested name/signature. */
        NO_LIFECYCLE_METHOD,
        /** The lifecycle method ran and threw; cause is the entrypoint's real exception. */
        ENTRYPOINT_THREW,
        /** An identifier was resolved that nothing has been published under. */
        NOT_PUBLISHED,
        /** A launch-plan file was structurally malformed; the whole launch refuses. */
        MALFORMED_PLAN,
        /** A consume directive cannot be served: arity/index gap or non-interface param. */
        CONSUME_MISMATCH,

        /* ---------------- mixin/coremod group (M8; approved with the M8 plan) ----------------
         * AT_PATCH_APPLIED was deliberately NOT added here: informational outcomes are DATA,
         * not failures — an AT application lands as a report field, never an exception. */

        /** A mixin injector that must resolve (require&gt;0) could not bind post-translation. */
        MIXIN_UNRESOLVED,
        /** A Forge-era IFMLLoadingPlugin/IClassTransformer coremod is present; not graph-translatable, refused. */
        COREMOD_ASM_UNSUPPORTED,
        /** A JS coremod (modern [[coremods]] or historical ModLoader-era) is present; refused. */
        COREMOD_JS_UNSUPPORTED
    }

    private final Kind kind;

    public MaterializationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MaterializationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}