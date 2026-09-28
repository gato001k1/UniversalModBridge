package dev.umb.pipeline;

import dev.umb.core.ModAnalysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * M11: case-split structural repair for {@link IncompatibleClassChangeError}
 * shapes no shim can fix (HBM-on-26.2 plateau: 4029/4664 loaded, 594 ICCE rows).
 *
 * <p>Host-shape facts below were verified against the unsigned 26.2 client with
 * JDK 25 {@code javap} (classfile 69; UMB builds stay on 21, so the pass never
 * reads host bytes — ASM 9.9 on JDK 21 must never define or parse V69):
 * <ul>
 *   <li>{@code .../projectile/Projectile} is an <b>abstract class</b> extending
 *       Entity, ctor {@code (EntityType, Level)} only — no {@code Level}-only
 *       ctor exists, so no ctor-compatible redirect target exists for the five
 *       mod declarers that {@code implements Projectile} while extending
 *       Entity. Repair: drop the interface clause (superclass Entity already
 *       carries identity/position/tick); the mod classes' own bytecode issues
 *       no Projectile-member calls (verified: only {@code List}/{@code log4j}
 *       invokeinterface rows). Lost {@code instanceof Projectile} conformance
 *       rides the evidence.
 *   <li>{@code .../AbstractSelectionList$Entry} is an <b>abstract class</b>
 *       (not an interface). Same drop-interface repair for
 *       {@code GuiFileList$Row}; no host class alternative (Entry's own
 *       parents are the interfaces LayoutElement/GuiEventListener).
 *   <li>{@code .../blockentity/BlockEntityRenderer} is an <b>interface</b>
 *       (host renderers implement it; the dispatcher is not a base class) and
 *       mod render ctors + {@code func_147500_a(Tessellator...)} bodies carry
 *       1.7.10-era members absent from the host. No class alternative:
 *       <b>refuse</b> ({@code STRUCTURAL_UNRESOLVABLE}).
 *   <li>{@code .../level/Explosion} is an <b>interface</b>. The only host
 *       implementation, {@code ServerExplosion}, requires
 *       {@code (ServerLevel, Entity, DamageSource, Calculator, Vec3, float,
 *       boolean, BlockInteraction)} while the mod calls
 *       {@code Explosion.<init>(Level, Entity, DDD, F)} — inventing the extra
 *       arguments would fabricate semantics. <b>Refuse.</b>
 *   <li>{@code .../inventory/CraftingContainer} is an <b>interface</b>, but the
 *       host ships the canonical simple implementation
 *       {@code TransientCraftingContainer} (non-final) whose ctor is exactly
 *       {@code (AbstractContainerMenu, int, int)} — descriptor-identical to the
 *       phantom super-ctor call the mod emits. Repair: <b>redirect</b> the
 *       supertype and rewrite the super-ctor owner. Inherited-member call
 *       sites are unaffected (the compiler emits them with the call-site class
 *       as owner, verified on {@code InventoryCraftingAuto}).
 *   <li>{@code .../levelgen/structure/StructureStart} and
 *       {@code net/minecraft/nbt/CompoundTag} are <b>final classes</b>.
 *       Cannot extend final: <b>refuse</b> naming both sides; host final is
 *       never stripped.
 *   <li>{@code AbstractTickableSoundInstance.stop()V} is {@code protected
 *       final}; {@code Entity.getGravity()D} is {@code public final}. Repair:
 *       <b>rename</b> the mod override to {@code umb$stop} /
 *       {@code umb$getGravity} (no override semantics anymore) and rewrite
 *       matching self/subclass call sites; never silently drop. Host
 *       polymorphic callers now bind the host final — behaviour delta rides
 *       the evidence.
 * </ul>
 *
 * <p>Scope rule (D4): the pass only touches supertypes / methods named in the
 * tables above. Anything else keeps its bytes; a jar with no findings is
 * copied through as SKIPPED. Refusals are WARN (never FAIL): a corpus jar
 * legitimately carries hundreds of unfixable renderers, and failing the whole
 * translate on them would make the stage unusable. Input jar never mutated
 * (spec §24); malformed single classes tolerated per §131 (copied verbatim +
 * note, like GraphJarRemapper).
 */
public final class StructuralRepairPass implements TranslationPass {

    public static final String PASS_ID = "M11-structural";
    public static final String EVIDENCE_KIND = "structural-repair";

    // ------------------------------------------------------------------ host table

    /** Mod supertypes that are 26.2 interfaces with no class alternative: refuse. */
    private static final Set<String> INTERFACE_SUPER_REFUSE = Set.of(
            "net/minecraft/client/renderer/blockentity/BlockEntityRenderer",
            "net/minecraft/world/level/Explosion");

    /** Mod interfaces-to-implement that are 26.2 classes: drop the clause. */
    private static final Set<String> IMPLEMENTS_CLASS_DROP = Set.of(
            "net/minecraft/world/entity/projectile/Projectile",
            "net/minecraft/client/gui/components/AbstractSelectionList$Entry");

    /** Mod supertypes that are 26.2 final classes: refuse, name both sides. */
    private static final Set<String> FINAL_SUPER_REFUSE = Set.of(
            "net/minecraft/world/level/levelgen/structure/StructureStart",
            "net/minecraft/nbt/CompoundTag");

    /** Interface supertype -> host class redirect (verified non-final, ctor-compatible). */
    private static final Map<String, String> INTERFACE_SUPER_REDIRECT = Map.of(
            "net/minecraft/world/inventory/CraftingContainer",
            "net/minecraft/world/inventory/TransientCraftingContainer");

    /** Host superclass links needed to recognise a final-method override through
     *  a mod intermediate (BurrowingBase) or directly (Loop, AudioDynamic).
     *  Verified via javap: PathfinderMob -&gt; Mob -&gt; LivingEntity -&gt; Entity;
     *  AbstractTickableSoundInstance -&gt; AbstractSoundInstance. */
    private static final Map<String, String> HOST_SUPER = Map.of(
            "net/minecraft/world/entity/PathfinderMob", "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Entity",
            "net/minecraft/client/resources/sounds/AbstractTickableSoundInstance",
            "net/minecraft/client/resources/sounds/AbstractSoundInstance");

    private record FinalMethod(String owner, String name, String desc, String renamed) {}

    private static final List<FinalMethod> FINAL_METHODS = List.of(
            new FinalMethod("net/minecraft/client/resources/sounds/AbstractTickableSoundInstance",
                    "stop", "()V", "umb$stop"),
            new FinalMethod("net/minecraft/world/entity/Entity",
                    "getGravity", "()D", "umb$getGravity"));

    // ------------------------------------------------------------------ run

    @Override
    public String id() { return PASS_ID; }

    @Override
    public PassReport run(ModAnalysis analysis, Path input, Path output) throws Exception {
        if (analysis == null) throw new NullPointerException("analysis");
        if (input == null || !Files.isRegularFile(input)) {
            PassReport r = new PassReport(PASS_ID, PassReport.Status.FAIL);
            r.diag(new PassReport.Diagnostic("MISSING_INPUT", "input jar not found: " + input,
                    analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                    "structural-repair", "StructuralRepairPass", "Ensure IN.jar exists"));
            return r;
        }
        if (output == null) throw new NullPointerException("output");
        Path outParent = output.toAbsolutePath().getParent();
        if (outParent != null) Files.createDirectories(outParent);
        String modId = analysis.modId() != null ? analysis.modId() : "unknown";
        String era = analysis.sourceMcVersion().orElse("(unknown)");

        Map<String, byte[]> classBytes = new LinkedHashMap<>();
        Map<String, byte[]> nonClassEntries = new LinkedHashMap<>();
        List<String> entryOrder = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        try (JarFile jf = new JarFile(input.toFile())) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry je = e.nextElement();
                if (je.isDirectory()) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    byte[] b = in.readAllBytes();
                    entryOrder.add(je.getName());
                    if (je.getName().endsWith(".class")) {
                        classBytes.put(je.getName().substring(0, je.getName().length() - 6), b);
                    } else {
                        nonClassEntries.put(je.getName(), b);
                    }
                }
            }
        }

        // Parse mod classes once (§131: malformed singles ride verbatim).
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : classBytes.entrySet()) {
            try {
                ClassNode n = new ClassNode();
                new ClassReader(e.getValue()).accept(n, 0);
                nodes.put(e.getKey(), n);
            } catch (RuntimeException malformedMember) {
                malformed.add(e.getKey() + " (" + malformedMember + ")");
            }
        }

        List<ModAnalysis.Evidence> evidence = new ArrayList<>();
        int redirected = 0, dropped = 0, renamed = 0, refused = 0, callSites = 0;

        // Pass 1: per-class supertype + override findings (collect renames first so
        // pass 2 can rewrite every call site jar-wide, including subclasses).
        Map<String, String> renameByDeclarer = new LinkedHashMap<>(); // declarer -> newName
        Map<String, FinalMethod> renameRule = new HashMap<>();
        Set<String> dirty = new HashSet<>();
        for (Map.Entry<String, ClassNode> e : nodes.entrySet()) {
            String internal = e.getKey();
            ClassNode n = e.getValue();

            // (b) final supertype: refuse, name both sides. Never strip host final.
            if (n.superName != null && FINAL_SUPER_REFUSE.contains(n.superName)) {
                refused++;
                String msg = "STRUCTURAL_UNRESOLVABLE: " + pretty(internal) + " extends final 26.2 host class "
                        + pretty(n.superName) + " — cannot inherit from final; refused, host final kept";
                evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, msg, 1.0));
                continue;
            }
            // (a/d) interface as superclass.
            if (n.superName != null && INTERFACE_SUPER_REFUSE.contains(n.superName)) {
                refused++;
                String msg = "STRUCTURAL_UNRESOLVABLE: " + pretty(internal) + " has 26.2 interface "
                        + pretty(n.superName) + " as superclass with no reasonable host class alternative"
                        + (n.superName.endsWith("/Explosion")
                                ? " (ServerExplosion needs DamageSource/Calculator/Vec3/BlockInteraction — inventing args would fabricate semantics)"
                                : " (host renderers implement the interface; no base class exists)")
                        + " — refused";
                evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND, msg, 1.0));
                continue;
            }
            String redirectTarget = n.superName == null ? null : INTERFACE_SUPER_REDIRECT.get(n.superName);
            if (redirectTarget != null) {
                String oldSuper = n.superName;
                n.superName = redirectTarget;
                int rewritten = rewriteSuperCtor(n, e.getKey(), redirectTarget);
                dirty.add(internal);
                redirected++;
                callSites += rewritten;
                evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "STRUCTURAL_REDIRECT: " + pretty(internal) + " supertype "
                                + pretty(oldSuper)
                                + " -> " + pretty(redirectTarget)
                                + " (host canonical impl, descriptor-identical ctor; super-ctor calls rewritten: "
                                + rewritten + ")", 0.9));
            }
            // (a) implements-a-class: drop the clause (superclass already carries identity).
            if (n.interfaces != null && !n.interfaces.isEmpty()) {
                List<String> droppedIfaces = new ArrayList<>();
                n.interfaces.removeIf(itf -> {
                    if (IMPLEMENTS_CLASS_DROP.contains(itf)) {
                        droppedIfaces.add(itf);
                        return true;
                    }
                    return false;
                });
                if (!droppedIfaces.isEmpty()) {
                    dirty.add(internal);
                    dropped++;
                    for (String itf : droppedIfaces) {
                        evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "STRUCTURAL_DROP_INTERFACE: " + pretty(internal)
                                        + " no longer declares implements " + pretty(itf)
                                        + " (26.2 host kind is a class, no ctor-compatible alternative;"
                                        + " instanceof conformance lost; superclass carries identity)", 0.9));
                    }
                }
            }
            // (c) override-final: rename, record for jar-wide call-site rewrite.
            for (FinalMethod fm : FINAL_METHODS) {
                if (declaresMethod(n, fm.name(), fm.desc())
                        && hostAncestorMatches(nodes, n, fm.owner())) {
                    String nn = fm.renamed();
                    if (declaresMethod(n, nn, fm.desc())) {
                        refused++;
                        evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "STRUCTURAL_UNRESOLVABLE: " + pretty(internal) + " overrides final host "
                                        + pretty(fm.owner()) + "." + fm.name() + fm.desc()
                                        + " but already declares " + nn
                                        + " — rename target collides; refused rather than clobber", 1.0));
                    } else {
                        renameMethod(n, fm.name(), fm.desc(), nn);
                        renameByDeclarer.put(internal, nn);
                        renameRule.put(internal, fm);
                        dirty.add(internal);
                        renamed++;
                        evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "STRUCTURAL_RENAME_FINAL_OVERRIDE: " + pretty(internal) + "."
                                        + fm.name() + fm.desc() + " -> " + nn
                                        + " (host " + pretty(fm.owner()) + " declares it final;"
                                        + " no override semantics anymore; self/subclass call sites rewritten;"
                                        + " host polymorphic callers now bind the host final)", 0.9));
                    }
                }
            }
        }

        // Pass 2: rewrite call sites of renamed methods in the declarer and every
        // mod subclass of it (host callers cannot be rewritten — evidenced above).
        // Super-ctor-adjacent invokespecial super.X is an explicit host invocation:
        // left alone. Only invokevirtual/invokeinterface with a mod-side owner in
        // the declarer's subclass closure are retargeted.
        if (!renameByDeclarer.isEmpty()) {
            // Targeted subclass sets: one linear scan per rename declarer
            // (a full O(n^2) closure would stall corpus jars with thousands of classes).
            Map<String, Set<String>> subclasses = new HashMap<>();
            for (String declarer : renameByDeclarer.keySet()) {
                Set<String> subs = new HashSet<>();
                for (String candidate : nodes.keySet()) {
                    if (!candidate.equals(declarer) && isModSubclassOf(nodes, candidate, declarer)) {
                        subs.add(candidate);
                    }
                }
                subclasses.put(declarer, subs);
            }
            for (Map.Entry<String, ClassNode> e : nodes.entrySet()) {
                ClassNode n = e.getValue();
                if (n.methods == null) continue;
                for (MethodNode m : n.methods) {
                    if (m.instructions == null) continue;
                    for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode mi)) continue;
                        int op = mi.getOpcode();
                        if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE) continue;
                        for (Map.Entry<String, String> re : renameByDeclarer.entrySet()) {
                            FinalMethod fm = renameRule.get(re.getKey());
                            if (!mi.name.equals(fm.name()) || !mi.desc.equals(fm.desc())) continue;
                            if (mi.owner.equals(re.getKey())
                                    || subclasses.getOrDefault(re.getKey(), Set.of()).contains(mi.owner)) {
                                mi.name = re.getValue();
                                dirty.add(e.getKey());
                                callSites++;
                            }
                        }
                    }
                }
            }
        }

        // Write output (§24: input never mutated; entry order preserved).
        Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (String name : entryOrder) {
                byte[] data;
                if (name.endsWith(".class")) {
                    String internal = name.substring(0, name.length() - 6);
                    ClassNode n = nodes.get(internal);
                    if (n != null && dirty.contains(internal)) {
                        // Frames copied through (GraphJarRemapper doctrine): edits keep
                        // identical stack shapes, and COMPUTE_FRAMES would need the
                        // V69 host hierarchy on a JDK 21 classpath that cannot hold it.
                        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        n.accept(cw);
                        data = cw.toByteArray();
                    } else {
                        data = classBytes.get(internal);
                    }
                    if (data == null) continue;
                } else {
                    data = nonClassEntries.get(name);
                    if (data == null) continue;
                }
                JarEntry je = new JarEntry(name);
                je.setTime(System.currentTimeMillis());
                out.putNextEntry(je);
                out.write(data);
                out.closeEntry();
            }
        }

        PassReport.Status status;
        if (redirected == 0 && dropped == 0 && renamed == 0 && refused == 0) {
            status = PassReport.Status.SKIPPED;
        } else if (refused > 0) {
            status = PassReport.Status.WARN;
        } else {
            status = PassReport.Status.OK;
        }
        PassReport report = new PassReport(PASS_ID, status);
        report.note("scanned: " + nodes.size() + " classes"
                + " redirected=" + redirected + " dropped=" + dropped
                + " renamed=" + renamed + " callSites=" + callSites + " refused=" + refused);
        if (!malformed.isEmpty()) {
            report.note("malformed classes copied verbatim (§131): " + malformed.size());
        }
        for (ModAnalysis.Evidence ev : evidence) {
            report.note("evidence[" + ev.kind() + "] " + ev.detail());
            String code = ev.detail().startsWith("STRUCTURAL_REDIRECT") ? "STRUCTURAL_REDIRECT"
                    : ev.detail().startsWith("STRUCTURAL_DROP_INTERFACE") ? "STRUCTURAL_DROP_INTERFACE"
                    : ev.detail().startsWith("STRUCTURAL_RENAME_FINAL_OVERRIDE")
                            ? "STRUCTURAL_RENAME_FINAL_OVERRIDE"
                    : "STRUCTURAL_UNRESOLVABLE";
            boolean isRefusal = code.equals("STRUCTURAL_UNRESOLVABLE");
            report.diag(new PassReport.Diagnostic(code, ev.detail(), modId, era,
                    "structural-repair", "StructuralRepairPass",
                    isRefusal
                            ? "No semantics-preserving repair exists — class stays unloaded by design; port the source"
                            : "Verify the repaired class loads and behaves under the host; evidence names what changed"));
        }
        return report;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean declaresMethod(ClassNode n, String name, String desc) {
        if (n.methods == null) return false;
        for (MethodNode m : n.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        }
        return false;
    }

    private static void renameMethod(ClassNode n, String from, String desc, String to) {
        for (MethodNode m : n.methods) {
            if (m.name.equals(from) && m.desc.equals(desc)) m.name = to;
        }
    }

    /**
     * Walks the mod superclass chain, then the baked host links: true when the
     * final-method owner is a host ancestor of the class.
     */
    private static boolean hostAncestorMatches(Map<String, ClassNode> nodes, ClassNode n, String hostOwner) {
        Set<String> seen = new HashSet<>();
        String sup = n.superName;
        while (sup != null && seen.add(sup)) {
            if (sup.equals(hostOwner)) return true;
            ClassNode mod = nodes.get(sup);
            if (mod != null) {
                sup = mod.superName;
            } else {
                sup = HOST_SUPER.get(sup);
            }
        }
        return false;
    }

    private static boolean isModSubclassOf(Map<String, ClassNode> nodes, String child, String ancestor) {
        Set<String> seen = new HashSet<>();
        String cur = child;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(ancestor)) return true;
            ClassNode n = nodes.get(cur);
            if (n == null) return false;
            cur = n.superName;
        }
        return false;
    }

    /**
     * Rewrites the phantom super-ctor call ({@code invokespecial OldSuper.<init>})
     * to the redirect target. Only {@code <init>} is illegal against an
     * interface super; inherited-member calls carry the call-site class as owner.
     */
    private static int rewriteSuperCtor(ClassNode n, String internal, String redirectTarget) {
        int count = 0;
        if (n.methods == null) return 0;
        for (MethodNode m : n.methods) {
            if (m.instructions == null) continue;
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESPECIAL
                        && mi.name.equals("<init>")
                        && INTERFACE_SUPER_REDIRECT.containsKey(mi.owner)) {
                    mi.owner = redirectTarget;
                    count++;
                }
            }
        }
        return count;
    }

    private static String pretty(String internal) {
        return internal.replace('/', '.');
    }

    /**
     * Standalone driver for post-processing an already-translated jar without a
     * full retranslate: {@code StructuralRepairPass IN.jar OUT.jar}. Analyzes
     * IN for report identity, never mutates it (§24). Exit 0 unless FAIL.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: StructuralRepairPass IN.jar OUT.jar");
            System.exit(1);
            return;
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        ModAnalysis analysis = new dev.umb.core.BasicModAnalyzer().analyze(in);
        PassReport r = new StructuralRepairPass().run(analysis, in, out);
        System.out.println("pass: " + r.passId() + " status=" + r.status());
        for (String note : r.notes()) System.out.println("  note: " + note);
        for (PassReport.Diagnostic d : r.diagnostics()) {
            System.out.println("  [" + d.code() + "] " + d.message());
        }
        System.exit(r.status() == PassReport.Status.FAIL ? 1 : 0);
    }
}
