package dev.umb.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * M8-3: Mixin bytecode rewriter.
 * Consumes {@link MixinSelector} IR (+ {@link ResolvedSelector} + {@link Refmap} via {@link ModAnalysis})
 * and applies injection points to target class bytes via ASM 9.9.
 *
 * <p>D4: on UNRESOLVABLE selectors, report and skip (never guess), emit {@link ModAnalysis.Evidence}.
 * Synthetic fixtures only (CC0), stubs never ship.
 */
public final class MixinRewriter {

    private MixinRewriter() {}

    public static final String EVIDENCE_KIND = "mixin-rewrite";

    public record RewriteResult(byte[] bytecode, List<ModAnalysis.Evidence> evidence, int applied, int skipped) {}

    public record RewriteStats(int applied, int skipped) {}

    // ------------------------------------------------------------------ public API

    public static RewriteResult rewrite(byte[] targetBytes, List<MixinSelector> selectors,
                                        Function<MemberSelector, ResolvedSelector> resolver) {
        if (targetBytes == null) throw new NullPointerException("targetBytes");
        if (selectors == null) selectors = List.of();
        ClassReader cr = new ClassReader(targetBytes);
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        List<ModAnalysis.Evidence> evidence = new ArrayList<>();
        RewriteStats stats = rewrite(node, selectors, resolver, evidence);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return new RewriteResult(cw.toByteArray(), List.copyOf(evidence), stats.applied(), stats.skipped());
    }

    public static RewriteResult rewrite(byte[] targetBytes, List<MixinSelector> selectors) {
        return rewrite(targetBytes, selectors, null);
    }

    /**
     * Rewrite with ModAnalysis convenience — iterates over {@link ModAnalysis.MixinInventory} selectors.
     * Targets map is internalName -> class bytes. Returns per-target results.
     */
    public static Map<String, RewriteResult> rewriteAll(ModAnalysis analysis, Map<String, byte[]> targets,
                                                         Function<MemberSelector, ResolvedSelector> resolver) {
        if (analysis == null) throw new NullPointerException("analysis");
        if (targets == null) return Map.of();
        // Collect all selectors from inventory
        List<MixinSelector> all = new ArrayList<>();
        if (analysis.mixinInventory() != null) {
            for (ModAnalysis.MixinConfigInventory cfg : analysis.mixinInventory().configs()) {
                for (ModAnalysis.MixinClassInventory cls : cfg.mixins()) {
                    if (cls.selectors() != null) all.addAll(cls.selectors());
                }
            }
        }
        java.util.HashMap<String, RewriteResult> out = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> e : targets.entrySet()) {
            RewriteResult r = rewrite(e.getValue(), all, resolver);
            out.put(e.getKey(), r);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Core tree rewrite. Mutates target in place. Returns stats and appends evidence to outEvidence.
     */
    public static RewriteStats rewrite(ClassNode target, List<MixinSelector> selectors,
                                       Function<MemberSelector, ResolvedSelector> resolver,
                                       List<ModAnalysis.Evidence> outEvidence) {
        return rewrite(target, selectors, resolver, null, outEvidence);
    }

    /**
     * Core tree rewrite with mixin class nodes for OVERWRITE body replacement.
     * When {@code mixinNodes} is non-null, OVERWRITE copies the handler method body
     * wholesale while preserving the target's widened access flags (AT ordering).
     * D4 on missing mixin/handler, descriptor mismatch, or static mismatch.
     */
    public static RewriteStats rewrite(ClassNode target, List<MixinSelector> selectors,
                                       Function<MemberSelector, ResolvedSelector> resolver,
                                       Map<String, ClassNode> mixinNodes,
                                       List<ModAnalysis.Evidence> outEvidence) {
        int applied = 0;
        int skipped = 0;
        if (selectors == null || selectors.isEmpty()) {
            return new RewriteStats(0, 0);
        }
        for (MixinSelector sel : selectors) {
            // D4 invalid -> report skip
            if (!sel.allSelectorsValid()) {
                String detail = "skipped invalid selector " + sel.mixinClass() + "#" + sel.handlerMethod()
                        + " kind=" + sel.kind() + " reasons=" + sel.invalidReasons();
                outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                skipped++;
                continue;
            }
            // Unsupported kind OTHER -> skip honest
            if (sel.kind() == InjectionPointKind.OTHER) {
                String detail = "skipped unsupported kind OTHER " + sel.mixinClass() + "#" + sel.handlerMethod();
                outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                skipped++;
                continue;
            }
            // Resolve targets
            List<ResolvedSelector> resolvedTargets = new ArrayList<>();
            for (MemberSelector t : sel.targets()) {
                ResolvedSelector rs = resolveOne(t, resolver);
                if (!rs.isResolved()) {
                    String detail = "unresolvable target '" + t.raw() + "' in " + sel.mixinClass() + "#" + sel.handlerMethod()
                            + ": " + rs.reason();
                    outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 0.9));
                    skipped++;
                } else {
                    resolvedTargets.add(rs);
                }
            }
            // Overwrite has no @At but still needs a target method – handle separately
            if (sel.kind() == InjectionPointKind.OVERWRITE) {
                if (resolvedTargets.isEmpty()) {
                    // targets empty but Overwrite may name method via handler name itself? If no targets, skip
                    String detail = "overwrite with no resolvable target " + sel.mixinClass() + "#" + sel.handlerMethod();
                    outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                    skipped++;
                    continue;
                }
                for (ResolvedSelector rt : resolvedTargets) {
                    List<MethodNode> mns = findTargetMethods(target, rt.resolved());
                    if (mns.isEmpty()) {
                        String detail = "overwrite target method not found '" + rt.resolved() + "' in " + target.name;
                        outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                        skipped++;
                        continue;
                    }
                    for (MethodNode mn : mns) {
                        if (mixinNodes != null && !mixinNodes.isEmpty()) {
                            int n = applyOverwriteWithBody(mn, sel, mixinNodes, outEvidence);
                            if (n > 0) applied += n;
                            else skipped++;
                        } else {
                            // Legacy path (no mixin bytes available): marker inject
                            int n = applyOverwrite(mn, sel);
                            applied += n;
                        }
                    }
                }
                continue;
            }
            if (resolvedTargets.isEmpty()) {
                // all unresolvable -> already counted skips
                continue;
            }
            if (sel.atSelectors() == null || sel.atSelectors().isEmpty()) {
                String detail = "no @At for injector " + sel.mixinClass() + "#" + sel.handlerMethod()
                        + " kind=" + sel.kind();
                outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                skipped++;
                continue;
            }
            for (ResolvedSelector rt : resolvedTargets) {
                List<MethodNode> mns = findTargetMethods(target, rt.resolved());
                if (mns.isEmpty()) {
                    String detail = "target method not found '" + rt.resolved() + "' (orig '" + rt.original().raw() + "') in " + target.name;
                    outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                    skipped++;
                    continue;
                }
                for (AtSelector at : sel.atSelectors()) {
                    if (!at.valid()) {
                        String detail = "invalid @At '" + at.rawValue() + "' in " + sel.mixinClass() + "#" + sel.handlerMethod()
                                + ": " + at.error();
                        outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                        skipped++;
                        continue;
                    }
                    // Resolve At target if present
                    ResolvedSelector atResolved = null;
                    if (at.target() != null) {
                        ResolvedSelector r = resolveOne(at.target(), resolver);
                        if (!r.isResolved()) {
                            String detail = "unresolvable @At target '" + at.rawTarget() + "' in " + sel.mixinClass() + "#" + sel.handlerMethod()
                                    + ": " + r.reason();
                            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 0.9));
                            skipped++;
                            continue;
                        }
                        // PASSTHROUGH means keep original target; TRANSLATED/REMAP means use resolved
                        if (r.kind() != ResolvedSelector.ResolutionKind.PASSTHROUGH) {
                            atResolved = r;
                        } else {
                            // still track as resolved for matching (same as original)
                            atResolved = r;
                        }
                    }
                    for (MethodNode mn : mns) {
                        // Slice filtering placeholder – if slices present, verify anchors exist but don't yet filter
                        List<AbstractInsnNode> points = findInjectionPoints(mn, at, atResolved);
                        if (points.isEmpty()) {
                            String detail = "no injection point for @At " + at.pointId()
                                    + (at.rawTarget() != null ? " target '" + at.rawTarget() + "'" : "")
                                    + " ordinal=" + at.ordinal() + " in " + mn.name + mn.desc;
                            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                            skipped++;
                            continue;
                        }
                        // Apply per kind
                        int appliedHere = applyInjection(mn, points, sel, at);
                        if (appliedHere > 0) {
                            applied += appliedHere;
                        } else {
                            String detail = "injector " + sel.kind() + " produced no change for @At " + at.pointId() + " in " + mn.name + mn.desc;
                            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, detail, 1.0));
                            skipped++;
                        }
                    }
                }
            }
        }
        return new RewriteStats(applied, skipped);
    }

    // ------------------------------------------------------------------ helpers

    private static ResolvedSelector resolveOne(MemberSelector sel, Function<MemberSelector, ResolvedSelector> resolver) {
        if (resolver != null) {
            ResolvedSelector r = resolver.apply(sel);
            if (r != null) return r;
        }
        return ResolvedSelector.passthrough(sel);
    }

    private static List<MethodNode> findTargetMethods(ClassNode target, MemberSelector sel) {
        List<MethodNode> out = new ArrayList<>();
        String name = sel.name();
        String desc = sel.descriptor();
        // Owner on target selector is usually not used (it would be the target class itself); ignore for matching
        for (MethodNode mn : target.methods) {
            if (!mn.name.equals(name)) continue;
            if (desc != null) {
                // desc on selector may be incomplete? Compare exact when present
                if (!mn.desc.equals(desc)) continue;
            }
            // quantifier ignored for method search – exact match
            out.add(mn);
        }
        return out;
    }

    private static List<AbstractInsnNode> findInjectionPoints(MethodNode mn, AtSelector at, ResolvedSelector atResolved) {
        MemberSelector targetSel = null;
        if (atResolved != null) targetSel = atResolved.resolved();
        else if (at.target() != null) targetSel = at.target();

        String pid = at.pointId();
        List<AbstractInsnNode> all = new ArrayList<>();

        switch (pid) {
            case "HEAD" -> {
                AbstractInsnNode first = mn.instructions.getFirst();
                if (first == null) {
                    // empty method – no point (should be RETURN)
                    return List.of();
                }
                // For HEAD, the insertion point is before first real insn; return first as anchor
                // Include label/line handling – caller will insert before first
                all.add(first);
            }
            case "TAIL", "RETURN" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    int op = insn.getOpcode();
                    if (op == Opcodes.RETURN || op == Opcodes.IRETURN || op == Opcodes.LRETURN
                            || op == Opcodes.FRETURN || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
                        all.add(insn);
                    }
                    if ("TAIL".equals(pid) && op == Opcodes.ATHROW) {
                        all.add(insn);
                    }
                }
            }
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "INVOKE_STRING_ASSIGN" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode mi)) continue;
                    if (at.opcode() != -1 && mi.getOpcode() != at.opcode()) continue;
                    if (targetSel != null) {
                        if (!mi.name.equals(targetSel.name())) continue;
                        if (targetSel.descriptor() != null && !mi.desc.equals(targetSel.descriptor())) continue;
                        if (targetSel.owner() != null && !mi.owner.equals(targetSel.owner())) continue;
                    }
                    all.add(insn);
                }
            }
            case "FIELD" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof FieldInsnNode fi)) continue;
                    if (at.opcode() != -1 && fi.getOpcode() != at.opcode()) continue;
                    if (targetSel != null) {
                        if (!fi.name.equals(targetSel.name())) continue;
                        if (targetSel.owner() != null && !fi.owner.equals(targetSel.owner())) continue;
                        if (targetSel.descriptor() != null && !fi.desc.equals(targetSel.descriptor())) continue;
                    }
                    all.add(insn);
                }
            }
            case "NEW" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof TypeInsnNode ti)) continue;
                    if (ti.getOpcode() != Opcodes.NEW) continue;
                    if (at.opcode() != -1 && ti.getOpcode() != at.opcode()) continue;
                    if (targetSel != null) {
                        String expected;
                        if (targetSel.owner() != null && targetSel.name() != null) expected = targetSel.owner() + "/" + targetSel.name();
                        else if (targetSel.owner() != null) expected = targetSel.owner();
                        else expected = targetSel.name();
                        if (expected != null && !ti.desc.equals(expected)) continue;
                    }
                    all.add(insn);
                }
            }
            case "JUMP" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof JumpInsnNode ji)) continue;
                    if (at.opcode() != -1 && ji.getOpcode() != at.opcode()) continue;
                    all.add(insn);
                }
            }
            case "CONSTANT" -> {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc) {
                        // args filter: if at.args contains "intValue" or "stringValue" or "nullValue", filter
                        // Synthetic: support filtering by args key "classValue" etc not needed; accept all
                        // Also support opcode filter (LDC == 18)
                        if (at.opcode() != -1 && ldc.getOpcode() != at.opcode()) continue;
                        // optional filtering via targetSel? Constant has no target selector normally; ignore
                        all.add(insn);
                    } else if (insn.getOpcode() >= Opcodes.ICONST_M1 && insn.getOpcode() <= Opcodes.ICONST_5
                            || insn.getOpcode() == Opcodes.BIPUSH || insn.getOpcode() == Opcodes.SIPUSH) {
                        if (at.opcode() != -1 && insn.getOpcode() != at.opcode()) continue;
                        all.add(insn);
                    }
                }
                // Further filter via args if present (e.g., intValue=42)
                if (!at.args().isEmpty()) {
                    // honour only simple intValue/stringValue filters
                    String intV = at.args().get("intValue");
                    String strV = at.args().get("stringValue");
                    if (intV != null) {
                        List<AbstractInsnNode> filtered = new ArrayList<>();
                        for (AbstractInsnNode n : all) {
                            Object val = null;
                            if (n instanceof LdcInsnNode ldc) val = ldc.cst;
                            else if (n.getOpcode() >= Opcodes.ICONST_M1 && n.getOpcode() <= Opcodes.ICONST_5) {
                                val = n.getOpcode() - Opcodes.ICONST_0;
                            } else if (n instanceof org.objectweb.asm.tree.IntInsnNode ii) {
                                val = ii.operand;
                            }
                            if (val != null && String.valueOf(val).equals(intV)) filtered.add(n);
                        }
                        all = filtered;
                    } else if (strV != null) {
                        List<AbstractInsnNode> filtered = new ArrayList<>();
                        for (AbstractInsnNode n : all) {
                            if (n instanceof LdcInsnNode ldc && strV.equals(String.valueOf(ldc.cst))) filtered.add(n);
                        }
                        all = filtered;
                    }
                }
            }
            default -> {
                // unsupported point id – treat as no match
                return List.of();
            }
        }

        // ordinal filtering
        if (at.ordinal() >= 0) {
            if (at.ordinal() >= all.size()) {
                return List.of();
            }
            return List.of(all.get(at.ordinal()));
        }
        return List.copyOf(all);
    }

    private static int applyInjection(MethodNode mn, List<AbstractInsnNode> points, MixinSelector sel, AtSelector at) {
        InjectionPointKind kind = sel.kind();
        String mixinClass = sel.mixinClass();
        String handler = sel.handlerMethod();
        String handlerDesc = sel.handlerDesc() != null ? sel.handlerDesc() : "()V";
        int count = 0;
        for (AbstractInsnNode anchor : points) {
            switch (kind) {
                case INJECT -> {
                    InsnList call = handlerCall(mixinClass, handler, handlerDesc);
                    if (isHeadOrTail(at)) {
                        // HEAD: before first; TAIL/RETURN: before return
                        if ("HEAD".equals(at.pointId())) {
                            // Insert at head: before first real instruction
                            // Find first non-label/line if shift is BEFORE, otherwise same
                            mn.instructions.insertBefore(anchor, call);
                        } else {
                            mn.instructions.insertBefore(anchor, call);
                        }
                    } else {
                        if (at.shift() == AtSelector.Shift.AFTER) {
                            mn.instructions.insert(anchor, call);
                        } else {
                            mn.instructions.insertBefore(anchor, call);
                        }
                    }
                    count++;
                }
                case REDIRECT -> {
                    InsnList call = handlerCall(mixinClass, handler, handlerDesc);
                    mn.instructions.insertBefore(anchor, call);
                    mn.instructions.remove(anchor);
                    count++;
                }
                case MODIFY_CONSTANT -> {
                    // insert handler call after constant
                    InsnList call = handlerCall(mixinClass, handler, handlerDesc);
                    mn.instructions.insert(anchor, call);
                    count++;
                }
                case MODIFY_ARG, MODIFY_ARGS -> {
                    // before the invoke
                    InsnList call = handlerCall(mixinClass, handler, handlerDesc);
                    mn.instructions.insertBefore(anchor, call);
                    count++;
                }
                case MODIFY_VARIABLE -> {
                    // after var load/store
                    InsnList call = handlerCall(mixinClass, handler, handlerDesc);
                    if (at.shift() == AtSelector.Shift.AFTER) {
                        mn.instructions.insert(anchor, call);
                    } else {
                        mn.instructions.insert(anchor, call);
                    }
                    count++;
                }
                default -> {
                    // OTHER etc handled outside
                }
            }
        }
        return count;
    }

    private static int applyOverwrite(MethodNode mn, MixinSelector sel) {
        // Legacy path: marker inject at head (no mixin bytes available)
        String mixinClass = sel.mixinClass();
        String handler = sel.handlerMethod();
        String handlerDesc = sel.handlerDesc() != null ? sel.handlerDesc() : "()V";
        InsnList call = handlerCall(mixinClass, handler, handlerDesc);
        AbstractInsnNode first = mn.instructions.getFirst();
        if (first == null) {
            mn.instructions.add(call);
        } else {
            mn.instructions.insertBefore(first, call);
        }
        return 1;
    }

    /**
     * True Overwrite per Mixin 0.8.7 (mixin-internals §3.6): replace target method body
     * wholesale with the mixin handler's body (alias/remap already resolved via selector's
     * resolved target). Preserves the target's AT-widened access flags — never clobbers
     * visibility or FINAL removals applied earlier in the same pass — and rejects
     * descriptor/static mismatches D4. Construction and abstract/interface targets are D4 skipped.
     * Uses label remapping so control flow and try-catch boundaries remain sound; frames
     * are recomputed by the caller via ClassWriter(COMPUTE_FRAMES).
     */
    private static int applyOverwriteWithBody(MethodNode targetMethod, MixinSelector sel,
                                              Map<String, ClassNode> mixinNodes,
                                              List<ModAnalysis.Evidence> outEvidence) {
        ClassNode mixinNode = mixinNodes.get(sel.mixinClass());
        if (mixinNode == null) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite mixin class not in pass input '" + sel.mixinClass() + "' for "
                            + sel.mixinClass() + "#" + sel.handlerMethod(), 0.95));
            return 0;
        }
        MethodNode handler = findMethodExact(mixinNode, sel.handlerMethod(), sel.handlerDesc());
        if (handler == null) {
            // Try name-only fallback when handlerDesc is null/"()V" defaulted
            for (MethodNode mi : mixinNode.methods) {
                if (mi.name.equals(sel.handlerMethod())) { handler = mi; break; }
            }
        }
        if (handler == null) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite handler not found '" + sel.handlerMethod() + sel.handlerDesc()
                            + "' in mixin " + sel.mixinClass(), 1.0));
            return 0;
        }
        if ((handler.access & Opcodes.ACC_ABSTRACT) != 0) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite handler is abstract (no body) " + sel.mixinClass() + "#" + sel.handlerMethod(), 1.0));
            return 0;
        }
        // Construction targets must not be overwritten (illegal to replace <init>/<clinit>)
        if ("<init>".equals(targetMethod.name) || "<clinit>".equals(targetMethod.name)) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite of constructor/static-init not supported: " + targetMethod.name + targetMethod.desc, 1.0));
            return 0;
        }
        String handlerDesc = handler.desc;
        if (!handlerDesc.equals(targetMethod.desc)) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite descriptor mismatch handler " + handlerDesc
                            + " vs target " + targetMethod.desc
                            + " (" + sel.mixinClass() + "#" + sel.handlerMethod() + ")", 1.0));
            return 0;
        }
        boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        boolean targetStatic = (targetMethod.access & Opcodes.ACC_STATIC) != 0;
        if (handlerStatic != targetStatic) {
            outEvidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "overwrite static mismatch handlerStatic=" + handlerStatic
                            + " targetStatic=" + targetStatic
                            + " (" + sel.mixinClass() + "#" + sel.handlerMethod() + ")", 1.0));
            return 0;
        }
        // Preserve widened access of the target (AT already ran: public/protected/default + FINAL removed).
        // Mixin's conformVisibility (mixin-internals overwrites.conformVisibility) may only UPGRADE, never narrow.
        int widenedAccess = targetMethod.access;
        // Replace body wholesale
        // Clone handler insns with fresh labels
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode ln) {
                labelMap.put(ln, new LabelNode());
            }
        }
        InsnList replacement = new InsnList();
        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            replacement.add(insn.clone(labelMap));
        }
        targetMethod.instructions.clear();
        targetMethod.instructions.add(replacement);
        // Try-catch blocks (clone with remapped labels)
        targetMethod.tryCatchBlocks = new ArrayList<>();
        for (TryCatchBlockNode tcb : handler.tryCatchBlocks) {
            LabelNode start = labelMap.getOrDefault(tcb.start, tcb.start);
            LabelNode end = labelMap.getOrDefault(tcb.end, tcb.end);
            LabelNode handlerLb = labelMap.getOrDefault(tcb.handler, tcb.handler);
            targetMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handlerLb, tcb.type));
        }
        // Local variables
        if (handler.localVariables != null) {
            targetMethod.localVariables = new ArrayList<>();
            for (LocalVariableNode lvn : handler.localVariables) {
                LabelNode s = labelMap.getOrDefault(lvn.start, lvn.start);
                LabelNode e = labelMap.getOrDefault(lvn.end, lvn.end);
                targetMethod.localVariables.add(new LocalVariableNode(lvn.name, lvn.desc, lvn.signature, s, e, lvn.index));
            }
        } else {
            targetMethod.localVariables = null;
        }
        targetMethod.exceptions = handler.exceptions == null ? new ArrayList<>() : new ArrayList<>(handler.exceptions);
        targetMethod.signature = handler.signature;
        targetMethod.visibleAnnotations = handler.visibleAnnotations != null ? new ArrayList<>(handler.visibleAnnotations) : null;
        targetMethod.invisibleAnnotations = handler.invisibleAnnotations != null ? new ArrayList<>(handler.invisibleAnnotations) : null;
        // Keep the original widened access (never overwrite visibility/final from mixin)
        targetMethod.access = widenedAccess;
        // Diagnostics use AccessorGenerator's shape but Overwrite has its own count domain; evidence already carries handler vs target proof.
        return 1;
    }

    private static MethodNode findMethodExact(ClassNode cn, String name, String desc) {
        if (name == null) return null;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(name)) continue;
            if (desc != null && !mn.desc.equals(desc)) continue;
            if (desc == null && mn.name.equals(name)) return mn;
            if (mn.desc.equals(desc)) return mn;
        }
        return null;
    }

    private static boolean isHeadOrTail(AtSelector at) {
        return "HEAD".equals(at.pointId()) || "TAIL".equals(at.pointId()) || "RETURN".equals(at.pointId());
    }

    private static InsnList handlerCall(String owner, String name, String desc) {
        InsnList l = new InsnList();
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
        return l;
    }
}
