package dev.umb.console;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console polls stage JSONs while legacy.ps1 is rewriting them, so parsing must never throw. */
class StatusJsonTest {

    /** Verbatim shape written by harness\legacy.ps1 (PowerShell ConvertTo-Json, 4-space indent). */
    private static final String REAL = """
            {
                "stage":  "probe",
                "status":  "ok",
                "startedAt":  "2026-09-08T00:19:01.4878532-04:00",
                "endedAt":  "2026-09-08T00:19:09.8140120-04:00",
                "durationMs":  8326,
                "command":  "powershell -NoProfile -ExecutionPolicy Bypass -File tools\\\\probe-hostagent.ps1",
                "outputs":  [
                                "C:\\\\repo\\\\research\\\\out\\\\legacy\\\\hostagent-probe.log"
                            ],
                "counts":  {
                               "blocks":  "1244/1244",
                               "items":  "3829/3829",
                               "dupIds":  0,
                               "renamed":  2
                           },
                "error":  null
            }
            """;

    @Test
    void parsesTheRealStageJson() {
        StageStatus s = StageStatus.parse("probe", REAL);
        assertEquals("probe", s.stage);
        assertEquals("ok", s.status);
        assertEquals(8326L, s.durationMs);
        assertEquals(1, s.outputs.size());
        assertEquals("1244/1244", s.counts.get("blocks"));
        assertEquals("0", s.counts.get("dupIds"));
        assertEquals("2", s.counts.get("renamed"));
        assertNull(s.error);
        assertTrue(s.command.contains("probe-hostagent.ps1"));
    }

    @Test
    void countsKeepInsertionOrder() {
        StageStatus s = StageStatus.parse("probe", REAL);
        assertEquals(List.of("blocks", "items", "dupIds", "renamed"), List.copyOf(s.counts.keySet()));
    }

    @Test
    void booleanAndNestedCountsSurvive() {
        String json = """
                {"stage":"analyze","status":"ok","durationMs":12,
                 "counts":{"fmlModAnnotation":true,"nested":{"a":1},"sha256":"abc"}}
                """;
        StageStatus s = StageStatus.parse("analyze", json);
        assertEquals("true", s.counts.get("fmlModAnnotation"));
        assertEquals("abc", s.counts.get("sha256"));
        assertTrue(s.counts.get("nested").contains("\"a\""));
    }

    @Test
    void nullCountsAreDropped() {
        StageStatus s = StageStatus.parse("pack", "{\"stage\":\"pack\",\"status\":\"ok\",\"counts\":{\"a\":null,\"b\":1}}");
        assertEquals(1, s.counts.size());
        assertEquals("1", s.counts.get("b"));
    }

    @Test
    void aBomIsTolerated() {
        StageStatus s = StageStatus.parse("pack", "\uFEFF{\"stage\":\"pack\",\"status\":\"ok\",\"durationMs\":5}");
        assertEquals("ok", s.status);
        assertEquals(5L, s.durationMs);
    }

    @Test
    void halfWrittenJsonBecomesUnreadableNotAnException() {
        StageStatus s = StageStatus.parse("pack", "{\"stage\":\"pack\",\"status\":\"o");
        assertEquals("unreadable", s.status);
        assertNotNull(s.error);
        assertEquals(0L, s.durationMs);
    }

    @Test
    void emptyAndNonObjectInputsAreUnreadable() {
        assertEquals("unreadable", StageStatus.parse("pack", "").status);
        assertEquals("unreadable", StageStatus.parse("pack", "   ").status);
        assertEquals("unreadable", StageStatus.parse("pack", null).status);
        assertEquals("unreadable", StageStatus.parse("pack", "[1,2,3]").status);
    }

    @Test
    void missingFieldsFallBackToTheRequestedStageName() {
        StageStatus s = StageStatus.parse("launch", "{}");
        assertEquals("launch", s.stage);
        assertEquals("unknown", s.status);
        assertEquals(0L, s.durationMs);
        assertTrue(s.outputs.isEmpty());
        assertTrue(s.counts.isEmpty());
    }

    @Test
    void nonNumericDurationDoesNotThrow() {
        StageStatus s = StageStatus.parse("pack", "{\"status\":\"ok\",\"durationMs\":\"lots\"}");
        assertEquals("ok", s.status);
        assertEquals(0L, s.durationMs);
    }

    @Test
    void readAllGivesOneRowPerStageEvenWithNoFiles(@org.junit.jupiter.api.io.TempDir Path tmp) {
        List<StageStatus> all = StageStatus.readAll(tmp, "hbm");
        assertEquals(StageStatus.ORDER.size(), all.size());
        assertEquals(StageStatus.ORDER, all.stream().map(s -> s.stage).toList());
        assertTrue(all.stream().allMatch(s -> s.status.equals("never")));
    }

    @Test
    void readPicksUpAFileOnDisk(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path runDir = tmp.resolve("hbm");
        Files.createDirectories(runDir);
        Files.write(runDir.resolve("pack.json"),
                "{\"stage\":\"pack\",\"status\":\"skipped\",\"durationMs\":1282,\"error\":\"exists\"}"
                        .getBytes(StandardCharsets.UTF_8));
        StageStatus s = StageStatus.read(runDir, "pack");
        assertEquals("skipped", s.status);
        assertEquals("exists", s.error);
        assertEquals(1282L, s.durationMs);
    }
}
