package dev.umb.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

import static org.junit.jupiter.api.Assertions.*;

class HostCommandTest {

    @TempDir Path tmp;

    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out)); cmd.setErr(new PrintWriter(err)); return cmd;
    }

    private static Path jarWithManifest(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name); var mf = new Manifest(); mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF")); mf.write(out); out.closeEntry();
            for (var e : entries.entrySet()) { out.putNextEntry(new JarEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); }
        }
        return p;
    }

    private static byte[] vec3iClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "net/minecraft/core/Vec3i", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "x", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "y", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "z", "I", null, null).visitEnd();
        var c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(III)V", null, null);
        c.visitVarInsn(Opcodes.ALOAD, 0); c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ILOAD, 1); c.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "x", "I");
        c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ILOAD, 2); c.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "y", "I");
        c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ILOAD, 3); c.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "z", "I");
        c.visitInsn(Opcodes.RETURN); c.visitMaxs(0, 0); c.visitEnd();
        for (String m : new String[]{"getX","getY","getZ"}) {
            String f = m.equals("getX")?"x":m.equals("getY")?"y":"z";
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m, "()I", null, null);
            mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/core/Vec3i", f, "I"); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        }
        cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] legacyIface() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT, "q/legacy/PosApi", null, "java/lang/Object", null);
        for (String m : new String[]{"getX","getY","getZ"}) { var mv=cw.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT, m, "()I", null, null); mv.visitEnd(); }
        cw.visitEnd(); return cw.toByteArray();
    }

    private static void defaultCtor(ClassWriter cw) {
        var mv=cw.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        mv.visitVarInsn(Opcodes.ALOAD,0); mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(1,1); mv.visitEnd();
    }

    private static byte[] seed() {
        var cw=new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52,Opcodes.ACC_PUBLIC,"p/Seed",null,"java/lang/Object",null); defaultCtor(cw);
        var mv=cw.visitMethod(Opcodes.ACC_PUBLIC,"onInitialize","()Ljava/lang/Object;",null,null);
        mv.visitTypeInsn(Opcodes.NEW,"net/minecraft/core/Vec3i"); mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH,100); mv.visitIntInsn(Opcodes.SIPUSH,200); mv.visitIntInsn(Opcodes.BIPUSH,-3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"net/minecraft/core/Vec3i","<init>","(III)V",false);
        mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0,0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] provider() {
        var cw=new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52,Opcodes.ACC_PUBLIC,"p/EntryPoint",null,"java/lang/Object",null); defaultCtor(cw);
        var mv=cw.visitMethod(Opcodes.ACC_PUBLIC,"onInitialize","(Lq/legacy/PosApi;)Ljava/lang/Object;",null,null);
        mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0,0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] consumer() {
        var cw=new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52,Opcodes.ACC_PUBLIC,"c/EntryPoint",null,"java/lang/Object",null); defaultCtor(cw);
        var mv=cw.visitMethod(Opcodes.ACC_PUBLIC,"onInitialize","(Lq/legacy/PosApi;)V",null,null);
        Label ok=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getX","()I",true);
        mv.visitIntInsn(Opcodes.BIPUSH,100); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad x");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        Label ok2=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getY","()I",true);
        mv.visitIntInsn(Opcodes.SIPUSH,200); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok2);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad y");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok2);
        Label ok3=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getZ","()I",true);
        mv.visitIntInsn(Opcodes.BIPUSH,-3); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok3);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad z");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok3); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0,0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] verify() {
        var cw=new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52,Opcodes.ACC_PUBLIC,"c/Verify",null,"java/lang/Object",null); defaultCtor(cw);
        var mv=cw.visitMethod(Opcodes.ACC_PUBLIC,"onInitialize","(Lq/legacy/PosApi;)V",null,null);
        Label ok=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getX","()I",true);
        mv.visitIntInsn(Opcodes.BIPUSH,100); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify x");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        Label ok2=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getY","()I",true);
        mv.visitIntInsn(Opcodes.SIPUSH,200); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok2);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify y");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok2);
        Label ok3=new Label(); mv.visitVarInsn(Opcodes.ALOAD,1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,"q/legacy/PosApi","getZ","()I",true);
        mv.visitIntInsn(Opcodes.BIPUSH,-3); mv.visitJumpInsn(Opcodes.IF_ICMPEQ,ok3);
        mv.visitTypeInsn(Opcodes.NEW,"java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify z");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;)V",false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok3); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0,0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] clean(String internal) {
        var cw=new ClassWriter(0); cw.visit(52,Opcodes.ACC_PUBLIC,internal,null,"java/lang/Object",null); cw.visitEnd(); return cw.toByteArray();
    }

    @Test
    void hostRunFourStageVerticalPlain() throws IOException {
        Path host=jarWithManifest(tmp,"host.jar", Map.of("net/minecraft/core/Vec3i.class", vec3iClass()));
        Path mod=jarWithManifest(tmp,"mod.jar", Map.of(
                "q/legacy/PosApi.class", legacyIface(),
                "p/Seed.class", seed(),
                "p/EntryPoint.class", provider(),
                "c/EntryPoint.class", consumer(),
                "c/Verify.class", verify()));
        Path plan=Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"p.Seed\",\"publish\":\"shared.vec3i\"},{\"class\":\"p.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}],\"publish\":\"shared.vec3i\"},{\"class\":\"c.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]},{\"class\":\"c.Verify\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]}]");
        var out=new StringWriter(); var err=new StringWriter();
        int code=cli(out,err).execute("host","run","--host",host.toString(),"--mod",mod.toString(),"--plan",plan.toString());
        assertEquals(0, code, out.toString() + err.toString());
        assertTrue(out.toString().contains("p.Seed completed"), out.toString());
        assertTrue(out.toString().contains("c.Verify completed"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void hostRunFourStageVerticalJson() throws IOException {
        Path host=jarWithManifest(tmp,"host.jar", Map.of("net/minecraft/core/Vec3i.class", vec3iClass()));
        Path mod=jarWithManifest(tmp,"mod.jar", Map.of(
                "q/legacy/PosApi.class", legacyIface(),
                "p/Seed.class", seed(),
                "p/EntryPoint.class", provider(),
                "c/EntryPoint.class", consumer(),
                "c/Verify.class", verify()));
        Path plan=Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"p.Seed\",\"publish\":\"shared.vec3i\"},{\"class\":\"p.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}],\"publish\":\"shared.vec3i\"},{\"class\":\"c.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]},{\"class\":\"c.Verify\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]}]");
        var out=new StringWriter(); var err=new StringWriter();
        int code=cli(out,err).execute("host","run","--host",host.toString(),"--mod",mod.toString(),"--plan",plan.toString(),"--json");
        assertEquals(0, code, out.toString());
        String line=out.toString().strip(); JsonObject o=JsonParser.parseString(line).getAsJsonObject();
        assertEquals(0, o.get("failed").getAsInt()); assertEquals(4, o.get("completed").getAsInt());
        assertEquals("", err.toString());
    }

    @Test
    void hostRunTwoModsConcurrentSharedRegistry() throws IOException {
        Path host=jarWithManifest(tmp,"host.jar", Map.of("net/minecraft/core/Vec3i.class", vec3iClass()));
        Path modA=jarWithManifest(tmp,"modA.jar", Map.of("q/legacy/PosApi.class", legacyIface(), "p/Seed.class", seed()));
        Path modB=jarWithManifest(tmp,"modB.jar", Map.of("q/legacy/PosApi.class", legacyIface(), "c/EntryPoint.class", consumer()));
        Path plan=Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"p.Seed\",\"publish\":\"shared.vec3i\"},{\"class\":\"c.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]}]");
        var out=new StringWriter(); var err=new StringWriter();
        int code=cli(out,err).execute("host","run","--host",host.toString(),"--mod",modA.toString(),"--mod",modB.toString(),"--plan",plan.toString());
        assertEquals(0, code, out.toString() + err.toString());
        assertTrue(out.toString().contains("p.Seed completed"), out.toString());
        assertTrue(out.toString().contains("c.EntryPoint completed"), out.toString());
    }

    @Test
    void hostSmokeSequentialGate() throws IOException {
        Path host=jarWithManifest(tmp,"host.jar", Map.of("net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod=jarWithManifest(tmp,"mod.jar", Map.of("q/Clean.class", clean("q/Clean")));
        var out=new StringWriter(); var err=new StringWriter();
        int code=cli(out,err).execute("host","smoke","--host",host.toString(), mod.toString());
        assertEquals(0, code, out.toString());
        assertTrue(out.toString().contains("smoked"), out.toString());
    }

    @Test
    void hostSmokeJson() throws IOException {
        Path host=jarWithManifest(tmp,"host.jar", Map.of("net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod=jarWithManifest(tmp,"mod.jar", Map.of("q/Clean.class", clean("q/Clean")));
        var out=new StringWriter(); var err=new StringWriter();
        int code=cli(out,err).execute("host","smoke","--host",host.toString(),"--json", mod.toString());
        assertEquals(0, code, out.toString() + err.toString());
        String line=out.toString().strip(); JsonObject o=JsonParser.parseString(line).getAsJsonObject();
        assertTrue(o.has("mods")); assertEquals(1, o.getAsJsonArray("mods").size());
        assertEquals("", err.toString());
    }
}
