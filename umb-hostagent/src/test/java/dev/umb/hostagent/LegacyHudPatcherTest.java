package dev.umb.hostagent;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RenderGameOverlayEvent.Pre} (e.g. HBM's gun HUD replacing CROSSHAIRS with its own) never
 * suppressed the HOST's own native draw of that element, so both got drawn on top of each other.
 *
 * with {@code VerifyError: Expecting a stackmap frame at branch target 10} in
 * {@code Hud.extractVehicleHealth} - the guard's own {@code F_SAME} frame, hand-supplied while
 * reading the class WITHOUT {@code ClassReader.EXPAND_FRAMES}, corrupted the delta-encoded
 * compressed-frame chain of a method that already carried a frame of its own shortly after its
 * first instruction (real compiled code frequently does). Every fixture method built here
 * therefore ALWAYS has that exact shape (an internal branch, and thus its own pre-existing frame,
 * a couple of instructions in) - a fixture with no internal branch at all (the pre-incident
 * version of this file) would not have caught the regression either.</p>
 *
 * <p>Builds a tiny synthetic stand-in for the real {@code net.minecraft.client.gui.Hud} class
 * (same fixture idiom as {@code UmbShimTransformerExtraNoopTest}: a bare class under the exact
 * internal name {@link LegacyHudPatcher} matches on, with methods named exactly like the real
 * per-element ones it targets), runs it through the REAL {@link LegacyHudPatcher}, loads the
 * transformed bytecode in an isolated classloader, and PROVES the runtime effect end to end: with
 * the element suppressed ({@link Hooks#isElementSuppressed}), the fixture's own draw body never
 * runs; with nothing suppressed, it runs exactly as before. This is deliberately a behavioral
 * test, not just a bytecode-shape one.</p>
 *
 * <p>{@link #thePatchedRealHudClassPassesRealJvmVerification()} goes further still: it patches
 * the ACTUAL {@code Hud.class} bytes from {@code research/jars/26.2/client.jar} and defines the
 * result in a real classloader with {@code Class.forName(..., true, ...)} - forcing the exact
 * same linking/verification pass the live JVM performs when the game itself loads {@code Hud} -
 * so a VerifyError in any real method this patcher touches, not just the small hand-built
 * fixtures above, fails the gate immediately instead of only surfacing live.</p>
 */
class LegacyHudPatcherTest {

    private static final Path CLIENT_JAR = Paths.get("research/jars/26.2/client.jar");
    private static final String HUD_BINARY_NAME = "net.minecraft.client.gui.Hud";
    private static final String HUD_INTERNAL_NAME = "net/minecraft/client/gui/Hud";

    @AfterEach
    void resetSuppression() {
        Hooks.setSuppressedElementsForTest(null);
        Recorder.ran = false;
    }

    /** What the fixture's method body does once it runs past any inserted guard. */
    public static final class Recorder {
        static boolean ran;
        public static void mark() {
            ran = true;
        }
    }

    private static Set<String> setOf(String value) {
        Set<String> s = new LinkedHashSet<String>();
        s.add(value);
        return s;
    }

    @Test
    void aCancelledElementSkipsTheMatchingHudMethodBody() throws Exception {
        Method extractCrosshair = loadTransformedMethod("extractCrosshair");
        Hooks.setSuppressedElementsForTest(setOf("CROSSHAIRS"));

        extractCrosshair.invoke(null, new Object());

        assertFalse(Recorder.ran, "the vanilla draw must not run once CROSSHAIRS is suppressed");
    }

    @Test
    void aDifferentElementBeingSuppressedDoesNotAffectThisOne() throws Exception {
        Method extractCrosshair = loadTransformedMethod("extractCrosshair");
        Hooks.setSuppressedElementsForTest(setOf("HOTBAR"));

        extractCrosshair.invoke(null, new Object());

        assertTrue(Recorder.ran, "an element nobody cancelled must still draw");
    }

    @Test
    void nothingSuppressedRunsNormally() throws Exception {
        Method extractCrosshair = loadTransformedMethod("extractCrosshair");
        Hooks.setSuppressedElementsForTest(Collections.<String>emptySet());

        extractCrosshair.invoke(null, new Object());

        assertTrue(Recorder.ran);
    }

    @Test
    void theFixturesOwnInternalBranchStillWorksRegardlessOfSuppression() throws Exception {
        // The fixture's body is `if (arg == null) return; else Recorder.mark();` - proves the
        // guard didn't just avoid crashing, the method's OWN pre-existing control flow (and its
        // own frame) still behaves correctly on both sides of it.
        Method extractCrosshair = loadTransformedMethod("extractCrosshair");
        Hooks.setSuppressedElementsForTest(Collections.<String>emptySet());

        extractCrosshair.invoke(null, new Object());
        assertTrue(Recorder.ran, "a non-null arg must still reach the fixture's own mark() call");

        Recorder.ran = false;
        extractCrosshair.invoke(null, new Object[] {null});
        assertFalse(Recorder.ran, "a null arg must still take the fixture's own early return");
    }

    @Test
    void everyDocumentedElementMethodIsGuarded() throws Exception {
        String[] methods = {"extractCrosshair", "extractItemHotbar", "extractHearts", "extractArmor",
                "extractFood", "extractAirBubbles", "extractVehicleHealth", "extractBossOverlay",
                "extractChat", "extractTabList"};
        String[] elements = {"CROSSHAIRS", "HOTBAR", "HEALTH", "ARMOR", "FOOD", "AIR",
                "HEALTHMOUNT", "BOSSHEALTH", "CHAT", "PLAYER_LIST"};
        for (int i = 0; i < methods.length; i++) {
            Recorder.ran = false;
            Method m = loadTransformedMethod(methods[i]);
            Hooks.setSuppressedElementsForTest(setOf(elements[i]));
            m.invoke(null, new Object());
            assertFalse(Recorder.ran, methods[i] + " must be suppressed by its ElementType " + elements[i]);
        }
    }

    @Test
    void anUnmappedMethodNameIsLeftCompletelyUntouched() throws Exception {
        byte[] original = buildFixtureHud("extractSomethingNotMapped");
        byte[] transformed = new LegacyHudPatcher()
                .transform(null, HUD_INTERNAL_NAME, null, null, original);
        // No recognized method (not extractRenderState, not in the element map) anywhere in this
        // fixture - the whole transform must be a no-op, exactly like an unrelated class.
        assertNull(transformed, "a class with no patchable method must pass through untouched");
    }

    @Test
    void aNonHudClassIsNeverTouched() throws Exception {
        byte[] original = buildFixtureHud("extractCrosshair");
        byte[] transformed = new LegacyHudPatcher()
                .transform(null, "net/minecraft/client/gui/SomeOtherClass", null, null, original);
        assertNull(transformed, "only net/minecraft/client/gui/Hud is ever transformed");
    }

    /**
     * forces the JVM to actually LINK (verify) the result, the same mandatory step the live game
     * performs before ever using the class. {@code initialize=false} still runs full verification
     * (verification is part of linking, before initialization) while skipping {@code <clinit>} -
     * no live {@code Minecraft} instance needed just to load the class. A VerifyError here fails
     * this test with the exact same error the crash report showed, instead of only surfacing in a
     * live game.
     */
    @Test
    void thePatchedRealHudClassPassesRealJvmVerification() throws Exception {
        byte[] realHud = readRealClassBytes(HUD_INTERNAL_NAME);
        byte[] transformed = new LegacyHudPatcher()
                .transform(null, HUD_INTERNAL_NAME, null, null, realHud);
        assertNotNull(transformed, "the real Hud class is expected to still have every mapped method");
        assertNotSame(realHud, transformed);

        ClassLoader loader = new FixtureClassLoader(
                LegacyHudPatcherTest.class.getClassLoader(), HUD_BINARY_NAME, transformed);
        Class.forName(HUD_BINARY_NAME, false, loader);
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

    /** Runs the fixture through the real patcher, loads it isolated, returns the named method. */
    private static Method loadTransformedMethod(String methodName) throws Exception {
        byte[] original = buildFixtureHud(methodName);
        byte[] transformed = new LegacyHudPatcher()
                .transform(null, HUD_INTERNAL_NAME, null, null, original);
        assertNotSame(original, transformed, "the fixture method must actually get patched");
        Class<?> hud = new FixtureClassLoader(LegacyHudPatcherTest.class.getClassLoader(),
                HUD_BINARY_NAME, transformed)
                .loadClass(HUD_BINARY_NAME);
        Method m = hud.getDeclaredMethod(methodName, Object.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * A bare stand-in for {@code net.minecraft.client.gui.Hud} carrying one static
     * {@code void(Object)} method named {@code methodName}, whose body is
     * {@code if (arg == null) return; Recorder.mark();} - a genuine internal branch (and hence
     * its own pre-existing compressed frame at the label), reproducing the exact shape that broke
     * because this is plain test setup code, not a live {@code ClassFileTransformer} running
     * during class definition (that distinction is exactly why the production patcher itself
     * never uses {@code COMPUTE_FRAMES} - see {@link LegacyHudPatcher}'s javadoc).
     */
    private static byte[] buildFixtureHud(String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, HUD_INTERNAL_NAME, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName,
                "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        Label skip = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNULL, skip);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/umb/hostagent/LegacyHudPatcherTest$Recorder",
                "mark", "()V", false);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines exactly one target name from supplied bytes; everything else (including
     *  {@code dev.umb.hostagent.Hooks}, {@code Recorder}, and every real {@code net.minecraft.*}
     *  type {@code Hud} itself references) delegates to the real parent loader, so the injected
     *  {@code INVOKESTATIC Hooks.isElementSuppressed} call resolves to the SAME live
     *  {@link Hooks} class this test drives via {@link Hooks#setSuppressedElementsForTest}. */
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
