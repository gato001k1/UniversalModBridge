package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.HostWorld;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.util.Random;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Defines the concrete SRG-named World subclass through the same deobfuscated loader as the TE. */
public final class Legacy1122WorldFacadeRaw {
    private static volatile Object currentTile;
    private static volatile Object currentState;
    public static void currentTile(Object tile) { currentTile = tile; }
    public static Object currentTile() { return currentTile; }
    public static void currentState(Object state) { currentState = state; }
    public static Object currentState() { return currentState; }
    static Object create(HostWorld host) {
        try {
            ClassLoader parent = Legacy1122WorldFacadeRaw.class.getClassLoader();
            Class<?> worldClass = Class.forName("net.minecraft.world.World", true, parent);
            Class<?> facadeType = new FacadeLoader(parent, worldClass.getProtectionDomain()).define(facadeBytes());
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeClass.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            Object unsafe = singleton.get(null);
            Object world = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, facadeType);
            Field remote = worldClass.getDeclaredField("field_72995_K");
            remote.setAccessible(true);
            remote.setBoolean(world, host != null && host.isRemote());
            Field random = null;
            for (String name : new String[]{"field_73012_v", "r"}) try {
                random = worldClass.getDeclaredField(name); random.setAccessible(true); break;
            } catch (NoSuchFieldException ignored) { }
            if (random == null) throw new NoSuchFieldException("field_73012_v/r");
            try {
                random.set(world, new Random(0L));
            } catch (IllegalAccessException finalField) {
                // World.rand is final in the notch class. Unsafe is already required by the
                // constructor-free facade, so use the field offset as the Java-25-safe fallback.
                long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                        .invoke(unsafe, random);
                unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                        .invoke(unsafe, world, Long.valueOf(offset), new Random(0L));
            }
            Field weather = null;
            for (String name : new String[]{"field_73021_x", "u"}) try {
                weather = worldClass.getDeclaredField(name); weather.setAccessible(true); break;
            } catch (NoSuchFieldException ignored) { }
            if (weather == null) throw new NoSuchFieldException("field_73021_x/u");
            Object weatherList = new ArrayList<Object>();
            try {
                weather.set(world, weatherList);
            } catch (IllegalAccessException finalField) {
                long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                        .invoke(unsafe, weather);
                unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                        .invoke(unsafe, world, Long.valueOf(offset), weatherList);
            }
            Class<?> providerType = Class.forName("net.minecraft.world.chunk.IChunkProvider", true, parent);
            Object provider = Proxy.newProxyInstance(parent, new Class<?>[]{providerType},
                    (proxy, method, args) -> method.getReturnType() == Boolean.TYPE ? Boolean.FALSE
                            : method.getReturnType() == Boolean.class ? Boolean.FALSE
                            : method.getReturnType() == Integer.TYPE || method.getReturnType() == Integer.class ? Integer.valueOf(0)
                            : method.getReturnType() == Long.TYPE || method.getReturnType() == Long.class ? Long.valueOf(0L)
                            : method.getReturnType() == Float.TYPE || method.getReturnType() == Float.class ? Float.valueOf(0.0F)
                            : method.getReturnType() == Double.TYPE || method.getReturnType() == Double.class ? Double.valueOf(0.0D)
                            : null);
            Field chunks = null;
            for (String name : new String[]{"field_73020_y", "v"}) try {
                chunks = worldClass.getDeclaredField(name); chunks.setAccessible(true); break;
            } catch (NoSuchFieldException ignored) { }
            if (chunks == null) throw new NoSuchFieldException("field_73020_y/v");
            chunks.set(world, provider);
            return world;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot allocate 1.12.2 World facade", t);
        }
    }

    private static byte[] facadeBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "dev/umb/legacy1122/legacyside/Legacy1122WorldFacadeGenerated", null,
                "net/minecraft/world/World", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/world/storage/ISaveHandler;Lnet/minecraft/world/storage/WorldInfo;"
                        + "Lnet/minecraft/world/WorldProvider;Lnet/minecraft/profiler/Profiler;Z)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitVarInsn(Opcodes.ALOAD, 4);
        ctor.visitVarInsn(Opcodes.ILOAD, 5);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/world/World", "<init>",
                "(Lnet/minecraft/world/storage/ISaveHandler;Lnet/minecraft/world/storage/WorldInfo;"
                        + "Lnet/minecraft/world/WorldProvider;Lnet/minecraft/profiler/Profiler;Z)V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();

        MethodVisitor provider = cw.visitMethod(Opcodes.ACC_PROTECTED, "func_72863_F",
                "()Lnet/minecraft/world/chunk/IChunkProvider;", null, null);
        provider.visitCode(); provider.visitInsn(Opcodes.ACONST_NULL); provider.visitInsn(Opcodes.ARETURN);
        provider.visitMaxs(0, 0); provider.visitEnd();

        MethodVisitor loaded = cw.visitMethod(Opcodes.ACC_PROTECTED, "func_175680_a", "(IIZ)Z", null, null);
        loaded.visitCode(); loaded.visitInsn(Opcodes.ICONST_1); loaded.visitInsn(Opcodes.IRETURN);
        loaded.visitMaxs(0, 0); loaded.visitEnd();
        MethodVisitor time = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_82737_E", "()J", null, null);
        time.visitCode(); time.visitInsn(Opcodes.LCONST_0); time.visitInsn(Opcodes.LRETURN);
        time.visitMaxs(0, 0); time.visitEnd();
        MethodVisitor entities = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_72872_a",
                "(Ljava/lang/Class;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;", null, null);
        entities.visitCode();
        entities.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                "()Ljava/util/List;", false);
        entities.visitInsn(Opcodes.ARETURN); entities.visitMaxs(0, 0); entities.visitEnd();
        MethodVisitor tile = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_175625_s",
                "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;", null, null);
        tile.visitCode(); tile.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/umb/legacy1122/legacyside/Legacy1122WorldFacadeRaw", "currentTile", "()Ljava/lang/Object;", false);
        tile.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/tileentity/TileEntity");
        tile.visitInsn(Opcodes.ARETURN); tile.visitMaxs(0, 0); tile.visitEnd();
        MethodVisitor state = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_180495_p",
                "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;", null, null);
        state.visitCode(); state.visitMethodInsn(Opcodes.INVOKESTATIC,
                "dev/umb/legacy1122/legacyside/Legacy1122WorldFacadeRaw", "currentState", "()Ljava/lang/Object;", false);
        state.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/block/state/IBlockState");
        state.visitInsn(Opcodes.ARETURN); state.visitMaxs(0, 0); state.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class FacadeLoader extends ClassLoader {
        private final ProtectionDomain domain;
        FacadeLoader(ClassLoader parent, ProtectionDomain domain) { super(parent); this.domain = domain; }
        Class<?> define(byte[] bytes) {
            return defineClass("dev.umb.legacy1122.legacyside.Legacy1122WorldFacadeGenerated",
                    bytes, 0, bytes.length, domain);
        }
    }
}
