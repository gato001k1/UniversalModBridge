package dev.umb.hostagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Suppresses 26.2's own vanilla left-click handling (entity attack AND block breaking) for the
 * tick(s) a legacy item claims the click - see
 * {@link dev.umb.hostagent.input.LegacyClientInputHook#suppressVanillaAttack()} for the (universal,
 * not mod-specific) rule: any tick where the held item is a legacy one and the attack button is
 * down/just-pressed.
 *
 * methods, not one:
 * <pre>
 * while (keyAttack.consumeClick()) { anyHandled |= startAttack(); }   // once, the tick the click is QUEUED
 * ...
 * continueAttack(!anyHandled &amp;&amp; screen()==null &amp;&amp; keyAttack.isDown() &amp;&amp; mouseGrabbed);  // EVERY tick held
 * </pre>
 * Only {@code startAttack()} was patched. That correctly suppresses the very first tick's swing /
 * {@code gameMode.startDestroyBlock}. But {@code consumeClick()} only answers true once per physical
 * press - on every following tick of the same hold, the {@code while} loop never runs {@code
 * startAttack()} again, so {@code anyHandled} is {@code false} and {@code continueAttack(true)} fires
 * instead, calling {@code gameMode.continueDestroyBlock} completely unguarded - which alone is enough
 * to instant-break a block in creative, or finish it in survival over the hold, exactly what was
 * observed. {@code continueAttack} is patched the same way now: when the click is suppressed, its
 * incoming {@code boolean} argument is forced to {@code false} at method entry, which is simply the
 * ALREADY-CORRECT vanilla path for "not attacking this tick" ({@code gameMode.stopDestroyBlock()},
 * cancelling any partial destroy instead of leaving it stuck) - no new behavior invented, just routed
 * into an existing vanilla branch.</p>
 *
 * <p>{@code COMPUTE_FRAMES} (not the {@code EXPAND_FRAMES}/hand-built-{@code F_NEW} approach
 * {@link LegacyHudPatcher}/{@link CreativePagingPatcher} use) has been this class's approach since
 * here merges a new reference type across the inserted branch (an early return / a primitive-int
 * overwrite, nothing pushed on the stack across the join), so {@code getCommonSuperClass} is never
 * actually invoked for either, sidestepping the classloading risk {@code LegacyHudPatcher}'s javadoc
 * warns about. {@link LegacyAttackPatcherTest#thePatchedRealMinecraftClassPassesRealJvmVerification()}
 * still runs the real patched class through a real classloader regardless, per the same incident's
 * standing rule that a VerifyError must never again only surface live.</p>
 */
public final class LegacyAttackPatcher implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/Minecraft";
    private static final String HOOK = "dev/umb/hostagent/input/LegacyClientInputHook";
    private static final String SUPPRESS = "suppressVanillaAttack";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            byte[] patched = patch(bytes);
            if (patched != null) AgentLog.loud("PATCHED LegacyAttackPatcher");
            return patched;
        } catch (Throwable t) {
            AgentLog.error("LegacyAttackPatcher", t, 3);
        }
        return null;
    }

    /** Public so the unit test can run it against the real {@code client.jar} bytes directly. */
    public static byte[] patch(byte[] original) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        boolean patchedAny = false;
        for (MethodNode method : node.methods) {
            if ("startAttack".equals(method.name) && "()Z".equals(method.desc)) {
                // if (suppressVanillaAttack()) return true;
                InsnList guard = new InsnList();
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, SUPPRESS, "()Z", false));
                LabelNode notSuppressed = new LabelNode();
                guard.add(new JumpInsnNode(Opcodes.IFEQ, notSuppressed));
                guard.add(new InsnNode(Opcodes.ICONST_1));
                guard.add(new InsnNode(Opcodes.IRETURN));
                guard.add(notSuppressed);
                method.instructions.insertBefore(method.instructions.getFirst(), guard);
                patchedAny = true;
            } else if ("continueAttack".equals(method.name) && "(Z)V".equals(method.desc)) {
                // if (suppressVanillaAttack()) arg0 = false;  -- routes into the method's own
                // existing "not continuing" branch (gameMode.stopDestroyBlock()) instead of
                // skipping the method outright, so an in-progress vanilla destroy is cancelled
                // rather than left stuck.
                InsnList guard = new InsnList();
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, SUPPRESS, "()Z", false));
                LabelNode notSuppressed = new LabelNode();
                guard.add(new JumpInsnNode(Opcodes.IFEQ, notSuppressed));
                guard.add(new InsnNode(Opcodes.ICONST_0));
                guard.add(new VarInsnNode(Opcodes.ISTORE, 1));
                guard.add(notSuppressed);
                method.instructions.insertBefore(method.instructions.getFirst(), guard);
                patchedAny = true;
            }
        }
        if (!patchedAny) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }
}
