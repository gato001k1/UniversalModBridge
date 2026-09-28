package dev.umb.hostagent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs UmbAccessWidener over the REAL 26.2 class bytes for all three targets and asserts:
 *  - before the patch, the flags really are the restrictive ones javap showed us (so the test
 *    would fail loudly if a future 26.2 build ever changes this),
 *  - after the patch, ACC_PUBLIC is set and CheckClassAdapter still verifies (flag-only edits
 *    change no stack map frame, so COMPUTE_MAXS alone must still be correct).
 */
class UmbAccessWidenerTest {

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

    private static MethodNode method(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    private static void verify(byte[] bytes) throws Exception {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(sw));
        assertEquals("", sw.toString().trim(), "CheckClassAdapter reported:\n" + sw);
    }

    @Test
    void menuTypeCtorIsPrivateInVanillaAndPublicAfterThePatch() throws Exception {
        byte[] before = classBytes(UmbAccessWidener.MENU_TYPE);
        ClassNode orig = read(before);
        MethodNode ctorBefore = null;
        for (MethodNode m : orig.methods) {
            if ("<init>".equals(m.name)
                    && "(Lnet/minecraft/world/inventory/MenuType$MenuSupplier;Lnet/minecraft/world/flag/FeatureFlagSet;)V".equals(m.desc)) {
                ctorBefore = m;
            }
        }
        assertNotNull(ctorBefore, "26.2 MenuType is expected to have the (MenuSupplier,FeatureFlagSet) ctor");
        assertTrue((ctorBefore.access & Opcodes.ACC_PRIVATE) != 0, "ctor must be private before patching");
        assertFalse((ctorBefore.access & Opcodes.ACC_PUBLIC) != 0, "ctor must not already be public");

        byte[] after = UmbAccessWidener.widenCtor(before);
        assertNotNull(after, "patch must apply");
        MethodNode ctorAfter = null;
        for (MethodNode m : read(after).methods) {
            if ("<init>".equals(m.name)
                    && "(Lnet/minecraft/world/inventory/MenuType$MenuSupplier;Lnet/minecraft/world/flag/FeatureFlagSet;)V".equals(m.desc)) {
                ctorAfter = m;
            }
        }
        assertNotNull(ctorAfter);
        assertTrue((ctorAfter.access & Opcodes.ACC_PUBLIC) != 0, "ctor must be public after patching");
        assertFalse((ctorAfter.access & Opcodes.ACC_PRIVATE) != 0, "private bit must be cleared");
        verify(after);
    }

    @Test
    void menuSupplierInterfaceIsPackagePrivateInVanillaAndPublicAfterThePatch() throws Exception {
        byte[] before = classBytes(UmbAccessWidener.MENU_SUPPLIER);
        ClassNode orig = read(before);
        assertTrue((orig.access & Opcodes.ACC_INTERFACE) != 0);
        assertFalse((orig.access & Opcodes.ACC_PUBLIC) != 0, "MenuType$MenuSupplier must not already be public");
        MethodNode createBefore = method(orig, "create");
        assertNotNull(createBefore, "26.2 MenuSupplier is expected to declare create(int, Inventory)");
        assertTrue((createBefore.access & Opcodes.ACC_PUBLIC) != 0,
                "interface methods are always ACC_PUBLIC in bytecode even on a package-private interface");

        byte[] after = UmbAccessWidener.widenInterface(before, UmbAccessWidener.MENU_SUPPLIER, "create");
        assertNotNull(after);
        ClassNode patched = read(after);
        assertTrue((patched.access & Opcodes.ACC_PUBLIC) != 0, "class must be public after patching");
        assertTrue((patched.access & Opcodes.ACC_INTERFACE) != 0, "must still be an interface");
        for (InnerClassNode icn : patched.innerClasses) {
            if (UmbAccessWidener.MENU_SUPPLIER.equals(icn.name)) {
                assertTrue((icn.access & Opcodes.ACC_PUBLIC) != 0, "self InnerClasses entry must also read public");
            }
        }
        verify(after);
    }

    @Test
    void screenConstructorInterfaceIsPackagePrivateInVanillaAndPublicAfterThePatch() throws Exception {
        byte[] before = classBytes(UmbAccessWidener.SCREEN_CONSTRUCTOR);
        ClassNode orig = read(before);
        assertTrue((orig.access & Opcodes.ACC_INTERFACE) != 0);
        assertFalse((orig.access & Opcodes.ACC_PUBLIC) != 0, "MenuScreens$ScreenConstructor must not already be public");
        assertNotNull(method(orig, "create"), "26.2 ScreenConstructor is expected to declare create(...)");
        assertNotNull(method(orig, "fromPacket"), "26.2 ScreenConstructor is expected to declare fromPacket(...)");

        byte[] after = UmbAccessWidener.widenInterface(before, UmbAccessWidener.SCREEN_CONSTRUCTOR, "create", "fromPacket");
        assertNotNull(after);
        ClassNode patched = read(after);
        assertTrue((patched.access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((method(patched, "create").access & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((method(patched, "fromPacket").access & Opcodes.ACC_PUBLIC) != 0);
        verify(after);
    }

    @Test
    void transformIgnoresUnrelatedClassesAndNeverThrows() throws Exception {
        UmbAccessWidener w = new UmbAccessWidener();
        assertNull(w.transform(null, "java/lang/String", null, null, new byte[]{1, 2, 3}));
        assertNull(w.transform(null, UmbAccessWidener.MENU_TYPE, null, null, new byte[]{9, 9, 9}),
                "garbage bytes must produce PATCH-FAILED, not an exception");
        assertNull(w.transform(null, null, null, null, new byte[]{1}));
    }

    @Test
    void transformAppliesAllThreeTargetsOverRealClientBytes() throws Exception {
        UmbAccessWidener w = new UmbAccessWidener();
        byte[] a = w.transform(null, UmbAccessWidener.MENU_TYPE, null, null, classBytes(UmbAccessWidener.MENU_TYPE));
        byte[] b = w.transform(null, UmbAccessWidener.MENU_SUPPLIER, null, null, classBytes(UmbAccessWidener.MENU_SUPPLIER));
        byte[] c = w.transform(null, UmbAccessWidener.SCREEN_CONSTRUCTOR, null, null, classBytes(UmbAccessWidener.SCREEN_CONSTRUCTOR));
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        verify(a);
        verify(b);
        verify(c);
    }
}
