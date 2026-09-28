/*
 * UMB's JDK-only build driver.
 *
 * Run from any shell and on Windows, Linux, or macOS:
 *   java tools/UmbBuild.java build
 *   java tools/UmbBuild.java test
 *   java tools/UmbBuild.java release
 *
 * The PowerShell files remain the lead's Windows wrappers.  This file is the
 * portable implementation and intentionally uses only java/javac/jar plus JDK
 * classes; no shell, Gradle, or OS-specific executable is required.
 */

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class UmbBuild {
    private static final Map<String,String> RELEASE_SHIMS = Map.of(
            "net/minecraft/client/renderer/Tessellator.class",
            "umb-legacy/src/legacyside/java/net/minecraft/client/renderer/Tessellator.java");
    private final Path root;
    private final Path inputs;
    private final Path javaHome;
    private final Path javac;
    private final Path java;
    private final Path jar;
    private final Path junit;
    private final Path toolsJunit;

    private UmbBuild(Path root, Path inputs) {
        this.root = root.toAbsolutePath().normalize();
        this.inputs = inputs.toAbsolutePath().normalize();
        this.javaHome = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
        this.javac = tool("javac");
        this.java = tool("java");
        this.jar = tool("jar");
        this.toolsJunit = inputs.resolve("tools/junit");
        this.junit = toolsJunit.resolve("junit-platform-console-standalone.jar");
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i=0; i<args.length; i++) {
            String a=args[i];
            if (a.equals("--root") || a.equals("--inputs")) {
                if (++i >= args.length) die(a+" needs a path");
                opts.put(a.substring(2), args[i]);
            } else positional.add(a);
        }
        if (positional.isEmpty()) usage();
        Path here = Paths.get(System.getProperty("user.dir"));
        Path root = Paths.get(opts.getOrDefault("root", here.toString()));
        Path inputs = Paths.get(opts.getOrDefault("inputs", root.toString()));
        UmbBuild b = new UmbBuild(root, inputs);
        switch (positional.get(0)) {
            case "build" -> b.buildAll();
            case "test" -> { b.buildAll(); b.runTests(); }
            case "release" -> { b.buildAll(); b.makeRelease(); }
            case "release-bundle" -> { b.makeRelease(); }
            case "legacy" -> b.buildLegacy(false, true);
            case "legacy-no-tests" -> b.buildLegacy(false, false);
            case "legacy1165" -> b.buildLegacy1165();
            case "legacy1122" -> b.buildLegacy1122();
            case "test1122" -> b.runLegacy1122Tests();
            case "hostagent" -> b.buildHostAgent();
            case "objbridge" -> b.buildObjBridge();
            case "rendermap" -> b.buildRenderMap();
            case "guimap" -> b.buildGuiMap();
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: java tools/UmbBuild.java <build|test|release|release-bundle|legacy|hostagent|objbridge|rendermap|guimap> [--root DIR] [--inputs DIR]");
        System.exit(2);
    }

    private Path tool(String name) {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name+".exe" : name;
        Path p=javaHome.resolve("bin").resolve(exe);
        if (Files.isExecutable(p)) return p;
        Path current = Paths.get(System.getProperty("java.home"), "bin", exe);
        if (Files.isExecutable(current)) return current;
        die("JDK tool not found: "+p);
        return p;
    }

    private Path optionalJdk21Tool(String name) {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name+".exe" : name;
        Path p=inputs.resolve("tools/jdk-21.0.12.1+1/bin").resolve(exe);
        return Files.isExecutable(p) ? p : tool(name);
    }

    private static void die(String message) { throw new IllegalStateException(message); }

    private void buildAll() throws Exception {
        buildLegacy(false, true);
        buildHostAgent();
        buildObjBridge();
        buildRenderMap();
        buildGuiMap();
        buildLegacy1165();
        buildLegacy1122();
        System.out.println("UMB-BUILD-OK");
    }

    private Path out(String relative) throws IOException {
        Path p=root.resolve(relative).normalize();
        Files.createDirectories(p.getParent());
        return p;
    }

    private Path input(String relative) { return inputs.resolve(relative).normalize(); }

    private static List<Path> sources(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) die("missing source directory: "+sourceRoot);
        try (Stream<Path> s=Files.walk(sourceRoot)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.deleteIfExists(f); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.deleteIfExists(d); return FileVisitResult.CONTINUE; }
        });
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) die("missing input: "+from);
        try (Stream<Path> s=Files.walk(from)) {
            for (Path p : s.collect(Collectors.toList())) {
                Path d=to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(d); else { Files.createDirectories(d.getParent()); Files.copy(p,d,StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private Path argFile(String name, List<Path> files) throws IOException {
        Path f=out("build/.args/"+name+".args");
        StringBuilder b=new StringBuilder();
        for (Path p : files) {
            String s=p.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            b.append('"').append(s).append('"').append('\n');
        }
        Files.writeString(f,b.toString(),StandardCharsets.US_ASCII,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        return f;
    }

    private int run(List<String> command, Path cwd) throws Exception {
        System.out.println("RUN " + command.stream().map(UmbBuild::quote).collect(Collectors.joining(" ")));
        ProcessBuilder pb=new ProcessBuilder(command).directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p=pb.start();
        try (BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) { r.lines().forEach(System.out::println); }
        int rc=p.waitFor();
        if (rc!=0) throw new IllegalStateException("command failed ("+rc+"): "+command.get(0));
        return rc;
    }

    private static String quote(String s) { return s.contains(" ") ? "\""+s+"\"" : s; }
    private String cp(Collection<Path> paths) { return paths.stream().filter(Objects::nonNull).map(p->p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator)); }
    private List<String> cmd(Path exe, String... args) { List<String> x=new ArrayList<>(); x.add(exe.toString()); x.addAll(Arrays.asList(args)); return x; }
    private List<String> cmd(Path exe, List<String> args) { List<String> x=new ArrayList<>(); x.add(exe.toString()); x.addAll(args); return x; }

    private void compile(String name, Path sourceRoot, String classpath, String release, Path dest) throws Exception {
        deleteTree(dest); Files.createDirectories(dest);
        List<Path> ss=sources(sourceRoot);
        List<String> a=new ArrayList<>(List.of("-nowarn","-encoding","UTF-8","--release",release));
        if (classpath!=null && !classpath.isEmpty()) { a.add("-cp"); a.add(classpath); }
        a.add("-d"); a.add(dest.toString()); a.add("@"+argFile(name,ss));
        run(cmd(optionalJdk21Tool("javac"),a),root);
        System.out.println("compiled "+name+" ("+ss.size()+" sources, release "+release+")");
    }

    private void jar(Path classes, Path destination, String mainClass, Path... extraDirs) throws Exception {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.deleteIfExists(destination);
        List<String> a=new ArrayList<>(List.of("--create","--file",destination.toString()));
        if (mainClass!=null) { a.add("--main-class"); a.add(mainClass); }
        a.add("-C"); a.add(classes.toString()); a.add(".");
        run(cmd(jar,a),root);
    }

    private void extractJar(Path archive, Path dest) throws Exception { Files.createDirectories(dest); run(cmd(jar,"--extract","--file",archive.toString()),dest); }

    private List<Path> classpathFile(Path file) throws IOException {
        String raw=Files.readString(file,StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) return List.of();
        String[] parts=raw.contains(";") ? raw.split(";") : raw.split(":");
        List<Path> result=new ArrayList<>();
        for (String part:parts) {
            if (!part.isBlank()) {
                Path resolved=resolveForeign(part.trim());
                if (!isNativeJar(resolved) || nativeMatches(resolved)) result.add(resolved);
            }
        }
        return result;
    }

    private static boolean isNativeJar(Path path) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("-natives-") || name.contains("-natives.");
    }

    private static boolean nativeMatches(Path path) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        String os=System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String platform=os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : "";
        if (platform.isEmpty() || !name.contains("-natives-" + platform)) return false;
        String arch=System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm=arch.contains("aarch64") || arch.contains("arm64");
        boolean armNative=name.contains("-arm64") || name.contains("-aarch64");
        return arm ? armNative : !armNative;
    }

    private static Path materializeClasspath(Path destination, List<Path> entries) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        String text=entries.stream().map(p->p.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(System.lineSeparator()));
        Files.writeString(destination,text+System.lineSeparator(),StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        return destination;
    }

    private List<Path> manifestPaths(Path file) throws IOException {
        try (Stream<String> lines=Files.lines(file,StandardCharsets.UTF_8)) {
            return lines.map(String::trim).filter(s->!s.isEmpty()&&!s.startsWith("#")).map(this::resolveForeign).toList();
        }
    }

    private Path resolveForeign(String raw) {
        String s=raw.replace('\\','/');
        Path p=Paths.get(s);
        if (Files.exists(p)) return p.toAbsolutePath().normalize();
        String marker="/translatemc/";
        int i=s.toLowerCase(Locale.ROOT).indexOf(marker);
        if (i>=0) { Path q=root.resolve(s.substring(i+marker.length())); if (Files.exists(q)) return q.normalize(); q=inputs.resolve(s.substring(i+marker.length())); if (Files.exists(q)) return q.normalize(); }
        if (!p.isAbsolute()) { Path q=inputs.resolve(s); if (Files.exists(q)) return q.normalize(); q=root.resolve(s); if (Files.exists(q)) return q.normalize(); }
        return p;
    }

    private Path firstMatching(Path dir, String glob) throws IOException {
        try (Stream<Path> s=Files.walk(dir)) { return s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches(glob)).findFirst().orElse(null); }
    }

    private Path existing(Path p, String label) { if (!Files.exists(p)) die("missing "+label+": "+p); return p; }

    private void buildLegacy(boolean skipRemap, boolean tests) throws Exception {
        Path build=root.resolve("build/legacy"); Files.createDirectories(build);
        // The raw DEBUG_SAVE jar is gitignored and unfetchable: CI only has it when the
        // cache restores it. Everything up to the SRG sanitize stage (api, bridge-api, boot)
        // builds without it, so those jars still land for hostagent and its tests.
        Path runtime=input("research/out/legacy/1.7.10-forge-srg-runtime.jar");
        boolean haveRuntime=Files.exists(runtime);
        if(!haveRuntime) System.out.println("LEGACY-1710-SKIP: missing legacy runtime jar "+runtime+" - skipping the SRG sanitize/field-repair/Forge-remap/legacyside stages that require it; release builds refuse to run without it");
        Path forge=existing(input("research/visual/mc1710-native/libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"),"Forge universal jar");
        Path notch=existing(input("research/visual/mc1710-native/versions/1.7.10-Forge10.13.4.1614-1.7.10/1.7.10-Forge10.13.4.1614-1.7.10.jar"),"1.7.10 client jar");
        Path libsFile=existing(input("research/visual/mc1710-native/classpath.txt"),"1.7.10 classpath file");
        List<Path> all=classpathFile(libsFile);
        Path nativeClasspath=materializeClasspath(build.resolve("classpath-native.txt"),all);
        List<Path> libs=all.stream().filter(p->{String n=p.getFileName().toString(); return !n.startsWith("launchwrapper-")&&!n.startsWith("1.7.10-Forge")&&!n.startsWith("forge-1.7.10-");}).collect(Collectors.toList());
        Path lw=all.stream().filter(p->p.getFileName().toString().startsWith("launchwrapper-")).findFirst().orElseThrow(()->new IllegalStateException("launchwrapper missing from classpath.txt"));
        Path jopt=firstMatching(input("research/visual/mc1710-native/libraries"),"jopt-simple-.*\\.jar");
        Path log4jApi=firstMatching(input("research/visual/mc1710-native/libraries"),"log4j-api-.*\\.jar");
        Path log4jCore=firstMatching(input("research/visual/mc1710-native/libraries"),"log4j-core-.*\\.jar");
        Path asm=existing(toolsJunit.resolve("asm-9.9.jar"),"ASM jar");
        Path legacyAsm=firstMatching(input("research/visual/mc1710-native/libraries"),"asm-all-5\\.0\\.3\\.jar");
        Path lzma=firstMatching(input("research/visual/mc1710-native/libraries"),"lzma-0\\.0\\.1\\.jar");
        Path canonicalApi=build.resolve("umb-bridge-api.jar");
        Path api=build.resolve("umb-legacy-api.jar");
        Path apiClasses=build.resolve("classes-api");
        compile("legacy-api",root.resolve("umb-legacy/src/api/java"),"","8",apiClasses); jar(apiClasses,api,null);
        Path bridgeClasses=build.resolve("classes-bridge-api");
        compile("legacy-bridge-api",root.resolve("umb-legacy/src/bridge-api/java"),"","8",bridgeClasses); jar(bridgeClasses,canonicalApi,null);
        Path bootClasses=build.resolve("classes-boot"), boot=build.resolve("umb-legacy-boot.jar");
        compile("legacy-boot",root.resolve("umb-legacy/src/boot/java"),cp(List.of(api,lw,asm,toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"))),"21",bootClasses); jar(bootClasses,boot,"dev.umb.legacy.boot.Bootstrap");
        if (haveRuntime) {
            Path clean=build.resolve("1.7.10-forge-srg-runtime-clean.jar"); sanitizeJar(runtime,clean);
            Path fields=build.resolve("1.7.10-forge-srg-runtime-fields.jar");
            Path repairSummary=root.resolve("research/out/legacy/legacy-boot/srg-field-repair.txt"); Files.createDirectories(repairSummary.getParent());
            run(cmd(java,List.of("-cp",cp(List.of(boot,asm)),"dev.umb.legacy.boot.SrgFieldRepair",input("research/mappings/joined-1.7.10.srg").toString(),clean.toString(),fields.toString(),repairSummary.toString())),root);
            Path srgForge=build.resolve("forge-1.7.10-10.13.4.1614-srg.jar");
            if (!skipRemap) {
                Path stage1=root.resolve("build/legacy/src-stage1"); deleteTree(stage1); Files.createDirectories(stage1.resolve("dev/umb/legacy/legacyside"));
                for(String f:List.of("ForgeSrgifier.java","Statics.java")) Files.copy(root.resolve("umb-legacy/src/legacyside/java/dev/umb/legacy/legacyside/"+f),stage1.resolve("dev/umb/legacy/legacyside/"+f),StandardCopyOption.REPLACE_EXISTING);
                Path s1Classes=build.resolve("classes-stage1"), s1Jar=build.resolve("umb-legacy-legacyside-stage1.jar");
                compile("legacy-legacyside-stage1",stage1,cp(join(List.of(api,lw,forge,notch,legacyAsm),libs)),"8",s1Classes); jar(s1Classes,s1Jar,null);
                List<String> a=new ArrayList<>(List.of("-Xmx512m","-Djava.awt.headless=true","--sun-misc-unsafe-memory-access=allow","-XX:-OmitStackTraceInFastThrow","-Dumb.repo="+root,"-Dumb.legacy.legacysideJar="+s1Jar,"-Dumb.legacy.forgeSrgJar="+srgForge,"-Dumb.legacy.notchJar="+notch,"-Dumb.legacy.forgeJar="+forge,"-Dumb.legacy.classpathFile="+nativeClasspath,"-Dumb.legacy.runtimeJar="+runtime,"-Dlog4j.configurationFile="+root.resolve("umb-legacy/src/legacyside/resources/log4j2-legacy.xml"),"-cp",cp(join(List.of(boot,api,lw,jopt,log4jApi,log4jCore,legacyAsm,lzma),List.of())),"dev.umb.legacy.boot.RemapTool"));
                run(cmd(java,a),root);
            }
            existing(srgForge,"SRG Forge output");
            Path lsClasses=build.resolve("classes-legacyside"), ls=build.resolve("umb-legacy-legacyside.jar");
            compile("legacy-legacyside",root.resolve("umb-legacy/src/legacyside/java"),cp(join(List.of(api,canonicalApi,lw,fields,srgForge,asm,toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar")),libs)),"8",lsClasses); if(Files.isDirectory(root.resolve("umb-legacy/src/legacyside/resources"))) copyTree(root.resolve("umb-legacy/src/legacyside/resources"),lsClasses); jar(lsClasses,ls,null);
            if (tests) {
                Path tc=build.resolve("classes-test");
                compile("legacy-tests",root.resolve("umb-legacy/src/test/java"),cp(join(List.of(api,canonicalApi,boot,lw,fields,srgForge,ls,junit,asm),libs)),"21",tc);
                run(cmd(java,List.of("-Djava.awt.headless=true","-Dumb.repo="+root,"-Dumb.legacy.classpathFile="+nativeClasspath,"--sun-misc-unsafe-memory-access=allow","--add-opens","java.base/java.lang=ALL-UNNAMED","--add-opens","java.base/java.lang.reflect=ALL-UNNAMED","-jar",junit.toString(),"execute","--class-path",cp(join(List.of(tc,api,canonicalApi,boot,lw,fields,srgForge,ls,jopt,log4jApi,log4jCore,asm),libs)),"--select-package","dev.umb.legacy.test","--details=summary","--disable-banner")),root);
            }
        }
        System.out.println("LEGACY-BUILD-OK");
    }

    private static List<Path> join(List<Path> a, List<Path> b) { List<Path> r=new ArrayList<>(a); r.addAll(b); return r; }
    private static List<Path> join(Collection<Path> a, Collection<Path> b) { List<Path> r=new ArrayList<>(a); r.addAll(b); return r; }

    private void sanitizeJar(Path source, Path destination) throws IOException {
        Files.deleteIfExists(destination); Files.createDirectories(destination.getParent());
        try(JarFile in=new JarFile(source.toFile()); JarOutputStream out=new JarOutputStream(Files.newOutputStream(destination))) {
            Enumeration<JarEntry> e=in.entries(); while(e.hasMoreElements()){JarEntry x=e.nextElement(); if(x.getName().contains(",")) continue; JarEntry y=new JarEntry(x.getName()); out.putNextEntry(y); if(!x.isDirectory()) in.getInputStream(x).transferTo(out); out.closeEntry();}
        }
    }

    private void buildHostAgent() throws Exception {
        Path build=root.resolve("build/hostagent"), classes=build.resolve("classes"), stage=build.resolve("stage"); deleteTree(classes); deleteTree(stage); Files.createDirectories(classes); Files.createDirectories(stage);
        Path client=existing(input("research/jars/26.2/client.jar"),"26.2 client jar"); Path cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"); List<Path> game=classpathFile(cpFile); Path gson=game.stream().filter(p->p.getFileName().toString().contains("gson")).findFirst().orElseThrow(()->new IllegalStateException("gson missing from 26.2 classpath"));
        Path asm=existing(toolsJunit.resolve("asm-9.9.jar"),"ASM"); Path asmTree=existing(toolsJunit.resolve("asm-tree-9.9.jar"),"ASM tree"); Path asmCommons=existing(toolsJunit.resolve("asm-commons-9.9.jar"),"ASM commons");
        Path legacy=root.resolve("build/legacy"); Path launchwrapper=firstMatching(input("research/visual/mc1710-native/libraries"),"launchwrapper-.*\\.jar"); List<Path> tier=List.of(legacy.resolve("umb-legacy-boot.jar"),legacy.resolve("umb-legacy-api.jar"),launchwrapper,firstMatching(input("research/visual/mc1710-native/libraries"),"jopt-simple-.*\\.jar")); for(Path p:tier) existing(p,"hostagent tier-1 jar"); List<Path> bundledTier=tier.stream().filter(p->!p.equals(launchwrapper)).collect(Collectors.toList());
        Path mirrorSrc=build.resolve("bridge-api-mirror-src/dev/umb/bridge/api"); deleteTree(mirrorSrc); Files.createDirectories(mirrorSrc); List<Path> canon=sources(root.resolve("umb-legacy/src/bridge-api/java/dev/umb/bridge/api"));
        for(Path p:canon){String text=Files.readString(p); Path d=mirrorSrc.resolve(p.getFileName()); Files.writeString(d,text.stripTrailing()+"\n\n// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.\n// Synced verbatim by tools/UmbBuild.java from umb-legacy/src/bridge-api/java/dev/umb/bridge/api/.\n",StandardCharsets.UTF_8);}
        Path mirrorClasses=build.resolve("classes-bridge-api-mirror"); compile("hostagent-bridge-api-mirror",build.resolve("bridge-api-mirror-src"),"","8",mirrorClasses);
        List<Path> main=sources(root.resolve("umb-hostagent/src/main/java")).stream().filter(p->!p.toString().contains("/dev/umb/bridge/api/")&&!p.toString().contains("\\dev\\umb\\bridge\\api\\")).collect(Collectors.toList());
        Path mainArgs=argFile("hostagent-main",main); List<String> a=new ArrayList<>(List.of("-nowarn","-encoding","UTF-8","-source","21","-target","21","-cp",cp(join(List.of(game.get(0)),List.of()))));
        List<Path> hostcp=new ArrayList<>(game); hostcp.addAll(List.of(asm,asmTree,asmCommons)); hostcp.addAll(tier); hostcp.add(mirrorClasses); a.set(a.indexOf(cp(List.of(game.get(0)))),cp(hostcp)); a.addAll(List.of("-d",classes.toString(),"@"+mainArgs)); run(cmd(javac,a),root);
        copyTree(mirrorClasses.resolve("dev/umb/bridge/api"),classes.resolve("dev/umb/bridge/api"));
        Path gate=build.resolve("bridge-api-canonical-check"); deleteTree(gate); extractJar(legacy.resolve("umb-bridge-api.jar"),gate); int count=0; try(Stream<Path> s=Files.list(classes.resolve("dev/umb/bridge/api"))){for(Path p:s.filter(x->x.toString().endsWith(".class")).toList()){count++; Path q=gate.resolve("dev/umb/bridge/api").resolve(p.getFileName()); if(!Files.exists(q)||!Arrays.equals(Files.readAllBytes(p),Files.readAllBytes(q))) die("BRIDGE-API-MIRROR-MISMATCH: "+p.getFileName());}}
        if(count==0) die("bridge-api mirror produced no class files"); System.out.println("BRIDGE-API-MIRROR-OK: "+count+" class files byte-identical to canonical umb-bridge-api.jar");
        copyTree(classes,stage); for(Path p:List.of(asm,asmTree,asmCommons)){extractJar(p,stage);} for(Path p:bundledTier){extractJar(p,stage);} deleteTree(stage.resolve("META-INF")); Files.deleteIfExists(stage.resolve("module-info.class"));
        Path manifest=build.resolve("MANIFEST.MF"); Files.writeString(manifest,"Manifest-Version: 1.0\nPremain-Class: dev.umb.hostagent.HostAgent\nAgent-Class: dev.umb.hostagent.HostAgent\nCan-Retransform-Classes: true\nImplementation-Title: umb-hostagent\nImplementation-Vendor: UniversalModBridge\n\n",StandardCharsets.US_ASCII); Path out=build.resolve("umb-hostagent.jar"); jarWithManifest(stage,out,manifest); System.out.println("HOSTAGENT-BUILD-OK: "+out+" bytes="+Files.size(out));
    }

    private void buildObjBridge() throws Exception {
        Path build=root.resolve("build/objbridge"), classes=build.resolve("classes"), stage=build.resolve("stage"); deleteTree(classes); deleteTree(stage); Files.createDirectories(classes); Files.createDirectories(stage); Path cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"); List<Path> game=classpathFile(cpFile); List<Path> asm=List.of(existing(toolsJunit.resolve("asm-9.9.jar"),"ASM"),existing(toolsJunit.resolve("asm-tree-9.9.jar"),"ASM tree"),existing(toolsJunit.resolve("asm-commons-9.9.jar"),"ASM commons")); List<Path> cc=new ArrayList<>(game); cc.addAll(asm); compileWithSources("objbridge-main",sources(root.resolve("umb-objbridge/src/main/java")),cp(cc),classes,"21"); copyTree(classes,stage); for(Path p:asm) extractJar(p,stage); deleteTree(stage.resolve("META-INF")); Files.deleteIfExists(stage.resolve("module-info.class")); Path manifest=build.resolve("MANIFEST.MF"); Files.writeString(manifest,"Manifest-Version: 1.0\nPremain-Class: dev.umb.objbridge.ObjBridgeAgent\nAgent-Class: dev.umb.objbridge.ObjBridgeAgent\nCan-Retransform-Classes: true\nImplementation-Title: umb-objbridge\nImplementation-Vendor: UniversalModBridge\n\n",StandardCharsets.US_ASCII); Path out=build.resolve("umb-objbridge.jar"); jarWithManifest(stage,out,manifest); System.out.println("OBJBRIDGE-BUILD-OK: "+out+" bytes="+Files.size(out));
    }

    private void compileWithSources(String name,List<Path> ss,String classpath,Path dest,String release) throws Exception { deleteTree(dest); Files.createDirectories(dest); List<String> a=new ArrayList<>(List.of("-nowarn","-encoding","UTF-8","-source",release,"-target",release,"-cp",classpath,"-d",dest.toString(),"@"+argFile(name,ss))); run(cmd(javac,a),root); System.out.println("compiled "+name+" ("+ss.size()+" sources)"); }
    private void jarWithManifest(Path classes,Path destination,Path manifest) throws Exception { Files.deleteIfExists(destination); run(cmd(jar,"--create","--file",destination.toString(),"--manifest",manifest.toString(),"-C",classes.toString(),"."),root); }

    private void buildRenderMap() throws Exception { buildSimpleModule("rendermap",root.resolve("umb-rendermap/src/main/java"),List.of(toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),toolsJunit.resolve("gson.jar")),"21"); }
    private void buildGuiMap() throws Exception { buildSimpleModule("guimap",root.resolve("umb-guimap/src/main/java"),List.of(toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),toolsJunit.resolve("gson.jar")),"21"); }
    private void buildSimpleModule(String name,Path src,List<Path> deps,String release) throws Exception { List<Path> d=deps.stream().map(p->existing(p,p.getFileName().toString())).toList(); Path classes=root.resolve("build/"+name+"/classes"); compileWithSources(name,sources(src),cp(d),classes,release); Path out=root.resolve("build/"+name+"/umb-"+name+".jar"); jar(classes,out,name.equals("rendermap")?"dev.umb.rendermap.RenderMap":null); }

    private void runTests() throws Exception { runJUnit("rendermap","dev.umb.rendermap",List.of()); runJUnit("guimap","dev.umb.guimap",List.of()); runHostTests(); runObjTests(); runLegacy1122Tests(); runLegacy1165Tests(); System.out.println("UMB-TEST-OK"); }
    private void runJUnit(String module,String pkg,List<Path> extra) throws Exception {
        Path main=root.resolve("build/"+module+"/classes"), tests=root.resolve("build/"+module+"/test-classes");
        List<Path> deps=new ArrayList<>(List.of(main,junit,toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),toolsJunit.resolve("gson.jar"))); deps.addAll(extra);
        compileWithSources(module+"-tests",sources(root.resolve("umb-"+module+"/src/test/java")),cp(deps),tests,"21");
        run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",tests+File.pathSeparator+cp(deps),"--select-package",pkg,"--details=summary","--disable-ansi-colors")),root);
    }
    private void runHostTests() throws Exception { Path build=root.resolve("build/hostagent"), test=build.resolve("test-classes"), main=build.resolve("classes"), cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"); List<Path> game=classpathFile(cpFile); List<Path> asm=List.of(toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),toolsJunit.resolve("asm-analysis-9.9.jar"),toolsJunit.resolve("asm-util-9.9.jar")); List<Path> tier=List.of(root.resolve("build/legacy/umb-legacy-boot.jar"),root.resolve("build/legacy/umb-legacy-api.jar"),firstMatching(input("research/visual/mc1710-native/libraries"),"launchwrapper-.*\\.jar"),firstMatching(input("research/visual/mc1710-native/libraries"),"jopt-simple-.*\\.jar")); List<Path> all=new ArrayList<>(); all.add(main); all.add(junit); all.addAll(asm); all.addAll(tier); all.addAll(game); compileWithSources("hostagent-tests",sources(root.resolve("umb-hostagent/src/test/java")),cp(all),test,"21"); String runCp=cp(join(List.of(test),all)); run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",runCp,"--select-class","dev.umb.hostagent.content.UmbLegacyBlockTest","--select-class","dev.umb.hostagent.content.DynFieldChannelTest","--details=summary","--disable-ansi-colors")),root); run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",runCp,"--scan-class-path",test.toString(),"--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbLegacyBlockTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.DynFieldChannelTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbMenuAdapterCrossLoaderTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbMenuContentParityTest","--details=summary","--disable-ansi-colors")),root); run(cmd(java,List.of("-javaagent:"+build.resolve("umb-hostagent.jar")+"=log="+build.resolve("test-agent.log")+";ns=hbm;launchwrapper="+tier.get(2),"-cp",test+File.pathSeparator+runCp,"org.junit.platform.console.ConsoleLauncher","execute","--select-class","dev.umb.hostagent.content.UmbMenuAdapterCrossLoaderTest","--select-class","dev.umb.hostagent.content.UmbMenuContentParityTest","--details=summary","--disable-ansi-colors")),root); }
    private void runObjTests() throws Exception { Path b=root.resolve("build/objbridge"), cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"), main=b.resolve("classes"), test=b.resolve("test-classes"); List<Path> game=classpathFile(cpFile); List<Path> deps=new ArrayList<>(List.of(main,junit,toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),root.resolve("build/hostagent/umb-hostagent.jar"))); deps.addAll(game); compileWithSources("objbridge-tests",sources(root.resolve("umb-objbridge/src/test/java")),cp(deps),test,"21"); run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",test+File.pathSeparator+cp(deps),"--scan-class-path",test.toString(),"--details=summary","--disable-ansi-colors")),root); }

    private void buildLegacy1122() throws Exception { Path m=root.resolve("umb-legacy-1122"), b=m.resolve("build"), lw=input("research/out/legacy-1122/libs/launchwrapper-1.12.jar"); if(!Files.exists(lw)){System.out.println("1122-BUILD-SKIP: missing "+lw);return;} Path packSrc=m.resolve("src/pack200/java"), packClasses=b.resolve("classes-pack200"); List<Path> packSources=sources(packSrc); Files.createDirectories(packClasses); run(cmd(optionalJdk21Tool("javac"),"-nowarn","-encoding","UTF-8","--patch-module","java.base="+packSrc,"-d",packClasses.toString(),"@"+argFile("1122-pack200",packSources)),root); jar(packClasses,b.resolve("umb-legacy1122-pack200.jar"),null); Path bridge=b.resolve("umb-legacy1122-bridge-api.jar"); compile("1122-bridge",m.resolve("src/bridge-api/java"),"","8",b.resolve("classes-bridge-api")); jar(b.resolve("classes-bridge-api"),bridge,null); Path api=b.resolve("umb-legacy1122-api.jar"); compile("1122-api",m.resolve("src/api/java"),"","8",b.resolve("classes-api")); jar(b.resolve("classes-api"),api,null); compile("1122-legacyside",m.resolve("src/legacyside/java"),bridge.toString(),"8",b.resolve("classes-legacyside")); jar(b.resolve("classes-legacyside"),b.resolve("umb-legacy1122-legacyside.jar"),null); compile("1122-boot",m.resolve("src/boot/java"),cp(List.of(api,lw)),"8",b.resolve("classes-boot")); jar(b.resolve("classes-boot"),b.resolve("umb-legacy1122-boot.jar"),null); }
    private void buildLegacy1165() throws Exception { Path m=root.resolve("umb-legacy-1165"), b=m.resolve("build"); compile("1165-bridge",m.resolve("src/bridge-api/java"),"","8",b.resolve("classes-bridge-api")); jar(b.resolve("classes-bridge-api"),b.resolve("umb-legacy1165-bridge-api.jar"),null); compile("1165-api",m.resolve("src/api/java"),"","8",b.resolve("classes-api")); jar(b.resolve("classes-api"),b.resolve("umb-legacy1165-api.jar"),null); Path cpFile=m.resolve("resources/classpath-1165.txt"); List<Path> deps=new ArrayList<>(); if(Files.exists(cpFile)) deps.addAll(manifestPaths(cpFile)); else System.out.println("1165-BUILD-NOTE: classpath manifest absent; compiling the source-only units"); Path jsr=input("research/out/legacy-1165/rename/tools/jsr305-3.0.2.jar"); if(Files.isRegularFile(jsr)) deps.add(jsr); Path mcpZip=input("research/out/legacy-1165/rename/inputs/mcp_config-1.16.5-20210115.111550.zip"), mcpSrc=b.resolve("mcp-src"), mcpClasses=b.resolve("classes-mcp"), mcpJar=b.resolve("mcp-annotations.jar"); if(Files.isRegularFile(mcpZip)){deleteTree(mcpSrc);extractJar(mcpZip,mcpSrc);Path anno=mcpSrc.resolve("config/inject/mcp/MethodsReturnNonnullByDefault.java");compileWithSources("1165-mcp-annotations",List.of(anno),cp(deps),mcpClasses,"8");jar(mcpClasses,mcpJar,null);deps.add(mcpJar);} deps.add(b.resolve("umb-legacy1165-bridge-api.jar")); deps.add(b.resolve("umb-legacy1165-api.jar")); compile("1165-legacyside",m.resolve("src/legacyside/java"),cp(deps),"8",b.resolve("classes-legacyside")); jar(b.resolve("classes-legacyside"),b.resolve("umb-legacy1165-legacyside.jar"),null); compile("1165-boot",m.resolve("src/boot/java"),cp(List.of(b.resolve("umb-legacy1165-api.jar"),b.resolve("umb-legacy1165-bridge-api.jar"))),"21",b.resolve("classes-boot")); jar(b.resolve("classes-boot"),b.resolve("umb-legacy1165-boot.jar"),null); }
    private void runLegacy1122Tests() throws Exception { Path m=root.resolve("umb-legacy-1122"), b=m.resolve("build"), tc=b.resolve("test-classes"); if(!Files.exists(b.resolve("umb-legacy1122-boot.jar"))){System.out.println("1122-TEST-SKIP");return;} Path lw=input("research/out/legacy-1122/libs/launchwrapper-1.12.jar"); List<Path> d=List.of(b.resolve("umb-legacy1122-bridge-api.jar"),b.resolve("umb-legacy1122-api.jar"),b.resolve("umb-legacy1122-legacyside.jar"),b.resolve("umb-legacy1122-boot.jar"),lw,junit,toolsJunit.resolve("asm-9.9.jar")); compileWithSources("1122-tests",sources(m.resolve("src/test/java")),cp(d),tc,"21"); run(cmd(java,List.of("-Dumb.repo="+root,"-cp",tc+File.pathSeparator+cp(d),"org.junit.platform.console.ConsoleLauncher","execute","--scan-classpath","--details=summary","--disable-ansi-colors")),root); }
    private void runLegacy1165Tests() throws Exception { Path m=root.resolve("umb-legacy-1165"), b=m.resolve("build"); if(!Files.exists(b.resolve("umb-legacy1165-boot.jar"))){System.out.println("1165-TEST-SKIP");return;} System.out.println("1165-TEST-NOTE: use umb-legacy-1165/run-tests.ps1 for the real-jar lifecycle gate"); }

    private void makeRelease() throws Exception { Path dist=root.resolve("dist"), staging=root.resolve("build/release/umb"); deleteTree(staging); Files.createDirectories(staging); List<Path> own=List.of(root.resolve("build/legacy/umb-legacy-api.jar"),root.resolve("build/legacy/umb-legacy-boot.jar"),root.resolve("build/legacy/umb-bridge-api.jar"),Files.isRegularFile(root.resolve("build/packaging-staging/umb-legacy-legacyside.jar")) ? root.resolve("build/packaging-staging/umb-legacy-legacyside.jar") : root.resolve("build/legacy/umb-legacy-legacyside.jar"),root.resolve("build/legacy/umb-legacy-legacyside-stage1.jar"),root.resolve("build/hostagent/umb-hostagent.jar"),root.resolve("build/objbridge/umb-objbridge.jar"),root.resolve("build/rendermap/umb-rendermap.jar"),root.resolve("build/guimap/umb-guimap.jar"),root.resolve("umb-legacy-1165/build/umb-legacy1165-api.jar"),root.resolve("umb-legacy-1165/build/umb-legacy1165-boot.jar"),root.resolve("umb-legacy-1165/build/umb-legacy1165-bridge-api.jar"),root.resolve("umb-legacy-1165/build/umb-legacy1165-legacyside.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-api.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-boot.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-bridge-api.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-legacyside.jar")); for(Path p:own) if(Files.exists(p)) Files.copy(p,staging.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING); Path installer=root.resolve("build/installer/umb-installer.jar"); buildInstaller(); Files.copy(installer,staging.resolve("umb-installer.jar"),StandardCopyOption.REPLACE_EXISTING); Files.copy(root.resolve("docs/INSTALL.md"),staging.resolve("INSTALL.md"),StandardCopyOption.REPLACE_EXISTING); Path notices=staging.resolve("LICENSE-THIRD-PARTY.txt"); Files.writeString(notices,"UniversalModBridge release notices\n\nASM 9.9 is bundled in the agent jars. ASM is licensed under the BSD 3-Clause license; see https://asm.ow2.io/license.html.\nnet/minecraft/client/renderer/Tessellator.class in umb-legacy-legacyside.jar is an original UMB capture-shim implementation, not Mojang code. Source: umb-legacy/src/legacyside/java/net/minecraft/client/renderer/Tessellator.java.\nMinecraft, Forge, MCP/SRG mappings, LaunchWrapper, and player mod jars are not included. They remain the property of their respective owners and are obtained by the installer from official sources or the player's disk.\n",StandardCharsets.UTF_8); scanRelease(staging); Files.createDirectories(dist); Path zip=dist.resolve("umb-0.1.0-alpha.zip"); Files.deleteIfExists(zip); run(cmd(jar,"--create","--file",zip.toString(),"-C",staging.toString(),"."),root); System.out.println("RELEASE-ZIP: "+zip); try(JarFile z=new JarFile(zip.toFile())){z.stream().map(JarEntry::getName).sorted().forEach(n->System.out.println("  "+n));} }

    private void scanRelease(Path staging) throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> forbidden = List.of("net/minecraft/", "cpw/", "net/minecraftforge/", "com/hbm/", "mcheli/", "handmadeguns/", "hmggvcmob/");
        for (Map.Entry<String,String> shim : RELEASE_SHIMS.entrySet()) {
            if (!Files.isRegularFile(root.resolve(shim.getValue()))) bad.add("allowlist source missing: " + shim.getKey() + " <- " + shim.getValue());
        }
        try (Stream<Path> files=Files.list(staging)) {
            for (Path jarPath:files.filter(p->p.getFileName().toString().endsWith(".jar")).toList()) {
                try (JarFile z=new JarFile(jarPath.toFile())) {
                    Enumeration<JarEntry> e=z.entries();
                    while(e.hasMoreElements()) {
                        JarEntry entry=e.nextElement(); String n=entry.getName();
                        scanReleaseEntry(jarPath.getFileName()+"!",n,bad,forbidden);
                        if(n.endsWith(".jar")) try(JarInputStream nested=new JarInputStream(z.getInputStream(entry))) { JarEntry ne; while((ne=nested.getNextJarEntry())!=null) scanReleaseEntry(jarPath.getFileName()+"!"+n+"!",ne.getName(),bad,forbidden); }
                    }
                }
            }
        }
        if(!bad.isEmpty()) die("RELEASE-SCAN-FAIL: forbidden or unverified third-party/mod classes:\n  "+String.join("\n  ",bad));
        System.out.println("RELEASE-SCAN-OK: forbidden prefixes are explicit-source allow-listed only");
        RELEASE_SHIMS.forEach((className, source) -> System.out.println("  ALLOW " + className + " <- " + source));
    }
    private static void scanReleaseEntry(String container,String n,List<String> bad,List<String> forbidden) {
        if(!n.endsWith(".class")) return;
        for(String prefix:forbidden) if(n.startsWith(prefix)) {
            String source=RELEASE_SHIMS.get(n);
            if(source==null) bad.add(container+n+" (not on explicit UMB shim allowlist)");
            break;
        }
    }
    private void buildInstaller() throws Exception { Path src=root.resolve("tools/UmbInstaller.java"), b=root.resolve("build/installer"), c=b.resolve("classes"), stage=b.resolve("stage"); compileWithSources("installer",List.of(src),"",c,"21"); Path embeddedApiClasses=b.resolve("embedded-api-classes"), embeddedApi=b.resolve("umb-legacy-api.jar"); compileWithSources("installer-legacy-api",sources(root.resolve("umb-legacy/src/api/java")),"",embeddedApiClasses,"8"); jar(embeddedApiClasses,embeddedApi,null); Path packSrc=root.resolve("tools/pack200/java"), packClasses=b.resolve("pack200-classes"), packJar=b.resolve("umb-pack200.jar"); run(cmd(javac,"-nowarn","-encoding","UTF-8","--patch-module","java.base="+packSrc,"-d",packClasses.toString(),"@"+argFile("installer-pack200",sources(packSrc))),root); jar(packClasses,packJar,null); Path patchedBoot=b.resolve("umb-legacy-boot.jar"), patchedClasses=b.resolve("legacy-boot-classes"), api=embeddedApi, bridge=root.resolve("build/legacy/umb-bridge-api.jar"), lw=firstMatching(input("research/visual/mc1710-native/libraries"),"launchwrapper-.*\\.jar"), asm=toolsJunit.resolve("asm-9.9.jar"), asmTree=toolsJunit.resolve("asm-tree-9.9.jar"), asmCommons=toolsJunit.resolve("asm-commons-9.9.jar"); compileWithSources("installer-legacy-boot",sources(root.resolve("umb-legacy/src/boot/java")),cp(List.of(api,bridge,lw,asm,asmTree,asmCommons)),patchedClasses,"21"); jar(patchedClasses,patchedBoot,null); deleteTree(stage); Files.createDirectories(stage); copyTree(c,stage); Path bundled=stage.resolve("bundled"); Files.createDirectories(bundled); List<Path> own=List.of(root.resolve("build/hostagent/umb-hostagent.jar"),root.resolve("build/objbridge/umb-objbridge.jar"),api,patchedBoot,bridge,packJar,Files.isRegularFile(root.resolve("build/packaging-staging/umb-legacy-legacyside.jar")) ? root.resolve("build/packaging-staging/umb-legacy-legacyside.jar") : root.resolve("build/legacy/umb-legacy-legacyside.jar"),root.resolve("build/legacy/umb-legacy-legacyside-stage1.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-api.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-boot.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-bridge-api.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-legacyside.jar"),root.resolve("umb-legacy-1122/build/umb-legacy1122-pack200.jar"),root.resolve("build/rendermap/umb-rendermap.jar"),root.resolve("build/guimap/umb-guimap.jar")); for(Path p:own) { existing(p,"installer bundled UMB jar"); Files.copy(p,bundled.resolve(p.getFileName()),StandardCopyOption.REPLACE_EXISTING); } jar(stage,b.resolve("umb-installer.jar"),"UmbInstaller"); }
}
