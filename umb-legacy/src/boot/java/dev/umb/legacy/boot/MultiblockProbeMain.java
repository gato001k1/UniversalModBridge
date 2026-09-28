package dev.umb.legacy.boot;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/** M1ProbeMain-shaped launcher for MultiblockProbe; kept separate so each run gets one universe. */
public final class MultiblockProbeMain {
    public static void main(String[] args) throws Exception {
        String repo = System.getProperty("umb.repo");
        Path out = Path.of(System.getProperty("umb.legacy.out", repo + "/research/out/legacy/legacy-boot"));
        Files.createDirectories(out);
        String cpFile = System.getProperty("umb.legacy.classpathFile", repo + "/research/visual/mc1710-native/classpath.txt");
        java.io.File runtime = new java.io.File(System.getProperty("umb.legacy.runtimeJar", repo + "/build/legacy/1.7.10-forge-srg-runtime-fields.jar"));
        java.io.File forge = new java.io.File(System.getProperty("umb.legacy.forgeJar", repo + "/build/legacy/forge-1.7.10-10.13.4.1614-srg.jar"));
        java.io.File ls = new java.io.File(System.getProperty("umb.legacy.legacysideJar", repo + "/build/legacy/umb-legacy-legacyside.jar"));
        java.io.File game = out.toFile();
        java.util.List<java.io.File> cp = LegacyClasspath.forBoot(new java.io.File(cpFile), runtime, forge,
                java.util.Arrays.asList(new java.io.File(System.getProperty("umb.legacy.probeClasses", repo + "/scratchpad/multiblock-probe-classes"))), java.util.Arrays.asList(ls,
                new java.io.File(repo+"/build/legacy/umb-legacy-api.jar"),
                new java.io.File(repo+"/build/legacy/umb-bridge-api.jar")));
        URL[] urls = LegacyClasspath.toUrls(cp);
        LegacyLoader loader = new LegacyLoader(urls, MultiblockProbeMain.class.getClassLoader());
        net.minecraft.launchwrapper.Launch.minecraftHome=game; net.minecraft.launchwrapper.Launch.assetsDir=new java.io.File(repo+"/research/visual/mc1710-native/assets"); net.minecraft.launchwrapper.Launch.classLoader=loader;
        java.util.Map<String,Object> bb=new java.util.HashMap<String,Object>(); net.minecraft.launchwrapper.Launch.blackboard=bb;
        java.util.Map<String,String> la=new java.util.HashMap<String,String>(); la.put("--version","1.7.10-Forge10.13.4.1614-1.7.10"); la.put("--gameDir",game.getAbsolutePath()); la.put("--assetsDir",net.minecraft.launchwrapper.Launch.assetsDir.getAbsolutePath()); bb.put("launchArgs",la); bb.put("fml.deobfuscatedEnvironment",Boolean.TRUE); bb.put("Tweaks",new java.util.ArrayList<Object>()); bb.put("TweakClasses",new java.util.ArrayList<String>()); bb.put("modList",new java.util.HashMap<String,java.util.Map<String,String>>()); bb.put("coremodList",new java.util.ArrayList<Object>());
        final String[] report={null}; final Throwable[] failure={null}; Thread t=new Thread(()->{try{Class<?> c=Class.forName("dev.umb.legacy.legacyside.MultiblockProbe",true,loader); report[0]=(String)c.getMethod("run").invoke(null);}catch(Throwable e){failure[0]=e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e;}},"Server thread"); t.setContextClassLoader(loader); t.start(); t.join(180000); if(t.isAlive()) throw new IllegalStateException("timeout"); String s=report[0]!=null?report[0]:"FAIL "+failure[0]; Files.writeString(out.resolve("multiblock-probe.txt"),s,StandardCharsets.UTF_8); System.out.print(s); System.exit(s.startsWith("MULTIBLOCK-PROBE")?0:1);
    }
}
