package dev.umb.mappings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * In-version probe for the (1.7.10, srg) node: a synthetic joined.srg fixture shaped
 * exactly like the published MCPConfig output is ingested into a
 * {@link DefaultMappingGraph} at {@code (1.7.10, srg)} ↔ {@code (1.7.10, official)},
 * and the composition is translated in BOTH directions. Pins OQ5 (official@1.7.10 is
 * the obfuscated column — pre-1.14.4 there is no mojang namespace), the null field
 * descriptor surviving the round trip (FD rows carry no type; never invented, D4),
 * and the method descriptor being rewritten through the same file's class pairings.
 * No real file is committed (fetch-only, same posture as client.txt).
 */
class SrgGraphProbeTest {

    private static final String JOINED_SRG = String.join("\n",
            "# MCP 1.7.10 joined.srg (synthetic)",
            "CL: aak net/minecraft/server/MinecraftServer",
            "CL: mt   net/minecraft/item/ItemStack",
            "FD: aak/a net/minecraft/server/MinecraftServer/field_3779_a",
            "FD: mt/q net/minecraft/item/ItemStack/field_3789_b",
            "MD: aak/q ()F net/minecraft/server/MinecraftServer/func_70001_a ()F",
            "MD: mt/a (Laak;)I net/minecraft/item/ItemStack/func_70002_a (Lnet/minecraft/server/MinecraftServer;)I");

    @TempDir
    Path tmp;

    @Test
    void srgNodeTranslatesObfInBothDirections() throws IOException {
        Path p = tmp.resolve("joined.srg");
        Files.writeString(p, JOINED_SRG, StandardCharsets.UTF_8);

        Node srg = new Node("1.7.10", SrgReader.NS_SRG);
        Node official = new Node("1.7.10", SrgReader.NS_OFFICIAL);
        DefaultMappingGraph g = new DefaultMappingGraph();
        g.addTinyFile(SrgReader.read(p), srg, 0, official, 1, Provenance.PUBLISHED, "1.7.10 joined.srg");

        // CLASS: srg-side readable name -> obfuscated class; and back (OQ5).
        Symbol clsSrg = new Symbol(srg, SymbolKind.CLASS, null, "net/minecraft/server/MinecraftServer", null);
        Optional<List<MappingEdge>> fwd = g.translate(clsSrg, official);
        assertTrue(fwd.isPresent(), "class must reach the obf column");
        assertEquals("aak", walk(fwd.get(), clsSrg).name(), "1.7.10 'official' names the obfuscated jar class");
        Symbol obfBack = new Symbol(official, SymbolKind.CLASS, null, "aak", null);
        assertEquals("net/minecraft/server/MinecraftServer",
                walk(g.translate(obfBack, srg).orElseThrow(), obfBack).name());

        // FIELD: FD rows carry no type, so the descriptor is null on BOTH sides.
        Symbol fieldSrg = new Symbol(srg, SymbolKind.FIELD, "net/minecraft/server/MinecraftServer",
                "field_3779_a", null);
        Optional<List<MappingEdge>> ff = g.translate(fieldSrg, official);
        assertTrue(ff.isPresent(), "field must reach the obf column");
        Symbol fieldObf = walk(ff.get(), fieldSrg);
        assertEquals("a", fieldObf.name());
        assertEquals("aak", fieldObf.owner());
        assertNull(fieldObf.descriptor(), "field type is never invented (D4)");
        assertNull(walk(g.translate(fieldObf, srg).orElseThrow(), fieldObf).descriptor(),
                "and stays null on the way back");

        // METHOD: the ()F descriptor survives verbatim; the descriptor that
        // references aak is stored in srg dialect (readable owner names) and
        // remapped by the same file's class pairings to the obf dialect on the
        // official column.
        Symbol mNoArg = new Symbol(srg, SymbolKind.METHOD, "net/minecraft/server/MinecraftServer",
                "func_70001_a", "()F");
        Symbol mNoArgObf = walk(g.translate(mNoArg, official).orElseThrow(), mNoArg);
        assertEquals("q", mNoArgObf.name());
        assertEquals("()F", mNoArgObf.descriptor());

        Symbol mItem = new Symbol(srg, SymbolKind.METHOD, "net/minecraft/item/ItemStack",
                "func_70002_a", "(Lnet/minecraft/server/MinecraftServer;)I");
        Symbol mItemObf = walk(g.translate(mItem, official).orElseThrow(), mItem);
        assertEquals("a", mItemObf.name());
        assertEquals("(Laak;)I", mItemObf.descriptor(),
                "descriptor stays in the obfuscated class dialect on the official column");
    }

    private static Symbol walk(List<MappingEdge> chain, Symbol start) {
        Symbol cur = start;
        for (MappingEdge e : chain) {
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        return cur;
    }
}