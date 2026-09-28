package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.HostPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.UUID;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A constructor-free EntityPlayer facade for Forge's server-side openGui path. */
public final class Legacy1122PlayerFacadeRaw {
    public static void openGui(Object player, Object mod, int id, Object world, int x, int y, int z) {
        try {
            ClassLoader l = player.getClass().getClassLoader();
            Class<?> nr = Class.forName("net.minecraftforge.fml.common.network.NetworkRegistry", true, l);
            Object registry = nr.getField("INSTANCE").get(null);
            Class<?> fml = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler", true, l);
            Object containerOwner = fml.getMethod("instance").invoke(null);
            Object modContainer = fml.getMethod("findContainerFor", Object.class).invoke(containerOwner, mod);
            Method target = null;
            // FMLNetworkHandler.openGui uses getRemoteGuiContainer on the server.  The
            // similarly-shaped getLocalGuiContainer dispatches the client handler and is
            // therefore allowed to return null for a valid server-side GUI.
            for (Method m : nr.getMethods()) if (m.getName().equals("getRemoteGuiContainer") && m.getParameterTypes().length == 7) { target = m; break; }
            if (target == null) throw new NoSuchMethodException("NetworkRegistry.getRemoteGuiContainer");
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            Object container;
            try {
                thread.setContextClassLoader(l);
                container = target.invoke(registry, modContainer, player, Integer.valueOf(id), world,
                        Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
            } finally { thread.setContextClassLoader(previous); }
            if (container == null) {
                // Forge's helper swallows handler exceptions and returns null. Use the same
                // registered server handler directly so a valid legacy container still crosses
                // the boundary and any real handler failure remains observable.
                try {
                    Field handlersField = nr.getDeclaredField("serverGuiHandlers");
                    handlersField.setAccessible(true);
                    Object handlers = handlersField.get(registry);
                    Object handler = handlers instanceof java.util.Map ? ((java.util.Map) handlers).get(modContainer) : null;
                    if (handler != null) {
                        Method serverGui = null;
                        for (Method m : handler.getClass().getMethods())
                            if (m.getName().equals("getServerGuiElement") && m.getParameterTypes().length == 6) { serverGui = m; break; }
                        if (serverGui != null) {
                            try {
                                container = serverGui.invoke(handler, Integer.valueOf(id), player, world,
                                        Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
                            } catch (Throwable directFailure) {
                                Throwable root = directFailure;
                                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                                System.err.println("[UMB-1122] direct GUI handler failed root=" + root);
                            }
                        }
                    }
                } catch (Throwable lookupFailure) {
                    System.err.println("[UMB-1122] server GUI handler lookup failed: " + lookupFailure);
                }
            }
            if (container == null) {
                // Diagnostic (lead 07:10): the mod's IGuiHandler returned null. Most handlers look
                // the TE up through world.getTileEntity(pos), so report what the facade returns.
                Object te = null;
                try {
                    Class<?> posType = Class.forName("net.minecraft.util.math.BlockPos", true, l);
                    Object pos = posType.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                    Method getTe = null;
                    for (Class<?> c = world.getClass(); c != null && getTe == null; c = c.getSuperclass())
                        for (Method m : c.getDeclaredMethods())
                            if (m.getName().equals("func_175625_s") && m.getParameterTypes().length == 1) { getTe = m; break; }
                    if (getTe != null) { getTe.setAccessible(true); te = getTe.invoke(world, pos); }
                } catch (Throwable probe) { te = "probe-failed:" + probe; }
                System.out.println("[UMB-1122] openGui returned null container: mod=" + mod + " modContainer=" + modContainer
                        + " id=" + id + " pos=" + x + "," + y + "," + z + " world=" + world.getClass().getName()
                        + " worldLoader=" + world.getClass().getClassLoader() + " playerLoader=" + player.getClass().getClassLoader()
                        + " inventory=" + value(player, "field_71071_by", "bv")
                        + " tile=" + te + " tileLoader=" + (te == null ? null : te.getClass().getClassLoader()));
            }
            if (container != null) {
                set(player.getClass(), player, "field_71069_bz", "bx", container);
                set(player.getClass(), player, "field_71070_bA", "by", container);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("1.12.2 openGui capture failed", t);
        }
    }
    private static Object value(Object receiver, String named, String raw) {
        try { Field f = field(receiver.getClass(), named, raw); return f == null ? null : f.get(receiver); }
        catch (Throwable t) { return "<error:" + t + ">"; }
    }
    static Object create(Object world, HostPlayer host) {
        try {
            ClassLoader loader = world.getClass().getClassLoader();
            Class<?> playerClass = Class.forName("net.minecraft.entity.player.EntityPlayerMP", true, loader);
            Class<?> type = new Definer(loader, playerClass.getProtectionDomain()).define(bytes());
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeClass.getDeclaredField("theUnsafe"); uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Object player = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
            set(playerClass, player, "field_70170_p", "u", world);
            Method position = method(playerClass, "setPosition", "func_70107_b", 3);
            if (position != null) position.invoke(player, host == null ? 0.0D : host.getX(),
                    host == null ? 0.0D : host.getY(), host == null ? 0.0D : host.getZ());
            Class<?> gp = Class.forName("com.mojang.authlib.GameProfile", true, loader);
            Object profile = gp.getConstructor(UUID.class, String.class).newInstance(UUID.nameUUIDFromBytes(
                    ("UMB1122:" + (host == null ? "player" : host.getName())).getBytes("UTF-8")),
                    host == null || host.getName() == null ? "umb1122" : host.getName());
            set(playerClass, player, "field_146106_i", "g", profile);
            Class<?> inv = Class.forName("net.minecraft.entity.player.InventoryPlayer", true, loader);
            java.lang.reflect.Constructor<?> inventoryCtor = null;
            for (java.lang.reflect.Constructor<?> c : inv.getConstructors())
                if (c.getParameterTypes().length == 1 && c.getParameterTypes()[0].isInstance(player)) { inventoryCtor = c; break; }
            if (inventoryCtor == null) throw new NoSuchMethodException("InventoryPlayer(EntityPlayer)");
            Object inventory = inventoryCtor.newInstance(player);
            set(playerClass, player, "field_71071_by", "bv", inventory);
            return player;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot allocate 1.12.2 EntityPlayer facade", t);
        }
    }

    public static void set(Class<?> owner, Object receiver, String named, String raw, Object value) throws Exception {
        Field f = field(owner, named, raw); if (f == null) throw new NoSuchFieldException(named + "/" + raw);
        f.setAccessible(true); f.set(receiver, value);
    }
    static Field field(Class<?> owner, String named, String raw) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) for (String n : new String[]{named, raw}) try {
            Field f = c.getDeclaredField(n); f.setAccessible(true); return f;
        } catch (NoSuchFieldException ignored) { }
        return null;
    }
    static Method method(Class<?> owner, String named, String raw, int arity) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods())
            if ((m.getName().equals(named) || m.getName().equals(raw)) && m.getParameterTypes().length == arity) {
                try { m.setAccessible(true); } catch (Throwable ignored) { } return m;
            }
        return null;
    }
    private static byte[] bytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "dev/umb/legacy1122/legacyside/Legacy1122PlayerFacadeGenerated", null,
                "net/minecraft/entity/player/EntityPlayerMP", null);
        MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/World;"
                        + "Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/management/ItemInWorldManager;)V", null, null);
        c.visitCode(); c.visitVarInsn(Opcodes.ALOAD, 0); c.visitVarInsn(Opcodes.ALOAD, 1);
        c.visitVarInsn(Opcodes.ALOAD, 2); c.visitVarInsn(Opcodes.ALOAD, 3);
        c.visitInsn(Opcodes.ACONST_NULL); c.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "net/minecraft/entity/player/EntityPlayerMP", "<init>",
                "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/World;"
                        + "Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/management/ItemInWorldManager;)V", false);
        c.visitInsn(Opcodes.RETURN); c.visitMaxs(0, 0); c.visitEnd();
        for (String n : new String[]{"func_175149_v", "func_184812_l_"}) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, n, "()Z", null, null);
            m.visitCode(); m.visitInsn(Opcodes.ICONST_0); m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0); m.visitEnd();
        }
        MethodVisitor gui = cw.visitMethod(Opcodes.ACC_PUBLIC, "openGui",
                "(Ljava/lang/Object;ILnet/minecraft/world/World;III)V", null, null);
        gui.visitCode(); gui.visitVarInsn(Opcodes.ALOAD, 0); gui.visitVarInsn(Opcodes.ALOAD, 1);
        gui.visitVarInsn(Opcodes.ILOAD, 2); gui.visitVarInsn(Opcodes.ALOAD, 3);
        gui.visitVarInsn(Opcodes.ILOAD, 4); gui.visitVarInsn(Opcodes.ILOAD, 5); gui.visitVarInsn(Opcodes.ILOAD, 6);
        gui.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/umb/legacy1122/legacyside/Legacy1122PlayerFacadeRaw",
                "openGui", "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;III)V", false);
        gui.visitInsn(Opcodes.RETURN); gui.visitMaxs(0, 0); gui.visitEnd();
        cw.visitEnd(); return cw.toByteArray();
    }
    private static final class Definer extends ClassLoader {
        private final ProtectionDomain domain;
        Definer(ClassLoader p, ProtectionDomain d) { super(p); domain = d; }
        Class<?> define(byte[] b) { return defineClass("dev.umb.legacy1122.legacyside.Legacy1122PlayerFacadeGenerated", b, 0, b.length, domain); }
    }
}
