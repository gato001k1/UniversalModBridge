package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Makes the private menu constructor and package-private factory interfaces accessible to runtime
 * adapters. These flag-only edits do not alter stack-map frames.
 */
public final class UmbAccessWidener implements ClassFileTransformer {

    public static final String MENU_TYPE = "net/minecraft/world/inventory/MenuType";
    public static final String MENU_SUPPLIER = "net/minecraft/world/inventory/MenuType$MenuSupplier";
    public static final String SCREEN_CONSTRUCTOR = "net/minecraft/client/gui/screens/MenuScreens$ScreenConstructor";

    private static final String CTOR_DESC =
            "(Lnet/minecraft/world/inventory/MenuType$MenuSupplier;Lnet/minecraft/world/flag/FeatureFlagSet;)V";

    public static volatile boolean menuTypeCtorWidened = false;
    public static volatile boolean menuSupplierWidened = false;
    public static volatile boolean screenConstructorWidened = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        try {
            switch (className) {
                case MENU_TYPE:
                    return widenCtor(classfileBuffer);
                case MENU_SUPPLIER:
                    return widenInterface(classfileBuffer, MENU_SUPPLIER, "create");
                case SCREEN_CONSTRUCTOR:
                    return widenInterface(classfileBuffer, SCREEN_CONSTRUCTOR, "create", "fromPacket");
                default:
                    return null;
            }
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED UmbAccessWidener " + className + ": " + t);
            AgentLog.error("UmbAccessWidener.transform", t, 5);
            return null;
        }
    }

    /** Package-visible for the unit test. Flips MenuType.<init>(MenuSupplier,FeatureFlagSet) private -> public. */
    static byte[] widenCtor(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        boolean found = false;
        for (MethodNode m : cn.methods) {
            if (!"<init>".equals(m.name) || !CTOR_DESC.equals(m.desc)) continue;
            m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            found = true;
        }
        if (!found) {
            AgentLog.loud("PATCH-FAILED UmbAccessWidener MenuType: ctor " + CTOR_DESC + " not found");
            return null;
        }
        menuTypeCtorWidened = true;
        AgentLog.loud("PATCHED MenuType.<init> " + CTOR_DESC + " private -> public");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Flips the class-level ACC_PUBLIC bit on a package-private interface, ensures the named
     * methods carry ACC_PUBLIC too (they already do in 26.2 -- interface methods are always
     * public in bytecode -- this is a defensive OR-in, never a downgrade), and fixes the
     * self-referencing InnerClasses attribute entry so reflection (Class.getModifiers()) agrees.
     */
    static byte[] widenInterface(byte[] original, String internalName, String... methodNames) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        if (!internalName.equals(cn.name)) {
            AgentLog.loud("PATCH-FAILED UmbAccessWidener: expected " + internalName + " got " + cn.name);
            return null;
        }
        cn.access |= Opcodes.ACC_PUBLIC;
        for (InnerClassNode icn : cn.innerClasses) {
            if (internalName.equals(icn.name)) {
                icn.access = (icn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            }
        }
        int methodsTouched = 0;
        for (MethodNode m : cn.methods) {
            for (String want : methodNames) {
                if (want.equals(m.name)) {
                    m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                    methodsTouched++;
                }
            }
        }
        if (SCREEN_CONSTRUCTOR.equals(internalName)) {
            screenConstructorWidened = true;
        } else if (MENU_SUPPLIER.equals(internalName)) {
            menuSupplierWidened = true;
        }
        AgentLog.loud("PATCHED " + internalName + " package -> public (" + methodsTouched + " method flags confirmed public)");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
