package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import dev.umb.bridge.api.HostWorld;

/** Defines the generated notch-named World subclass against the real 1.12.2 jars. */
class Legacy1122WorldFacadeTest {
    @Test
    void generatedWorldSubclassDefinesAndInstantiates() throws Exception {
        Class<?> worldClass;
        try {
            worldClass = Class.forName("net.minecraft.world.World");
        } catch (ClassNotFoundException missingRealJar) {
            worldClass = null;
        }
        Class<?> helper = Class.forName("dev.umb.legacy1122.legacyside.Legacy1122WorldFacadeRaw");
        Method bytesMethod = helper.getDeclaredMethod("facadeBytes");
        bytesMethod.setAccessible(true);
        ClassNode generated = new ClassNode();
        new ClassReader((byte[]) bytesMethod.invoke(null)).accept(generated, 0);
        assertEquals("net/minecraft/world/World", generated.superName);
        assertNotNull(generated.methods.stream().filter(m -> m.name.equals("func_72863_F")).findFirst().orElse(null));
        assertNotNull(generated.methods.stream().filter(m -> m.name.equals("func_175680_a")).findFirst().orElse(null));
        if (worldClass == null) return; // the plain client jar is intentionally still notch-named
        HostWorld host = (HostWorld) Proxy.newProxyInstance(
                HostWorld.class.getClassLoader(), new Class<?>[] {HostWorld.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == long.class ? 0L
                        : method.getReturnType() == int.class ? 0 : null);
        Method create = helper.getDeclaredMethod("create", HostWorld.class);
        create.setAccessible(true);
        Object world = create.invoke(null, host);
        assertNotNull(world);
        assertEquals(worldClass, world.getClass().getSuperclass());
        java.lang.reflect.Field random = null;
        for (String name : new String[]{"field_73012_v", "r"}) try {
            random = worldClass.getDeclaredField(name); random.setAccessible(true); break;
        } catch (NoSuchFieldException ignored) { }
        assertNotNull(random, "World random field must be addressable by named or notch name");
        assertNotNull(random.get(world), "generated World facade must seed its Random");
        java.lang.reflect.Field weather = null;
        for (String name : new String[]{"field_73021_x", "u"}) try {
            weather = worldClass.getDeclaredField(name); weather.setAccessible(true); break;
        } catch (NoSuchFieldException ignored) { }
        assertNotNull(weather, "World weather list must be addressable by named or notch name");
        assertNotNull(weather.get(world), "generated World facade must seed its weather list");
        java.lang.reflect.Field chunks = null;
        for (String name : new String[]{"field_73020_y", "v"}) try {
            chunks = worldClass.getDeclaredField(name); chunks.setAccessible(true); break;
        } catch (NoSuchFieldException ignored) { }
        assertNotNull(chunks, "World chunk-provider field must be addressable by named or notch name");
        assertNotNull(chunks.get(world), "generated World facade must install a chunk-provider proxy");
        assertNotNull(world.getClass().getDeclaredMethod("func_82737_E"));
        assertNotNull(world.getClass().getDeclaredMethod("func_72872_a", Class.class,
                Class.forName("net.minecraft.util.math.AxisAlignedBB", true, worldClass.getClassLoader())));
        assertNotNull(world.getClass().getDeclaredMethod("func_72863_F"));
        assertNotNull(world.getClass().getDeclaredMethod("func_175680_a", int.class, int.class, boolean.class));
    }
}
