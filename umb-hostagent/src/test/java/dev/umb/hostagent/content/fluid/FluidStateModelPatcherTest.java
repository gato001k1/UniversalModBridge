package dev.umb.hostagent.content.fluid;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class FluidStateModelPatcherTest {
    @Test
    void patchesTheExactVerifiedBakeDescriptorAfterVanillaMapConstruction() throws Exception {
        Path client = Path.of("research", "jars", "26.2", "client.jar");
        assertTrue(Files.isRegularFile(client));
        byte[] original;
        try (JarFile jar = new JarFile(client.toFile())) {
            try (InputStream in = jar.getInputStream(jar.getJarEntry(
                    "net/minecraft/client/renderer/block/FluidStateModelSet.class"))) {
                original = in.readAllBytes();
            }
        }
        byte[] patched = FluidStateModelPatcher.patch(original);
        assertNotNull(patched);
        ClassNode cn = new ClassNode();
        new ClassReader(patched).accept(cn, 0);
        boolean helperCall = false;
        for (var m : cn.methods) if (m.name.equals("bake") && m.desc.equals(FluidStateModelPatcher.BAKE_DESC)) {
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
                        && call.owner.equals("dev/umb/hostagent/content/fluid/FluidStateModelPatcher")
                        && call.name.equals("addLegacyFluidModels")) helperCall = true;
            }
        }
        assertTrue(helperCall, "the exact bake(MaterialBaker):Map method must call the fluid helper");
    }
}
