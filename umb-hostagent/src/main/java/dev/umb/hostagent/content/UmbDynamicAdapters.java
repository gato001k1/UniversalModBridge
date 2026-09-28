package dev.umb.hostagent.content;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;

/**
 * Synthesises, AT AGENT RUNTIME, the thin adapter classes that must literally {@code implements}
 * a package-private 26.2 interface ({@code MenuType$MenuSupplier},
 * {@code MenuScreens$ScreenConstructor} -- see {@link dev.umb.hostagent.UmbAccessWidener}).
 *
 * A {@link java.lang.reflect.Proxy} is not usable here: the JDK defines a dynamic proxy class for
 * a non-public interface INSIDE that interface's own package, and {@code net.minecraft.*} is a
 * signed, sealed package -- defining any class there throws
 * {@code SecurityException: signer information does not match}. Defining our OWN class in our OWN
 * (unsealed) package that happens to {@code implements} the (by then access-widened) interface has
 * no such restriction; the JVM's class-linking accessibility check for "does this implements
 * clause resolve" inspects only the target interface's own class-file access_flags, which
 * {@code UmbAccessWidener} has already flipped to {@code ACC_PUBLIC} by the time these adapters
 * are generated (both interfaces are loaded, and therefore already transformed, well before
 * {@code Hooks.beforeFreeze} runs -- MenuType's static initialiser touches MenuSupplier building
 * every vanilla MenuType constant, and that runs long before bootstrap reaches us).
 *
 * Each generated class is a bare shim: a public no-arg constructor plus one method whose body does
 * nothing but forward to an ordinary, precompiled static helper method with an identical erased
 * descriptor. All real logic stays in normal javac-compiled Java; only the {@code implements}
 * relationship is bytecode-generated, so javac never has to see the package-private interface.
 *
 * The generated class is defined via {@link MethodHandles.Lookup#defineClass(byte[])} using a
 * {@link MethodHandles#lookup()} captured RIGHT HERE, inside this very class -- NOT via a
 * dedicated child {@link ClassLoader} (that was the bug: a runtime package is (loader, name), not
 * just name, so a class loaded by a child loader is in a DIFFERENT runtime package from an
 * identically-named one in the parent loader, even though javap/source both print the same
 * package). {@code defineClass(byte[])} always defines the new class in the LOOKUP CLASS's own
 * loader, runtime package and protection domain -- since this class ({@code UmbDynamicAdapters})
 * is compiled into, and loaded by, the very same loader as {@code UmbMenuRegistration} (both are
 * ordinary classes in the agent jar, loaded by the app/system loader like everything else in this
 * single-classloader host), the generated adapter ends up in that SAME loader and package too, so
 * the package-private helper method it forwards to (e.g.
 * {@code UmbMenuRegistration.clientCreateMenuHelper}) is legally callable. This was the exact hop
 * that crashed live (see crash-2026-09-08_17.19.11-client.txt): {@code UmbMenuSupplierGen}, defined
 * by the old {@code UmbDynamicAdapters$Loader} child loader, could implement the (by-then-public)
 * {@code MenuType$MenuSupplier} interface just fine, but could not reach
 * {@code UmbMenuRegistration}'s package-private helper across that loader boundary --
 * {@code IllegalAccessError}, thrown the first time a menu was actually opened rather than at
 * registration time, which is why the previous test suite (registration-only) never caught it. A
 * full-privilege {@code MethodHandles.lookup()} (this is one: a plain, undropped lookup captured by
 * ordinary compiled-in code) is required for {@code defineClass}; it is otherwise unrestricted --
 * no reflection, no {@code setAccessible}, and (unlike the removed child loader) no extra
 * classloader identity for the rest of the JVM to reason about.
 */
final class UmbDynamicAdapters {

    private UmbDynamicAdapters() {
    }

    /**
     * Generates {@code final class <generatedInternalName> implements <implementsInternalName> }
     * with a public no-arg ctor and a single public method {@code <methodName><methodDesc>} whose
     * body forwards every argument, in order, to the static method
     * {@code <helperOwnerInternalName>.<helperMethodName>} -- which MUST have exactly the same
     * descriptor (same argument types, same return type) as {@code methodDesc} -- and returns its
     * result. Returns a live instance of the generated class.
     */
    static Object generateSingleMethodAdapter(String generatedInternalName, String implementsInternalName,
                                              String methodName, String methodDesc,
                                              String helperOwnerInternalName, String helperMethodName) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                generatedInternalName, null, "java/lang/Object", new String[]{implementsInternalName});

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDesc, null, null);
        mv.visitCode();
        Type[] args = Type.getArgumentTypes(methodDesc);
        int slot = 1;
        for (Type a : args) {
            mv.visitVarInsn(a.getOpcode(Opcodes.ILOAD), slot);
            slot += a.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, helperOwnerInternalName, helperMethodName, methodDesc, false);
        Type ret = Type.getReturnType(methodDesc);
        mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        // A plain, full-privilege lookup captured right here (not passed in, not weakened) -- see
        // this class's own javadoc for why defineClass(byte[]) landing the generated class in
        // THIS lookup's loader+package (i.e. UmbMenuRegistration's own) is exactly the fix.
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> generated = lookup.defineClass(bytes);
        return generated.getDeclaredConstructor().newInstance();
    }
}
