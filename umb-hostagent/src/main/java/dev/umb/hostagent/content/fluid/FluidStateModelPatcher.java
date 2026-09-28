package dev.umb.hostagent.content.fluid;

import dev.umb.hostagent.AgentLog;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.material.Fluid;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;

/** Patches FluidStateModelSet.bake after vanilla's water/lava Map.of has been built. */
public final class FluidStateModelPatcher implements ClassFileTransformer {
    public static final String TARGET = "net/minecraft/client/renderer/block/FluidStateModelSet";
    static final String BAKE_DESC = "(Lnet/minecraft/client/resources/model/sprite/MaterialBaker;)Ljava/util/Map;";
    private static final String HELPER_DESC =
            "(Ljava/util/Map;Lnet/minecraft/client/resources/model/sprite/MaterialBaker;)Ljava/util/Map;";
    public static volatile int lastAdded;

    @Override public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(className)) return null;
        try {
            byte[] patched = patch(bytes);
            AgentLog.loud("PATCHED FluidStateModelSet.bake" + BAKE_DESC + " legacyFluids=" + lastAdded);
            return patched;
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED FluidStateModelSet.bake: " + t);
            AgentLog.error("FluidStateModelPatcher.transform", t, 5);
            return null;
        }
    }

    /** Package-visible for bytecode tests; injects only into the exact verified bake descriptor. */
    static byte[] patch(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (!"bake".equals(m.name) || !BAKE_DESC.equals(m.desc) || (m.access & Opcodes.ACC_STATIC) == 0) continue;
            AbstractInsnNode ret = m.instructions.getLast();
            while (ret != null && ret.getOpcode() != Opcodes.ARETURN) ret = ret.getPrevious();
            if (ret == null) return null;
            InsnList add = new InsnList();
            add.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 1));
            add.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
            add.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/umb/hostagent/content/fluid/FluidStateModelPatcher",
                    "addLegacyFluidModels", HELPER_DESC, false));
            m.instructions.insertBefore(ret, add);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        }
        return null;
    }

    /** Called from the patched method while registries and the resource baker are live. */
    public static Map<Fluid, FluidModel> addLegacyFluidModels(Map<Fluid, FluidModel> vanilla,
                                                                MaterialBaker baker) {
        Map<Fluid, FluidModel> out = new LinkedHashMap<>(vanilla);
        int added = 0;
        for (Map.Entry<ResourceKey<Fluid>, Fluid> e : BuiltInRegistries.FLUID.entrySet()) {
            if (!(e.getValue() instanceof GeneratedFluid fluid) || !fluid.sourceMember()) continue;
            FluidEntry data = fluid.entry();
            String base = "fluid_" + dev.umb.hostagent.content.LegacyIds.sanitizePath(data.name);
            Identifier fluidId = e.getKey().identifier();
            String ns = fluidId.getNamespace();
            Identifier still = Identifier.fromNamespaceAndPath(ns, "block/" + base + "_still");
            Identifier flowing = Identifier.fromNamespaceAndPath(ns, "block/" + base + "_flow");
            ModelDebugName debug = () -> "UMB fluid " + fluidId;
            BlockTintSource white = state -> 0xFFFFFFFF;
            FluidModel model = new FluidModel.Unbaked(new Material(still), new Material(flowing), null, white)
                    .bake(baker, debug);
            out.put(fluid, model);
            out.put(fluid.getFlowing(), model);
            added++;
        }
        lastAdded = added;
        return out;
    }
}
