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
 * The eventbus-transformation worker for the 1.16.5 legacy universe. Lives INSIDE the child
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

    private static boolean applyAccessorMixins(ClassLoader loader, ClassNode target) {
        List<AccessorMixin> mixins = accessorMixins(loader);
        boolean changed = false;
        Set<String> existing = new HashSet<String>(target.interfaces);
        for (AccessorMixin mixin : mixins) {
            if (!mixin.target.equals(target.name) || existing.contains(mixin.name)) continue;
            boolean usable = true;
            List<MethodNode> generated = new ArrayList<MethodNode>();
            for (MethodNode accessor : mixin.methods) {
                String field = mixin.fields.get(accessor.name + accessor.desc);
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
            if (!usable) continue;
            target.interfaces.add(mixin.name);
            target.methods.addAll(generated);
            existing.add(mixin.name);
            changed = true;
            debug("accessor mixin " + mixin.name + " -> " + target.name);
        }
        return changed;
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
        mv.visitCode();
        if (args.length == 0) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn((field.access & Opcodes.ACC_STATIC) != 0 ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                    owner, field.name, field.desc);
            mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(args[0].getOpcode(Opcodes.ILOAD), 1);
            mv.visitFieldInsn((field.access & Opcodes.ACC_STATIC) != 0 ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
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
                for (MethodNode method : node.methods) {
                    if (hasAnnotation(allAnnotations(method.visibleAnnotations,
                            method.invisibleAnnotations),
                            "Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                        methods.add(method);
                    }
                }
                if (methods.isEmpty()) continue;
                Map<String, String> fields = new HashMap<String, String>();
                for (MethodNode method : methods) {
                    String mapped = refmapField(refmaps.values(), node.name, method);
                    if (mapped != null) fields.put(method.name + method.desc, mapped);
                }
                out.add(new AccessorMixin(node.name, target, methods, fields));
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
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static String refmapField(Iterable<String> refmaps, String mixin, MethodNode method) {
        Pattern block = Pattern.compile("\\\"" + Pattern.quote(mixin)
                + "\\\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        List<String> names = new ArrayList<String>();
        names.add(method.name);
        if (method.name.startsWith("get") || method.name.startsWith("set")) {
            names.add(Character.toLowerCase(method.name.charAt(3)) + method.name.substring(4));
        }
        for (String json : refmaps) {
            Matcher bm = block.matcher(json);
            if (!bm.find()) continue;
            for (String name : names) {
                Pattern value = Pattern.compile("\\\"" + Pattern.quote(name)
                        + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
                Matcher vm = value.matcher(bm.group(1));
                if (vm.find()) {
                    String mapped = vm.group(1);
                    int colon = mapped.indexOf(':');
                    return colon < 0 ? mapped : mapped.substring(0, colon);
                }
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
        final List<MethodNode> methods;
        final Map<String, String> fields;

        AccessorMixin(String name, String target, List<MethodNode> methods,
                Map<String, String> fields) {
            this.name = name;
            this.target = target;
            this.methods = methods;
            this.fields = fields;
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
            // mutator.  Keeping it inside the child worker preserves ASM class identity.
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
