package dev.umb.pipeline.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-1 ride-along: LaunchPlan.parse under direct test in its home module (house
 * discipline, cf. EntrypointScannerTest). Every structural violation is a
 * MALFORMED_PLAN D4 refusal naming exactly what was wrong — malformed JSON, a
 * non-array root, a missing/wrong-typed {@code class}, a negative or non-integer
 * {@code param}, a missing {@code id}, a {@code consume} that is not an array,
 * a consume element that is not an object. Unknown extra keys are ignored and
 * the file's array order is the drive order, verbatim.
 */
class LaunchPlanTest {

    @TempDir
    Path tmp;

    /** Writes the plan text to a fresh file and returns its path. */
    private Path write(String text) throws IOException {
        Path p = tmp.resolve("plan-" + Math.abs(text.hashCode()) + ".json");
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    /** Parsing must refuse with MALFORMED_PLAN whose detail names the fragment. */
    private static MaterializationException assertMalformed(Path p, String fragment) {
        MaterializationException ex = assertThrows(MaterializationException.class,
                () -> LaunchPlan.parse(p));
        assertEquals(MaterializationException.Kind.MALFORMED_PLAN, ex.kind());
        assertTrue(ex.getMessage().contains(fragment),
                "detail must name \"" + fragment + "\" but was: " + ex.getMessage());
        return ex;
    }

    // ------------------------------------------------------------------ structural refusals

    @Test
    void malformedJsonIsNamed() throws IOException {
        assertMalformed(write("{ not json"), "not valid JSON");
    }

    @Test
    void nonArrayRootIsNamed() throws IOException {
        assertMalformed(write("{\"class\":\"q.Main\"}"), "expected a JSON array");
    }

    @Test
    void entryNotObjectIsNamed() throws IOException {
        assertMalformed(write("[\"just a string\"]"), "plan entry 0 is not an object");
    }

    @Test
    void missingOrWrongTypedClassIsNamed() throws IOException {
        assertMalformed(write("[{}]"), "has no \"class\" string");
        assertMalformed(write("[{\"class\":123}]"), "has no \"class\" string");
    }

    @Test
    void negativeOrNonIntegerParamIsNamed() throws IOException {
        assertMalformed(write("[{\"class\":\"q.M\",\"consume\":[{\"param\":-1,\"id\":\"x\"}]}]"),
                "negative parameter index");
        assertMalformed(write("[{\"class\":\"q.M\",\"consume\":[{\"param\":1.5,\"id\":\"x\"}]}]"),
                "is not an integer");
    }

    @Test
    void missingIdIsNamed() throws IOException {
        assertMalformed(write("[{\"class\":\"q.M\",\"consume\":[{\"param\":0}]}]"),
                "has no \"id\" string");
    }

    // ------------------------------------------------------------------ tolerated surface

    @Test
    void unknownExtraKeysAreIgnored() throws IOException {
        LaunchPlan plan = LaunchPlan.parse(write("[{\"class\":\"q.CFirst\",\"publish\":\"p.one\","
                + "\"flavor\":true,\"nested\":{\"a\":1}},"
                + "{\"class\":\"q.ANoRole\",\"z\":null}]"));
        assertEquals(List.of("q.CFirst", "q.ANoRole"),
                plan.entries().stream().map(LaunchPlan.PlanEntry::className).toList());
        assertEquals("p.one", plan.entries().get(0).publishId());
        assertNull(plan.entries().get(1).publishId());
        assertTrue(plan.entries().get(0).consume().isEmpty());
    }

    @Test
    void fileArrayOrderPreservedVerbatim() throws IOException {
        LaunchPlan plan = LaunchPlan.parse(write("[{\"class\":\"q.Z\",\"publish\":\"pz\","
                + "\"consume\":[{\"param\":2,\"id\":\"i2\"},{\"param\":0,\"id\":\"i0\"}]},"
                + "{\"class\":\"q.A\",\"publish\":\"pa\"},"
                + "{\"class\":\"q.M\"}]"));
        assertEquals(List.of("q.Z", "q.A", "q.M"),
                plan.entries().stream().map(LaunchPlan.PlanEntry::className).toList());
        // the driver sorts by paramIndex later; parse preserves the author's order verbatim
        assertEquals(List.of(2, 0), plan.entries().get(0).consume().stream()
                .map(LaunchPlan.ConsumeDirective::paramIndex).toList());
        assertEquals("i0", plan.entries().get(0).consume().get(1).identifier());
        assertNull(plan.entries().get(2).publishId());
        assertTrue(plan.entries().get(2).consume().isEmpty());
    }

    @Test
    void consumeNotArrayIsNamed() throws IOException {
        assertMalformed(write("[{\"class\":\"q.A\",\"consume\":\"x\"}]"),
                "\"consume\" must be an array");
    }

    @Test
    void consumeElementNotObjectIsNamed() throws IOException {
        assertMalformed(write("[{\"class\":\"q.A\",\"consume\":[\"x\"]}]"),
                "consume 0 is not an object");
    }
}