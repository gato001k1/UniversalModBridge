package dev.umb.hostagent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs both transformers over the REAL 26.2 class bytes and asserts what they produced. */
class PatcherTest {

    private static final Path CLIENT = Paths.get("research/jars/26.2/client.jar");

    private static byte[] classBytes(String internalName) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(CLIENT), "client.jar not present: " + CLIENT);
        try (ZipFile zip = new ZipFile(CLIENT.toFile())) {
            ZipEntry e = zip.getEntry(internalName + ".class");
            assertNotNull(e, "no such class in client.jar: " + internalName);
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    private static MethodNode method(byte[] bytes, String name, String desc) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        return null;
    }

    @Test
    void hookLandsAtTheStartOfBuiltInRegistriesFreeze() throws Exception {
        byte[] before = classBytes(BuiltInRegistriesPatcher.TARGET);
        MethodNode orig = method(before, "freeze", "()V");
        assertNotNull(orig, "26.2 is expected to still have private static void freeze()");

        byte[] after = BuiltInRegistriesPatcher.patch(before);
        assertNotNull(after, "patch must apply");
        assertEquals("freeze()V@entry", BuiltInRegistriesPatcher.variantApplied);

        MethodNode patched = method(after, "freeze", "()V");
        AbstractInsnNode first = null;
        for (AbstractInsnNode in = patched.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() >= 0) {
                first = in;
                break;
            }
        }
        assertNotNull(first);
        assertEquals(Opcodes.INVOKESTATIC, first.getOpcode());
        MethodInsnNode call = (MethodInsnNode) first;
        assertEquals("dev/umb/hostagent/Hooks", call.owner);
        assertEquals("beforeFreeze", call.name);
        assertEquals("()V", call.desc);
        // and the original body still follows
        assertEquals(orig.instructions.size() + 1, patched.instructions.size());
    }

    @Test
    void creativeTabSpriteClampIsNarrowedByOne() throws Exception {
        byte[] before = classBytes(CreativeTabSpritePatcher.TARGET);
        byte[] after = CreativeTabSpritePatcher.patch(before);
        assertNotNull(after, "26.2 is expected to clamp the tab sprite index against arr.length");

        MethodNode m = method(after, "extractTabButton", null);
        assertNotNull(m);
        int fixed = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.ARRAYLENGTH) continue;
            AbstractInsnNode a = in.getNext();
            AbstractInsnNode b = a == null ? null : a.getNext();
            if (a != null && b != null && a.getOpcode() == Opcodes.ICONST_1 && b.getOpcode() == Opcodes.ISUB) fixed++;
        }
        assertTrue(fixed >= 1, "expected at least one ARRAYLENGTH,ICONST_1,ISUB sequence");

        // idempotent: re-running finds nothing left to do
        assertEquals(null, CreativeTabSpritePatcher.patch(after));
    }

    @Test
    void transformerIgnoresEveryOtherClass() {
        BuiltInRegistriesPatcher p = new BuiltInRegistriesPatcher();
        assertEquals(null, p.transform(null, "java/lang/String", null, null, new byte[]{1, 2, 3}));
        CreativeTabSpritePatcher q = new CreativeTabSpritePatcher();
        assertEquals(null, q.transform(null, "java/lang/String", null, null, new byte[]{1, 2, 3}));
    }

    @Test
    void agentArgsParse() {
        var kv = HostAgent.parse("snapshot=C:\\a\\b.json;log=C:\\c\\d.log;ns=hbm");
        assertEquals("C:\\a\\b.json", kv.get("snapshot"));
        assertEquals("C:\\c\\d.log", kv.get("log"));
        assertEquals("hbm", kv.get("ns"));
        assertEquals(0, HostAgent.parse(null).size());
        assertEquals(0, HostAgent.parse("").size());
    }
}
