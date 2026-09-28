package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.IFMLSidedHandler;
import net.minecraft.command.CommandHandler;
import net.minecraft.server.MinecraftServer;

/** Focused regression coverage for the FML sided-delegate/server facade handshake. */
class LegacyLifecycleTest {

    @Test
    void sidedHandlerReturnsFacadeAndFacadeOwnsRealCommandManager() {
        MinecraftServer server = UmbMinecraftServer.create(null);
        UmbSidedHandler handler = new UmbSidedHandler(new File("build/test-saves"));
        handler.bindServer(server);

        IFMLSidedHandler sided = handler;
        assertSame(server, sided.getServer());
        assertTrue(server.func_71187_D() instanceof CommandHandler);
        assertEquals(CommandHandler.class, server.func_71187_D().getClass());
    }
}
