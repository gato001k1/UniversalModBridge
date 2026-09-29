package dev.umb.legacy.boot;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** UMB-authored install-time remapper for the official obfuscated 1.7.10 client. */
public final class ClientSrgifier {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 6 && args.length != 7) throw new IllegalArgumentException("usage: ClientSrgifier <joined.srg> <client.jar> <out.jar> [forge.jar classpath.txt workdir [mod.jar,...]]");
        Maps maps = Maps.read(Path.of(args[0]));
        Patcher patcher = args.length >= 6
                ? Patcher.create(Path.of(args[1]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]), args.length == 7 ? parseMods(args[6]) : List.of())
                : null;
        Remapper remapper = new Remapper() {
            @Override public String map(String name) { return maps.mapClass(name); }
            @Override public String mapFieldName(String owner, String name, String descriptor) {
                String rawOwner = maps.unmapClass(owner);
                return maps.fields.getOrDefault(rawOwner + "/" + name, name);
            }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                String hit = maps.methods.get(owner + "/" + name + " " + descriptor);
                String rawOwner = maps.unmapClass(owner);
                if (hit == null) hit = maps.methods.get(rawOwner + "/" + name + " " + descriptor);
                if (hit == null) hit = maps.methods.get(rawOwner + "/" + name + " " + maps.unmapDescriptor(descriptor));
                return hit == null ? name : hit;
            }
        };
        remap(Path.of(args[1]), Path.of(args[2]), remapper, patcher);
    }

    private static List<Path> parseMods(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    private static void remap(Path input, Path output, Remapper remapper, Patcher patcher) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Set<String> written = new HashSet<>();
        try (JarFile in = new JarFile(input.toFile()); JarOutputStream out = new JarOutputStream(new FileOutputStream(output.toFile()))) {
            Enumeration<JarEntry> all = in.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.isDirectory()) continue;
                String upper = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")) continue;
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    if (patcher != null) {
                        String rawName = name.substring(0, name.length() - 6);
                        bytes = patcher.apply(rawName, remapper.map(rawName), bytes);
                    }
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    name = remapper.map(reader.getClassName()) + ".class";
                    bytes = writer.toByteArray();
                    if (patcher != null) bytes = patcher.applyPost(name.substring(0, name.length() - 6), bytes);
                    // A LaunchWrapper DEBUG_SAVE capture exposes the transformed Minecraft
                    // surface to the legacy side. Reproduce that boundary for cold builds:
                    // Forge/legacy sources must be able to link against transformed vanilla
                    // classes without depending on a machine-local captured runtime.
                    bytes = widenRuntimeAccess(bytes);
                }
                if (!written.add(name)) continue;
                JarEntry copy = new JarEntry(name);
                copy.setTime(0L);
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

    private static byte[] widenRuntimeAccess(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(org.objectweb.asm.Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        node.access = publicAccess(node.access);
        for (org.objectweb.asm.tree.FieldNode field : node.fields) field.access = publicAccess(field.access);
        for (org.objectweb.asm.tree.MethodNode method : node.methods) if (method.name.equals("<init>")) method.access = publicAccess(method.access);
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    private static int publicAccess(int access) {
        return (access & ~(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_PROTECTED)) | org.objectweb.asm.Opcodes.ACC_PUBLIC;
    }

    private static final class Patcher {
        private final Object transformer;
        private final java.lang.reflect.Method transform;
        private final List<Object> postTransformers;
        private final List<java.lang.reflect.Method> postMethods;

        static Patcher create(Path client, Path forge, Path classpathFile, Path workdir, List<Path> mods) throws Exception {
            List<URL> urls = new ArrayList<>();
            urls.add(client.toUri().toURL()); urls.add(forge.toUri().toURL());
            // The installer materializes classpath.txt using File.pathSeparator.  Reuse the
            // shared parser so the patcher accepts both that native form and the launcher's
            // historical semicolon/newline form on every OS.
            for (File entry : LegacyClasspath.read(classpathFile.toFile())) urls.add(entry.toURI().toURL());
            LaunchClassLoader loader = new LaunchClassLoader(urls.toArray(URL[]::new));
            Launch.minecraftHome = workdir.toFile(); Launch.assetsDir = workdir.toFile(); Launch.classLoader = loader;
            java.lang.reflect.Method build = Class.forName("cpw.mods.fml.relauncher.FMLInjectionData").getDeclaredMethod("build", File.class, LaunchClassLoader.class);
            build.setAccessible(true); build.invoke(null, workdir.toFile(), loader);
            Class<?> sideType = Class.forName("cpw.mods.fml.relauncher.Side"); Object server = Enum.valueOf((Class) sideType, "CLIENT");
            Class<?> relaunchLog = Class.forName("cpw.mods.fml.relauncher.FMLRelaunchLog");
            java.lang.reflect.Field side = relaunchLog.getDeclaredField("side"); side.setAccessible(true); side.set(null, server);
            java.lang.reflect.Field home = relaunchLog.getDeclaredField("minecraftHome"); home.setAccessible(true); home.set(null, workdir.toFile());
            Class<?> manager = Class.forName("cpw.mods.fml.common.patcher.ClassPatchManager"); Object instance = manager.getField("INSTANCE").get(null);
            manager.getMethod("setup", sideType).invoke(instance, server);
            List<Object> post = List.of();
            Object t = Class.forName("cpw.mods.fml.common.asm.transformers.PatchingTransformer").getConstructor().newInstance();
            java.lang.reflect.Method transform = t.getClass().getMethod("transform", String.class, String.class, byte[].class);
            List<java.lang.reflect.Method> postMethods = List.of();
            List<AccessRule> accessRules = new ArrayList<>();
            accessRules.addAll(readConfigAccessRules(forge, "forge_at.cfg"));
            accessRules.addAll(readConfigAccessRules(forge, "fml_at.cfg"));
            accessRules.addAll(readAccessRules(mods));
            return new Patcher(t, transform, post, postMethods, accessRules);
        }

        private static List<AccessRule> readAccessRules(List<Path> mods) throws Exception {
            List<AccessRule> rules = new ArrayList<>();
            for (Path mod : mods) try (JarFile jar = new JarFile(mod.toFile())) {
                Manifest manifest = jar.getManifest(); if (manifest == null) continue;
                String files = manifest.getMainAttributes().getValue("FMLAT"); if (files == null) continue;
                for (String file : files.split("\\s+")) {
                    JarEntry entry = jar.getJarEntry("META-INF/" + file); if (entry == null) continue;
                    try (InputStream in = jar.getInputStream(entry)) { addConfigAccessRules(rules, new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
                }
            }
            return rules;
        }
        private static List<AccessRule> readConfigAccessRules(Path jarPath, String entryName) throws Exception {
            List<AccessRule> rules = new ArrayList<>();
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry != null) try (InputStream in = jar.getInputStream(entry)) { addConfigAccessRules(rules, new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            }
            return rules;
        }
        private static void addConfigAccessRules(List<AccessRule> rules, String text) {
            for (String line : text.split("\\R")) {
                int comment = line.indexOf('#'); if (comment >= 0) line = line.substring(0, comment);
                String[] p = line.trim().split("\\s+"); if (p.length < 2) continue;
                String owner = p[1]; String member = p.length >= 3 ? p[2] : "*";
                String descriptor = ""; int paren = member.indexOf('('); if (paren >= 0) { descriptor = member.substring(paren).replace('.', '/'); member = member.substring(0, paren); }
                rules.add(new AccessRule(owner.replace('.', '/'), member, descriptor, p[0]));
            }
        }

        private Patcher(Object transformer, java.lang.reflect.Method transform, List<Object> postTransformers, List<java.lang.reflect.Method> postMethods, List<AccessRule> accessRules) { this.transformer = transformer; this.transform = transform; this.postTransformers = postTransformers; this.postMethods = postMethods; this.accessRules = accessRules; }
        private final List<AccessRule> accessRules;
        byte[] apply(String rawName, String transformedName, byte[] bytes) throws Exception {
            Object result = transform.invoke(transformer, rawName.replace('/', '.'), transformedName.replace('/', '.'), bytes);
            return result instanceof byte[] ? (byte[]) result : bytes;
        }
        byte[] applyPost(String name, byte[] bytes) throws Exception {
            byte[] current = bytes;
            for (int i = 0; i < postTransformers.size(); i++) {
                Object result = postMethods.get(i).invoke(postTransformers.get(i), name.replace('/', '.'), name.replace('/', '.'), current);
                if (result instanceof byte[]) current = (byte[]) result;
            }
            if (name.equals("net/minecraft/item/ItemStack")) current = applyItemStackShim(current);
            if (name.equals("net/minecraft/item/Item")) current = applyItemShim(current);
            if (name.equals("net/minecraft/util/StringTranslate")) current = applyStringTranslateShim(current);
            if (name.equals("net/minecraft/creativetab/CreativeTabs")) current = applyCreativeTabsShim(current);
            if (!accessRules.isEmpty()) current = applyAccessRules(name, current);
            return current;
        }
        private static byte[] applyItemShim(byte[] bytes) {
            final String item = "net/minecraft/item/Item";
            final String delegate = "Lcpw/mods/fml/common/registry/RegistryDelegate;";
            final String registry = "cpw/mods/fml/common/registry/FMLControlledNamespacedRegistry";
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(org.objectweb.asm.Opcodes.ASM9);
            new ClassReader(bytes).accept(node, 0);
            boolean hasDelegate = node.fields.stream().anyMatch(f -> f.name.equals("delegate") && f.desc.equals(delegate));
            if (!hasDelegate) node.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL, "delegate", delegate, null, null));
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode put)
                            || put.getOpcode() != org.objectweb.asm.Opcodes.PUTSTATIC
                            || !put.owner.equals(item) || !put.name.equals("field_150901_e")) continue;
                    org.objectweb.asm.tree.AbstractInsnNode ctor = put.getPrevious();
                    org.objectweb.asm.tree.AbstractInsnNode dup = ctor == null ? null : ctor.getPrevious();
                    org.objectweb.asm.tree.AbstractInsnNode fresh = dup == null ? null : dup.getPrevious();
                    if (ctor instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("<init>")
                            && dup != null && dup.getOpcode() == org.objectweb.asm.Opcodes.DUP
                            && fresh != null && fresh.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                        method.instructions.remove(ctor); method.instructions.remove(dup); method.instructions.remove(fresh);
                        method.instructions.insertBefore(put, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "cpw/mods/fml/common/registry/GameData", "getItemRegistry", "()Lcpw/mods/fml/common/registry/FMLControlledNamespacedRegistry;", false));
                    }
                    break;
                }
            }
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!method.name.equals("<init>") || !method.desc.equals("()V")) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof org.objectweb.asm.tree.MethodInsnNode call)
                            || call.getOpcode() != org.objectweb.asm.Opcodes.INVOKESPECIAL
                            || !call.owner.equals("java/lang/Object") || !call.name.equals("<init>")) continue;
                    org.objectweb.asm.tree.InsnList init = new org.objectweb.asm.tree.InsnList();
                    init.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    init.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, item, "field_150901_e", "Lnet/minecraft/util/RegistryNamespaced;"));
                    init.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, registry));
                    init.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    init.add(new org.objectweb.asm.tree.LdcInsnNode(org.objectweb.asm.Type.getObjectType(item)));
                    init.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, registry, "getDelegate", "(Ljava/lang/Object;Ljava/lang/Class;)Lcpw/mods/fml/common/registry/RegistryDelegate;", false));
                    init.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, item, "delegate", delegate));
                    method.instructions.insert(call, init);
                    break;
                }
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        }
        private static byte[] applyStringTranslateShim(byte[] bytes) {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(org.objectweb.asm.Opcodes.ASM9);
            new ClassReader(bytes).accept(node, 0);
            boolean hasMethod = node.methods.stream().anyMatch(m -> m.name.equals("parseLangFile") && m.desc.equals("(Ljava/io/InputStream;)Ljava/util/HashMap;"));
            if (!hasMethod) {
                org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "parseLangFile", "(Ljava/io/InputStream;)Ljava/util/HashMap;", "(Ljava/io/InputStream;)Ljava/util/HashMap<Ljava/lang/String;Ljava/lang/String;>;", null);
                org.objectweb.asm.tree.InsnList code = method.instructions;
                code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/util/HashMap"));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
                code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, 1));
                code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.NEW, "java/util/Properties"));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
                code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/util/Properties", "<init>", "()V", false));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, 2));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 2));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/util/Properties", "load", "(Ljava/io/InputStream;)V", false));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 2));
                code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "putAll", "(Ljava/util/Map;)V", false));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
                method.maxStack = 2; method.maxLocals = 3; node.methods.add(method);
            }
            boolean hasInject = node.methods.stream().anyMatch(m -> m.name.equals("inject") && m.desc.equals("(Ljava/io/InputStream;)V"));
            if (!hasInject) {
                org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "inject", "(Ljava/io/InputStream;)V", null, null);
                method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/util/StringTranslate", "parseLangFile", "(Ljava/io/InputStream;)Ljava/util/HashMap;", false));
                method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/util/StringTranslate", "func_135063_a", "(Ljava/util/Map;)V", false));
                method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                method.maxStack = 1; method.maxLocals = 1; node.methods.add(method);
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES); node.accept(writer); return writer.toByteArray();
        }
        private static byte[] applyCreativeTabsShim(byte[] bytes) {
            final String tabs = "net/minecraft/creativetab/CreativeTabs";
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(org.objectweb.asm.Opcodes.ASM9);
            new ClassReader(bytes).accept(node, 0);
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!method.name.equals("<init>") || !method.desc.equals("(ILjava/lang/String;)V")) continue;
                org.objectweb.asm.tree.InsnList code = method.instructions;
                code.clear();
                org.objectweb.asm.tree.LabelNode registered = new org.objectweb.asm.tree.LabelNode();
                org.objectweb.asm.tree.LabelNode copyDone = new org.objectweb.asm.tree.LabelNode();
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.LdcInsnNode("items.png"));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, tabs, "field_78043_p", "Ljava/lang/String;"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, tabs, "field_78042_q", "Z"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, tabs, "field_78041_r", "Z"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 1));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARRAYLENGTH));
                code.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IF_ICMPLT, registered));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 1));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IADD));
                code.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.ANEWARRAY, tabs));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, 3));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ISTORE, 4));
                org.objectweb.asm.tree.LabelNode copyLoop = new org.objectweb.asm.tree.LabelNode();
                code.add(copyLoop);
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 4));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARRAYLENGTH));
                code.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IF_ICMPGE, copyDone));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 3));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 4));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 4));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.AALOAD));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.AASTORE));
                code.add(new org.objectweb.asm.tree.IincInsnNode(4, 1));
                code.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.GOTO, copyLoop));
                code.add(copyDone);
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 3));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                code.add(registered);
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 1));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, tabs, "field_78033_n", "I"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 2));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, tabs, "field_78034_o", "Ljava/lang/String;"));
                code.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ILOAD, 1));
                code.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.AASTORE));
                code.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                method.maxStack = 4; method.maxLocals = 5;
            }
            boolean hasNextId = node.methods.stream().anyMatch(m -> m.name.equals("getNextID") && m.desc.equals("()I"));
            if (!hasNextId) {
                org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "getNextID", "()I", null, null);
                method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, tabs, "field_78032_a", "[Lnet/minecraft/creativetab/CreativeTabs;"));
                method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARRAYLENGTH));
                method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
                method.maxStack = 1; node.methods.add(method);
            }
            boolean hasStringCtor = node.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/lang/String;)V"));
            if (!hasStringCtor) {
                org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
                method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, tabs, "getNextID", "()I", false));
                method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1));
                method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESPECIAL, tabs, "<init>", "(ILjava/lang/String;)V", false));
                method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                method.maxStack = 3; method.maxLocals = 2; node.methods.add(method);
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES); node.accept(writer); return writer.toByteArray();
        }
        private static byte[] applyItemStackShim(byte[] bytes) {
            final String item = "net/minecraft/item/Item";
            final String stack = "net/minecraft/item/ItemStack";
            final String delegate = "Lcpw/mods/fml/common/registry/RegistryDelegate;";
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode(org.objectweb.asm.Opcodes.ASM9);
            new ClassReader(bytes).accept(node, 0);
            boolean hasDelegate = node.fields.stream().anyMatch(f -> f.name.equals("delegate") && f.desc.equals(delegate));
            if (!hasDelegate) node.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "delegate", delegate, null, null));
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (method.name.equals("func_77973_b") && method.desc.equals("()Lnet/minecraft/item/Item;")) {
                    method.instructions.clear(); org.objectweb.asm.tree.LabelNode fallback = new org.objectweb.asm.tree.LabelNode();
                    method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETFIELD, stack, "delegate", delegate));
                    method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IFNULL, fallback));
                    method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETFIELD, stack, "delegate", delegate));
                    method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "cpw/mods/fml/common/registry/RegistryDelegate", "get", "()Ljava/lang/Object;", true));
                    method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, item));
                    method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
                    method.instructions.add(fallback);
                    method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
                    method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETFIELD, stack, "field_151002_e", "Lnet/minecraft/item/Item;"));
                    method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
                } else if (method.name.equals("func_150996_a") && method.desc.equals("(Lnet/minecraft/item/Item;)V")) {
                    org.objectweb.asm.tree.LabelNode noItem = new org.objectweb.asm.tree.LabelNode(); org.objectweb.asm.tree.LabelNode set = new org.objectweb.asm.tree.LabelNode(); org.objectweb.asm.tree.InsnList prefix = new org.objectweb.asm.tree.InsnList();
                    prefix.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0)); prefix.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1)); prefix.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IFNULL, noItem)); prefix.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1)); prefix.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETFIELD, item, "delegate", delegate)); prefix.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.GOTO, set)); prefix.add(noItem); prefix.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL)); prefix.add(set); prefix.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, stack, "delegate", delegate)); method.instructions.insert(prefix);
                }
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES); node.accept(writer); return writer.toByteArray();
        }
        private byte[] applyAccessRules(String name, byte[] bytes) {
            ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(0);
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                @Override public void visit(int version, int access, String className, String signature, String superName, String[] interfaces) {
                    super.visit(version, adjust(className, "*", "", access), className, signature, superName, interfaces);
                }
                @Override public org.objectweb.asm.FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
                    return super.visitField(adjust(name, fieldName, "", access), fieldName, descriptor, signature, value);
                }
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                    return super.visitMethod(adjust(name, methodName, descriptor, access), methodName, descriptor, signature, exceptions);
                }
                private int adjust(String owner, String member, String descriptor, int access) {
                    int current = access;
                    for (AccessRule rule : accessRules) if (rule.matches(owner, member, descriptor)) current = rule.apply(current);
                    return current;
                }
            }, 0);
            return writer.toByteArray();
        }
    }

    private record AccessRule(String owner, String member, String descriptor, String operation) {
        boolean matches(String candidateOwner, String candidateMember, String candidateDescriptor) {
            return (owner.equals("*") || owner.equals(candidateOwner)) && (member.equals("*") || member.equals(candidateMember)) && (descriptor.isEmpty() || descriptor.equals(candidateDescriptor));
        }
        int apply(int access) {
            String op = operation; boolean addFinal = op.endsWith("+f"), removeFinal = op.endsWith("-f"); if (op.length() > 1 && (op.endsWith("+f") || op.endsWith("-f"))) op = op.substring(0, op.length() - 2);
            int visibility = switch (op) { case "public" -> 0x0001; case "protected" -> 0x0004; case "private" -> 0x0002; default -> 0; };
            if (visibility != 0) access = (access & ~0x0007) | visibility;
            if (addFinal) access |= 0x0010; if (removeFinal) access &= ~0x0010; return access;
        }
    }

    private static final class Maps {
        final Map<String,String> classes = new HashMap<>();
        final Map<String,String> reverseClasses = new HashMap<>();
        final Map<String,String> fields = new HashMap<>();
        final Map<String,String> methods = new HashMap<>();

        static Maps read(Path file) throws Exception {
            Maps m = new Maps();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 3 && p[0].equals("CL:")) { m.classes.put(p[1], p[2]); m.reverseClasses.put(p[2], p[1]); }
                else if (p.length == 3 && p[0].equals("FD:")) m.fields.put(p[1], p[2].substring(p[2].lastIndexOf('/') + 1));
                else if (p.length == 5 && p[0].equals("MD:")) {
                    String rawOwner = p[1].substring(0, p[1].lastIndexOf('/'));
                    String rawName = p[1].substring(p[1].lastIndexOf('/') + 1);
                    String mappedOwner = p[3].substring(0, p[3].lastIndexOf('/'));
                    String mappedName = p[3].substring(p[3].lastIndexOf('/') + 1);
                    m.methods.put(rawOwner + "/" + rawName + " " + p[2], mappedName);
                    m.methods.put(mappedOwner + "/" + rawName + " " + p[2], mappedName);
                    m.methods.put(rawOwner + "/" + rawName + " " + p[4], mappedName);
                    m.methods.put(mappedOwner + "/" + rawName + " " + p[4], mappedName);
                }
            }
            if (m.classes.isEmpty()) throw new IllegalStateException("joined.srg contains no class mappings: " + file);
            return m;
        }

        String mapClass(String name) {
            String direct = classes.get(name);
            if (direct != null) return direct;
            int dollar = name.indexOf('$');
            if (dollar > 0) {
                String mappedOuter = classes.get(name.substring(0, dollar));
                if (mappedOuter != null) return mappedOuter + name.substring(dollar);
            }
            return name;
        }

        String unmapClass(String name) {
            String direct = reverseClasses.get(name);
            if (direct != null) return direct;
            int dollar = name.indexOf('$');
            if (dollar > 0) {
                String rawOuter = reverseClasses.get(name.substring(0, dollar));
                if (rawOuter != null) return rawOuter + name.substring(dollar);
            }
            return name;
        }

        String unmapDescriptor(String descriptor) {
            StringBuilder b = new StringBuilder(descriptor.length());
            for (int i = 0; i < descriptor.length();) {
                int start = descriptor.indexOf('L', i);
                if (start < 0) { b.append(descriptor, i, descriptor.length()); break; }
                b.append(descriptor, i, start + 1);
                int end = descriptor.indexOf(';', start);
                if (end < 0) { b.append(descriptor, start + 1, descriptor.length()); break; }
                String mapped = descriptor.substring(start + 1, end);
                b.append(reverseClasses.getOrDefault(mapped, mapped)).append(';');
                i = end + 1;
            }
            return b.toString();
        }
    }

    private ClientSrgifier() { }
}
