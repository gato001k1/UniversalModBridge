package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SrgReader against synthetic fixtures shaped exactly like the published MCPConfig
 * output: a 1.7.10 joined.srg (CL:/FD:/MD: rows, func_/field_ searge names on the
 * srg side, single-letter obf members on the official side, the descriptor its own
 * middle token per column, and PK: rows present). No real file is committed
 * (fetch-only posture).
 */
class SrgReaderTest {

    private static final String JOINED_SRG = String.join("\n",
            "# MCP 1.7.10 joined.srg (synthetic)",
            "CL: aak net/minecraft/server/MinecraftServer",
            "CL: mt   net/minecraft/item/ItemStack",
            "FD: aak/a net/minecraft/server/MinecraftServer/field_3779_a",
            "FD: mt/q net/minecraft/item/ItemStack/field_3789_b",
            "PK: aak net/minecraft/server",
            "MD: aak/q ()F net/minecraft/server/MinecraftServer/func_70001_a ()F",
            "MD: mt/a (Laak;)I net/minecraft/item/ItemStack/func_70002_a (Lnet/minecraft/server/MinecraftServer;)I");

    @TempDir
    Path tmp;

    private Path fixture(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void parsesJoinedSrgIntoFixedNamespaceShape() throws IOException {
        TinyV2Reader.TinyFile f = SrgReader.read(fixture("joined.srg", JOINED_SRG));
        assertEquals(List.of(SrgReader.NS_SRG, SrgReader.NS_OFFICIAL), f.namespaces());
        assertEquals(2, f.classes().size());
        assertArrayEquals(new String[] { "net/minecraft/server/MinecraftServer", "aak" },
                f.classes().get(0).names());
        assertArrayEquals(new String[] { "field_3779_a", "a" },
                f.classes().get(0).fields().get(0).names());
        assertNull(f.classes().get(0).fields().get(0).descriptor());
        assertArrayEquals(new String[] { "func_70001_a", "q" },
                f.classes().get(0).methods().get(0).names());
        assertEquals("()F", f.classes().get(0).methods().get(0).descriptor());
        assertArrayEquals(new String[] { "func_70002_a", "a" },
                f.classes().get(1).methods().get(0).names());
        assertEquals("(Lnet/minecraft/server/MinecraftServer;)I",
                f.classes().get(1).methods().get(0).descriptor());
    }

    @Test
    void tabsCommentsCrlfAndBlankLinesAreTolerated() throws IOException {
        Path p = fixture("crlf.srg", String.join("\r\n",
                "CL:\taak\t net/minecraft/server/MinecraftServer ",
                "",
                "# comment between rows",
                "FD:\taak/a\tnet/minecraft/server/MinecraftServer/field_1_a"));
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals(1, f.classes().size());
        assertEquals("field_1_a", f.classes().get(0).fields().get(0).names()[0]);
    }

    @Test
    void bomOnFirstLineIsStripped() throws IOException {
        Path p = fixture("bom.srg", "\uFEFF" + "CL: aak net/minecraft/server/MinecraftServer");
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals("aak", f.classes().get(0).names()[1]);
    }

    @Test
    void emptySrgFileParsesToEmptyTinyFile() throws IOException {
        TinyV2Reader.TinyFile f = SrgReader.read(fixture("empty.srg", "# only a comment\n\n"));
        assertEquals(0, f.classes().size());
        assertEquals(List.of(SrgReader.NS_SRG, SrgReader.NS_OFFICIAL), f.namespaces());
    }

    @Test
    void memberRowBeforeItsClassRowStillAttaches() throws IOException {
        Path p = fixture("forward.srg", String.join("\n",
                "FD: aak/a net/minecraft/server/MinecraftServer/field_3779_a",
                "MD: aak/q ()F net/minecraft/server/MinecraftServer/func_70001_a ()F",
                "CL: aak net/minecraft/server/MinecraftServer"));
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals(1, f.classes().size());
        assertEquals(1, f.classes().get(0).fields().size());
        assertEquals(1, f.classes().get(0).methods().size());
    }

    @Test
    void memberWithoutOwnerClassIsRejectedWithLineContext() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("orphan.srg", String.join("\n",
                        "CL: aak net/minecraft/server/MinecraftServer",
                        "FD: zz/x net/minecraft/server/MinecraftServer/field_1_a"))));
        assertTrue(e.getMessage().contains("zz"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("has no CL row"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), () -> e.getMessage());
    }

    @Test
    void pkRowsAreSkipped() throws IOException {
        Path p = fixture("pk.srg", String.join("\n",
                "PK: aak net/minecraft/server",
                "CL: aak net/minecraft/server/MinecraftServer"));
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals(1, f.classes().size());
    }

    @Test
    void unknownRowPrefixIsRejectedWithLineContext() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("zz.srg", String.join("\n",
                        "ZZ: aak net/minecraft/server/MinecraftServer"))));
        assertTrue(e.getMessage().contains("unknown row prefix 'ZZ:'"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 1"), () -> e.getMessage());
    }

    @Test
    void wrongTokenCountsAreRejected() throws IOException {
        IllegalArgumentException cl = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("cl.srg", String.join("\n",
                        "CL: aak net/minecraft/server/MinecraftServer extra"))));
        assertTrue(cl.getMessage().contains("got 3 tokens"), () -> cl.getMessage());

        IllegalArgumentException fd = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("fd.srg", String.join("\n",
                        "CL: aak net/minecraft/server/MinecraftServer",
                        "FD: aak/a"))));
        assertTrue(fd.getMessage().contains("FD row must be"), () -> fd.getMessage());
        assertTrue(fd.getMessage().contains("line 2"), () -> fd.getMessage());

        IllegalArgumentException md = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("md.srg", String.join("\n",
                        "CL: mt net/minecraft/item/ItemStack",
                        "MD: mt/a ()V func_70002_a"))));
        assertTrue(md.getMessage().contains("MD row must be"), () -> md.getMessage());
        assertTrue(md.getMessage().contains("line 2"), () -> md.getMessage());
    }

    @Test
    void mdRowWithoutDescriptorIsRejected() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("nodesc.srg", String.join("\n",
                        "CL: aak net/minecraft/server/MinecraftServer",
                        "MD: aak/func_70001_a net/minecraft/server/MinecraftServer/func_70001_a"))));
        assertTrue(e.getMessage().contains("has no descriptor"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), () -> e.getMessage());
    }

    @Test
    void mdRowWithMismatchedDescriptorsIsRejected() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("mismatch.srg", String.join("\n",
                        "CL: mt net/minecraft/item/ItemStack",
                        "MD: mt/a ()V net/minecraft/item/ItemStack/func_70002_a ()F"))));
        assertTrue(e.getMessage().contains("descriptors differ"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("()F"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("()V"), () -> e.getMessage());
    }

    @Test
    void unbalancedDescriptorParenthesesAreRejected() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.read(fixture("unbal.srg", String.join("\n",
                        "CL: mt net/minecraft/item/ItemStack",
                        "MD: mt/a ()V net/minecraft/item/ItemStack/func_70002_a (Lmt;"))));
        assertTrue(e.getMessage().contains("unbalanced"), () -> e.getMessage());
    }

    @Test
    void staticInitializerNamesParseVerbatim() throws IOException {
        Path p = fixture("init.srg", String.join("\n",
                "CL: aak net/minecraft/server/MinecraftServer",
                "MD: aak/<init> (Laak;)V net/minecraft/server/MinecraftServer/<init> (Lnet/minecraft/server/MinecraftServer;)V",
                "MD: aak/<clinit> ()V net/minecraft/server/MinecraftServer/<clinit> ()V"));
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals("<init>", f.classes().get(0).methods().get(0).names()[0]);
        assertEquals("<clinit>", f.classes().get(0).methods().get(1).names()[0]);
    }

    @Test
    void nestedClassDollarNamesSurviveUnchanged() throws IOException {
        Path p = fixture("nested.srg", String.join("\n",
                "CL: aak$a net/minecraft/server/MinecraftServer$a",
                "FD: aak$a/a net/minecraft/server/MinecraftServer$a/field_1_a"));
        TinyV2Reader.TinyFile f = SrgReader.read(p);
        assertEquals("aak$a", f.classes().get(0).names()[1]);
        assertEquals("net/minecraft/server/MinecraftServer$a", f.classes().get(0).names()[0]);
    }

    @Test
    void readTsrgParsesIndentedBlockShape() throws IOException {
        Path p = fixture("joined.tsrg", String.join("\n",
                "aak net/minecraft/server/MinecraftServer",
                "\ta field_3779_a",
                "\tq ()F func_70001_a",
                "\ta (Laak;)I func_70002_a",
                "\t\tp_70001_1_ par",
                "mt net/minecraft/item/ItemStack",
                "\tb field_3789_b"));
        TinyV2Reader.TinyFile f = SrgReader.readTsrg(p);
        assertEquals(2, f.classes().size());
        assertArrayEquals(new String[] { "net/minecraft/server/MinecraftServer", "aak" },
                f.classes().get(0).names());
        assertArrayEquals(new String[] { "field_3779_a", "a" },
                f.classes().get(0).fields().get(0).names());
        assertArrayEquals(new String[] { "func_70001_a", "q" },
                f.classes().get(0).methods().get(0).names());
        assertEquals("(Laak;)I", f.classes().get(0).methods().get(1).descriptor());
        assertArrayEquals(new String[] { "func_70002_a", "a" },
                f.classes().get(0).methods().get(1).names());
        assertEquals("field_3789_b", f.classes().get(1).fields().get(0).names()[0]);
    }

    @Test
    void tsrgMemberBeforeAnyClassIsRejectedWithLineContext() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.readTsrg(fixture("orphan.tsrg", "\ta field_1_a\n")));
        assertTrue(e.getMessage().contains("member row before any class row"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 1"), () -> e.getMessage());
    }

    @Test
    void tsrgMalformedRowsAreRejectedWithLineContext() throws IOException {
        IllegalArgumentException cls = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.readTsrg(fixture("badcls.tsrg", "aak net/minecraft/server/MinecraftServer extra\n")));
        assertTrue(cls.getMessage().contains("class row must be"), () -> cls.getMessage());
        assertTrue(cls.getMessage().contains("line 1"), () -> cls.getMessage());

        IllegalArgumentException mem = assertThrows(IllegalArgumentException.class,
                () -> SrgReader.readTsrg(fixture("badmem.tsrg", String.join("\n",
                        "aak net/minecraft/server/MinecraftServer",
                        "\tq ()F func_70001_a extra"))));
        assertTrue(mem.getMessage().contains("member row must be"), () -> mem.getMessage());
        assertTrue(mem.getMessage().contains("line 2"), () -> mem.getMessage());
    }

    @Test
    void repeatedParsesProduceIdenticalContent() throws IOException {
        Path p = fixture("joined.srg", JOINED_SRG);
        assertEquals(canon(SrgReader.read(p)), canon(SrgReader.read(p)));
    }

    private static String canon(TinyV2Reader.TinyFile f) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", f.namespaces())).append('\n');
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            sb.append(String.join("/", c.names())).append('\n');
            for (TinyV2Reader.FieldEntry fl : c.fields()) {
                sb.append("f:").append(fl.descriptor()).append(':')
                        .append(String.join("/", fl.names())).append('\n');
            }
            for (TinyV2Reader.MethodEntry m : c.methods()) {
                sb.append("m:").append(m.descriptor()).append(':')
                        .append(String.join("/", m.names())).append('\n');
            }
        }
        return sb.toString();
    }
}