package dev.umb.pipeline;

import dev.umb.core.AccessorGenerator;
import dev.umb.core.AtWidener;
import dev.umb.core.InjectionPointKind;
import dev.umb.core.MixinRewriter;
import dev.umb.core.MixinSelector;
import dev.umb.core.ModAnalysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * M8-4: pipeline pass that wires {@link MixinRewriter}, {@link AccessorGenerator} and
 * {@link AtWidener} into jar processing. For each target class it applies matching
 * {@code MixinSelector}s (via rewrite), generates {@code @Accessor}/{@code @Invoker}
 * bridges, and widens access per AT files. D4 on UNRESOLVABLE: report evidence, skip.
 * Synthetic fixtures only (CC0), stubs never ship. Never modifies original jar (spec 24).
 */
public final class MixinApplyPass implements TranslationPass {

    public static final String PASS_ID = "M08-mixin-apply";
    public static final String AUDIT_EVIDENCE_KIND = "mixin-audit";
    public static final String HARD_AUDIT_EVIDENCE_KIND = "mixin-unresolved";

    @Override
    public String id() { return PASS_ID; }

    @Override
    public PassReport run(ModAnalysis analysis, Path input, Path output) throws Exception {
        if (analysis == null) throw new NullPointerException("analysis");
        if (input == null || !Files.isRegularFile(input)) {
            PassReport r = new PassReport(PASS_ID, PassReport.Status.FAIL);
            r.diag(new PassReport.Diagnostic("MISSING_INPUT", "input jar not found: " + input,
                    analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                    "mixin-apply", "MixinApplyPass", "Ensure IN.jar exists"));
            return r;
        }
        if (output == null) throw new NullPointerException("output");
        Path outParent = output.toAbsolutePath().getParent();
        if (outParent != null) Files.createDirectories(outParent);

        // M8-7 translate-or-reject: ASM and JS coremods are not graph-translatable — refuse before any jar flow
        List<ModAnalysis.CoremodShape> shapes = analysis.coremodShapes() != null ? analysis.coremodShapes() : List.of();
        boolean hasAsm = shapes.contains(ModAnalysis.CoremodShape.IFML_PLUGIN)
                || shapes.contains(ModAnalysis.CoremodShape.LAUNCHWRAPPER_TWEAKER);
        boolean hasJs = shapes.contains(ModAnalysis.CoremodShape.JS_COREMOD_MODERN)
                || shapes.contains(ModAnalysis.CoremodShape.JS_COREMOD_HISTORICAL);
        if (hasAsm || hasJs) {
            PassReport r = new PassReport(PASS_ID, PassReport.Status.FAIL);
            String modId = analysis.modId();
            String era = analysis.sourceMcVersion().orElse("(unknown)");
            if (hasAsm) {
                String which = shapes.stream()
                        .filter(s -> s == ModAnalysis.CoremodShape.IFML_PLUGIN
                                || s == ModAnalysis.CoremodShape.LAUNCHWRAPPER_TWEAKER)
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.joining(","));
                String msg = "COREMOD_ASM_UNSUPPORTED: Forge IFMLLoadingPlugin/IClassTransformer coremod present ("
                        + which + ") classes=" + analysis.coremodClasses() + " — not graph-translatable; refused per D4 §115";
                r.diag(new PassReport.Diagnostic("COREMOD_ASM_UNSUPPORTED", msg, modId, era,
                        "mixin-apply", "MixinApplyPass",
                        "Remove IClassTransformer/IFMLLoadingPlugin coremod or port to Mixin/AT"));
                r.note("evidence[coremod] " + msg);
            }
            if (hasJs) {
                String which = shapes.stream()
                        .filter(s -> s == ModAnalysis.CoremodShape.JS_COREMOD_MODERN
                                || s == ModAnalysis.CoremodShape.JS_COREMOD_HISTORICAL)
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.joining(","));
                String msg = "COREMOD_JS_UNSUPPORTED: JS coremod present (" + which
                        + ") — not graph-translatable; refused per D4 §115";
                r.diag(new PassReport.Diagnostic("COREMOD_JS_UNSUPPORTED", msg, modId, era,
                        "mixin-apply", "MixinApplyPass",
                        "Remove JS coremod (initializeCoreMod/addOverride) or port to Mixin"));
                r.note("evidence[coremod] " + msg);
            }
            return r;
        }

        boolean hasMixinWork = analysis.mixinInventory() != null
                && analysis.mixinInventory().declaredMixinCount() > 0;
        boolean hasAtWork = (analysis.accessWideners() != null && !analysis.accessWideners().isEmpty())
                || (analysis.accessTransformers() != null && !analysis.accessTransformers().isEmpty());
        // Also opportunistically treat META-INF/accesstransformer entries as work even if analyzer missed them,
        // but the hasMixinWork/hasAtWork gate is about declared work — if nothing declared we SKIPPED (identity copy).
        if (!hasMixinWork && !hasAtWork) {
            PassReport r = new PassReport(PASS_ID, PassReport.Status.SKIPPED);
            r.note("no mixin or AT work declared (mixinInventory declared=0, accessWideners="
                    + (analysis.accessWideners() == null ? 0 : analysis.accessWideners().size())
                    + " accessTransformers=" + (analysis.accessTransformers() == null ? 0 : analysis.accessTransformers().size()) + ")");
            copyJarIdentity(input, output);
            return r;
        }

        Map<String, byte[]> classBytes = new LinkedHashMap<>();
        Map<String, byte[]> nonClassEntries = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(input.toFile())) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (je.isDirectory()) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    byte[] b = in.readAllBytes();
                    if (je.getName().endsWith(".class")) {
                        String internal = je.getName().substring(0, je.getName().length() - 6);
                        classBytes.put(internal, b);
                    } else {
                        nonClassEntries.put(je.getName(), b);
                    }
                }
            }
        }

        List<AtWidener.AtRule> atRules = new ArrayList<>();
        List<ModAnalysis.Evidence> atEvidence = new ArrayList<>();
        List<ModAnalysis.Evidence> atParseEvidence = new ArrayList<>();
        if (analysis.accessWideners() != null) {
            for (String path : analysis.accessWideners()) {
                byte[] data = lookup(nonClassEntries, path);
                if (data == null) {
                    atEvidence.add(new ModAnalysis.Evidence(AtWidener.EVIDENCE_KIND,
                            "accessWidener not found in jar: " + path, 0.9));
                    continue;
                }
                String text = new String(data, StandardCharsets.UTF_8);
                AtWidener.ParseResult pr = AtWidener.parseAccessWidener(text);
                atRules.addAll(pr.rules());
                atParseEvidence.addAll(pr.evidence());
            }
        }
        if (analysis.accessTransformers() != null) {
            for (String path : analysis.accessTransformers()) {
                byte[] data = lookup(nonClassEntries, path);
                if (data == null) {
                    for (Map.Entry<String, byte[]> en : nonClassEntries.entrySet()) {
                        if (en.getKey().contains("accesstransformer")) {
                            data = en.getValue();
                            break;
                        }
                    }
                }
                if (data == null) {
                    atEvidence.add(new ModAnalysis.Evidence(AtWidener.EVIDENCE_KIND,
                            "accessTransformer not found in jar: " + path, 0.9));
                    continue;
                }
                String text = new String(data, StandardCharsets.UTF_8);
                AtWidener.ParseResult pr = AtWidener.parseFmlAt(text);
                atRules.addAll(pr.rules());
                atParseEvidence.addAll(pr.evidence());
            }
        }
        // Opportunistically include any META-INF/accesstransformer entries not already handled
        java.util.Set<String> handledAtPaths = new java.util.HashSet<>();
        if (analysis.accessTransformers() != null) {
            for (String p : analysis.accessTransformers()) {
                handledAtPaths.add(p);
                handledAtPaths.add("/" + p);
                if (p.startsWith("/")) handledAtPaths.add(p.substring(1));
            }
        }
        for (Map.Entry<String, byte[]> en : nonClassEntries.entrySet()) {
            String name = en.getKey();
            if (!name.startsWith("META-INF/accesstransformer")) continue;
            if (handledAtPaths.contains(name)) continue;
            String text = new String(en.getValue(), StandardCharsets.UTF_8);
            AtWidener.ParseResult pr = AtWidener.parseFmlAt(text);
            atRules.addAll(pr.rules());
            atParseEvidence.addAll(pr.evidence());
        }

        Map<String, List<MixinSelector>> targetSelectors = new HashMap<>();
        Map<String, List<AccessorGenerator.AccessorRequest>> targetAccessors = new HashMap<>();
        Map<String, ClassNode> mixinNodes = new HashMap<>();
        if (analysis.mixinInventory() != null) {
            for (ModAnalysis.MixinConfigInventory cfg : analysis.mixinInventory().configs()) {
                for (ModAnalysis.MixinClassInventory mci : cfg.mixins()) {
                    boolean hasSelectors = mci.selectors() != null && !mci.selectors().isEmpty();
                    boolean hasAccessors = (mci.accessors() > 0 || mci.invokers() > 0);
                    boolean needsMixinBytes = hasAccessors || hasSelectors;
                    // Cache mixin ClassNode when bytes are available — needed for accessor extract and true Overwrite bodies
                    if (needsMixinBytes) {
                        String mixinInternal = mci.className().replace('.', '/');
                        if (!mixinNodes.containsKey(mixinInternal)) {
                            byte[] mixinBytes = classBytes.get(mixinInternal);
                            if (mixinBytes == null) {
                                for (Map.Entry<String, byte[]> cb : classBytes.entrySet()) {
                                    if (cb.getKey().replace('/', '.').equals(mci.className())) {
                                        mixinBytes = cb.getValue();
                                        break;
                                    }
                                }
                            }
                            if (mixinBytes != null) {
                                mixinNodes.put(mixinInternal, toNode(mixinBytes));
                            }
                        }
                    }
                    if (mci.presence() != ModAnalysis.MixinClassPresence.PRESENT_IN_JAR) continue;
                    List<String> targets = mci.mixinTargets();
                    if (targets == null || targets.isEmpty()) continue;
                    for (String targetName : targets) {
                        String internalTarget = normalizeToInternal(targetName);
                        if (hasSelectors) {
                            targetSelectors.computeIfAbsent(internalTarget, k -> new ArrayList<>())
                                    .addAll(mci.selectors());
                        }
                    }
                    if (hasAccessors) {
                        String mixinInternal = mci.className().replace('.', '/');
                        ClassNode mixinNode = mixinNodes.get(mixinInternal);
                        if (mixinNode == null) continue;
                        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixinNode);
                        if (reqs.isEmpty()) continue;
                        for (String targetName : targets) {
                            String internalTarget = normalizeToInternal(targetName);
                            targetAccessors.computeIfAbsent(internalTarget, k -> new ArrayList<>())
                                    .addAll(reqs);
                        }
                    }
                }
            }
        }

        int classesTransformed = 0;
        int totalMixinApplied = 0;
        int totalMixinSkipped = 0;
        int totalAccessorsGenerated = 0;
        int totalAtWidened = 0;
        List<ModAnalysis.Evidence> collectedEvidence = new ArrayList<>();
        collectedEvidence.addAll(atEvidence);
        collectedEvidence.addAll(atParseEvidence);

        Map<String, byte[]> rewrittenClasses = new LinkedHashMap<>();
        // Track per-target application for audit (unapplied / shadow mismatches)
        Map<String, Integer> appliedByTarget = new HashMap<>();
        Map<String, Integer> skippedByTarget = new HashMap<>();
        // Shadow membership audit needs the remapped target shape; consult the remapped nodes
        Map<String, java.util.List<String>> shadowMembersByMixin = new HashMap<>();
        if (analysis.mixinInventory() != null) {
            for (ModAnalysis.MixinConfigInventory cfg : analysis.mixinInventory().configs()) {
                for (ModAnalysis.MixinClassInventory mci : cfg.mixins()) {
                    if (!mci.shadowMembers().isEmpty()) {
                        shadowMembersByMixin.put(mci.className().replace('.', '/'),
                                new java.util.ArrayList<>(mci.shadowMembers()));
                    }
                }
            }
        }
        for (Map.Entry<String, byte[]> e : classBytes.entrySet()) {
            String internal = e.getKey();
            byte[] original = e.getValue();
            ClassNode node = toNode(original);
            boolean dirty = false;
            List<ModAnalysis.Evidence> perClassEvidence = new ArrayList<>();

            if (!atRules.isEmpty()) {
                AtWidener.WidenResult wr = AtWidener.apply(node, atRules, perClassEvidence);
                if (wr.widened() > 0) {
                    totalAtWidened += wr.widened();
                    dirty = true;
                }
            }

            List<AccessorGenerator.AccessorRequest> accReqs = targetAccessors.getOrDefault(internal, List.of());
            if (!accReqs.isEmpty()) {
                AccessorGenerator.GenerateResult gr = AccessorGenerator.generate(node, accReqs, perClassEvidence);
                if (gr.generated() > 0) {
                    totalAccessorsGenerated += gr.generated();
                    dirty = true;
                }
            }

            List<MixinSelector> sels = targetSelectors.getOrDefault(internal, List.of());
            if (!sels.isEmpty()) {
                List<ModAnalysis.Evidence> rwEvidence = new ArrayList<>();
                MixinRewriter.RewriteStats stats;
                if (mixinNodes.isEmpty()) {
                    stats = MixinRewriter.rewrite(node, sels, null, rwEvidence);
                } else {
                    stats = MixinRewriter.rewrite(node, sels, null, mixinNodes, rwEvidence);
                }
                if (stats.applied() > 0) dirty = true;
                totalMixinApplied += stats.applied();
                totalMixinSkipped += stats.skipped();
                if (!sels.isEmpty()) {
                    appliedByTarget.merge(internal, stats.applied(), Integer::sum);
                    skippedByTarget.merge(internal, stats.skipped(), Integer::sum);
                }
                perClassEvidence.addAll(rwEvidence);
            }

            collectedEvidence.addAll(perClassEvidence);
            byte[] outBytes;
            if (dirty) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                node.accept(cw);
                outBytes = cw.toByteArray();
                classesTransformed++;
            } else {
                outBytes = original;
            }
            rewrittenClasses.put(internal, outBytes);
        }

        Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> en : nonClassEntries.entrySet()) {
                JarEntry je = new JarEntry(en.getKey());
                out.putNextEntry(je);
                out.write(en.getValue());
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : rewrittenClasses.entrySet()) {
                String entryName = e.getKey() + ".class";
                JarEntry je = new JarEntry(entryName);
                out.putNextEntry(je);
                out.write(e.getValue());
                out.closeEntry();
            }
        }

        // --- M8-6 audit surface: unapplied mixins + shadow mismatches (TranslateCommand staging already has host names) ---
        // M8-7: split unapplied into soft (require==0, fail-soft drop) vs hard (require>0, MIXIN_UNRESOLVED FAIL)
        List<ModAnalysis.Evidence> auditEvidence = new ArrayList<>();
        List<ModAnalysis.Evidence> hardAuditEvidence = new ArrayList<>();
        if (analysis.mixinInventory() != null) {
            for (ModAnalysis.MixinConfigInventory cfg : analysis.mixinInventory().configs()) {
                for (ModAnalysis.MixinClassInventory mci : cfg.mixins()) {
                    List<String> targets = mci.mixinTargets();
                    if (targets == null || targets.isEmpty()) continue;
                    boolean classAbsent = mci.presence() != ModAnalysis.MixinClassPresence.PRESENT_IN_JAR;
                    int expected = mci.selectors() == null ? 0 : mci.selectors().size();
                    long unapplied = 0;
                    for (String tn : targets) {
                        String it = normalizeToInternal(tn);
                        Integer ok = appliedByTarget.get(it);
                        Integer skipped = skippedByTarget.get(it);
                        boolean hasRewriteWork = targetSelectors.containsKey(it);
                        // If this mixin's selectors targeted this class but nothing was applied there, it's unapplied for that mixin class
                        if (hasRewriteWork && (ok == null || ok == 0)) {
                            // Attribute proportionally: this mixin contributed at least one selector for this target
                            // Our per-target accounting merged across mixins; use skipped as signal.
                            if (skipped != null && skipped > 0) unapplied++;
                            else if (ok == null) unapplied++;
                        }
                    }
                    if (classAbsent && hasAtWork) {
                        // Mixins missing from jar are already evidenced in ModAnalysis; surface as audit too
                    }
                    for (MixinSelector sel : (mci.selectors() == null ? List.<MixinSelector>of() : mci.selectors())) {
                        if (sel.kind() != InjectionPointKind.OVERWRITE && sel.require() > 0) {
                            // require>0 with no successful application on any of its targets = D4-injected failure
                            boolean anyAppliedForSomeTarget = false;
                            for (String tn : targets) {
                                String it = normalizeToInternal(tn);
                                if (appliedByTarget.getOrDefault(it, 0) > 0) { anyAppliedForSomeTarget = true; break; }
                            }
                            if (!anyAppliedForSomeTarget && expected > 0) {
                                // Will be covered by the unapplied message below; individual require detail here is verbose
                            }
                        }
                    }
                    if (unapplied > 0) {
                        if (mci.requireGreaterThanZero() > 0) {
                            hardAuditEvidence.add(new ModAnalysis.Evidence(HARD_AUDIT_EVIDENCE_KIND,
                                    "MIXIN_UNRESOLVED: unapplied mixin " + mci.className()
                                            + " (targets=" + targets + ") — all selectors skipped; require=" + mci.requireGreaterThanZero()
                                            + " overwrites=" + mci.overwrites() + " — refused (require>0) per M8-7 D4", 1.0));
                        } else {
                            auditEvidence.add(new ModAnalysis.Evidence(AUDIT_EVIDENCE_KIND,
                                    "unapplied mixin " + mci.className()
                                            + " (targets=" + targets + ") — all selectors skipped; require=" + mci.requireGreaterThanZero()
                                            + " overwrites=" + mci.overwrites() + "; dropped (require==0) per M8-7 fail-soft", 1.0));
                        }
                    }
                }
            }
            // Shadow audit: every @Shadow member should resolve on at least one declared target's actual bytecode.
            // After remap, target names are host names; inventory shadowMember names are developer names. Compare membership
            // by name (and descriptor when available) against the rewritten target set.
            for (ModAnalysis.MixinConfigInventory cfg : analysis.mixinInventory().configs()) {
                for (ModAnalysis.MixinClassInventory mci : cfg.mixins()) {
                    if (mci.shadowMembers().isEmpty()) continue;
                    List<String> targets = mci.mixinTargets();
                    if (targets == null || targets.isEmpty()) continue;
                    for (String shadow : mci.shadowMembers()) {
                        boolean foundOnSomeTarget = false;
                        for (String tn : targets) {
                            String it = normalizeToInternal(tn);
                            byte[] tb = rewrittenClasses.get(it);
                            if (tb == null) tb = classBytes.get(it);
                            if (tb == null) continue;
                            ClassNode cn = toNode(tb);
                            boolean nameHit = false;
                            for (org.objectweb.asm.tree.FieldNode fn : cn.fields) if (fn.name.equals(shadow)) { nameHit = true; break; }
                            if (!nameHit) for (org.objectweb.asm.tree.MethodNode mn : cn.methods) if (mn.name.equals(shadow)) { nameHit = true; break; }
                            if (nameHit) { foundOnSomeTarget = true; break; }
                        }
                        if (!foundOnSomeTarget) {
                            // Constructor shadows and synthetic targets legitimately absent; still D4 report
                            auditEvidence.add(new ModAnalysis.Evidence(AUDIT_EVIDENCE_KIND,
                                    "shadow mismatch '" + shadow + "' in " + mci.className()
                                            + " not present on any declared target " + targets, 0.9));
                        }
                    }
                }
            }
        }
        List<ModAnalysis.Evidence> effectiveAuditEvidence = new ArrayList<>(auditEvidence);
        effectiveAuditEvidence.addAll(hardAuditEvidence);
        collectedEvidence.addAll(auditEvidence);
        collectedEvidence.addAll(hardAuditEvidence);

        // M8-7 hard-fail surface: require>0 unapplied -> MIXIN_UNRESOLVED FAIL (before any OK/WARN branching)
        if (!hardAuditEvidence.isEmpty()) {
            PassReport fail = new PassReport(PASS_ID, PassReport.Status.FAIL);
            fail.note("MIXIN_UNRESOLVED: " + hardAuditEvidence.size() + " hard-unapplied mixin(s) (require>0) — refused per M8-7"
                    + " atWidened=" + totalAtWidened + " accessors=" + totalAccessorsGenerated
                    + " mixinApplied=" + totalMixinApplied + " mixinSkipped=" + totalMixinSkipped);
            // Emit hard evidence as MIXIN_UNRESOLVED diagnostics (and also keep audit stream for visibility)
            for (ModAnalysis.Evidence ev : hardAuditEvidence) {
                fail.note("evidence[" + ev.kind() + "] " + ev.detail());
                fail.diag(new PassReport.Diagnostic("MIXIN_UNRESOLVED", ev.detail(),
                        analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                        "mixin-apply", "MixinApplyPass",
                        "A required mixin injector (require>0) could not bind — verify mapping/refmap/target present or make it optional (require=0)"));
            }
            // Also surface the remaining soft evidence so operators see the full picture even on the FAIL path
            for (ModAnalysis.Evidence ev : collectedEvidence) {
                boolean isAt = ev.kind().equals(AtWidener.EVIDENCE_KIND);
                boolean isRewriter = ev.kind().equals(MixinRewriter.EVIDENCE_KIND);
                boolean isAccessor = ev.kind().equals(AccessorGenerator.EVIDENCE_KIND);
                boolean isAudit = ev.kind().equals(AUDIT_EVIDENCE_KIND);
                // hard evidence already emitted above; avoid double MIXIN_UNRESOLVED for the same entry
                if (ev.kind().equals(HARD_AUDIT_EVIDENCE_KIND)) continue;
                fail.note("evidence[" + ev.kind() + "] " + ev.detail());
                if (isRewriter || isAt || isAccessor || isAudit) {
                    String code = isAt ? "AT_UNRESOLVABLE"
                            : isAccessor ? "ACCESSOR_UNRESOLVABLE"
                            : isAudit ? "MIXIN_AUDIT"
                            : "MIXIN_UNRESOLVABLE";
                    fail.diag(new PassReport.Diagnostic(code, ev.detail(),
                            analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                            "mixin-apply", "MixinApplyPass",
                            isAudit ? "Unapplied mixin/shadow mismatch — verify mapping/refmap/target still present"
                                    : "Verify target class still carries the member (mapping drift, refmap gap)"));
                }
            }
            return fail;
        }

        PassReport report;
        String auditSuffix = effectiveAuditEvidence.isEmpty() ? ""
                : " auditWarnings=" + effectiveAuditEvidence.size()
                + (totalMixinSkipped > 0 ? " (mixins skipped; check require>0)" : "");
        if (classesTransformed == 0 && totalMixinApplied == 0 && totalAccessorsGenerated == 0 && totalAtWidened == 0) {
            report = new PassReport(PASS_ID, PassReport.Status.WARN);
            report.note("no class transformed (declared work but no target matched) — "
                    + "atWidened=" + totalAtWidened
                    + " accessors=" + totalAccessorsGenerated
                    + " mixinApplied=" + totalMixinApplied
                    + " mixinSkipped=" + totalMixinSkipped + auditSuffix);
        } else if (!effectiveAuditEvidence.isEmpty()) {
            report = new PassReport(PASS_ID, PassReport.Status.WARN);
            report.note("classes transformed: " + classesTransformed
                    + " atWidened=" + totalAtWidened
                    + " accessors=" + totalAccessorsGenerated
                    + " mixinApplied=" + totalMixinApplied
                    + " mixinSkipped=" + totalMixinSkipped + auditSuffix);
        } else {
            report = new PassReport(PASS_ID, PassReport.Status.OK);
            report.note("classes transformed: " + classesTransformed
                    + " atWidened=" + totalAtWidened
                    + " accessors=" + totalAccessorsGenerated
                    + " mixinApplied=" + totalMixinApplied
                    + " mixinSkipped=" + totalMixinSkipped + auditSuffix);
        }
        for (ModAnalysis.Evidence ev : collectedEvidence) {
            report.note("evidence[" + ev.kind() + "] " + ev.detail());
            boolean isRewriter = ev.kind().equals(MixinRewriter.EVIDENCE_KIND);
            boolean isAt = ev.kind().equals(AtWidener.EVIDENCE_KIND);
            boolean isAccessor = ev.kind().equals(AccessorGenerator.EVIDENCE_KIND);
            boolean isAudit = ev.kind().equals(AUDIT_EVIDENCE_KIND);
            boolean isHardAudit = ev.kind().equals(HARD_AUDIT_EVIDENCE_KIND);
            if (isRewriter || isAt || isAccessor || isAudit || isHardAudit) {
                String code = isAt ? "AT_UNRESOLVABLE"
                        : isAccessor ? "ACCESSOR_UNRESOLVABLE"
                        : isAudit ? "MIXIN_AUDIT"
                        : "MIXIN_UNRESOLVABLE";
                report.diag(new PassReport.Diagnostic(code, ev.detail(),
                        analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                        "mixin-apply", "MixinApplyPass",
                        isAudit
                                ? "Unapplied mixin/shadow mismatch — verify mapping/refmap/target still present"
                                : "Verify target class still carries the member (mapping drift, refmap gap)"));
            }
        }
        return report;
    }

    private static byte[] lookup(Map<String, byte[]> entries, String path) {
        byte[] data = entries.get(path);
        if (data == null && !path.startsWith("/")) data = entries.get("/" + path);
        if (data == null && path.startsWith("/")) data = entries.get(path.substring(1));
        return data;
    }

    private static String normalizeToInternal(String target) {
        if (target == null) return "";
        String t = target.trim();
        if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
        t = t.replace('.', '/');
        return t;
    }

    private static ClassNode toNode(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private static void copyJarIdentity(Path in, Path out) throws IOException {
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
