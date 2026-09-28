package dev.umb.mappings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import dev.umb.mappings.MappingGraph.Node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph-driven remapper vertical: synthesize a jar whose references use the
 * OFFICIAL names of the shared tiny fixture, remap to intermediary and back, and
 * require structural identity after the round trip (spec §24/§128 discipline
 * applied to whole jars). Also pins the untrusted-input tolerance contract.
 */
class GraphJarRemapperTest {

    @TempDir
    Path tmp;

    private static final String SERVER_OFFICIAL = "net/minecraft/server/MinecraftServer";
    private static final String SERVER_ITP = "net/minecraft/class_2966";
    private static final String WORLD_OFFICIAL = "net/minecraft/world/World";
    private static final String WORLD_ITP = "net/minecraft/class_111";

    // ------------------------------------------------------------------ helpers

    private DefaultMappingGraph loadedGraph() throws IOException {
        TinyV2Reader.TinyFile f = TinyV2Reader.read(TinyFixtures.sampleOfficialIntermediary());
        DefaultMappingGraph g = new DefaultMappingGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");
        g.addTinyFile(f, off, 0, itp, 1, 1.0, "sample-official-intermediary.tiny");
        return g;
    }

    /** A class that touches the mapped API the way real mod code does. */
    private static byte[] callerClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        // Field of a mapped type: exercises map() + field descriptors.
        cw.visitField(Opcodes.ACC_PRIVATE, "world", "L" + WORLD_OFFICIAL + ";", null,
                null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // this.world = World.isRemote ? null : new-ish ref chain is overkill; instead:
        // int r = this.world.isRemote() -> exercises METHOD ref on mapped owner.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, name, "world", "L" + WORLD_OFFICIAL + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WORLD_OFFICIAL, "isRemote", "()Z", false);
        mv.visitInsn(Opcodes.POP);
        // MinecraftServer.loadWorld(this.world) -> cross-class DESCRIPTOR translation.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, name, "world", "L" + WORLD_OFFICIAL + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_OFFICIAL, "loadWorld",
                "(L" + WORLD_OFFICIAL + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries)
            throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    /** Core-structure fingerprint: everything a wrong remap could corrupt. */
    private static List<String> structure(Path jar, String className) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            var entry = jf.getEntry(className + ".class");
            if (entry == null) {
                return lines;
            }
            ClassReader cr = new ClassReader(jf.getInputStream(entry));
            ClassNodeProbe probe = new ClassNodeProbe();
            cr.accept(probe, ClassReader.EXPAND_FRAMES);
            lines.addAll(probe.lines);
        }
        return lines;
    }

    /** Minimal visitor collecting a deterministic textual fingerprint of a class. */
    private static final class ClassNodeProbe extends org.objectweb.asm.ClassVisitor {
        final List<String> lines = new ArrayList<>();

        ClassNodeProbe() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            lines.add("class " + name + " extends " + superName);
            for (String i : interfaces != null ? interfaces : new String[0]) {
                lines.add("implements " + i);
            }
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                                                         String descriptor, String signature, Object value) {
            lines.add("field " + name + " " + descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            lines.add("method " + name + " " + descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name,
                                            String descriptor, boolean isInterface) {
                    lines.add("  call " + owner + "." + name + descriptor);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name,
                                           String descriptor) {
                    lines.add("  getset " + owner + "." + name + " " + descriptor);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    lines.add("  type " + type);
                }
            };
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void roundTripOfficialIntermediaryOfficialRestoresStructure() throws IOException {
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("com/example/Caller.class", callerClass("com/example/Caller"));
        src.put("fabric.mod.json", "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Path original = jarOf(tmp, "original.jar", src);

        GraphJarRemapper remapper = new GraphJarRemapper(g);
        Path forward = tmp.resolve("forward.jar");
        GraphJarRemapper.Result fwd = remapper.remap(original, forward, off, itp);

        assertEquals(4, fwd.symbolsTranslated(),
                "World + MinecraftServer classes, isRemote + loadWorld methods");
        assertEquals(1, fwd.classesRemapped());
        assertEquals(5, fwd.symbolsUnmapped(),
                "Caller class + its <init> + world field, java/lang/Object + its <init>");

        // Forward output must reference intermediary names everywhere.
        List<String> fwdStruct = structure(forward, "com/example/Caller");
        assertTrue(fwdStruct.stream().anyMatch(l -> l.contains(SERVER_ITP)),
                "server class must be remapped to intermediary:\n" + fwdStruct);
        assertTrue(fwdStruct.stream().anyMatch(l -> l.contains(WORLD_ITP)),
                "world class must be remapped to intermediary:\n" + fwdStruct);
        assertTrue(fwdStruct.stream().noneMatch(l -> l.contains(WORLD_OFFICIAL)),
                "no official world refs may survive:\n" + fwdStruct);
        // The loadWorld descriptor must have its PARAMETER rewritten too
        // (method_14002 = loadWorld's intermediary name).
        assertTrue(fwdStruct.stream().anyMatch(l ->
                        l.contains("method_14002") && l.contains("(L" + WORLD_ITP + ";)V")),
                "descriptor must carry the intermediary parameter:\n" + fwdStruct);

        // And back again on a fresh remapper instance (no cache reuse).
        Path back = tmp.resolve("back.jar");
        GraphJarRemapper.Result bwd = new GraphJarRemapper(g).remap(forward, back, itp, off);
        assertEquals(4, bwd.symbolsTranslated(),
                "reverse pass must translate the same four symbols");

        List<String> orig = structure(original, "com/example/Caller");
        List<String> ret = structure(back, "com/example/Caller");
        assertEquals(orig, ret, "A→B→A must restore the jar's linked structure");
    }

    @Test
    void unmappableSymbolsKeepTheirNamesAndAreCounted() throws IOException {
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        // A class touching nothing the mapping knows: every symbol stays verbatim.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/plain/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("com/plain/Plain.class", cw.toByteArray());
        Path in = jarOf(tmp, "plain.jar", src);

        Path out = tmp.resolve("plain-out.jar");
        GraphJarRemapper.Result r = new GraphJarRemapper(g).remap(in, out, off, itp);

        assertEquals(0, r.symbolsTranslated(), "nothing here can translate");
        assertTrue(r.symbolsUnmapped() > 0, "unmappable symbols must be counted");
        List<String> s = structure(out, "com/plain/Plain");
        assertTrue(s.stream().anyMatch(l ->
                        l.equals("class com/plain/Plain extends java/lang/Object")),
                "unmapped refs must be untouched:\n" + s);
    }

    @Test
    void classEntriesAreRenamedWithTheirContent() throws IOException {
        // Real-1.20.1 smoke lesson: content rewritten to net/minecraft/... under a
        // stale obf zip path cannot load — the JVM locates by entry, defines by
        // this_class. Entry paths must follow the translation.
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("com/example/Caller.class", callerClass("com/example/Caller"));
        // An untranslatable class name (like the real jar's synthetic cmm$1) must
        // keep its path, staying consistent with the kept references to it.
        ClassWriter plain = new ClassWriter(0);
        plain.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/plain/Plain", null, "java/lang/Object", null);
        plain.visitEnd();
        src.put("com/plain/Plain.class", plain.toByteArray());
        Path in = jarOf(tmp, "named.jar", src);

        Path out = tmp.resolve("named-out.jar");
        new GraphJarRemapper(g).remap(in, out, off, itp);

        try (var jf = new java.util.jar.JarFile(out.toFile())) {
            // Caller's own name maps to nothing in this fixture (the tiny maps the
            // API classes, not mod classes) — an untranslatable self-name keeps its
            // path, consistent with the kept references to it.
            assertTrue(jf.getEntry("com/example/Caller.class") != null,
                    "untranslatable self-name keeps its entry path");
            assertTrue(jf.getEntry("com/plain/Plain.class") != null,
                    "unmapped class keeps its entry path");
        }
        Map<String, byte[]> src2 = new LinkedHashMap<>();
        src2.put(WORLD_OFFICIAL + ".class", callerClass(WORLD_OFFICIAL));
        Path in2 = jarOf(tmp, "selfnamed.jar", src2);
        Path out2 = tmp.resolve("selfnamed-out.jar");
        new GraphJarRemapper(g).remap(in2, out2, off, itp);
        try (var jf = new java.util.jar.JarFile(out2.toFile())) {
            assertTrue(jf.getEntry(WORLD_OFFICIAL + ".class") == null,
                    "class whose own name translates must not keep the source path");
            assertTrue(jf.getEntry(WORLD_ITP + ".class") != null,
                    "entry path must follow the remapped this_class");
        }
        // The move is not just a zip rename: the bytecode's this_class must carry
        // the SAME new name, so the JVM can define the class it locates by path.
        List<String> remapped = structure(out2, WORLD_ITP);
        assertTrue(remapped.stream().anyMatch(l -> l.contains("class " + WORLD_ITP)),
                "entry path and this_class must move in lockstep:\n" + remapped);
    }

    @Test
    void metaInfOverlayEntryKeepsItsPrefixAndFollowsTheClass() throws IOException {
        // Multi-release jars carry the overlay under META-INF/versions/N/. The
        // overlay must keep overriding the same base class (prefix survives) while
        // the class name under it still follows the translation.
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("META-INF/versions/9/" + WORLD_OFFICIAL + ".class",
                callerClass(WORLD_OFFICIAL));
        Path in = jarOf(tmp, "overlay.jar", src);

        Path out = tmp.resolve("overlay-out.jar");
        new GraphJarRemapper(g).remap(in, out, off, itp);

        try (var jf = new java.util.jar.JarFile(out.toFile())) {
            assertTrue(jf.getEntry("META-INF/versions/9/" + WORLD_ITP + ".class") != null,
                    "overlay entry must keep its prefix and follow the class name");
            assertTrue(jf.getEntry("META-INF/versions/9/" + WORLD_OFFICIAL + ".class") == null,
                    "source-namespace overlay path must not survive");
        }
        List<String> overlay = structure(out, "META-INF/versions/9/" + WORLD_ITP);
        assertTrue(overlay.stream().anyMatch(l -> l.contains("class " + WORLD_ITP)),
                "overlay content must be remapped too:\n" + overlay);
    }

    @Test
    void corruptClassIsCopiedVerbatimWithWarning() throws IOException {
        DefaultMappingGraph g = loadedGraph();
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("com/example/Caller.class", callerClass("com/example/Caller"));
        src.put("broken/NotAClass.class", "this is not a classfile".getBytes(StandardCharsets.UTF_8));
        src.put("META-INF/services/net.minecraft.server.MinecraftServer",
                SERVER_OFFICIAL.getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "mixed.jar", src);

        Path out = tmp.resolve("mixed-out.jar");
        GraphJarRemapper.Result r =
                new GraphJarRemapper(g).remap(in, out,
                        new Node("1.20-test", "official"), new Node("1.20-test", "intermediary"));

        boolean warnedBroken = r.warnings().stream().anyMatch(w -> w.contains("broken/NotAClass.class"));
        boolean warnedServices = r.warnings().stream().anyMatch(w -> w.contains("META-INF/services/"));
        assertTrue(warnedBroken, "unparsable class must produce a warning: " + r.warnings());
        assertTrue(r.classesRemapped() == 1, "the good class must still remap");
        assertTrue(warnedServices, "service file must be flagged: " + r.warnings());
        // The corrupt entry survives byte-for-byte.
        try (var jf = new java.util.jar.JarFile(out.toFile())) {
            var e = jf.getEntry("broken/NotAClass.class");
            assertTrue(e != null, "corrupt entry must be copied");
            assertEquals("this is not a classfile",
                    new String(jf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // --------------------------------------- stale signatures (D10, 1.21.1 smoke)

    /**
     * A Mojang-signed client.jar carries META-INF/*.SF/.RSA blocks and a manifest
     * whose per-entry sections declare SHA-*-Digest values. Remapping rewrites the
     * class bytes, so copying those signature entries VERBATIM makes the JDK's
     * JarFile verify the rewritten bytes against the stale digests and throw
     * SecurityException on every read (found by the 1.21.1 smoke: 60 classes
     * unreadable in a remapped client.jar). The remapper must un-sign its output:
     * drop the signature blocks and strip the manifest's per-entry digests while
     * keeping the main section, non-digest attributes, and — per the existing
     * entry-rename contract — the rewritten class content (c).
     */
    @Test
    void remappedSignedJarStripsSignatureBlocksAndVerifies() throws IOException {
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("X-Smoke", "kept-main");
        for (String entry : List.of("com/example/Caller.class", "asset.txt")) {
            Attributes section = new Attributes();
            section.putValue("SHA-384-Digest", "uGF3hT2qkA=="); // stale for the class: bytes get rewritten
            section.putValue("X-Smoke", "kept-" + entry);
            manifest.getEntries().put(entry, section);
        }
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("asset.txt", "non-class payload".getBytes(StandardCharsets.UTF_8));
        src.put("com/example/Caller.class", callerClass("com/example/Caller"));
        src.put("META-INF/MOJANGCS.SF", "fake signature file".getBytes(StandardCharsets.UTF_8));
        src.put("META-INF/MOJANGCS.RSA", "fake signer block".getBytes(StandardCharsets.UTF_8));
        src.put("META-INF/MOJANGCS.DSA", "fake DSA block".getBytes(StandardCharsets.UTF_8));
        src.put("META-INF/MOJANGCS.EC", "fake EC block".getBytes(StandardCharsets.UTF_8));
        Path in = tmp.resolve("signed.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(in), manifest)) {
            for (var e : src.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }

        Path outJar = tmp.resolve("signed-out.jar");
        new GraphJarRemapper(g).remap(in, outJar, off, itp);

        List<String> names = new ArrayList<>();
        try (var jf = new java.util.jar.JarFile(outJar.toFile())) {
            for (var en = jf.entries(); en.hasMoreElements(); ) {
                names.add(en.nextElement().getName());
            }
            assertEquals(List.of("META-INF/MANIFEST.MF", "asset.txt",
                            "com/example/Caller.class"), names,
                    "entry order preserved for what remains; all four signature blocks are dropped");
            // THE fix's point: no .SF remains for the JDK verifier to check against,
            // so every class entry reads clean instead of throwing SecurityException.
            for (String n : names) {
                if (n.endsWith(".class")) {
                    var e = jf.getEntry(n);
                    assertTrue(e != null, "class entry must be present");
                    try (var in0 = jf.getInputStream(e)) {
                        assertTrue(in0.readAllBytes().length > 0,
                                "class entry must read under JDK jar verification");
                    }
                }
            }
            Manifest outManifest = jf.getManifest();
            assertTrue(outManifest != null, "a sanitized manifest is still present");
            assertEquals("kept-main",
                    outManifest.getMainAttributes().getValue("X-Smoke"),
                    "main-section attributes must survive");
            assertEquals("kept-com/example/Caller.class",
                    outManifest.getEntries().get("com/example/Caller.class")
                            .getValue("X-Smoke"),
                    "non-digest per-entry attributes must survive");
            for (Attributes s : outManifest.getEntries().values()) {
                for (Object key : s.keySet()) {
                    assertFalse(key.toString().endsWith("-Digest"),
                            () -> "per-entry digest must be stripped, got " + key);
                }
            }
        }
        // (c) the rewritten class content is untouched by the un-signing.
        List<String> s = structure(outJar, "com/example/Caller");
        assertTrue(s.stream().anyMatch(l -> l.contains(WORLD_ITP)),
                "class refs still remap under the stripped signature:\n" + s);
        assertTrue(s.stream().anyMatch(l -> l.contains(SERVER_ITP)),
                "method refs still remap under the stripped signature:\n" + s);
    }

    /** No manifest to sanitize: the remapper must not invent one — unchanged behavior (b). */
    @Test
    void manifestLessJarStillRemapsUnchangedBehavior() throws IOException {
        DefaultMappingGraph g = loadedGraph();
        Node off = new Node("1.20-test", "official");
        Node itp = new Node("1.20-test", "intermediary");

        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("com/example/Caller.class", callerClass("com/example/Caller"));
        Path in = jarOf(tmp, "manifestless.jar", src);
        Path outJar = tmp.resolve("manifestless-out.jar");
        GraphJarRemapper.Result r = new GraphJarRemapper(g).remap(in, outJar, off, itp);

        assertEquals(4, r.symbolsTranslated(), "same symbols as the round-trip fixture");
        assertEquals(1, r.classesRemapped());
        try (var jf = new java.util.jar.JarFile(outJar.toFile())) {
            assertTrue(jf.getManifest() == null,
                    "a manifest must not be invented for a manifest-less jar");
        }
        List<String> s = structure(outJar, "com/example/Caller");
        assertTrue(s.stream().anyMatch(l -> l.contains(WORLD_ITP)),
                "content still remaps under a manifest-less jar:\n" + s);
    }
}
