package dev.umb.objbridge;

import dev.umb.objbridge.patch.ItemModelsPatcher;
import dev.umb.objbridge.patch.ModelManagerPatcher;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs both transformers over the REAL bytes from {@code research/jars/26.2/client.jar} and asserts
 * that the hook call really lands immediately before the method's {@code RETURN}. This is the whole
 * patch proven headless, without launching the game.
 */
class PatcherTest {

    private static final Path CLIENT = Paths.get("research/jars/26.2/client.jar");

    private static byte[] classBytes(String internalName) throws IOException {
        if (!Files.isRegularFile(CLIENT)) return null;
        try (ZipFile zf = new ZipFile(CLIENT.toFile())) {
            ZipEntry e = zf.getEntry(internalName + ".class");
            assertNotNull(e, internalName + " not found in " + CLIENT);
            try (InputStream in = zf.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name)) return mn;
        }
        return null;
    }

    @Test
    void itemModelsBootstrapGetsTheHookRightBeforeItsReturn() throws Exception {
        byte[] original = classBytes("net/minecraft/client/renderer/item/ItemModels");
        if (original == null) return;

        MethodNode before = method(original, "bootstrap");
        assertNotNull(before);
        assertEquals("()V", before.desc);
        assertEquals(0, countHooks(before, "registerItemModelType"), "unpatched already?");

        byte[] patched = new ItemModelsPatcher().transform(
                null, "net/minecraft/client/renderer/item/ItemModels", null, null, original);
        assertNotNull(patched, "transform returned null - see ItemModelsPatcher.result");
        assertTrue(ItemModelsPatcher.result.startsWith("ItemModels.bootstrap()V@return"),
                "result was: " + ItemModelsPatcher.result);

        MethodNode after = method(patched, "bootstrap");
        assertNotNull(after);
        assertEquals(1, countHooks(after, "registerItemModelType"));

        // the hook must be the very last real instruction before RETURN
        AbstractInsnNode ret = lastOpcode(after, Opcodes.RETURN);
        assertNotNull(ret);
        AbstractInsnNode prev = previousReal(ret);
        assertTrue(prev instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKESTATIC
                        && m.owner.equals("dev/umb/objbridge/ObjBridge")
                        && m.name.equals("registerItemModelType")
                        && m.desc.equals("()V"),
                "expected our INVOKESTATIC directly before RETURN, found " + prev);
    }

    @Test
    void modelManagerApplyGetsAloadZeroAndTheHookBeforeItsReturn() throws Exception {
        byte[] original = classBytes("net/minecraft/client/resources/model/ModelManager");
        if (original == null) return;

        MethodNode before = method(original, "apply");
        assertNotNull(before, "ModelManager.apply not found");
        assertTrue(before.desc.contains("ReloadState"), "apply desc was " + before.desc);
        assertEquals(0, countHooks(before, "onModelsApplied"));

        byte[] patched = new ModelManagerPatcher().transform(
                null, "net/minecraft/client/resources/model/ModelManager", null, null, original);
        assertNotNull(patched, "transform returned null - see ModelManagerPatcher.result");
        assertTrue(ModelManagerPatcher.result.startsWith("ModelManager.apply("),
                "result was: " + ModelManagerPatcher.result);

        MethodNode after = method(patched, "apply");
        assertNotNull(after);
        assertEquals(1, countHooks(after, "onModelsApplied"));

        AbstractInsnNode ret = lastOpcode(after, Opcodes.RETURN);
        assertNotNull(ret);
        AbstractInsnNode call = previousReal(ret);
        assertTrue(call instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKESTATIC
                        && m.owner.equals("dev/umb/objbridge/ObjBridge")
                        && m.name.equals("onModelsApplied")
                        && m.desc.equals("(Lnet/minecraft/client/resources/model/ModelManager;)V"),
                "expected our INVOKESTATIC before RETURN, found " + call);
        AbstractInsnNode aload = previousReal(call);
        assertTrue(aload instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0,
                "the hook must be handed `this`, found " + aload);
    }

    @Test
    void bothTransformersIgnoreEveryOtherClass() throws Exception {
        byte[] someOtherClass = classBytes("net/minecraft/core/Direction");
        if (someOtherClass == null) return;
        assertNull(new ItemModelsPatcher().transform(
                null, "net/minecraft/core/Direction", null, null, someOtherClass));
        assertNull(new ModelManagerPatcher().transform(
                null, "net/minecraft/core/Direction", null, null, someOtherClass));
    }

    @Test
    void transformNeverThrowsOnGarbageInput() {
        byte[] garbage = {0, 1, 2, 3, 4, 5, 6, 7};
        assertNull(new ItemModelsPatcher().transform(
                null, "net/minecraft/client/renderer/item/ItemModels", null, null, garbage));
        assertTrue(ItemModelsPatcher.result.startsWith("PATCH-FAILED"),
                "result was: " + ItemModelsPatcher.result);
        assertNull(new ModelManagerPatcher().transform(
                null, "net/minecraft/client/resources/model/ModelManager", null, null, garbage));
        assertTrue(ModelManagerPatcher.result.startsWith("PATCH-FAILED"),
                "result was: " + ModelManagerPatcher.result);
    }

    private static int countHooks(MethodNode mn, String hookName) {
        int n = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode m
                    && m.owner.equals("dev/umb/objbridge/ObjBridge")
                    && m.name.equals(hookName)) {
                n++;
            }
        }
        return n;
    }

    private static AbstractInsnNode lastOpcode(MethodNode mn, int opcode) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == opcode) found = insn;
        }
        return found;
    }

    /** Skips labels/line numbers/frames backwards to the previous real instruction. */
    private static AbstractInsnNode previousReal(AbstractInsnNode from) {
        AbstractInsnNode p = from.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        return p;
    }
}
