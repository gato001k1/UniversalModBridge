package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Routes server input and client effects at the verified 26.2 custom-payload listener seams.
 *
 * "plan legacy:key:hbm.key.gunPrimary:-100 ... KeybindPacket pressed=true"), the legacy handler ran
 * without error, and the whole client-&gt;server chain (bytecode-verified against
 * {@code research/mods-hbm/HBM-NTM-1.0.27_X5771.jar}: {@code KeybindPacket$Handler.onMessage} ->
 * {@code HbmKeybindsServer.onPressedServer} -> {@code ItemGunBaseNT.handleKeybind} ->
 * {@code setPrimary} writing the fire flag onto the held stack's own NBT) is architecturally sound
 * {@code net/minecraft/server/network/ServerGamePacketListenerImpl.handleCustomPayload} (and its
 * superclass {@code ServerCommonPacketListenerImpl}'s own override) shows BOTH are a bare
 * {@code return} - unlike every other server-bound handler in the same class (e.g.
 * {@code handleCustomClickAction}), neither calls {@code PacketUtils.ensureRunningOnSameThread}
 * first. That means nothing hops this packet to the main server thread before OUR hook runs: our
 * previously-injected call landed on the raw netty decode thread. Every legacy-side mutation the
 * hook's own dispatch chain makes there (selected-slot sync, and critically the NBT
 * {@code ItemGunBaseNT.handleKeybind} writes onto the held stack) then races the main server tick
 * thread's OWN unsynchronized reads of that exact state (`UmbPlayer.tickInventoryItems` ->
 * {@code Item.onUpdate} reading the SAME stack's NBT each tick) with no happens-before edge between
 * the two threads at all - 1.7.10's own code was never written to tolerate concurrent access, so a
 * write made off-thread is not guaranteed to ever become visible to the tick thread that decides
 * whether to fire.
 *
 * <p>The fix reuses vanilla's OWN established idiom instead of inventing a new one: prepend the
 * exact same {@code PacketUtils.ensureRunningOnSameThread(packet, this, server.packetProcessor())}
 * call {@code handleCustomClickAction} already makes, before our hook. Off the main thread, that
 * call re-schedules this exact method call on the main thread and throws to unwind the current
 * (netty-thread) invocation - so neither our hook nor the original body ever runs there; both run
 * only once vanilla resumes this same call on the main thread. SERVER-side only: the reported
 * symptom is server-authoritative world mutation (a spawned entity), and touching the CLIENT
 * listener without equivalent live evidence is out of scope for this fix.</p>
 *
 * <p>No new branch/jump target is introduced (straight-line instructions only, prepended before the
 * method's original first instruction; {@code ensureRunningOnSameThread}'s own unwind is an
 * exception edge with no local handler here, not a normal control-flow merge), so - unlike the
 * own and stays safe under the existing {@code ClassWriter.COMPUTE_MAXS}-only writer.</p>
 */
public final class LegacyCustomPayloadPatcher implements ClassFileTransformer {

    private static final String SERVER_TARGET = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String CLIENT_TARGET = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String LISTENER_OWNER = "net/minecraft/server/network/ServerCommonPacketListenerImpl";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        boolean server = SERVER_TARGET.equals(name);
        boolean client = CLIENT_TARGET.equals(name);
        if (!server && !client) return null;
        try {
            byte[] patched = patch(bytes, server);
            if (patched != null) AgentLog.loud("PATCHED LegacyCustomPayloadPatcher");
            return patched;
        } catch (Throwable t) {
            AgentLog.error("LegacyCustomPayloadPatcher", t, 3);
        }
        return null;
    }

    /** Public so the unit test can run it against the real {@code client.jar} bytes directly. */
    public static byte[] patch(byte[] original, boolean server) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        String desc = server
                ? "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V"
                : "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V";
        int hits = 0;
        for (MethodNode method : node.methods) {
            if (!"handleCustomPayload".equals(method.name) || !desc.equals(method.desc)) continue;
            InsnList insns = new InsnList();
            if (server) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_OWNER, "server",
                        "Lnet/minecraft/server/MinecraftServer;"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer",
                        "packetProcessor", "()Lnet/minecraft/network/PacketProcessor;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/network/protocol/PacketUtils",
                        "ensureRunningOnSameThread",
                        "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
                                + "Lnet/minecraft/network/PacketProcessor;)V",
                        false));
            }
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/umb/hostagent/Hooks",
                    server ? "serverCustomPayload" : "clientCustomPayload",
                    server ? "(Ljava/lang/Object;Ljava/lang/Object;)V" : "(Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(method.instructions.getFirst(), insns);
            hits++;
        }
        if (hits == 0) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
