package dev.umb.legacy.boot;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * F0 - SrgFieldRepair .
 * <p>{@code build/legacy/1.7.10-forge-srg-runtime-clean.jar} is internally inconsistent: hundreds of classes declare their fields under raw obfuscated short names while the rest of the jar (and every mod compiled against SRG names)...
 */
public final class SrgFieldRepair {

    /** Immutable result of one repair run, also the JUnit gate's input. */
    public static final class Result {
        public final int classesTouched;
        public final int fieldDeclarationsRenamed;
        public final int methodDeclarationsRenamed;
        public final int methodInstructionsRenamed;
        public final int totalClassesInJar;
        public final boolean slotGetSlotIndexInjected;

        Result(int classesTouched, int fieldDeclarationsRenamed, int methodDeclarationsRenamed,
               int methodInstructionsRenamed, int totalClassesInJar,
               boolean slotGetSlotIndexInjected) {
            this.classesTouched = classesTouched;
            this.fieldDeclarationsRenamed = fieldDeclarationsRenamed;
            this.methodDeclarationsRenamed = methodDeclarationsRenamed;
            this.methodInstructionsRenamed = methodInstructionsRenamed;
            this.totalClassesInJar = totalClassesInJar;
            this.slotGetSlotIndexInjected = slotGetSlotIndexInjected;
        }

        public String summary() {
            return "SrgFieldRepair: classesInJar=" + totalClassesInJar
                    + " classesTouched=" + classesTouched
                    + " fieldDeclarationsRenamed=" + fieldDeclarationsRenamed
                    + " methodDeclarationsRenamed=" + methodDeclarationsRenamed
                    + " methodInstructionsRenamed=" + methodInstructionsRenamed
                    + " slotGetSlotIndexInjected=" + slotGetSlotIndexInjected;
        }
    }

    private static final String SLOT = "net/minecraft/inventory/Slot";
    private static final String SLOT_INDEX_FIELD = "field_75225_a";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SrgFieldRepair <joined.srg> <inJar> <outJar> [summaryFile]");
            System.exit(2);
            return;
        }
        Path srgFile = Path.of(args[0]);
        File inJar = new File(args[1]);
        File outJar = new File(args[2]);
        Result r = repair(srgFile, inJar, outJar);
        String summary = r.summary();
        System.out.println("[SrgFieldRepair] " + summary);
        if (args.length >= 4) {
            Files.writeString(Path.of(args[3]), summary + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
    }

    public static Result repair(Path srgFile, File inJar, File outJar) throws IOException {
        Map<String, Map<String, String>> fieldMap = SrgFieldMap.load(srgFile);
        Map<String, Map<SrgMethodMap.Key, SrgMethodMap.Target>> methodMap = SrgMethodMap.load(srgFile);

        // ---- pass 1: read every class, build the superclass graph, keep the raw bytes in order ----
        Map<String, byte[]> classEntries = new LinkedHashMap<>();
        Map<String, byte[]> otherEntries = new LinkedHashMap<>();
        Map<String, String> superOf = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(inJar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (je.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(jf.getInputStream(je));
                if (je.getName().endsWith(".class")) {
                    classEntries.put(je.getName(), data);
                    ClassReader cr = new ClassReader(data);
                    superOf.put(cr.getClassName(), cr.getSuperName());
                } else {
                    otherEntries.put(je.getName(), data);
                }
            }
        }

        // ---- pass 2: rewrite field declarations + field instructions ----
        int classesTouched = 0;
        int fieldDeclarationsRenamed = 0;
        int methodDeclarationsRenamed = 0;
        int methodInstructionsRenamed = 0;
        Map<String, byte[]> outClasses = new LinkedHashMap<>(classEntries.size());
        for (Map.Entry<String, byte[]> e : classEntries.entrySet()) {
            ClassReader cr = new ClassReader(e.getValue());
            ClassWriter cw = new ClassWriter(0);
            int[] counts = new int[3];
            RenameVisitor rv = new RenameVisitor(cw, fieldMap, methodMap, superOf, counts);
            cr.accept(rv, 0);
            byte[] out = cw.toByteArray();
            if (counts[0] > 0 || counts[1] > 0 || counts[2] > 0) {
                classesTouched++;
                fieldDeclarationsRenamed += counts[0];
                methodDeclarationsRenamed += counts[1];
                methodInstructionsRenamed += counts[2];
            }
            if (SLOT.equals(cr.getClassName())) {
                out = injectSlotGetSlotIndex(out);
            }
            outClasses.put(e.getKey(), out);
        }

        // ---- write the repaired jar ----
        File parent = outJar.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outJar)))) {
            for (Map.Entry<String, byte[]> e : outClasses.entrySet()) {
                writeEntry(jos, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, byte[]> e : otherEntries.entrySet()) {
                writeEntry(jos, e.getKey(), e.getValue());
            }
        }

        return new Result(classesTouched, fieldDeclarationsRenamed, methodDeclarationsRenamed,
                methodInstructionsRenamed, classEntries.size(), true);
    }

    /** {@code owner} is walked up its superclass chain (built from the jar) until a map hit or Object. */
    static String resolveFieldName(String owner, String name, Map<String, Map<String, String>> fieldMap,
                                    Map<String, String> superOf) {
        String cur = owner;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            Map<String, String> m = fieldMap.get(cur);
            if (m != null) {
                String srg = m.get(name);
                if (srg != null) {
                    return srg;
                }
            }
            cur = superOf.get(cur);
        }
        return name;
    }

    static String resolveMethodName(String owner, String name, String descriptor,
                                    Map<String, Map<SrgMethodMap.Key, SrgMethodMap.Target>> methodMap,
                                    Map<String, String> superOf) {
        String cur = owner;
        Set<String> seen = new HashSet<>();
        SrgMethodMap.Key key = new SrgMethodMap.Key(name, descriptor);
        while (cur != null && seen.add(cur)) {
            Map<SrgMethodMap.Key, SrgMethodMap.Target> m = methodMap.get(cur);
            if (m != null) {
                SrgMethodMap.Target target = m.get(key);
                if (target != null) return target.name;
            }
            cur = superOf.get(cur);
        }
        return name;
    }

    private static byte[] injectSlotGetSlotIndex(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        // getSlotIndex() must not already exist - defend against re-running against an already
        // repaired jar, which would otherwise produce a duplicate-method classfile.
        boolean[] alreadyThere = new boolean[1];
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                              String[] exceptions) {
                if ("getSlotIndex".equals(name) && "()I".equals(descriptor)) {
                    alreadyThere[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (alreadyThere[0]) {
            return classBytes;
        }

        ClassWriter cw = new ClassWriter(0);
        ClassVisitor injector = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "getSlotIndex", "()I", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, SLOT, SLOT_INDEX_FIELD, "I");
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
                super.visitEnd();
            }
        };
        cr.accept(injector, 0);
        return cw.toByteArray();
    }

    private static final class RenameVisitor extends ClassVisitor {
        private final Map<String, Map<String, String>> fieldMap;
        private final Map<String, Map<SrgMethodMap.Key, SrgMethodMap.Target>> methodMap;
        private final Map<String, String> superOf;
        private final int[] counts;
        private String className;

        RenameVisitor(ClassVisitor cv, Map<String, Map<String, String>> fieldMap,
                      Map<String, Map<SrgMethodMap.Key, SrgMethodMap.Target>> methodMap,
                      Map<String, String> superOf, int[] counts) {
            super(Opcodes.ASM9, cv);
            this.fieldMap = fieldMap;
            this.methodMap = methodMap;
            this.superOf = superOf;
            this.counts = counts;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                           String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            String mapped = resolveFieldName(className, name, fieldMap, superOf);
            if (!mapped.equals(name)) counts[0]++;
            return super.visitField(access, mapped, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                          String[] exceptions) {
            String mappedName = resolveMethodName(className, name, descriptor, methodMap, superOf);
            if (!mappedName.equals(name)) counts[1]++;
            MethodVisitor mv = super.visitMethod(access, mappedName, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
            Map<String, Map<String, String>> fm = fieldMap;
            Map<String, String> so = superOf;
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    String mapped = resolveFieldName(owner, name, fm, so);
                    super.visitFieldInsn(opcode, owner, mapped, descriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                            boolean isInterface) {
                    String mapped = resolveMethodName(owner, name, descriptor, methodMap, so);
                    if (!mapped.equals(name)) counts[2]++;
                    super.visitMethodInsn(opcode, owner, mapped, descriptor, isInterface);
                }
            };
        }
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] data) throws IOException {
        JarEntry je = new JarEntry(name);
        jos.putNextEntry(je);
        jos.write(data);
        jos.closeEntry();
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

    private SrgFieldRepair() {
    }
}
