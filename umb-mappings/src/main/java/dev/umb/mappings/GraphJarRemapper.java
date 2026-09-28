package dev.umb.mappings;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import dev.umb.mappings.MappingGraph.MappingEdge;
import dev.umb.mappings.MappingGraph.Node;
import dev.umb.mappings.MappingGraph.Symbol;
import dev.umb.mappings.MappingGraph.SymbolKind;

/**
 * Graph-driven jar remapper (pipeline stage "Remap"): rewrites every statically
 * linked reference in a mod jar from one {@code (version, namespace)} node of the
 * canonical mapping graph to another, using {@link MappingGraph#translate} for
 * each symbol so multi-hop provenance-scored paths work exactly as everywhere
 * else in UMB.
 *
 * <p>Classfile rewriting is delegated to ASM's {@link ClassRemapper}, which handles
 * descriptors, signatures (generics), annotations, exception tables and stack map
 * frames through four overrides we provide: type names, method refs, field refs.
 * The input's frames are copied through the remapper rather than recomputed —
 * recomputation would require resolving the whole class hierarchy against classes
 * that may not be on any classpath here (mods reference an absent Minecraft host).
 *
 * <p>Semantics at unmappable symbols follow ASM's contract: return the name
 * unchanged and count it. A result with {@code symbolsTranslated == 0} on a jar
 * that contains classes is reported by the CLI as suspicious (D4: never a silent
 * empty pass), because it almost always means the wrong namespaces were asked for.
 *
 * <p>v0 scope notes (deliberate): non-class entries are copied verbatim, so
 * {@code META-INF/services/*} provider files keep source-namespace names (counted
 * as warnings); {@code META-INF/versions/} overlay classes end in {@code .class}
 * and therefore get the same remap pass as the base entries, and class ENTRY
 * PATHS are renamed together with {@code this_class} (the JVM locates a class by
 * zip path and defines it by the bytecode's own name — a mismatch breaks loading;
 * found by the real-1.20.1 client.jar smoke, where 104k symbols were rewritten
 * under stale obf paths). A jar-signatured input is un-signed on remap: the
 * {@code META-INF/*.SF/.RSA/.DSA/.EC} signature blocks are dropped and the
 * manifest re-emitted WITHOUT its per-entry digests — otherwise the JDK's
 * JarFile recomputes a digest against bytes we just rewrote and throws
 * SecurityException on every read (D10: a remapped jar must be loadable as-is;
 * found by the real-1.21.1 smoke, where a remapped client.jar's classes were
 * already unreadable). Mixin refmaps are
 * data files — they belong to the M8 mixin translation pass, not to name
 * remapping. Entry order is preserved for reproducible output.
 */
public final class GraphJarRemapper {

    /** Outcome report; counts let callers enforce no-vacuous-success themselves. */
    public record Result(
            int entriesCopied,
            int classesRemapped,
            int symbolsTranslated,
            int symbolsUnmapped,
            List<String> warnings) {}

    private final MappingGraph graph;

    public GraphJarRemapper(MappingGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /**
     * Remaps {@code inJar} writing {@code outJar}. The original file is never
     * touched (spec §24); callers pass a cache-staged output path.
     *
     * @throws IOException on unreadable input or unwritable output; a malformed
     *                      single class inside a third-party jar is tolerated per
     *                      spec §131 (copied verbatim + warning), not fatal — one
     *                      broken member must not lose the other 10k entries
     */
    public Result remap(Path inJar, Path outJar, Node from, Node to) throws IOException {
        Objects.requireNonNull(inJar, "inJar");
        Objects.requireNonNull(outJar, "outJar");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (inJar.equals(outJar)) {
            throw new IllegalArgumentException("inJar == outJar: output must be a new file");
        }

        SymbolLookup lookup = new SymbolLookup(from, to);
        List<String> warnings = new ArrayList<>();

        Files.createDirectories(outJar.toAbsolutePath().getParent());
        Path staging = outJar.resolveSibling(
                outJar.getFileName() + ".staging-" + Long.toUnsignedString(System.nanoTime()));
        int entriesCopied = 0;
        int classesRemapped = 0;
        try (JarFile jf = new JarFile(inJar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(staging))) {
            Manifest manifest = sanitizeManifest(jf.getManifest());
            // Write manifest first when present (JarInputStream consumers expect it),
            // then every remaining entry in original order.
            byte[] buffer = new byte[8192];
            boolean manifestWritten = false;
            for (java.util.Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (e.getName().equals("META-INF/MANIFEST.MF")) {
                    continue;
                }
                if (!manifestWritten && manifest != null) {
                    writeManifest(jos, manifest);
                    manifestWritten = true;
                }
                String name = e.getName();
                if (isSignatureFile(name)) {
                    // Stale signature blocks must not ride along: after this pass the
                    // bytes no longer match the digests the ORIGINAL signer computed,
                    // and the JDK's JarFile verifies on read — SecurityException on
                    // every class open (D10: a remapped jar must be loadable as-is).
                    continue;
                }
                if (name.endsWith(".class")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        byte[] remappedClass = remapClass(in, lookup);
                        // The entry path must follow the rewritten this_class — a jar
                        // whose cmm.class content claims net/minecraft/.../Level cannot
                        // load. Same lookup key the content pass already resolved, so
                        // this is a cache hit (no extra path searches, no double count).
                        writeEntry(jos, remappedEntryName(name, lookup), remappedClass, e.getTime());
                        classesRemapped++;
                    } catch (IOException | RuntimeException malformedMember) {
                        // Untrusted input tolerance (spec §131): copy verbatim, warn.
                        // The message rides along because a silently-swallowed cause
                        // once masked a real bug during bring-up (D4 lesson).
                        warnings.add("unparsable class copied verbatim: " + name
                                + " (" + malformedMember + ")");
                        try (InputStream in = jf.getInputStream(e)) {
                            copyEntry(jos, e, in, buffer);
                            entriesCopied++;
                        }
                    }
                    continue;
                }
                if (name.startsWith("META-INF/services/") && name.length() > "META-INF/services/".length()) {
                    warnings.add("service file kept source-namespace names (not yet rewritten): " + name);
                }
                try (InputStream in = jf.getInputStream(e)) {
                    copyEntry(jos, e, in, buffer);
                    entriesCopied++;
                }
            }
            if (!manifestWritten && manifest != null) {
                writeManifest(jos, manifest);
            }
        }

        // Atomic-ish publish: rename over any previous output only after the whole
        // archive was written without I/O failure.
        Files.move(staging, outJar, StandardCopyOption.REPLACE_EXISTING);
        return new Result(entriesCopied, classesRemapped,
                lookup.translated, lookup.unmapped, List.copyOf(warnings));
    }

    private byte[] remapClass(InputStream classBytes, SymbolLookup lookup) throws IOException {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0); // frames copied through, not recomputed
        cr.accept(new ClassRemapper(cw, lookup.asRemapper()), ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Zip path for a remapped class: the class's own name went through the same
     * lookup during content remapping (this_class is a CLASS symbol), so this is
     * a cache hit; an untranslatable name (synthetic/anonymous {@code cmm$1})
     * keeps its path, staying consistent with the references to it that were
     * likewise kept. {@code META-INF/versions/} overlay prefixes survive so the
     * overlay still overrides the same base class.
     */
    private String remappedEntryName(String entryName, SymbolLookup lookup) {
        String className = entryName.substring(0, entryName.length() - ".class".length());
        String prefix = "";
        if (className.startsWith("META-INF/versions/")) {
            int slash = className.indexOf('/', "META-INF/versions/".length());
            if (slash >= 0) {
                prefix = className.substring(0, slash + 1);
                className = className.substring(slash + 1);
            }
        }
        return prefix + lookup.mapClassName(className) + ".class";
    }

    /**
     * Per-run translator cache: whole-pool audits showed per-symbol path searches
     * dominate cost, and a remap pass asks for overlapping symbol sets repeatedly
     * (descriptors re-resolve the same classes). Cache lives for one remap call;
     * the underlying graph may gain edges between calls, and stale translations
     * across runs would be invisible bugs.
     */
    private final class SymbolLookup {
        private final Node from;
        private final Node to;
        private final Map<Symbol, Optional<String>> cache = new HashMap<>();
        int translated;
        int unmapped;

        SymbolLookup(Node from, Node to) {
            this.from = from;
            this.to = to;
        }

        /** CLASS-kind translation for entry renaming; same cache, fallback = input. */
        String mapClassName(String internalName) {
            return translate(SymbolKind.CLASS, null, internalName, null, internalName);
        }

        Remapper asRemapper() {
            // ASM 9.9 deprecates the no-arg Remapper() in favor of passing the api level.
            return new Remapper(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public String map(String internalName) {
                    return translate(SymbolKind.CLASS, null, internalName, null, internalName);
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return translate(SymbolKind.METHOD, owner, name, descriptor, name);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return translate(SymbolKind.FIELD, owner, name, descriptor, name);
                }
            };
        }

        private String translate(SymbolKind kind, String owner, String name,
                                 String desc, String fallback) {
            Symbol key = new Symbol(from, kind, owner, name, desc);
            Optional<String> hit = cache.get(key);
            if (hit != null) {
                // Cache stores "resolved" vs "known-unmappable" as present/empty.
                // An empty hit must fall back, NOT unwrap — Optional.get() here once
                // threw mid-accept() on every revisited unmappable symbol and got
                // the whole classfile condemned as unparsable (spec §131 catch).
                return hit.isPresent() ? hit.get() : fallback;
            }
            Optional<String> result;
            try {
                Optional<List<MappingEdge>> path = graph.translate(key, to);
                result = path.map(p -> GraphJarRemapper.terminalName(p, key));
            } catch (RuntimeException badSymbolShape) {
                // translate validates its inputs; a weird-but-real bytecode shape
                // must not abort a whole-jar remap.
                result = Optional.empty();
            }
            if (result.isPresent()) {
                translated++;
            } else {
                unmapped++;
            }
            cache.put(key, result);
            return result.orElse(fallback);
        }
    }

    /**
     * Follows the returned edge chain from {@code start} to its far end. Edges are
     * traversable in either direction, and which endpoint is "next" depends on
     * where we currently stand — so the start symbol is required input, not an
     * implementation detail of the path.
     */
    private static String terminalName(List<MappingEdge> path, Symbol start) {
        if (path.isEmpty()) {
            throw new IllegalStateException("empty translate path is not a valid result");
        }
        Symbol cur = start;
        for (MappingEdge e : path) {
            if (e.to().equals(cur)) {
                cur = e.from();
            } else if (e.from().equals(cur)) {
                cur = e.to();
            } else {
                throw new IllegalStateException(
                        "translate path broke continuity at: " + e);
            }
        }
        return cur.name();
    }

    // ------------------------------------------------------------------ jar plumbing

    private static void writeManifest(JarOutputStream jos, Manifest manifest) throws IOException {
        JarEntry e = new JarEntry("META-INF/MANIFEST.MF");
        e.setTime(System.currentTimeMillis());
        jos.putNextEntry(e);
        manifest.write(jos);
        jos.closeEntry();
    }

    /**
     * Strips the per-entry digest sections a JAR signature leaves behind. The
     * signature blocks themselves are dropped (see {@link #isSignatureFile}), so a
     * manifest that still declared SHA-*-Digest values for entries whose bytes were
     * just rewritten would make the JDK's JarFile verification fail on read. The
     * main section (Manifest-Version, Main-Class, ...) and any non-digest per-entry
     * attributes (Sealed, X-* metadata) survive; a section left empty after the
     * strip is dropped. A jar with no manifest yields null — nothing is invented.
     */
    private static Manifest sanitizeManifest(Manifest in) {
        if (in == null) {
            return null;
        }
        Manifest out = new Manifest();
        out.getMainAttributes().putAll(in.getMainAttributes());
        for (var section : in.getEntries().entrySet()) {
            Attributes clean = new Attributes();
            for (var attr : section.getValue().entrySet()) {
                if (attr.getKey().toString().endsWith("-Digest")) {
                    continue;
                }
                clean.put(attr.getKey(), attr.getValue());
            }
            if (!clean.isEmpty()) {
                out.getEntries().put(section.getKey(), clean);
            }
        }
        return out;
    }

    /**
     * The {.SF, .RSA, .DSA, .EC} files a signed jar carries under META-INF/ (the
     * signature file plus its signer block). A remapped artifact is no longer
     * signed by the original signer — carrying them over unchanged is exactly
     * what makes the JDK reject the rewritten bytes against the dead digests.
     */
    private static boolean isSignatureFile(String name) {
        return name.startsWith("META-INF/")
                && (name.endsWith(".SF") || name.endsWith(".RSA")
                || name.endsWith(".DSA") || name.endsWith(".EC"));
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] bytes, long time)
            throws IOException {
        JarEntry e = new JarEntry(name);
        e.setTime(time > 0 ? time : System.currentTimeMillis());
        jos.putNextEntry(e);
        jos.write(bytes);
        jos.closeEntry();
    }

    private static void copyEntry(JarOutputStream jos, JarEntry src, InputStream in, byte[] buffer)
            throws IOException {
        JarEntry e = new JarEntry(src.getName());
        e.setTime(src.getTime());
        jos.putNextEntry(e);
        int n;
        while ((n = in.read(buffer)) >= 0) {
            jos.write(buffer, 0, n);
        }
        jos.closeEntry();
    }
}
