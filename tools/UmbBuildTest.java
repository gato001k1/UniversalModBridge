import java.util.*;

/** Small JDK-only regression check for Forge classpath conflict resolution. */
final class UmbBuildTest {
    public static void main(String[] args) {
        List<String> actual=UmbBuild.dropOlderLibraries(List.of(
                "libraries/net/minecraftforge/forge/x/forge-x-universal.jar",
                "libraries/com/google/guava/guava/17.0/guava-17.0.jar",
                "libraries/com/google/guava/guava/15.0/guava-15.0.jar",
                "libraries/net/sf/jopt-simple/jopt-simple/4.5/jopt-simple-4.5.jar",
                "libraries/net/sf/jopt-simple/jopt-simple/4.5/jopt-simple-4.5.jar"));
        if(actual.stream().anyMatch(x->x.contains("guava/15.0")) || actual.stream().filter(x->x.contains("guava/17.0")).count()!=1) throw new AssertionError(actual.toString());
        List<String> reversed=UmbBuild.dropOlderLibraries(List.of("libraries/com/google/guava/guava/15.0/guava-15.0.jar","libraries/com/google/guava/guava/17.0/guava-17.0.jar"));
        if(reversed.size()!=1 || !reversed.get(0).contains("17.0")) throw new AssertionError(reversed.toString());
        if(actual.stream().filter(x->x.contains("jopt-simple/4.5")).count()!=2) throw new AssertionError("exact duplicates must remain: "+actual);
        if(!actual.get(0).contains("forge-x-universal")) throw new AssertionError(actual.toString());
        if(args.length>0){
            try{
                String raw=java.nio.file.Files.readString(java.nio.file.Path.of(args[0]));
                int q=raw.indexOf('"'), e=raw.indexOf('"',q+1);
                String line=q>=0&&e>q?raw.substring(q+1,e):raw.trim();
                List<String> copy=UmbBuild.dropOlderLibraries(Arrays.asList(line.split("[;]")));
                if(copy.stream().map(x->x.replace('\\','/')).anyMatch(x->x.contains("guava/15.0"))) throw new AssertionError("older guava survived copied manifest");
                System.out.println("copied manifest entries="+copy.size()+" guava17="+copy.stream().map(x->x.replace('\\','/')).filter(x->x.contains("guava/17.0")).count());
            }catch(Exception e){throw new RuntimeException(e);}
        }
        System.out.println("UMB-BUILD-CLASSPATH-TEST-OK");
    }
}
