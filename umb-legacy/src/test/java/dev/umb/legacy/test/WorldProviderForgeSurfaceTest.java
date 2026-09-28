package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy.legacyside.UmbForgeWorldProviderTransformer;

/** Regression guard for the complete Forge WorldProvider method surface. */
class WorldProviderForgeSurfaceTest {
    private static final String[][] FORGE_METHODS = {
        {"setDimension", "(I)V"},
        {"getSaveFolder", "()Ljava/lang/String;"},
        {"getWelcomeMessage", "()Ljava/lang/String;"},
        {"getDepartMessage", "()Ljava/lang/String;"},
        {"getMovementFactor", "()D"},
        {"getSkyRenderer", "()Lnet/minecraftforge/client/IRenderHandler;"},
        {"setSkyRenderer", "(Lnet/minecraftforge/client/IRenderHandler;)V"},
        {"getCloudRenderer", "()Lnet/minecraftforge/client/IRenderHandler;"},
        {"setCloudRenderer", "(Lnet/minecraftforge/client/IRenderHandler;)V"},
        {"getWeatherRenderer", "()Lnet/minecraftforge/client/IRenderHandler;"},
        {"setWeatherRenderer", "(Lnet/minecraftforge/client/IRenderHandler;)V"},
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

    @Test
    void transformedRuntimeHasEveryForgeAddedWorldProviderSignature() throws Exception {
        Path repo = Paths.get(System.getProperty("umb.repo", "."));
        Path runtime = repo.resolve("build/legacy/1.7.10-forge-srg-runtime-fields.jar");
        byte[] input;
        try (JarFile jar = new JarFile(runtime.toFile());
                InputStream stream = jar.getInputStream(jar.getJarEntry("net/minecraft/world/WorldProvider.class"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
            input = bytes.toByteArray();
        }
        byte[] transformed = new UmbForgeWorldProviderTransformer().transform(
                "net.minecraft.world.WorldProvider", "net.minecraft.world.WorldProvider", input);
        final Set<String> methods = new HashSet<String>();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                methods.add(name + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String[] signature : FORGE_METHODS) {
            assertTrue(methods.contains(signature[0] + signature[1]),
                    signature[0] + signature[1] + " missing from transformed WorldProvider");
        }
    }
}
