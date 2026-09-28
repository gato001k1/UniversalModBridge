package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The UMB menu button patch must produce verifiable bytecode. 2026-09-25 22:50 it shipped with a
 * missing receiver ALOAD and every pause-menu open crashed the client (VerifyError "Operand stack
 * underflow" in PauseScreen.init). Run a real stack analysis over the patched init() of both screens.
 */
class UmbMenuPatcherTest {
    @Test
    void pauseAndOptionsInitStayVerifiable() throws Exception {
        String[] screens = {
                "net/minecraft/client/gui/screens/PauseScreen",
                "net/minecraft/client/gui/screens/options/OptionsScreen"};
        try (JarFile jar = new JarFile("research/jars/26.2/client.jar")) {
            for (String screen : screens) {
                byte[] original;
                try (InputStream in = jar.getInputStream(jar.getJarEntry(screen + ".class"))) {
                    original = in.readAllBytes();
                }
                byte[] patched = new UmbMenuPatcher().transform(null, screen, null, null, original);
                assertNotNull(patched, "patcher must apply to " + screen);
                ClassNode cn = new ClassNode();
                new ClassReader(patched).accept(cn, 0);
                int analyzed = 0;
                for (MethodNode m : cn.methods) {
                    if (!"init".equals(m.name) || !"()V".equals(m.desc)) continue;
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, m);
                    analyzed++;
                }
                assertEquals(1, analyzed, "exactly one init()V in " + screen);
            }
        }
    }
}
