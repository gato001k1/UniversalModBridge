package dev.umb.legacy.test;

import net.minecraft.launchwrapper.IClassTransformer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * IClassTransformer contract guard: a transformer must return its input unchanged for classes it does not target.
 * Returning null makes LaunchClassLoader NPE ("transformedClass is null") and silently erases EVERY class that passes through - this broke the...
 */
class TransformerPassthroughTest {

    private static final String[] TRANSFORMERS = {
            "dev.umb.legacy.legacyside.UmbShimTransformer",
            "dev.umb.legacy.legacyside.UmbForgeWorldProviderTransformer",
    };

    private static byte[] unrelatedClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "umb/test/NotATransformTarget", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void everyTransformerPassesUntargetedClassesThroughUnchanged() throws Exception {
        byte[] input = unrelatedClass();
        for (String name : TRANSFORMERS) {
            IClassTransformer t = (IClassTransformer) Class.forName(name).getDeclaredConstructor().newInstance();
            byte[] out = t.transform("umb.test.NotATransformTarget", "umb.test.NotATransformTarget", input);
            assertNotNull(out, name + " returned null for an untargeted class (erases every class)");
            assertArrayEquals(input, out, name + " modified an untargeted class");
        }
    }
}
