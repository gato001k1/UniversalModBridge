package dev.umb.legacy.boot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Legacy compatibility behavior. */
public final class LinkPreflight {

    public static final class Ref {
        public final String owner, name, desc;
        public final boolean isField;

        public Ref(String owner, String name, String desc, boolean isField) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.isField = isField;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Ref r)) {
                return false;
            }
            return isField == r.isField && owner.equals(r.owner) && name.equals(r.name) && desc.equals(r.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, desc, isField);
        }

        @Override
        public String toString() {
            return owner + "." + name + ":" + desc + (isField ? " (field)" : " (method)");
        }
    }

    private static final class ClassInfo {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> fieldNames = new HashSet<>();
        final Set<String> methodSigs = new HashSet<>();
    }

    public static final class ClassIndex {
        final Map<String, ClassInfo> classes = new HashMap<>();
    }

    private static final Set<String> ALWAYS_RESOLVED_OFF_INDEX = Set.of(
            "equals(Ljava/lang/Object;)Z",
            "hashCode()I",
            "toString()Ljava/lang/String;",
            "getClass()Ljava/lang/Class;",
            "wait()V",
            "notify()V",
            "notifyAll()V",
            "ordinal()I",
            "name()Ljava/lang/String;",
            "compareTo(Ljava/lang/Object;)I");

    public static final class Report {
        public int totalRefs;
        public int missing;
        public int ownerClassAbsent;
        public final List<Ref> missingRefs = new ArrayList<>();

        public String summary() {
            return "LinkPreflight: totalRefs=" + totalRefs + " missing=" + missing
                    + " ownerClassAbsent=" + ownerClassAbsent;
        }
    }

    public static ClassIndex indexJars(List<File> jars) throws IOException {
        ClassIndex idx = new ClassIndex();
        for (File jar : jars) {
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry je = en.nextElement();
                    if (!je.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] data = readAll(jf.getInputStream(je));
                    ClassReader cr = new ClassReader(data);
                    ClassInfo ci = idx.classes.computeIfAbsent(cr.getClassName(), k -> new ClassInfo());
                    ci.superName = cr.getSuperName();
                    ci.interfaces = cr.getInterfaces();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor,
                                                        String signature, Object value) {
                            ci.fieldNames.add(name);
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                          String signature, String[] exceptions) {
                            ci.methodSigs.add(name + descriptor);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return idx;
    }

    /** Collects distinct Fieldref/Methodref usages whose owner starts with one of {@code ownerPrefixes}. */
    public static Set<Ref> collectReferences(List<File> scanJars, String[] ownerPrefixes) throws IOException {
        Set<Ref> refs = new LinkedHashSet<>();
        for (File jar : scanJars) {
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry je = en.nextElement();
                    if (!je.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] data = readAll(jf.getInputStream(je));
                    ClassReader cr = new ClassReader(data);
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                          String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                                    if (matches(owner, ownerPrefixes)) {
                                        refs.add(new Ref(owner, fname, fdesc, true));
                                    }
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                             boolean isInterface) {
                                    if (matches(owner, ownerPrefixes)) {
                                        refs.add(new Ref(owner, mname, mdesc, false));
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return refs;
    }

    /** Like {@link #collectReferences} but keeps only refs whose owner is in {@code ownerAllowList}. */
    public static Set<Ref> collectReferencesForOwners(List<File> scanJars, Set<String> ownerAllowList)
            throws IOException {
        Set<Ref> all = collectReferences(scanJars, new String[] {""});
        Set<Ref> out = new LinkedHashSet<>();
        for (Ref r : all) {
            if (ownerAllowList.contains(r.owner)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean matches(String owner, String[] prefixes) {
        for (String p : prefixes) {
            if (owner.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static boolean resolve(ClassIndex idx, Ref ref) {
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(ref.owner);
        boolean ranOffIndex = false;
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!seen.add(cur)) {
                continue;
            }
            ClassInfo ci = idx.classes.get(cur);
            if (ci == null) {
                ranOffIndex = true;
                continue;
            }
            if (ref.isField) {
                if (ci.fieldNames.contains(ref.name)) {
                    return true;
                }
            } else {
                if (ci.methodSigs.contains(ref.name + ref.desc)) {
                    return true;
                }
            }
            if (ci.superName != null) {
                stack.push(ci.superName);
            }
            for (String i : ci.interfaces) {
                stack.push(i);
            }
        }
        if (ranOffIndex && !ref.isField && ALWAYS_RESOLVED_OFF_INDEX.contains(ref.name + ref.desc)) {
            return true;
        }
        return false;
    }

    public static Report run(List<File> scanJars, List<File> resolveJars, String[] ownerPrefixes)
            throws IOException {
        ClassIndex idx = indexJars(resolveJars);
        Set<Ref> refs = collectReferences(scanJars, ownerPrefixes);
        return runAgainst(idx, refs);
    }

    public static Report runForOwners(List<File> scanJars, List<File> resolveJars, Set<String> ownerAllowList)
            throws IOException {
        ClassIndex idx = indexJars(resolveJars);
        Set<Ref> refs = collectReferencesForOwners(scanJars, ownerAllowList);
        return runAgainst(idx, refs);
    }

    private static Report runAgainst(ClassIndex idx, Set<Ref> refs) {
        Report rep = new Report();
        rep.totalRefs = refs.size();
        for (Ref r : refs) {
            if (!idx.classes.containsKey(r.owner)) {
                rep.ownerClassAbsent++;
                rep.missing++;
                rep.missingRefs.add(r);
                continue;
            }
            if (!resolve(idx, r)) {
                rep.missing++;
                rep.missingRefs.add(r);
            }
        }
        return rep;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: LinkPreflight <scanJars,...> <resolveJars,...> <prefixes,...>");
            System.exit(2);
            return;
        }
        List<File> scanJars = split(args[0]);
        List<File> resolveJars = split(args[1]);
        String[] prefixes = args[2].split(",");
        Report r = run(scanJars, resolveJars, prefixes);
        System.out.println(r.summary());
        for (Ref ref : r.missingRefs) {
            System.out.println("  MISSING " + ref);
        }
        System.exit(r.missing == 0 ? 0 : 1);
    }

    private static List<File> split(String s) {
        List<File> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(new File(p));
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private LinkPreflight() {
    }
}
