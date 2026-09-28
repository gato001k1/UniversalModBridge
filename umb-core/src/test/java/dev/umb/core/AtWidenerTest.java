package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-4: AT / accessWidener parsing and widening. Synthetic fixtures only (CC0).
 */
class AtWidenerTest {

    private static ClassNode target() {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, "com/example/Target", null, "java/lang/Object", null);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "secret", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "finalSecret", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "prot", "Ljava/lang/String;", null, null));
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "hidden", "()V", null, null);
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        m.visitMaxs(0, 0);
        cn.methods.add(m);
        MethodNode pub = new MethodNode(Opcodes.ACC_PUBLIC, "visible", "()V", null, null);
        pub.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        pub.visitMaxs(0, 0);
        cn.methods.add(pub);
        return cn;
    }

    @Test
    void parseFmlAtPublicFieldWithComment() {
        String at = "public com.example.Target secret # comment\n";
        AtWidener.ParseResult pr = AtWidener.parseFmlAt(at);
        assertEquals(1, pr.rules().size());
        assertTrue(pr.evidence().isEmpty(), pr.evidence().toString());
        AtWidener.AtRule r = pr.rules().get(0);
        assertEquals("com/example/Target", r.internalClass());
        assertEquals("secret", r.memberName());
        assertTrue(r.makePublic());
    }

    @Test
    void parseFmlAtPublicClassOnly() {
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target\n");
        assertEquals(1, pr.rules().size());
        assertEquals(AtWidener.TargetKind.CLASS, pr.rules().get(0).kind());
    }

    @Test
    void parseFmlAtPublicMinusFRemovesFinal() {
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public-f com.example.Target hidden()V\n");
        assertEquals(1, pr.rules().size());
        AtWidener.AtRule r = pr.rules().get(0);
        assertTrue(r.makePublic());
        assertTrue(r.removeFinal());
        assertEquals("hidden", r.memberName());
        assertEquals("()V", r.descriptor());
    }

    @Test
    void parseFmlAtWildcardStarForFields() {
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target *\n");
        assertEquals(1, pr.rules().size());
        assertEquals(AtWidener.TargetKind.WILDCARD_FIELDS, pr.rules().get(0).kind());
    }

    @Test
    void parseFmlAtWildcardStarParenForMethods() {
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target *()\n");
        assertEquals(1, pr.rules().size());
        assertEquals(AtWidener.TargetKind.WILDCARD_METHODS, pr.rules().get(0).kind());
    }

    @Test
    void parseFmlAtMalformedEmitsEvidence() {
        // missing target
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public\n");
        assertTrue(pr.rules().isEmpty());
        assertFalse(pr.evidence().isEmpty());
        assertTrue(pr.evidence().get(0).kind().equals(AtWidener.EVIDENCE_KIND));
    }

    @Test
    void parseFmlAtCommentAndBlankIgnored() {
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("# full line comment\n\npublic com.example.Target secret\n  # inline\n");
        assertEquals(1, pr.rules().size());
        assertTrue(pr.evidence().isEmpty());
    }

    @Test
    void parseAccessWidenerV2Fields() {
        String widener = "accessWidener v2 named\n"
                + "accessible field com/example/Target secret I\n"
                + "mutable field com/example/Target finalSecret I\n"
                + "extendable field com/example/Target finalSecret I\n"
                + "transitive-accessible class com/example/Target\n"
                + "# comment line\n";
        AtWidener.ParseResult pr = AtWidener.parseAccessWidener(widener);
        assertEquals(4, pr.rules().size(), pr.evidence().toString());
        assertTrue(pr.evidence().isEmpty());
        AtWidener.AtRule r0 = pr.rules().get(0);
        assertEquals(AtWidener.TargetKind.FIELD, r0.kind());
        assertTrue(r0.makePublic());
        AtWidener.AtRule r1 = pr.rules().get(1);
        assertTrue(r1.removeFinal());
        AtWidener.AtRule r3 = pr.rules().get(3);
        assertEquals(AtWidener.TargetKind.CLASS, r3.kind());
    }

    @Test
    void parseAccessWidenerMethod() {
        String w = "accessWidener v2 named\naccessible method com/example/Target hidden ()V\n";
        AtWidener.ParseResult pr = AtWidener.parseAccessWidener(w);
        assertEquals(1, pr.rules().size());
        AtWidener.AtRule r = pr.rules().get(0);
        assertEquals(AtWidener.TargetKind.METHOD, r.kind());
        assertEquals("hidden", r.memberName());
        assertEquals("()V", r.descriptor());
    }

    @Test
    void parseAccessWidenerMalformedEmitsEvidence() {
        AtWidener.ParseResult pr = AtWidener.parseAccessWidener("accessible\n");
        assertTrue(pr.rules().isEmpty());
        assertFalse(pr.evidence().isEmpty());
    }

    @Test
    void applyWidensPrivateFieldToPublic() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target secret\n");
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr.rules(), ev);
        assertEquals(1, wr.widened());
        FieldNode fn = cn.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
        assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((fn.access & Opcodes.ACC_PRIVATE) == 0);
        assertTrue(ev.isEmpty());
    }

    @Test
    void applyRemovesFinal() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public-f com.example.Target hidden()V\n");
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr.rules());
        assertEquals(1, wr.widened());
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("hidden")).findFirst().orElseThrow();
        assertTrue((mn.access & Opcodes.ACC_FINAL) == 0);
        assertTrue((mn.access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void applyWildcardWidensAllFields() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target *\n");
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr.rules());
        // private + protected fields should all become public (3 fields)
        assertTrue(wr.widened() >= 2);
        for (FieldNode fn : cn.fields) assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void applyMissingTargetIsEvidenceD4() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target missingField\n");
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr.rules(), ev);
        assertEquals(0, wr.widened());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("not found")));
    }

    @Test
    void applyViaWidenerAccessibleAndMutable() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseAccessWidener(
                "accessWidener v2 named\naccessible field com/example/Target secret I\n");
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr.rules());
        assertEquals(1, wr.widened());
        FieldNode fn = cn.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
        assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void applyAlreadyPublicIsNoOp() {
        ClassNode cn = target();
        AtWidener.ParseResult pr = AtWidener.parseFmlAt("public com.example.Target visible()V\n");
        // need actual method widen for visible which is already public
        AtWidener.ParseResult pr2 = AtWidener.parseAccessWidener(
                "accessWidener v2 named\naccessible method com/example/Target visible ()V\n");
        AtWidener.WidenResult wr = AtWidener.apply(cn, pr2.rules());
        assertEquals(0, wr.widened());
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals("visible")).findFirst().orElseThrow();
        assertTrue((mn.access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void applyWithNullRulesIsZero() {
        ClassNode cn = target();
        AtWidener.WidenResult wr = AtWidener.apply(cn, null);
        assertEquals(0, wr.widened());
    }
}
