package dev.umb.hostagent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
 * Runs the creative-paging transformer over the REAL 26.2 CreativeModeInventoryScreen bytes,
 * asserts every target method was found, and hands the result to ASM's CheckClassAdapter -
 * which is the only cheap way to catch a wrong stack map frame, since edits 3 and 4 add a
 * branch target and the writer only uses COMPUTE_MAXS.
 */
class PagingPatcherTest {

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

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        return null;
    }

    private static int countCalls(MethodNode m, int opcode, String owner, String name) {
        int n = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != opcode) continue;
            MethodInsnNode c = (MethodInsnNode) in;
            if (owner.equals(c.owner) && name.equals(c.name)) n++;
        }
        return n;
    }

    @Test
    void everyPagingTargetIsFoundInTheRealClass() throws Exception {
        byte[] before = classBytes(CreativePagingPatcher.TARGET);
        ClassNode orig = read(before);

        // the targets have to exist in 26.2 before we claim to patch them
        assertNotNull(method(orig, CreativePagingPatcher.M_SCROLL, CreativePagingPatcher.D_SCROLL),
                "26.2 is expected to have mouseScrolled(DDDD)Z");
        assertNotNull(method(orig, CreativePagingPatcher.M_KEY, CreativePagingPatcher.D_KEY),
                "26.2 is expected to have keyPressed(KeyEvent)Z");
        assertNotNull(method(orig, CreativePagingPatcher.M_RENDER, CreativePagingPatcher.D_RENDER),
                "26.2 is expected to have extractRenderState(GuiGraphicsExtractor,IIF)V");

        byte[] after = CreativePagingPatcher.patch(before);
        assertNotNull(after, "patch must apply");

        // exact call-site counts, matching javap -c of the shipped jar
        assertEquals(5, CreativePagingPatcher.lastCounts.get("tabs"),
                "CreativeModeTabs.tabs() is called from 5 places in the screen");
        assertEquals(2, CreativePagingPatcher.lastCounts.get("column"),
                "CreativeModeTab.column() is called from getTabX and extractTabButton only");
        assertEquals(1, CreativePagingPatcher.lastCounts.get("scroll"));
        assertEquals(1, CreativePagingPatcher.lastCounts.get("key"));
        assertEquals(1, CreativePagingPatcher.lastCounts.get("hint"));
    }

    @Test
    void tabsAndColumnAreRedirectedToHooks() throws Exception {
        byte[] after = CreativePagingPatcher.patch(classBytes(CreativePagingPatcher.TARGET));
        assertNotNull(after);
        ClassNode cn = read(after);

        int tabs = 0, oldTabs = 0;
        for (MethodNode m : cn.methods) {
            tabs += countCalls(m, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "visibleTabs");
            oldTabs += countCalls(m, Opcodes.INVOKESTATIC, CreativePagingPatcher.TABS, "tabs");
        }
        assertEquals(5, tabs);
        assertEquals(0, oldTabs, "no CreativeModeTabs.tabs() call may survive inside the screen");

        // allTabs() in tryRefreshInvalidatedTabs is deliberately untouched
        MethodNode refresh = method(cn, "tryRefreshInvalidatedTabs", null);
        assertNotNull(refresh);
        assertEquals(1, countCalls(refresh, Opcodes.INVOKESTATIC, CreativePagingPatcher.TABS, "allTabs"));

        MethodNode getTabX = method(cn, "getTabX", null);
        assertNotNull(getTabX);
        assertEquals(1, countCalls(getTabX, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "tabColumn"));
        assertEquals(0, countCalls(getTabX, Opcodes.INVOKEVIRTUAL, CreativePagingPatcher.TAB, "column"));

        MethodNode tabButton = method(cn, "extractTabButton", null);
        assertNotNull(tabButton);
        assertEquals(1, countCalls(tabButton, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "tabColumn"));
        assertEquals(0, countCalls(tabButton, Opcodes.INVOKEVIRTUAL, CreativePagingPatcher.TAB, "column"));
    }

    @Test
    void scrollAndKeyGetAnEarlyReturnAndRenderGetsTheHint() throws Exception {
        byte[] after = CreativePagingPatcher.patch(classBytes(CreativePagingPatcher.TARGET));
        ClassNode cn = read(after);

        MethodNode scroll = method(cn, CreativePagingPatcher.M_SCROLL, CreativePagingPatcher.D_SCROLL);
        assertEquals(1, countCalls(scroll, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "creativeScroll"));
        MethodNode key = method(cn, CreativePagingPatcher.M_KEY, CreativePagingPatcher.D_KEY);
        assertEquals(1, countCalls(key, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "creativeKey"));

        // the hook is the FIRST real instruction's callee, i.e. it runs before vanilla's body
        AbstractInsnNode first = null;
        for (AbstractInsnNode in = scroll.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() >= 0) {
                first = in;
                break;
            }
        }
        assertEquals(Opcodes.ALOAD, first.getOpcode());

        MethodNode render = method(cn, CreativePagingPatcher.M_RENDER, CreativePagingPatcher.D_RENDER);
        assertEquals(1, countCalls(render, Opcodes.INVOKESTATIC, CreativePagingPatcher.HOOKS, "renderPageHint"));
        // ... and it is the last thing before the return, so the hint draws on top
        AbstractInsnNode last = render.instructions.getLast();
        while (last != null && last.getOpcode() != Opcodes.RETURN) last = last.getPrevious();
        assertNotNull(last);
        AbstractInsnNode prev = last.getPrevious();
        while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
        assertTrue(prev instanceof MethodInsnNode
                && "renderPageHint".equals(((MethodInsnNode) prev).name), "hint must be the last call");
    }

    /** The real point of this test: a bad stack map frame would only show up in a window. */
    @Test
    void patchedClassPassesCheckClassAdapter() throws Exception {
        byte[] after = CreativePagingPatcher.patch(classBytes(CreativePagingPatcher.TARGET));
        assertNotNull(after);
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(after), false, new PrintWriter(sw));
        assertEquals("", sw.toString().trim(), "CheckClassAdapter reported:\n" + sw);
    }

    @Test
    void bothClientPatchesComposeInTheOrderTheAgentInstallsThem() throws Exception {
        byte[] before = classBytes(CreativePagingPatcher.TARGET);
        byte[] clamped = CreativeTabSpritePatcher.patch(before);
        assertNotNull(clamped, "the v0 sprite clamp fix must still apply");
        byte[] both = CreativePagingPatcher.patch(clamped);
        assertNotNull(both, "paging must apply on top of the clamp fix");

        // the clamp fix survives
        ClassNode cn = read(both);
        MethodNode tabButton = method(cn, "extractTabButton", null);
        int fixed = 0;
        for (AbstractInsnNode in = tabButton.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.ARRAYLENGTH) continue;
            AbstractInsnNode a = in.getNext();
            AbstractInsnNode b = a == null ? null : a.getNext();
            if (a != null && b != null && a.getOpcode() == Opcodes.ICONST_1 && b.getOpcode() == Opcodes.ISUB) fixed++;
        }
        assertTrue(fixed >= 1, "ARRAYLENGTH-1 must survive the paging patch");

        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(both), false, new PrintWriter(sw));
        assertEquals("", sw.toString().trim(), "CheckClassAdapter reported:\n" + sw);
    }

    @Test
    void pagingPatchIsIdempotentAndNeverThrows() throws Exception {
        byte[] after = CreativePagingPatcher.patch(classBytes(CreativePagingPatcher.TARGET));
        // re-running finds no tabs()/column() left, so the guard returns null rather than
        // double-injecting the early returns
        assertNull(CreativePagingPatcher.patch(after));

        CreativePagingPatcher p = new CreativePagingPatcher();
        assertNull(p.transform(null, "java/lang/String", null, null, new byte[]{1, 2, 3}));
        assertNull(p.transform(null, CreativePagingPatcher.TARGET, null, null, new byte[]{9, 9, 9}),
                "garbage bytes must produce PATCH-FAILED, not an exception");
    }

    @Test
    void pageArithmeticIsTheSameOnBothSidesOfThePatch() {
        // TOP 0..4 per page; vanilla owns 0..6 so our tabs start at 7
        assertEquals(0, CreativePaging.pageOfColumn(0));
        assertEquals(0, CreativePaging.pageOfColumn(6));
        assertEquals(1, CreativePaging.pageOfColumn(7));
        assertEquals(1, CreativePaging.pageOfColumn(11));
        assertEquals(2, CreativePaging.pageOfColumn(12));
        assertEquals(2, CreativePaging.pageOfColumn(16));
        assertEquals(3, CreativePaging.pageOfColumn(17));

        assertEquals(0, CreativePaging.drawColumn(7));
        assertEquals(4, CreativePaging.drawColumn(11));
        assertEquals(0, CreativePaging.drawColumn(12));
        assertEquals(4, CreativePaging.drawColumn(16));
        // vanilla columns pass through untouched
        for (int c = 0; c <= 6; c++) assertEquals(c, CreativePaging.drawColumn(c));
        // a drawn column never lands on TOP 5 (Saved Hotbars) or TOP 6 (Search Items)
        for (int c = 7; c < 40; c++) assertTrue(CreativePaging.drawColumn(c) < 5);
    }
}
