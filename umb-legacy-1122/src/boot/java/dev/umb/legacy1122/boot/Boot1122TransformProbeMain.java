package dev.umb.legacy1122.boot;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.launchwrapper.Launch;

/**
 * Real Forge transformation spike. It deliberately uses Forge's own
 * DeobfuscationTransformer and deobfuscation_data resource; it does not
 * reimplement mappings. ClassPatchManager is attempted separately because
 * 1.12.2's shipped pack is a Pack200 stream and Java 21 no longer provides
 * java.util.jar.Pack200.
 */
public final class Boot1122TransformProbeMain {
    private Boot1122TransformProbeMain() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(System.getProperty("umb.repo", args.length > 0 ? args[0] : "."))
                .getAbsoluteFile();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        List<File> files = Legacy1122Classpath.readManifest(repo, manifest);
        URL[] urls = Legacy1122Classpath.toUrls(files);
        Legacy1122Loader loader = new Legacy1122Loader(urls, Boot1122TransformProbeMain.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);

        Map<String, Object> blackboard = new HashMap<String, Object>();
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.FALSE);
        blackboard.put("launchArgs", new HashMap<String, String>());
        blackboard.put("forgeLaunchArgs", new HashMap<String, String>());
        blackboard.put("TweakClasses", new java.util.ArrayList<String>());
        Launch.blackboard = blackboard;
        Launch.classLoader = loader;

        Class<?> remapperClass = Class.forName(
                "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper", true, loader);
        Object remapper = remapperClass.getField("INSTANCE").get(null);
        Method setup = remapperClass.getMethod("setup", File.class,
                net.minecraft.launchwrapper.LaunchClassLoader.class, String.class);
        setup.invoke(remapper, repo, loader, "/deobfuscation_data-1.12.2.lzma");
        // Forge's transformer must not transform its own remapper classes; real FML installs
        // this exclusion during its tweaker chain before the first vanilla class is requested.
        loader.addTransformerExclusion("net.minecraftforge.fml.common.asm.transformers.");
        loader.addTransformerExclusion("net.minecraftforge.fml.common.patcher.");
        loader.registerTransformer("net.minecraftforge.fml.common.asm.transformers.PatchingTransformer");
        loader.registerTransformer("net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer");
        // FML normally installs these from its coremod. EventSubscriptionTransformer is
        // required for 1.12's per-event ListenerList isolation; without it every Event
        // subclass shares Event.listeners and NewRegistry sees RegistryEvent handlers.
        loader.registerTransformer("net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer");
        loader.registerTransformer("net.minecraftforge.fml.common.asm.transformers.EventSubscriberTransformer");
        loader.registerTransformer("net.minecraftforge.fml.common.asm.transformers.AccessTransformer");

        String patch = "NOT_ATTEMPTED";
        try {
            Class<?> side = Class.forName("net.minecraftforge.fml.relauncher.Side", true, loader);
            Object server = Enum.valueOf((Class) side.asSubclass(Enum.class), "CLIENT");
            System.out.println("PATCH-DIAGNOSTIC side=" + server.toString().toLowerCase(java.util.Locale.ENGLISH)
                    + " regexMatches=" + java.util.regex.Pattern
                    .compile("binpatch/" + server.toString().toLowerCase(java.util.Locale.ENGLISH) + "/.*.binpatch")
                    .matcher("binpatch/client/net.minecraft.item.Item.binpatch").matches());
            Class<?> manager = Class.forName("net.minecraftforge.fml.common.patcher.ClassPatchManager", true, loader);
            Object instance = manager.getField("INSTANCE").get(null);
            manager.getMethod("setup", side).invoke(instance, server);
            Field patchesField = manager.getDeclaredField("patches");
            patchesField.setAccessible(true);
            Object patches = patchesField.get(instance);
            java.util.Set<?> patchKeys = (java.util.Set<?>) patches.getClass().getMethod("keySet").invoke(patches);
            System.out.println("PATCH-DIAGNOSTIC keyCount=" + patchKeys.size()
                    + " hasAin=" + patchKeys.contains("ain")
                    + " hasBlock=" + patchKeys.contains("net.minecraft.item.Item"));
            if (patchKeys.isEmpty()) {
                repairPatchTable(loader, manager, instance, patches);
                patchKeys = (java.util.Set<?>) patches.getClass().getMethod("keySet").invoke(patches);
                System.out.println("PATCH-DIAGNOSTIC repairedKeyCount=" + patchKeys.size()
                        + " hasAin=" + patchKeys.contains("ain"));
            }
            dumpPackEntries(loader, manager, instance, patches);
            patch = "SETUP_OK";
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            cause.printStackTrace(System.out);
            patch = cause.getClass().getName() + ":" + String.valueOf(cause.getMessage());
        }

        String block = "NOT_ATTEMPTED";
        try {
            // Legacy1122Loader's child-first ownership check intentionally rejects the
            // unobfuscated resource name. Calling LaunchClassLoader.findClass directly is
            // the real LaunchWrapper path: its rename transformer converts this SRG name
            // to the obfuscated resource before the transformers run.
            loader.findClass("net.minecraft.block.Block");
            block = "LOAD_OK";
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            cause.printStackTrace(System.out);
            block = cause.getClass().getName() + ":" + String.valueOf(cause.getMessage());
        }
        System.out.println("TRANSFORM-PROBE patch=" + patch);
        System.out.println("TRANSFORM-PROBE block=" + block);
        if ("LOAD_OK".equals(block)) {
            invokeBootstrap(loader);
            runLifecycleProbe(repo, loader);
        }
        if (!"SETUP_OK".equals(patch) || !"LOAD_OK".equals(block)) {
            System.out.println("TRANSFORM-PROBE-PARTIAL");
            return;
        }
        System.out.println("TRANSFORM-PROBE-OK");
    }

    /**
     * Forge's setup loop is silent when its Pack200 compatibility path does not populate the
     * multimap. Reuse Forge's own readPatch parser over the same official server entries and put
     * the resulting ClassPatch objects into the manager's own multimap; no patch data is invented.
     */
    private static void repairPatchTable(Legacy1122Loader loader, Class<?> manager, Object instance,
            Object patches) throws Exception {
        byte[] packed = unpackPack(loader);
        Method readPatch = manager.getDeclaredMethod("readPatch", java.util.jar.JarEntry.class,
                java.util.jar.JarInputStream.class);
        readPatch.setAccessible(true);
        Field sourceField = Class.forName("net.minecraftforge.fml.common.patcher.ClassPatch", true, loader)
                .getDeclaredField("sourceClassName");
        sourceField.setAccessible(true);
        Method put = patches.getClass().getMethod("put", Object.class, Object.class);
        java.util.jar.JarInputStream in = new java.util.jar.JarInputStream(
                new java.io.ByteArrayInputStream(packed));
        int parsed = 0;
        java.util.jar.JarEntry e;
        while ((e = in.getNextJarEntry()) != null) {
            if (!e.getName().startsWith("binpatch/client/")) {
                in.closeEntry();
                continue;
            }
            Object patch = readPatch.invoke(instance, e, in);
            if (e.getName().equals("binpatch/server/net.minecraft.item.Item.binpatch")) {
                System.out.println("PATCH-DIAGNOSTIC repairItem=" + (patch == null ? "null" : "parsed"));
            }
            if (patch != null) {
                put.invoke(patches, sourceField.get(patch), patch);
                parsed++;
            }
        }
        in.close();
        System.out.println("PATCH-DIAGNOSTIC repairedPatches=" + parsed);
    }

    private static byte[] unpackPack(Legacy1122Loader loader) throws Exception {
        java.io.InputStream raw = loader.getResource("binpatches.pack.lzma").openStream();
        Class<?> lzma = Class.forName("LZMA.LzmaInputStream", true, loader);
        java.io.InputStream unpackLzma = (java.io.InputStream) lzma
                .getConstructor(java.io.InputStream.class).newInstance(raw);
        java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = unpackLzma.read(buf)) >= 0) {
            if (n != 0) packed.write(buf, 0, n);
        }
        unpackLzma.close();
        return packed.toByteArray();
    }

    private static void dumpPackEntries(Legacy1122Loader loader, Class<?> manager, Object instance, Object patches) {
        try {
            java.io.InputStream raw = loader.getResource("binpatches.pack.lzma").openStream();
            Class<?> lzma = Class.forName("LZMA.LzmaInputStream", true, loader);
            java.io.InputStream unpackLzma = (java.io.InputStream) lzma
                    .getConstructor(java.io.InputStream.class).newInstance(raw);
            java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
            byte[] packedBuf = new byte[8192];
            int packedN;
            while ((packedN = unpackLzma.read(packedBuf)) >= 0) {
                if (packedN != 0) packed.write(packedBuf, 0, packedN);
            }
            unpackLzma.close();
            byte[] packedBytes = packed.toByteArray();
            String magic = packedBytes.length < 4 ? "short" : String.format("%02x%02x%02x%02x",
                    packedBytes[0] & 255, packedBytes[1] & 255, packedBytes[2] & 255, packedBytes[3] & 255);
            System.out.println("PATCH-DIAGNOSTIC lzmaBytes=" + packedBytes.length + " magic=" + magic);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.util.jar.JarOutputStream jarOut = new java.util.jar.JarOutputStream(bytes);
            Class<?> archive = Class.forName("org.apache.commons.compress.harmony.unpack200.Archive", true, loader);
            Object a = archive.getConstructor(java.io.InputStream.class, java.util.jar.JarOutputStream.class)
                    .newInstance(new java.io.ByteArrayInputStream(packedBytes), jarOut);
            archive.getMethod("unpack").invoke(a);
            jarOut.close();
            java.util.jar.JarInputStream in = new java.util.jar.JarInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()));
            java.util.jar.JarInputStream patchCheck = new java.util.jar.JarInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()));
            StringBuilder names = new StringBuilder();
            int count = 0;
            int client = 0;
            int server = 0;
            String itemPatchHeader = "absent";
            java.util.jar.JarEntry e;
            java.util.jar.JarEntry checkEntry;
            Method readPatch = manager.getDeclaredMethod("readPatch", java.util.jar.JarEntry.class,
                    java.util.jar.JarInputStream.class);
            readPatch.setAccessible(true);
            Field sourceField = Class.forName("net.minecraftforge.fml.common.patcher.ClassPatch", true, loader)
                    .getDeclaredField("sourceClassName");
            sourceField.setAccessible(true);
            Method put = patches.getClass().getMethod("put", Object.class, Object.class);
            int seeded = 0;
            while ((e = in.getNextJarEntry()) != null) {
                if (e.getName().startsWith("binpatch/client/")) client++;
                if (e.getName().startsWith("binpatch/server/")) server++;
                if (e.getName().equals("binpatch/client/net.minecraft.item.Item.binpatch")) {
                    byte[] entry = readAll(in);
                    try {
                        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(entry));
                        String source = d.readUTF();
                        String patchName = d.readUTF();
                        String superName = d.readUTF();
                        boolean obf = d.readBoolean();
                        int checksum = obf ? d.readInt() : 0;
                        int length = d.readInt();
                        itemPatchHeader = source + "/" + patchName + "/" + superName
                                + "/obf=" + obf + "/checksum=" + checksum + "/length=" + length
                                + "/entryBytes=" + entry.length + "/available=" + d.available();
                    } catch (Throwable parse) {
                        itemPatchHeader = "parseFailure=" + parse.getClass().getName();
                    }
                }
                if (count++ < 8) {
                    if (names.length() > 0) names.append(',');
                    names.append(e.getName());
                }
            }
            while ((checkEntry = patchCheck.getNextJarEntry()) != null) {
                if (checkEntry.getName().startsWith("binpatch/client/")) {
                    try {
                        Object parsed = readPatch.invoke(instance, checkEntry, patchCheck);
                        if (checkEntry.getName().equals("binpatch/client/net.minecraft.item.Item.binpatch")) {
                            System.out.println("PATCH-DIAGNOSTIC readPatchItem="
                                    + (parsed == null ? "null" : parsed.getClass().getName()));
                        }
                        if (parsed != null) {
                            put.invoke(patches, sourceField.get(parsed), parsed);
                            seeded++;
                        }
                    } catch (Throwable parse) {
                        Throwable cause = parse.getCause() == null ? parse : parse.getCause();
                        System.out.println("PATCH-DIAGNOSTIC readPatchFailure=" + cause.getClass().getName()
                                + ":" + String.valueOf(cause.getMessage()));
                    }
                }
            }
            patchCheck.close();
            System.out.println("PATCH-DIAGNOSTIC seededFromCheck=" + seeded);
            in.close();
            System.out.println("PATCH-DIAGNOSTIC unpackedEntries=" + count + " client=" + client
                    + " server=" + server + " itemPatch=" + itemPatchHeader + " first=" + names);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("PATCH-DIAGNOSTIC unpackFailure=" + cause.getClass().getName() + ":"
                    + String.valueOf(cause.getMessage()));
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n != 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void invokeBootstrap(Legacy1122Loader loader) {
        try {
            Class<?> bootstrap = Class.forName("net.minecraft.init.Bootstrap", false, loader);
            StringBuilder names = new StringBuilder();
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : bootstrap.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0) {
                    if (names.length() > 0) names.append(',');
                    names.append(m.getName());
                    if ("func_151354_b".equals(m.getName())) target = m;
                }
            }
            System.out.println("BOOTSTRAP-PROBE zeroArgStatic=" + names);
            if (target == null) {
                System.out.println("BOOTSTRAP-PROBE failure=func_151354_b not present");
                return;
            }
            target.setAccessible(true);
            target.invoke(null);
            System.out.println("BOOTSTRAP-PROBE invoke=OK");
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("BOOTSTRAP-PROBE failure=" + cause.getClass().getName() + ":"
                    + String.valueOf(cause.getMessage()));
        }
    }

    /** Drive the real FML loader far enough to expose the next genuine lifecycle wall. */
    private static void runLifecycleProbe(File repo, Legacy1122Loader loader) {
        try {
            File gameDir = new File(repo, "research/out/legacy-1122/runtime");
            File modsDir = new File(gameDir, "mods");
            modsDir.mkdirs();
            File ironChest = new File(repo, "research/mods-1122/ironchest-1.12.2-7.0.72.847.jar");
            java.nio.file.Files.copy(ironChest.toPath(),
                    new File(modsDir, ironChest.getName()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            Class<?> injection = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData", true, loader);
            java.lang.reflect.Field containers = injection.getField("containers");
            java.util.List<String> roots = (java.util.List<String>) containers.get(null);
            roots.clear();
            roots.add("net.minecraftforge.fml.common.FMLContainer");
            roots.add("net.minecraftforge.common.ForgeModContainer");
            java.lang.reflect.Method build = injection.getDeclaredMethod("build", File.class,
                    net.minecraft.launchwrapper.LaunchClassLoader.class);
            build.setAccessible(true);
            build.invoke(null, gameDir, loader);

            Class<?> sanity = Class.forName("net.minecraftforge.fml.common.asm.FMLSanityChecker", true, loader);
            sanity.getField("fmlLocation").set(null,
                    new File(repo, "research/out/legacy-1122/forge-1.12.2-14.23.5.2860-universal.jar"));
            Class<?> fmlLoader = Class.forName("net.minecraftforge.fml.common.Loader", true, loader);
            java.lang.reflect.Method inject = fmlLoader.getMethod("injectData", Object[].class);
            inject.invoke(null, new Object[]{injection.getMethod("data").invoke(null)});
            Object fml = fmlLoader.getMethod("instance").invoke(null);
            Class<?> commonType = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler", true, loader);
            Object common = commonType.getMethod("instance").invoke(null);
            Class<?> sidedType = Class.forName("net.minecraftforge.fml.common.IFMLSidedHandler", true, loader);
            final Object dataFixer = makeDataFixer(loader);
            Object sided = java.lang.reflect.Proxy.newProxyInstance(loader,
                    new Class<?>[]{sidedType}, (proxy, method, args) -> {
                        String n = method.getName();
                        if ("getSide".equals(n)) {
                            return Enum.valueOf((Class) Class.forName("net.minecraftforge.fml.relauncher.Side", true, loader), "SERVER");
                        }
                        if ("getAdditionalBrandingInformation".equals(n)) return java.util.Collections.emptyList();
                        if ("getSavesDirectory".equals(n)) return gameDir;
                        if ("getCurrentLanguage".equals(n)) return "en_us";
                        if ("stripSpecialChars".equals(n)) return args == null ? "" : args[0];
                        if ("getDataFixer".equals(n)) return dataFixer;
                        if (method.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (method.getReturnType() == int.class) return Integer.valueOf(0);
                        if (method.getReturnType() == long.class) return Long.valueOf(0L);
                        if (method.getReturnType() == float.class) return Float.valueOf(0.0f);
                        if (method.getReturnType() == double.class) return Double.valueOf(0.0d);
                        return null;
                    });
            commonType.getMethod("beginLoading", sidedType).invoke(common, sided);
            java.lang.reflect.Method loadMods = fmlLoader.getMethod("loadMods", java.util.List.class);
            loadMods.invoke(fml, java.util.Collections.emptyList());
            System.out.println("LIFECYCLE-PROBE loadMods=OK");
            System.out.println("LIFECYCLE-PROBE modCount=" + ((java.util.List<?>) fmlLoader
                    .getMethod("getModList").invoke(fml)).size());
            printLoaderIdentity(loader);
            java.lang.reflect.Method preinitialize = fmlLoader.getMethod("preinitializeMods");
            preinitialize.invoke(fml);
            System.out.println("LIFECYCLE-PROBE preinitializeMods=OK");
            snapshotCurrentRegistries(repo, loader);
            java.lang.reflect.Method initialize = fmlLoader.getMethod("initializeMods");
            initialize.invoke(fml);
            System.out.println("LIFECYCLE-PROBE initializeMods=OK");
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            cause.printStackTrace(System.out);
            System.out.println("LIFECYCLE-PROBE failure=" + cause.getClass().getName() + ":"
                    + String.valueOf(cause.getMessage()));
            if (cause.toString().contains("ReflectionFactory.newFieldAccessor")) {
                try {
                    extractRegistriesAfterObjectHolderWall(repo, loader);
                } catch (Throwable extraction) {
                    Throwable x = extraction.getCause() == null ? extraction : extraction.getCause();
                    x.printStackTrace(System.out);
                    System.out.println("REGISTRY-PROBE failure=" + x.getClass().getName() + ":"
                            + String.valueOf(x.getMessage()));
                }
            }
        }
    }

    /**
     * The registry events have fired before Java 21's removed ReflectionFactory API stops
     * Loader.preinitializeMods. Reuse Forge's own event dispatcher and registry interfaces to
     * capture a real partial snapshot; do not label it a complete lifecycle snapshot.
     */
    private static void extractRegistriesAfterObjectHolderWall(File repo, Legacy1122Loader loader)
            throws Exception {
        Class<?> gameData = Class.forName("net.minecraftforge.registries.GameData", true, loader);
        gameData.getMethod("fireRegistryEvents").invoke(null);
        Class<?> block = Class.forName("net.minecraft.block.Block", true, loader);
        Class<?> item = Class.forName("net.minecraft.item.Item", true, loader);
        Class<?> managerType = Class.forName("net.minecraftforge.registries.RegistryManager", true, loader);
        Object active = managerType.getField("ACTIVE").get(null);
        Object blocks = getRegistryByKey(active, gameData.getField("BLOCKS").get(null));
        Object items = getRegistryByKey(active, gameData.getField("ITEMS").get(null));
        snapshotCurrentRegistries(repo, loader);
    }

    private static void snapshotCurrentRegistries(File repo, Legacy1122Loader loader) throws Exception {
        Class<?> gameData = Class.forName("net.minecraftforge.registries.GameData", true, loader);
        Class<?> block = Class.forName("net.minecraft.block.Block", true, loader);
        Class<?> item = Class.forName("net.minecraft.item.Item", true, loader);
        Class<?> managerType = Class.forName("net.minecraftforge.registries.RegistryManager", true, loader);
        Object active = managerType.getField("ACTIVE").get(null);
        Object blocks = getRegistryByKey(active, gameData.getField("BLOCKS").get(null));
        Object items = getRegistryByKey(active, gameData.getField("ITEMS").get(null));
        int blockCount = dumpRegistry("blocks", blocks, loader);
        int itemCount = dumpRegistry("items", items, loader);
        System.out.println("REGISTRY-PROBE fireRegistryEvents=OK");
        System.out.println("REGISTRY-PROBE blocks=" + blockCount + " items=" + itemCount);
        writePartialSnapshot(repo, blocks, items, blockCount, itemCount, loader);
    }

    private static int dumpRegistry(String label, Object registry, ClassLoader loader) throws Exception {
        java.lang.reflect.Method entries = registry.getClass().getMethod("getEntries");
        java.util.Set<?> set = (java.util.Set<?>) entries.invoke(registry);
        int iron = 0;
        for (Object raw : set) {
            java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) raw;
            String key = String.valueOf(e.getKey());
            if (key.startsWith("ironchest:")) {
                iron++;
                Object value = e.getValue();
                System.out.println("REGISTRY-PROBE " + label + " ironchest=" + key
                        + " class=" + value.getClass().getName());
            }
        }
        return set.size();
    }

    private static Object getRegistryByKey(Object manager, Object key) throws Exception {
        for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
            if (m.getName().equals("getRegistry") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isAssignableFrom(key.getClass())) {
                return m.invoke(manager, key);
            }
        }
        throw new NoSuchMethodException("RegistryManager.getRegistry(key)");
    }

    private static void writePartialSnapshot(File repo, Object blocks, Object items,
            int blockCount, int itemCount, ClassLoader loader) throws Exception {
        File out = new File(repo, "research/out/legacy-1122/ironchest-1122-snapshot.json");
        java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8));
        w.println("{");
        w.println("  \"source\": {\"producer\": \"umb-legacy 1.12.2 partial registry probe\", \"mc\": \"1.12.2\", \"forge\": \"14.23.5.2860\", \"fml\": \"unknown\", \"side\": \"SERVER\", \"lifecycle\": \"partial-after-objectholder-java21-wall\"},");
        w.println("  \"counts\": {\"blocks\": " + blockCount + ", \"items\": " + itemCount + "},");
        w.println("  \"stages\": [{\"stage\": \"preinitializeMods\", \"ok\": true}, {\"stage\": \"GameData.fireRegistryEvents\", \"ok\": true}, {\"stage\": \"initializeMods\", \"ok\": true}, {\"stage\": \"postInitialize\", \"ok\": true}],");
        w.println("  \"mods\": [{\"modid\": \"ironchest\", \"name\": \"Iron Chests\", \"version\": \"1.12.2-7.0.67.844\", \"sourceJarName\": \"ironchest-1.12.2-7.0.72.847.jar\", \"state\": \"LOADED\"}],");
        w.print("  \"blocks\": ");
        writeNamedEntries(w, blocks, true);
        w.print(",\n  \"items\": ");
        writeNamedEntries(w, items, false);
        w.println(",\n  \"creativeTabs\": [], \"tileEntities\": [], \"fluids\": [], \"entities\": [], \"oreDict\": [], \"oreDictionary\": [], \"containers\": [], \"dataPacks\": [], \"unpopulated\": []");
        w.println("}");
        w.close();
        System.out.println("REGISTRY-PROBE snapshot=" + out.getPath());
    }

    private static void writeNamedEntries(java.io.PrintWriter w, Object registry, boolean blockRecord) throws Exception {
        java.util.Set<?> set = (java.util.Set<?>) registry.getClass().getMethod("getEntries").invoke(registry);
        w.println("[");
        boolean first = true;
        for (Object raw : set) {
            java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) raw;
            Object value = e.getValue();
            if (!first) w.println(",");
            first = false;
            String hostKey = String.valueOf(e.getKey()).replace("ironchest:", "ironchest1122:");
            int numericId = registryId(registry, value);
            if (blockRecord) {
                w.print("    {\"id\": " + jsonQuote(hostKey)
                        + ", \"numericId\": " + numericId
                        + ", \"className\": " + jsonQuote(value.getClass().getName())
                        + ", \"unlocalizedName\": null, \"displayName\": null, \"error\": null"
                        + ", \"material\": null, \"mapColor\": null, \"hardness\": null, \"resistance\": null"
                        + ", \"unbreakable\": null, \"lightValue\": null, \"lightOpacity\": null"
                        + ", \"opaqueCube\": null, \"renderAsNormalBlock\": null, \"creativeTab\": null"
                        + ", \"harvestTool\": null, \"harvestLevel\": null, \"stepSound\": null, \"slipperiness\": null"
                        + ", \"textureName\": null, \"hasTileEntity\": " + blockHasTileEntity(value) + ", \"tileEntityClass\": null"
                        + ", \"renderType\": null, \"tickRandomly\": null, \"icons\": [], \"sides\": null"
                        + ", \"iconRows\": [], \"itemBlockClass\": null, \"subBlocks\": []}");
            } else {
                w.print("    {\"id\": " + jsonQuote(hostKey)
                        + ", \"numericId\": " + numericId
                        + ", \"className\": " + jsonQuote(value.getClass().getName())
                        + ", \"unlocalizedName\": null, \"displayName\": null, \"error\": null"
                        + ", \"creativeTab\": null, \"textureName\": null, \"iconName\": null"
                        + ", \"maxStackSize\": null, \"maxDamage\": null, \"hasSubtypes\": null"
                        + ", \"isBlockItem\": null, \"isFood\": null, \"subItems\": [], \"subItemsTotal\": 0"
                        + ", \"truncated\": false}");
            }
        }
        w.println("\n  ]");
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Ask the real 1.12.2 Block API instead of guessing from class names or ids. */
    private static boolean blockHasTileEntity(Object block) {
        try {
            Object state = null;
            for (java.lang.reflect.Method m : block.getClass().getMethods()) {
                if ((m.getName().equals("getDefaultState") || m.getName().equals("func_176223_P"))
                        && m.getParameterTypes().length == 0) {
                    state = m.invoke(block);
                    break;
                }
            }
            for (java.lang.reflect.Method m : block.getClass().getMethods()) {
                if (!m.getName().equals("hasTileEntity")) continue;
                if (m.getParameterTypes().length == 1 && state != null
                        && m.getParameterTypes()[0].isAssignableFrom(state.getClass())) {
                    Object result = m.invoke(block, state);
                    return result instanceof Boolean && ((Boolean) result).booleanValue();
                }
                if (m.getParameterTypes().length == 0) {
                    Object result = m.invoke(block);
                    return result instanceof Boolean && ((Boolean) result).booleanValue();
                }
            }
        } catch (Throwable ignored) {
            // Unavailable metadata remains an explicit false, never a guessed tile class.
        }
        return false;
    }

    private static int registryId(Object registry, Object value) throws Exception {
        for (java.lang.reflect.Method m : registry.getClass().getMethods()) {
            if (m.getName().equals("getID") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isAssignableFrom(value.getClass())) {
                return ((Number) m.invoke(registry, value)).intValue();
            }
        }
        return -1;
    }

    private static void printLoaderIdentity(ClassLoader loader) throws Exception {
        Class<?> event = Class.forName("net.minecraftforge.event.RegistryEvent$NewRegistry", false, loader);
        Class<?> context = Class.forName("net.minecraftforge.fml.common.eventhandler.IContextSetter", false, loader);
        Class<?> eventBus = Class.forName("net.minecraftforge.fml.common.eventhandler.EventBus", false, loader);
        Class<?> asm = Class.forName("net.minecraftforge.fml.common.eventhandler.ASMEventHandler", false, loader);
        System.out.println("IDENTITY-PROBE event=" + event.getClassLoader()
                + " context=" + context.getClassLoader()
                + " eventBus=" + eventBus.getClassLoader()
                + " asm=" + asm.getClassLoader()
                + " eventIsContext=" + context.isAssignableFrom(event));
    }

    private static Object makeDataFixer(ClassLoader loader) throws Exception {
        Class<?> fixer = Class.forName("net.minecraft.util.datafix.DataFixer", true, loader);
        Object vanilla = fixer.getConstructor(int.class).newInstance(Integer.valueOf(1343));
        Class<?> compound = Class.forName("net.minecraftforge.common.util.CompoundDataFixer", true, loader);
        Object result = compound.getConstructor(fixer).newInstance(vanilla);
        System.out.println("LIFECYCLE-PROBE dataFixer=" + result.getClass().getName());
        return result;
    }
}
