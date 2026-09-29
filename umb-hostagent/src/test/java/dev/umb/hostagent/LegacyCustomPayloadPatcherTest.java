package dev.umb.hostagent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * server handler (log showed "KeybindPacket pressed=true"), yet no bullet ever spawned. See
 * {@code ServerGamePacketListenerImpl.handleCustomPayload} (unlike every sibling handler in the
 * same class) never hops to the main server thread - our own previously-injected hook therefore ran
 * on the raw netty decode thread, racing the main tick thread's unsynchronized reads of the exact
 * legacy state (a held stack's NBT) the packet handler just wrote. This proves the fix inserts
 * vanilla's own {@code PacketUtils.ensureRunningOnSameThread} call BEFORE our hook (server only),
 * and that the patched real classes still pass real JVM verification.
 */
class LegacyCustomPayloadPatcherTest {

    private static final Path CLIENT_JAR = Paths.get("research/jars/26.2/client.jar");
    private static final String SERVER_INTERNAL = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String SERVER_BINARY = "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String CLIENT_INTERNAL = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String CLIENT_BINARY = "net.minecraft.client.multiplayer.ClientPacketListener";

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

    private static MethodNode handleCustomPayload(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if ("handleCustomPayload".equals(m.name)) return m;
        }
        return null;
    }

    private static int indexOfStaticCall(MethodNode method, String owner, String name) {
        int i = 0;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext(), i++) {
            if (in instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void serverHandlerGetsTheVanillaThreadHopPrependedBeforeOurHook() throws Exception {
        byte[] before = readRealClassBytes(SERVER_INTERNAL);
        byte[] after = LegacyCustomPayloadPatcher.patch(before, true);
        assertNotNull(after, "26.2 is expected to still have handleCustomPayload");
        assertNotSame(before, after);

        MethodNode patched = handleCustomPayload(after);
        int hopIndex = indexOfStaticCall(patched, "net/minecraft/network/protocol/PacketUtils",
                "ensureRunningOnSameThread");
        int hookIndex = indexOfStaticCall(patched, "dev/umb/hostagent/Hooks", "serverCustomPayload");
        assertTrue(hopIndex >= 0, "the vanilla main-thread hop must be inserted");
        assertTrue(hookIndex >= 0, "our own hook must still be inserted");
        assertTrue(hopIndex < hookIndex,
                "the thread hop must run BEFORE our hook, or our hook still races the tick thread");

        // The hop reads `this.server.packetProcessor()` - confirm the field/owner match vanilla's
        // own sibling handler (handleCustomClickAction), not a guessed name.
        boolean sawServerField = false;
        for (AbstractInsnNode in = patched.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof FieldInsnNode f && "server".equals(f.name)
                    && "net/minecraft/server/network/ServerCommonPacketListenerImpl".equals(f.owner)) {
                sawServerField = true;
            }
        }
        assertTrue(sawServerField, "expected a GETFIELD of ServerCommonPacketListenerImpl.server");
    }

    @Test
    void clientHandlerHasNoServerOnlyThreadHop() throws Exception {
        byte[] before = readRealClassBytes(CLIENT_INTERNAL);
        byte[] after = LegacyCustomPayloadPatcher.patch(before, false);
        assertNotNull(after, "26.2 is expected to still have handleCustomPayload");

        MethodNode patched = handleCustomPayload(after);
        int hopIndex = indexOfStaticCall(patched, "net/minecraft/network/protocol/PacketUtils",
                "ensureRunningOnSameThread");
        int hookIndex = indexOfStaticCall(patched, "dev/umb/hostagent/Hooks", "clientCustomPayload");
        assertEquals(-1, hopIndex, "the server-only thread hop must not be inserted on the client listener");
        assertTrue(hookIndex >= 0, "our own client hook must still be inserted");
    }

    @Test
    void aNonTargetClassIsNeverTouched() {
        LegacyCustomPayloadPatcher patcher = new LegacyCustomPayloadPatcher();
        assertNull(patcher.transform(null, "net/minecraft/client/gui/SomeOtherClass", null, null,
                new byte[] {1, 2, 3}));
    }

    /**
     * The deploy-#109-incident standing rule: patch the REAL class bytes and force the JVM to
     * actually link (verify) the result. This insertion adds no new branch/jump target (see this
     * here; this test exists so a future edit to this class cannot silently reintroduce that risk
     * without the gate catching it.
     */
    @Test
    void thePatchedRealServerListenerClassPassesRealJvmVerification() throws Exception {
        byte[] before = readRealClassBytes(SERVER_INTERNAL);
        byte[] after = LegacyCustomPayloadPatcher.patch(before, true);
        assertNotNull(after);
        ClassLoader loader = new FixtureClassLoader(
                LegacyCustomPayloadPatcherTest.class.getClassLoader(), SERVER_BINARY, after);
        Class.forName(SERVER_BINARY, false, loader);
    }

    @Test
    void thePatchedRealClientListenerClassPassesRealJvmVerification() throws Exception {
        byte[] before = readRealClassBytes(CLIENT_INTERNAL);
        byte[] after = LegacyCustomPayloadPatcher.patch(before, false);
        assertNotNull(after);
        ClassLoader loader = new FixtureClassLoader(
                LegacyCustomPayloadPatcherTest.class.getClassLoader(), CLIENT_BINARY, after);
        Class.forName(CLIENT_BINARY, false, loader);
    }

    /** Defines exactly one target name from supplied bytes; everything else delegates to the real
     *  parent loader, so every OTHER real {@code net.minecraft.*}/{@code dev.umb.hostagent.*} type
     *  referenced resolves normally (same idiom as {@code LegacyHudPatcherTest}/
     *  {@code LegacyAttackPatcherTest}). */
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
