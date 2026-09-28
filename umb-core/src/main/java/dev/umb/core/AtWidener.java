package dev.umb.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * M8-4: Access Transformer / Access Widener support.
 * Parses FML AT ({@code accesstransformer.cfg}) and Fabric accessWidener v2 files
 * and applies visibility widening (private/protected -> public) and final removals
 * via ASM 9.9. Synthetic fixtures only (CC0), stubs never ship. D4: malformed lines
 * emit evidence and are skipped, never guessed.
 */
public final class AtWidener {

    private AtWidener() {}

    public static final String EVIDENCE_KIND = "access-widen";

    /** What a rule targets. */
    public enum TargetKind { CLASS, FIELD, METHOD, WILDCARD_FIELDS, WILDCARD_METHODS, WILDCARD_ALL }

    public record AtRule(
            String internalClass,
            String memberName,
            String descriptor,
            TargetKind kind,
            boolean makePublic,
            boolean makeProtected,
            boolean makeDefault,
            boolean removeFinal
    ) implements java.io.Serializable {}

    public record WidenResult(int widened, List<ModAnalysis.Evidence> evidence) {}

    // ------------------------------------------------------------------ parsing FML AT

    /** Parse an FML accesstransformer.cfg string. */
    public static ParseResult parseFmlAt(String content) {
        if (content == null) content = "";
        List<AtRule> rules = new ArrayList<>();
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int lineNo = 0;
        for (String rawLine : lines) {
            lineNo++;
            String line = rawLine;
            // Strip inline comment after # (but preserve # inside? AT comments use # at start or after target)
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            // Directives: #group / #endgroup etc? Already stripped, skip
            // Parse
            AtRule rule = parseFmlLine(line);
            if (rule == null) {
                ev.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "FML AT line " + lineNo + " unparseable: '" + rawLine.trim() + "'", 1.0));
            } else {
                rules.add(rule);
            }
        }
        return new ParseResult(List.copyOf(rules), List.copyOf(ev));
    }

    private static AtRule parseFmlLine(String line) {
        // First token is access spec like public, public-f, protected-f, private-f, default
        // Remaining is target
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        // Split on whitespace — first token is accessSpec, rest is target spec
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            // Could be class-only line like "public net.minecraft.world.entity.Entity"
            // Actually needs at least 2 parts
            // If only 1 part, malformed
            return null;
        }
        String accessSpec = parts[0];
        // accessSpec may be like "public-f", "public+f", "protected", "default", "private"
        boolean makePublic = false, makeProtected = false, makeDefault = false, removeFinal = false;
        String access = accessSpec;
        String mod = "";
        int dash = accessSpec.indexOf('-');
        int plus = accessSpec.indexOf('+');
        if (dash >= 0) {
            access = accessSpec.substring(0, dash);
            mod = accessSpec.substring(dash + 1);
        } else if (plus >= 0) {
            access = accessSpec.substring(0, plus);
            mod = accessSpec.substring(plus + 1);
        }
        switch (access) {
            case "public" -> makePublic = true;
            case "protected" -> makeProtected = true;
            case "default" -> makeDefault = true;
            case "private" -> { /* stay private? But we widen per spec to public logic - but keep explicit private? For AT private means keep private? Actually AT private makes private. We'll keep as private== no widen? But spec says public/private -> public. Here private means narrow. We'll encode as no makePublic. */ }
            default -> {
                // unknown access token
                return null;
            }
        }
        if (mod.contains("f")) {
            if (accessSpec.contains("-f")) removeFinal = true;
            else if (accessSpec.contains("+f")) { /* add final not widen */ }
        }
        // Reconstruct target spec from remaining parts (class + optional member)
        // Member may have descriptor with spaces? In FML format descriptor is separate token like "(...)"? Actually class and member are separate tokens.
        // Expected forms: ClassName [memberName [descriptor]] where descriptor may be attached to member or separate.
        // Examples: "net.minecraft.tileentity.TileEntity func_145826_a(Ljava/lang/Class;Ljava/lang/String;)V"
        //           "net.minecraft.client.gui.GuiTextField field_146209_f"
        //           "net.minecraft.client.renderer.RenderBlocks *"
        //           "net.minecraft.client.renderer.RenderBlocks *()"
        String className = parts[1];
        String memberName = null;
        String descriptor = null;
        TargetKind kind = TargetKind.CLASS;
        if (parts.length >= 3) {
            // parts[2] is member name (may include descriptor attached) or wildcard
            String mem = parts[2];
            if ("*()".equals(mem)) {
                memberName = "*";
                descriptor = null;
                kind = TargetKind.WILDCARD_METHODS;
            } else {
                // If member contains '(' it includes descriptor inline (e.g. func_123(I)V)
                int paren = mem.indexOf('(');
                if (paren >= 0) {
                    memberName = mem.substring(0, paren);
                    descriptor = mem.substring(paren);
                    kind = TargetKind.METHOD;
            } else {
                memberName = mem;
                // Check if wildcard
                if ("*".equals(memberName)) {
                    kind = TargetKind.WILDCARD_FIELDS;
                } else if ("*()".equals(memberName) || "*".equals(memberName) && parts.length > 3) {
                    // ambiguous
                    kind = TargetKind.WILDCARD_FIELDS;
                } else {
                    kind = TargetKind.FIELD; // default; may be method without parens? But fields don't have parens.
                }
                // If there is a 4th part, it is descriptor
                if (parts.length >= 4) {
                    String d = parts[3];
                    // d could be like "(I)V" or "I" etc.
                    if (d.startsWith("(") || d.startsWith("L") || "I".equals(d) || "Z".equals(d) || d.startsWith("[")) {
                        descriptor = d;
                        // If descriptor starts with '(' then it's method
                        if (descriptor.startsWith("(")) kind = TargetKind.METHOD;
                        else kind = TargetKind.FIELD;
                    } else if ("*()".equals(d)) {
                        kind = TargetKind.WILDCARD_METHODS;
                        memberName = "*";
                        descriptor = null;
                    }
                }
                // Handle "*()" as member wildcard method
                if ("*()".equals(memberName)) {
                    kind = TargetKind.WILDCARD_METHODS;
                    memberName = "*";
                    descriptor = null;
                }
                // Handle "*" with descriptor "*()" split
                if ("*".equals(memberName) && descriptor == null && parts.length == 3) {
                    // bare * could be either fields or methods - spec uses * for fields, *() for methods
                    // check if line originally had "*()" as separate? already handled
                    kind = TargetKind.WILDCARD_FIELDS;
                }
                }
            }
        }
        // Normalize className dot -> slash
        String internalClass = className.replace('.', '/');
        // Handle wildcard "*": memberName=="*" with field/method ambiguity
        if ("*".equals(memberName) && descriptor == null) {
            // If original line had "*()" we already mapped to WILDCARD_METHODS
            // otherwise treat bare "*" as wildcard-all logic: AT file uses "*" for fields and "*()" for methods
            // Keep as WILDCARD_FIELDS for "*"
        }
        if (memberName == null) {
            kind = TargetKind.CLASS;
        } else if ("*".equals(memberName) && kind == TargetKind.WILDCARD_METHODS) {
            // ok
        } else if ("*".equals(memberName) && kind == TargetKind.WILDCARD_FIELDS) {
            // ok
        } else if (memberName != null && kind == TargetKind.FIELD && descriptor != null && descriptor.startsWith("(")) {
            kind = TargetKind.METHOD;
        }
        // Edge: method wildcard via "*()" may have been parsed as member "*()" -> handled
        return new AtRule(internalClass, memberName, descriptor, kind, makePublic, makeProtected, makeDefault, removeFinal);
    }

    public record ParseResult(List<AtRule> rules, List<ModAnalysis.Evidence> evidence) {}

    // ------------------------------------------------------------------ parsing accessWidener v2

    public static ParseResult parseAccessWidener(String content) {
        if (content == null) content = "";
        List<AtRule> rules = new ArrayList<>();
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int lineNo = 0;
        for (String rawLine : lines) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue;
            if (line.startsWith("accessWidener")) continue;
            // Format: <action> <kind> <owner> [name] [desc]
            // action may be transitive- prefixed
            AtRule rule = parseWidenerLine(line);
            if (rule == null) {
                ev.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "accessWidener line " + lineNo + " unparseable: '" + rawLine.trim() + "'", 1.0));
            } else {
                rules.add(rule);
            }
        }
        return new ParseResult(List.copyOf(rules), List.copyOf(ev));
    }

    private static AtRule parseWidenerLine(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return null;
        String action = parts[0]; // accessible, extendable, mutable, transitive-...
        String kindStr = parts[1]; // class, field, method
        String owner = parts.length >= 3 ? parts[2] : null;
        if (owner == null) return null;
        String internalClass;
        String memberName = null;
        String descriptor = null;
        TargetKind kind;
        boolean makePublic = false, removeFinal = false;
        String baseAction = action.startsWith("transitive-") ? action.substring("transitive-".length()) : action;
        if (baseAction.contains("accessible")) makePublic = true;
        if (baseAction.contains("extendable") || baseAction.contains("mutable")) removeFinal = true;
        // extendable also implies widen? per ffapi, extendable -> public + remove final
        // If extendable without accessible pairing, we still want to widen to public for spec.
        // But file pairs them separately; we keep logic as: extendable/mutable => removeFinal plus makePublic when spec says public/private -> public?
        // For safety, treat extendable as also makePublic if not already (matches ffapi's mapping)
        if (baseAction.contains("extendable")) makePublic = true;

        switch (kindStr) {
            case "class" -> {
                kind = TargetKind.CLASS;
                internalClass = owner.replace('.', '/');
                // class entries have no member
                return new AtRule(internalClass, null, null, kind, makePublic, false, false, removeFinal);
            }
            case "field" -> {
                kind = TargetKind.FIELD;
                internalClass = owner.replace('.', '/');
                if (parts.length >= 4) {
                    memberName = parts[3];
                    if (parts.length >= 5) descriptor = parts[4];
                } else {
                    return null;
                }
                return new AtRule(internalClass, memberName, descriptor, kind, makePublic, false, false, removeFinal);
            }
            case "method" -> {
                kind = TargetKind.METHOD;
                internalClass = owner.replace('.', '/');
                if (parts.length >= 4) {
                    memberName = parts[3];
                    if (parts.length >= 5) {
                        // descriptor may be split as "(I)I" but actually single token "(I)I" includes no space
                        // If descriptor was split due to spaces inside? Unlikely.
                        descriptor = parts[4];
                        // If there are more parts, rejoin (defensive)
                        if (parts.length > 5) {
                            StringBuilder sb = new StringBuilder(descriptor);
                            for (int i = 5; i < parts.length; i++) sb.append(' ').append(parts[i]);
                            descriptor = sb.toString();
                        }
                    } else {
                        // method without descriptor? Some widener entries omit desc for <init>?
                        // treat as no desc
                    }
                } else {
                    return null;
                }
                // Handle constructor <init>
                return new AtRule(internalClass, memberName, descriptor, kind, makePublic, false, false, removeFinal);
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ application

    /** Apply rules matching target class. Mutates ClassNode in place. */
    public static WidenResult apply(ClassNode target, List<AtRule> rules, List<ModAnalysis.Evidence> outEvidence) {
        Objects.requireNonNull(target, "target");
        if (rules == null || rules.isEmpty()) return new WidenResult(0, List.of());
        List<ModAnalysis.Evidence> local = outEvidence != null ? outEvidence : new ArrayList<>();
        int widened = 0;
        for (AtRule rule : rules) {
            if (!rule.internalClass().equals(target.name)) continue;
            switch (rule.kind()) {
                case CLASS -> {
                    int before = target.access;
                    int after = widenAccess(before, rule);
                    if (after != before) {
                        target.access = after;
                        widened++;
                    }
                }
                case FIELD -> {
                    FieldNode fn = findField(target, rule.memberName(), rule.descriptor());
                    if (fn == null) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "AT field target not found '" + rule.memberName() + ":" + rule.descriptor()
                                        + "' in " + target.name, 0.9));
                    } else {
                        int before = fn.access;
                        int after = widenAccess(before, rule);
                        if (after != before) {
                            fn.access = after;
                            widened++;
                        }
                    }
                }
                case METHOD -> {
                    MethodNode mn = findMethod(target, rule.memberName(), rule.descriptor());
                    if (mn == null) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "AT method target not found '" + rule.memberName() + rule.descriptor()
                                        + "' in " + target.name, 0.9));
                    } else {
                        int before = mn.access;
                        int after = widenAccess(before, rule);
                        // Constructors <init> should not be made public via AT? But allow.
                        if (after != before) {
                            mn.access = after;
                            widened++;
                        }
                    }
                }
                case WILDCARD_FIELDS -> {
                    for (FieldNode fn : target.fields) {
                        int before = fn.access;
                        int after = widenAccess(before, rule);
                        if (after != before) {
                            fn.access = after;
                            widened++;
                        }
                    }
                }
                case WILDCARD_METHODS -> {
                    for (MethodNode mn : target.methods) {
                        int before = mn.access;
                        int after = widenAccess(before, rule);
                        if (after != before) {
                            mn.access = after;
                            widened++;
                        }
                    }
                }
                case WILDCARD_ALL -> {
                    for (FieldNode fn : target.fields) {
                        int before = fn.access;
                        int after = widenAccess(before, rule);
                        if (after != before) { fn.access = after; widened++; }
                    }
                    for (MethodNode mn : target.methods) {
                        int before = mn.access;
                        int after = widenAccess(before, rule);
                        if (after != before) { mn.access = after; widened++; }
                    }
                    int before = target.access;
                    int after = widenAccess(before, rule);
                    if (after != before) { target.access = after; widened++; }
                }
            }
        }
        if (outEvidence == null) {
            // caller passed null, we collected locally — return copy
            return new WidenResult(widened, List.copyOf(local));
        }
        return new WidenResult(widened, List.copyOf(local));
    }

    public static WidenResult apply(ClassNode target, List<AtRule> rules) {
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        return apply(target, rules, ev);
    }

    private static int widenAccess(int access, AtRule rule) {
        int out = access;
        if (rule.makePublic()) {
            out = (out & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (rule.makeProtected()) {
            out = (out & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC)) | Opcodes.ACC_PROTECTED;
        } else if (rule.makeDefault()) {
            out = out & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        }
        if (rule.removeFinal()) {
            out = out & ~Opcodes.ACC_FINAL;
        }
        return out;
    }

    private static FieldNode findField(ClassNode cn, String name, String desc) {
        for (FieldNode fn : cn.fields) {
            if (!fn.name.equals(name)) continue;
            if (desc != null && !fn.desc.equals(desc)) continue;
            return fn;
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(name)) continue;
            if (desc != null && !mn.desc.equals(desc)) continue;
            return mn;
        }
        return null;
    }
}
