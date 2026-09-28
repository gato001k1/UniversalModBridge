package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parser contract on the checked-in three-namespace fixture (spec §13):
 * header order, per-row column counts, tiny-v2 unescaping, silent parameter
 * rows, and the malformed-input failures the loader must reject.
 */
class TinyV2ReaderTest {

    @TempDir
    Path tmp;

    @Test
    void readsThreeNamespaceHeaderInOrder() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());

        assertEquals(List.of("official", "intermediary", "named"), f.namespaces(),
                "header namespace order must survive the parse");
        assertEquals(4, f.classes().size());

        // Every row carries one name token per header namespace: column counts
        // are what makes column SELECTION meaningful downstream.
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            assertEquals(3, c.names().length,
                    () -> "class column count: " + Arrays.toString(c.names()));
            for (TinyV2Reader.FieldEntry fl : c.fields()) {
                assertEquals(3, fl.names().length,
                        () -> "field column count: " + Arrays.toString(fl.names()));
            }
            for (TinyV2Reader.MethodEntry m : c.methods()) {
                assertEquals(3, m.names().length,
                        () -> "method column count: " + Arrays.toString(m.names()));
            }
        }

        TinyV2Reader.ClassEntry server = byOfficialName(f, "net/minecraft/server/MinecraftServer");
        assertEquals("net/minecraft/class_2966", server.names()[1]);
        assertEquals("com/example/server/Server", server.names()[2]);
        assertEquals(2, server.fields().size());
        assertEquals(2, server.methods().size());
        assertEquals("(Lnet/minecraft/world/World;)V", server.methods().get(1).descriptor(),
                "descriptor stays in namespace[0] (official) form");
    }

    @Test
    void unescapesEscapeSequencesIntoRealCharacters() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());

        TinyV2Reader.ClassEntry stack = byOfficialName(f, "net/minecraft/item/ItemStack");
        assertEquals(1, stack.fields().size());
        TinyV2Reader.FieldEntry broken = stack.fields().get(0);
        // The fixture stores backslash-t as two literal characters; only that
        // pair may become a tab — every surrounding raw tab was a separator.
        assertEquals("broken\ttooltip", broken.names()[0], "backslash-t must unescape to a real tab");
        assertEquals("field_broken_tooltip", broken.names()[1]);
        assertEquals("brokenTooltipLabel", broken.names()[2]);
        assertEquals("Ljava/lang/String;", broken.descriptor(), "descriptors unescape too");
    }

    @Test
    void skipsParameterRowsSilently() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());

        // Both fixture classes carry an indented "\t\tp ..." parameter row; a
        // parser that promoted them would inflate these method counts.
        assertEquals(2, byOfficialName(f, "net/minecraft/server/MinecraftServer").methods().size());
        assertEquals(2, byOfficialName(f, "net/minecraft/world/World").methods().size());
    }

    /**
     * Tiny v2 comments are 'c'-typed rows NESTED under their element (yarn embeds
     * javadoc this way); '#' lines are a v1 convention kept only as leniency. A
     * parser that only knows '#' rejects every javadoc-bearing real-world file.
     */
    @Test
    void skipsTinyV2CommentRowsAtAnyDepth() throws IOException {
        String content = String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\tcom/example/A\tclass_A",
                "\tc\tThe quaternion class.",
                "\tf\tI\tweight\tfield_1",
                "\t\tc\tmember javadoc with\tan embedded tab",
                "\tm\t()V\ttick\tmethod_1",
                "# v1-style hash comment, tolerated",
                "");
        Path p = TinyFixtures.write(tmp, "v2-comments.tiny", content);

        TinyV2Reader.TinyFile f = TinyV2Reader.read(p);
        assertEquals(1, f.classes().size());
        TinyV2Reader.ClassEntry a = f.classes().get(0);
        assertEquals("com/example/A", a.names()[0]);
        assertEquals(1, a.fields().size(), "class-level comment must not become a field");
        assertEquals(1, a.methods().size(), "member-level comment must not become a method");
        assertEquals("weight", a.fields().get(0).names()[0]);
    }

    /** Property rows live between header and first class; unknown keys are skipped without error per spec. */
    @Test
    void skipsHeaderPropertyRowsWithoutError() throws IOException {
        String content = String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "\tescaped-names",
                "\tmissing-lvt-indices",
                "\tsome-future-property\twith a value",
                "c\tcom/example/A\tclass_A");
        Path p = TinyFixtures.write(tmp, "properties.tiny", content);

        TinyV2Reader.TinyFile f = TinyV2Reader.read(p);
        assertEquals(List.of("official", "intermediary"), f.namespaces());
        assertEquals(1, f.classes().size());
        assertEquals("com/example/A", f.classes().get(0).names()[0]);
    }

    /** Trailing spaces must not glue into tokens; a trailing tab on the header must not fabricate a namespace column. */
    @Test
    void stripsTrailingWhitespaceAndIgnoresHeaderTrailingTab() throws IOException {
        String content = "tiny\t2\t0\tofficial\tintermediary  \r\n"
                + "c\tcom/example/A\tclass_A \n"
                + "\tf\tI\tweight\tfield_1  \r\n";
        Path p = TinyFixtures.write(tmp, "trailing.tiny", content);

        TinyV2Reader.TinyFile f = TinyV2Reader.read(p);
        assertEquals(List.of("official", "intermediary"), f.namespaces());
        TinyV2Reader.ClassEntry a = f.classes().get(0);
        assertEquals("class_A", a.names()[1], () -> Arrays.toString(a.names()));
        assertEquals("field_1", a.fields().get(0).names()[1], () -> Arrays.toString(a.fields().get(0).names()));
    }

    @Test
    void trailingTabHeaderDoesNotFabricatePhantomNamespace() throws IOException {
        String content = "tiny\t2\t0\tofficial\tintermediary\t\n"
                + "c\tcom/example/A\tclass_A\n";
        Path p = TinyFixtures.write(tmp, "phantom-ns.tiny", content);

        TinyV2Reader.TinyFile f = TinyV2Reader.read(p);
        assertEquals(List.of("official", "intermediary"), f.namespaces(),
                "the empty token after a stray trailing tab is editor sloppiness, not a column");
        assertEquals(1, f.classes().size());
    }

    /** The spec's legal trailing-empty-name row keeps parsing: tabs are never stripped. */
    @Test
    void legalTrailingEmptyNameColumnStillParses() throws IOException {
        String content = "tiny\t2\t0\ta\tb\tc\n"
                + "c\tx\ty\t\n";
        Path p = TinyFixtures.write(tmp, "trailing-empty-name.tiny", content);

        TinyV2Reader.TinyFile f = TinyV2Reader.read(p);
        assertEquals(3, f.classes().get(0).names().length);
        assertEquals("", f.classes().get(0).names()[2], "a legal trailing empty name survives");
    }

    @Test
    void rejectsBadMagicHeader() {
        Path p = TinyFixtures.write(tmp, "bad-magic.tiny", "tinny\t2\t0\ta\tb\tc\n");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> TinyV2Reader.read(p));
        assertTrue(ex.getMessage().contains("header"), () -> ex.getMessage());
    }

    @Test
    void rejectsWrongTinyVersion() {
        Path p = TinyFixtures.write(tmp, "bad-version.tiny", "tiny\t3\t0\ta\tb\tc\n");
        assertThrows(IllegalArgumentException.class, () -> TinyV2Reader.read(p));
    }

    @Test
    void rejectsDanglingMemberRowBeforeAnyClass() {
        String content = String.join("\n",
                "tiny\t2\t0\ta\tb\tc",
                "\tf\tI\tone\ttwo\tthree",
                "");
        Path p = TinyFixtures.write(tmp, "dangling.tiny", content);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> TinyV2Reader.read(p));
        assertTrue(ex.getMessage().contains("before any class"), () -> ex.getMessage());
    }

    @Test
    void rejectsFileWithoutHeaderRow() {
        Path p = TinyFixtures.write(tmp, "empty.tiny", "# only a comment\n\n");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> TinyV2Reader.read(p));
        assertTrue(ex.getMessage().contains("no header"), () -> ex.getMessage());
    }

    @Test
    void reportsUnreadableFilesAsIoException() {
        Path missing = tmp.resolve("does-not-exist.tiny");
        assertThrows(NoSuchFileException.class, () -> TinyV2Reader.read(missing));
    }

    private static TinyV2Reader.ClassEntry byOfficialName(TinyV2Reader.TinyFile f, String official) {
        return f.classes().stream()
                .filter(c -> c.names()[0].equals(official))
                .findFirst()
                .orElseThrow();
    }
}
