package dev.umb.legacy.api;

/** One FML lifecycle stage: did it run, how long, and if it blew up, exactly how. */
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

    public String stage() { return stage; }
    public boolean ok() { return ok; }
    public long millis() { return millis; }
    public String throwableClass() { return throwableClass; }
    public String throwableMessage() { return throwableMessage; }
    public String stackTrace() { return stackTrace; }
}
