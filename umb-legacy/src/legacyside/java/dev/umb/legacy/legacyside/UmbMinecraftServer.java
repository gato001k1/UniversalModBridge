package dev.umb.legacy.legacyside;

import net.minecraft.command.CommandHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;

/**
 * E step 2/4: a minimal facade so {@link LegacyBridgeImpl#boot} can fire the ONE real FML lifecycle event HBM (and, in general, many Forge 1.7.10 mods) rely on for world-scoped static setup that never runs during {@code CONSTRUCTING}/{@code PREINIT}/{@code...
 */
final class UmbMinecraftServer {

    private UmbMinecraftServer() {
    }

    static MinecraftServer create(UmbWorld world) {
        IntegratedServer s = UmbUnsafe.allocate(IntegratedServer.class);
        UmbUnsafe.setField(s, UmbUnsafe.field(MinecraftServer.class, "field_71305_c"),
                new WorldServer[] {world});
        UmbUnsafe.setField(s, UmbUnsafe.field(MinecraftServer.class, "field_71321_q"),
                new CommandHandler());
        // Mods use MinecraftServer.func_71254_M() as the universal save-format
        // service locator while constructing tiles.  The isolated universe has
        // no disk-backed worlds, but returning null here makes those otherwise
        // valid constructions fail before the mod can open its GUI.
        UmbUnsafe.setField(s, UmbUnsafe.field(MinecraftServer.class, "field_71310_m"),
                new UmbSaveFormat());
        // Universal ctor replay, part 3 : the safe remainder of
        // MinecraftServer(File,Proxy,DataFixer...) — collections, helpers, constants.
        // Unavailable by design and left null (each documented): the network system
        // (would bind sockets), the save converter + server file (real disk IO),
        // Yggdrasil/session/profile-cache (online auth). Nothing in-universe reads them;
        // every live reader so far needed only the worlds array, the command manager,
        // and the static instance below.
        try {
            Class<?> server = MinecraftServer.class;
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_71307_n"),
                    new net.minecraft.profiler.PlayerUsageSnooper("server", s,
                            System.currentTimeMillis()));
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_71322_p"),
                    new java.util.ArrayList<Object>());
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_71304_b"),
                    new net.minecraft.profiler.Profiler());
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_147147_p"),
                    new net.minecraft.network.ServerStatusResponse());
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_147146_q"),
                    new java.util.Random());
            UmbUnsafe.setInt(s, UmbUnsafe.field(server, "field_71319_s"), -1);
            UmbUnsafe.setBoolean(s, UmbUnsafe.field(server, "field_71317_u"), true);
            UmbUnsafe.setInt(s, UmbUnsafe.field(server, "field_143008_E"), 0);
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_71311_j"), new long[100]);
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "worldTickTimes"),
                    new java.util.Hashtable<Object, Object>());
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_147141_M"), "");
            UmbUnsafe.setLong(s, UmbUnsafe.field(server, "field_147142_T"), 0L);
            UmbUnsafe.setField(s, UmbUnsafe.field(server, "field_110456_c"),
                    java.net.Proxy.NO_PROXY);
        } catch (Throwable t) {
            world.host().log("[UMB] UmbMinecraftServer seed failed (non-fatal): " + t);
        }
        // MinecraftServer's real constructor does `mcServer = this` (static field_71309_l, read by
        // getServer()/func_71276_C). The Unsafe allocation skipped it, so every mod calling
        // MinecraftServer.getServer() - e.g. FML ServerTickEvent handlers - NPE'd every tick once
        // tickEvents actually started reaching the universe (2026-09-24).
        try {
            java.lang.reflect.Field instance = MinecraftServer.class.getDeclaredField("field_71309_l");
            instance.setAccessible(true);
            if (instance.get(null) == null) instance.set(null, s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot seed MinecraftServer.field_71309_l (mcServer)", e);
        }
        return s;
    }
}
