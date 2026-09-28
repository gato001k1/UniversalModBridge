package dev.umb.pipeline;

import dev.umb.core.BasicModAnalyzer;
import dev.umb.core.ModAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M11: StructuralRepairPass — case-split ICCE repair/refuse behind the
 * structural-repair evidence kind and STRUCTURAL_* §115 codes.
 * All fixtures are synthetic CC0 classes; stubs never ship. Input never mutated (§24).
 */
class StructuralRepairPassTest {

    @TempDir Path tmp;

    private static final String CRAFTING = "net/minecraft/world/inventory/CraftingContainer";
    private static final String TRANSIENT = "net/minecraft/world/inventory/TransientCraftingContainer";
    private static final String PROJECTILE = "net/minecraft/world/entity/projectile/Projectile";
    private static final String BER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
    private static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    private static final String STRUCT_START = "net/minecraft/world/level/levelgen/structure/StructureStart";
    private static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    private static final String TICK_SOUND = "net/minecraft/client/resources/sounds/AbstractTickableSoundInstance";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String MOB = "net/minecraft/world/entity/PathfinderMob";

    private static byte[] bytes(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void voidBody(MethodNode m) {
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.visitMaxs(0, 0);
    }

    /** Minimal ctor delegating to a super-ctor owner (phantom interface super allowed synthetically). */
    private static MethodNode ctorWithSuper(String superOwner, String desc) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // Push default args matching (Ljava/lang/Object;II)V-style descriptors used below.
        m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m.instructions.add(new InsnNode(Opcodes.ICONST_0));
        m.instructions.add(new InsnNode(Opcodes.ICONST_0));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superOwner, "<init>", desc, false));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.visitMaxs(0, 0);
        return m;
    }

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static Map<String, byte[]> base() {
        Map<String, byte[]> m = new HashMap<>();
        m.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private static ModAnalysis analyze(Path jar) throws IOException {
        return new BasicModAnalyzer().analyze(jar);
    }

    private static ClassNode readClass(Path jar, String entry) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            byte[] b = jf.getInputStream(jf.getEntry(entry)).readAllBytes();
            ClassNode n = new ClassNode();
            new ClassReader(b).accept(n, 0);
            return n;
        }
    }

    private static byte[] rawClass(Path jar, String entry) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getInputStream(jf.getEntry(entry)).readAllBytes();
        }
    }

    private static boolean hasDiag(PassReport r, String code) {
        return r.diagnostics().stream().anyMatch(d -> d.code().equals(code));
    }

    // ------------------------------------------------------------------ (a) redirect

    @Test
    void interfaceRedirectAppliesWithSuperCtorRewrite() throws Exception {
        String internal = "com/example/Inv";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, CRAFTING, null);
        cn.methods.add(ctorWithSuper(CRAFTING, "(Ljava/lang/Object;II)V"));
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-redirect.jar", entries);
        byte[] before = rawClass(in, internal + ".class");

        Path out = tmp.resolve("out-redirect.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.OK, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(hasDiag(r, "STRUCTURAL_REDIRECT"), "must carry STRUCTURAL_REDIRECT: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("TransientCraftingContainer")),
                "evidence must name the redirect target: " + r.diagnostics());
        ClassNode n = readClass(out, internal + ".class");
        assertEquals(TRANSIENT, n.superName, "supertype must redirect to the host impl");
        boolean superCtorRewritten = n.methods.stream().flatMap(m -> {
            java.util.List<String> owners = new java.util.ArrayList<>();
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && mi.name.equals("<init>")) owners.add(mi.owner);
            }
            return owners.stream();
        }).allMatch(TRANSIENT::equals);
        assertTrue(superCtorRewritten, "phantom super-ctor owner must be rewritten");
        assertArrayEquals(before, rawClass(in, internal + ".class"), "input jar must not be mutated (§24)");
    }

    @Test
    void implementsClassDropsInterfaceClause() throws Exception {
        String internal = "com/example/Shot";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, ENTITY, new String[]{PROJECTILE});
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        voidBody(tick);
        cn.methods.add(tick);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-drop.jar", entries);

        Path out = tmp.resolve("out-drop.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.OK, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(hasDiag(r, "STRUCTURAL_DROP_INTERFACE"), "must carry STRUCTURAL_DROP_INTERFACE: " + r.diagnostics());
        ClassNode n = readClass(out, internal + ".class");
        assertFalse(n.interfaces.contains(PROJECTILE), "Projectile clause must be dropped");
        assertEquals(ENTITY, n.superName, "Entity superclass must be kept");
        assertTrue(n.methods.stream().anyMatch(m -> m.name.equals("tick")), "members must survive the drop");
    }

    // ------------------------------------------------------------------ (a/d) refuse

    @Test
    void blockEntityRendererExtendsRefusesWithNamedKind() throws Exception {
        String internal = "com/example/RenderTile";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, BER, null);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-ber.jar", entries);
        byte[] before = rawClass(in, internal + ".class");

        Path out = tmp.resolve("out-ber.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString());
        assertTrue(hasDiag(r, "STRUCTURAL_UNRESOLVABLE"), "must refuse with STRUCTURAL_UNRESOLVABLE: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("RenderTile")
                        && d.message().contains("BlockEntityRenderer")),
                "refusal must name both sides: " + r.diagnostics());
        assertArrayEquals(before, rawClass(out, internal + ".class"), "refused class must pass through byte-identical");
    }

    @Test
    void explosionExtendsRefusesWithoutInventingSemantics() throws Exception {
        String internal = "com/example/Boom";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, EXPLOSION, null);
        MethodNode boom = new MethodNode(Opcodes.ACC_PUBLIC, "boom", "()V", null, null);
        voidBody(boom);
        cn.methods.add(boom);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-boom.jar", entries);

        Path out = tmp.resolve("out-boom.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString());
        assertTrue(hasDiag(r, "STRUCTURAL_UNRESOLVABLE"), "must refuse: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("ServerExplosion")),
                "evidence must name why the only host impl is unusable: " + r.diagnostics());
        ClassNode n = readClass(out, internal + ".class");
        assertEquals(EXPLOSION, n.superName, "refused supertype must be untouched");
    }

    // ------------------------------------------------------------------ (b) final supertype

    @Test
    void finalSupertypeRefusesNamingBothSides() throws Exception {
        String a = "com/example/ProcStart";
        ClassNode ca = new ClassNode();
        ca.visit(52, Opcodes.ACC_PUBLIC, a, null, STRUCT_START, null);
        String b = "com/example/TagExt";
        ClassNode cb = new ClassNode();
        cb.visit(52, Opcodes.ACC_PUBLIC, b, null, COMPOUND, null);
        Map<String, byte[]> entries = base();
        entries.put(a + ".class", bytes(ca));
        entries.put(b + ".class", bytes(cb));
        Path in = jarOf(tmp, "in-final.jar", entries);

        Path out = tmp.resolve("out-final.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString());
        assertEquals(2, r.diagnostics().stream().filter(d -> d.code().equals("STRUCTURAL_UNRESOLVABLE")).count(),
                "both final-supertype declarers must refuse: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("ProcStart")
                        && d.message().contains("StructureStart") && d.message().contains("final")),
                "must name declarer, host, and finality: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("TagExt")
                        && d.message().contains("CompoundTag")),
                "CompoundTag refusal must name both sides: " + r.diagnostics());
        // Host final never stripped: supertypes byte-identical in output.
        assertEquals(STRUCT_START, readClass(out, a + ".class").superName);
        assertEquals(COMPOUND, readClass(out, b + ".class").superName);
    }

    // ------------------------------------------------------------------ (c) override-final rename

    @Test
    void overrideFinalRenamesAndRewritesCallSites() throws Exception {
        String loop = "com/example/Loop";
        ClassNode cl = new ClassNode();
        cl.visit(52, Opcodes.ACC_PUBLIC, loop, null, TICK_SOUND, null);
        MethodNode stop = new MethodNode(Opcodes.ACC_PROTECTED, "stop", "()V", null, null);
        voidBody(stop);
        cl.methods.add(stop);
        // Self call site: func_73660_a calls this.stop().
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "func_73660_a", "()V", null, null);
        tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, loop, "stop", "()V", false));
        tick.instructions.add(new InsnNode(Opcodes.RETURN));
        tick.visitMaxs(0, 0);
        cl.methods.add(tick);
        // Subclass calling the same name through the declarer.
        String sub = "com/example/SubLoop";
        ClassNode cs = new ClassNode();
        cs.visit(52, Opcodes.ACC_PUBLIC, sub, null, loop, null);
        MethodNode use = new MethodNode(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
        use.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        use.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, loop, "stop", "()V", false));
        use.instructions.add(new InsnNode(Opcodes.RETURN));
        use.visitMaxs(0, 0);
        cs.methods.add(use);
        Map<String, byte[]> entries = base();
        entries.put(loop + ".class", bytes(cl));
        entries.put(sub + ".class", bytes(cs));
        Path in = jarOf(tmp, "in-stop.jar", entries);

        Path out = tmp.resolve("out-stop.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.OK, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(hasDiag(r, "STRUCTURAL_RENAME_FINAL_OVERRIDE"), "must carry rename code: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("no override semantics")),
                "evidence must say override semantics are gone: " + r.diagnostics());
        ClassNode nl = readClass(out, loop + ".class");
        assertTrue(nl.methods.stream().anyMatch(m -> m.name.equals("umb$stop") && m.desc.equals("()V")),
                "stop must be renamed to umb$stop");
        assertFalse(nl.methods.stream().anyMatch(m -> m.name.equals("stop")),
                "no stop()V may remain to clash with the host final");
        // Self call site rewritten.
        MethodNode ntick = nl.methods.stream().filter(m -> m.name.equals("func_73660_a")).findFirst().orElseThrow();
        boolean selfRewritten = false;
        for (var insn = ntick.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.name.equals("umb$stop")) selfRewritten = true;
            assertFalse(insn instanceof MethodInsnNode mi2
                            && (mi2.getOpcode() == Opcodes.INVOKEVIRTUAL || mi2.getOpcode() == Opcodes.INVOKEINTERFACE)
                            && mi2.name.equals("stop") && mi2.desc.equals("()V"),
                    "no stale stop()V call may remain in the declarer");
        }
        assertTrue(selfRewritten, "self call site must target umb$stop");
        // Subclass call site rewritten.
        ClassNode ns = readClass(out, sub + ".class");
        MethodNode nuse = ns.methods.stream().filter(m -> m.name.equals("use")).findFirst().orElseThrow();
        boolean subRewritten = false;
        for (var insn = nuse.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.name.equals("umb$stop")) subRewritten = true;
        }
        assertTrue(subRewritten, "subclass call site must follow the rename");
    }

    @Test
    void overrideFinalThroughModIntermediateRenames() throws Exception {
        // BurrowingBase sits between the mod class and host Entity (via the
        // baked PathfinderMob -> Mob -> LivingEntity -> Entity chain).
        String base = "com/example/BurrowingBase";
        ClassNode cb = new ClassNode();
        cb.visit(52, Opcodes.ACC_PUBLIC, base, null, MOB, null);
        String tunneler = "com/example/Tunneler";
        ClassNode ct = new ClassNode();
        ct.visit(52, Opcodes.ACC_PUBLIC, tunneler, null, base, null);
        MethodNode g = new MethodNode(Opcodes.ACC_PROTECTED, "getGravity", "()D", null, null);
        g.instructions.add(new InsnNode(Opcodes.DCONST_0));
        g.instructions.add(new InsnNode(Opcodes.DRETURN));
        g.visitMaxs(0, 0);
        ct.methods.add(g);
        Map<String, byte[]> entries = base();
        entries.put(base + ".class", bytes(cb));
        entries.put(tunneler + ".class", bytes(ct));
        Path in = jarOf(tmp, "in-grav.jar", entries);

        Path out = tmp.resolve("out-grav.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.OK, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        ClassNode nt = readClass(out, tunneler + ".class");
        assertTrue(nt.methods.stream().anyMatch(m -> m.name.equals("umb$getGravity") && m.desc.equals("()D")),
                "getGravity must rename through the mod intermediate: "
                        + nt.methods.stream().map(m -> m.name + m.desc).toList());
    }

    @Test
    void overrideFinalRenameCollisionRefusesRatherThanClobbers() throws Exception {
        String internal = "com/example/LoopClash";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, TICK_SOUND, null);
        MethodNode stop = new MethodNode(Opcodes.ACC_PROTECTED, "stop", "()V", null, null);
        voidBody(stop);
        cn.methods.add(stop);
        MethodNode clash = new MethodNode(Opcodes.ACC_PROTECTED, "umb$stop", "()V", null, null);
        voidBody(clash);
        cn.methods.add(clash);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-clash.jar", entries);
        byte[] before = rawClass(in, internal + ".class");

        Path out = tmp.resolve("out-clash.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString());
        assertTrue(hasDiag(r, "STRUCTURAL_UNRESOLVABLE"), "collision must refuse: " + r.diagnostics());
        assertArrayEquals(before, rawClass(out, internal + ".class"), "clashing class must pass through untouched");
    }

    // ------------------------------------------------------------------ scope rule

    @Test
    void noFindingsSkippedWithIdentityCopy() throws Exception {
        String internal = "com/example/Plain";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        entries.put("assets/x/note.txt", new byte[]{0x01});
        Path in = jarOf(tmp, "in-plain.jar", entries);
        byte[] before = rawClass(in, internal + ".class");

        Path out = tmp.resolve("out-plain.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.SKIPPED, r.status(), r.notes().toString());
        assertEquals("M11-structural", r.passId());
        assertTrue(r.diagnostics().isEmpty(), "skip must carry no diagnostics");
        assertArrayEquals(before, rawClass(out, internal + ".class"), "skip must be an identity copy");
        try (JarFile jf = new JarFile(out.toFile())) {
            assertNotNull(jf.getEntry("assets/x/note.txt"), "non-class entries must survive");
        }
    }

    @Test
    void untrackedSupertypeLeftAlone() throws Exception {
        // A superclass that is neither a tabled interface, final, nor redirect
        // target must not be touched (D4 scope rule).
        String internal = "com/example/Custom";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, "com/example/OtherBase", null);
        MethodNode stop = new MethodNode(Opcodes.ACC_PUBLIC, "stop", "()V", null, null);
        voidBody(stop);
        cn.methods.add(stop);
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-scope.jar", entries);

        Path out = tmp.resolve("out-scope.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.SKIPPED, r.status(), r.notes().toString()
                + " — stop()V without a host-final ancestor must not rename");
        ClassNode n = readClass(out, internal + ".class");
        assertTrue(n.methods.stream().anyMatch(m -> m.name.equals("stop")), "untracked stop()V must survive");
    }

    @Test
    void entryImplementsClassDropKeepsAbstractEntryUsable() throws Exception {
        // GuiFileList$Row shape: implements the host-abstract Entry class.
        String internal = "com/example/Row";
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object",
                new String[]{"net/minecraft/client/gui/components/AbstractSelectionList$Entry"});
        Map<String, byte[]> entries = base();
        entries.put(internal + ".class", bytes(cn));
        Path in = jarOf(tmp, "in-row.jar", entries);

        Path out = tmp.resolve("out-row.jar");
        PassReport r = new StructuralRepairPass().run(analyze(in), in, out);

        assertEquals(PassReport.Status.OK, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(readClass(out, internal + ".class").interfaces.isEmpty(), "Entry clause must drop");
    }
}
