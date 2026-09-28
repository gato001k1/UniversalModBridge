package dev.umb.legacy.legacyside;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.Type;

/** Restores Forge WorldProvider methods absent from the supplied vanilla dump. */
public final class UmbForgeWorldProviderTransformer implements IClassTransformer {
    private static final String TARGET = "net/minecraft/world/WorldProvider";
    private static final String TARGET_INTERNAL = "net/minecraft/world/WorldProvider";
    private static final String WORLD = "net/minecraft/world/World";
    private static final String WORLD_INFO = "net/minecraft/world/storage/WorldInfo";
    private static final String RENDER_HANDLER = "Lnet/minecraftforge/client/IRenderHandler;";

/** Legacy compatibility behavior. */
    private static final String[][] FORGE_METHODS = {
        {"setDimension", "(I)V"},
        {"getSaveFolder", "()Ljava/lang/String;"},
        {"getWelcomeMessage", "()Ljava/lang/String;"},
        {"getDepartMessage", "()Ljava/lang/String;"},
        {"getMovementFactor", "()D"},
        {"getSkyRenderer", "()" + RENDER_HANDLER},
        {"setSkyRenderer", "(" + RENDER_HANDLER + ")V"},
        {"getCloudRenderer", "()" + RENDER_HANDLER},
        {"setCloudRenderer", "(" + RENDER_HANDLER + ")V"},
        {"getWeatherRenderer", "()" + RENDER_HANDLER},
        {"setWeatherRenderer", "(" + RENDER_HANDLER + ")V"},
        {"getRandomizedSpawnPoint", "()Lnet/minecraft/util/ChunkCoordinates;"},
        {"shouldMapSpin", "(Ljava/lang/String;DDD)Z"},
        {"getRespawnDimension", "(Lnet/minecraft/entity/player/EntityPlayerMP;)I"},
        {"getBiomeGenForCoords", "(II)Lnet/minecraft/world/biome/BiomeGenBase;"},
        {"isDaytime", "()Z"},
        {"getSunBrightnessFactor", "(F)F"},
        {"getCurrentMoonPhaseFactor", "()F"},
        {"getSkyColor", "(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/util/Vec3;"},
        {"drawClouds", "(F)Lnet/minecraft/util/Vec3;"},
        {"getSunBrightness", "(F)F"},
        {"getStarBrightness", "(F)F"},
        {"setAllowedSpawnTypes", "(ZZ)V"},
        {"calculateInitialWeather", "()V"},
        {"updateWeather", "()V"},
        {"canBlockFreeze", "(IIIZ)Z"},
        {"canSnowAt", "(IIIZ)Z"},
        {"setWorldTime", "(J)V"},
        {"getSeed", "()J"},
        {"getWorldTime", "()J"},
        {"getSpawnPoint", "()Lnet/minecraft/util/ChunkCoordinates;"},
        {"setSpawnPoint", "(III)V"},
        {"canMineBlock", "(Lnet/minecraft/entity/player/EntityPlayer;III)Z"},
        {"isBlockHighHumidity", "(III)Z"},
        {"getHeight", "()I"},
        {"getActualHeight", "()I"},
        {"getHorizon", "()D"},
        {"resetRainAndThunder", "()V"},
        {"canDoLightning", "(Lnet/minecraft/world/chunk/Chunk;)Z"},
        {"canDoRainSnowIce", "(Lnet/minecraft/world/chunk/Chunk;)Z"}
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        String target = transformedName == null ? name : transformedName;
        if (basicClass == null || !TARGET.equals(target.replace('.', '/'))) {
            return basicClass; // IClassTransformer contract: untouched classes pass through (null erases them)
        }
        ClassNode node = new ClassNode();
        new ClassReader(basicClass).accept(node, 0);
        boolean changed = addForgeFields(node);
        for (String[] signature : FORGE_METHODS) {
            if (!hasMethod(node, signature[0], signature[1])) {
                node.methods.add(forgeMethod(signature[0], signature[1]));
                changed = true;
            }
        }
        if (!changed) return basicClass;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean addForgeFields(ClassNode node) {
        boolean changed = false;
        changed |= addField(node, "skyRenderer");
        changed |= addField(node, "cloudRenderer");
        changed |= addField(node, "weatherRenderer");
        return changed;
    }

    private static boolean addField(ClassNode node, String name) {
        for (Object object : node.fields) {
            if (name.equals(((FieldNode) object).name)
                    && RENDER_HANDLER.equals(((FieldNode) object).desc)) {
                return false;
            }
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, name, RENDER_HANDLER, null, null));
        return true;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        for (Object object : node.methods) {
            MethodNode method = (MethodNode) object;
            if (name.equals(method.name) && desc.equals(method.desc)) return true;
        }
        return false;
    }

    private static MethodNode forgeMethod(String name, String desc) {
        if ("getHeight".equals(name)) return returnConstant(name, 256);
        if ("getActualHeight".equals(name)) return actualHeight();
        if ("getSpawnPoint".equals(name) || "getRandomizedSpawnPoint".equals(name)) return spawnPoint(name);
        if ("getSeed".equals(name)) return worldInfoNoArgs(name, desc, "func_76063_b", "()J");
        if ("getWorldTime".equals(name)) return worldInfoNoArgs(name, desc, "func_76073_f", "()J");
        if ("setWorldTime".equals(name)) return worldInfoArgs(name, desc, "func_76068_b", "(J)V", new int[] {Opcodes.LLOAD});
        if ("setSpawnPoint".equals(name)) return worldInfoArgs(name, desc, "func_76081_a", "(III)V",
                new int[] {Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD});
        if ("getBiomeGenForCoords".equals(name)) return worldArgs(name, desc, "getBiomeGenForCoordsBody",
                "(II)Lnet/minecraft/world/biome/BiomeGenBase;", new int[] {Opcodes.ILOAD, Opcodes.ILOAD});
        if ("getSunBrightnessFactor".equals(name)) return worldArgs(name, desc, "getSunBrightnessFactor",
                "(F)F", new int[] {Opcodes.FLOAD});
        if ("getCurrentMoonPhaseFactor".equals(name)) return worldNoArgs(name, desc,
                "getCurrentMoonPhaseFactorBody", "()F");
        if ("getSkyColor".equals(name)) return worldArgs(name, desc, "getSkyColorBody",
                "(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/util/Vec3;",
                new int[] {Opcodes.ALOAD, Opcodes.FLOAD});
        if ("drawClouds".equals(name)) return worldArgs(name, desc, "drawCloudsBody",
                "(F)Lnet/minecraft/util/Vec3;", new int[] {Opcodes.FLOAD});
        if ("getSunBrightness".equals(name)) return worldArgs(name, desc, "getSunBrightnessBody",
                "(F)F", new int[] {Opcodes.FLOAD});
        if ("getStarBrightness".equals(name)) return worldArgs(name, desc, "getStarBrightnessBody",
                "(F)F", new int[] {Opcodes.FLOAD});
        if ("setAllowedSpawnTypes".equals(name)) return worldArgs(name, desc, "func_72891_a",
                "(ZZ)V", new int[] {Opcodes.ILOAD, Opcodes.ILOAD});
        if ("calculateInitialWeather".equals(name)) return worldNoArgs(name, desc,
                "calculateInitialWeatherBody", "()V");
        if ("updateWeather".equals(name)) return worldNoArgs(name, desc, "updateWeatherBody", "()V");
        if ("canBlockFreeze".equals(name)) return worldArgs(name, desc, "canBlockFreezeBody",
                "(IIIZ)Z", new int[] {Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD});
        if ("canSnowAt".equals(name)) return worldArgs(name, desc, "canSnowAtBody",
                "(IIIZ)Z", new int[] {Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD});
        if ("canMineBlock".equals(name)) return worldArgs(name, desc, "canMineBlockBody",
                "(Lnet/minecraft/entity/player/EntityPlayer;III)Z",
                new int[] {Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ILOAD});
        if ("isBlockHighHumidity".equals(name)) return highHumidity();
        if ("resetRainAndThunder".equals(name)) return resetRainAndThunder();
        if ("getSkyRenderer".equals(name) || "getCloudRenderer".equals(name)
                || "getWeatherRenderer".equals(name)) return rendererGetter(name);
        if ("setSkyRenderer".equals(name) || "setCloudRenderer".equals(name)
                || "setWeatherRenderer".equals(name)) return rendererSetter(name);
        if ("setDimension".equals(name)) return setDimension();
        if ("getMovementFactor".equals(name)) return constantDouble(name, desc, 1.0D);
        if ("getHorizon".equals(name)) return constantDouble(name, desc, 63.0D);
        if ("canDoLightning".equals(name) || "canDoRainSnowIce".equals(name)) return constantBoolean(name, desc, true);
        return defaultMethod(name, desc);
    }

    private static MethodNode returnConstant(String name, int value) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()I", null, null);
        method.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private static MethodNode defaultMethod(String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        appendDefaultReturn(method, desc);
        return method;
    }

    private static MethodNode constantBoolean(String name, String desc, boolean value) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private static MethodNode constantDouble(String name, String desc, double value) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        if (value == 0.0D) {
            method.instructions.add(new InsnNode(Opcodes.DCONST_0));
        } else if (value == 1.0D) {
            method.instructions.add(new InsnNode(Opcodes.DCONST_1));
        } else {
            method.instructions.add(new LdcInsnNode(Double.valueOf(value)));
        }
        method.instructions.add(new InsnNode(Opcodes.DRETURN));
        return method;
    }

    private static void appendDefaultReturn(MethodNode method, String desc) {
        switch (Type.getReturnType(desc).getSort()) {
        case Type.VOID:
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            break;
        case Type.LONG:
            method.instructions.add(new InsnNode(Opcodes.LCONST_0));
            method.instructions.add(new InsnNode(Opcodes.LRETURN));
            break;
        case Type.FLOAT:
            method.instructions.add(new InsnNode(Opcodes.FCONST_0));
            method.instructions.add(new InsnNode(Opcodes.FRETURN));
            break;
        case Type.DOUBLE:
            method.instructions.add(new InsnNode(Opcodes.DCONST_0));
            method.instructions.add(new InsnNode(Opcodes.DRETURN));
            break;
        case Type.ARRAY:
        case Type.OBJECT:
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            break;
        default:
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            break;
        }
    }

    private static MethodNode setDimension() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "setDimension", "(I)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, "field_76574_g", "I"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode rendererGetter(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + RENDER_HANDLER, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, rendererField(name), RENDER_HANDLER));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode rendererSetter(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(" + RENDER_HANDLER + ")V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_INTERNAL, rendererField(name), RENDER_HANDLER));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static String rendererField(String method) {
        if (method.indexOf("Sky") >= 0) return "skyRenderer";
        if (method.indexOf("Cloud") >= 0) return "cloudRenderer";
        return "weatherRenderer";
    }

    private static MethodNode worldNoArgs(String name, String desc, String targetName, String targetDesc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        worldPrefix(method.instructions);
        invokeWorld(method.instructions, targetName, targetDesc);
        appendReturn(method, targetDesc);
        return method;
    }

    private static MethodNode worldArgs(String name, String desc, String targetName, String targetDesc,
            int[] loads) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        worldPrefix(method.instructions);
        loadArguments(method.instructions, loads);
        invokeWorld(method.instructions, targetName, targetDesc);
        appendReturn(method, targetDesc);
        return method;
    }

    private static MethodNode worldInfoNoArgs(String name, String desc, String targetName, String targetDesc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        worldInfoPrefix(method.instructions);
        invokeWorldInfo(method.instructions, targetName, targetDesc);
        appendReturn(method, targetDesc);
        return method;
    }

    private static MethodNode worldInfoArgs(String name, String desc, String targetName, String targetDesc,
            int[] loads) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        worldInfoPrefix(method.instructions);
        loadArguments(method.instructions, loads);
        invokeWorldInfo(method.instructions, targetName, targetDesc);
        appendReturn(method, targetDesc);
        return method;
    }

    private static void worldPrefix(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_INTERNAL, "field_76579_a", "L" + WORLD + ";"));
    }

    private static void worldInfoPrefix(InsnList code) {
        worldPrefix(code);
        code.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, "field_72986_A", "L" + WORLD_INFO + ";"));
    }

    private static void loadArguments(InsnList code, int[] loads) {
        int slot = 1;
        for (int load : loads) {
            code.add(new VarInsnNode(load, slot));
            slot += load == Opcodes.LLOAD || load == Opcodes.DLOAD ? 2 : 1;
        }
    }

    private static void invokeWorld(InsnList code, String name, String desc) {
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD, name, desc, false));
    }

    private static void invokeWorldInfo(InsnList code, String name, String desc) {
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_INFO, name, desc, false));
    }

    private static void appendReturn(MethodNode method, String desc) {
        switch (Type.getReturnType(desc).getSort()) {
        case Type.VOID:
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            break;
        case Type.LONG:
            method.instructions.add(new InsnNode(Opcodes.LRETURN));
            break;
        case Type.FLOAT:
            method.instructions.add(new InsnNode(Opcodes.FRETURN));
            break;
        case Type.DOUBLE:
            method.instructions.add(new InsnNode(Opcodes.DRETURN));
            break;
        case Type.ARRAY:
        case Type.OBJECT:
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            break;
        default:
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            break;
        }
    }

    private static MethodNode highHumidity() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "isBlockHighHumidity", "(III)Z", null, null);
        worldPrefix(method.instructions);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD, "func_72807_a",
                "(II)Lnet/minecraft/world/biome/BiomeGenBase;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/biome/BiomeGenBase",
                "func_76736_e", "()Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private static MethodNode resetRainAndThunder() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "resetRainAndThunder", "()V", null, null);
        worldInfoPrefix(method.instructions);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_INFO, "func_76080_g", "(I)V", false));
        worldInfoPrefix(method.instructions);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_INFO, "func_76084_b", "(Z)V", false));
        worldInfoPrefix(method.instructions);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_INFO, "func_76090_f", "(I)V", false));
        worldInfoPrefix(method.instructions);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_INFO, "func_76069_a", "(Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static MethodNode actualHeight() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getActualHeight", "()I", null, null);
        LabelNode normal = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET.replace('.', '/'), "field_76576_e", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, normal));
        code.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, 128));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(normal);
        code.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, 256));
        code.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    /** Forge's client API name delegates to the SRG vanilla spawn-point method. */
    private static MethodNode spawnPoint(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name,
                "()Lnet/minecraft/util/ChunkCoordinates;", null, null);
        LabelNode fallback = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                TARGET.replace('.', '/'), "func_76554_h",
                "()Lnet/minecraft/util/ChunkCoordinates;", false));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(fallback);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,
                "net/minecraft/util/ChunkCoordinates"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/util/ChunkCoordinates", "<init>", "(III)V", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

}
