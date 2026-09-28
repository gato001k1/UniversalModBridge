package dev.umb.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Flattened, queryable index of a host Minecraft jar (spec §19–§20). Indexes every class's
 * name hierarchy, method signatures and field signatures once, then answers membership
 * queries for the static linkage checker without re-reading the jar.
 *
 * <p>Only what static linkage needs: class existence, method (name+desc) per owner,
 * field (name+desc) per owner, plus the supertype closure for inherited-member resolution.
 */
public final class HostIndex implements AutoCloseable {

    /** class internal name -> declared members. */
    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final JarFile jar;

    public static HostIndex of(Path hostJar) throws IOException {
        HostIndex idx = new HostIndex(new JarFile(hostJar.toFile()));
        try {
            for (var entries = idx.jar.entries(); entries.hasMoreElements(); ) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = idx.jar.getInputStream(e)) {
                    idx.index(idx.read(in));
                } catch (IOException | RuntimeException malformed) {
                    // untrusted input (spec §131): skip malformed entries silently
                }
            }
        } catch (RuntimeException failure) {
            idx.close();
            throw failure;
        }
        return idx;
    }

    private HostIndex(JarFile jar) {
        this.jar = jar;
    }

    private ClassNode read(InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);
        ClassNode node = new ClassNode();
        cr.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return node;
    }

    private void index(ClassNode node) {
        ClassInfo info = new ClassInfo(node.name, node.superName);
        for (String itf : node.interfaces) {
            info.interfaces.add(itf);
        }
        if (node.methods != null) {
            for (var m : node.methods) {
                info.methods.put(m.name + m.desc, new MemberRef(m.name, m.desc));
            }
        }
        if (node.fields != null) {
            for (var f : node.fields) {
                info.fields.put(f.name + f.desc, new MemberRef(f.name, f.desc));
            }
        }
        classes.put(node.name, info);
    }

    // ------------------------------------------------------------------ queries

    public boolean hasClass(String internalName) {
        return classes.containsKey(internalName);
    }

    /**
     * Resolves a method reference against the host, walking the superclass chain and
     * interfaces. Returns null when neither the class nor any supertype declares it.
     */
    public MemberRef findMethod(String ownerInternal, String name, String descriptor) {
        ClassInfo start = classes.get(ownerInternal);
        if (start == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        return walk(start, seen, (ci) -> ci.methods.get(name + descriptor));
    }

    /** Same resolution rules as {@link #findMethod}, for fields. */
    public MemberRef findField(String ownerInternal, String name, String descriptor) {
        ClassInfo start = classes.get(ownerInternal);
        if (start == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        return walk(start, seen, (ci) -> ci.fields.get(name + descriptor));
    }

    private interface MemberLookup {
        MemberRef in(ClassInfo ci);
    }

    /**
     * Breadth-first over superclasses then interfaces — JVM resolution order. Cycle-safe
     * via {@code seen} (malformed jars can declare impossible hierarchies).
     */
    private MemberRef walk(ClassInfo start, Set<String> seen, MemberLookup lookup) {
        var queue = new java.util.ArrayDeque<ClassInfo>();
        queue.add(start);
        seen.add(start.name);
        while (!queue.isEmpty()) {
            ClassInfo ci = queue.poll();
            MemberRef hit = lookup.in(ci);
            if (hit != null) {
                return hit;
            }
            if (ci.superName != null && seen.add(ci.superName)) {
                ClassInfo sup = classes.get(ci.superName);
                if (sup != null) {
                    queue.add(sup);
                }
            }
            for (String itf : ci.interfaces) {
                if (seen.add(itf)) {
                    ClassInfo itfInfo = classes.get(itf);
                    if (itfInfo != null) {
                        queue.add(itfInfo);
                    }
                }
            }
        }
        // java/lang/Object methods (toString etc.) always resolve.
        if (start.name.equals("java/lang/Object")) {
            return null;
        }
        return lookup.in(new ClassInfo("java/lang/Object", null));
    }

    /** Number of indexed classes — used by CLI reporting to show index scale. */
    public int classCount() {
        return classes.size();
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }

    private static final class ClassInfo {
        final String name;
        final String superName;
        final Set<String> interfaces = new HashSet<>();
        final Map<String, MemberRef> methods = new HashMap<>();
        final Map<String, MemberRef> fields = new HashMap<>();

        ClassInfo(String name, String superName) {
            this.name = name;
            this.superName = superName;
        }
    }

    record MemberRef(String name, String desc) {}
}
