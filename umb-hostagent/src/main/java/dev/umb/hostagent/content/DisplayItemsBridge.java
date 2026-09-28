package dev.umb.hostagent.content;

import dev.umb.hostagent.AgentLog;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.ItemLike;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Builds a CreativeModeTab.DisplayItemsGenerator without ever naming CreativeModeTab.Output.
 *
 * Output is declared {@code protected static} in CreativeModeTab's InnerClasses attribute, so
 * javac refuses to let outside code name it - which rules out the obvious
 * {@code (params, output) -> ...} lambda. The interface's own class file is ACC_PUBLIC though,
 * so a java.lang.reflect.Proxy over the (public) DisplayItemsGenerator interface can accept the
 * argument as Object and call Output.accept(ItemLike) reflectively.
 */
final class DisplayItemsBridge {

    private static final String OUTPUT = "net.minecraft.world.item.CreativeModeTab$Output";
    private static final String GENERATOR = "net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator";

    private DisplayItemsBridge() {
    }

    static CreativeModeTab.DisplayItemsGenerator of(List<ItemLike> items) throws Exception {
        ClassLoader cl = CreativeModeTab.class.getClassLoader();
        Class<?> generatorClass = Class.forName(GENERATOR, false, cl);
        Class<?> outputClass = Class.forName(OUTPUT, false, cl);
        final Method accept = outputClass.getMethod("accept", ItemLike.class);
        try {
            accept.setAccessible(true);
        } catch (RuntimeException ignored) {
            // public interface method; setAccessible is only belt-and-braces
        }

        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("accept".equals(name) && args != null && args.length == 2 && args[1] != null) {
                    int failed = 0;
                    for (ItemLike il : items) {
                        try {
                            accept.invoke(args[1], il);
                        } catch (Throwable t) {
                            failed++;
                        }
                    }
                    if (failed > 0) AgentLog.line("tab display: " + failed + " entries rejected");
                    return null;
                }
                if ("toString".equals(name)) return "UmbDisplayItems(" + items.size() + ")";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                return null;
            }
        };
        return (CreativeModeTab.DisplayItemsGenerator)
                Proxy.newProxyInstance(cl, new Class<?>[]{generatorClass}, h);
    }
}
