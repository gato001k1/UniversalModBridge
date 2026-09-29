/*
 * UMB's JDK-only build driver.
 *
 * Run from any shell and on Windows, Linux, or macOS:
 *   java tools/UmbBuild.java build
 *   java tools/UmbBuild.java test
 *   java tools/UmbBuild.java release
 *
 * portable implementation and intentionally uses only java/javac/jar plus JDK
 * classes; no shell, Gradle, or OS-specific executable is required.
 */

import java.util.regex.Pattern;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        try { switch (positional.get(0)) {
            case "fetch" -> b.fetchInputs();
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
        }} catch (MissingInput e) {
            System.err.println("UMB missing input: " + e.getMessage() + ". Fix: java tools/UmbBuild.java fetch");
            System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("usage: java tools/UmbBuild.java <fetch|build|test|release|release-bundle|legacy|hostagent|objbridge|rendermap|guimap> [--root DIR] [--inputs DIR]");
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
    private static final class MissingInput extends RuntimeException { MissingInput(String message) { super(message); } }

    private void buildAll() throws Exception {
        ensureInputs();
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

    private Path existing(Path p, String label) { if (!Files.exists(p)) throw new MissingInput("missing "+label+": "+p); return p; }

    private void ensureInputs() throws Exception {
        if (!Files.isRegularFile(input("tools/junit/asm-9.9.jar"))
                || !Files.isRegularFile(input("research/visual/mc1710-native/classpath.txt"))
                || !Files.isRegularFile(input("research/jars/26.2/client.jar"))
                || !Files.isRegularFile(input("research/jars/1.12.2/client.jar"))
                || !Files.isRegularFile(input("research/jars/1.16.5/client.jar"))
                || !Files.isRegularFile(input("research/mappings/joined-1.7.10.srg"))) fetchInputs();
    }

    /** Portable, checksum-verified equivalent of ci-fetch.ps1. */
    private void fetchInputs() throws Exception {
        Fetcher f = new Fetcher();
        f.maven("https://repo1.maven.org/maven2", "org.ow2.asm", "asm", "9.9", "asm-9.9.jar", input("tools/junit/asm-9.9.jar"));
        for (String a : List.of("asm-tree", "asm-commons", "asm-analysis", "asm-util")) f.maven("https://repo1.maven.org/maven2", "org.ow2.asm", a, "9.9", a+"-9.9.jar", input("tools/junit/"+a+"-9.9.jar"));
        f.maven("https://repo1.maven.org/maven2", "com.google.code.gson", "gson", "2.14.0", "gson-2.14.0.jar", input("tools/junit/gson.jar"));
        f.maven("https://repo1.maven.org/maven2", "org.junit.platform", "junit-platform-console-standalone", "1.12.2", "junit-platform-console-standalone-1.12.2.jar", input("tools/junit/junit-platform-console-standalone.jar"));
        f.maven("https://repo1.maven.org/maven2", "org.apache.commons", "commons-compress", "1.21", "commons-compress-1.21.jar", input("tools/junit/commons-compress-1.21.jar"));
        Path v17=input("research/visual/mc1710-native"); f.mojang("1.7.10",v17,input("research/visual/mc1710-native/classpath.generated.txt"));
        Path client17=v17.resolve("client.jar"); Path v17ver=v17.resolve("versions/1.7.10-Forge10.13.4.1614-1.7.10/1.7.10-Forge10.13.4.1614-1.7.10.jar"); copyFile(client17,v17ver);
        Path forge17=v17.resolve("libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"); f.maven("https://maven.minecraftforge.net","net.minecraftforge","forge","1.7.10-10.13.4.1614-1.7.10",forge17.getFileName().toString(),forge17);
        Path lw=v17.resolve("libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"); f.verified("https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",lw);
        writeForgeClasspath(v17,forge17,v17ver,input("research/visual/mc1710-native/classpath.txt"));
        Path v26=input("research/visual/mc262-vanilla"); f.mojang("26.2",input("research/jars/26.2"),v26.resolve("classpath.generated.txt")); copyTree(input("research/jars/26.2/libraries"),v26.resolve("libraries")); copyFile(v26.resolve("classpath.generated.txt"),v26.resolve("classpath.txt"));
        for (String ver : List.of("1.12.2","1.16.5")) { Path d=input("research/jars/"+ver); f.mojang(ver,d,d.resolve("classpath.txt")); String fv=ver.equals("1.12.2")?"14.23.5.2860":"36.2.39"; f.maven("https://maven.minecraftforge.net","net.minecraftforge","forge",ver+"-"+fv,"forge-"+ver+"-"+fv+"-universal.jar",d.resolve("forge-"+ver+"-"+fv+"-universal.jar")); }
        copyFile(lw,input("research/out/legacy-1122/libs/launchwrapper-1.12.jar"));
        f.maven("https://repo1.maven.org/maven2","org.ow2.asm","asm-debug-all","5.2","asm-debug-all-5.2.jar",input("research/out/legacy-1122/libs/asm-debug-all-5.2.jar"));
        Path mcp=input("research/mappings/ci-mcp-1.7.10-srg.zip"); f.verified("https://mcp.zeith.org/mcp/1.7.10/mcp-1.7.10-srg.zip",mcp); extractMapping(mcp,input("research/mappings/joined-1.7.10.srg"));
        // This is the former cache-only DEBUG_SAVE artifact. ClientSrgifier is the
        // deterministic source-of-truth and consumes only the fetched inputs above.
        generateRuntime(client17,forge17,input("research/visual/mc1710-native/classpath.txt"),input("research/mappings/joined-1.7.10.srg"));
        System.out.println("UMB-FETCH-OK");
    }

    private void generateRuntime(Path client, Path forge, Path cpFile, Path mapping) throws Exception {
        Path boot=root.resolve("build/legacy/umb-legacy-boot.jar");
        if (!Files.isRegularFile(boot)) { buildLegacy(true,false); boot=root.resolve("build/legacy/umb-legacy-boot.jar"); }
        Path raw=input("research/out/legacy/1.7.10-forge-srg-runtime.jar");
        if (Files.isRegularFile(raw)) return;
        Path work=input("research/out/legacy/client-patch"); Files.createDirectories(work);
        List<Path> cp=classpathFile(cpFile); cp.add(0,client); cp.add(1,forge); cp.add(2,boot); cp.add(3,toolsJunit.resolve("asm-9.9.jar")); cp.add(4,toolsJunit.resolve("asm-tree-9.9.jar")); cp.add(5,toolsJunit.resolve("asm-commons-9.9.jar")); cp.add(6,toolsJunit.resolve("commons-compress-1.21.jar"));
        Path packSource=root.resolve("tools/pack200/java"), pack200=root.resolve("build/pack200-runtime-classes");
        deleteTree(pack200); Files.createDirectories(pack200);
        run(cmd(javac,List.of("--patch-module","java.base="+packSource,"-d",pack200.toString(),"@"+argFile("runtime-pack200",sources(packSource)))),root);
        List<String> runtimeArgs=new ArrayList<>(List.of("--patch-module","java.base="+pack200,"--add-opens","java.base/java.io=ALL-UNNAMED","-Dumb.repo="+root,"-Dumb.legacy.classpathFile="+cpFile,"-cp",cp(cp),"dev.umb.legacy.boot.ClientSrgifier",mapping.toString(),client.toString(),raw.toString(),forge.toString(),cpFile.toString(),work.toString()));
        run(cmd(java,runtimeArgs),root);
        if (!Files.isRegularFile(raw)) throw new IllegalStateException("runtime generation produced no output: "+raw);
    }

    private void extractMapping(Path zip, Path destination) throws IOException {
        if (Files.isRegularFile(destination)) return; Files.createDirectories(destination.getParent());
        try (java.util.zip.ZipFile z=new java.util.zip.ZipFile(zip.toFile())) { java.util.zip.ZipEntry e=z.stream().filter(x->x.getName().matches("(?i)(^|.*/)joined\\.srg")).findFirst().orElseThrow(()->new IOException("MCP archive has no joined.srg")); try(InputStream in=z.getInputStream(e)){Files.copy(in,destination);}}
    }

    private void copyFile(Path from, Path to) throws IOException { Files.createDirectories(to.toAbsolutePath().getParent()); if (Files.isRegularFile(from)) { if (Files.isRegularFile(to) && Files.mismatch(from,to)<0) return; Files.copy(from,to,StandardCopyOption.REPLACE_EXISTING); } }

    private void writeForgeClasspath(Path root17, Path forge, Path versionJar, Path destination) throws Exception {
        Path template=root17.resolve("launch-cmd.txt"); if(!Files.isRegularFile(template)) template=root.resolve("tools/forge-1710-launch-classpath.txt");
        String command=Files.readString(template,StandardCharsets.UTF_8);
        int q=command.indexOf('"'), end=command.indexOf('"',q+1);
        if(q<0||end<0) throw new IOException("Forge launcher metadata has no quoted classpath: "+template);
        List<String> relative=dropOlderLibraries(Arrays.asList(command.substring(q+1,end).split("[;]")));
        List<String> paths=new ArrayList<>();
        Fetcher fetcher=new Fetcher();
        for(String r:relative){ Path p=root17.resolve(r.replace('/',File.separatorChar)).normalize(); if(!Files.isRegularFile(p) && r.startsWith("libraries/")) fetcher.library(r.substring("libraries/".length()),p); if(!Files.isRegularFile(p)) throw new IOException("Forge classpath entry is missing: "+r); paths.add(p.toAbsolutePath().toString()); }
        String forgePath=forge.toAbsolutePath().toString(); paths.removeIf(p->p.equals(forgePath)); paths.add(0,forgePath);
        String lw=root17.resolve("libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar").toAbsolutePath().toString();
        if(!paths.contains(lw)) paths.add(1,lw);
        String version=versionJar.toAbsolutePath().toString(); if(!paths.contains(version)) paths.add(version);
        Files.createDirectories(destination.getParent()); Files.writeString(destination,String.join(File.pathSeparator,paths)+System.lineSeparator(),StandardCharsets.UTF_8);
    }

    /** Removes an older copy of the same Maven group/artifact while preserving exact duplicates. */
    static List<String> dropOlderLibraries(List<String> entries) {
        Map<String,String> versions=new HashMap<>(); Map<String,Integer> positions=new HashMap<>(); List<String> out=new ArrayList<>();
        for(String entry:entries){String e=entry.trim(); if(e.isEmpty())continue; String normalized=e.replace('\\','/'); int lib=normalized.toLowerCase(Locale.ROOT).indexOf("/libraries/"); if(lib>=0) normalized=normalized.substring(lib+1); String[] p=normalized.split("/");
            if(p.length>=5 && "libraries".equalsIgnoreCase(p[0])) { String key=String.join("/",Arrays.copyOfRange(p,1,p.length-2)); String version=p[p.length-2]; String marker=key+"@"; boolean older=false;
                String prior=versions.get(key); if(prior!=null && !prior.equals(version)){ int cmp=compareVersions(version,prior); if(cmp<=0) older=true; else { int old=positions.get(key); out.set(old,null); versions.put(key,version); positions.put(key,out.size()); } }
                if(older)continue; versions.putIfAbsent(key,version); positions.putIfAbsent(key,out.size());
            } out.add(e);
        } return out.stream().filter(Objects::nonNull).toList();
    }
    private static int compareVersions(String a,String b){String[] x=a.split("[^0-9]+"),y=b.split("[^0-9]+");int n=Math.max(x.length,y.length);for(int i=0;i<n;i++){int u=i<x.length&&!x[i].isEmpty()?Integer.parseInt(x[i]):0,v=i<y.length&&!y[i].isEmpty()?Integer.parseInt(y[i]):0;if(u!=v)return Integer.compare(u,v);}return a.compareTo(b);}

    private final class Fetcher {
        private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        private final Path cache=input(".ci-cache/downloads");
        private String text(String url) throws Exception { return new String(http.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofByteArray()).body(),StandardCharsets.UTF_8).trim(); }
        private byte[] bytes(String url) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofByteArray()).body(); }
        private void verified(String url,Path dest) throws Exception { String sha=text(url+".sha1").split("\\s+")[0]; Files.createDirectories(dest.getParent()); if(Files.isRegularFile(dest)&&sha.equalsIgnoreCase(hash(dest,"SHA-1")))return; Path tmp=cache.resolve(Integer.toHexString(url.hashCode())+".download"); Files.createDirectories(cache); Files.write(tmp,bytes(url)); if(!sha.equalsIgnoreCase(hash(tmp,"SHA-1")))throw new IOException("SHA-1 mismatch: "+url); Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING); }
        private void verified(String url,String sha,Path dest) throws Exception { Files.createDirectories(dest.getParent()); if(Files.isRegularFile(dest)&&sha.equalsIgnoreCase(hash(dest,"SHA-1")))return; Path tmp=cache.resolve(Integer.toHexString(url.hashCode())+".download"); Files.createDirectories(cache); Files.write(tmp,bytes(url)); if(!sha.equalsIgnoreCase(hash(tmp,"SHA-1")))throw new IOException("SHA-1 mismatch: "+url); Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING); }
        private void maven(String base,String group,String artifact,String version,String file,Path dest)throws Exception{String u=base+"/"+group.replace('.','/')+"/"+artifact+"/"+version+"/"+file;verified(u,dest);}
        private void library(String relative,Path dest)throws Exception{String pin=null;Path pins=root.resolve("tools/forge-1710-library-sha1.txt");if(Files.isRegularFile(pins))for(String line:Files.readAllLines(pins)){String[] p=line.trim().split("\\s+");if(p.length==2&&p[1].equals(relative)){pin=p[0];break;}}Exception last=null;for(String base:List.of("https://maven.minecraftforge.net","https://repo1.maven.org/maven2","https://libraries.minecraft.net")){String u=base+"/"+relative;try{String sha=pin!=null?pin:text(u+".sha1").split("\\s+")[0];verified(u,sha,dest);return;}catch(Exception e){last=e;}}throw new IOException("unable to fetch Forge launcher library "+relative,last);}
        private void mojang(String version,Path target,Path cpFile)throws Exception{Map<?,?> manifest=(Map<?,?>)Json.parse(text("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")); Map<?,?> found=null; for(Object o:(List<?>)manifest.get("versions")){Map<?,?> e=(Map<?,?>)o;if(version.equals(e.get("id"))){found=e;break;}} if(found==null)throw new IOException("Mojang version not found: "+version); Map<?,?> j=(Map<?,?>)Json.parse(text((String)found.get("url"))); Files.createDirectories(target); Map<?,?> dl=(Map<?,?>)j.get("downloads"); Map<?,?> c=(Map<?,?>)dl.get("client"); Path client=target.resolve("client.jar"); download((String)c.get("url"),(String)c.get("sha1"),client); List<Path> entries=new ArrayList<>(); Object lo=j.get("libraries"); if(lo instanceof List<?> list) for(Object x:list){Map<?,?> lib=(Map<?,?>)x; if(!allowed(lib.get("rules")))continue; Map<?,?> d=(Map<?,?>)lib.get("downloads"); if(d==null)continue; Object ao=d.get("artifact"); if(ao instanceof Map<?,?> a){Path out=target.resolve("libraries").resolve(((String)a.get("path")).replace('/',File.separatorChar));download((String)a.get("url"),(String)a.get("sha1"),out);entries.add(out);} } entries.add(client); Files.createDirectories(cpFile.getParent()); Files.writeString(cpFile,entries.stream().map(p->p.toAbsolutePath().toString()).distinct().collect(Collectors.joining(File.pathSeparator))+System.lineSeparator(),StandardCharsets.UTF_8); }
        private boolean allowed(Object rules){if(!(rules instanceof List<?> list))return true; boolean ok=false;for(Object x:list){if(!(x instanceof Map<?,?> r))continue;Object os=r.get("os");boolean match=true;if(os instanceof Map<?,?> m && m.get("name") instanceof String n){String want=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"windows":System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")?"osx":"linux";match=want.equals(n);}if(match)ok="allow".equals(r.get("action"));}return ok;}
        private void download(String u,String sha,Path d)throws Exception{Files.createDirectories(d.getParent());if(Files.isRegularFile(d)&&sha.equalsIgnoreCase(hash(d,"SHA-1")))return;Path t=cache.resolve(Integer.toHexString(u.hashCode())+".download");Files.write(t,bytes(u));if(!sha.equalsIgnoreCase(hash(t,"SHA-1")))throw new IOException("SHA-1 mismatch: "+u);Files.move(t,d,StandardCopyOption.REPLACE_EXISTING);}
    }

    private static String hash(Path p,String algorithm) throws Exception { MessageDigest d=MessageDigest.getInstance(algorithm); try(InputStream in=Files.newInputStream(p)){in.transferTo(new OutputStream(){public void write(int b){d.update((byte)b);}public void write(byte[] b,int o,int l){d.update(b,o,l);}});} StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b));return s.toString(); }

    private static final class Json {
        static Object parse(String s){return new Parser(s).value();}
        private static final class Parser { final String s; int p; Parser(String s){this.s=s;} void ws(){while(p<s.length()&&s.charAt(p)<=32)p++;} Object value(){ws();char c=s.charAt(p);if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(s.startsWith("true",p)){p+=4;return Boolean.TRUE;}if(s.startsWith("false",p)){p+=5;return Boolean.FALSE;}if(s.startsWith("null",p)){p+=4;return null;}int q=p;while(p<s.length()&&"-+.0123456789eE".indexOf(s.charAt(p))>=0)p++;return Double.valueOf(s.substring(q,p));} Map<String,Object> object(){Map<String,Object> m=new LinkedHashMap<>();p++;ws();while(s.charAt(p)!='}'){String k=string();ws();p++;m.put(k,value());ws();if(s.charAt(p)==','){p++;ws();}}p++;return m;} List<Object> array(){List<Object> a=new ArrayList<>();p++;ws();while(s.charAt(p)!=']'){a.add(value());ws();if(s.charAt(p)==','){p++;ws();}}p++;return a;} String string(){p++;StringBuilder b=new StringBuilder();while(s.charAt(p)!='"'){char c=s.charAt(p++);if(c=='\\'){char n=s.charAt(p++);b.append(n=='n'?'\n':n=='r'?'\r':n=='t'?'\t':n);}else b.append(c);}p++;return b.toString();} }
    }

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
            // The source distribution does not contain player-owned legacy mod jars or
            // their generated test fixtures. Keep a clean checkout buildable; the full
            // legacy test suite still runs when the local corpus is present.
            boolean haveLegacyTestCorpus = Files.isRegularFile(input("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar"));
            if (tests && haveLegacyTestCorpus) {
                Path tc=build.resolve("classes-test");
                compile("legacy-tests",root.resolve("umb-legacy/src/test/java"),cp(join(List.of(api,canonicalApi,boot,lw,fields,srgForge,ls,junit,asm),libs)),"21",tc);
                run(cmd(java,List.of("-Djava.awt.headless=true","-Dumb.repo="+root,"-Dumb.legacy.classpathFile="+nativeClasspath,"--sun-misc-unsafe-memory-access=allow","--add-opens","java.base/java.lang=ALL-UNNAMED","--add-opens","java.base/java.lang.reflect=ALL-UNNAMED","-jar",junit.toString(),"execute","--class-path",cp(join(List.of(tc,api,canonicalApi,boot,lw,fields,srgForge,ls,jopt,log4jApi,log4jCore,asm),libs)),"--select-package","dev.umb.legacy.test","--details=summary","--disable-banner")),root);
            } else if (tests) {
                System.out.println("LEGACY-1710-TEST-SKIP: missing local legacy mod corpus; source build remains available, run tests with research/mods-hbm/HBM-NTM-1.0.27_X5771.jar");
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
    /** On CI (env CI set) skip test classes that read locally generated corpus data (research/out, research/mods-*), which a clean checkout never has. */
    private List<String> ciCorpusExcludes(Path testSrc) throws Exception {
        List<String> out = new ArrayList<>();
        if (System.getenv("CI") == null || !Files.isDirectory(testSrc)) return out;
        Pattern needsCorpus = Pattern.compile("research(?:[/\\\\]+|\"\\s*,\\s*\")(out|mods-|mappings[/\\\\]+joined-1\\.12)|classpath-1122\\.txt");
        try (Stream<Path> s = Files.walk(testSrc)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (!needsCorpus.matcher(Files.readString(p)).find()) continue;
                String cls = testSrc.relativize(p).toString().replace('\\', '/').replace('/', '.');
                cls = cls.substring(0, cls.length() - ".java".length());
                out.add("--exclude-classname");
                out.add(Pattern.quote(cls) + ".*");
                System.out.println("CI-SKIP (needs local corpus data): " + cls);
            }
        }
        return out;
    }

    private void runHostTests() throws Exception { Path build=root.resolve("build/hostagent"), test=build.resolve("test-classes"), main=build.resolve("classes"), cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"); List<Path> game=classpathFile(cpFile); List<Path> asm=List.of(toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),toolsJunit.resolve("asm-analysis-9.9.jar"),toolsJunit.resolve("asm-util-9.9.jar")); List<Path> tier=List.of(root.resolve("build/legacy/umb-legacy-boot.jar"),root.resolve("build/legacy/umb-legacy-api.jar"),firstMatching(input("research/visual/mc1710-native/libraries"),"launchwrapper-.*\\.jar"),firstMatching(input("research/visual/mc1710-native/libraries"),"jopt-simple-.*\\.jar")); List<Path> all=new ArrayList<>(); all.add(main); all.add(junit); all.addAll(asm); all.addAll(tier); all.addAll(game); compileWithSources("hostagent-tests",sources(root.resolve("umb-hostagent/src/test/java")),cp(all),test,"21"); String runCp=cp(join(List.of(test),all)); run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",runCp,"--select-class","dev.umb.hostagent.content.UmbLegacyBlockTest","--select-class","dev.umb.hostagent.content.DynFieldChannelTest","--details=summary","--disable-ansi-colors")),root); List<String> scanArgs=new ArrayList<>(List.of("-jar",junit.toString(),"execute","--class-path",runCp,"--scan-class-path",test.toString(),"--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbLegacyBlockTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.DynFieldChannelTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbMenuAdapterCrossLoaderTest","--exclude-classname","dev\\.umb\\.hostagent\\.content\\.UmbMenuContentParityTest","--details=summary","--disable-ansi-colors")); scanArgs.addAll(ciCorpusExcludes(root.resolve("umb-hostagent/src/test/java"))); run(cmd(java,scanArgs),root); run(cmd(java,List.of("-javaagent:"+build.resolve("umb-hostagent.jar")+"=log="+build.resolve("test-agent.log")+";ns=hbm;launchwrapper="+tier.get(2),"-cp",test+File.pathSeparator+runCp,"org.junit.platform.console.ConsoleLauncher","execute","--select-class","dev.umb.hostagent.content.UmbMenuAdapterCrossLoaderTest","--select-class","dev.umb.hostagent.content.UmbMenuContentParityTest","--details=summary","--disable-ansi-colors")),root); }
    private void runObjTests() throws Exception { Path b=root.resolve("build/objbridge"), cpFile=existing(input("research/visual/mc262-vanilla/classpath.txt"),"26.2 classpath"), main=b.resolve("classes"), test=b.resolve("test-classes"); List<Path> game=classpathFile(cpFile); List<Path> deps=new ArrayList<>(List.of(main,junit,toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-commons-9.9.jar"),root.resolve("build/hostagent/umb-hostagent.jar"))); deps.addAll(game); compileWithSources("objbridge-tests",sources(root.resolve("umb-objbridge/src/test/java")),cp(deps),test,"21"); run(cmd(java,List.of("-jar",junit.toString(),"execute","--class-path",test+File.pathSeparator+cp(deps),"--scan-class-path",test.toString(),"--details=summary","--disable-ansi-colors")),root); }

    private void buildLegacy1122() throws Exception { Path m=root.resolve("umb-legacy-1122"), b=m.resolve("build"), lw=input("research/out/legacy-1122/libs/launchwrapper-1.12.jar"); if(!Files.exists(lw)){System.out.println("1122-BUILD-SKIP: missing "+lw);return;} Path packSrc=m.resolve("src/pack200/java"), packClasses=b.resolve("classes-pack200"); List<Path> packSources=sources(packSrc); Files.createDirectories(packClasses); run(cmd(optionalJdk21Tool("javac"),"-nowarn","-encoding","UTF-8","--patch-module","java.base="+packSrc,"-d",packClasses.toString(),"@"+argFile("1122-pack200",packSources)),root); jar(packClasses,b.resolve("umb-legacy1122-pack200.jar"),null); Path bridge=b.resolve("umb-legacy1122-bridge-api.jar"); compile("1122-bridge",m.resolve("src/bridge-api/java"),"","8",b.resolve("classes-bridge-api")); jar(b.resolve("classes-bridge-api"),bridge,null); Path api=b.resolve("umb-legacy1122-api.jar"); compile("1122-api",m.resolve("src/api/java"),"","8",b.resolve("classes-api")); jar(b.resolve("classes-api"),api,null); Path asm1122=input("research/out/legacy-1122/libs/asm-debug-all-5.2.jar"), guava1122=input("research/jars/1.12.2/libraries/com/google/guava/guava/21.0/guava-21.0.jar"); compile("1122-legacyside",m.resolve("src/legacyside/java"),cp(List.of(bridge,lw,asm1122,guava1122)),"8",b.resolve("classes-legacyside")); jar(b.resolve("classes-legacyside"),b.resolve("umb-legacy1122-legacyside.jar"),null); compile("1122-boot",m.resolve("src/boot/java"),cp(List.of(api,lw)),"8",b.resolve("classes-boot")); jar(b.resolve("classes-boot"),b.resolve("umb-legacy1122-boot.jar"),null); }
    private void buildLegacy1165() throws Exception { Path m=root.resolve("umb-legacy-1165"), b=m.resolve("build"); compile("1165-bridge",m.resolve("src/bridge-api/java"),"","8",b.resolve("classes-bridge-api")); jar(b.resolve("classes-bridge-api"),b.resolve("umb-legacy1165-bridge-api.jar"),null); compile("1165-api",m.resolve("src/api/java"),"","8",b.resolve("classes-api")); jar(b.resolve("classes-api"),b.resolve("umb-legacy1165-api.jar"),null); Path srgClient=input("research/out/legacy-1165/rename/output/mc-client-srg-at.jar"); if(!Files.isRegularFile(srgClient)){System.out.println("1165-BUILD-SKIP: missing remapped 1.16.5 client "+srgClient+" - built the bridge/api jars only; the legacyside and boot stages need the local SRG rename");return;} Path cpFile=m.resolve("resources/classpath-1165.txt"); List<Path> deps=new ArrayList<>(); if(Files.exists(cpFile)) deps.addAll(manifestPaths(cpFile)); else System.out.println("1165-BUILD-NOTE: classpath manifest absent; compiling the source-only units"); Path jsr=input("research/out/legacy-1165/rename/tools/jsr305-3.0.2.jar"); if(Files.isRegularFile(jsr)) deps.add(jsr); Path mcpZip=input("research/out/legacy-1165/rename/inputs/mcp_config-1.16.5-20210115.111550.zip"), mcpSrc=b.resolve("mcp-src"), mcpClasses=b.resolve("classes-mcp"), mcpJar=b.resolve("mcp-annotations.jar"); if(Files.isRegularFile(mcpZip)){deleteTree(mcpSrc);extractJar(mcpZip,mcpSrc);Path anno=mcpSrc.resolve("config/inject/mcp/MethodsReturnNonnullByDefault.java");compileWithSources("1165-mcp-annotations",List.of(anno),cp(deps),mcpClasses,"8");jar(mcpClasses,mcpJar,null);deps.add(mcpJar);} deps.add(b.resolve("umb-legacy1165-bridge-api.jar")); deps.add(b.resolve("umb-legacy1165-api.jar")); compile("1165-legacyside",m.resolve("src/legacyside/java"),cp(deps),"8",b.resolve("classes-legacyside")); jar(b.resolve("classes-legacyside"),b.resolve("umb-legacy1165-legacyside.jar"),null); compile("1165-boot",m.resolve("src/boot/java"),cp(List.of(b.resolve("umb-legacy1165-api.jar"),b.resolve("umb-legacy1165-bridge-api.jar"))),"21",b.resolve("classes-boot")); jar(b.resolve("classes-boot"),b.resolve("umb-legacy1165-boot.jar"),null); }
    private void runLegacy1122Tests() throws Exception { Path m=root.resolve("umb-legacy-1122"), b=m.resolve("build"), tc=b.resolve("test-classes"); if(!Files.exists(b.resolve("umb-legacy1122-boot.jar"))){System.out.println("1122-TEST-SKIP");return;} Path lw=input("research/out/legacy-1122/libs/launchwrapper-1.12.jar"); List<Path> d=List.of(b.resolve("umb-legacy1122-bridge-api.jar"),b.resolve("umb-legacy1122-api.jar"),b.resolve("umb-legacy1122-legacyside.jar"),b.resolve("umb-legacy1122-boot.jar"),lw,junit,toolsJunit.resolve("asm-9.9.jar"),toolsJunit.resolve("asm-tree-9.9.jar"),toolsJunit.resolve("asm-analysis-9.9.jar")); compileWithSources("1122-tests",sources(m.resolve("src/test/java")),cp(d),tc,"21"); List<String> args1122=new ArrayList<>(List.of("-Dumb.repo="+root,"-cp",tc+File.pathSeparator+cp(d),"org.junit.platform.console.ConsoleLauncher","execute","--scan-classpath","--details=summary","--disable-ansi-colors")); args1122.addAll(ciCorpusExcludes(m.resolve("src/test/java"))); run(cmd(java,args1122),root); }
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
