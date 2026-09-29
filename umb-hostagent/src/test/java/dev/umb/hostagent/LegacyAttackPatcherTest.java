package dev.umb.hostagent;

import dev.umb.hostagent.input.LegacyClientInputHook;
import dev.umb.hostagent.input.LegacyInputFrame;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * an iron chest, even though {@link LegacyAttackPatcher} already patched {@code startAttack()}. See
 * left-click held-down block mining through a SECOND, previously-unpatched method,
 * {@code continueAttack(boolean)} - this fixture proves both methods now honour
 * {@link LegacyClientInputHook#suppressVanillaAttack()}, and that the real patched
 * {@code Minecraft.class} still links.
 */
class LegacyAttackPatcherTest {

    private static final Path CLIENT_JAR = Paths.get("research/jars/26.2/client.jar");
    private static final String MC_BINARY_NAME = "net.minecraft.client.Minecraft";
    private static final String MC_INTERNAL_NAME = "net/minecraft/client/Minecraft";

    @AfterEach
    void resetInputState() {
        LegacyClientInputHook.setLastForTest(null);
        Recorder.startAttackRan = false;
        Recorder.lastContinueArg = null;
    }

    /** What the fixture's method bodies do once they run past any inserted guard. */
    public static final class Recorder {
        static boolean startAttackRan;
        static Boolean lastContinueArg;

        public static void markStartAttack() {
            startAttackRan = true;
        }

        public static void recordContinue(boolean value) {
            lastContinueArg = value;
        }
    }

    private static LegacyInputFrame heldGunFrame() {
        return new LegacyInputFrame(1L, "player-1", "hbm:item.gun_uzi", 0, 1, null,
                false, false, true, true, false, 0, 0, -1, 0f, 0f, 0, Map.of());
    }

    @Test
    void suppressedStartAttackReturnsTrueWithoutRunningTheOriginalBody() throws Exception {
        Object mc = newFixtureInstance();
        LegacyClientInputHook.setLastForTest(heldGunFrame());

        boolean handled = startAttack(mc);

        assertTrue(handled, "a suppressed click must report itself as already handled");
        assertFalse(Recorder.startAttackRan, "the vanilla swing/startDestroyBlock body must not run");
    }

    @Test
    void unsuppressedStartAttackRunsTheOriginalBody() throws Exception {
        Object mc = newFixtureInstance();
        LegacyClientInputHook.setLastForTest(null); // no legacy item held

        boolean handled = startAttack(mc);

        assertFalse(handled, "the fixture's own body returns false");
        assertTrue(Recorder.startAttackRan, "nothing legacy claimed the click, vanilla must run normally");
    }

    @Test
    void suppressedContinueAttackForcesTheArgumentFalse() throws Exception {
        // The live bug: this is the method that ACTUALLY performs continued block mining every
        // tick the button stays held, completely bypassing startAttack() after the first tick.
        Object mc = newFixtureInstance();
        LegacyClientInputHook.setLastForTest(heldGunFrame());

        continueAttack(mc, true);

        assertEquals(Boolean.FALSE, Recorder.lastContinueArg,
                "held-gun suppression must force continueAttack down its own 'stop destroying' branch");
    }

    @Test
    void unsuppressedContinueAttackPassesTheArgumentThrough() throws Exception {
        Object mc = newFixtureInstance();
        LegacyClientInputHook.setLastForTest(null);

        continueAttack(mc, true);

        assertEquals(Boolean.TRUE, Recorder.lastContinueArg,
                "vanilla mining (no legacy item held) must be completely unaffected");
    }

    @Test
    void aNonMinecraftClassIsNeverTouched() {
        byte[] original = buildFixtureMinecraft();
        byte[] transformed = new LegacyAttackPatcher()
                .transform(null, "net/minecraft/client/gui/SomeOtherClass", null, null, original);
        assertNull(transformed, "only net/minecraft/client/Minecraft is ever transformed");
    }

    /**
     * The deploy-#109-incident standing rule (see {@code LegacyHudPatcherTest}): patch the REAL
     * {@code Minecraft.class} bytes and force the JVM to actually link (verify) the result, so a
     * VerifyError in either edit fails the gate immediately instead of only surfacing live.
     */
    @Test
    void thePatchedRealMinecraftClassPassesRealJvmVerification() throws Exception {
        byte[] realMc = readRealClassBytes(MC_INTERNAL_NAME);
        byte[] transformed = new LegacyAttackPatcher()
                .transform(null, MC_INTERNAL_NAME, null, null, realMc);
        assertNotNull(transformed, "26.2 is expected to still have both startAttack()Z and continueAttack(Z)V");
        assertNotSame(realMc, transformed);

        ClassLoader loader = new FixtureClassLoader(
                LegacyAttackPatcherTest.class.getClassLoader(), MC_BINARY_NAME, transformed);
        Class.forName(MC_BINARY_NAME, false, loader);
    }

    private static byte[] readRealClassBytes(String internalName) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(CLIENT_JAR), "client.jar not present: " + CLIENT_JAR);
        try (ZipFile zip = new ZipFile(CLIENT_JAR.toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            assertNotNull(entry, "no such class in client.jar: " + internalName);
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    // ------------------------------------------------------------ fixture plumbing

    private static Method startAttackMethod;
    private static Method continueAttackMethod;

    private static Object newFixtureInstance() throws Exception {
        byte[] original = buildFixtureMinecraft();
        byte[] transformed = new LegacyAttackPatcher()
                .transform(null, MC_INTERNAL_NAME, null, null, original);
        assertNotSame(original, transformed, "the fixture must actually get patched");
        Class<?> mc = new FixtureClassLoader(LegacyAttackPatcherTest.class.getClassLoader(),
                MC_BINARY_NAME, transformed)
                .loadClass(MC_BINARY_NAME);
        startAttackMethod = mc.getDeclaredMethod("startAttack");
        startAttackMethod.setAccessible(true);
        continueAttackMethod = mc.getDeclaredMethod("continueAttack", boolean.class);
        continueAttackMethod.setAccessible(true);
        return mc.getDeclaredConstructor().newInstance();
    }

    private static boolean startAttack(Object mc) throws Exception {
        return (Boolean) startAttackMethod.invoke(mc);
    }

    private static void continueAttack(Object mc, boolean value) throws Exception {
        continueAttackMethod.invoke(mc, value);
    }

    /**
     * A bare stand-in for {@code net.minecraft.client.Minecraft} carrying real INSTANCE methods
     * {@code boolean startAttack()} and {@code void continueAttack(boolean)}, matching the real
     * class exactly (the inserted guards touch local variable slots, so static vs. instance and the
     * exact parameter slot layout both matter). Built with {@code COMPUTE_FRAMES | COMPUTE_MAXS} -
     * safe here because this is test setup code, not the live transformer (see
     * {@link LegacyAttackPatcher}'s own javadoc for why it still uses {@code COMPUTE_FRAMES} too,
     * just inside a real {@code ClassFileTransformer.transform()} call).
     */
    private static byte[] buildFixtureMinecraft() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, MC_INTERNAL_NAME, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor start = cw.visitMethod(Opcodes.ACC_PUBLIC, "startAttack", "()Z", null, null);
        start.visitCode();
        start.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/umb/hostagent/LegacyAttackPatcherTest$Recorder", "markStartAttack", "()V", false);
        start.visitInsn(Opcodes.ICONST_0);
        start.visitInsn(Opcodes.IRETURN);
        start.visitMaxs(0, 0);
        start.visitEnd();

        MethodVisitor cont = cw.visitMethod(Opcodes.ACC_PUBLIC, "continueAttack", "(Z)V", null, null);
        cont.visitCode();
        cont.visitVarInsn(Opcodes.ILOAD, 1);
        cont.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/umb/hostagent/LegacyAttackPatcherTest$Recorder", "recordContinue", "(Z)V", false);
        cont.visitInsn(Opcodes.RETURN);
        cont.visitMaxs(0, 0);
        cont.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines exactly one target name from supplied bytes; everything else (including
     *  {@code dev.umb.hostagent.input.LegacyClientInputHook} and {@code Recorder}) delegates to the
     *  real parent loader, so the injected {@code INVOKESTATIC suppressVanillaAttack} call resolves
     *  to the SAME live hook this test drives via {@link LegacyClientInputHook#setLastForTest}. */
    private static final class FixtureClassLoader extends ClassLoader {
        private final String targetName;
        private final byte[] targetBytes;

        FixtureClassLoader(ClassLoader parent, String targetName, byte[] targetBytes) {
            super(parent);
            this.targetName = targetName;
            this.targetBytes = targetBytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!targetName.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, targetBytes, 0, targetBytes.length);
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
