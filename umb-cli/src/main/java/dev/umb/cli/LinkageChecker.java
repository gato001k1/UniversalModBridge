package dev.umb.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

/**
 * Static linkage checker (spec §21): walks every class of a candidate mod jar and resolves
 * each method/field reference against a {@link HostIndex}. Unresolved references are the
 * concrete "this mod cannot run on the host as-is" signal that drives pass planning —
 * reported with owner/name/desc plus source location, never summarized away.
 *
 * <p>Deliberately static-only: no loading, no execution, no agent. Third-party jars are
 * untrusted input (spec §131).
 */
public final class LinkageChecker {

    /** One unresolved reference, located to class+method for pass-planning triage. */
    public record MissingRef(String kind, String owner, String name, String desc,
                             String sourceClass, String sourceMethod) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%s %s.%s%s (from %s::%s)",
                    kind, owner.replace('/', '.'), name, desc, sourceClass, sourceMethod);
        }
    }

    public record LinkageResult(int classesScanned, int refsChecked, List<MissingRef> missing) {
        public boolean links() {
            return missing.isEmpty();
        }
    }

    private final HostIndex host;

    public LinkageChecker(HostIndex host) {
        this.host = host;
    }

    public LinkageResult check(Path modJar) throws IOException {
        int classesScanned = 0;
        int refs = 0;
        List<MissingRef> missing = new ArrayList<>();

        try (JarFile jf = new JarFile(modJar.toFile())) {
            for (var entries = jf.entries(); entries.hasMoreElements(); ) {
                var e = entries.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node;
                try (InputStream in = jf.getInputStream(e)) {
                    node = read(in);
                } catch (IOException | RuntimeException malformed) {
                    missing.add(new MissingRef("malformed-class", e.getName(), "-", "-", "-", "-"));
                    continue;
                }
                classesScanned++;
                // References into classes the jar itself does not define must resolve in host.
                for (MethodNode mn : node.methods) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof MethodInsnNode mi) {
                            refs++;
                            if (!host.hasClass(mi.owner)) {
                                continue; // not a host class (own/jdk/library code) — out of scope
                            }
                            if (host.findMethod(mi.owner, mi.name, mi.desc) == null) {
                                missing.add(new MissingRef(
                                        "method", mi.owner, mi.name, mi.desc, node.name, mn.name));
                            }
                        } else if (insn instanceof FieldInsnNode fi) {
                            refs++;
                            if (!host.hasClass(fi.owner)) {
                                continue;
                            }
                            if (host.findField(fi.owner, fi.name, fi.desc) == null) {
                                missing.add(new MissingRef(
                                        "field", fi.owner, fi.name, fi.desc, node.name, mn.name));
                            }
                        }
                    }
                }
            }
        }
        return new LinkageResult(classesScanned, refs, List.copyOf(missing));
    }

    private ClassNode read(InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }
}
