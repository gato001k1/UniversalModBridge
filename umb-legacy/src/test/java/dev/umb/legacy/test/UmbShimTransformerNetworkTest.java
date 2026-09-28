package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy.legacyside.UmbShimTransformer;

/**
 * Boot-free regression test for the G2 step 4 additions to {@code UmbShimTransformer}: feeds the
 * REAL class bytes (from the built jars) through {@code transform()} and inspects the resulting
 * bytecode with plain core-ASM visitors (deliberately NOT {@code org.objectweb.asm.tree.*} - the
 * legacy universe's own bundled ASM, on this test's classpath via the 43 native libraries, is an
 * older major version than {@code tools/junit/asm-9.9.jar} and mixing tree-API classes from one
 * with core classes from the other risks a version-mismatch {@code NoSuchMethodError}).
 *
 * <p>A real headless boot does NOT prove these three classes' rewritten bodies are correct - it only
 * proves the boot did not load them with a MISMATCHING patch count (which would throw
 * {@code IllegalStateException} and fail the whole boot). This session's boot.log has no evidence
 * either {@code com.hbm.main.NetworkHandler} or {@code com.hbm.handler.threading.PacketThreading}
 * was even loaded during a boot that never ticks a world - so this direct test is the real gate.</p>
 */
class UmbShimTransformerNetworkTest {

    private static Path repoRoot() {
        String p = System.getProperty("umb.repo");
        if (p != null) {
            return Path.of(p);
        }
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve("umb-legacy")) && Files.isDirectory(cur.resolve("research"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("cannot locate repo root; pass -Dumb.repo=<path>");
    }

    private static byte[] readClass(File jar, String internalName) throws Exception {
        try (JarFile jf = new JarFile(jar)) {
            JarEntry e = jf.getJarEntry(internalName + ".class");
            assertNotNull(e, "missing " + internalName + " in " + jar);
            return jf.getInputStream(e).readAllBytes();
        }
    }

    @Test
    void openGuiBodyCallsUmbGui() throws Exception {
        File forgeSrg = repoRoot().resolve("build/legacy/forge-1.7.10-10.13.4.1614-srg.jar").toFile();
        byte[] original = readClass(forgeSrg, "cpw/mods/fml/common/network/internal/FMLNetworkHandler");
        byte[] transformed = new UmbShimTransformer().transform(
                "cpw.mods.fml.common.network.internal.FMLNetworkHandler",
                "cpw.mods.fml.common.network.internal.FMLNetworkHandler", original);

        final boolean[] found = {false};
        final boolean[] callsShim = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                              String[] exceptions) {
                if ("openGui".equals(name)
                        && "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/Object;ILnet/minecraft/world/World;III)V"
                                .equals(descriptor)) {
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                     boolean isInterface) {
                            if ("dev/umb/legacy/legacyside/UmbGui".equals(owner) && "openGui".equals(mname)) {
                                callsShim[0] = true;
                            }
                        }
                    };
                }
                return null;
            }
        }, 0);

        assertTrue(found[0], "openGui method not found in transformed FMLNetworkHandler");
        assertTrue(callsShim[0], "openGui body must call UmbGui.openGui");
    }

    @Test
    void resourceManagerAccessorIsRoutedThroughCaptureShim() throws Exception {
        File mcheli = repoRoot().resolve(
                "research/mods-third/mcheli-1.7.10-1.0.3-repackaged.jar").toFile();
        byte[] original = readClass(mcheli, "mcheli/wrapper/modelloader/W_WavefrontObject");
        byte[] transformed = new UmbShimTransformer().transform(
                "mcheli.wrapper.modelloader.W_WavefrontObject",
                "mcheli.wrapper.modelloader.W_WavefrontObject", original);

        final boolean[] routed = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "dev/umb/legacy/legacyside/render/LegacyRenderCapture".equals(owner)
                                && "currentResourceManager".equals(method)
                                && "(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/client/resources/IResourceManager;"
                                        .equals(desc)) {
                            routed[0] = true;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(routed[0], "legacy model loader must use the capture resource-manager shim");
    }

    @Test
    void gl15ModelAdaptersDelegateToPlainModelWithoutNativeCalls() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        byte[] original = readClass(hbm, "com/hbm/render/loader/HFRWavefrontObjectVBO");
        byte[] transformed = new UmbShimTransformer().transform(
                "com.hbm.render.loader.HFRWavefrontObjectVBO",
                "com.hbm.render.loader.HFRWavefrontObjectVBO", original);

        final boolean[] delegateField = {false};
        final boolean[] delegateRender = {false};
        final int[] gl15Calls = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                              String signature, Object value) {
                if ("umb$renderDelegate".equals(name)
                        && "Lnet/minecraftforge/client/model/IModelCustom;".equals(descriptor)) {
                    delegateField[0] = true;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                 String desc, boolean isInterface) {
                        if ("org/lwjgl/opengl/GL15".equals(owner)) gl15Calls[0]++;
                        if ("net/minecraftforge/client/model/IModelCustom".equals(owner)
                                && "renderAll".equals(method) && "renderAll".equals(name)) {
                            delegateRender[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(delegateField[0], "GL-backed model adapter needs a plain-model delegate");
        assertTrue(delegateRender[0], "renderAll must delegate to the capture-readable model");
        assertEquals(0, gl15Calls[0], "transformed model adapter must contain no GL15 call");
    }

    @Test
    void hbmClientInitializationHasNoResidualStaticLwjglCalls() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        String[] classes = {
                "com/hbm/main/ResourceManager",
                "com/hbm/main/ClientProxy",
                "com/hbm/render/item/ItemRenderMissileGeneric",
                "com/hbm/render/item/ItemRenderMissileGeneric$RenderMissileType",
                "com/hbm/render/loader/HFRWavefrontObject",
                "com/hbm/render/loader/HFRWavefrontObjectVBO",
                "com/hbm/render/shader/ShaderManager",
                "com/hbm/render/shader/Shader"
        };
        for (String internalName : classes) {
            byte[] transformed = new UmbShimTransformer().transform(
                    internalName.replace('/', '.'), internalName.replace('/', '.'),
                    readClass(hbm, internalName));
            final int[] residual = {0};
            new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                     String desc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKESTATIC
                                    && (owner.startsWith("org/lwjgl/opengl/")
                                    || "org/lwjgl/Sys".equals(owner)
                                    || "org/lwjgl/BufferUtils".equals(owner))) {
                                residual[0]++;
                            }
                        }
                    };
                }
            }, 0);
            assertEquals(0, residual[0], "legacy client init retains native LWJGL call in "
                    + internalName);
        }
    }

    @Test
    void packetThreadingNoOpsHaveNoCallsLeft() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        byte[] original = readClass(hbm, "com/hbm/handler/threading/PacketThreading");
        byte[] transformed = new UmbShimTransformer().transform(
                "com.hbm.handler.threading.PacketThreading",
                "com.hbm.handler.threading.PacketThreading", original);

        assertNoOpMethods(transformed,
                new String[] {"createAllAroundThreadedPacket", "createSendToThreadedPacket"}, 2);
    }

    @Test
    void genericSimpleNetworkWrapperIsRouted() throws Exception {
        File forgeSrg = repoRoot().resolve("build/legacy/forge-1.7.10-10.13.4.1614-srg.jar").toFile();
        byte[] original = readClass(forgeSrg, "cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper");
        byte[] transformed = new UmbShimTransformer().transform(
                "cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper",
                "cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper", original);
        assertRoutedMethods(transformed,
                new String[] {"sendToServer", "sendToDimension", "sendToAllAround",
                        "sendTo", "sendToAll"}, 5);
    }

    @Test
    void genericEventChannelIsRouted() throws Exception {
        File forgeSrg = repoRoot().resolve("build/legacy/forge-1.7.10-10.13.4.1614-srg.jar").toFile();
        byte[] original = readClass(forgeSrg, "cpw/mods/fml/common/network/FMLEventChannel");
        byte[] transformed = new UmbShimTransformer().transform(
                "cpw.mods.fml.common.network.FMLEventChannel",
                "cpw.mods.fml.common.network.FMLEventChannel", original);
        assertRoutedMethods(transformed,
                new String[] {"sendToServer", "sendToDimension", "sendToAllAround", "sendTo", "sendToAll"}, 5);
    }

    @Test
    void keyBindingRegistrationGetsStableIdHook() throws Exception {
        File forgeSrg = repoRoot().resolve("build/legacy/forge-1.7.10-10.13.4.1614-srg.jar").toFile();
        byte[] original = readClass(forgeSrg, "cpw/mods/fml/client/registry/ClientRegistry");
        byte[] transformed = new UmbShimTransformer().transform(
                "cpw.mods.fml.client.registry.ClientRegistry",
                "cpw.mods.fml.client.registry.ClientRegistry", original);
        final boolean[] found = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                              String[] exceptions) {
                if (!"registerKeyBinding".equals(name)) return null;
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                boolean isInterface) {
                        if ("dev/umb/legacy/legacyside/input/LegacyKeyBindingRegistry".equals(owner)
                                && "register".equals(mname)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(found[0], "ClientRegistry.registerKeyBinding must call the stable-id hook");
    }

    @Test
    void networkHandlerFallbackRoutesInsteadOfDroppingMessages() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        byte[] original = readClass(hbm, "com/hbm/main/NetworkHandler");
        byte[] transformed = new UmbShimTransformer().transform(
                "com.hbm.main.NetworkHandler", "com.hbm.main.NetworkHandler", original);

        assertRoutedMethods(transformed,
                new String[] {"sendToServer", "sendToDimension", "sendToAllAround", "sendTo", "sendToAll"}, 6);
    }

    @Test
    void customWrapperRegistrationInstantiatesHandlerClassForLoopback() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        byte[] original = readClass(hbm, "com/hbm/main/NetworkHandler");
        byte[] transformed = new UmbShimTransformer().transform(
                "com.hbm.main.NetworkHandler", "com.hbm.main.NetworkHandler", original);
        assertRoutedMethods(transformed, new String[] {"registerMessage"}, 1);
    }

    @Test
    void entityRendererRegistrationFailureDoesNotAbortLaterRows() throws Exception {
        File hbm = repoRoot().resolve("research/mods-hbm/HBM-NTM-1.0.27_X5771.jar").toFile();
        byte[] original = readClass(hbm, "com/hbm/main/ClientProxy");
        byte[] transformed = new UmbShimTransformer().transform(
                "com.hbm.main.ClientProxy", "com.hbm.main.ClientProxy", original);

        final int[] protectedRows = {0};
        final int[] failureHooks = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (!"registerEntityRenderer".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                    org.objectweb.asm.Label end,
                                                    org.objectweb.asm.Label handler,
                                                    String type) {
                        if ("java/lang/Throwable".equals(type)) protectedRows[0]++;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if ("dev/umb/legacy/legacyside/render/LegacyRenderCapture".equals(owner)
                                && "rendererRegistrationFailure".equals(method)) {
                            failureHooks[0]++;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(protectedRows[0] > 100,
                "the aggregate client hook must isolate each renderer row; got " + protectedRows[0]);
        assertEquals(protectedRows[0], failureHooks[0],
                "each protected renderer row needs a rate-limited failure hook");
    }

    @Test
    void riderWriteInstrumentationIsAdditiveWithInteractionInstrumentation() {
        byte[] original = syntheticRiderInteractionClass();
        byte[] transformed = new UmbShimTransformer().transform(
                "dev.umb.test.SyntheticRideEntity",
                "dev.umb.test.SyntheticRideEntity", original);

        final boolean[] riderHook = {false};
        final boolean[] interactionHook = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if ("dev/umb/legacy/legacyside/LegacyRiderTransitionDiag".equals(owner)
                                && "afterPassengerWrite".equals(method)) {
                            riderHook[0] = true;
                        }
                        if ("dev/umb/legacy/legacyside/LegacyInteractionDiag".equals(owner)
                                && "record".equals(method)) {
                            interactionHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(riderHook[0], "rider writes must remain instrumented");
        assertTrue(interactionHook[0], "interaction returns must remain instrumented");
    }

    private static byte[] syntheticRiderInteractionClass() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "dev/umb/test/SyntheticRideEntity", null,
                "net/minecraft/entity/Entity", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "field_70153_n", "Lnet/minecraft/entity/Entity;",
                null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/entity/Entity", "<init>",
                "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor interact = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_130002_c",
                "(Lnet/minecraft/entity/player/EntityPlayer;)Z", null, null);
        interact.visitCode();
        interact.visitVarInsn(Opcodes.ALOAD, 0);
        interact.visitInsn(Opcodes.ACONST_NULL);
        interact.visitFieldInsn(Opcodes.PUTFIELD, "dev/umb/test/SyntheticRideEntity",
                "field_70153_n", "Lnet/minecraft/entity/Entity;");
        interact.visitInsn(Opcodes.ICONST_1);
        interact.visitInsn(Opcodes.IRETURN);
        interact.visitMaxs(2, 2);
        interact.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Asserts every method whose name is in {@code names} (any descriptor) has zero method calls
     *  left in its body and does contain at least one RETURN, and that {@code expectedCount}
     *  methods matched by name (counting overloads). */
    private static void assertNoOpMethods(byte[] classBytes, String[] names, int expectedCount) {
        final int[] matched = {0};
        final java.util.List<String> withCalls = new java.util.ArrayList<String>();
        final java.util.List<String> withoutReturn = new java.util.ArrayList<String>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, final String name, final String descriptor,
                                              String signature, String[] exceptions) {
                boolean isTarget = false;
                for (String n : names) {
                    if (n.equals(name)) {
                        isTarget = true;
                        break;
                    }
                }
                if (!isTarget) {
                    return null;
                }
                matched[0]++;
                final boolean[] hasCall = {false};
                final boolean[] hasReturn = {false};
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                 boolean isInterface) {
                        hasCall[0] = true;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            hasReturn[0] = true;
                        }
                    }

                    @Override
                    public void visitEnd() {
                        if (hasCall[0]) {
                            withCalls.add(name + descriptor);
                        }
                        if (!hasReturn[0]) {
                            withoutReturn.add(name + descriptor);
                        }
                    }
                };
            }
        }, 0);

        assertEquals(expectedCount, matched[0], "expected " + expectedCount + " matching methods (incl. overloads)");
        assertTrue(withCalls.isEmpty(), "still contain method calls: " + withCalls);
        assertTrue(withoutReturn.isEmpty(), "missing a RETURN: " + withoutReturn);
    }

    private static void assertRoutedMethods(byte[] classBytes, String[] names, int expectedCount) {
        final int[] matched = {0};
        final java.util.List<String> missing = new java.util.ArrayList<String>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, final String name, String descriptor,
                                              String signature, String[] exceptions) {
                boolean target = false;
                for (String n : names) if (n.equals(name)) target = true;
                if (!target) return null;
                matched[0]++;
                final boolean[] routed = {false};
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                 String mdesc, boolean isInterface) {
                        if ("dev/umb/legacy/legacyside/network/LegacyNetworkLoopback".equals(owner)) {
                            routed[0] = true;
                        }
                    }
                    @Override public void visitEnd() {
                        if (!routed[0]) missing.add(name);
                    }
                };
            }
        }, 0);
        assertEquals(expectedCount, matched[0]);
        assertTrue(missing.isEmpty(), "unrouted fallback methods: " + missing);
    }
}
