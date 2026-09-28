package dev.umb.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI surface: subcommand wiring, usage output, exit codes on bad input (D5 partition). */
class UmbCliTest {

    /** Shared factory so tests exercise the same usage-error/exception wiring as main(). */
    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    @Test
    void bareInvocationPrintsUsageAndExitsZero() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute();
        assertEquals(0, exit);
        assertTrue(out.toString().contains("analyze"));
        assertTrue(out.toString().contains("check-linkage"));
    }

    /**
     * D5 reserves exit 2 for check-linkage's "ran but does not link"; picocli's
     * usage-error default would hand that code to every typo'd invocation.
     */
    @Test
    void usageErrorsExitOneNotPicocliDefaultTwo() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        assertEquals(1, cli(out, err).execute("frobnicate"), "unmatched argument");

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        assertEquals(1, cli(out2, err2).execute("analyze"), "missing required parameter");

        StringWriter out3 = new StringWriter();
        StringWriter err3 = new StringWriter();
        assertEquals(1, cli(out3, err3).execute("graph-audit", "--bogus", "x"), "unknown option");
    }

    @Test
    void analyzeRejectsMissingFile() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("analyze", "Z:/definitely/not/here.jar");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("no such file"));
    }

    @Test
    void checkLinkageRejectsMissingHost() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("check-linkage", "some.jar", "Z:/no/host.jar");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("no such file"));
    }
}
