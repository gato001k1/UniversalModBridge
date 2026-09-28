/* UMB player-side installer.  It deliberately has no third-party dependency. */

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.regex.*;
import java.util.stream.*;

public final class UmbInstaller {
    private static final String MC = "26.2";
    private static final String MC1710 = "1.7.10";
    private static final String MC1122 = "1.12.2";
    private static final String FORGE = "1.7.10-10.13.4.1614-1.7.10";
    private static final String FORGE1122 = "1.12.2-14.23.5.2860";
    private static final String FORGE_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + FORGE + "/forge-" + FORGE + "-universal.jar";
    private static final String FORGE1122_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + FORGE1122 + "/forge-" + FORGE1122 + "-universal.jar";
    private static final String LAUNCHWRAPPER_URL = "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar";
    private static final String LAUNCHWRAPPER_NAME = "launchwrapper-1.12.jar";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String MCP_SRG_URL = "https://mcp.zeith.org/mcp/1.7.10/mcp-1.7.10-srg.zip";

    private record Mod(Path jar, String namespace) {}
    private record Generated(Mod mod, Path snapshot, Path assets, Path renderMap, Path transforms, Path gui, Path blockShapes, Path basePack, Path objPack, Path soundPack, Path fluidPack, Path inputPlans) {}

    public static void main(String[] args) throws Exception {
        Map<String,String> o = options(args);
        String minecraft=o.getOrDefault("minecraft",o.get("instance"));
        if (o.containsKey("help") || minecraft==null || (!o.containsKey("check") && !o.containsKey("mods"))) { usage(); return; }
        Path instance=Path.of(minecraft).toAbsolutePath().normalize();
        if (o.containsKey("check")) { checkInstall(instance); return; }
        Path mods=Path.of(o.get("mods")).toAbsolutePath().normalize();
        Path repo=o.containsKey("repo")?Path.of(o.get("repo")).toAbsolutePath().normalize():null;
        Path inputRoot=Path.of(o.getOrDefault("inputs", instance.toString())).toAbsolutePath().normalize();
        Files.createDirectories(instance); Files.createDirectories(mods);
        List<Mod> found=findMods(mods); if(found.isEmpty()) fail("No .jar files found in "+mods);
        Path umb=instance.resolve("umb"); Files.createDirectories(umb); for(String n:List.of("java","jna","lwjgl","netty"))Files.createDirectories(umb.resolve("natives").resolve(n));
        Path client=ensureClient(instance,umb,o.get("client"));
        Path forge=ensureForge(umb,o.get("forge"));
        Path launchwrapper=ensureLaunchwrapper(instance,umb,o.get("launchwrapper"));
        ensureLegacyArtifacts(instance,umb,o);
        Path forgeRuntime=findFirst(inputRoot.resolve("build/legacy"),"1.7.10-forge-srg-runtime-fields.jar");
        Path mapping=findMapping(inputRoot);
        List<Path> own=copyOwnJars(repo,umb);
        prepareLegacyInputs(found, instance, umb, own, o);
        Path tools=toolRoot(repo,umb,own);
        List<Generated> generated=new ArrayList<>();
        for(Mod mod:found) generated.add(generate(mod,repo,inputRoot,instance,umb,tools,o));
        installResourcePacks(instance,generated);
        Path manifest=writeManifest(umb,generated,mods);
        writeLog4jConfig(umb); Path argsFile=writeJvmArgs(umb,manifest,generated,mods,client,forge,forgeRuntime,mapping,launchwrapper,own); String argsLine=Files.readString(argsFile,StandardCharsets.UTF_8); String pack200Flag="--patch-module java.base=\""+umb.resolve("umb-legacy1122-pack200.jar")+"\" "; Files.writeString(argsFile,pack200Flag+"-Dlog4j.configurationFile=\""+umb.resolve("log4j-client.xml")+"\" "+argsLine,StandardCharsets.UTF_8);
        Files.writeString(argsFile, "-Dumb.legacy.transformers=dev.umb.legacy.legacyside.UmbShimTransformer,cpw.mods.fml.common.asm.transformers.MarkerTransformer,cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer,cpw.mods.fml.common.asm.transformers.AccessTransformer,net.minecraftforge.classloading.FluidIdTransformer,net.minecraftforge.transformers.ForgeAccessTransformer "+Files.readString(argsFile,StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        Files.writeString(argsFile, "-Dumb.home=\""+umb+"\" "+Files.readString(argsFile,StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        System.out.println("UMB-INSTALL-OK");
        System.out.println("mods="+found.size()+" generated="+generated.size());
        System.out.println("umb="+umb);
        System.out.println("jvm-arguments="+argsFile);
        System.out.println(Files.readString(argsFile));
    }

    private static void usage() { System.out.println("java -jar umb-installer.jar --minecraft MINECRAFT_INSTANCE --mods MOD_FOLDER"); System.out.println("java -jar umb-installer.jar --minecraft MINECRAFT_INSTANCE --check"); System.out.println("  --instance is accepted as an alias for --minecraft."); System.out.println("  --inputs and --repo are developer-only overrides; release users do not need them."); }
    private static Map<String,String> options(String[] a) {
        Map<String,String> r=new LinkedHashMap<>();
        for(int i=0;i<a.length;i++){String x=a[i]; if(x.equals("--help")){r.put("help","true");continue;} if(!x.startsWith("--")) fail("Unknown argument "+x); String k=x.substring(2); String v="true"; if(i+1<a.length&&!a[i+1].startsWith("--"))v=a[++i]; r.put(k,v);} return r;
    }
    private static void fail(String s) { throw new IllegalArgumentException(s); }

    private static List<Mod> findMods(Path dir) throws IOException {
        try(Stream<Path> s=Files.list(dir)){return s.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().map(p->new Mod(p,namespace(p))).toList();}
    }
    private static String namespace(Path jar) {
        try(JarFile z=new JarFile(jar.toFile())){
            for(String n:List.of("mcmod.info","META-INF/mods.toml","META-INF/MANIFEST.MF")){
                JarEntry e=z.getJarEntry(n); if(e==null)continue; String t=new String(z.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8);
                Matcher m=Pattern.compile("(?i)(?:\\\"modid\\\"|modId|modid)\\s*[=:]\\s*[\\\"]?([A-Za-z0-9_.-]+)").matcher(t); if(m.find())return cleanNs(m.group(1));
            }
        }catch(IOException ignored){}
        String n=jar.getFileName().toString().replaceFirst("(?i)\\.jar$","").replaceAll("(?i)-(?:[0-9].*)$","");
        return cleanNs(n.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }
    private static String cleanNs(String s){String n=s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]","_"); return n.isEmpty()?"legacy":n;}

    private static Path ensureClient(Path instance,Path umb,String explicit) throws Exception {
        if(explicit!=null)return require(Path.of(explicit),"client jar");
        List<Path> candidates=new ArrayList<>();
        candidates.add(instance.resolve("versions/"+MC+"/"+MC+".jar")); candidates.add(instance.resolve("versions/"+MC+"/client.jar")); candidates.add(instance.resolve(MC+".jar"));
        String home=System.getProperty("user.home"); candidates.add(Path.of(home,".minecraft/versions/"+MC+"/"+MC+".jar"));
        for(Path p:candidates)if(Files.isRegularFile(p)){System.out.println("client="+p);return p;}
        return ensureMojangVersionArtifact(MC,"client",umb.resolve("inputs/minecraft-"+MC+"-client.jar"));
    }
    private static Path ensureForge(Path umb,String explicit) throws Exception { if(explicit!=null)return require(Path.of(explicit),"Forge universal jar"); return ensureVerified(FORGE_URL,umb.resolve("inputs/forge-"+FORGE+"-universal.jar"),null,"Forge 1.7.10 universal jar"); }

    private static void ensureLegacyArtifacts(Path instance,Path umb,Map<String,String> opts) throws Exception {
        Path input=umb.resolve("inputs"); Files.createDirectories(input);
        ensureMojangVersionArtifact(MC1710,"client",input.resolve("minecraft-"+MC1710+"-client.jar"));
        ensureMojangVersionArtifact(MC1710,"server",input.resolve("minecraft-"+MC1710+"-server.jar"));
        ensureMojangVersionArtifact(MC1122,"client",input.resolve("minecraft-"+MC1122+"-client.jar"));
        ensureMojangVersionArtifact(MC1122,"server",input.resolve("minecraft-"+MC1122+"-server.jar"));
        ensureVerified(FORGE1122_URL,input.resolve("forge-"+FORGE1122+"-universal.jar"),null,"Forge 1.12.2 universal jar");
        ensureVersionLibraries(MC1710,input.resolve("libraries/"+MC1710));
        ensureVersionLibraries(MC1122,input.resolve("libraries/"+MC1122));
        ensureVersionLibraries(MC,input.resolve("libraries/"+MC));
        Path tools=input.resolve("tools");
        ensureVerified("https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.jar",tools.resolve("asm-9.9.jar"),null,"ASM");
        ensureVerified("https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar",tools.resolve("asm-tree-9.9.jar"),null,"ASM tree");
        ensureVerified("https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.jar",tools.resolve("asm-commons-9.9.jar"),null,"ASM commons");
        ensureVerified("https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar",tools.resolve("asm-all-5.0.3.jar"),null,"legacy ASM 5");
        ensureVerified("https://repo1.maven.org/maven2/com/google/guava/guava/17.0/guava-17.0.jar",tools.resolve("guava-17.0.jar"),null,"legacy Guava 17");
        ensureVerified("https://libraries.minecraft.net/lzma/lzma/0.0.1/lzma-0.0.1.jar",tools.resolve("lzma-0.0.1.jar"),null,"legacy LZMA");
        ensureVerified("https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar",tools.resolve("commons-compress-1.21.jar"),null,"Pack200 unpacker");
        ensureVerified("https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",tools.resolve("gson-2.11.0.jar"),null,"Gson");
    }

    private static Path ensureMojangVersionArtifact(String version,String side,Path out) throws Exception {
        if(Files.isRegularFile(out)) { String sha=artifactSha1(out); if(sha!=null)verifySha1(out,sha); return out; }
        String manifest=get(VERSION_MANIFEST); String versionUrl=extractNear(manifest,"\"id\"\\s*:\\s*\""+Pattern.quote(version)+"\"","\"url\"\\s*:\\s*\"([^\"]+)\"");
        if(versionUrl==null)fail("Minecraft "+version+" was not found in Mojang's official manifest");
        String json=get(versionUrl); Files.createDirectories(out.getParent()); Files.writeString(out.resolveSibling(out.getFileName()+".json"),json,StandardCharsets.UTF_8);
        String block=extract(json,"\""+side+"\"\\s*:\\s*\\{(.*?)\\}"); String url=block==null?null:extract(block,"\"url\"\\s*:\\s*\"([^\"]+)\""); String sha=block==null?null:extract(block,"\"sha1\"\\s*:\\s*\"([0-9a-fA-F]{40})\"");
        if(url==null||sha==null)fail("Mojang's "+version+" manifest has no verified "+side+" download"); downloadVerified(url,out,sha); Files.writeString(out.resolveSibling(out.getFileName()+".sha1"),sha+System.lineSeparator(),StandardCharsets.US_ASCII); return out;
    }

    private static void ensureVersionLibraries(String version,Path root) throws Exception {
        Path marker=root.resolve(".complete"); if(Files.isRegularFile(marker))return;
        String manifest=get(VERSION_MANIFEST); String versionUrl=extractNear(manifest,"\"id\"\\s*:\\s*\""+Pattern.quote(version)+"\"","\"url\"\\s*:\\s*\"([^\"]+)\"");
        if(versionUrl==null)fail("Minecraft "+version+" was not found in Mojang's official manifest"); String json=get(versionUrl); Files.createDirectories(root);
        Pattern p=Pattern.compile("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"sha1\\\"\\s*:\\s*\\\"([0-9a-fA-F]{40})\\\".*?\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",Pattern.CASE_INSENSITIVE|Pattern.DOTALL); Matcher m=p.matcher(json); int count=0;
        while(m.find()){Path out=root.resolve(m.group(1).replace('/',File.separatorChar)); if(!Files.isRegularFile(out))downloadVerified(m.group(3),out,m.group(2)); else verifySha1(out,m.group(2)); count++;}
        if(count==0)fail("Mojang's "+version+" manifest contained no downloadable libraries"); Files.writeString(marker,"version="+version+System.lineSeparator()+"libraries="+count+System.lineSeparator(),StandardCharsets.UTF_8); System.out.println("libraries="+version+" count="+count);
    }

    private static void prepareLegacyInputs(List<Mod> mods,Path instance,Path umb,List<Path> own,Map<String,String> opts) throws Exception {
        Path input=umb.resolve("inputs"), client=input.resolve("minecraft-"+MC1710+"-client.jar"), forge=input.resolve("forge-"+FORGE+"-universal.jar");
        Path cpFile=input.resolve("legacy-"+MC1710+"-classpath.txt");
        Path asm=input.resolve("tools/asm-9.9.jar"), asmTree=input.resolve("tools/asm-tree-9.9.jar"), asmCommons=input.resolve("tools/asm-commons-9.9.jar"), legacyAsm=input.resolve("tools/asm-all-5.0.3.jar"), guava=input.resolve("tools/guava-17.0.jar"), lzma=input.resolve("tools/lzma-0.0.1.jar"), compress=input.resolve("tools/commons-compress-1.21.jar"), pack200=umb.resolve("umb-pack200.jar");
        List<Path> entries=Files.isRegularFile(cpFile) ? readClasspathEntries(cpFile, input.resolve("libraries/"+MC1710)) : new ArrayList<>();
        if(entries.isEmpty())try(Stream<Path>s=Files.walk(input.resolve("libraries/"+MC1710))){entries.addAll(s.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")).sorted().toList());}
        entries.removeIf(p->p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("guava-")); for(Path p:List.of(asm,asmTree,asmCommons,legacyAsm,guava,lzma))if(!entries.contains(p))entries.add(p); Files.writeString(cpFile,entries.stream().map(p->p.toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator))+File.pathSeparator,StandardCharsets.UTF_8);
        Path boot=umb.resolve("umb-legacy-boot.jar"), api=umb.resolve("umb-legacy-api.jar"), ls=umb.resolve("umb-legacy-legacyside.jar"), bridge=umb.resolve("umb-bridge-api.jar"), lw=input.resolve(LAUNCHWRAPPER_NAME);
        Path jopt=findJar(input.resolve("libraries/"+MC1710),"jopt-simple-.*\\.jar"), logApi=findJar(input.resolve("libraries/"+MC1710),"log4j-api-.*\\.jar"), logCore=findJar(input.resolve("libraries/"+MC1710),"log4j-core-.*\\.jar");
        List<Path> app=new ArrayList<>(List.of(boot,api,bridge,ls,lw,asm,asmTree,asmCommons,legacyAsm,guava,lzma,compress)); for(Path p:List.of(jopt,logApi,logCore))if(p!=null)app.add(p);
        Path mcpZip=input.resolve("mcp-1.7.10-srg.zip"), mapping=input.resolve("joined-1.7.10.srg");
        if(!Files.isRegularFile(mapping)){ensureVerified(MCP_SRG_URL,mcpZip,null,"MCP 1.7.10 SRG export"); extractJoinedSrg(mcpZip,mapping);}
        Path runtimeRaw=input.resolve("minecraft-1.7.10-srg-runtime.jar"), runtime=input.resolve("minecraft-1.7.10-srg-runtime-fields.jar"), forgeSrg=input.resolve("forge-1.7.10-10.13.4.1614-1.7.10-srg.jar");
        if(!Files.isRegularFile(runtime)){List<Path> patchCp=new ArrayList<>(app); patchCp.add(forge); String modPaths=mods.stream().map(m->m.jar().toString()).collect(Collectors.joining(File.pathSeparator)); runJava(opts,new String[]{"--patch-module","java.base="+pack200,"--add-opens","java.base/java.io=ALL-UNNAMED","-Dfml.ignorePatchDiscrepancies=true","-cp",cp(patchCp),"dev.umb.legacy.boot.ClientSrgifier",mapping.toString(),client.toString(),runtimeRaw.toString(),forge.toString(),cpFile.toString(),input.resolve("client-patch").toString(),modPaths}); runJava(opts,new String[]{"-cp",cp(List.of(boot,asm)),"dev.umb.legacy.boot.SrgFieldRepair",mapping.toString(),runtimeRaw.toString(),runtime.toString()});}
        if(!Files.isRegularFile(forgeSrg)){List<String> remap=new ArrayList<>(List.of("-Dumb.repo="+umb,"-Dumb.legacy.out="+input.resolve("legacy-remap"),"-Dumb.legacy.notchJar="+client,"-Dumb.legacy.forgeJar="+forge,"-Dumb.legacy.classpathFile="+cpFile,"-Dumb.legacy.runtimeJar="+runtime,"-Dumb.legacy.legacysideJar="+ls,"-Dumb.legacy.forgeSrgJar="+forgeSrg,"-cp",cp(app),"dev.umb.legacy.boot.RemapTool")); runJava(opts,remap.toArray(String[]::new));}
        Path forgeCompatMarker=input.resolve("forge-1.7.10-objectholder-compatified");
        if(!Files.isRegularFile(forgeCompatMarker)){runJava(opts,"-cp",cp(List.of(boot,asm,asmTree,asmCommons)),"dev.umb.legacy.boot.ForgeCompatifier",forgeSrg.toString());Files.writeString(forgeCompatMarker,"ObjectHolderRef class-name fallback"+System.lineSeparator(),StandardCharsets.UTF_8);}
        Path assetsRoot=input.resolve("assets/"+MC1710); Files.createDirectories(assetsRoot);
        for(Mod mod:mods){String ns=mod.namespace(); Path out=umb.resolve("extracted"); Path snap=out.resolve(ns+"-snapshot.json"), assets=out.resolve(ns+"-assets"); if(Files.isRegularFile(snap)&&Files.isDirectory(assets))continue; Files.createDirectories(assets); extractModAssets(mod.jar(),assets);
            Path run=umb.resolve("extraction").resolve(ns); Files.createDirectories(run); Path game=run.resolve("game"); Files.createDirectories(game.resolve("mods")); Files.createDirectories(game.resolve("config")); Files.copy(mod.jar(),game.resolve("mods").resolve(mod.jar().getFileName()),StandardCopyOption.REPLACE_EXISTING);
            List<String> jvm=new ArrayList<>(List.of("-Xmx3G","-Djava.awt.headless=true","--add-opens","java.base/java.lang=ALL-UNNAMED","--add-opens","java.base/java.util=ALL-UNNAMED","--add-opens","java.base/java.lang.reflect=ALL-UNNAMED","-Dumb.repo="+umb,"-Dumb.legacy.out="+run,"-Dumb.legacy.runtimeJar="+runtime,"-Dumb.legacy.forgeJar="+forgeSrg,"-Dumb.legacy.classpathFile="+cpFile,"-Dumb.legacy.legacysideJar="+ls,"-Dumb.legacy.gameDir="+game,"-Dumb.legacy.assetsDir="+assetsRoot,"-Dumb.legacy.deobfuscatedEnvironment=true","-Dumb.legacy.transformers=dev.umb.legacy.legacyside.UmbShimTransformer,cpw.mods.fml.common.asm.transformers.MarkerTransformer,cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer,cpw.mods.fml.common.asm.transformers.AccessTransformer,net.minecraftforge.classloading.FluidIdTransformer,net.minecraftforge.transformers.ForgeAccessTransformer","-Dumb.legacy.timeoutSeconds=600","-cp",cp(app),"dev.umb.legacy.boot.Bootstrap"));
            runJava(opts,jvm.toArray(String[]::new)); Path live=run.resolve("live-registries.json"); if(!Files.isRegularFile(live))fail("Legacy snapshot failed for "+ns+"; see "+run.resolve("stages.txt")); Files.copy(live,snap,StandardCopyOption.REPLACE_EXISTING);
            Path shapes=out.resolve(ns+"-block-shapes.json"); List<String> shapeJvm=new ArrayList<>(jvm); shapeJvm.remove("dev.umb.legacy.boot.Bootstrap"); shapeJvm.add("-Dumb.legacy.blockShapesJson="+shapes); shapeJvm.add("dev.umb.legacy.boot.BlockShapeMain"); runJava(opts,shapeJvm.toArray(String[]::new));
        }
    }
    private static void extractJoinedSrg(Path zip,Path dest)throws IOException{try(ZipFile z=new ZipFile(zip.toFile())){ZipEntry hit=z.stream().filter(e->!e.isDirectory()&&e.getName().toLowerCase(Locale.ROOT).endsWith("joined.srg")).findFirst().orElse(null);if(hit==null)fail("MCP SRG archive contained no joined.srg");Files.createDirectories(dest.getParent());try(InputStream in=z.getInputStream(hit)){Files.copy(in,dest,StandardCopyOption.REPLACE_EXISTING);}}}
    private static void extractModAssets(Path jar,Path dest)throws IOException{
        System.out.println("[installer] extracting assets " + jar.getFileName());
        Path staged=Files.createTempFile("umb-mod-", ".jar");
        int count=0;
        try {
            // A mod jar may be on a mounted Windows drive under WSL.  JarFile's
            // random-access entry reads are extremely slow there, so stage it on
            // the local filesystem and stream the archive once.
            Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
            Path root=dest.toAbsolutePath().normalize();
            try(ZipInputStream z=new ZipInputStream(Files.newInputStream(staged))){
                ZipEntry e;
                while((e=z.getNextEntry())!=null){
                    if(e.isDirectory()||!e.getName().startsWith("assets/"))continue;
                    Path p=root.resolve(e.getName()).normalize();
                    if(!p.startsWith(root))throw new IOException("Unsafe asset path in "+jar+": "+e.getName());
                    Files.createDirectories(p.getParent());
                    Files.copy(z,p,StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        } finally { Files.deleteIfExists(staged); }
        System.out.println("[installer] extracted assets " + jar.getFileName() + " entries=" + count);
    }
    private static Path ensureLaunchwrapper(Path instance,Path umb,String explicit) throws Exception {
        String expected=launchwrapperSha1(instance);
        List<Path> candidates=new ArrayList<>();
        if(explicit!=null)candidates.add(Path.of(explicit));
        candidates.add(umb.resolve("inputs").resolve(LAUNCHWRAPPER_NAME));
        candidates.add(instance.resolve("libraries/net/minecraft/launchwrapper/1.12/").resolve(LAUNCHWRAPPER_NAME));
        Path home=Path.of(System.getProperty("user.home"));
        candidates.add(home.resolve(".minecraft/libraries/net/minecraft/launchwrapper/1.12/").resolve(LAUNCHWRAPPER_NAME));
        for(Path p:candidates)if(Files.isRegularFile(p)){verifySha1(p,expected);return p.toAbsolutePath().normalize();}
        Path out=umb.resolve("inputs").resolve(LAUNCHWRAPPER_NAME); downloadVerified(LAUNCHWRAPPER_URL,out,expected); return out;
    }

    private static Path ensureVerified(String url,Path out,String expected,String label) throws Exception {
        String sha=expected;
        if(sha==null) { try { sha=sha1Text(url+".sha1").split("\\s+")[0]; } catch(Exception ignored) { } }
        if(sha==null||!sha.matches("[0-9a-fA-F]{40}"))fail("No official SHA-1 was available for "+label+" at "+url);
        if(Files.isRegularFile(out)) { verifySha1(out,sha); return out; }
        downloadVerified(url,out,sha); return out;
    }
    private static void downloadVerified(String url,Path dest,String expected)throws Exception { download(url,dest); verifySha1(dest,expected); }
    private static String artifactSha1(Path file) { Path side=file.resolveSibling(file.getFileName()+".sha1"); try { if(Files.isRegularFile(side))return Files.readString(side,StandardCharsets.US_ASCII).trim().split("\\s+")[0]; } catch(IOException ignored) {} return null; }
    private static String launchwrapperSha1(Path instance) throws Exception {
        List<Path> manifests=new ArrayList<>();
        for(Path root:List.of(instance,Path.of(System.getProperty("user.home"),".minecraft"))){
            if(!Files.isDirectory(root))continue;
            try(Stream<Path>s=Files.walk(root,4)){manifests.addAll(s.filter(p->p.toString().endsWith(".json")).toList());}
        }
        Pattern p=Pattern.compile("\\\"net\\.minecraft:launchwrapper:1\\.12\\\".*?\\\"sha1\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Pattern reverse=Pattern.compile("\\\"sha1\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\".*?\\\"net\\.minecraft:launchwrapper:1\\.12\\\"",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        for(Path f:manifests){String t=Files.readString(f,StandardCharsets.UTF_8);Matcher m=p.matcher(t);if(m.find())return m.group(1);m=reverse.matcher(t);if(m.find())return m.group(1);}
        try { String vm=get(VERSION_MANIFEST); String versionUrl=extractNear(vm,"\\\"id\\\"\\s*:\\s*\\\""+Pattern.quote(MC)+"\\\"","\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""); if(versionUrl!=null){String t=get(versionUrl);Matcher m=p.matcher(t);if(m.find())return m.group(1);m=reverse.matcher(t);if(m.find())return m.group(1);}} catch(Exception ignored) { }
        String sidecar=sha1Text(LAUNCHWRAPPER_URL+".sha1");
        if(sidecar==null||!sidecar.matches("[0-9a-fA-F]{40}"))fail("No official SHA-1 was available for "+LAUNCHWRAPPER_URL);
        return sidecar.toLowerCase(Locale.ROOT);
    }
    private static String sha1Text(String url)throws Exception{return new String(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body(),StandardCharsets.US_ASCII).trim();}
    private static void verifySha1(Path file,String expected)throws Exception {try(InputStream in=Files.newInputStream(file)){MessageDigest d=MessageDigest.getInstance("SHA-1");in.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(),d));String actual=HexFormat.of().formatHex(d.digest());if(!actual.equalsIgnoreCase(expected))fail("SHA-1 mismatch for "+file+": expected "+expected+", got "+actual);}}
    private static Path findMapping(Path root){for(Path p:List.of(root.resolve("research/mappings/joined-1.7.10.srg"),root.resolve("research/visual/mc1710-native/mappings.srg"),root.resolve("research/visual/mc1710-native/conf/joined.srg")))if(Files.isRegularFile(p))return p; return null;}
    private static Path findFirst(Path dir,String name) throws IOException {if(!Files.isDirectory(dir))return null; try(Stream<Path>s=Files.walk(dir)){return s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equals(name)).findFirst().orElse(null);}}
    private static Path require(Path p,String label){if(!Files.isRegularFile(p))fail("Missing "+label+": "+p);return p.toAbsolutePath().normalize();}

    private static void checkInstall(Path instance) throws Exception {
        Path umb=instance.resolve("umb"); List<String> fixes=new ArrayList<>();
        for(String n:List.of("umb-hostagent.jar","umb-objbridge.jar","umb-legacy-api.jar","umb-legacy-boot.jar","umb-bridge-api.jar","umb-legacy-legacyside.jar","umb-rendermap.jar","umb-guimap.jar","jvm-arguments.txt","manifest.json")) if(!Files.isRegularFile(umb.resolve(n)))fixes.add("re-run installer: missing umb/"+n);
        Path args=umb.resolve("jvm-arguments.txt"); if(Files.isRegularFile(args)){String t=Files.readString(args); if(!t.contains("launchwrapper="))fixes.add("re-run installer: jvm-arguments.txt has no LaunchWrapper classpath"); if(isMac()&&!t.contains("-XstartOnFirstThread"))fixes.add("re-run installer on macOS: add -XstartOnFirstThread");}
        Path input=umb.resolve("inputs"); for(String n:List.of("minecraft-1.7.10-client.jar","minecraft-1.7.10-server.jar","minecraft-1.12.2-client.jar","minecraft-1.12.2-server.jar","forge-"+FORGE+"-universal.jar","forge-"+FORGE1122+"-universal.jar",LAUNCHWRAPPER_NAME))if(!Files.isRegularFile(input.resolve(n)))fixes.add("re-run installer: download missing umb/inputs/"+n);
        if(!Files.isDirectory(umb.resolve("generated")))fixes.add("re-run installer: generated data is missing");
        if(fixes.isEmpty()){System.out.println("UMB-CHECK-OK: "+umb);return;}
        System.out.println("UMB-CHECK-FAIL: "+umb); fixes.forEach(x->System.out.println("FIX: "+x));
    }

    private static List<Path> copyOwnJars(Path repo,Path umb) throws IOException {
        List<String> names=List.of("umb-hostagent.jar","umb-objbridge.jar","umb-legacy-api.jar","umb-legacy-boot.jar","umb-bridge-api.jar","umb-pack200.jar","umb-legacy-legacyside.jar","umb-legacy-legacyside-stage1.jar","umb-legacy1122-api.jar","umb-legacy1122-boot.jar","umb-legacy1122-bridge-api.jar","umb-legacy1122-legacyside.jar","umb-legacy1122-pack200.jar","umb-rendermap.jar","umb-guimap.jar"); List<Path> r=new ArrayList<>();
        for(String n:names){Path dst=umb.resolve(n);Path src=repo==null?null:findSibling(repo,n);if(src!=null)Files.copy(src,dst,StandardCopyOption.REPLACE_EXISTING);else{try(InputStream in=UmbInstaller.class.getResourceAsStream("/bundled/"+n)){if(in==null)fail("Bundled UMB jar missing: "+n);Files.copy(in,dst,StandardCopyOption.REPLACE_EXISTING);}}r.add(dst);}
        Files.createDirectories(umb.resolve("build/legacy")); Files.copy(umb.resolve("umb-legacy-api.jar"),umb.resolve("build/legacy/umb-legacy-api.jar"),StandardCopyOption.REPLACE_EXISTING); Files.copy(umb.resolve("umb-bridge-api.jar"),umb.resolve("build/legacy/umb-bridge-api.jar"),StandardCopyOption.REPLACE_EXISTING);
        require(umb.resolve("umb-hostagent.jar"),"umb-hostagent.jar"); require(umb.resolve("umb-objbridge.jar"),"umb-objbridge.jar"); return r;
    }
    private static Path findSibling(Path repo,String name){for(Path p:List.of(repo.resolve("build/hostagent/"+name),repo.resolve("build/objbridge/"+name),repo.resolve("build/legacy/"+name),repo.resolve("umb-legacy-1122/build/"+name),repo.resolve("build/rendermap/"+name),repo.resolve("build/guimap/"+name),repo.resolve(name),Path.of(System.getProperty("user.dir")).resolve(name)))if(Files.isRegularFile(p))return p; return null;}
    private static Path toolRoot(Path repo,Path umb,List<Path> own){return repo;}

    private static Generated generate(Mod mod,Path repo,Path inputRoot,Path instance,Path umb,Path tools,Map<String,String> opts) throws Exception {
        String ns=mod.namespace(); Path source=locateData(inputRoot,ns,"-snapshot.json"); Path assets=locateDir(inputRoot,ns,"-assets");
        if(source==null||!Files.isRegularFile(source))source=umb.resolve("extracted/"+ns+"-snapshot.json"); if(assets==null||!Files.isDirectory(assets))assets=umb.resolve("extracted/"+ns+"-assets");
        if(!Files.isRegularFile(source)||!Files.isDirectory(assets)) throw new IllegalStateException("Legacy extraction did not produce snapshot/assets for namespace "+ns+"; see umb/extraction/"+ns+"/boot.log");
        Path out=umb.resolve("generated").resolve(ns); Files.createDirectories(out); Path snap=out.resolve(ns+"-snapshot.json"); Files.copy(source,snap,StandardCopyOption.REPLACE_EXISTING);
        // Keep the extracted asset tree in place.  Re-copying thousands of small
        // files from a mounted Windows drive (notably WSL /mnt/*) makes install
        // appear hung and wastes both time and disk space; generators can read
        // the immutable extracted tree directly.
        Path assetOut=assets;
        System.out.println("[installer] generating " + ns + " from " + assetOut);
        Path render=out.resolve("rendermap"), map=render.resolve(ns+"-render-map.json"); Files.createDirectories(render); Path renderJar=umb.resolve("umb-rendermap.jar"); Path gson=findJar(inputRoot,"gson-.*\\.jar"); Path asm=findJar(inputRoot,"asm-9.9.jar"); Path asmTree=findJar(inputRoot,"asm-tree-9.9.jar"); Path asmCommons=findJar(inputRoot,"asm-commons-9.9.jar");
        if(renderJar!=null&&gson!=null&&asm!=null&&asmTree!=null&&asmCommons!=null) runJava(opts,"-Xmx3g","-cp",cp(List.of(renderJar,gson,asm,asmTree,asmCommons)),"dev.umb.rendermap.RenderMap",mod.jar().toString(),snap.toString(),render.toString(),"--engine-classpath",cp(List.of(inputRoot.resolve("umb/inputs/minecraft-1.7.10-client.jar"),inputRoot.resolve("umb/inputs/forge-"+FORGE+"-universal.jar"))));
        Path cachedMap=locateFile(inputRoot,ns,"-render-map.json"); if(!Files.isRegularFile(map)&&cachedMap!=null)Files.copy(cachedMap,map,StandardCopyOption.REPLACE_EXISTING); require(map,"render map for "+ns);
        Path transforms=render.resolve("renderer-transforms.json"); if(renderJar!=null&&gson!=null&&asm!=null&&asmTree!=null&&asmCommons!=null)runJava(opts,"-cp",cp(List.of(renderJar,gson,asm,asmTree,asmCommons)),"dev.umb.rendermap.RendererTransformExtractor",mod.jar().toString(),map.toString(),render.toString()); if(!Files.exists(transforms)){Path c=locateFile(inputRoot,ns,"renderer-transforms.json");if(c!=null)Files.copy(c,transforms,StandardCopyOption.REPLACE_EXISTING);}
        Path gui=out.resolve("gui-profile.json"); Path guiJar=umb.resolve("umb-guimap.jar"); if(Files.isRegularFile(guiJar)&&gson!=null&&asm!=null&&asmTree!=null&&asmCommons!=null)runJava(opts,"-cp",cp(List.of(guiJar,gson,asm,asmTree,asmCommons)),"dev.umb.guimap.GuiMap",mod.jar().toString(),gui.toString()); else {Path c=locateFile(inputRoot,ns,"-gui-profile.json");if(c!=null)Files.copy(c,gui,StandardCopyOption.REPLACE_EXISTING);}
        Path shapes=copyOptional(inputRoot,ns,"-block-shapes.json",out.resolve("block-shapes.json")); Path resourceRoot=instance.resolve("resourcepacks"); Files.createDirectories(resourceRoot); Path base=resourceRoot.resolve("umb-"+ns+"-generated"), obj=resourceRoot.resolve("umb-"+ns+"-objmodels"); Path host=umb.resolve("umb-hostagent.jar");
        List<Path> gameCp=clientClasspath(inputRoot);
        List<Path> packCp=new ArrayList<>(List.of(host)); packCp.addAll(gameCp);
        List<String> pack=new ArrayList<>(List.of("-cp",cp(packCp),"dev.umb.packgen.PackGen",snap.toString(),assetOut.toString(),base.toString(),"--ns",ns)); if(Files.isRegularFile(gui)) pack.addAll(List.of("--gui-profile",gui.toString())); if(shapes!=null) pack.addAll(List.of("--block-shapes",shapes.toString())); runJava(opts,pack.toArray(String[]::new));
        Path objJar=umb.resolve("umb-objbridge.jar"); if(Files.isRegularFile(objJar)) {List<Path> objCp=new ArrayList<>(List.of(objJar)); objCp.addAll(gameCp); List<String> op=new ArrayList<>(List.of("-cp",cp(objCp),"dev.umb.objbridge.gen.ObjPackGen",map.toString(),snap.toString(),assetOut.toString(),obj.toString(),"--ns",ns,"--base-pack-dir",base.toString(),"--report",out.resolve("objpackgen-report.md").toString(),"--block-list",out.resolve("block-objmodels.json").toString())); runJava(opts,op.toArray(String[]::new));}
        Path sound=copyCachedDir(inputRoot,ns,"-sounds",resourceRoot.resolve("umb-"+ns+"-sounds")); Path fluid=copyCachedDir(inputRoot,ns,"-fluids",resourceRoot.resolve("umb-"+ns+"-fluids"));
        Path plans=out.resolve(ns+"-input-plans.json"); Path cachedPlans=locateFile(inputRoot,ns,"-input-plans.json"); if(cachedPlans!=null){Files.copy(cachedPlans,plans,StandardCopyOption.REPLACE_EXISTING);Files.copy(cachedPlans,umb.resolve(ns+"-input-plans.json"),StandardCopyOption.REPLACE_EXISTING);}
        return new Generated(mod,snap,assetOut,map,transforms,gui,shapes,base,obj,sound,fluid,plans);
    }

    private static List<Path> clientClasspath(Path inputRoot) throws IOException {
        Path cp=inputRoot.resolve("research/visual/mc262-vanilla/classpath.txt");
        if(!Files.isRegularFile(cp)) { Path cache=inputRoot.resolve("umb/inputs"); List<Path> result=new ArrayList<>(); Path client=cache.resolve("minecraft-26.2-client.jar"); if(Files.isRegularFile(client))result.add(client); Path libs=cache.resolve("libraries/26.2"); if(Files.isDirectory(libs))try(Stream<Path>s=Files.walk(libs)){result.addAll(s.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")).filter(p->!isNativeJar(p)||nativeMatches(p)).sorted().toList());} return result.isEmpty()?List.of(inputRoot.resolve("research/jars/26.2/client.jar")):result; }
        String raw=Files.readString(cp,StandardCharsets.UTF_8).trim(); String[] parts=raw.contains(";")||raw.contains("\n")||raw.contains("\r")?raw.split(";|\\r?\\n"):raw.split(Pattern.quote(File.pathSeparator)); List<Path> result=new ArrayList<>();
        for(String part:parts){if(part.isBlank())continue;String s=part.trim().replace('\\','/');Path p=Path.of(s);if(!Files.exists(p)){int i=s.toLowerCase(Locale.ROOT).indexOf("/translatemc/");if(i>=0)p=inputRoot.resolve(s.substring(i+13));else if(!p.isAbsolute())p=inputRoot.resolve(s);}p=p.normalize();if(!isNativeJar(p)||nativeMatches(p))result.add(p);}
        return result;
    }
    private static boolean isNativeJar(Path p){String n=p.getFileName().toString().toLowerCase(Locale.ROOT);return n.contains("-natives-")||n.contains("-natives.");}
    private static boolean nativeMatches(Path p){String n=p.getFileName().toString().toLowerCase(Locale.ROOT);String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);boolean platformOk=os.contains("win")?n.contains("-natives-windows"):os.contains("mac")||os.contains("darwin")?n.contains("-natives-macos")||n.contains("-natives-osx"):os.contains("linux")&&n.contains("-natives-linux");if(!platformOk)return false;String arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);boolean arm=arch.contains("aarch64")||arch.contains("arm64");boolean armNative=n.contains("-arm64")||n.contains("-aarch64");return arm?armNative:!armNative;}
    private static List<Path> readClasspathEntries(Path file,Path cacheRoot)throws IOException{String raw=Files.readString(file,StandardCharsets.UTF_8).trim();if(raw.isEmpty())return new ArrayList<>();String sep=raw.contains(";")||raw.contains("\n")||raw.contains("\r")?";|\\r\\n":Pattern.quote(File.pathSeparator);List<Path> out=new ArrayList<>();try(Stream<Path>s=Files.walk(cacheRoot)){List<Path> cached=s.filter(Files::isRegularFile).toList();for(String token:raw.split(sep)){String value=token.trim().replace('\\','/');if(value.isEmpty())continue;Path p=Path.of(value);if(Files.isRegularFile(p)){out.add(p.toAbsolutePath().normalize());continue;}Path byName=cached.stream().filter(x->x.getFileName().toString().equalsIgnoreCase(Path.of(value).getFileName().toString())).findFirst().orElse(null);if(byName!=null)out.add(byName.toAbsolutePath().normalize());}}return new ArrayList<>(new LinkedHashSet<>(out));}
    private static Path findJar(Path root,String regex) throws IOException {
        for(Path base:List.of(root.resolve("tools/junit"),root.resolve("umb/inputs/tools"),root.resolve("research/jars/26.2/libraries"),root.resolve("research/visual/mc262-vanilla"))) {
            if(!Files.isDirectory(base))continue;
            try(Stream<Path>s=Files.walk(base)){Path p=s.filter(Files::isRegularFile).filter(x->x.getFileName().toString().matches(regex)).findFirst().orElse(null);if(p!=null)return p;}
        }
        try(Stream<Path>s=Files.walk(root)){return s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches(regex)).findFirst().orElse(null);}
    }
    private static Path locateFile(Path root,String ns,String suffix) throws IOException {Path base=root.resolve("research/out/legacy"); if(!Files.isDirectory(base))return null; try(Stream<Path>s=Files.walk(base)){return s.filter(Files::isRegularFile).filter(p->{String n=p.getFileName().toString().toLowerCase(Locale.ROOT);String wanted=(ns+suffix).toLowerCase(Locale.ROOT);if(n.equals(wanted))return true;if(suffix.equals("-render-map.json")&&n.equals("hbm-render-map.json")){String all=p.toString().toLowerCase(Locale.ROOT);return all.contains("rendermap-"+ns.toLowerCase(Locale.ROOT))||all.contains("/"+ns.toLowerCase(Locale.ROOT)+"-render-map.json");}return false;}).findFirst().orElse(null);}}
    private static Path locateData(Path root,String ns,String suffix)throws IOException{return locateFile(root,ns,suffix);}
    private static Path locateDir(Path root,String ns,String suffix)throws IOException{Path base=root.resolve("research/out/legacy");if(!Files.isDirectory(base))return null;try(Stream<Path>s=Files.walk(base)){return s.filter(Files::isDirectory).filter(p->p.getFileName().toString().equalsIgnoreCase(ns+suffix)).findFirst().orElse(null);}}
    private static Path copyOptional(Path root,String ns,String suffix,Path dest)throws IOException{Path p=locateFile(root,ns,suffix);if(p==null||!Files.isRegularFile(p)){Path generated=root.resolve("umb/extracted/"+ns+suffix);p=Files.isRegularFile(generated)?generated:null;}if(p==null&&suffix.equals("-block-shapes.json")){Path common=root.resolve("research/out/legacy/block-shapes.json");if(Files.isRegularFile(common))p=common;}if(p!=null){Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);return dest;}return null;}
    private static Path copyCachedDir(Path root,String ns,String suffix,Path dest)throws IOException{Path p=locateDir(root,ns,suffix);if(p==null)return null;copyTree(p,dest);return dest;}
    private static void copyTree(Path from,Path to)throws IOException{try(Stream<Path>s=Files.walk(from)){for(Path p:s.toList()){Path d=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(d);else{Files.createDirectories(d.getParent());Files.copy(p,d,StandardCopyOption.REPLACE_EXISTING);}}}}

    private static void installResourcePacks(Path instance,List<Generated> gs)throws IOException{
        Path dir=instance.resolve("resourcepacks");Files.createDirectories(dir);List<String> names=new ArrayList<>();
        for(Generated g:gs){List<Path> packs=new ArrayList<>();packs.add(g.basePack);packs.add(g.objPack);if(g.soundPack!=null)packs.add(g.soundPack);if(g.fluidPack!=null)packs.add(g.fluidPack);for(Path p:packs)if(Files.isDirectory(p)){String n=p.getParent().toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize())?p.getFileName().toString():"umb-"+p.getFileName();if(!p.toAbsolutePath().normalize().equals(dir.resolve(n).toAbsolutePath().normalize()))copyTree(p,dir.resolve(n));names.add(n);}}
        String list="resourcePacks:["+names.stream().map(n->"\"file/"+n+"\"").collect(Collectors.joining(","))+"]";
        Path options=instance.resolve("options.txt");List<String> lines=Files.isRegularFile(options)?Files.readAllLines(options,StandardCharsets.UTF_8):new ArrayList<>();boolean found=false;List<String> out=new ArrayList<>();for(String line:lines){if(line.startsWith("resourcePacks:")){out.add(list);found=true;}else if(line.startsWith("incompatibleResourcePacks:")){out.add("incompatibleResourcePacks:[]");}else out.add(line);}if(!found)out.add(list);Files.write(options,out,StandardCharsets.UTF_8);Files.writeString(instance.resolve("umb/resourcepacks.txt"),String.join(System.lineSeparator(),names)+System.lineSeparator(),StandardCharsets.UTF_8);
    }

    private static Path writeManifest(Path umb,List<Generated> gs,Path mods)throws IOException {Path p=umb.resolve("manifest.json");StringBuilder b=new StringBuilder("{\n  \"mods\": [\n");for(int i=0;i<gs.size();i++){Generated g=gs.get(i);b.append("    {\n      \"namespace\": \"").append(g.mod.namespace()).append("\",\n      \"jar\": \"").append(json(g.mod.jar().toString())).append("\",\n      \"snapshot\": \"").append(json(g.snapshot.toString())).append("\",\n      \"assets\": \"").append(json(g.assets.toString())).append("\",\n      \"rendermap\": \"").append(json(g.renderMap.toString())).append("\",\n      \"blockShapes\": \"").append(g.blockShapes==null?"":json(g.blockShapes.toString())).append("\",\n      \"basePack\": \"").append(json(g.basePack.toString())).append("\",\n      \"objPack\": \"").append(json(g.objPack.toString())).append("\",\n      \"soundPack\": \"").append(g.soundPack==null?"":json(g.soundPack.toString())).append("\",\n      \"fluidPack\": \"").append(g.fluidPack==null?"":json(g.fluidPack.toString())).append("\",\n      \"guiProfile\": \"").append(json(g.gui.toString())).append("\"\n    }").append(i+1<gs.size()?",":"").append('\n');}b.append("  ]\n}\n");Files.writeString(p,b.toString(),StandardCharsets.UTF_8);return p;}
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}

    private static Path writeJvmArgs(Path umb,Path manifest,List<Generated> gs,Path mods,Path client,Path forge,Path forgeRuntime,Path mapping,Path launchwrapper,List<Path> own)throws IOException {String first=gs.get(0).mod.namespace();String modjars=gs.stream().map(g->g.mod.jar().toString()).collect(Collectors.joining(","));Generated g=gs.get(0);Path natives=umb.resolve("natives");StringBuilder b=new StringBuilder();b.append("-Xmx4G ");if(isMac())b.append("-XstartOnFirstThread ");b.append("-Dumb.inputPlans=\"").append(umb).append("\" ");b.append("-Dminecraft.clientJar=\"").append(client).append("\" ");if(mapping!=null)b.append("-Dumb.mappingFile=\"").append(mapping).append("\" ");String[] opens={"java.base/java.lang","java.base/java.lang.reflect","java.base/java.util","java.base/java.util.jar","java.base/sun.security.util","java.base/java.util.concurrent","java.base/java.net","java.base/java.nio","java.base/java.io","java.base/java.text"};for(String x:opens)b.append("--add-opens ").append(x).append("=ALL-UNNAMED ");b.append("-XX:HeapDumpPath=\"").append(umb.resolve("MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump")).append("\" ");b.append("--sun-misc-unsafe-memory-access=allow ");b.append("--enable-native-access=ALL-UNNAMED ");b.append("-Djava.library.path=\"").append(natives.resolve("java")).append("\" ");b.append("-Djna.tmpdir=\"").append(natives.resolve("jna")).append("\" ");b.append("-Dorg.lwjgl.system.SharedLibraryExtractPath=\"").append(natives.resolve("lwjgl")).append("\" ");b.append("-Dio.netty.native.workdir=\"").append(natives.resolve("netty")).append("\" ");b.append("-Dminecraft.launcher.brand=umb-game -Dminecraft.launcher.version=1.0 -Dorg.lwjgl.util.NoChecks=true ");b.append("-javaagent:\"").append(umb.resolve("umb-hostagent.jar")).append("\"=snapshot=\"").append(g.snapshot).append("\";log=\"").append(umb.resolve("hostagent.log")).append("\";ns=").append(first).append(";modjars=\"").append(modjars).append("\";manifest=\"").append(manifest).append("\";launchwrapper=\"").append(launchwrapper).append("\" ");b.append("-javaagent:\"").append(umb.resolve("umb-objbridge.jar")).append("\"=rendermap=\"").append(g.renderMap).append("\";assets=\"").append(g.assets).append("\";transforms=\"").append(g.transforms).append("\";log=\"").append(umb.resolve("objbridge.log")).append("\";manifest=\"").append(manifest).append("\"");Path p=umb.resolve("jvm-arguments.txt");Files.writeString(p,b.toString()+System.lineSeparator(),StandardCharsets.UTF_8);return p;}
    private static void writeLog4jConfig(Path umb)throws IOException { String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Configuration status=\"WARN\"><Appenders><Console name=\"SysOut\" target=\"SYSTEM_OUT\"><PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level]: %msg%n\"/></Console><RollingRandomAccessFile name=\"File\" fileName=\"logs/latest.log\" filePattern=\"logs/%d{yyyy-MM-dd}-%i.log.gz\"><PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level]: %msg%n\"/><Policies><TimeBasedTriggeringPolicy/><OnStartupTriggeringPolicy/></Policies></RollingRandomAccessFile></Appenders><Loggers><Root level=\"info\"><AppenderRef ref=\"SysOut\"/><AppenderRef ref=\"File\"/></Root></Loggers></Configuration>"; Files.writeString(umb.resolve("log4j-client.xml"),xml+System.lineSeparator(),StandardCharsets.UTF_8); }
    private static boolean isMac(){String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);return os.contains("mac")||os.contains("darwin");}

    private static String cp(List<Path> ps){return ps.stream().filter(Objects::nonNull).map(Path::toString).collect(Collectors.joining(File.pathSeparator));}
    private static void runJava(Map<String,String> opts,String... args)throws Exception{Path j=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");List<String> c=new ArrayList<>();c.add(j.toString());c.addAll(Arrays.asList(args));ProcessBuilder p=new ProcessBuilder(c);p.redirectErrorStream(true);Process q=p.start();try(BufferedReader r=new BufferedReader(new InputStreamReader(q.getInputStream(),StandardCharsets.UTF_8))){r.lines().forEach(System.out::println);}if(q.waitFor()!=0)throw new IllegalStateException("generation command failed: "+String.join(" ",c));}
    private static String get(String url)throws Exception{HttpClient c=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();HttpResponse<String>r=c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("HTTP "+r.statusCode());return r.body();}
    private static void download(String url,Path dest)throws Exception{Files.createDirectories(dest.getParent());HttpClient c=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();HttpResponse<byte[]>r=c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()/100!=2)throw new IOException("HTTP "+r.statusCode());Files.write(dest,r.body());}
    private static String extract(String text,String regex){Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text);return m.find()?m.group(1):null;}
    private static String extractNear(String text,String id,String url){Matcher m=Pattern.compile(id+".*?"+url,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text);return m.find()?m.group(1):null;}
}
