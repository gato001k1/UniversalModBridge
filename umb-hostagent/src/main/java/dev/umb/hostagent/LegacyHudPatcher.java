package dev.umb.hostagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Inserts the universal legacy overlay seam at the 26.2 HUD extraction boundary, and (bug
 * finding #3 from the HBM-guns static analysis: a legacy mod cancelling a vanilla
 * {@code RenderGameOverlayEvent.Pre} element - e.g. HBM's gun HUD replacing CROSSHAIRS with its
 * own - never suppressed the HOST's own native draw of that same element, so both got drawn on
 * top of each other) makes a cancelled Pre suppress the matching host vanilla element for that
 * frame.
 *
 * <p>The seam ({@code Hud.extractRenderState}) runs the legacy overlay dispatch as the method's
 * very FIRST instruction, before any vanilla element - that dispatch is what decides, per
 * {@link Hooks#isElementSuppressed}, which element(s) a legacy mod cancelled THIS frame (see
 * {@link Hooks#renderHud}). Every per-element method below gets one more instruction pair ahead
 * of its own body: "if this frame's element was cancelled, return immediately" - the exact same
 * insert-before-first-instruction idiom the seam call already uses, just conditional. Cheap and
 * allocation-free at runtime on this class's part: {@link Hooks#isElementSuppressed} reads one
 * already-built (almost always empty/shared) {@code Set} reference, no lookup table here.</p>
 *
 * (a genuinely new jump target) while reading the class WITHOUT {@link ClassReader#EXPAND_FRAMES}
 * and supplying only a compressed {@code F_SAME} frame by hand. That corrupts the delta-encoded
 * compressed-frame chain the moment a target method already carries its OWN frame shortly after
 * its own first instruction (real compiled code very often does) - which crashed the live game at
 * startup: {@code VerifyError: Expecting a stackmap frame at branch target 10} in
 * {@code Hud.extractVehicleHealth}. The fix - reading with {@code EXPAND_FRAMES} and supplying a
 * full {@code F_NEW} frame built from the target method's own descriptor - is the exact same
 * pattern {@link CreativePagingPatcher} already uses for the identical shape of edit (see its own
 * javadoc); {@link LegacyHudPatcherTest} now also runs the patched output through a real
 * classloader for exactly this reason (a VerifyError cannot silently pass the gate again).</p>
 *
 * <p>{@code COMPUTE_FRAMES} is deliberately never used here (same reasoning as
 * {@code CreativePagingPatcher}/{@code LegacyAttackPatcher}): it would need to resolve reference
 * types via {@code Class.forName} at agent time, from inside the very
 * {@code ClassFileTransformer.transform()} call defining the class being resolved - a real
 * {@code ClassCircularityError}/deadlock risk this codebase avoids everywhere else too.</p>
 *
 * <p>Mapped 1:1 to a real 26.2 {@code Hud} method that draws ONLY that element and nothing else
 * <pre>
 * CROSSHAIRS  -&gt; extractCrosshair
 * HOTBAR      -&gt; extractItemHotbar
 * HEALTH      -&gt; extractHearts
 * ARMOR       -&gt; extractArmor
 * FOOD        -&gt; extractFood
 * AIR         -&gt; extractAirBubbles
 * HEALTHMOUNT -&gt; extractVehicleHealth
 * BOSSHEALTH  -&gt; extractBossOverlay
 * CHAT        -&gt; extractChat
 * PLAYER_LIST -&gt; extractTabList
 * </pre>
 * <p><b>NOT mapped</b> (documented rather than guessed at, per the brief): {@code EXPERIENCE}
 * and {@code JUMPBAR} have no method of their own in 26.2's {@code Hud} - they are drawn by the
 * shared {@code ContextualBar} interface calls inside {@code extractHotbarAndDecorations}, the
 * SAME method that also draws HOTBAR/HEALTH/HEALTHMOUNT/the selected-item-name TEXT; guarding
 * that whole method would suppress all of those together, not just the one cancelled element.
 * {@code TEXT}, {@code HELMET}, {@code PORTAL} and {@code DEBUG} likewise have no single
 * dedicated method that draws only that element and nothing else. {@code ALL} is Forge's
 * synthetic "every element" marker, not a real vanilla draw to suppress.</p>
 */
public final class LegacyHudPatcher implements ClassFileTransformer {

    /** Real 26.2 {@code Hud} method name -&gt; the Forge {@code ElementType} name it exclusively draws. */
    private static final Map<String, String> ELEMENT_METHODS = new LinkedHashMap<String, String>();
    static {
        ELEMENT_METHODS.put("extractCrosshair", "CROSSHAIRS");
        ELEMENT_METHODS.put("extractItemHotbar", "HOTBAR");
        ELEMENT_METHODS.put("extractHearts", "HEALTH");
        ELEMENT_METHODS.put("extractArmor", "ARMOR");
        ELEMENT_METHODS.put("extractFood", "FOOD");
        ELEMENT_METHODS.put("extractAirBubbles", "AIR");
        ELEMENT_METHODS.put("extractVehicleHealth", "HEALTHMOUNT");
        ELEMENT_METHODS.put("extractBossOverlay", "BOSSHEALTH");
        ELEMENT_METHODS.put("extractChat", "CHAT");
        ELEMENT_METHODS.put("extractTabList", "PLAYER_LIST");
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                             ProtectionDomain domain, byte[] bytes) {
        if (!"net/minecraft/client/gui/Hud".equals(name)) return null;
        try {
            ClassNode node = new ClassNode();
            // frame into the class's existing COMPRESSED frame chain (F_SAME/F_CHOP/F_APPEND,
            // each delta-encoded against the previous table entry) corrupts every later entry the
            // moment a target method already has one of its own past the first instruction - real
            // compiled code frequently does. Expanded in, recompressed by the writer.
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            boolean patchedAny = false;
            int guardedElements = 0;
            for (MethodNode method : node.methods) {
                if ("extractRenderState".equals(method.name)
                        && "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
                                .equals(method.desc)) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    call.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "dev/umb/hostagent/Hooks", "renderHud",
                            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                            false));
                    // No branch, no new frame needed - a straight-line call sequence ahead of the
                    // method's own first instruction.
                    method.instructions.insertBefore(method.instructions.getFirst(), call);
                    patchedAny = true;
                    continue;
                }
                String elementName = ELEMENT_METHODS.get(method.name);
                if (elementName == null) continue;
                method.instructions.insertBefore(method.instructions.getFirst(),
                        suppressGuard(node, method, elementName));
                patchedAny = true;
                guardedElements++;
            }
            if (!patchedAny) return null;
            // COMPUTE_MAXS only, deliberately NOT COMPUTE_FRAMES - see this class's own javadoc.
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            AgentLog.loud("PATCHED LegacyHudPatcher (seam + " + guardedElements + " element guard(s))");
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.error("LegacyHudPatcher", t, 3);
        }
        return null;
    }

    /**
     * {@code if (Hooks.isElementSuppressed("<elementName>")) return;} - works unmodified on a
     * static or instance {@code void} method (plain {@code RETURN}, no receiver/args touched).
     *
     * <p>{@code continueLabel} is a genuinely new jump target the class file must carry an
     * explicit StackMapTable frame for (classfile major version 50+). It is exactly the method's
     * own entry frame: {@code IFEQ} is the only instruction that can reach it, and by the time it
     * fires the guard has already popped its own {@code isElementSuppressed} boolean back off the
     * stack (push 1 via {@code LDC}, push/pop 1 via the {@code INVOKESTATIC} call, pop 1 via
     * {@code IFEQ} itself) and touched no local variable - i.e. {@code this} (if not static) plus
     * every parameter, exactly as they arrive at the method's own first instruction, with an
     * empty operand stack. {@link #entryFrameLocals} builds that list from the method's own
     * descriptor, so this is correct for any target method's signature, not just the ones
     * currently mapped.</p>
     */
    private static InsnList suppressGuard(ClassNode owner, MethodNode method, String elementName) {
        InsnList guard = new InsnList();
        LabelNode continueLabel = new LabelNode();
        guard.add(new LdcInsnNode(elementName));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/umb/hostagent/Hooks", "isElementSuppressed", "(Ljava/lang/String;)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(continueLabel);
        List<Object> locals = entryFrameLocals(owner, method);
        guard.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        return guard;
    }

    /**
     * The verification-frame "locals" list for {@code method}'s own entry point: {@code this}
     * (the owning class's internal name, unless the method is static) followed by each parameter
     * type from {@code method.desc}, in ASM's expanded-frame element format (a {@code String}
     * internal/array-descriptor name for a reference/array type, or one of
     * {@code Opcodes.INTEGER/FLOAT/LONG/DOUBLE} for a primitive - {@code long}/{@code double}
     * correctly occupy a single list entry each; the verifier itself accounts for their extra
     * local-variable-table slot). Built from the descriptor rather than hand-listed per method so
     * this stays correct for any future target method, whatever its real parameter list is.
     */
    private static List<Object> entryFrameLocals(ClassNode owner, MethodNode method) {
        List<Object> locals = new ArrayList<Object>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals.add(owner.name);
        }
        for (Type argType : Type.getArgumentTypes(method.desc)) {
            switch (argType.getSort()) {
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    locals.add(Opcodes.INTEGER);
                    break;
                case Type.FLOAT:
                    locals.add(Opcodes.FLOAT);
                    break;
                case Type.LONG:
                    locals.add(Opcodes.LONG);
                    break;
                case Type.DOUBLE:
                    locals.add(Opcodes.DOUBLE);
                    break;
                case Type.ARRAY:
                    locals.add(argType.getDescriptor());
                    break;
                default:
                    locals.add(argType.getInternalName());
            }
        }
        return locals;
    }
}
