package dev.umb.legacy1165.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraftforge.eventbus.EventBusEngine;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;

import net.minecraftforge.common.asm.ObjectHolderDefinalize;
import net.minecraftforge.common.asm.RuntimeEnumExtender;

/**
 * universe (compiled against ASM + eventbus, loaded child-first by {@code Legacy1165Loader}),
 * so every reference here - ASM types, engine types, event supertypes - resolves to ONE
 * consistent copy. (The loader itself, which lives on the application side, must NOT touch ASM
 * or engine types directly: its symbolic references would resolve through the app loader and
 * collide with the child's copies. It calls this class reflectively - six lines - and never
 * links the transformed world itself.)
 *
 * <p>What it does, per class file: parse to a ClassNode; ask Forge's OWN
 * {@code EventBusEngine} (the engine ModLauncher's {@code "eventbus"} launch plugin drives in
 * production) whether it handles the type; if so, run {@code processClass} with the legacy
 * loader as TCCL (the transformer loads event superclasses through the TCCL, proven by its
 * source); rewrite with recomputed frames when changed. Returns null when nothing changed.
 * Never throws for transform causes: failures yield null (plain bytes) so classloading never
 * breaks for a transform.</p>
 */
public final class EventTransform {

    private static volatile boolean resolved = false;
    private static volatile boolean available = false;
    private static volatile ObjectHolderDefinalize holderPlugin;
    private static volatile RuntimeEnumExtender enumPlugin;
    private static final Map<ClassLoader, List<AccessorMixin>> ACCESSOR_CACHE =
            new java.util.WeakHashMap<ClassLoader, List<AccessorMixin>>();
    private static final Object ACCESSOR_LOCK = new Object();

    private EventTransform() {
    }

    /**
     * @param legacyLoader the isolated universe loader (used for TCCL + hierarchy resolution)
     * @param bytes the original class file
     * @return transformed bytes, or null when the engine does not handle the class or the
     *         transform changed nothing / failed
     */
    public static byte[] transform(ClassLoader legacyLoader, byte[] bytes) {
        try {
            ensureEngine();
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int flags = 0;
            // Accessor application is independent of Forge's optional EventBus engine. This
            // keeps a missing optional engine from suppressing mod-owned accessors.
            if (applyAccessorMixins(legacyLoader, node)) {
                flags |= 1;
            }
            if (available) {
                Type type = Type.getObjectType(node.name);
                if (EventBusEngine.INSTANCE.handlesClass(type)) {
                    Thread thread = Thread.currentThread();
                    ClassLoader previous = thread.getContextClassLoader();
                    thread.setContextClassLoader(legacyLoader);
                    try {
                        flags |= EventBusEngine.INSTANCE.processClass(node, type);
                    } finally {
                        thread.setContextClassLoader(previous);
                    }
                }
                if (holderPlugin != null
                        && !holderPlugin.handlesClass(type, false).isEmpty()) {
                    flags |= holderPlugin.processClassWithFlags(
                            ILaunchPluginService.Phase.AFTER, node, type, "legacy1165");
                }
                if (enumPlugin != null
                        && !enumPlugin.handlesClass(type, false).isEmpty()) {
                    flags |= enumPlugin.processClassWithFlags(
                            ILaunchPluginService.Phase.AFTER, node, type, "legacy1165");
                }
                if (node.name.contains("RegistryEvent")) {
                    debug("processClass(" + type.getClassName() + ")=" + flags);
                }
            }
            if (flags == 0) {
                return null;
            }
            ClassWriter writer = new LegacyClassWriter(legacyLoader, ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            return writer.toByteArray();
        } catch (LinkageError | RuntimeException e) {
            available = false;
            debug("transform failed", e);
            return null;
        }
    }

    /**
     * Two independent cases, because a STATIC {@code @Accessor}/{@code @Invoker} cannot be
     * dispatched the same way an instance one is:
     *
     * <p><b>Case 1 - {@code target} is the REAL class a mixin targets</b> (e.g. {@code TileEntity}
     * for {@code TileEntityAccess}): merge in the INSTANCE accessors/invokers by making
     * {@code target} implement the accessor interface and generating the interface's methods as
     * real members of {@code target} - exactly Mixin's own semantics (the target implements the
     * interface; its instance methods dispatch virtually to the generated field/method access).
     * A static Java interface method is never inherited or dispatched this way, so static
     * members are skipped here.</p>
     *
     * <p><b>Case 2 - {@code target} IS the accessor/invoker interface itself</b> (e.g.
     * {@code PotionBrewingAccess}): rewrite its OWN static methods' bodies in place, replacing
     * the "Replaced by Mixin" stub with real {@code GETSTATIC}/{@code PUTSTATIC}/
     * {@code INVOKESTATIC} bytecode against the mixin's target class. This is what the real
     * Mixin transformer does for a static accessor - it transforms the ACCESSOR CLASS, never the
     * target - and it is the path {@code PotionBrewingAccess.getConversions()} (a real, static,
     */
    private static boolean applyAccessorMixins(ClassLoader loader, ClassNode target) {
        List<AccessorMixin> mixins = accessorMixins(loader);
        boolean changed = false;

        // Case 1: instance accessors/invokers, merged into the real target class. Also widens
        // the ACCESS of any member a STATIC accessor/invoker (Case 2, below) needs: Case 2's
        // generated code lives on the SEPARATE accessor interface class (a different package,
        // almost always - e.g. blusunrize.immersiveengineering.mixin.accessors vs
        // net.minecraft.potion), so a plain GETSTATIC/INVOKESTATIC on a private member is
        // rejected by the JVM's OWN access-control verification regardless of bytecode
        // correctness (proven live: IllegalAccessError on PotionBrewing.field_185213_a before
        // this widening was added). Real Mixin does the same widening as part of applying any
        // mixin to a target class; it is not optional.
        Set<String> existing = new HashSet<String>(target.interfaces);
        for (AccessorMixin mixin : mixins) {
            if (!mixin.target.equals(target.name)) continue;
            if (widenStaticMemberAccess(mixin, target)) {
                changed = true;
            }
            if (existing.contains(mixin.name)) continue;
            boolean usable = true;
            List<MethodNode> generated = new ArrayList<MethodNode>();
            for (MethodNode accessor : mixin.methods) {
                if ((accessor.access & Opcodes.ACC_STATIC) != 0) continue; // Case 2 handles these
                String key = mixin.explicitNames.get(accessor.name + accessor.desc);
                // The refmap/annotation-derived name IS the field name (grounded: Mixin's own
                // refmap keys every accessor by its @Accessor value, e.g. "POTION_MIXES" ->
                // "field_185213_a:...", never by the accessor METHOD's own name). A method-name
                // guess is the fallback ONLY, for a mod that omits the value AND ships no refmap.
                String field = resolveName(mixin.refmaps, mixin.name, key, accessor.name);
                if (field == null) field = inferField(target, accessor);
                if (field == null) {
                    usable = false;
                    break;
                }
                FieldNode targetField = findField(target, field, accessor);
                if (targetField == null) {
                    usable = false;
                    break;
                }
                generated.add(accessorMethod(target.name, accessor, targetField));
            }
            if (usable) {
                for (MethodNode invoker : mixin.invokers) {
                    if ((invoker.access & Opcodes.ACC_STATIC) != 0) continue; // Case 2
                    String key = mixin.explicitNames.get(invoker.name + invoker.desc);
                    String defaultName = stripInvokerPrefix(invoker.name);
                    String methodName = resolveName(mixin.refmaps, mixin.name, key, defaultName);
                    if (methodName == null) methodName = defaultName;
                    MethodNode targetMethod = findMethodExact(target, methodName, invoker.desc);
                    if (targetMethod == null) {
                        usable = false;
                        break;
                    }
                    generated.add(invokerMethod(target.name, invoker, targetMethod));
                }
            }
            if (!usable || generated.isEmpty()) continue;
            target.interfaces.add(mixin.name);
            target.methods.addAll(generated);
            existing.add(mixin.name);
            changed = true;
            debug("accessor mixin " + mixin.name + " -> " + target.name);
        }

        // Case 2: static accessors/invokers, rewritten in place on the interface itself.
        for (AccessorMixin mixin : mixins) {
            if (!mixin.name.equals(target.name)) continue;
            for (MethodNode accessor : mixin.methods) {
                if ((accessor.access & Opcodes.ACC_STATIC) == 0) continue;
                MethodNode real = findMethodExact(target, accessor.name, accessor.desc);
                if (real == null) continue;
                String key = mixin.explicitNames.get(accessor.name + accessor.desc);
                String field = resolveName(mixin.refmaps, mixin.name, key, accessor.name);
                if (field == null) continue; // no live target ClassNode here to infer against
                rewriteStaticAccessorBody(real, mixin.target, field);
                changed = true;
                debug("static accessor " + mixin.name + "." + accessor.name + " -> "
                        + mixin.target + "." + field);
            }
            for (MethodNode invoker : mixin.invokers) {
                if ((invoker.access & Opcodes.ACC_STATIC) == 0) continue;
                MethodNode real = findMethodExact(target, invoker.name, invoker.desc);
                if (real == null) continue;
                String key = mixin.explicitNames.get(invoker.name + invoker.desc);
                String defaultName = stripInvokerPrefix(invoker.name);
                String methodName = resolveName(mixin.refmaps, mixin.name, key, defaultName);
                if (methodName == null) methodName = defaultName;
                rewriteStaticInvokerBody(real, mixin.target, methodName);
                changed = true;
                debug("static invoker " + mixin.name + "." + invoker.name + " -> "
                        + mixin.target + "." + methodName);
            }
        }
        return changed;
    }

    /**
     * Widens the access of every field/method a STATIC {@code @Accessor}/{@code @Invoker}
     * targeting {@code target} needs, from whatever it was (typically {@code private}) to
     * {@code public}. Required because the generated call site lives on the accessor interface
     * class, not {@code target} itself - see {@link #applyAccessorMixins}'s Case 1 javadoc.
     * Resolution mirrors Case 2 exactly (explicit annotation value, else refmap, else the
     * accessor's own default-derived name) so the two cases can never disagree about which
     * member they mean.
     */
    private static boolean widenStaticMemberAccess(AccessorMixin mixin, ClassNode target) {
        boolean changed = false;
        for (MethodNode accessor : mixin.methods) {
            if ((accessor.access & Opcodes.ACC_STATIC) == 0) continue;
            String key = mixin.explicitNames.get(accessor.name + accessor.desc);
            String field = resolveName(mixin.refmaps, mixin.name, key, accessor.name);
            if (field == null) continue;
            for (FieldNode f : target.fields) {
                if (f.name.equals(field) && f.desc.equals(accessorFieldDesc(accessor))
                        && (f.access & (Opcodes.ACC_PUBLIC)) == 0) {
                    f.access = (f.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                            | Opcodes.ACC_PUBLIC;
                    changed = true;
                }
            }
        }
        for (MethodNode invoker : mixin.invokers) {
            if ((invoker.access & Opcodes.ACC_STATIC) == 0) continue;
            String key = mixin.explicitNames.get(invoker.name + invoker.desc);
            String defaultName = stripInvokerPrefix(invoker.name);
            String methodName = resolveName(mixin.refmaps, mixin.name, key, defaultName);
            if (methodName == null) methodName = defaultName;
            MethodNode real = findMethodExact(target, methodName, invoker.desc);
            if (real != null && (real.access & Opcodes.ACC_PUBLIC) == 0) {
                real.access = (real.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PUBLIC;
                changed = true;
            }
        }
        return changed;
    }

    private static MethodNode findMethodExact(ClassNode owner, String name, String desc) {
        for (MethodNode m : owner.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    /** Instance {@code @Invoker}: calls the target's own (often private) instance method. */
    private static MethodNode invokerMethod(String owner, MethodNode source, MethodNode targetMethod) {
        MethodNode out = new MethodNode(Opcodes.ACC_PUBLIC, source.name, source.desc,
                source.signature, null);
        Type[] args = Type.getArgumentTypes(source.desc);
        Type ret = Type.getReturnType(source.desc);
        out.visitCode();
        out.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type arg : args) {
            out.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        // INVOKESPECIAL: same-class access to a (commonly private) sibling method, exactly the
        // accessibility @Invoker exists to bypass - not a virtual-dispatch redirect.
        out.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, targetMethod.name, targetMethod.desc, false);
        out.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        out.visitMaxs(0, 0);
        out.visitEnd();
        return out;
    }

    /**
     * Rewrites a static {@code @Accessor}'s OWN body in place (see the class javadoc: this is
     * the only way a static accessor can ever do real work, since a static interface method is
     * never inherited by an "implements" target).  Field existence is verified by the JVM at
     * class-load time like any other field reference, not looked up against a live ClassNode
     * here - an honest {@code NoSuchFieldError} beats a fabricated success.
     */
    private static void rewriteStaticAccessorBody(MethodNode accessor, String targetOwner, String fieldName) {
        Type[] args = Type.getArgumentTypes(accessor.desc);
        Type ret = Type.getReturnType(accessor.desc);
        accessor.instructions = new org.objectweb.asm.tree.InsnList();
        accessor.tryCatchBlocks = new ArrayList<org.objectweb.asm.tree.TryCatchBlockNode>();
        accessor.localVariables = null;
        if (args.length == 0) {
            accessor.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.GETSTATIC, targetOwner, fieldName, ret.getDescriptor()));
            accessor.instructions.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        } else {
            accessor.instructions.add(new org.objectweb.asm.tree.VarInsnNode(
                    args[0].getOpcode(Opcodes.ILOAD), 0));
            accessor.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.PUTSTATIC, targetOwner, fieldName, args[0].getDescriptor()));
            accessor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        }
    }

    /** Rewrites a static {@code @Invoker}'s OWN body in place (see {@link #rewriteStaticAccessorBody}). */
    private static void rewriteStaticInvokerBody(MethodNode invoker, String targetOwner, String methodName) {
        Type[] args = Type.getArgumentTypes(invoker.desc);
        Type ret = Type.getReturnType(invoker.desc);
        invoker.instructions = new org.objectweb.asm.tree.InsnList();
        invoker.tryCatchBlocks = new ArrayList<org.objectweb.asm.tree.TryCatchBlockNode>();
        invoker.localVariables = null;
        int slot = 0;
        for (Type arg : args) {
            invoker.instructions.add(new org.objectweb.asm.tree.VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        invoker.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
                Opcodes.INVOKESTATIC, targetOwner, methodName, invoker.desc, false));
        invoker.instructions.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(Opcodes.IRETURN)));
    }

    /**
     * Resolves an accessor/invoker's real target member name: the refmap entry keyed by the
     * EXPLICIT annotation value when both are present (the grounded, common case - every
     * accessor/invoker this project's real corpus uses gives an explicit {@code value()}); the
     * explicit value used directly when no refmap covers it (a non-obfuscated/MCP target, or a
     * dev-environment jar); the annotation-convention default name otherwise. Never the accessor
     * METHOD's own name - that was the root cause of every accessor in the real IE jar silently
     * failing to resolve (its refmap is keyed by "POTION_MIXES", never "getConversions").
     */
    private static String resolveName(Collection<String> refmaps, String mixinInternalName,
            String explicitValue, String defaultName) {
        String candidate = (explicitValue != null && !explicitValue.isEmpty())
                ? explicitValue : defaultName;
        String remapped = refmapField(refmaps, mixinInternalName, candidate);
        if (remapped != null) return remapped;
        return (explicitValue != null && !explicitValue.isEmpty()) ? explicitValue : null;
    }

    /**
     * Mixin's own default-name convention for an {@code @Invoker} with no explicit
     * {@code value()}: strip a leading {@code call}/{@code invoke}/{@code new} and decapitalize,
     * e.g. {@code callDoStuff -> doStuff}. Falls back to the accessor's own name unchanged when
     * it does not match any known prefix (an honest miss, not a guess).
     */
    private static String stripInvokerPrefix(String name) {
        for (String prefix : new String[] {"call", "invoke", "new"}) {
            if (name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length()))
                        + name.substring(prefix.length() + 1);
            }
        }
        return name;
    }

    private static FieldNode findField(ClassNode target, String name, MethodNode accessor) {
        for (FieldNode field : target.fields) {
            if (field.name.equals(name) && field.desc.equals(accessorFieldDesc(accessor))) {
                return field;
            }
        }
        return null;
    }

    private static String accessorFieldDesc(MethodNode method) {
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length == 0) return Type.getReturnType(method.desc).getDescriptor();
        if (args.length == 1 && Type.getReturnType(method.desc) == Type.VOID_TYPE) {
            return args[0].getDescriptor();
        }
        return null;
    }

    private static String inferField(ClassNode target, MethodNode accessor) {
        String desc = accessorFieldDesc(accessor);
        if (desc == null) return null;
        String name = accessor.name;
        if (name.startsWith("get") || name.startsWith("set")) {
            name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        for (FieldNode field : target.fields) {
            if (field.desc.equals(desc) && field.name.equals(name)) return field.name;
        }
        String only = null;
        for (FieldNode field : target.fields) {
            if (field.desc.equals(desc)) {
                if (only != null) return null;
                only = field.name;
            }
        }
        return only;
    }

    private static MethodNode accessorMethod(String owner, MethodNode source, FieldNode field) {
        MethodNode out = new MethodNode(Opcodes.ACC_PUBLIC, source.name, source.desc,
                source.signature, null);
        org.objectweb.asm.MethodVisitor mv = out;
        Type[] args = Type.getArgumentTypes(source.desc);
        Type ret = Type.getReturnType(source.desc);
        boolean staticField = (field.access & Opcodes.ACC_STATIC) != 0;
        mv.visitCode();
        if (args.length == 0) {
            // An INSTANCE accessor method may legitimately target a STATIC field (Mixin allows
            // it); GETSTATIC/PUTSTATIC need no receiver, so `this` (ALOAD 0) must be skipped or
            // it sits unconsumed on the stack - a stack-height mismatch the verifier rejects.
            if (!staticField) mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(staticField ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                    owner, field.name, field.desc);
            mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        } else {
            if (!staticField) mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(args[0].getOpcode(Opcodes.ILOAD), 1);
            mv.visitFieldInsn(staticField ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
                    owner, field.name, field.desc);
            mv.visitInsn(Opcodes.RETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return out;
    }

    private static List<AccessorMixin> accessorMixins(ClassLoader loader) {
        synchronized (ACCESSOR_LOCK) {
            List<AccessorMixin> cached = ACCESSOR_CACHE.get(loader);
            if (cached != null) return cached;
            List<AccessorMixin> found = new ArrayList<AccessorMixin>();
            if (loader instanceof URLClassLoader) {
                for (URL source : ((URLClassLoader) loader).getURLs()) {
                    if (!"file".equals(source.getProtocol())) continue;
                    File file;
                    try {
                        file = new File(source.toURI());
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
                    scanAccessorJar(file, found);
                }
            }
            List<AccessorMixin> immutable = java.util.Collections.unmodifiableList(found);
            ACCESSOR_CACHE.put(loader, immutable);
            return immutable;
        }
    }

    private static void scanAccessorJar(File file, List<AccessorMixin> out) {
        try (JarFile jar = new JarFile(file)) {
            Map<String, String> refmaps = new HashMap<String, String>();
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith("refmap.json")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        refmaps.put(entry.getName(), new String(readAll(in), StandardCharsets.UTF_8));
                    }
                }
            }
            entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().contains("$")) continue;
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = readAll(in);
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                        | ClassReader.SKIP_FRAMES);
                if ((node.access & Opcodes.ACC_INTERFACE) == 0) continue;
                String target = mixinTarget(allAnnotations(node.visibleAnnotations,
                        node.invisibleAnnotations));
                if (target == null) continue;
                List<MethodNode> methods = new ArrayList<MethodNode>();
                List<MethodNode> invokers = new ArrayList<MethodNode>();
                Map<String, String> explicitNames = new HashMap<String, String>();
                for (MethodNode method : node.methods) {
                    List<AnnotationNode> anns = allAnnotations(method.visibleAnnotations,
                            method.invisibleAnnotations);
                    AnnotationNode accessorAnn = findAnnotation(anns,
                            "Lorg/spongepowered/asm/mixin/gen/Accessor;");
                    AnnotationNode invokerAnn = findAnnotation(anns,
                            "Lorg/spongepowered/asm/mixin/gen/Invoker;");
                    if (accessorAnn != null) {
                        methods.add(method);
                        String value = annotationString(accessorAnn, "value");
                        if (value != null) explicitNames.put(method.name + method.desc, value);
                    } else if (invokerAnn != null) {
                        invokers.add(method);
                        String value = annotationString(invokerAnn, "value");
                        if (value != null) explicitNames.put(method.name + method.desc, value);
                    }
                }
                if (methods.isEmpty() && invokers.isEmpty()) continue;
                out.add(new AccessorMixin(node.name, target, methods, invokers, explicitNames,
                        java.util.Collections.unmodifiableList(
                                new ArrayList<String>(refmaps.values()))));
            }
        } catch (Throwable t) {
            debug("accessor scan failed for " + file, t);
        }
    }

    private static List<AnnotationNode> allAnnotations(List<AnnotationNode> visible,
            List<AnnotationNode> invisible) {
        if (visible == null || visible.isEmpty()) {
            return invisible == null ? java.util.Collections.<AnnotationNode>emptyList() : invisible;
        }
        if (invisible == null || invisible.isEmpty()) return visible;
        List<AnnotationNode> all = new ArrayList<AnnotationNode>(visible.size() + invisible.size());
        all.addAll(visible);
        all.addAll(invisible);
        return all;
    }

    private static String mixinTarget(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)
                    || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!"value".equals(annotation.values.get(i))) continue;
                Object value = annotation.values.get(i + 1);
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    Object first = ((List<?>) value).get(0);
                    if (first instanceof Type) return ((Type) first).getInternalName();
                } else if (value instanceof Type) {
                    return ((Type) value).getInternalName();
                }
            }
        }
        return null;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        return findAnnotation(annotations, desc) != null;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    /** A plain String-valued annotation member (both {@code @Accessor}/{@code @Invoker} shape). */
    private static String annotationString(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                Object value = annotation.values.get(i + 1);
                return value instanceof String ? (String) value : null;
            }
        }
        return null;
    }

    /**
     * Looks up ONE candidate name (the accessor/invoker's real, resolved target member key -
     * see {@link #resolveName}) inside the mixin's own refmap block. The refmap's own shape
     * (verified against the real {@code ImmersiveEngineering-refmap.json}) is
     * {@code "<mixin internal name>": {"<annotation value>": "<srg name>[:<desc>]"}} - a single
     * exact key lookup, never a method-name guess.
     */
    private static String refmapField(Iterable<String> refmaps, String mixin, String name) {
        if (name == null) return null;
        Pattern block = Pattern.compile("\\\"" + Pattern.quote(mixin)
                + "\\\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        for (String json : refmaps) {
            Matcher bm = block.matcher(json);
            if (!bm.find()) continue;
            Pattern value = Pattern.compile("\\\"" + Pattern.quote(name)
                    + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
            Matcher vm = value.matcher(bm.group(1));
            if (vm.find()) {
                String mapped = vm.group(1);
                int colon = mapped.indexOf(':');
                return colon < 0 ? mapped : mapped.substring(0, colon);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static final class AccessorMixin {
        final String name;
        final String target;
        /** {@code @Accessor} methods (field get/set), template nodes (no code - see the scan). */
        final List<MethodNode> methods;
        /** {@code @Invoker} methods (method redirect), template nodes (no code - see the scan). */
        final List<MethodNode> invokers;
        /** {@code name+desc -> @Accessor/@Invoker's own explicit value()}, when one was given. */
        final Map<String, String> explicitNames;
        /** This mixin's own jar's refmap file contents (may be empty - a dev/MCP-only mod). */
        final List<String> refmaps;

        AccessorMixin(String name, String target, List<MethodNode> methods,
                List<MethodNode> invokers, Map<String, String> explicitNames, List<String> refmaps) {
            this.name = name;
            this.target = target;
            this.methods = methods;
            this.invokers = invokers;
            this.explicitNames = explicitNames;
            this.refmaps = refmaps;
        }
    }

    private static void debug(String what) {
        debug(what, null);
    }

    private static void debug(String what, Throwable t) {
        if ("true".equals(System.getProperty("umb.debugTransform"))) {
            if (t == null) {
                System.err.println("[legacy1165-transform] worker: " + what);
            } else {
                System.err.println("[legacy1165-transform] worker: " + what + ": " + t);
                Throwable c = t.getCause();
                while (c != null) {
                    System.err.println("[legacy1165-transform] worker:   caused: " + c);
                    c = c.getCause();
                }
            }
        }
    }

    private static synchronized void ensureEngine() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            // Touching INSTANCE initializes the engine (needs log4j, like everything Forge).
            Object instance = EventBusEngine.INSTANCE;
            available = instance != null;
            // The Finalize plugin is stateless; one instance serves the universe.
            holderPlugin = new ObjectHolderDefinalize();
            // This is Forge's own launch-plugin implementation from the hash-verified
            // forge-1.16.5-36.2.34-launch.jar on classpath-1165.txt, not a hand-rolled enum
            enumPlugin = new RuntimeEnumExtender();
        } catch (LinkageError | RuntimeException e) {
            available = false;
            debug("engine unavailable", e);
        }
    }

    /**
     * ASM ClassWriter resolving common superclasses through the legacy universe instead of
     * whatever loader loaded this helper - the default implementation would resolve
     * Forge/vanilla supers from the wrong side and fail.
     */
    private static final class LegacyClassWriter extends ClassWriter {
        private final ClassLoader legacyLoader;

        LegacyClassWriter(ClassLoader legacyLoader, int flags) {
            super(flags);
            this.legacyLoader = legacyLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, legacyLoader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, legacyLoader);
                if (c1.isAssignableFrom(c2)) {
                    return type1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return type2;
                }
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    c1 = c1.getSuperclass();
                } while (!c1.isAssignableFrom(c2));
                return c1.getName().replace('.', '/');
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
