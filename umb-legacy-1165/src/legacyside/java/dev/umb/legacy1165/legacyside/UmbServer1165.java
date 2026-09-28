package dev.umb.legacy1165.legacyside;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import net.minecraft.command.Commands;
import net.minecraft.resources.DataPackRegistries;
import net.minecraft.resources.IResourcePack;
import net.minecraft.resources.FolderPack;
import net.minecraft.resources.VanillaPack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.packs.ModFileResourcePack;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

/**
 * Narrow generic server facade for mod callbacks that load server-data resources. It does
 * not start a server, bind sockets, create worlds, or tick; it only exposes the real child
 * resource manager populated from the discovered mod packs.
 */
final class UmbServer1165 {
    private UmbServer1165() {
    }

    static MinecraftServer create(List<ModFile> modFiles, Consumer<String> log) {
        return create(modFiles, java.util.Collections.<java.io.File>emptyList(), log);
    }

    static MinecraftServer create(List<ModFile> modFiles, List<java.io.File> dataPacks,
            Consumer<String> log) {
        try {
            List<IResourcePack> packs = new ArrayList<IResourcePack>();
            // ServerPackFinder installs this exact built-in pack before enabled mod packs.
            // Without it, the reload sees mod data but no minecraft:block tag definitions.
            packs.add(new VanillaPack("minecraft"));
            for (ModFile modFile : modFiles) {
                packs.add(new ModFileResourcePack(modFile));
            }
            if (dataPacks != null) {
                for (java.io.File dataPack : dataPacks) {
                    if (dataPack != null && dataPack.isDirectory()) {
                        packs.add(new FolderPack(dataPack));
                    }
                }
            }
            // Do not use the lifecycle thread as an executor and do not wait forever. Vanilla's
            // reload graph can schedule completion on its apply executor; a direct executor can
            // make the lifecycle thread wait for work that only it can run. Dedicated daemon
            // workers keep this facade independent and make a broken pack fail clearly.
            ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(
                    named("umb-1165-datapack-reload"));
            ExecutorService applyExecutor = Executors.newSingleThreadExecutor(
                    named("umb-1165-datapack-apply"));
            DataPackRegistries registries;
            int modReloadListeners = 0;
            try {
                // The vanilla helper only installs the six built-in listeners. Forge mods add
                // their own data-driven caches through AddReloadListenerEvent (for example a
                // machine recipe index); omitting that event leaves World.getRecipeManager()
                // populated while the mod's own lookup cache remains empty. Construct the same
                // registry holder, publish the event, and attach every listener before the
                // single reload so each mod owns its normal reload lifecycle.
                DataPackRegistries pending = new DataPackRegistries(
                        Commands.EnvironmentType.DEDICATED, 0);
                net.minecraftforge.event.AddReloadListenerEvent event =
                        new net.minecraftforge.event.AddReloadListenerEvent(pending);
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
                // Some child-loader boots expose the real mod objects but not the Forge bus
                // registrations to this resource-facade call.  Replay the public lifecycle
                // callbacks generically in that case; this preserves each mod's own reload
                // implementation without naming or depending on any particular mod.
                if (event.getListeners().isEmpty()) {
                    modReloadListeners += invokeMissingReloadListeners(event);
                }
                net.minecraft.resources.IReloadableResourceManager resourceManager =
                        (net.minecraft.resources.IReloadableResourceManager) pending.func_240970_h_();
                List<net.minecraft.resources.IResourceManagerReloadListener> directReloadListeners =
                        new ArrayList<net.minecraft.resources.IResourceManagerReloadListener>();
                for (net.minecraft.resources.IFutureReloadListener listener : event.getListeners()) {
                    modReloadListeners++;
                    // The child runtime replaces IResourceManagerReloadListener's default
                    // IFutureReloadListener adapter with a Mixin stub.  Registering such a
                    // listener with AsyncReloader therefore fails before the mod callback runs.
                    // Run its real, synchronous reload contract after the vanilla graph has
                    // loaded instead; IFutureReloadListener implementations remain on the
                    // normal asynchronous path.
                    if (listener instanceof net.minecraft.resources.IResourceManagerReloadListener) {
                        directReloadListeners.add(
                                (net.minecraft.resources.IResourceManagerReloadListener) listener);
                    } else {
                        resourceManager.func_219534_a(listener);
                    }
                }
                CompletableFuture<net.minecraft.util.Unit> loaded = resourceManager.func_219536_a(
                        reloadExecutor, applyExecutor, packs,
                        CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE));
                try {
                    loaded.get(30L, TimeUnit.SECONDS);
                    for (net.minecraft.resources.IResourceManagerReloadListener listener
                            : directReloadListeners) {
                        try {
                            listener.func_195410_a(resourceManager);
                        } catch (Throwable failure) {
                            throw new IllegalStateException(
                                    "1.16.5 direct resource reload listener failed", failure);
                        }
                    }
                    registries = pending;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    loaded.cancel(true);
                    throw new IllegalStateException(
                            "1.16.5 data-pack reload interrupted", e);
                } catch (TimeoutException e) {
                    loaded.cancel(true);
                    throw new IllegalStateException(
                            "1.16.5 data-pack reload timed out after 30 seconds", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new IllegalStateException(
                            "1.16.5 data-pack reload failed: " + cause, cause);
                }
            } finally {
                applyExecutor.shutdownNow();
                reloadExecutor.shutdownNow();
            }

            // DataPackRegistries publishes the reloaded collections to TagCollectionManager,
            // but the vanilla server also performs this second step before callbacks can use
            // BlockTags/ItemTags/etc.  Without it, a real mod hook that asks a BlockState about
            // a Forge tag fails with "used before it was bound" even though reload succeeded.
            registries.func_240971_i_();

            // DedicatedServer is concrete, so Unsafe can allocate it without entering the
            // production constructor (which would bind sockets and require a real save).
            DedicatedServer server = UmbUnsafe1165.allocate(DedicatedServer.class);
            UmbUnsafe1165.setField(server,
                    UmbUnsafe1165.field(MinecraftServer.class, "field_195576_ac"), registries);

            // Forge APIs such as ServerLifecycleHooks use this same child-universe identity.
            if (ServerLifecycleHooks.getCurrentServer() == null) {
                UmbUnsafe1165.setStaticField(
                        UmbUnsafe1165.field(ServerLifecycleHooks.class, "currentServer"), server);
            }
            log.accept("[lifecycle] headless server resource facade packs=" + modFiles.size()
                    + " generatedDataPacks=" + (dataPacks == null ? 0 : dataPacks.size())
                    + " modReloadListeners=" + modReloadListeners);
            return server;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create headless server resource facade", t);
        }
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static int invokeMissingReloadListeners(
            net.minecraftforge.event.AddReloadListenerEvent event) {
        final int[] invoked = new int[] {0};
        net.minecraftforge.fml.ModList.get().forEachModContainer(
                (ignoredId, container) -> {
                    Object mod = container.getMod();
                    if (mod == null) {
                        return;
                    }
                    java.lang.reflect.Method[] methods = mod.getClass().getMethods();
                    java.util.Arrays.sort(methods, (left, right) -> {
                        int byName = left.getName().compareTo(right.getName());
                        return byName != 0 ? byName
                                : left.toGenericString().compareTo(right.toGenericString());
                    });
                    for (java.lang.reflect.Method method : methods) {
                        Class<?>[] parameters = method.getParameterTypes();
                        if (parameters.length != 1
                                || parameters[0]
                                        != net.minecraftforge.event.AddReloadListenerEvent.class) {
                            continue;
                        }
                        try {
                            method.invoke(mod, event);
                            invoked[0]++;
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                            throw new IllegalStateException(
                                    "1.16.5 mod reload listener failed", cause);
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(
                                    "1.16.5 mod reload listener could not be invoked", failure);
                        }
                    }
                });
        return invoked[0];
    }
}
