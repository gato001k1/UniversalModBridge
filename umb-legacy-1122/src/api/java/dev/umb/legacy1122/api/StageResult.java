package dev.umb.legacy1122.api;

/**
 * One boot/probe stage: did it run, how long, and if it blew up, exactly how.
 *
 * <p>Mirrors {@code dev.umb.legacy.api.StageResult} (the 1.7.10 module) field-for-field on purpose
 * - the two eras' boot reports should read the same way even though nothing here is shared code
 * (this module owns its own copy; see ERA-1122-PLAN.md "why not share a boot-report jar").</p>
 */
public final class StageResult {

    private final String stage;
    private final boolean ok;
    private final long millis;
    private final String throwableClass;
    private final String throwableMessage;
    private final String stackTrace;

    public StageResult(String stage, boolean ok, long millis,
                        String throwableClass, String throwableMessage, String stackTrace) {
        this.stage = stage;
        this.ok = ok;
        this.millis = millis;
        this.throwableClass = throwableClass;
        this.throwableMessage = throwableMessage;
        this.stackTrace = stackTrace;
    }

    public static StageResult ok(String stage, long millis) {
        return new StageResult(stage, true, millis, null, null, null);
    }

    public static StageResult failed(String stage, long millis, Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return new StageResult(stage, false, millis, t.getClass().getName(), t.getMessage(), w.toString());
    }

    public String stage() { return stage; }
    public boolean ok() { return ok; }
    public long millis() { return millis; }
    public String throwableClass() { return throwableClass; }
    public String throwableMessage() { return throwableMessage; }
    public String stackTrace() { return stackTrace; }
}
