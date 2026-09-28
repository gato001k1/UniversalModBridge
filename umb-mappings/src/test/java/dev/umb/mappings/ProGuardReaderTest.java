package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;


/**
 * ProGuardReader against synthetic fixtures shaped exactly like the real
 * 1.20.1 client.txt (comment header, dotted class rows, line-number ranges,
 * SourceFile rows, array/varargs types). The REAL-file smoke that pins the
 * parser to the published corpus lives in ProGuardSmokeTest.
 */
class ProGuardReaderTest {

    private static final String PROGUARD = String.join("\n",
            "# (c) synthetic header comment",
            "com.example.player.Player -> oaa:",
            "    int HEALTH -> b",
            "    java.lang.String[] TAGS -> c",
            "    com.example.player.Item[] INVENTORY -> d",
            "    10:10:void <init>() -> <init>",
            "    12:13:void jump(net.minecraft.world.phys.Vec3,float) -> a",
            "    com.example.player.Player create(java.lang.String,int) -> b",
            "    void run() -> e",
            "    Player.java -> oaa:",
            "com.example.player.Item -> oab:",
            "    22:-1:void inlined(int) -> c");

    @TempDir
    Path tmp;

    private Path fixture(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void parsesClassFieldAndMethodRowsIntoTinyShape() throws IOException {
        TinyV2Reader.TinyFile f = ProGuardReader.read(fixture("client.txt", PROGUARD));

        assertEquals(List.of(ProGuardReader.NS_MOJANG, ProGuardReader.NS_OFFICIAL),
                f.namespaces(), "fixed namespace pair");
        assertEquals(2, f.classes().size());

        TinyV2Reader.ClassEntry player = f.classes().get(0);
        assertEquals("com/example/player/Player", player.names()[0], "mojang column internal form");
        assertEquals("oaa", player.names()[1], "obf column verbatim");
        assertEquals(3, player.fields().size());
        assertEquals(4, player.methods().size(), "<init> kept, SourceFile row skipped");

        TinyV2Reader.ClassEntry item = f.classes().get(1);
        assertEquals("com/example/player/Item", item.names()[0]);
        assertEquals(0, item.fields().size(), "member-less class carries no members");
        assertEquals(1, item.methods().size());
        assertEquals("oab", item.names()[1]);
    }

    @Test
    void javaTypesConvertToInternalDescriptors() throws IOException {
        TinyV2Reader.TinyFile f = ProGuardReader.read(fixture("client.txt", PROGUARD));
        TinyV2Reader.ClassEntry player = f.classes().get(0);

        // field descriptors: primitives bare, arrays stacked, varargs as arrays
        assertEquals("I", descOfField(f, "HEALTH"));
        assertEquals("[Ljava/lang/String;", descOfField(f, "TAGS"));
        assertEquals("[Lcom/example/player/Item;", descOfField(f, "INVENTORY"));

        // methods: line ranges stripped, args comma-separated, constructor ()V
        assertEquals("()V", descOfMethod(player, "<init>"));
        assertEquals("(Lnet/minecraft/world/phys/Vec3;F)V", descOfMethod(player, "jump"));
        assertEquals("(Ljava/lang/String;I)Lcom/example/player/Player;",
                descOfMethod(player, "create"));
        assertEquals("()V", descOfMethod(player, "run"));
        assertEquals("(I)V", descOfMethod(f.classes().get(1), "inlined"),
                "negative line-number range stripped");
    }

    @Test
    void roundTripsThroughGraphWithDescriptorsOnBothSides() throws IOException {
        TinyV2Reader.TinyFile f = ProGuardReader.read(fixture("client.txt", PROGUARD));
        Node mojang = new Node("1.20.1", "mojang");
        Node official = new Node("1.20.1", "official");
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(f, mojang, 0, official, 1, Provenance.PUBLISHED, "client.txt");

        // class forward + reverse
        Symbol cls = new Symbol(mojang, SymbolKind.CLASS, null, "com/example/player/Player", null);
        Optional<List<MappingGraph.MappingEdge>> fwd = g.translate(cls, official);
        assertTrue(fwd.isPresent(), "class must translate mojang -> official");
        assertEquals("oaa", fwd.get().get(0).to().name());
        Symbol revStart = new Symbol(official, SymbolKind.CLASS, null, "oaa", null);
        assertTrue(g.translate(revStart, mojang).isPresent(), "class must translate in reverse");

        // method: the Vec3 arg class is absent from the fixture, so the
        // descriptor passes through BOTH sides verbatim and still matches.
        Symbol jump = new Symbol(mojang, SymbolKind.METHOD, "com/example/player/Player",
                "jump", "(Lnet/minecraft/world/phys/Vec3;F)V");
        Optional<List<MappingGraph.MappingEdge>> mfwd = g.translate(jump, official);
        assertTrue(mfwd.isPresent(), "method must translate with its mojang descriptor");
        assertEquals("a", mfwd.get().get(0).to().name());

        // method whose descriptor references classes PRESENT in the fixture: the
        // official side must carry the REWRITTEN form (Player -> oaa), unlike the
        // absent-Vec3 jump probe above.
        Symbol create = new Symbol(mojang, SymbolKind.METHOD, "com/example/player/Player",
                "create", "(Ljava/lang/String;I)Lcom/example/player/Player;");
        Optional<List<MappingEdge>> cfwd = g.translate(create, official);
        assertTrue(cfwd.isPresent(), "create must translate with its mojang descriptor");
        assertEquals("b", cfwd.get().get(0).to().name());
        assertEquals("(Ljava/lang/String;I)Loaa;", cfwd.get().get(0).to().descriptor(),
                "descriptor must follow the class mapping onto the official side");

        // field whose element class IS mapped: Item -> oab inside the array dims.
        Symbol inventory = new Symbol(mojang, SymbolKind.FIELD, "com/example/player/Player",
                "INVENTORY", "[Lcom/example/player/Item;");
        Optional<List<MappingEdge>> ifwd = g.translate(inventory, official);
        assertTrue(ifwd.isPresent(), "field must translate with its mojang descriptor");
        assertEquals("d", ifwd.get().get(0).to().name());
        assertEquals("[Loab;", ifwd.get().get(0).to().descriptor(),
                "array-of-mapped-class descriptor must be rewritten too");

        // audit: mojang -> official -> mojang restores identity on every probed class
        RoundtripAudit.AuditResult r =
                RoundtripAudit.audit(g, mojang, official, SymbolKind.CLASS, 10);
        assertEquals(2, r.tested());
        assertEquals(0, r.contradictions(), () -> "samples: " + r.samples());
        assertEquals(Provenance.PUBLISHED, r.minPathConfidence(), 0.0);
    }

    @Test
    void memberRowBeforeAnyClassIsRejected() throws IOException {
        Path p = fixture("bad.txt", "    int x -> a\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(p));
        assertTrue(e.getMessage().contains("member row before any class row"), () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 1"), () -> e.getMessage());
    }

    @Test
    void malformedClassRowsAreRejectedWithLineContext() throws IOException {
        IllegalArgumentException missingColon = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("a.txt", "com.example.A -> aa\n")));
        assertTrue(missingColon.getMessage().contains("must end with ':'"),
                () -> missingColon.getMessage());

        IllegalArgumentException missingArrow = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("b.txt", "# c\ncom.example.A ->  :\n")));
        assertTrue(missingArrow.getMessage().contains("empty name"),
                () -> missingArrow.getMessage());
    }

    @Test
    void malformedMemberRowsAreRejectedWithLineNumbers() throws IOException {
        // field with no separating space after the line-number-free body
        IllegalArgumentException noType = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("c.txt", "com.example.A -> aa:\n    lone -> b\n")));
        assertTrue(noType.getMessage().contains("field row must be"), () -> noType.getMessage());

        // method signature without a return type and not a constructor
        IllegalArgumentException noRet = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("d.txt", "com.example.A -> aa:\n    weird(int) -> b\n")));
        assertTrue(noRet.getMessage().contains("no return type"), () -> noRet.getMessage());

        // unterminated line-number range: signatures never begin with a digit
        IllegalArgumentException badRange = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("e.txt",
                        "com.example.A -> aa:\n    12void f() -> b\n")));
        assertTrue(badRange.getMessage().contains("not followed by ':'"), () -> badRange.getMessage());
        assertTrue(badRange.getMessage().contains("line 2"), () -> badRange.getMessage());
    }

    @Test
    void bareColonAndLoneLineRangeRowsAreRejectedNotSkipped() throws IOException {
        // The SourceFile skip requires the "<file> -> <obf>:" shape. A bare ':'
        // or a lone line-number range carries no declaration and must NOT be
        // dropped with the debug rows — it falls through to the arrow check and
        // dies with its row's line number.
        IllegalArgumentException bareColon = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("f.txt",
                        "com.example.A -> aa:\n    :\n")));
        assertTrue(bareColon.getMessage().contains("line 2"), () -> bareColon.getMessage());
        assertTrue(bareColon.getMessage().contains("member row"),
                () -> bareColon.getMessage());

        IllegalArgumentException loneRange = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("g.txt",
                        "com.example.A -> aa:\n    10:10:\n")));
        assertTrue(loneRange.getMessage().contains("line 2"), () -> loneRange.getMessage());
    }

    @Test
    void sourceFileRowIsStillSkippedWhileSiblingMembersParse() throws IOException {
        // Positive control for the SourceFile skip: the row is dropped, and the
        // member rows around it still land on the same class.
        Path p = fixture("srcfile.txt", String.join("\n",
                "com.example.A -> aa:",
                "    Foo.java -> aa:",
                "    int x -> a",
                "    void f() -> b"));
        TinyV2Reader.TinyFile f = ProGuardReader.read(p);
        TinyV2Reader.ClassEntry c = f.classes().get(0);
        assertEquals(1, f.classes().size(), "SourceFile row must not create a class");
        assertEquals(1, c.fields().size(), "field before/after the SourceFile row must keep");
        assertEquals(1, c.methods().size(), "method sibling must keep");
        assertEquals("()V", descOfMethod(c, "f"));
    }

    @Test
    void spacedArrayDimsStackIntoTheDescriptor() throws IOException {
        // "java.lang.String [][]" — the dims loop consumes the two "[]" pairs
        // from the END before the last-space split, so both dims land in the
        // descriptor and the method name comes out clean.
        Path p = fixture("spaceddims.txt", String.join("\n",
                "com.example.A -> aa:",
                "    java.lang.String [][] m() -> a"));
        TinyV2Reader.TinyFile f = ProGuardReader.read(p);
        TinyV2Reader.ClassEntry c = f.classes().get(0);
        assertEquals(1, c.methods().size());
        assertEquals("m", c.methods().get(0).names()[0]);
        assertEquals("()[[Ljava/lang/String;", c.methods().get(0).descriptor());
    }

    @Test
    void spacedVarargsFailsLoudlyRatherThanCorrupting() throws IOException {
        // The varargs marker must END the type token; "String ... x" leaves "..."
        // mid-string, so the dims loop cannot consume it and descOf refuses the
        // residual whitespace. Pinned as a malformed() throw with line context —
        // the current behavior — because any silent acceptance here would emit a
        // class whose method signature silently drops the varargs dim.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProGuardReader.read(fixture("varargs.txt", String.join("\n",
                        "com.example.A -> aa:",
                        "    void v(java.lang.String ... x) -> a"))));
        assertTrue(e.getMessage().contains("stray brackets or spaces in type"),
                () -> e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), () -> e.getMessage());
    }

    @Test
    void constructorWithoutReturnTypeIsTolerated() throws IOException {
        Path p = fixture("ctors.txt", String.join("\n",
                "com.example.C -> ca:",
                "    <init>(int) -> <init>",
                "    void tick() -> a"));
        TinyV2Reader.TinyFile f = ProGuardReader.read(p);
        TinyV2Reader.ClassEntry c = f.classes().get(0);
        assertEquals(2, c.methods().size());
        assertEquals("()V", c.methods().get(1).descriptor());
        assertEquals("<init>", c.methods().get(0).names()[0]);
        assertEquals("(I)V", c.methods().get(0).descriptor());
    }

    @Test
    void dottedObfColumnsAreSlashNormalized() throws IOException {
        // 1.20.1 reality: identity rows and nested classes of unobfuscated parents
        // carry DOTTED right-hand names. Fabric's intermediary official column is
        // slash-form, so the obf column must be internalized too or the composed
        // 2-hop paths never join (the composed audit found 28 such breaks; 13 are
        // genuine corpus gaps, 15 were this bug).
        Path p = fixture("identity.txt", String.join("\n",
                "com.example.Outer -> com.example.Outer:",
                "    void tick() -> tick",
                "com.example.Outer$Inner -> com.example.Outer$a:",
                "com.example.Plain -> p:"));
        TinyV2Reader.TinyFile f = ProGuardReader.read(p);
        assertEquals("com/example/Outer", f.classes().get(0).names()[1],
                "identity row's obf column must land in internal form");
        assertEquals("com/example/Outer$a", f.classes().get(1).names()[1],
                "nested-of-unobfuscated dotted obf must become slash form");
        assertEquals("p", f.classes().get(2).names()[1],
                "short obf names pass through unchanged");
        assertEquals("com/example/Plain", f.classes().get(2).names()[0]);
    }

    // ---- helpers ------------------------------------------------------------

    private static String descOfField(TinyV2Reader.TinyFile f, String mojangName) {
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            for (TinyV2Reader.FieldEntry fl : c.fields()) {
                if (fl.names()[0].equals(mojangName)) {
                    return fl.descriptor();
                }
            }
        }
        throw new AssertionError("field not found: " + mojangName);
    }

    private static String descOfMethod(TinyV2Reader.ClassEntry c, String mojangName) {
        for (TinyV2Reader.MethodEntry m : c.methods()) {
            if (m.names()[0].equals(mojangName)) {
                return m.descriptor();
            }
        }
        throw new AssertionError("method not found: " + mojangName);
    }
}
