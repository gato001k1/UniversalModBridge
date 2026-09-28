package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multiblock/metadata TE linkage: a block whose factory builds a lightweight TE for some
 * metas and the real, rendered TE for the core ones must link to the rendered one when the
 * snapshot (honestly) names the proxy. Every assertion here is about linkage mechanics on
 * synthetic bytecode - no mod names anywhere.
 */
class TileEntityLinkerTest implements Opcodes {

    private static final String TE_A = "any/pkg/FakeTeA";
    private static final String TE_B = "any/pkg/FakeTeB";
    private static final String BLOCK = "any/pkg/MultiBlock";

    /** Factory branching on the int (metadata) param: >=12 builds B, else A. */
    private static ClassNode branchedBlock() {
        return TestAsm.classWithMethod(BLOCK, "net/minecraft/block/Block", null,
                "createNewTileEntity", "(Lnet/minecraft/world/World;I)Lnet/minecraft/tileentity/TileEntity;",
                ACC_PUBLIC, mv -> {
                    Label elseLabel = new Label();
                    mv.visitVarInsn(ILOAD, 2);
                    mv.visitIntInsn(BIPUSH, 12);
                    mv.visitJumpInsn(IF_ICMPLT, elseLabel);
                    mv.visitTypeInsn(NEW, TE_B);
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, TE_B, "<init>", "()V", false);
                    mv.visitInsn(ARETURN);
                    mv.visitLabel(elseLabel);
                    mv.visitTypeInsn(NEW, TE_A);
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, TE_A, "<init>", "()V", false);
                    mv.visitInsn(ARETURN);
                });
    }

    private static JarIndex jarWith(ClassNode... extra) {
        ClassNode a = TestAsm.bareClass(TE_A, "net/minecraft/tileentity/TileEntity");
        ClassNode b = TestAsm.bareClass(TE_B, "net/minecraft/tileentity/TileEntity");
        ClassNode[] all = new ClassNode[extra.length + 2];
        all[0] = a;
        all[1] = b;
        System.arraycopy(extra, 0, all, 2, extra.length);
        return TestAsm.jarOf(all);
    }

    private static Snapshot snap(Path dir, String blockTe) throws Exception {
        Path p = dir.resolve("snap.json");
        Files.writeString(p, "{\"blocks\":[{\"id\":\"examplemod:multi\","
                + "\"className\":\"any.pkg.MultiBlock\",\"renderType\":0,"
                + "\"hasTileEntity\":true,\"tileEntityClass\":"
                + (blockTe == null ? "null" : ("\"" + blockTe + "\"")) + "}],"
                + "\"items\":[],\"tileEntities\":["
                + "{\"name\":\"tea\",\"className\":\"any.pkg.FakeTeA\"},"
                + "{\"name\":\"teb\",\"className\":\"any.pkg.FakeTeB\"}],"
                + "\"entities\":[]}", StandardCharsets.UTF_8);
        return Snapshot.load(p);
    }

    @Test
    void unboundSnapshotTeUpgradesToBoundFactoryAlternative(@TempDir Path dir) throws Exception {
        Snapshot snap = snap(dir, "any.pkg.FakeTeA");
        Map<String, String> notes = TileEntityLinker.relinkUnrendered(
                jarWith(branchedBlock()), snap, Set.of("any.pkg.FakeTeB"));

        assertEquals("any.pkg.FakeTeB", snap.blockById.get("examplemod:multi").tileEntityClass);
        assertTrue(snap.blocksByTeClass.getOrDefault("any.pkg.FakeTeB", java.util.List.of())
                .contains("examplemod:multi"));
        assertFalse(snap.blocksByTeClass.getOrDefault("any.pkg.FakeTeA", java.util.List.of())
                .contains("examplemod:multi"));
        assertTrue(notes.containsKey("examplemod:multi"), "moved row must carry a linkage note");
        String note = notes.get("examplemod:multi");
        assertTrue(note.contains("FakeTeA") && note.contains("FakeTeB"), note);
    }

    @Test
    void alreadyBoundLinkageIsUntouched(@TempDir Path dir) throws Exception {
        Snapshot snap = snap(dir, "any.pkg.FakeTeB");
        Map<String, String> notes = TileEntityLinker.relinkUnrendered(
                jarWith(branchedBlock()), snap, Set.of("any.pkg.FakeTeB"));

        assertTrue(notes.isEmpty());
        assertEquals("any.pkg.FakeTeB", snap.blockById.get("examplemod:multi").tileEntityClass);
    }

    @Test
    void singleCandidateFactoryLeavesLinkageAlone(@TempDir Path dir) throws Exception {
        ClassNode single = TestAsm.classWithMethod(BLOCK, "net/minecraft/block/Block", null,
                "createNewTileEntity", "(Lnet/minecraft/world/World;I)Lnet/minecraft/tileentity/TileEntity;",
                ACC_PUBLIC, mv -> {
                    mv.visitTypeInsn(NEW, TE_A);
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, TE_A, "<init>", "()V", false);
                    mv.visitInsn(ARETURN);
                });
        Snapshot snap = snap(dir, "any.pkg.FakeTeA");
        Map<String, String> notes = TileEntityLinker.relinkUnrendered(
                jarWith(single), snap, Set.of("any.pkg.FakeTeB"));

        assertTrue(notes.isEmpty());
        assertEquals("any.pkg.FakeTeA", snap.blockById.get("examplemod:multi").tileEntityClass);
    }

    @Test
    void unknownTeConstructionsAreIgnored(@TempDir Path dir) throws Exception {
        ClassNode mixed = TestAsm.classWithMethod(BLOCK, "net/minecraft/block/Block", null,
                "createNewTileEntity", "(Lnet/minecraft/world/World;I)Lnet/minecraft/tileentity/TileEntity;",
                ACC_PUBLIC, mv -> {
                    mv.visitTypeInsn(NEW, "com/unknown/GhostTe");
                    mv.visitInsn(POP);
                    mv.visitTypeInsn(NEW, TE_A);
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, TE_A, "<init>", "()V", false);
                    mv.visitInsn(ARETURN);
                });
        Snapshot snap = snap(dir, "any.pkg.FakeTeA");
        Map<String, String> notes = TileEntityLinker.relinkUnrendered(
                jarWith(mixed), snap, Set.of("any.pkg.FakeTeB"));

        // GhostTe is not snapshot-known: no bound candidate exists, linkage stands.
        assertTrue(notes.isEmpty());
        assertEquals("any.pkg.FakeTeA", snap.blockById.get("examplemod:multi").tileEntityClass);
    }

    @Test
    void applyStillRecoversNullTeLinkages(@TempDir Path dir) throws Exception {
        Snapshot snap = snap(dir, null);
        TileEntityLinker.apply(jarWith(branchedBlock()), snap);
        // First constructed known TE wins (existing behavior, unchanged by the relink pass).
        assertEquals("any.pkg.FakeTeB", snap.blockById.get("examplemod:multi").tileEntityClass);
    }
}
