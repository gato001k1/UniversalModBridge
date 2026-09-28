package dev.umb.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured outcome of a pass. Never silently swallow failures (spec §115). */
public final class PassReport {
    public enum Status { OK, SKIPPED, WARN, FAIL }

    private final String passId;
    private final Status status;
    private final List<String> notes = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public PassReport(String passId, Status status) {
        this.passId = passId;
        this.status = status;
    }

    public static PassReport ok(String id) { return new PassReport(id, Status.OK); }
    public static PassReport skipped(String id) { return new PassReport(id, Status.SKIPPED); }
    public static PassReport fail(String id, Diagnostic d) {
        PassReport r = new PassReport(id, Status.FAIL);
        r.diagnostics.add(d);
        return r;
    }

    public PassReport note(String n) { notes.add(n); return this; }
    public PassReport diag(Diagnostic d) { diagnostics.add(d); return this; }

    public String passId() { return passId; }
    public Status status() { return status; }
    public List<String> notes() { return Collections.unmodifiableList(notes); }
    public List<Diagnostic> diagnostics() { return Collections.unmodifiableList(diagnostics); }

    /** Rich, actionable diagnostic per spec §115 — no bare NPEs. */
    public record Diagnostic(
            String code,
            String message,
            String modId,
            String sourceEra,
            String subsystem,
            String caller,
            String suggestion
    ) {}
}
